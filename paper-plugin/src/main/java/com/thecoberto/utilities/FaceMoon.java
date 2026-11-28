package com.thecoberto.utilities;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Face Moon: on some nights the moon turns into TheCoberto's face. At dusk the
 * plugin decides if tonight is a face night (by chance, every few nights, or on
 * chosen moon phases), sends Java players a small resource pack that swaps the
 * moon texture, and takes it away at sunrise. Bedrock players (Geyser) can't be
 * sent packs while playing, so they see the normal moon.
 * Settings live in face-moon.yml and are changed with /facemoon.
 */
public final class FaceMoon implements Listener, TabExecutor {
	private static final UUID PACK_ID = UUID.fromString("6d1f3b8a-2c4e-4f7a-9e15-8b0c7a3d5f62");
	private static final long DUSK = 12_500, DAWN = 23_500; // the moon is up between these
	private static final List<String> MODES = List.of("random", "every", "phases");
	private static final List<String> PHASES = List.of("full", "waning_gibbous", "third_quarter", "waning_crescent",
		"new", "waxing_crescent", "first_quarter", "waxing_gibbous");

	private final JavaPlugin plugin;
	private final Store store;
	private boolean wasNight;
	private volatile boolean active; // read by Geyser's threads
	private FaceMoonBedrock bedrock; // null without Geyser

	public FaceMoon(JavaPlugin plugin) {
		this.plugin = plugin;
		this.store = new Store(plugin, "face-moon.yml");
		var y = store.yaml;
		y.options().setHeader(List.of(
			"Face Moon: on some nights the moon becomes TheCoberto's face.",
			"Java players get the pack at dusk. Bedrock players (Geyser) get bedrock_pack_url when they",
			"join during a face night, so ones already online see it after they rejoin. bedrock_pack_url is a",
			"file in this folder (FaceMoonPack.mcpack) or an https:// link served as application/zip.",
			"Change these with /facemoon, or edit this file and run /facemoon reload.",
			"mode: random (chance each night, with at least min_gap plain nights between),",
			"      every (every N nights), or phases (on these moon phases: " + String.join(", ", PHASES) + ").",
			"announce: chat message at dusk on a face night (& colors). Empty for none.",
			"prompt: text on the pack prompt for players who haven't allowed server packs."));
		y.addDefault("enabled", true);
		y.addDefault("world", "world");
		y.addDefault("mode", "random");
		y.addDefault("chance", 0.2);
		y.addDefault("min_gap", 2);
		y.addDefault("every", 5);
		y.addDefault("phases", List.of("full"));
		y.addDefault("announce", "&8&oThe moon is watching tonight...");
		y.addDefault("prompt", "The moon looks... different tonight.");
		y.addDefault("pack_url", "https://github.com/TheCoberto/server-resource-pack/releases/download/v1.2.0/FaceMoonPack.zip");
		y.addDefault("pack_sha1", "8de05aab58b5de90df1cffb8cb9c4e031372b563");
		y.addDefault("bedrock_pack_url", "FaceMoonPack.mcpack"); // a file in this folder, or an https:// link served as application/zip
		y.addDefault("bedrock_rejoin_hint", "&8&o(Bedrock: rejoin to see the moon tonight.)");
		y.options().copyDefaults(true);
		store.save();
		active = y.getBoolean("state.active");

		if (Bukkit.getPluginManager().getPlugin("Geyser-Spigot") != null) {
			try {
				bedrock = new FaceMoonBedrock(plugin, () -> active);
				Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> bedrock.setUrl(plugin, y.getString("bedrock_pack_url")));
			} catch (Throwable t) {
				bedrock = null;
				plugin.getLogger().warning("Face Moon: couldn't hook into Geyser, so Bedrock players see the normal moon (" + t + ")");
			}
		}

		Bukkit.getPluginManager().registerEvents(this, plugin);
		plugin.getCommand("facemoon").setExecutor(this);
		plugin.getCommand("facemoon").setTabCompleter(this);

