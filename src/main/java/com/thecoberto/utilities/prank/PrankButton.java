package com.thecoberto.utilities.prank;

import com.thecoberto.utilities.CobertoUtilities;
import net.minecraft.world.phys.Vec3;

import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.permissions.Permissions;

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

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
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
import net.minecraft.core.BlockPos;

/**
 * Prank Button: a big red button with a floating sign above it saying
 * something like "Don't Push This Button". Whoever pushes it gets pranked.
 * The player who placed it (or an operator) sneaks and uses it to change the
 * message, the prank, the cooldown and so on, in clickable chat.
 */
public final class PrankButton {
	private PrankButton() {}

	public static final Block BLOCK = new PrankButtonBlock(BlockBehaviour.Properties.of()
			.setId(ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("coberto", "prank_button")))
			.mapColor(MapColor.COLOR_RED)
			.strength(1.0f, 3_600_000.0f) // explosions can't break it
			.sound(SoundType.STONE)
			.noOcclusion());
	public static BlockEntityType<PrankButtonBlockEntity> BLOCK_ENTITY;

	public static void registerBlocks() {
		Identifier id = Identifier.fromNamespaceAndPath("coberto", "prank_button");
		Registry.register(BuiltInRegistries.BLOCK, id, BLOCK);
		Item item = Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(BLOCK, new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id)).useBlockDescriptionPrefix()));
		BLOCK_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id,
				new BlockEntityType<>(PrankButtonBlockEntity::new, java.util.Set.of(BLOCK)));
		CreativeModeTabEvents.modifyOutputEvent(CobertoUtilities.FUNCTIONAL_BLOCKS).register(output -> output.accept(item));
	}

	public static void registerEvents() {
		Pranks.registerEvents();
	}

	// ---------- Pushing it ----------

	private static boolean canEdit(ServerPlayer player, PrankButtonBlockEntity button) {
		return button.isOwner(player.getUUID()) || player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) || button.ownerId == null;
	}

	static void press(ServerPlayer player, PrankButtonBlockEntity button) {
		if (player.isShiftKeyDown() && canEdit(player, button)) {
			showSetup(player.createCommandSourceStack(), button);
			return;
		}
		var world = player.level();
		world.playSound(null, button.getBlockPos(), SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, 1f, 1f);
		if (button.isOwner(player.getUUID()) && button.ownerSafe) {
			player.sendSystemMessage(prefix().append("It's your button, so nothing happens. Sneak and use it to change it."));
			return;
		}

		long now = world.getServer().getTickCount();
		if (button.lastPress != Long.MIN_VALUE && now - button.lastPress < button.cooldown * 20L) return;
		button.lastPress = now;

		Pranks.pick(button.prank, world.getRandom()).run().accept(player, button);
		if (button.announce) {
			Component message = prefix().append(Component.literal(player.getName().getString()).withStyle(ChatFormatting.YELLOW)).append(" pushed the button.");
			for (ServerPlayer p : world.getPlayers(p -> p.distanceToSqr(Vec3.atCenterOf(button.getBlockPos())) <= 64 * 64)) {
				p.sendSystemMessage(message);
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

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		RequiredArgumentBuilder<CommandSourceStack, Coordinates> at = Commands.argument("pos", BlockPosArgument.blockPos())
				.executes(c -> withButton(c, b -> showSetup(c.getSource(), b)));
		at.then(Commands.literal("message").then(Commands.argument("text", StringArgumentType.greedyString())
				.executes(c -> set(c, b -> {
					String text = StringArgumentType.getString(c, "text").trim();
					b.message = text.isEmpty() ? PrankButtonBlockEntity.DEFAULT_MESSAGE : text.substring(0, Math.min(80, text.length()));
				}))));
		at.then(Commands.literal("prank").then(Commands.argument("prank", StringArgumentType.word())
				.suggests((c, s) -> SharedSuggestionProvider.suggest(prankChoices(), s))
				.executes(c -> {
					String prank = StringArgumentType.getString(c, "prank").toLowerCase(Locale.ROOT);
					if (!prankChoices().contains(prank)) {
						c.getSource().sendFailure(Component.literal("No prank called " + prank + ". Choose one of: " + String.join(", ", prankChoices())));
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
		at.then(Commands.literal("peck").then(Commands.argument("strength", StringArgumentType.word())
				.suggests((c, s) -> SharedSuggestionProvider.suggest(Pranks.PECKS.keySet(), s))
				.executes(c -> {
					String peck = StringArgumentType.getString(c, "strength").toLowerCase(Locale.ROOT);
					if (!Pranks.PECKS.containsKey(peck)) {
						c.getSource().sendFailure(Component.literal("Choose light, medium, brutal or deadly."));
						return 0;
					}
					return set(c, b -> b.peck = peck);
				})));
		at.then(intSetting("launchheight", 10, 500, (b, v) -> b.launchHeight = v));
		at.then(intSetting("slowfall", 5, 300, (b, v) -> b.slowFallSeconds = v));

		dispatcher.register(Commands.literal("prankbutton").then(at));
	}

	private interface IntSetter { void set(PrankButtonBlockEntity b, int value); }
	private interface BoolSetter { void set(PrankButtonBlockEntity b, boolean value); }

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> intSetting(String name, int min, int max, IntSetter setter) {
		return Commands.literal(name).then(Commands.argument("value", IntegerArgumentType.integer(min, max))
				.executes(c -> set(c, b -> setter.set(b, IntegerArgumentType.getInteger(c, "value")))));
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> boolSetting(String name, BoolSetter setter) {
		return Commands.literal(name).then(Commands.argument("value", BoolArgumentType.bool())
				.executes(c -> set(c, b -> setter.set(b, BoolArgumentType.getBool(c, "value")))));
	}

	/** Finds the button at pos and checks the command's runner may change it. */
	private static int withButton(CommandContext<CommandSourceStack> c, Consumer<PrankButtonBlockEntity> action) throws CommandSyntaxException {
		CommandSourceStack source = c.getSource();
		BlockPos pos = BlockPosArgument.getLoadedBlockPos(c, "pos");
		if (!(source.getLevel().getBlockEntity(pos) instanceof PrankButtonBlockEntity button)) {
			source.sendFailure(Component.literal("There's no Prank Button there."));
			return 0;
		}
		ServerPlayer player = source.getPlayer();
		boolean allowed = source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) || (player != null && canEdit(player, button));
		if (!allowed) {
			source.sendFailure(Component.literal("Only " + (button.owner.isEmpty() ? "its owner" : button.owner) + " or an operator can change this button."));
			return 0;
		}
		action.accept(button);
		return 1;
	}

	private static int set(CommandContext<CommandSourceStack> c, Consumer<PrankButtonBlockEntity> change) throws CommandSyntaxException {
		return withButton(c, b -> {
			change.accept(b);
			ServerPlayer player = c.getSource().getPlayer();
			if (b.ownerId == null && player != null) {
				b.owner = player.getName().getString();
				b.ownerId = player.getUUID();
			}
			b.changed();
			showSetup(c.getSource(), b);
		});
	}

	static void showSetup(CommandSourceStack source, PrankButtonBlockEntity b) {
		BlockPos p = b.getBlockPos();
		String cmd = "/prankbutton " + p.getX() + " " + p.getY() + " " + p.getZ() + " ";
		MutableComponent text = Component.empty().append(Component.literal("Prank Button").withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
				.append(Component.literal(b.owner.isEmpty() ? "" : " (" + b.owner + "'s)").withStyle(ChatFormatting.GRAY))
				.append(": click to change.")
				.append("\n Sign: ").append(Component.literal(b.message).withStyle(ChatFormatting.RED)).append(" ").append(suggest("[Change]", cmd + "message " + b.message))
				.append("\n Prank: ");
		for (String id : prankChoices()) {
			boolean on = id.equals(b.prank);
			text.append(Component.literal(on ? "[" + prankName(id) + "]" : prankName(id)).withStyle(s -> s
					.withColor(on ? ChatFormatting.GREEN : ChatFormatting.GRAY)
					.withClickEvent(new ClickEvent.RunCommand(cmd + "prank " + id))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal(cmd + "prank " + id)))))
					.append(" ");
		}
		text.append("\n Works again after " + b.cooldown + "s ").append(suggest("[Change]", cmd + "cooldown " + b.cooldown))
				.append("  ").append(toggle("Owner is safe", b.ownerSafe, cmd + "ownersafe"))
				.append(" ").append(toggle("Tell everyone who pushed it", b.announce, cmd + "announce"))
				.append(Component.literal("\n Anvil shower: ").withStyle(ChatFormatting.GOLD))
				.append("drops from " + b.anvilHeight + " blocks ").append(suggest("[Change]", cmd + "anvilheight " + b.anvilHeight))
				.append(" ").append(toggle("Always kill, even through netherite", b.anvilsKill, cmd + "anvilskill"))
				.append(Component.literal("\n Killer chickens: ").withStyle(ChatFormatting.GOLD))
				.append(b.chickenCount + " ").append(suggest("[Change]", cmd + "chickens " + b.chickenCount))
				.append(", chase for " + b.chickenSeconds + "s ").append(suggest("[Change]", cmd + "chickenseconds " + b.chickenSeconds))
				.append(", peck: ");
		for (var peck : Pranks.PECKS.entrySet()) {
			boolean on = peck.getKey().equals(b.peck);
			text.append(Component.literal(on ? "[" + peck.getValue() + "]" : peck.getValue()).withStyle(s -> s
					.withColor(on ? ChatFormatting.GREEN : ChatFormatting.GRAY)
					.withClickEvent(new ClickEvent.RunCommand(cmd + "peck " + peck.getKey()))))
					.append(" ");
		}
		text.append(Component.literal("\n Launch: ").withStyle(ChatFormatting.GOLD))
				.append(b.launchHeight + " blocks up (300+ is space) ").append(suggest("[Change]", cmd + "launchheight " + b.launchHeight))
				.append(", " + b.slowFallSeconds + "s of slow falling ").append(suggest("[Change]", cmd + "slowfall " + b.slowFallSeconds))
				.append(Component.literal(" (about 1 second per 2 blocks)").withStyle(ChatFormatting.GRAY));
		source.sendSuccess(() -> text, false);
	}

	private static MutableComponent suggest(String label, String command) {
		return Component.literal(label).withStyle(s -> s.withColor(ChatFormatting.YELLOW)
				.withClickEvent(new ClickEvent.SuggestCommand(command))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
	}

	private static MutableComponent toggle(String label, boolean on, String command) {
		String full = command + " " + !on;
		return Component.literal("[" + (on ? "✔ " : "✘ ") + label + "]").withStyle(s -> s
				.withColor(on ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY)
				.withClickEvent(new ClickEvent.RunCommand(full))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(full))));
	}

	static MutableComponent prefix() {
		return Component.empty().append(Component.literal("Prank:").withStyle(ChatFormatting.RED)).append(" ");
	}
}
