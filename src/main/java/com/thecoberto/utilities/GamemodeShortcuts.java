package com.thecoberto.utilities;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.level.GameType;

/**
 * /gms, /gmc, /gma and /gmsp: short for /gamemode <mode> [players]. With no
 * players given, they change the gamemode of whoever ran the command.
 */
public final class GamemodeShortcuts {
	private GamemodeShortcuts() {}

	private record Shortcut(String name, GameType mode, String label) {}

	private static final List<Shortcut> SHORTCUTS = List.of(
			new Shortcut("gms", GameType.SURVIVAL, "Survival"),
			new Shortcut("gmc", GameType.CREATIVE, "Creative"),
			new Shortcut("gma", GameType.ADVENTURE, "Adventure"),
			new Shortcut("gmsp", GameType.SPECTATOR, "Spectator"));

	static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		for (Shortcut shortcut : SHORTCUTS) {
			dispatcher.register(Commands.literal(shortcut.name())
					.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
					.executes(context -> {
						CommandSourceStack source = context.getSource();
						ServerPlayer self = source.getPlayer();
						if (self == null) {
							source.sendFailure(Component.literal("Choose which players to change."));
							return 0;
						}
						return apply(source, List.of(self), shortcut);
					})
					.then(Commands.argument("players", EntityArgument.players())
							.executes(context -> apply(context.getSource(),
									EntityArgument.getPlayers(context, "players"), shortcut))));
		}
	}

	private static int apply(CommandSourceStack source, Collection<ServerPlayer> players, Shortcut shortcut)
			throws CommandSyntaxException {
		for (ServerPlayer player : players) {
			player.setGameMode(shortcut.mode());
			player.sendSystemMessage(Component.literal("Your game mode is now ")
					.append(Component.literal(shortcut.label()).withStyle(ChatFormatting.YELLOW))
					.append("."));
		}
		ServerPlayer self = source.getPlayer();
		String who = players.size() == 1 && players.contains(self)
				? "your"
				: players.stream().map(p -> p.getName().getString()).collect(Collectors.joining(", ")) + "'s";
		source.sendSuccess(() -> Component.literal("Set " + who + " game mode to " + shortcut.label() + "."), true);
		return players.size();
	}
}
