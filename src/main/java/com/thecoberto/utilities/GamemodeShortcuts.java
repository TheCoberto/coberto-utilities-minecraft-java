package com.thecoberto.utilities;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;

/**
 * /gms, /gmc, /gma and /gmsp: short for /gamemode <mode> [players]. With no
 * players given, they change the gamemode of whoever ran the command.
 */
public final class GamemodeShortcuts {
	private GamemodeShortcuts() {}

	private record Shortcut(String name, GameMode mode, String label) {}

	private static final List<Shortcut> SHORTCUTS = List.of(
			new Shortcut("gms", GameMode.SURVIVAL, "Survival"),
			new Shortcut("gmc", GameMode.CREATIVE, "Creative"),
			new Shortcut("gma", GameMode.ADVENTURE, "Adventure"),
			new Shortcut("gmsp", GameMode.SPECTATOR, "Spectator"));

	static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		for (Shortcut shortcut : SHORTCUTS) {
			dispatcher.register(CommandManager.literal(shortcut.name())
					.requires(s -> s.hasPermissionLevel(2))
					.executes(context -> {
						ServerCommandSource source = context.getSource();
						ServerPlayerEntity self = source.getPlayer();
						if (self == null) {
							source.sendError(Text.literal("Choose which players to change."));
							return 0;
						}
						return apply(source, List.of(self), shortcut);
					})
					.then(CommandManager.argument("players", EntityArgumentType.players())
							.executes(context -> apply(context.getSource(),
									EntityArgumentType.getPlayers(context, "players"), shortcut))));
		}
	}

	private static int apply(ServerCommandSource source, Collection<ServerPlayerEntity> players, Shortcut shortcut)
			throws CommandSyntaxException {
		for (ServerPlayerEntity player : players) {
			player.changeGameMode(shortcut.mode());
			player.sendMessage(Text.literal("Your game mode is now ")
					.append(Text.literal(shortcut.label()).formatted(Formatting.YELLOW))
					.append("."));
		}
		ServerPlayerEntity self = source.getPlayer();
		String who = players.size() == 1 && players.contains(self)
				? "your"
				: players.stream().map(p -> p.getName().getString()).collect(Collectors.joining(", ")) + "'s";
		source.sendFeedback(() -> Text.literal("Set " + who + " game mode to " + shortcut.label() + "."), true);
		return players.size();
	}
}