		World w = world();
		wasNight = w != null && isNight(w);
		if (active() && !wasNight) setActive(false); // restarted after the night ended
		Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, 100L);
	}

	// ---- settings ----

	private World world() {
		World w = Bukkit.getWorld(store.yaml.getString("world", "world"));
		return w != null ? w : Bukkit.getWorlds().get(0);
	}

	private boolean active() { return active; }

	private long night(World w) { return w.getFullTime() / 24_000; }

	private static boolean isNight(World w) {
		long t = w.getTime();
		return t >= DUSK && t < DAWN;
	}

	private String phaseName(World w) { return PHASES.get((int) (night(w) % 8)); }

	// ---- day and night ----

	private void tick() {
		World w = world();
		if (w == null) return;
		boolean night = isNight(w);
		if (night == wasNight) return;
		wasNight = night;
		if (night) {
			String forced = store.yaml.getString("state.next");
			store.yaml.set("state.next", null);
			boolean face = forced != null ? forced.equals("face") : store.yaml.getBoolean("enabled") && decide(w);
			if (face) start(w);
			else store.save();
		} else if (active()) {
			setActive(false);
		}
	}

	private boolean decide(World w) {
		var y = store.yaml;
		long n = night(w);
		return switch (y.getString("mode", "random").toLowerCase(Locale.ROOT)) {
			case "every" -> n % Math.max(1, y.getInt("every", 5)) == 0;
			case "phases" -> y.getStringList("phases").stream().anyMatch(p -> p.equalsIgnoreCase(phaseName(w)));
			default -> n - y.getLong("state.last_night", Long.MIN_VALUE / 2) > y.getInt("min_gap", 2)
				&& ThreadLocalRandom.current().nextDouble() < y.getDouble("chance", 0.2);
		};
	}

	private void start(World w) {
		store.yaml.set("state.last_night", night(w));
		setActive(true);
		String msg = store.yaml.getString("announce", "");
		if (!msg.isBlank()) Bukkit.broadcast(Msg.legacy(msg));
		String hint = store.yaml.getString("bedrock_rejoin_hint", "");
		if (bedrock != null && !hint.isBlank()) for (Player p : Bukkit.getOnlinePlayers())
			if (bedrock.isBedrock(p.getUniqueId())) p.sendMessage(Msg.legacy(hint));
	}

	private void setActive(boolean on) {
		active = on;
		store.yaml.set("state.active", on);
		store.save();
		for (Player p : Bukkit.getOnlinePlayers()) {
			if (on) send(p);
			else p.removeResourcePacks(PACK_ID);
		}
	}

	private void send(Player p) {
		if (bedrock != null && bedrock.isBedrock(p.getUniqueId())) return; // Geyser gives them the Bedrock pack at join
		String url = store.yaml.getString("pack_url", "");
		String sha = store.yaml.getString("pack_sha1", "");
		if (url.isBlank()) return;
		byte[] hash = sha.matches("[0-9a-fA-F]{40}") ? HexFormat.of().parseHex(sha) : null;
		p.addResourcePack(PACK_ID, url, hash, store.yaml.getString("prompt", ""), false);
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent e) {
		if (active()) Bukkit.getScheduler().runTaskLater(plugin, () -> {
			if (e.getPlayer().isOnline() && active()) send(e.getPlayer());
		}, 60L); // after the server's own pack
	}

	// ---- /facemoon ----

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		var y = store.yaml;
		String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
		switch (sub) {
			case "status" -> {
				World w = world();
				sender.sendMessage(Component.text("Face Moon", NamedTextColor.GOLD)
					.append(Component.text(y.getBoolean("enabled") ? " (on)" : " (off)", NamedTextColor.GRAY)));
				sender.sendMessage(Component.text("Mode: " + describe(), NamedTextColor.YELLOW));
				sender.sendMessage(Component.text("Tonight's moon: " + phaseName(w).replace('_', ' ')
					+ (active() ? " - the face is up now" : ""), NamedTextColor.YELLOW));
				String next = y.getString("state.next");
				if (next != null) sender.sendMessage(Component.text("Next night is set to: " + (next.equals("face") ? "face" : "plain"), NamedTextColor.YELLOW));
				sender.sendMessage(Component.text("Pack: " + y.getString("pack_url") + (y.getString("pack_sha1", "").isBlank() ? " (no sha1 set)" : ""), NamedTextColor.GRAY));
				sender.sendMessage(Component.text("Bedrock: " + (bedrock == null ? "off (no Geyser)" : bedrock.loaded() ? "pack loaded, given to Bedrock players who join on face nights" : "pack not loaded (check bedrock_pack_url)"), NamedTextColor.GRAY));
			}
			case "on", "off" -> { y.set("enabled", sub.equals("on")); store.save(); ok(sender, "Face Moon is " + sub + "."); }
			case "now" -> { start(world()); ok(sender, "The face is up for everyone until the next sunrise."); }
			case "stop" -> { setActive(false); ok(sender, "The moon is back to normal."); }
			case "tonight" -> { y.set("state.next", "face"); store.save(); ok(sender, "The next night will be a face night."); }
			case "skip" -> { y.set("state.next", "plain"); store.save(); ok(sender, "The next night will have a normal moon."); }
			case "mode" -> {
				if (args.length < 2 || !MODES.contains(args[1].toLowerCase(Locale.ROOT))) { Msg.error(sender, "Mode: random, every or phases."); return true; }
				y.set("mode", args[1].toLowerCase(Locale.ROOT)); store.save(); ok(sender, "Mode: " + describe());
			}
			case "chance" -> {
				Integer pct = args.length < 2 ? null : Msg.parseInt(args[1].replace("%", ""), 0, 100);
				if (pct == null) { Msg.error(sender, "Give a percent, 0-100."); return true; }
				y.set("chance", pct / 100.0); store.save(); ok(sender, "Chance each night: " + pct + "%.");
			}
			case "gap", "every" -> {
				Integer n = args.length < 2 ? null : Msg.parseInt(args[1], sub.equals("gap") ? 0 : 1, 1000);
				if (n == null) { Msg.error(sender, "Give a number of nights."); return true; }
				y.set(sub.equals("gap") ? "min_gap" : "every", n); store.save(); ok(sender, "Mode: " + describe());
			}
			case "phases" -> {
				List<String> list = new ArrayList<>();
				for (int i = 1; i < args.length; i++) {
					String p = args[i].toLowerCase(Locale.ROOT).replace(",", "");
					if (!PHASES.contains(p)) { Msg.error(sender, "Phases: " + String.join(", ", PHASES)); return true; }
					list.add(p);
				}
				if (list.isEmpty()) { Msg.error(sender, "Name at least one phase: " + String.join(", ", PHASES)); return true; }
				y.set("phases", list); store.save(); ok(sender, "Mode: " + describe());
			}
			case "announce" -> {
				String msg = Msg.join(args, 1);
				y.set("announce", msg.equalsIgnoreCase("none") ? "" : msg); store.save(); ok(sender, msg.equalsIgnoreCase("none") || msg.isBlank() ? "No announcement." : "Announcement set.");
			}
			case "pack" -> {
				if (args.length < 3 || !args[2].matches("[0-9a-fA-F]{40}")) { Msg.error(sender, "/facemoon pack <url> <sha1>"); return true; }
				y.set("pack_url", args[1]); y.set("pack_sha1", args[2].toLowerCase(Locale.ROOT)); store.save(); ok(sender, "Pack set.");
			}
			case "reload" -> {
				try { y.load(new java.io.File(plugin.getDataFolder(), "face-moon.yml")); } catch (Exception ex) { Msg.error(sender, "Couldn't read face-moon.yml: " + ex.getMessage()); return true; }
				if (bedrock != null) Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> bedrock.setUrl(plugin, y.getString("bedrock_pack_url")));
				ok(sender, "Reloaded. Mode: " + describe());
			}
			default -> { return false; }
		}
		return true;
	}

	private String describe() {
		var y = store.yaml;
		return switch (y.getString("mode", "random").toLowerCase(Locale.ROOT)) {
			case "every" -> "every " + Msg.plural(y.getInt("every", 5), "night");
			case "phases" -> "on " + String.join(", ", y.getStringList("phases")) + " moons";
			default -> "random, " + Math.round(y.getDouble("chance", 0.2) * 100) + "% a night, at least "
				+ Msg.plural(y.getInt("min_gap", 2), "plain night") + " between";
		};
	}

	private static void ok(CommandSender sender, String text) {
		sender.sendMessage(Component.text(text, NamedTextColor.GREEN));
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length == 1) return Msg.matching(List.of("status", "on", "off", "now", "stop", "tonight", "skip", "mode",
			"chance", "gap", "every", "phases", "announce", "pack", "reload"), args[0]);
		if (args.length == 2 && args[0].equalsIgnoreCase("mode")) return Msg.matching(MODES, args[1]);
		if (args.length >= 2 && args[0].equalsIgnoreCase("phases")) return Msg.matching(PHASES, args[args.length - 1]);
		return List.of();
	}
}
