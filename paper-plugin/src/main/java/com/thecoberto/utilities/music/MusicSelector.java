package com.thecoberto.utilities.music;

import com.thecoberto.utilities.Money;
import com.thecoberto.utilities.Msg;
import com.thecoberto.utilities.SpecialBlocks;
import com.thecoberto.utilities.Store;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Music Selector: players use the block to pay Money and queue a song at that
 * selector. Each selector has its own queue and plays like a jukebox, heard
 * only nearby, with a floating "Now Playing" label above it. Each player waits
 * a while between songs. Operators change the price, cooldown, which songs can
 * be queued and the autoplay loop with /music (or Settings in the menu).
 */
public final class MusicSelector implements SpecialBlocks.Kind, TabExecutor {
	static final String ADMIN = "cobertoutilities.admin";

	private final SpecialBlocks blocks;
	private final Store store;
	private final MusicMenu menu;
	/** Position key -> station, made when a selector is first loaded. */
	private final Map<String, Station> stations = new HashMap<>();

	int price = 500;
	Map<String, Integer> prices = new HashMap<>();
	int cooldown = 150;
	int maxQueue = 5;
	List<String> allowed = new ArrayList<>();
	boolean autoplay = false;
	boolean shuffle = false;
	List<String> playlist = new ArrayList<>();
	Map<UUID, Long> cooldowns = new HashMap<>();

	public MusicSelector(JavaPlugin plugin, SpecialBlocks blocks) {
		this.blocks = blocks;
		blocks.register(this);
		store = new Store(plugin, "music.yml");
		var y = store.yaml;
		if (y.contains("price")) {
			price = y.getInt("price");
			ConfigurationSection p = y.getConfigurationSection("prices");
			if (p != null) for (String k : p.getKeys(false)) prices.put(k, p.getInt(k));
			cooldown = y.getInt("cooldown");
			maxQueue = Math.max(1, y.getInt("maxQueue"));
			allowed = new ArrayList<>(y.getStringList("allowed"));
			autoplay = y.getBoolean("autoplay");
			shuffle = y.getBoolean("shuffle");
			playlist = new ArrayList<>(y.getStringList("playlist"));
			ConfigurationSection c = y.getConfigurationSection("cooldowns");
			if (c != null) for (String k : c.getKeys(false)) {
				try {
					cooldowns.put(UUID.fromString(k), c.getLong(k));
				} catch (IllegalArgumentException ignored) {
					// Not a player id.
				}
			}
		} else {
			// New: every song can be queued and is in the autoplay loop.
			allowed = new ArrayList<>(Track.all().keySet());
			playlist = new ArrayList<>(Track.all().keySet());
		}
		allowed.removeIf(id -> !Track.all().containsKey(id));
		playlist.removeIf(id -> !Track.all().containsKey(id));

		menu = new MusicMenu(plugin, this);
		Bukkit.getPluginManager().registerEvents(menu, plugin);
		for (String name : List.of("music", "musicskip")) {
			plugin.getCommand(name).setExecutor(this);
			plugin.getCommand(name).setTabCompleter(this);
		}
		Bukkit.getScheduler().runTaskTimer(plugin, () -> {
			for (Block block : blocks.loaded(this)) station(block).tick();
		}, 20, 20);
	}

	void save() {
		var y = store.yaml;
		y.set("price", price);
		y.set("prices", null);
		prices.forEach((k, v) -> y.set("prices." + k, v));
		y.set("cooldown", cooldown);
		y.set("maxQueue", maxQueue);
		y.set("allowed", allowed);
		y.set("autoplay", autoplay);
		y.set("shuffle", shuffle);
		y.set("playlist", playlist);
		y.set("cooldowns", null);
		long now = System.currentTimeMillis();
		cooldowns.forEach((uuid, until) -> {
			if (until > now) y.set("cooldowns." + uuid, until);
		});
		store.save();
	}

	Station station(Block block) {
		return stations.computeIfAbsent(SpecialBlocks.key(block), k -> new Station(this, block));
	}

	int priceOf(String id) {
		return prices.getOrDefault(id, price);
	}

	// ---------- The block ----------

	@Override public String id() { return "music_selector"; }
	@Override public String name() { return "Music Selector"; }
	@Override public NamedTextColor color() { return NamedTextColor.LIGHT_PURPLE; }
	@Override public String lore() { return "Players use it to pay and queue a song."; }
	@Override public Material defaultMaterial() { return Material.JUKEBOX; }

	@Override
	public void placed(Player player, Block block) {
		station(block).updateLabel();
	}

	@Override
	public void use(Player player, Block block, boolean sneak) {
		menu.open(player, station(block));
	}

	@Override
	public void removed(Block block) {
		Station station = stations.remove(SpecialBlocks.key(block));
		if (station != null) station.removed();
		else com.thecoberto.utilities.Labels.remove(block, Station.LABEL_HEIGHT, Station.LABEL_TAG);
	}

