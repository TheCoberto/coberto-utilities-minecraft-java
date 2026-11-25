package com.thecoberto.utilities.music;

import com.thecoberto.utilities.CobertoUtilities;

import com.thecoberto.utilities.Sounds;

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
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * Music Selector: players use the block to pay Money and queue a song at that
 * selector. Each selector has its own queue and plays like a jukebox, heard
 * only nearby, with a floating "Now Playing" label above it. Each player waits
 * a while between songs. Operators change the price, cooldown, which songs can
 * be queued and the autoplay loop with /music (or Settings in the menu).
 */
public final class MusicSelector {
	private MusicSelector() {}

	public static final Block BLOCK = new MusicSelectorBlock(BlockBehaviour.Properties.of()
			.setId(ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("coberto", "music_selector")))
			.mapColor(MapColor.DIRT)
			.strength(2.0f, 3_600_000.0f) // explosions can't break it
			.sound(SoundType.WOOD)
			.lightLevel(state -> 4));
	public static BlockEntityType<MusicSelectorBlockEntity> BLOCK_ENTITY;

	/** Selectors loaded right now, for /musicskip and the settings that affect every selector. */
	static final java.util.Set<MusicSelectorBlockEntity> LOADED = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

	public static void registerBlocks() {
		Identifier id = Identifier.fromNamespaceAndPath("coberto", "music_selector");
		Registry.register(BuiltInRegistries.BLOCK, id, BLOCK);
		Item item = Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(BLOCK, new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id)).useBlockDescriptionPrefix()));
		BLOCK_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id,
				new BlockEntityType<>(MusicSelectorBlockEntity::new, java.util.Set.of(BLOCK)));
		CreativeModeTabEvents.modifyOutputEvent(CobertoUtilities.FUNCTIONAL_BLOCKS).register(output -> output.accept(item));
	}

	public static void registerEvents() {
		MusicMenu.registerEvents();
		// Each world can have its own data pack songs.
		ServerLifecycleEvents.SERVER_STARTING.register(server -> Track.clearCache());
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> Track.clearCache());
	}

	// ---------- Queueing ----------

	static int cooldownLeft(ServerPlayer player) {
		Long until = MusicState.get(player.level().getServer()).cooldowns.get(player.getUUID());
		return until == null ? 0 : (int) Math.max(0, Math.ceil((until - System.currentTimeMillis()) / 1000.0));
	}

	static void queueSong(ServerPlayer player, MusicSelectorBlockEntity station, Track track) {
		MusicState s = MusicState.get(player.level().getServer());
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
		s.cooldowns.put(player.getUUID(), System.currentTimeMillis() + s.cooldown * 1000L);
		s.setDirty();

		// A song someone paid for interrupts the autoplay loop.
		boolean startNow = station.current == null || station.current.by() == null;
		station.queue.add(new MusicSelectorBlockEntity.Entry(track.id(), player.getName().getString()));
		if (startNow) {
			station.playNext();
		} else {
			station.tellNearby(prefix().append(player.getName().getString() + " queued ")
					.append(Component.literal(track.name()).withStyle(ChatFormatting.AQUA))
					.append(" (#" + station.queue.size() + " in line)."));
			station.updateLabel();
		}
		Sounds.play(player, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1.0f, 1.0f);
	}

	private static void error(ServerPlayer player, String message) {
		player.sendSystemMessage(prefix().append(Component.literal(message).withStyle(ChatFormatting.RED)));
	}

	// ---------- Commands ----------

	private static final SuggestionProvider<CommandSourceStack> SONGS = (c, b) ->
			SharedSuggestionProvider.suggest(Track.all(c.getSource().getServer()).keySet(), b);

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("music")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(c -> showSettings(c.getSource()))
				.then(Commands.literal("price").then(Commands.argument("money", IntegerArgumentType.integer(0, Money.MAX_AMOUNT))
						.executes(c -> change(c, s -> s.price = IntegerArgumentType.getInteger(c, "money"), true))))
				.then(Commands.literal("cooldown").then(Commands.argument("seconds", IntegerArgumentType.integer(0, 86400))
						.executes(c -> change(c, s -> s.cooldown = IntegerArgumentType.getInteger(c, "seconds"), true))))
				.then(Commands.literal("maxqueue").then(Commands.argument("songs", IntegerArgumentType.integer(1, 20))
						.executes(c -> change(c, s -> s.maxQueue = IntegerArgumentType.getInteger(c, "songs"), true))))
				.then(Commands.literal("autoplay").then(Commands.argument("on", BoolArgumentType.bool())
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
				.then(Commands.literal("shuffle").then(Commands.argument("on", BoolArgumentType.bool())
						.executes(c -> change(c, s -> s.shuffle = BoolArgumentType.getBool(c, "on"), true))))
				.then(Commands.literal("songs").executes(c -> showSongs(c.getSource())))
				.then(Commands.literal("allow").then(Commands.argument("song", StringArgumentType.string()).suggests(SONGS)
						.then(Commands.argument("on", BoolArgumentType.bool())
								.executes(c -> changeSong(c, (s, id) -> toggle(s.allowed, id, BoolArgumentType.getBool(c, "on")))))))
				.then(Commands.literal("playlist").then(Commands.argument("song", StringArgumentType.string()).suggests(SONGS)
						.then(Commands.argument("on", BoolArgumentType.bool())
								.executes(c -> changeSong(c, (s, id) -> toggle(s.playlist, id, BoolArgumentType.getBool(c, "on")))))))
				.then(Commands.literal("songprice").then(Commands.argument("song", StringArgumentType.string()).suggests(SONGS)
						.then(Commands.literal("default").executes(c -> changeSong(c, (s, id) -> s.prices.remove(id))))
						.then(Commands.argument("money", IntegerArgumentType.integer(0, Money.MAX_AMOUNT))
								.executes(c -> changeSong(c, (s, id) -> s.prices.put(id, IntegerArgumentType.getInteger(c, "money")))))))
				.then(Commands.literal("stopall").executes(c -> {
					for (var station : LOADED) station.clear();
					c.getSource().sendSuccess(() -> prefix().append("Stopped every loaded selector."), true);
					return 1;
				})));

		dispatcher.register(Commands.literal("musicskip")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(c -> {
					CommandSourceStack source = c.getSource();
					Vec3 from = source.getPosition();
					var nearest = LOADED.stream()
							.filter(st -> !st.isRemoved() && st.getLevel() == source.getLevel() && st.current != null)
							.filter(st -> Vec3.atCenterOf(st.getBlockPos()).distanceToSqr(from) <= 64 * 64)
							.min(Comparator.comparingDouble(st -> Vec3.atCenterOf(st.getBlockPos()).distanceToSqr(from)));
					if (nearest.isEmpty()) {
						source.sendFailure(Component.literal("No Music Selector within 64 blocks is playing a song."));
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

	private static int change(CommandContext<CommandSourceStack> c, Consumer<MusicState> change, boolean show) {
		MusicState s = MusicState.get(c.getSource().getServer());
		change.accept(s);
		s.setDirty();
		if (show) showSettings(c.getSource());
		return 1;
	}

	private static int changeSong(CommandContext<CommandSourceStack> c, java.util.function.BiConsumer<MusicState, String> change) {
		String id = StringArgumentType.getString(c, "song");
		if (!Track.all(c.getSource().getServer()).containsKey(id)) {
			c.getSource().sendFailure(Component.literal("No song called " + id + ". Use /music songs to see them."));
			return 0;
		}
		MusicState s = MusicState.get(c.getSource().getServer());
		change.accept(s, id);
		s.setDirty();
		return showSongs(c.getSource());
	}

	static int showSettings(CommandSourceStack source) {
		MusicState s = MusicState.get(source.getServer());
		int total = Track.all(source.getServer()).size();
		MutableComponent text = Component.empty().append(Component.literal("Music Settings").withStyle(ChatFormatting.GOLD))
				.append(" (every Music Selector; each keeps its own queue)")
				.append("\n Price: ").append(Component.literal(Money.format(s.price) + " Money").withStyle(ChatFormatting.GREEN)).append(" per song ")
				.append(button("[Change]", "/music price " + s.price, false))
				.append("\n Cooldown: " + formatTime(s.cooldown) + " between a player's songs ")
				.append(button("[Change]", "/music cooldown " + s.cooldown, false))
				.append("\n Queue: up to " + s.maxQueue + " songs per selector ")
				.append(button("[Change]", "/music maxqueue " + s.maxQueue, false))
				.append("\n Songs players can queue: " + s.allowed.size() + "/" + total + " ")
				.append(button("[Songs and prices]", "/music songs", true))
				.append("\n Autoplay: ")
				.append(s.autoplay ? Component.literal("on").withStyle(ChatFormatting.GREEN) : Component.literal("off").withStyle(ChatFormatting.RED))
				.append(s.autoplay ? " (" + s.playlist.size() + " songs" + (s.shuffle ? ", shuffled" : "") + ")" : "")
				.append(" ").append(button(s.autoplay ? "[Turn off]" : "[Turn on]", "/music autoplay " + !s.autoplay, true))
				.append(" ").append(button(s.shuffle ? "[Shuffle: on]" : "[Shuffle: off]", "/music shuffle " + !s.shuffle, true))
				.append("\n ").append(button("[Stop every selector]", "/music stopall", true).withStyle(ChatFormatting.RED));
		source.sendSuccess(() -> text, false);
		return 1;
	}

	private static int showSongs(CommandSourceStack source) {
		MusicState s = MusicState.get(source.getServer());
		MutableComponent text = Component.empty().append(Component.literal("Songs").withStyle(ChatFormatting.GOLD))
				.append(": click to change. ").append(Component.literal("Queue").withStyle(ChatFormatting.AQUA))
				.append(" = players can queue it, ").append(Component.literal("Auto").withStyle(ChatFormatting.AQUA))
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
					.append(" ").append(Component.literal(e.getValue().name()).withStyle(ChatFormatting.WHITE))
					.append(" ").append(button("[" + Money.format(s.priceOf(id)) + (ownPrice ? "" : " (default)") + "]",
							"/music songprice " + arg + " " + s.priceOf(id), false).withStyle(ChatFormatting.GREEN));
		}
		text.append(Component.literal("\n Song prices: type a number, or \"default\" to use the normal price.").withStyle(ChatFormatting.GRAY));
		source.sendSuccess(() -> text, false);
		return 1;
	}

	private static MutableComponent toggleButton(String label, boolean on, String command) {
		return Component.literal("[" + (on ? "✔ " : "✘ ") + label + "]").withStyle(s -> s
				.withColor(on ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY)
				.withClickEvent(new ClickEvent.RunCommand(command))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
	}

	private static MutableComponent button(String label, String command, boolean run) {
		return Component.literal(label).withStyle(s -> s.withColor(ChatFormatting.YELLOW)
				.withClickEvent((run ? new ClickEvent.RunCommand(command) : new ClickEvent.SuggestCommand(command)))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
	}

	static String formatTime(int seconds) {
		return seconds / 60 + ":" + String.format("%02d", seconds % 60);
	}

	static MutableComponent prefix() {
		return Component.empty().append(Component.literal("Music:").withStyle(ChatFormatting.LIGHT_PURPLE)).append(" ");
	}
}
