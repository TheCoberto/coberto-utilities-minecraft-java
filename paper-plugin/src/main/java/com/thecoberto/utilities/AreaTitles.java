package com.thecoberto.utilities;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Area Titles: a title on screen when a player walks into an area (a circle
 * around a point, at any height). It shows once per visit, and not again for
 * that player until the area's cooldown has passed. Saved in areas.yml.
 */
public final class AreaTitles implements TabExecutor, Listener {
	private static final int DEFAULT_COOLDOWN = 300; // seconds
	private static final Title.Times TIMES = Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(3000), Duration.ofMillis(1000));

	private static final class Area {
		String world;
		double x, z;
		int radius;
		String title;
		String subtitle = "";
		int cooldown = DEFAULT_COOLDOWN;

		boolean contains(Location loc) {
			if (!loc.getWorld().getName().equals(world)) return false;
			double dx = loc.getX() - x, dz = loc.getZ() - z;
			return dx * dx + dz * dz <= (double) radius * radius;
		}
	}

	private final Store store;
	private final Map<String, Area> areas = new LinkedHashMap<>();
	/** Areas each player is standing in right now. */
	private final Map<UUID, Set<String>> inside = new HashMap<>();
	/** "player|area" -> when the title last showed (ms). */
	private final Map<String, Long> lastShown = new HashMap<>();

	AreaTitles(JavaPlugin plugin) {
		store = new Store(plugin, "areas.yml");
		ConfigurationSection section = store.yaml.getConfigurationSection("areas");
		if (section != null) {
			for (String name : section.getKeys(false)) {
				ConfigurationSection s = section.getConfigurationSection(name);
				if (s == null) continue;
				Area a = new Area();
				a.world = s.getString("world");
				a.x = s.getDouble("x");
				a.z = s.getDouble("z");
				a.radius = Math.max(1, s.getInt("radius", 10));
				a.title = s.getString("title", name);
				a.subtitle = s.getString("subtitle", "");
				a.cooldown = Math.max(0, s.getInt("cooldown", DEFAULT_COOLDOWN));
				areas.put(name, a);
			}
		}
		plugin.getCommand("areatitle").setExecutor(this);
		plugin.getCommand("areatitle").setTabCompleter(this);
		Bukkit.getPluginManager().registerEvents(this, plugin);
		Bukkit.getScheduler().runTaskTimer(plugin, this::check, 20L, 10L);
	}

	private void save() {
		store.yaml.set("areas", null);
		areas.forEach((name, a) -> {
			String p = "areas." + name + ".";
			store.yaml.set(p + "world", a.world);
			store.yaml.set(p + "x", a.x);
			store.yaml.set(p + "z", a.z);
			store.yaml.set(p + "radius", a.radius);
			store.yaml.set(p + "title", a.title);
			store.yaml.set(p + "subtitle", a.subtitle);
			store.yaml.set(p + "cooldown", a.cooldown);
		});
		store.save();
	}

	private void check() {
		long now = System.currentTimeMillis();
		for (Player player : Bukkit.getOnlinePlayers()) {
			Set<String> was = inside.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>());
			Location loc = player.getLocation();
			for (Map.Entry<String, Area> e : areas.entrySet()) {
				String name = e.getKey();
				if (!e.getValue().contains(loc)) {
					was.remove(name);
					continue;
				}
				if (!was.add(name)) continue; // already inside: no repeat
				String key = player.getUniqueId() + "|" + name;
				Long last = lastShown.get(key);
				if (last != null && now - last < e.getValue().cooldown * 1000L) continue;
				lastShown.put(key, now);
				show(player, e.getValue());
			}
		}
	}

	private static void show(Player player, Area a) {
		player.showTitle(Title.title(Msg.legacy(a.title), Msg.legacy(a.subtitle), TIMES));
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		// Cooldowns stay, so rejoining in the area doesn't show it again right away.
		inside.remove(event.getPlayer().getUniqueId());
	}

	private void forget(String name) {
		lastShown.keySet().removeIf(k -> k.endsWith("|" + name));
		inside.values().forEach(s -> s.remove(name));
	}

	// ---------- /areatitle ----------

	private static Component prefix() {
		return Msg.prefix("Area Titles:", NamedTextColor.GOLD);
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "list";
		switch (sub) {
			case "list" -> {
				if (areas.isEmpty()) {
					sender.sendMessage(prefix().append(Component.text("No areas yet. Stand in the middle and use /areatitle create <name> <radius>.")));
					return true;
				}
				sender.sendMessage(prefix().append(Component.text(Msg.plural(areas.size(), "area"))));
				areas.forEach((name, a) -> sender.sendMessage(Component.text(" " + name + ": ", NamedTextColor.YELLOW)
						.append(Msg.legacy(a.title))
						.append(Component.text(a.subtitle.isEmpty() ? "" : " / ", NamedTextColor.GRAY)).append(Msg.legacy(a.subtitle))
						.append(Component.text(String.format(Locale.US, "  (%s %.0f, %.0f, radius %d, cooldown %s) ",
								a.world, a.x, a.z, a.radius, Msg.time(a.cooldown)), NamedTextColor.GRAY))
						.append(Msg.button("[Test]", NamedTextColor.AQUA, "/areatitle test " + name, true))));
				return true;
			}
			case "create" -> {
				if (args.length != 3) return false;
				Location loc = Msg.where(sender);
				if (loc == null) {
					Msg.error(sender, "Run it in-game, standing in the middle of the area.");
					return true;
				}
				String name = args[1].toLowerCase(Locale.ROOT);
				if (!name.matches("[a-z0-9_-]+")) {
					Msg.error(sender, "Use letters, numbers, - and _ for the name.");
					return true;
				}
				if (areas.containsKey(name)) {
					Msg.error(sender, "There's already an area called " + name + ". Use /areatitle remove " + name + " first.");
					return true;
				}
				Integer radius = Msg.parseInt(args[2], 1, 1000);
				if (radius == null) return false;
				Area a = new Area();
				a.world = loc.getWorld().getName();
				a.x = loc.getBlockX() + 0.5;
				a.z = loc.getBlockZ() + 0.5;
				a.radius = radius;
				a.title = "&6" + name.replace('_', ' ');
				areas.put(name, a);
				save();
				sender.sendMessage(prefix().append(Component.text("Made " + name + " (radius " + radius + "). Set its text: "))
						.append(Msg.button("[title]", NamedTextColor.AQUA, "/areatitle set " + name + " title ", false))
						.append(Component.text(" "))
						.append(Msg.button("[subtitle]", NamedTextColor.AQUA, "/areatitle set " + name + " subtitle ", false)));
				return true;
			}
			case "remove", "test" -> {
				if (args.length != 2) return false;
				String name = args[1].toLowerCase(Locale.ROOT);
				Area a = areas.get(name);
				if (a == null) {
					Msg.error(sender, "No area called " + name + ".");
					return true;
				}
				if (sub.equals("test")) {
					if (sender instanceof Player p) show(p, a);
					else Msg.error(sender, "Only players can see titles.");
					return true;
				}
				areas.remove(name);
				forget(name);
				save();
				sender.sendMessage(prefix().append(Component.text("Removed " + name + ".")));
				return true;
			}
			case "set" -> {
				if (args.length < 3) return false;
				String name = args[1].toLowerCase(Locale.ROOT);
				Area a = areas.get(name);
				if (a == null) {
					Msg.error(sender, "No area called " + name + ".");
					return true;
				}
				String what = args[2].toLowerCase(Locale.ROOT);
				String value = Msg.join(args, 3);
				switch (what) {
					case "title" -> {
						if (value.isEmpty()) return false;
						a.title = value;
					}
					case "subtitle" -> a.subtitle = value.equalsIgnoreCase("none") ? "" : value;
					case "radius", "cooldown" -> {
						Integer n = Msg.parseInt(value, what.equals("radius") ? 1 : 0, what.equals("radius") ? 1000 : 86400);
						if (n == null) return false;
						if (what.equals("radius")) a.radius = n;
						else a.cooldown = n;
					}
					case "here" -> {
						Location loc = Msg.where(sender);
						if (loc == null) return false;
						a.world = loc.getWorld().getName();
						a.x = loc.getBlockX() + 0.5;
						a.z = loc.getBlockZ() + 0.5;
					}
					default -> {
						return false;
					}
				}
				save();
				sender.sendMessage(prefix().append(Component.text("Changed " + name + "'s " + what + ". ")
						.append(Msg.button("[Test]", NamedTextColor.AQUA, "/areatitle test " + name, true))));
				return true;
			}
			default -> {
				return false;
			}
		}
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length == 1) return Msg.matching(List.of("create", "set", "remove", "test", "list"), args[0]);
		String sub = args[0].toLowerCase(Locale.ROOT);
		if (args.length == 2 && List.of("set", "remove", "test").contains(sub)) return Msg.matching(new ArrayList<>(areas.keySet()), args[1]);
		if (args.length == 3 && sub.equals("set")) return Msg.matching(List.of("title", "subtitle", "radius", "cooldown", "here"), args[2]);
		return List.of();
	}
}
