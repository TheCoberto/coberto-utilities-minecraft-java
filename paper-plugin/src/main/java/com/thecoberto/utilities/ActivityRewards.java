package com.thecoberto.utilities;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Activity Rewards: players earn Money for every so many minutes they're
 * active. Off by default. AFK detection is left to EssentialsX: the timer
 * pauses while Essentials has someone marked AFK.
 */
public final class ActivityRewards implements Listener, TabExecutor {
	private static final String DEFAULT_MESSAGE = "You have been awarded &a{amount}&r for being active!";
	private static final String DEFAULT_SOUND = "minecraft:entity.player.levelup";

	private final Store store;
	private boolean enabled;
	private int amount;
	private int minutes;
	private String message;
	private String sound; // "" = none

	private final Map<UUID, Integer> activeSeconds = new HashMap<>();

	ActivityRewards(JavaPlugin plugin) {
		store = new Store(plugin, "activity.yml");
		var y = store.yaml;
		enabled = y.getBoolean("enabled", false);
		amount = y.getInt("amount", 100);
		minutes = y.getInt("minutes", 10);
		message = y.getString("message", DEFAULT_MESSAGE);
		sound = y.getString("sound", DEFAULT_SOUND);
		Bukkit.getPluginManager().registerEvents(this, plugin);
		for (String name : List.of("activity", "activityreward", "activitymessage", "activitysound")) {
			plugin.getCommand(name).setExecutor(this);
			plugin.getCommand(name).setTabCompleter(this);
		}
		Bukkit.getScheduler().runTaskTimer(plugin, this::everySecond, 20, 20);
	}

	private void save() {
		var y = store.yaml;
		y.set("enabled", enabled);
		y.set("amount", amount);
		y.set("minutes", minutes);
		y.set("message", message);
		y.set("sound", sound);
		store.save();
	}

	// ---------- Tracking ----------

	private void everySecond() {
		if (!enabled) return;
		for (Player player : Bukkit.getOnlinePlayers()) {
			if (isAfk(player)) continue; // the timer only runs while they aren't AFK
			int seconds = activeSeconds.merge(player.getUniqueId(), 1, Integer::sum);
			if (seconds >= minutes * 60) {
				activeSeconds.put(player.getUniqueId(), 0);
				pay(player);
			}
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		activeSeconds.remove(event.getPlayer().getUniqueId());
	}

	private void pay(Player player) {
		if (!Money.available()) return;
		Money.give(player, amount);
		player.sendMessage(Msg.legacy(message.replace("{amount}", Money.format(amount))
				.replace("{player}", player.getName()).replace("{minutes}", String.valueOf(minutes))));
		if (!sound.isEmpty()) player.playSound(player.getLocation(), sound, SoundCategory.MASTER, 1f, 1f);
	}

	// EssentialsX's AFK status, read without depending on Essentials.
	private Method getUser;
	private Method isAfkMethod;
	private boolean essentialsBroken;

	private boolean isAfk(Player player) {
		Plugin essentials = Bukkit.getPluginManager().getPlugin("Essentials");
		if (essentials == null || essentialsBroken) return false;
		try {
			if (getUser == null) getUser = essentials.getClass().getMethod("getUser", Player.class);
			Object user = getUser.invoke(essentials, player);
			if (user == null) return false;
			if (isAfkMethod == null) isAfkMethod = user.getClass().getMethod("isAfk");
			return (boolean) isAfkMethod.invoke(user);
		} catch (ReflectiveOperationException | RuntimeException e) {
			essentialsBroken = true;
			return false;
		}
	}

	// ---------- Commands ----------

	private void announce(Component text) {
		Bukkit.broadcast(Msg.prefix("Activity Rewards:", NamedTextColor.GOLD).append(text));
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		switch (command.getName()) {
			case "activity" -> {
				if (args.length == 0) {
					sender.sendMessage(describe());
					return true;
				}
				Boolean on = Msg.parseBool(args[0]);
				if (on == null) return false;
				enabled = on;
				save();
				if (on && !Money.available()) Msg.error(sender, "No economy plugin was found through Vault, so nobody can be paid yet.");
				announce(on ? Component.text("On", NamedTextColor.GREEN).append(Component.text(". Active players now earn Money over time."))
						: Component.text("Off", NamedTextColor.RED).append(Component.text(".")));
			}
			case "activityreward" -> {
				if (args.length != 2) return false;
				Integer a = Msg.parseInt(args[0], 1, 1_000_000_000);
				Integer m = Msg.parseInt(args[1], 1, 1440);
				if (a == null || m == null) return false;
				amount = a;
				minutes = m;
				save();
				announce(Component.text("Players now earn ").append(Component.text(Money.format(a), NamedTextColor.GREEN))
						.append(Component.text(" every " + Msg.plural(m, "minute") + " they're active.")));
			}
			case "activitymessage" -> {
				String text = Msg.join(args, 0);
				if (text.isEmpty()) return false;
				// Quotes are optional, like on Bedrock.
				if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) text = text.substring(1, text.length() - 1);
				boolean reset = text.equalsIgnoreCase("reset");
				message = reset ? DEFAULT_MESSAGE : text;
				save();
				announce(reset ? Component.text("Reward message reset.")
						: Component.text("Reward message is now: ").append(Msg.legacy(message)));
			}
			case "activitysound" -> {
				if (args.length != 1) return false;
				if (args[0].equalsIgnoreCase("none")) {
					sound = "";
					save();
					announce(Component.text("Rewards no longer play a sound."));
					return true;
				}
				String id = args[0].toLowerCase(java.util.Locale.ROOT);
				if (!id.matches("([a-z0-9_.-]+:)?[a-z0-9_./-]+")) {
					Msg.error(sender, "That isn't a sound name. Try entity.player.levelup, or none.");
					return true;
				}
				sound = id.contains(":") ? id : "minecraft:" + id;
				save();
				announce(Component.text("Rewards now play ").append(Component.text(sound, NamedTextColor.YELLOW)).append(Component.text(".")));
			}
			default -> {
				return false;
			}
		}
		return true;
	}

	private Component describe() {
		Component on = Component.text("on", NamedTextColor.GREEN);
		Component off = Component.text("off", NamedTextColor.RED);
		return Component.text("Activity Rewards", NamedTextColor.GOLD).append(Component.text(" are "))
				.append(enabled ? on : off).append(Component.text(".\nReward: "))
				.append(Component.text(Money.format(amount), NamedTextColor.GREEN))
				.append(Component.text(" every " + Msg.plural(minutes, "minute") + " of activity.\nMessage: "))
				.append(Msg.legacy(message))
				.append(Component.text("\nSound: " + (sound.isEmpty() ? "none" : sound)))
				.append(Component.text("\nThe timer pauses while EssentialsX has a player marked AFK.", NamedTextColor.GRAY));
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length != 1) return List.of();
		return switch (command.getName()) {
			case "activity" -> Msg.matching(List.of("true", "false"), args[0]);
			case "activitymessage" -> Msg.matching(List.of("reset"), args[0]);
			case "activitysound" -> Msg.matching(List.of("none", "entity.player.levelup", "entity.experience_orb.pickup"), args[0]);
			default -> List.of();
		};
	}
}
