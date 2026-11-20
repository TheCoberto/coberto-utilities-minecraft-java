package com.thecoberto.utilities;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

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

	static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("creepergriefing")
				.requires(s -> s.hasPermissionLevel(2))
				.executes(context -> {
					boolean breaks = creepersBreakBlocks(context.getSource().getServer());
					context.getSource().sendFeedback(() -> Text.literal("Creepers ")
							.append(breaks
									? Text.literal("break").formatted(Formatting.RED)
									: Text.literal("don't break").formatted(Formatting.GREEN))
							.append(" blocks. Use /creepergriefing true or false to change it."), false);
					return breaks ? 1 : 0;
				})
				.then(CommandManager.argument("breakBlocks", BoolArgumentType.bool())
						.executes(context -> {
							boolean breaks = BoolArgumentType.getBool(context, "breakBlocks");
							MinecraftServer server = context.getSource().getServer();
							UtilitiesState.get(server).setCreeperGriefing(breaks);
							server.getPlayerManager().broadcast(CobertoUtilities.prefix().append("Creepers ")
									.append(breaks
											? Text.literal("now break").formatted(Formatting.RED)
											: Text.literal("no longer break").formatted(Formatting.GREEN))
									.append(" blocks."), false);
							return 1;
						})));
	}
}
