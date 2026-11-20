package com.thecoberto.utilities.crates;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.ItemStackArgumentType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

/**
 * Loot crates: Crate, Spooky Crate and Holiday Crate blocks to hide around the
 * map for events. Opening one pops out random loot and the crate disappears;
 * breaking it gives nothing. Operators change what each crate drops with
 * /crates (saved in the world).
 */
public final class Crates {
	private Crates() {}

	/** A kind of crate. {@code key} is used in commands and the save file. */
	public record Kind(String key, String name, Formatting color, ParticleEffect particle, Block block) {}

	public static final Map<String, Kind> KINDS = new LinkedHashMap<>();

	private static void kind(String key, String name, Formatting color, ParticleEffect particle) {
		Block block = new CrateBlock(key, AbstractBlock.Settings.create()
				.mapColor(MapColor.BROWN)
				.strength(2.0f, 3_600_000.0f) // explosions can't break it
				.sounds(BlockSoundGroup.WOOD)
				.dropsNothing());
		KINDS.put(key, new Kind(key, name, color, particle, block));
	}

	static {
		kind("crate", "Crate", Formatting.GOLD, ParticleTypes.HAPPY_VILLAGER);
		kind("spooky_crate", "Spooky Crate", Formatting.DARK_PURPLE, ParticleTypes.SOUL);
		kind("holiday_crate", "Holiday Crate", Formatting.RED, ParticleTypes.TOTEM_OF_UNDYING);
	}