	// ---------- Queueing ----------

	int cooldownLeft(Player player) {
		Long until = cooldowns.get(player.getUniqueId());
		return until == null ? 0 : (int) Math.max(0, Math.ceil((until - System.currentTimeMillis()) / 1000.0));
	}

	void queueSong(Player player, Station station, Track track) {
		if (!allowed.contains(track.id())) {
			error(player, track.name() + " can't be queued.");
			return;
		}
		int wait = cooldownLeft(player);
		if (wait > 0) {
			error(player, "Wait " + Msg.time(wait) + " before queueing another song.");
			return;
		}
		if (station.queue.size() >= maxQueue) {
			error(player, "This queue is full (" + maxQueue + " songs). Try again later.");
			return;
		}
		boolean queued = station.queue.stream().anyMatch(q -> q.id().equals(track.id()))
				|| (station.current != null && station.current.by() != null && station.current.id().equals(track.id()));
		if (queued) {
			error(player, track.name() + " is already playing or queued here.");
			return;
		}
		int cost = priceOf(track.id());
		if (cost > 0 && !Money.available()) {
			error(player, "Songs cost money, but the server has no economy plugin.");
			return;
		}
		if (!Money.take(player, cost)) {
			error(player, "You need " + Money.format(cost) + " (you have " + Money.format(Money.get(player)) + ").");
			return;
		}
		cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + cooldown * 1000L);
		save();

