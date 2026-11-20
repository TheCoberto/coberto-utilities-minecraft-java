package com.thecoberto.utilities.prank;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.command.argument.PosArgument;
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
import net.minecraft.util.math.BlockPos;

/**
 * Prank Button: a big red button with a floating sign above it saying
 * something like "Don't Push This Button". Whoever pushes it gets pranked.
 * The player who placed it (or an operator) sneaks and uses it to change the
 * message, the prank, the cooldown and so on, in clickable chat.
 */
public final class PrankButton {
	private PrankButton() {}

	public static final Block BLOCK = new PrankButtonBlock(AbstractBlock.Settings.create()
			.mapColor(MapColor.RED)
			.strength(1.0f, 3_600_000.0f) // explosions can't break it
			.sounds(BlockSoundGroup.STONE)
			.nonOpaque());
	public static BlockEntityType<PrankButtonBlockEntity> BLOCK_ENTITY;

	public static void registerBlocks() {
		Identifier id = Identifier.of("coberto", "prank_button");
		Registry.register(Registries.BLOCK, id, BLOCK);
		Item item = Registry.register(Registries.ITEM, id, new BlockItem(BLOCK, new Item.Settings()));
		BLOCK_ENTITY = Registry.register(Registries.BLOCK_ENTITY_TYPE, id,
				BlockEntityType.Builder.create(PrankButtonBlockEntity::new, BLOCK).build(null));
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> entries.add(item));
	}

	public static void registerEvents() {
		Pranks.registerEvents();
	}

	// ---------- Pushing it ----------

	private static boolean canEdit(ServerPlayerEntity player, PrankButtonBlockEntity button) {
		return button.isOwner(player.getUuid()) || player.hasPermissionLevel(2) || button.ownerId == null;
	}

	static void press(ServerPlayerEntity player, PrankButtonBlockEntity button) {
		if (player.isSneaking() && canEdit(player, button)) {
			showSetup(player.getCommandSource(), button);
			return;
		}
		var world = player.getServerWorld();
		world.playSound(null, button.getPos(), SoundEvents.BLOCK_STONE_BUTTON_CLICK_ON, SoundCategory.BLOCKS, 1f, 1f);
		if (button.isOwner(player.getUuid()) && button.ownerSafe) {
			player.sendMessage(prefix().append("It's your button, so nothing happens. Sneak and use it to change it."));
			return;
		}

		long now = world.getServer().getTicks();
		if (button.lastPress != Long.MIN_VALUE && now - button.lastPress < button.cooldown * 20L) return;
		button.lastPress = now;

		Pranks.pick(button.prank, world.getRandom()).run().accept(player, button);
		if (button.announce) {
			Text message = prefix().append(Text.literal(player.getName().getString()).formatted(Formatting.YELLOW)).append(" pushed the button.");
			for (ServerPlayerEntity p : world.getPlayers(p -> p.squaredDistanceTo(button.getPos().toCenterPos()) <= 64 * 64)) {
				p.sendMessage(message);
			}
		}
	}

	// ---------- Setup (/prankbutton) ----------

	private static List<String> prankChoices() {
		List<String> choices = new ArrayList<>(Pranks.ALL.keySet());
		choices.add("random");
		return choices;
	}

	private static String prankName(String id) {
		return id.equals("random") ? "Random prank" : Pranks.ALL.containsKey(id) ? Pranks.ALL.get(id).name() : id;
	}

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		RequiredArgumentBuilder<ServerCommandSource, PosArgument> at = CommandManager.argument("pos", BlockPosArgumentType.blockPos())
				.executes(c -> withButton(c, b -> showSetup(c.getSource(), b)));
		at.then(CommandManager.literal("message").then(CommandManager.argument("text", StringArgumentType.greedyString())
				.executes(c -> set(c, b -> {
					String text = StringArgumentType.getString(c, "text").trim();
					b.message = text.isEmpty() ? PrankButtonBlockEntity.DEFAULT_MESSAGE : text.substring(0, Math.min(80, text.length()));
				}))));
		at.then(CommandManager.literal("prank").then(CommandManager.argument("prank", StringArgumentType.word())
				.suggests((c, s) -> CommandSource.suggestMatching(prankChoices(), s))
				.executes(c -> {
					String prank = StringArgumentType.getString(c, "prank").toLowerCase(Locale.ROOT);
					if (!prankChoices().contains(prank)) {
						c.getSource().sendError(Text.literal("No prank called " + prank + ". Choose one of: " + String.join(", ", prankChoices())));
						return 0;
					}
					return set(c, b -> b.prank = prank);
				})));
		at.then(intSetting("cooldown", 0, 60, (b, v) -> b.cooldown = v));
		at.then(boolSetting("ownersafe", (b, v) -> b.ownerSafe = v));
		at.then(boolSetting("announce", (b, v) -> b.announce = v));
		at.then(intSetting("anvilheight", 5, 100, (b, v) -> b.anvilHeight = v));
		at.then(boolSetting("anvilskill", (b, v) -> b.anvilsKill = v));
		at.then(intSetting("chickens", 3, 30, (b, v) -> b.chickenCount = v));
		at.then(intSetting("chickenseconds", 5, 60, (b, v) -> b.chickenSeconds = v));
		at.then(CommandManager.literal("peck").then(CommandManager.argument("strength", StringArgumentType.word())
				.suggests((c, s) -> CommandSource.suggestMatching(Pranks.PECKS.keySet(), s))
				.executes(c -> {
					String peck = StringArgumentType.getString(c, "strength").toLowerCase(Locale.ROOT);
					if (!Pranks.PECKS.containsKey(peck)) {
						c.getSource().sendError(Text.literal("Choose light, medium, brutal or deadly."));
						return 0;
					}
					return set(c, b -> b.peck = peck);
				})));
		at.then(intSetting("launchheight", 10, 500, (b, v) -> b.launchHeight = v));
		at.then(intSetting("slowfall", 5, 300, (b, v) -> b.slowFallSeconds = v));

		dispatcher.register(CommandManager.literal("prankbutton").then(at));
	}

	private interface IntSetter { void set(PrankButtonBlockEntity b, int value); }
	private interface BoolSetter { void set(PrankButtonBlockEntity b, boolean value); }

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> intSetting(String name, int min, int max, IntSetter setter) {
		return CommandManager.literal(name).then(CommandManager.argument("value", IntegerArgumentType.integer(min, max))
				.executes(c -> set(c, b -> setter.set(b, IntegerArgumentType.getInteger(c, "value")))));
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> boolSetting(String name, BoolSetter setter) {
		return CommandManager.literal(name).then(CommandManager.argument("value", BoolArgumentType.bool())
				.executes(c -> set(c, b -> setter.set(b, BoolArgumentType.getBool(c, "value")))));
	}

	/** Finds the button at pos and checks the command's runner may change it. */
	private static int withButton(CommandContext<ServerCommandSource> c, Consumer<PrankButtonBlockEntity> action) throws CommandSyntaxException {
		ServerCommandSource source = c.getSource();
		BlockPos pos = BlockPosArgumentType.getLoadedBlockPos(c, "pos");
		if (!(source.getWorld().getBlockEntity(pos) instanceof PrankButtonBlockEntity button)) {
			source.sendError(Text.literal("There's no Prank Button there."));
			return 0;
		}
		ServerPlayerEntity player = source.getPlayer();
		boolean allowed = source.hasPermissionLevel(2) || (player != null && canEdit(player, button));
		if (!allowed) {
			source.sendError(Text.literal("Only " + (button.owner.isEmpty() ? "its owner" : button.owner) + " or an operator can change this button."));
			return 0;
		}
		action.accept(button);
		return 1;
	}

	private static int set(CommandContext<ServerCommandSource> c, Consumer<PrankButtonBlockEntity> change) throws CommandSyntaxException {
		return withButton(c, b -> {
			change.accept(b);
			ServerPlayerEntity player = c.getSource().getPlayer();
			if (b.ownerId == null && player != null) {
				b.owner = player.getName().getString();
				b.ownerId = player.getUuid();
			}
			b.changed();
			showSetup(c.getSource(), b);
		});
	}

	static void showSetup(ServerCommandSource source, PrankButtonBlockEntity b) {
		BlockPos p = b.getPos();
		String cmd = "/prankbutton " + p.getX() + " " + p.getY() + " " + p.getZ() + " ";
		MutableText text = Text.empty().append(Text.literal("Prank Button").formatted(Formatting.RED, Formatting.BOLD))
				.append(Text.literal(b.owner.isEmpty() ? "" : " (" + b.owner + "'s)").formatted(Formatting.GRAY))
				.append(": click to change.")
				.append("\n Sign: ").append(Text.literal(b.message).formatted(Formatting.RED)).append(" ").append(suggest("[Change]", cmd + "message " + b.message))
				.append("\n Prank: ");
		for (String id : prankChoices()) {
			boolean on = id.equals(b.prank);
			text.append(Text.literal(on ? "[" + prankName(id) + "]" : prankName(id)).styled(s -> s
					.withColor(on ? Formatting.GREEN : Formatting.GRAY)
					.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd + "prank " + id))
					.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(cmd + "prank " + id)))))
					.append(" ");
		}
		text.append("\n Works again after " + b.cooldown + "s ").append(suggest("[Change]", cmd + "cooldown " + b.cooldown))
				.append("  ").append(toggle("Owner is safe", b.ownerSafe, cmd + "ownersafe"))
				.append(" ").append(toggle("Tell everyone who pushed it", b.announce, cmd + "announce"))
				.append(Text.literal("\n Anvil shower: ").formatted(Formatting.GOLD))
				.append("drops from " + b.anvilHeight + " blocks ").append(suggest("[Change]", cmd + "anvilheight " + b.anvilHeight))
				.append(" ").append(toggle("Always kill, even through netherite", b.anvilsKill, cmd + "anvilskill"))
				.append(Text.literal("\n Killer chickens: ").formatted(Formatting.GOLD))
				.append(b.chickenCount + " ").append(suggest("[Change]", cmd + "chickens " + b.chickenCount))
				.append(", chase for " + b.chickenSeconds + "s ").append(suggest("[Change]", cmd + "chickenseconds " + b.chickenSeconds))
				.append(", peck: ");
		for (var peck : Pranks.PECKS.entrySet()) {
			boolean on = peck.getKey().equals(b.peck);
			text.append(Text.literal(on ? "[" + peck.getValue() + "]" : peck.getValue()).styled(s -> s
					.withColor(on ? Formatting.GREEN : Formatting.GRAY)
					.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd + "peck " + peck.getKey()))))
					.append(" ");
		}
		text.append(Text.literal("\n Launch: ").formatted(Formatting.GOLD))
				.append(b.launchHeight + " blocks up (300+ is space) ").append(suggest("[Change]", cmd + "launchheight " + b.launchHeight))
				.append(", " + b.slowFallSeconds + "s of slow falling ").append(suggest("[Change]", cmd + "slowfall " + b.slowFallSeconds))
				.append(Text.literal(" (about 1 second per 2 blocks)").formatted(Formatting.GRAY));
		source.sendFeedback(() -> text, false);
	}

	private static MutableText suggest(String label, String command) {
		return Text.literal(label).styled(s -> s.withColor(Formatting.YELLOW)
				.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(command))));
	}

	private static MutableText toggle(String label, boolean on, String command) {
		String full = command + " " + !on;
		return Text.literal("[" + (on ? "✔ " : "✘ ") + label + "]").styled(s -> s
				.withColor(on ? Formatting.GREEN : Formatting.DARK_GRAY)
				.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, full))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(full))));
	}

	static MutableText prefix() {
		return Text.empty().append(Text.literal("Prank:").formatted(Formatting.RED)).append(" ");
	}
}