	public static void registerBlocks() {
		List<Item> items = new ArrayList<>();
		for (Kind kind : KINDS.values()) {
			Identifier id = Identifier.of("coberto", kind.key());
			Registry.register(Registries.BLOCK, id, kind.block());
			items.add(Registry.register(Registries.ITEM, id, new BlockItem(kind.block(), new Item.Settings())));
		}
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> items.forEach(entries::add));
	}

	// ---------- Opening a crate ----------

	static void open(ServerWorld world, BlockPos pos, ServerPlayerEntity player, String key) {
		Kind kind = KINDS.get(key);
		CratesState.Crate crate = CratesState.get(world.getServer()).crate(key);
		Vec3d center = Vec3d.ofCenter(pos);

		world.removeBlock(pos, false);
		world.playSound(null, pos, SoundEvents.BLOCK_CHEST_OPEN, SoundCategory.BLOCKS, 1.0f, 1.0f);
		world.playSound(null, pos, SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.BLOCKS, 0.6f, 1.4f);
		world.spawnParticles(kind.particle(), center.x, center.y, center.z, 20, 0.4, 0.4, 0.4, 0.05);

		List<Text> found = new ArrayList<>();
		for (CratesState.Drop drop : roll(crate, world.getRandom())) {
			Item item = Registries.ITEM.get(drop.item());
			if (item == Items.AIR) continue;
			int left = drop.amount();
			while (left > 0) {
				int count = Math.min(left, item.getMaxCount());
				ItemEntity entity = new ItemEntity(world, center.x, center.y, center.z, new ItemStack(item, count));
				entity.setToDefaultPickupDelay();
				world.spawnEntity(entity);
				left -= count;
			}
			found.add(Text.literal(drop.amount() + "x ").append(item.getName()));
			if (item == Items.ELYTRA || item == Items.HEAVY_CORE) {
				world.getServer().getPlayerManager().broadcast(Text.empty()
						.append(Text.literal(player.getName().getString() + " found ").formatted(Formatting.GOLD))
						.append(item.getName().copy().formatted(Formatting.LIGHT_PURPLE))
						.append(Text.literal(" in a " + kind.name() + "!").formatted(Formatting.GOLD)), false);
			}
		}

		MutableText message = Text.empty().append(Text.literal(kind.name() + ":").formatted(kind.color())).append(" You found ");
		if (found.isEmpty()) message.append("nothing... it was empty!");
		for (int i = 0; i < found.size(); i++) {
			if (i > 0) message.append(", ");
			message.append(found.get(i));
		}
		player.sendMessage(message);
	}

	private static List<CratesState.Drop> roll(CratesState.Crate crate, Random random) {
		List<CratesState.Drop> drops = new ArrayList<>();
		int total = crate.loot.stream().mapToInt(e -> Math.max(0, e.weight)).sum();
		if (total <= 0) return drops;
		int rolls = crate.rollsMin + random.nextInt(crate.rollsMax - crate.rollsMin + 1);
		for (int i = 0; i < rolls; i++) {
			int pick = random.nextInt(total);
			for (CratesState.Entry entry : crate.loot) {
				if (entry.weight <= 0) continue;
				pick -= entry.weight;
				if (pick < 0) {
					drops.add(new CratesState.Drop(entry.item, entry.min + random.nextInt(entry.max - entry.min + 1)));
					break;
				}
			}
		}
		return drops;
	}

	// ---------- Commands (/crates) ----------

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
		RequiredArgumentBuilder<ServerCommandSource, String> crate = CommandManager.argument("crate", StringArgumentType.word())
				.suggests((c, b) -> CommandSource.suggestMatching(KINDS.keySet(), b))
				.executes(c -> show(c.getSource(), kindArg(c)))
				// add <item> <weight> [min] [max]: adds the item, or changes it if the crate already has it
				.then(CommandManager.literal("add")
						.then(CommandManager.argument("item", ItemStackArgumentType.itemStack(registryAccess))
								.then(CommandManager.argument("weight", IntegerArgumentType.integer(0, 1_000_000))
										.executes(c -> add(c, 1, 1))
										.then(CommandManager.argument("min", IntegerArgumentType.integer(1, 10_000))
												.executes(c -> {
													int amount = IntegerArgumentType.getInteger(c, "min");
													return add(c, amount, amount);
												})
												.then(CommandManager.argument("max", IntegerArgumentType.integer(1, 10_000))
														.executes(c -> add(c, IntegerArgumentType.getInteger(c, "min"),
																IntegerArgumentType.getInteger(c, "max"))))))))
				.then(CommandManager.literal("remove")
						.then(CommandManager.argument("item", ItemStackArgumentType.itemStack(registryAccess))
								.executes(Crates::remove)))
				.then(CommandManager.literal("items")
						.then(CommandManager.argument("min", IntegerArgumentType.integer(1, 27))
								.then(CommandManager.argument("max", IntegerArgumentType.integer(1, 27))
										.executes(Crates::items))))
				.then(CommandManager.literal("reset").executes(c -> {
					Kind kind = kindArg(c);
					if (kind == null) return unknown(c.getSource());
					CratesState.get(c.getSource().getServer()).reset(kind.key());
					c.getSource().sendFeedback(() -> prefix().append(kind.name() + " reset to the default loot."), true);
					return 1;
				}));

		dispatcher.register(CommandManager.literal("crates")
				.requires(s -> s.hasPermissionLevel(2))
				.executes(c -> {
					MutableText text = Text.empty().append(Text.literal("Loot Crates").formatted(Formatting.GOLD))
							.append(": click one to see and change what it drops.");
					for (Kind kind : KINDS.values()) {
						text.append("\n ").append(Text.literal("[" + kind.name() + "]").styled(s -> s
								.withColor(kind.color())
								.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/crates " + kind.key()))
								.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("/crates " + kind.key())))));
					}
					c.getSource().sendFeedback(() -> text, false);
					return 1;
				})
				.then(crate));
	}

	private static Kind kindArg(CommandContext<ServerCommandSource> c) {
		return KINDS.get(StringArgumentType.getString(c, "crate"));
	}

	private static int show(ServerCommandSource source, Kind kind) {
		if (kind == null) return unknown(source);
		CratesState.Crate crate = CratesState.get(source.getServer()).crate(kind.key());
		String cmd = "/crates " + kind.key();
		MutableText text = Text.empty().append(Text.literal(kind.name()).formatted(kind.color()))
				.append(" gives " + crate.rollsMin + "-" + crate.rollsMax + " items when opened. Each item is picked by chance.");
		for (CratesState.Entry entry : crate.loot) {
			Item item = Registries.ITEM.get(entry.item);
			String amount = entry.min == entry.max ? "" + entry.min : entry.min + "-" + entry.max;
			text.append("\n ").append(Text.empty().append(item.getName()).append(" x" + amount)
					.styled(s -> s.withColor(Formatting.YELLOW)
							.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
									cmd + " add " + entry.item + " " + entry.weight + " " + entry.min + " " + entry.max))
							.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
									Text.literal("Click to change it: weight, then least and most amount")))))
					.append(Text.literal(" " + chance(crate, entry) + " each pick (weight " + entry.weight + ") ").formatted(Formatting.GRAY))
					.append(button("[x]", Formatting.RED, cmd + " remove " + entry.item, "Remove it", false));
		}
		text.append("\n ")
				.append(button("[+ Add item]", Formatting.DARK_GREEN, cmd + " add ", "/crates " + kind.key() + " add <item> <weight> [min] [max]", false))
				.append(" ")
				.append(button("[Items per crate]", Formatting.AQUA, cmd + " items " + crate.rollsMin + " " + crate.rollsMax, "/crates " + kind.key() + " items <least> <most>", false))
				.append(" ")
				.append(button("[Reset]", Formatting.RED, cmd + " reset", "Put the loot back to the defaults", false));
		source.sendFeedback(() -> text, false);
		return 1;
	}

	private static MutableText button(String label, Formatting color, String command, String hover, boolean run) {
		return Text.literal(label).styled(s -> s.withColor(color)
				.withClickEvent(new ClickEvent(run ? ClickEvent.Action.RUN_COMMAND : ClickEvent.Action.SUGGEST_COMMAND, command))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(hover))));
	}

	private static int add(CommandContext<ServerCommandSource> c, int min, int max) throws CommandSyntaxException {
		Kind kind = kindArg(c);
		if (kind == null) return unknown(c.getSource());
		if (min > max) {
			c.getSource().sendError(Text.literal("The least amount can't be more than the most."));
			return 0;
		}
		Item item = ItemStackArgumentType.getItemStackArgument(c, "item").getItem();
		Identifier id = Registries.ITEM.getId(item);
		int weight = IntegerArgumentType.getInteger(c, "weight");
		CratesState state = CratesState.get(c.getSource().getServer());
		CratesState.Crate crate = state.crate(kind.key());
		CratesState.Entry entry = crate.set(id, weight, min, max);
		state.markDirty();
		c.getSource().sendFeedback(() -> prefix().append(kind.name() + " now drops ").append(item.getName())
				.append(" (" + chance(crate, entry) + " each pick)."), true);
		return 1;
	}

	private static int remove(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
		Kind kind = kindArg(c);
		if (kind == null) return unknown(c.getSource());
		Item item = ItemStackArgumentType.getItemStackArgument(c, "item").getItem();
		CratesState state = CratesState.get(c.getSource().getServer());
		if (!state.crate(kind.key()).loot.removeIf(e -> e.item.equals(Registries.ITEM.getId(item)))) {
			c.getSource().sendError(Text.literal("The " + kind.name() + " doesn't drop that."));
			return 0;
		}
		state.markDirty();
		c.getSource().sendFeedback(() -> prefix().append("Removed ").append(item.getName()).append(" from the " + kind.name() + "."), true);
		return 1;
	}

	private static int items(CommandContext<ServerCommandSource> c) {
		Kind kind = kindArg(c);
		if (kind == null) return unknown(c.getSource());
		int a = IntegerArgumentType.getInteger(c, "min");
		int b = IntegerArgumentType.getInteger(c, "max");
		CratesState state = CratesState.get(c.getSource().getServer());
		CratesState.Crate crate = state.crate(kind.key());
		crate.rollsMin = Math.min(a, b);
		crate.rollsMax = Math.max(a, b);
		state.markDirty();
		c.getSource().sendFeedback(() -> prefix().append(kind.name() + " now gives " + crate.rollsMin + "-" + crate.rollsMax + " items."), true);
		return 1;
	}

	private static String chance(CratesState.Crate crate, CratesState.Entry entry) {
		int total = crate.loot.stream().mapToInt(e -> Math.max(0, e.weight)).sum();
		if (total <= 0 || entry.weight <= 0) return "0%";
		double pct = entry.weight * 100.0 / total;
		return String.format(java.util.Locale.US, pct < 1 ? "%.2f%%" : "%.1f%%", pct);
	}

	private static int unknown(ServerCommandSource source) {
		source.sendError(Text.literal("There's no crate called that. Use crate, spooky_crate or holiday_crate."));
		return 0;
	}

	private static MutableText prefix() {
		return Text.empty().append(Text.literal("Crates:").formatted(Formatting.GOLD)).append(" ");
	}
}
