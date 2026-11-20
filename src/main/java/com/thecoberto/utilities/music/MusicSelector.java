package com.thecoberto.utilities.music;

import java.util.Comparator;
import java.util.Map;
import java.util.function.Consumer;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.thecoberto.utilities.Money;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.command.CommandSource;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

/**
 * Music Selector: players use the block to pay Money and queue a song at that
 * selector. Each selector has its own queue and plays like a jukebox, heard
 * only nearby, with a floating "Now Playing" label above it. Each player waits
 * a while between songs. Operators change the price, cooldown, which songs can
 * be queued and the autoplay loop with /music (or Settings in the menu).
 */
public final class MusicSelector {
	private MusicSelector() {}

	public static final Block BLOCK = new MusicSelectorBlock(AbstractBlock.Settings.create()
			.mapColor(MapColor.DIRT_BROWN)
			.strength(2.0f, 3_600_000.0f) // explosions can't break it
			.sounds(BlockSoundGroup.WOOD)
			.luminance(state -> 4));
	public static BlockEntityType<MusicSelectorBlockEntity> BLOCK_ENTITY;

	/** Selectors loaded right now, for /musicskip and the settings that affect every selector. */
	static final java.util.Set<MusicSelectorBlockEntity> LOADED = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

	public static void registerBlocks() {
		Identifier id = Identifier.of("coberto", "music_selector");
		Registry.register(Registries.BLOCK, id, BLOCK);
		Item item = Registry.register(Registries.ITEM, id, new BlockItem(BLOCK, new Item.Settings()));
		BLOCK_ENTITY = Registry.register(Registries.BLOCK_ENTITY_TYPE, id,
				BlockEntityType.Builder.create(MusicSelectorBlockEntity::new, BLOCK).build(null));
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> entries.add(item));
	}

	public static void registerEvents() {
		MusicMenu.registerEvents();
		// Each world can have its own data pack songs.
		ServerLifecycleEvents.SERVER_STARTING.register(server -> Track.clearCache());
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> Track.clearCache());
	}

	// ---------- Queueing ----------

	static int cooldownLeft(ServerPlayerEntity player) {
		Long until = MusicState.get(player.server).cooldowns.get(player.getUuid());
		return until == null ? 0 : (int) Math.max(0, Math.ceil((until - System.currentTimeMillis()) / 1000.0));
	}

	static void queueSong(ServerPlayerEntity player, MusicSelectorBlockEntity station, Track track) {
		MusicState s = MusicState.get(player.server);
		if (!s.allowed.contains(track.id())) {
			error(player, track.name() + " can't be queued.");
			return;
		}
		int wait = cooldownLeft(player);
		if (wait > 0) {
			error(player, "Wait " + formatTime(wait) + " before queueing another song.");
			return;
		}
		if (station.queue.size() >= s.maxQueue) {
			error(player, "This queue is full (" + s.maxQueue + " songs). Try again later.");
			return;
		}
		boolean queued = station.queue.stream().anyMatch(q -> q.id().equals(track.id()))
				|| (station.current != null && station.current.by() != null && station.current.id().equals(track.id()));
		if (queued) {
			error(player, track.name() + " is already playing or queued here.");
			return;
		}
		int price = s.priceOf(track.id());
		int money = Money.get(player);
		if (money < price) {
			error(player, "You need " + Money.format(price) + " Money (you have " + Money.format(money) + ").");
			return;
		}

		if (price > 0) Money.add(player, -price);
		s.cooldowns.put(player.getUuid(), System.currentTimeMillis() + s.cooldown * 1000L);
		s.markDirty();

		// A song someone paid for interrupts the autoplay loop.
		boolean startNow = station.current == null || station.current.by() == null;
		station.queue.add(new MusicSelectorBlockEntity.Entry(track.id(), player.getName().getString()));
		if (startNow) {
			station.playNext();
		} else {
			station.tellNearby(prefix().append(player.getName().getString() + " queued ")
					.append(Text.literal(track.name()).formatted(Formatting.AQUA))
					.append(" (#" + station.queue.size() + " in line)."));
			station.updateLabel();
		}
		player.playSoundToPlayer(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 1.0f, 1.0f);
	}

	private static void error(ServerPlayerEntity player, String message) {
		player.sendMessage(prefix().append(Text.literal(message).formatted(Formatting.RED)));
	}

	// ---------- Commands ----------

	private static final SuggestionProvider<ServerCommandSource> SONGS = (c, b) ->
			CommandSource.suggestMatching(Track.all(c.getSource().getServer()).keySet(), b);

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("music")
				.requires(s -> s.hasPermissionLevel(2))
				.executes(c -> showSettings(c.getSource()))
				.then(CommandManager.literal("price").then(CommandManager.argument("money", IntegerArgumentType.integer(0, Money.MAX_AMOUNT))
						.executes(c -> change(c, s -> s.price = IntegerArgumentType.getInteger(c, "money"), true))))
				.then(CommandManager.literal("cooldown").then(CommandManager.argument("seconds", IntegerArgumentType.integer(0, 86400))
						.executes(c -> change(c, s -> s.cooldown = IntegerArgumentType.getInteger(c, "seconds"), true))))
				.then(CommandManager.literal("maxqueue").then(CommandManager.argument("songs", IntegerArgumentType.integer(1, 20))
						.executes(c -> change(c, s -> s.maxQueue = IntegerArgumentType.getInteger(c, "songs"), true))))
				.then(CommandManager.literal("autoplay").then(CommandManager.argument("on", BoolArgumentType.bool())
						.executes(c -> {
							boolean on = BoolArgumentType.getBool(c, "on");
							change(c, s -> s.autoplay = on, true);
							for (var station : LOADED) {
								station.resetAutoplay();
								if (!on && station.current != null && station.current.by() == null) {
									station.stopCurrent();
									station.updateLabel();
								}
							}
							return 1;
						})))
				.then(CommandManager.literal("shuffle").then(CommandManager.argument("on", BoolArgumentType.bool())
						.executes(c -> change(c, s -> s.shuffle = BoolArgumentType.getBool(c, "on"), true))))
				.then(CommandManager.literal("songs").executes(c -> showSongs(c.getSource())))
				.then(CommandManager.literal("allow").then(CommandManager.argument("song", StringArgumentType.string()).suggests(SONGS)
						.then(CommandManager.argument("on", BoolArgumentType.bool())
								.executes(c -> changeSong(c, (s, id) -> toggle(s.allowed, id, BoolArgumentType.getBool(c, "on")))))))
				.then(CommandManager.literal("playlist").then(CommandManager.argument("song", StringArgumentType.string()).suggests(SONGS)
						.then(CommandManager.argument("on", BoolArgumentType.bool())
								.executes(c -> changeSong(c, (s, id) -> toggle(s.playlist, id, BoolArgumentType.getBool(c, "on")))))))
				.then(CommandManager.literal("songprice").then(CommandManager.argument("song", StringArgumentType.string()).suggests(SONGS)
						.then(CommandManager.literal("default").executes(c -> changeSong(c, (s, id) -> s.prices.remove(id))))
						.then(CommandManager.argument("money", IntegerArgumentType.integer(0, Money.MAX_AMOUNT))
								.executes(c -> changeSong(c, (s, id) -> s.prices.put(id, IntegerArgumentType.getInteger(c, "money")))))))
				.then(CommandManager.literal("stopall").executes(c -> {
					for (var station : LOADED) station.clear();
					c.getSource().sendFeedback(() -> prefix().append("Stopped every loaded selector."), true);
					return 1;
				})));

		dispatcher.register(CommandManager.literal("musicskip")
				.requires(s -> s.hasPermissionLevel(2))
				.executes(c -> {
					ServerCommandSource source = c.getSource();
					Vec3d from = source.getPosition();
					var nearest = LOADED.stream()
							.filter(st -> !st.isRemoved() && st.getWorld() == source.getWorld() && st.current != null)
							.filter(st -> st.getPos().toCenterPos().squaredDistanceTo(from) <= 64 * 64)
							.min(Comparator.comparingDouble(st -> st.getPos().toCenterPos().squaredDistanceTo(from)));
					if (nearest.isEmpty()) {
						source.sendError(Text.literal("No Music Selector within 64 blocks is playing a song."));
						return 0;
					}
					nearest.get().skip();
					return 1;
				}));
	}

	private static void toggle(java.util.List<String> list, String id, boolean on) {
		list.remove(id);
		if (on) list.add(id);
	}

	private static int change(CommandContext<ServerCommandSource> c, Consumer<MusicState> change, boolean show) {
		MusicState s = MusicState.get(c.getSource().getServer());
		change.accept(s);
		s.markDirty();
		if (show) showSettings(c.getSource());
		return 1;
	}

	private static int changeSong(CommandContext<ServerCommandSource> c, java.util.function.BiConsumer<MusicState, String> change) {
		String id = StringArgumentType.getString(c, "song");
		if (!Track.all(c.getSource().getServer()).containsKey(id)) {
			c.getSource().sendError(Text.literal("No song called " + id + ". Use /music songs to see them."));
			return 0;
		}
		MusicState s = MusicState.get(c.getSource().getServer());
		change.accept(s, id);
		s.markDirty();
		return showSongs(c.getSource());
	}

	static int showSettings(ServerCommandSource source) {
		MusicState s = MusicState.get(source.getServer());
		int total = Track.all(source.getServer()).size();
		MutableText text = Text.empty().append(Text.literal("Music Settings").formatted(Formatting.GOLD))
				.append(" (every Music Selector; each keeps its own queue)")
				.append("\n Price: ").append(Text.literal(Money.format(s.price) + " Money").formatted(Formatting.GREEN)).append(" per song ")
				.append(button("[Change]", "/music price " + s.price, false))
				.append("\n Cooldown: " + formatTime(s.cooldown) + " between a player's songs ")
				.append(button("[Change]", "/music cooldown " + s.cooldown, false))
				.append("\n Queue: up to " + s.maxQueue + " songs per selector ")
				.append(button("[Change]", "/music maxqueue " + s.maxQueue, false))
				.append("\n Songs players can queue: " + s.allowed.size() + "/" + total + " ")
				.append(button("[Songs and prices]", "/music songs", true))
				.append("\n Autoplay: ")
				.append(s.autoplay ? Text.literal("on").formatted(Formatting.GREEN) : Text.literal("off").formatted(Formatting.RED))
				.append(s.autoplay ? " (" + s.playlist.size() + " songs" + (s.shuffle ? ", shuffled" : "") + ")" : "")
				.append(" ").append(button(s.autoplay ? "[Turn off]" : "[Turn on]", "/music autoplay " + !s.autoplay, true))
				.append(" ").append(button(s.shuffle ? "[Shuffle: on]" : "[Shuffle: off]", "/music shuffle " + !s.shuffle, true))
				.append("\n ").append(button("[Stop every selector]", "/music stopall", true).formatted(Formatting.RED));
		source.sendFeedback(() -> text, false);
		return 1;
	}

	private static int showSongs(ServerCommandSource source) {
		MusicState s = MusicState.get(source.getServer());
		MutableText text = Text.empty().append(Text.literal("Songs").formatted(Formatting.GOLD))
				.append(": click to change. ").append(Text.literal("Queue").formatted(Formatting.AQUA))
				.append(" = players can queue it, ").append(Text.literal("Auto").formatted(Formatting.AQUA))
				.append(" = in the autoplay loop. ").append(button("[Back]", "/music", true));
		for (Map.Entry<String, Track> e : Track.all(source.getServer()).entrySet()) {
			String id = e.getKey();
			String arg = id.contains(":") ? "\"" + id + "\"" : id;
			boolean allowed = s.allowed.contains(id);
			boolean inPlaylist = s.playlist.contains(id);
			boolean ownPrice = s.prices.containsKey(id);
			text.append("\n ")
					.append(toggleButton("Queue", allowed, "/music allow " + arg + " " + !allowed))
					.append(" ").append(toggleButton("Auto", inPlaylist, "/music playlist " + arg + " " + !inPlaylist))
					.append(" ").append(Text.literal(e.getValue().name()).formatted(Formatting.WHITE))
					.append(" ").append(button("[" + Money.format(s.priceOf(id)) + (ownPrice ? "" : " (default)") + "]",
							"/music songprice " + arg + " " + s.priceOf(id), false).formatted(Formatting.GREEN));
		}
		text.append(Text.literal("\n Song prices: type a number, or \"default\" to use the normal price.").formatted(Formatting.GRAY));
		source.sendFeedback(() -> text, false);
		return 1;
	}

	private static MutableText toggleButton(String label, boolean on, String command) {
		return Text.literal("[" + (on ? "✔ " : "✘ ") + label + "]").styled(s -> s
				.withColor(on ? Formatting.GREEN : Formatting.DARK_GRAY)
				.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(command))));
	}

	private static MutableText button(String label, String command, boolean run) {
		return Text.literal(label).styled(s -> s.withColor(Formatting.YELLOW)
				.withClickEvent(new ClickEvent(run ? ClickEvent.Action.RUN_COMMAND : ClickEvent.Action.SUGGEST_COMMAND, command))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(command))));
	}

	static String formatTime(int seconds) {
		return seconds / 60 + ":" + String.format("%02d", seconds % 60);
	}

	static MutableText prefix() {
		return Text.empty().append(Text.literal("Music:").formatted(Formatting.LIGHT_PURPLE)).append(" ");
	}
}
