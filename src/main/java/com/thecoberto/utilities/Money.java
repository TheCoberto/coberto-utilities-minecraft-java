package com.thecoberto.utilities;

import java.text.NumberFormat;
import java.util.Locale;

import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.minecraft.world.scores.Objective;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

/**
 * The Money scoreboard: shared by Mob Money, Activity Rewards and the Slot
 * Machine mod. Created the first time someone is paid.
 */
public final class Money {
	public static final String OBJECTIVE = "Money";
	/** Scoreboards top out a little above 2 billion. */
	public static final int MAX_AMOUNT = 1_000_000_000;

	private Money() {}

	public static void add(ServerPlayer player, int amount) {
		ServerScoreboard scoreboard = player.level().getServer().getScoreboard();
		Objective objective = scoreboard.getObjective(OBJECTIVE);
		if (objective == null) {
			objective = scoreboard.addObjective(OBJECTIVE, ObjectiveCriteria.DUMMY, Component.literal(OBJECTIVE),
					ObjectiveCriteria.RenderType.INTEGER, true, null);
		}
		scoreboard.getOrCreatePlayerScore(player, objective).add(amount);
	}

	public static int get(ServerPlayer player) {
		ServerScoreboard scoreboard = player.level().getServer().getScoreboard();
		Objective objective = scoreboard.getObjective(OBJECTIVE);
		if (objective == null) return 0;
		var score = scoreboard.getPlayerScoreInfo(player, objective);
		return score == null ? 0 : score.value();
	}

	public static String format(long n) {
		return NumberFormat.getIntegerInstance(Locale.US).format(n);
	}
}