		// A song someone paid for interrupts the autoplay loop.
		boolean startNow = station.current == null || station.current.by() == null;
		station.queue.add(new Station.Entry(track.id(), player.getName()));
		if (startNow) {
			station.playNext();
		} else {
			station.tellNearby(prefix().append(Component.text(player.getName() + " queued "))
					.append(Component.text(track.name(), NamedTextColor.AQUA))
					.append(Component.text(" (#" + station.queue.size() + " in line).")));
			station.updateLabel();
		}
		player.playSound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
	}

	private static void error(Player player, String message) {
		player.sendMessage(prefix().append(Component.text(message, NamedTextColor.RED)));
	}

	static Component prefix() {
		return Msg.prefix("Music:", NamedTextColor.LIGHT_PURPLE);
	}

	// ---------- Commands ----------

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (command.getName().equals("musicskip")) {
			Location from = Msg.where(sender);
			if (from == null) {
				Msg.error(sender, "Run this as a player or from a command block.");
				return true;
			}
			var nearest = stations.values().stream()
					.filter(st -> st.current != null && st.block.getWorld() == from.getWorld())
					.filter(st -> st.block.getLocation().distanceSquared(from) <= 64 * 64)
					.min(Comparator.comparingDouble(st -> st.block.getLocation().distanceSquared(from)));
			if (nearest.isEmpty()) {
				Msg.error(sender, "No Music Selector within 64 blocks is playing a song.");
				return true;
			}
			nearest.get().skip();
			return true;
		}

		if (args.length == 0) {
			showSettings(sender);
			return true;
		}
		String sub = args[0].toLowerCase(Locale.ROOT);
		switch (sub) {
			case "price", "cooldown", "maxqueue" -> {
				if (args.length != 2) return false;
				Integer value = switch (sub) {
					case "price" -> Msg.parseInt(args[1], 0, 1_000_000_000);
					case "cooldown" -> Msg.parseInt(args[1], 0, 86400);
					default -> Msg.parseInt(args[1], 1, 20);
				};
				if (value == null) return false;
				switch (sub) {
					case "price" -> price = value;
					case "cooldown" -> cooldown = value;
					default -> maxQueue = value;
				}
				save();
				showSettings(sender);
			}
			case "autoplay", "shuffle" -> {
				Boolean on = args.length == 2 ? Msg.parseBool(args[1]) : null;
				if (on == null) return false;
				if (sub.equals("shuffle")) shuffle = on;
				else {
					autoplay = on;
					for (Station station : stations.values()) {
						station.resetAutoplay();
						if (!on && station.current != null && station.current.by() == null) {
							station.stopCurrent();
							station.updateLabel();
						}
					}
				}
				save();
				showSettings(sender);
			}
			case "songs" -> showSongs(sender);
			case "allow", "playlist", "songprice" -> {
				if (args.length != 3) return false;
				String id = args[1].toLowerCase(Locale.ROOT);
				if (!Track.all().containsKey(id)) {
					Msg.error(sender, "No song called " + id + ". Use /music songs to see them.");
					return true;
				}
				if (sub.equals("songprice")) {
					if (args[2].equalsIgnoreCase("default")) prices.remove(id);
					else {
						Integer money = Msg.parseInt(args[2], 0, 1_000_000_000);
						if (money == null) return false;
						prices.put(id, money);
					}
				} else {
					Boolean on = Msg.parseBool(args[2]);
					if (on == null) return false;
					List<String> list = sub.equals("allow") ? allowed : playlist;
					list.remove(id);
					if (on) list.add(id);
					// Keep them in disc order.
					List<String> order = new ArrayList<>(Track.all().keySet());
					list.sort(Comparator.comparingInt(order::indexOf));
				}
				save();
				showSongs(sender);
			}
			case "stopall" -> {
				for (Station station : stations.values()) station.clear();
				sender.sendMessage(prefix().append(Component.text("Stopped every loaded selector.")));
			}
			default -> {
				return false;
			}
		}
		return true;
	}

	void showSettings(CommandSender sender) {
		sender.sendMessage(Component.text("Music Settings", NamedTextColor.GOLD)
				.append(Component.text(" (every Music Selector; each keeps its own queue)", NamedTextColor.WHITE))
				.append(Component.text("\n Price: ")).append(Component.text(Money.format(price), NamedTextColor.GREEN))
				.append(Component.text(" per song ")).append(Msg.button("[Change]", NamedTextColor.YELLOW, "/music price " + price, false))
				.append(Component.text("\n Cooldown: " + Msg.time(cooldown) + " between a player's songs "))
				.append(Msg.button("[Change]", NamedTextColor.YELLOW, "/music cooldown " + cooldown, false))
				.append(Component.text("\n Queue: up to " + maxQueue + " songs per selector "))
				.append(Msg.button("[Change]", NamedTextColor.YELLOW, "/music maxqueue " + maxQueue, false))
				.append(Component.text("\n Songs players can queue: " + allowed.size() + "/" + Track.all().size() + " "))
				.append(Msg.button("[Songs and prices]", NamedTextColor.YELLOW, "/music songs", true))
				.append(Component.text("\n Autoplay: "))
				.append(autoplay ? Component.text("on", NamedTextColor.GREEN) : Component.text("off", NamedTextColor.RED))
				.append(Component.text(autoplay ? " (" + playlist.size() + " songs" + (shuffle ? ", shuffled" : "") + ")" : ""))
				.append(Component.text(" ")).append(Msg.button(autoplay ? "[Turn off]" : "[Turn on]", NamedTextColor.YELLOW, "/music autoplay " + !autoplay, true))
				.append(Component.text(" ")).append(Msg.button(shuffle ? "[Shuffle: on]" : "[Shuffle: off]", NamedTextColor.YELLOW, "/music shuffle " + !shuffle, true))
				.append(Component.text("\n ")).append(Msg.button("[Stop every selector]", NamedTextColor.RED, "/music stopall", true)));
	}

	private void showSongs(CommandSender sender) {
		Component text = Component.text("Songs", NamedTextColor.GOLD)
				.append(Component.text(": click to change. ", NamedTextColor.WHITE)).append(Component.text("Queue", NamedTextColor.AQUA))
				.append(Component.text(" = players can queue it, ", NamedTextColor.WHITE)).append(Component.text("Auto", NamedTextColor.AQUA))
				.append(Component.text(" = in the autoplay loop. ", NamedTextColor.WHITE))
				.append(Msg.button("[Back]", NamedTextColor.YELLOW, "/music", true));
		for (Track track : Track.all().values()) {
			String id = track.id();
			boolean isAllowed = allowed.contains(id);
			boolean inPlaylist = playlist.contains(id);
			text = text.append(Component.text("\n "))
					.append(Msg.toggle("Queue", isAllowed, "/music allow " + id + " " + !isAllowed))
					.append(Component.text(" ")).append(Msg.toggle("Auto", inPlaylist, "/music playlist " + id + " " + !inPlaylist))
					.append(Component.text(" ")).append(Component.text(track.name(), NamedTextColor.WHITE))
					.append(Component.text(" ")).append(Msg.button("[" + Money.format(priceOf(id)) + (prices.containsKey(id) ? "" : " (default)") + "]",
							NamedTextColor.GREEN, "/music songprice " + id + " " + priceOf(id), false));
		}
		sender.sendMessage(text.append(Component.text("\n Song prices: type a number, or \"default\" to use the normal price.", NamedTextColor.GRAY)));
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (!command.getName().equals("music")) return List.of();
		if (args.length == 1) {
			return Msg.matching(List.of("price", "cooldown", "maxqueue", "autoplay", "shuffle", "songs", "allow", "playlist", "songprice", "stopall"), args[0]);
		}
		String sub = args[0].toLowerCase(Locale.ROOT);
		if (args.length == 2 && List.of("allow", "playlist", "songprice").contains(sub)) return Msg.matching(new ArrayList<>(Track.all().keySet()), args[1]);
		if (args.length == 2 && List.of("autoplay", "shuffle").contains(sub)) return Msg.matching(List.of("true", "false"), args[1]);
		if (args.length == 3 && List.of("allow", "playlist").contains(sub)) return Msg.matching(List.of("true", "false"), args[2]);
		if (args.length == 3 && sub.equals("songprice")) return Msg.matching(List.of("default"), args[2]);
		return List.of();
	}
}
