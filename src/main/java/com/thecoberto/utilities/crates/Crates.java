package com.thecoberto.utilities.crates;

import com.thecoberto.utilities.CobertoUtilities;

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

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.RandomSource;

/**
 * Loot crates: Crate, Spooky Crate and Holiday Crate blocks to hide around the
 * map for events. Opening one pops out random loot and the crate disappears;
 * breaking it gives nothing. Operators change what each crate drops with
 * /crates (saved in the world).
 */
public final class Crates {
	private Crates() {}

	/** A kind of crate. {@code key} is used in commands and the save file. */
	public record Kind(String key, String name, ChatFormatting color, ParticleOptions particle, Block block) {}

	public static final Map<String, Kind> KINDS = new LinkedHashMap<>();

	private static void kind(String key, String name, ChatFormatting color, ParticleOptions particle) {
		Block block = new CrateBlock(key, BlockBehaviour.Properties.of()
				.setId(ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("coberto", key)))
				.mapColor(MapColor.COLOR_BROWN)
				.strength(2.0f, 3_600_000.0f) // explosions can't break it
				.sound(SoundType.WOOD)
				.noLootTable());
		KINDS.put(key, new Kind(key, name, color, particle, block));
	}

	static {
		kind("crate", "Crate", ChatFormatting.GOLD, ParticleTypes.HAPPY_VILLAGER);
		kind("spooky_crate", "Spooky Crate", ChatFormatting.DARK_PURPLE, ParticleTypes.SOUL);
		kind("holiday_crate", "Holiday Crate", ChatFormatting.RED, ParticleTypes.TOTEM_OF_UNDYING);
	}

	public static void registerBlocks() {
		List<Item> items = new ArrayList<>();
		for (Kind kind : KINDS.values()) {
			Identifier id = Identifier.fromNamespaceAndPath("coberto", kind.key());
			Registry.register(BuiltInRegistries.BLOCK, id, kind.block());
			items.add(Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(kind.block(), new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id)).useBlockDescriptionPrefix())));
		}
		CreativeModeTabEvents.modifyOutputEvent(CobertoUtilities.FUNCTIONAL_BLOCKS).register(output -> items.forEach(output::accept));
	}

	// ---------- Opening a crate ----------

	static void open(ServerLevel world, BlockPos pos, ServerPlayer player, String key) {
		Kind kind = KINDS.get(key);
		CratesState.Crate crate = CratesState.get(world.getServer()).crate(key);
		Vec3 center = Vec3.atCenterOf(pos);

		world.removeBlock(pos, false);
		world.playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 1.0f, 1.0f);
		world.playSound(null, pos, SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 0.6f, 1.4f);
		world.sendParticles(kind.particle(), center.x, center.y, center.z, 20, 0.4, 0.4, 0.4, 0.05);

		List<Component> found = new ArrayList<>();
		for (CratesState.Drop drop : roll(crate, world.getRandom())) {
			Item item = BuiltInRegistries.ITEM.getValue(drop.item());
			if (item == Items.AIR) continue;
			int left = drop.amount();
			while (left > 0) {
				int count = Math.min(left, item.getDefaultMaxStackSize());
				ItemEntity entity = new ItemEntity(world, center.x, center.y, center.z, new ItemStack(item, count));
				entity.setDefaultPickUpDelay();
				world.addFreshEntity(entity);
				left -= count;
			}
			found.add(Component.literal(drop.amount() + "x ").append(item.getName(item.getDefaultInstance())));
			if (item == Items.ELYTRA || item == Items.HEAVY_CORE) {
				world.getServer().getPlayerList().broadcastSystemMessage(Component.empty()
						.append(Component.literal(player.getName().getString() + " found ").withStyle(ChatFormatting.GOLD))
						.append(item.getName(item.getDefaultInstance()).copy().withStyle(ChatFormatting.LIGHT_PURPLE))
						.append(Component.literal(" in a " + kind.name() + "!").withStyle(ChatFormatting.GOLD)), false);
			}
		}

		MutableComponent message = Component.empty().append(Component.literal(kind.name() + ":").withStyle(kind.color())).append(" You found ");
		if (found.isEmpty()) message.append("nothing... it was empty!");
		for (int i = 0; i < found.size(); i++) {
			if (i > 0) message.append(", ");
			message.append(found.get(i));
		}
		player.sendSystemMessage(message);
	}

	private static List<CratesState.Drop> roll(CratesState.Crate crate, RandomSource random) {
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

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess) {
		RequiredArgumentBuilder<CommandSourceStack, String> crate = Commands.argument("crate", StringArgumentType.word())
				.suggests((c, b) -> SharedSuggestionProvider.suggest(KINDS.keySet(), b))
				.executes(c -> show(c.getSource(), kindArg(c)))
				// add <item> <weight> [min] [max]: adds the item, or changes it if the crate already has it
				.then(Commands.literal("add")
						.then(Commands.argument("item", ItemArgument.item(registryAccess))
								.then(Commands.argument("weight", IntegerArgumentType.integer(0, 1_000_000))
										.executes(c -> add(c, 1, 1))
										.then(Commands.argument("min", IntegerArgumentType.integer(1, 10_000))
												.executes(c -> {
													int amount = IntegerArgumentType.getInteger(c, "min");
													return add(c, amount, amount);
												})
												.then(Commands.argument("max", IntegerArgumentType.integer(1, 10_000))
														.executes(c -> add(c, IntegerArgumentType.getInteger(c, "min"),
																IntegerArgumentType.getInteger(c, "max"))))))))
				.then(Commands.literal("remove")
						.then(Commands.argument("item", ItemArgument.item(registryAccess))
								.executes(Crates::remove)))
				.then(Commands.literal("items")
						.then(Commands.argument("min", IntegerArgumentType.integer(1, 27))
								.then(Commands.argument("max", IntegerArgumentType.integer(1, 27))
										.executes(Crates::items))))
				.then(Commands.literal("reset").executes(c -> {
					Kind kind = kindArg(c);
					if (kind == null) return unknown(c.getSource());
					CratesState.get(c.getSource().getServer()).reset(kind.key());
					c.getSource().sendSuccess(() -> prefix().append(kind.name() + " reset to the default loot."), true);
					return 1;
				}));

		dispatcher.register(Commands.literal("crates")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(c -> {
					MutableComponent text = Component.empty().append(Component.literal("Loot Crates").withStyle(ChatFormatting.GOLD))
							.append(": click one to see and change what it drops.");
					for (Kind kind : KINDS.values()) {
						text.append("\n ").append(Component.literal("[" + kind.name() + "]").withStyle(s -> s
								.withColor(kind.color())
								.withClickEvent(new ClickEvent.RunCommand("/crates " + kind.key()))
								.withHoverEvent(new HoverEvent.ShowText(Component.literal("/crates " + kind.key())))));
					}
					c.getSource().sendSuccess(() -> text, false);
					return 1;
				})
				.then(crate));
	}

	private static Kind kindArg(CommandContext<CommandSourceStack> c) {
		return KINDS.get(StringArgumentType.getString(c, "crate"));
	}

	private static int show(CommandSourceStack source, Kind kind) {
		if (kind == null) return unknown(source);
		CratesState.Crate crate = CratesState.get(source.getServer()).crate(kind.key());
		String cmd = "/crates " + kind.key();
		MutableComponent text = Component.empty().append(Component.literal(kind.name()).withStyle(kind.color()))
				.append(" gives " + crate.rollsMin + "-" + crate.rollsMax + " items when opened. Each item is picked by chance.");
		for (CratesState.Entry entry : crate.loot) {
			Item item = BuiltInRegistries.ITEM.getValue(entry.item);
			String amount = entry.min == entry.max ? "" + entry.min : entry.min + "-" + entry.max;
			text.append("\n ").append(Component.empty().append(item.getName(item.getDefaultInstance())).append(" x" + amount)
					.withStyle(s -> s.withColor(ChatFormatting.YELLOW)
							.withClickEvent(new ClickEvent.SuggestCommand(
									cmd + " add " + entry.item + " " + entry.weight + " " + entry.min + " " + entry.max))
							.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to change it: weight, then least and most amount")))))
					.append(Component.literal(" " + chance(crate, entry) + " each pick (weight " + entry.weight + ") ").withStyle(ChatFormatting.GRAY))
					.append(button("[x]", ChatFormatting.RED, cmd + " remove " + entry.item, "Remove it", false));
		}
		text.append("\n ")
				.append(button("[+ Add item]", ChatFormatting.DARK_GREEN, cmd + " add ", "/crates " + kind.key() + " add <item> <weight> [min] [max]", false))
				.append(" ")
				.append(button("[Items per crate]", ChatFormatting.AQUA, cmd + " items " + crate.rollsMin + " " + crate.rollsMax, "/crates " + kind.key() + " items <least> <most>", false))
				.append(" ")
				.append(button("[Reset]", ChatFormatting.RED, cmd + " reset", "Put the loot back to the defaults", false));
		source.sendSuccess(() -> text, false);
		return 1;
	}

	private static MutableComponent button(String label, ChatFormatting color, String command, String hover, boolean run) {
		return Component.literal(label).withStyle(s -> s.withColor(color)
				.withClickEvent((run ? new ClickEvent.RunCommand(command) : new ClickEvent.SuggestCommand(command)))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	private static int add(CommandContext<CommandSourceStack> c, int min, int max) throws CommandSyntaxException {
		Kind kind = kindArg(c);
		if (kind == null) return unknown(c.getSource());
		if (min > max) {
			c.getSource().sendFailure(Component.literal("The least amount can't be more than the most."));
			return 0;
		}
		Item item = ItemArgument.getItem(c, "item").item().value();
		Identifier id = BuiltInRegistries.ITEM.getKey(item);
		int weight = IntegerArgumentType.getInteger(c, "weight");
		CratesState state = CratesState.get(c.getSource().getServer());
		CratesState.Crate crate = state.crate(kind.key());
		CratesState.Entry entry = crate.set(id, weight, min, max);
		state.setDirty();
		c.getSource().sendSuccess(() -> prefix().append(kind.name() + " now drops ").append(item.getName(item.getDefaultInstance()))
				.append(" (" + chance(crate, entry) + " each pick)."), true);
		return 1;
	}

	private static int remove(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		Kind kind = kindArg(c);
		if (kind == null) return unknown(c.getSource());
		Item item = ItemArgument.getItem(c, "item").item().value();
		CratesState state = CratesState.get(c.getSource().getServer());
		if (!state.crate(kind.key()).loot.removeIf(e -> e.item.equals(BuiltInRegistries.ITEM.getKey(item)))) {
			c.getSource().sendFailure(Component.literal("The " + kind.name() + " doesn't drop that."));
			return 0;
		}
		state.setDirty();
		c.getSource().sendSuccess(() -> prefix().append("Removed ").append(item.getName(item.getDefaultInstance())).append(" from the " + kind.name() + "."), true);
		return 1;
	}

	private static int items(CommandContext<CommandSourceStack> c) {
		Kind kind = kindArg(c);
		if (kind == null) return unknown(c.getSource());
		int a = IntegerArgumentType.getInteger(c, "min");
		int b = IntegerArgumentType.getInteger(c, "max");
		CratesState state = CratesState.get(c.getSource().getServer());
		CratesState.Crate crate = state.crate(kind.key());
		crate.rollsMin = Math.min(a, b);
		crate.rollsMax = Math.max(a, b);
		state.setDirty();
		c.getSource().sendSuccess(() -> prefix().append(kind.name() + " now gives " + crate.rollsMin + "-" + crate.rollsMax + " items."), true);
		return 1;
	}

	private static String chance(CratesState.Crate crate, CratesState.Entry entry) {
		int total = crate.loot.stream().mapToInt(e -> Math.max(0, e.weight)).sum();
		if (total <= 0 || entry.weight <= 0) return "0%";
		double pct = entry.weight * 100.0 / total;
		return String.format(java.util.Locale.US, pct < 1 ? "%.2f%%" : "%.1f%%", pct);
	}

	private static int unknown(CommandSourceStack source) {
		source.sendFailure(Component.literal("There's no crate called that. Use crate, spooky_crate or holiday_crate."));
		return 0;
	}

	private static MutableComponent prefix() {
		return Component.empty().append(Component.literal("Crates:").withStyle(ChatFormatting.GOLD)).append(" ");
	}
}
