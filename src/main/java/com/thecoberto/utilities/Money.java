package com.thecoberto.utilities;

import java.text.NumberFormat;
import java.util.Locale;

import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * The Money scoreboard: shared by Mob Money, Activity Rewards and the Slot
 * Machine mod. Created the first time someone is paid.
 */
public final class Money {
	public static final String OBJECTIVE = "Money";
	/** Scoreboards top out a little above 2 billion. */
	public static final int MAX_AMOUNT = 1_000_000_000;

	private Money() {}

	public static void add(ServerPlayerEntity player, int amount) {
		ServerScoreboard scoreboard = player.server.getScoreboard();
		ScoreboardObjective objective = scoreboard.getNullableObjective(OBJECTIVE);
		if (objective == null) {
			objective = scoreboard.addObjective(OBJECTIVE, ScoreboardCriterion.DUMMY, Text.literal(OBJECTIVE),
					ScoreboardCriterion.RenderType.INTEGER, true, null);
		}
		scoreboard.getOrCreateScore(player, objective).incrementScore(amount);
	}

	public static String format(long n) {
		return NumberFormat.getIntegerInstance(Locale.US).format(n);
	}
}
