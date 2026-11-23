package com.thecoberto.utilities;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Money comes from the server economy through Vault (EssentialsX on this
 * server), the same balance as /bal and the slot machine. Without Vault and an
 * economy plugin, the features that use Money say so instead.
 */
public final class Money {
	private static Economy economy;

	private Money() {}

	static void hook() {
		if (Bukkit.getPluginManager().getPlugin("Vault") == null) return;
		RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
		if (rsp != null) economy = rsp.getProvider();
	}

	public static boolean available() {
		return economy != null;
	}

	public static double get(OfflinePlayer player) {
		return economy == null ? 0 : economy.getBalance(player);
	}

	public static void give(OfflinePlayer player, double amount) {
		if (economy != null && amount > 0) economy.depositPlayer(player, amount);
	}

	/** Takes the amount if they have it. */
	public static boolean take(OfflinePlayer player, double amount) {
		if (amount <= 0) return true;
		if (economy == null || economy.getBalance(player) < amount) return false;
		return economy.withdrawPlayer(player, amount).transactionSuccess();
	}

	public static String format(double amount) {
		return economy == null ? String.valueOf((long) amount) : economy.format(amount);
	}
}
