package com.thecoberto.utilities;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;

import net.minecraft.server.MinecraftServer;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

/**
 * Creeper anti-grief: creeper explosions still hurt players and mobs but
 * don't break blocks, unless an operator turns griefing back on with
 * /creepergriefing true. The explosion itself is changed in CreeperEntityMixin.
 */
public final class CreeperGriefing {
	private CreeperGriefing() {}

	public static boolean creepersBreakBlocks(MinecraftServer server) {
		return UtilitiesState.get(server).creeperGriefing();
	}

	static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("creepergriefing")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(context -> {
					boolean breaks = creepersBreakBlocks(context.getSource().getServer());
					context.getSource().sendSuccess(() -> Component.literal("Creepers ")
							.append(breaks
									? Component.literal("break").withStyle(ChatFormatting.RED)
									: Component.literal("don't break").withStyle(ChatFormatting.GREEN))
							.append(" blocks. Use /creepergriefing true or false to change it."), false);
					return breaks ? 1 : 0;
				})
				.then(Commands.argument("breakBlocks", BoolArgumentType.bool())
						.executes(context -> {
							boolean breaks = BoolArgumentType.getBool(context, "breakBlocks");
							MinecraftServer server = context.getSource().getServer();
							UtilitiesState.get(server).setCreeperGriefing(breaks);
							server.getPlayerList().broadcastSystemMessage(CobertoUtilities.prefix().append("Creepers ")
									.append(breaks
											? Component.literal("now break").withStyle(ChatFormatting.RED)
											: Component.literal("no longer break").withStyle(ChatFormatting.GREEN))
									.append(" blocks."), false);
							return 1;
						})));
	}
}
