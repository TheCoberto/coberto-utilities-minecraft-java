package com.thecoberto.utilities.kingdom;

import com.thecoberto.utilities.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Clan Archive: keeps a copy of every SimpleClans clan in clan-archive.yml, so a clan that is
 * deleted (by the inactivity purge or by its players) can be brought back with /clanrestore.
 *
 * The copy is refreshed every few minutes and when the server stops. A clan that's no longer in
 * SimpleClans is marked deleted. If the inactivity purge deleted it, any of its members at that
 * time can restore it; if its players disbanded it, only its old leaders can. Whoever restores it
 * leads it, the old leaders come back as leaders too, and every old member who isn't in another
 * clan is added back. The bank isn't restored (that would let a clan disband and restore to copy
 * its money); kills are kept per player by SimpleClans anyway.
 */
public final class ClanArchive implements TabExecutor, Listener {
	private static final String PERMISSION_ADMIN = "cobertoutilities.admin";
	private static final long EVERY_TICKS = 20L * 60 * 5;

	private final JavaPlugin plugin;
	private final File file;
	private YamlConfiguration archive;
	private String lastSaved = "";
	private boolean firstLook = true;
	/** Tags restored since the Kingdom Board last looked, with who restored them. */
	private final Map<String, String> restoredBy = new ConcurrentHashMap<>();

	public ClanArchive(JavaPlugin plugin) {
		this.plugin = plugin;
		this.file = new File(plugin.getDataFolder(), "clan-archive.yml");
		archive = YamlConfiguration.loadConfiguration(file);
		lastSaved = archive.saveToString();
		plugin.getCommand("clanrestore").setExecutor(this);
		plugin.getCommand("clanrestore").setTabCompleter(this);
		Bukkit.getPluginManager().registerEvents(this, plugin);
		// SimpleClans purges inactive clans while it starts; look once it has, then every 5 minutes.
		Bukkit.getScheduler().runTaskTimer(plugin, this::snapshot, 20L * 20, EVERY_TICKS);
	}

	/** Who restored this clan, once; the Kingdom Board uses it to say "restored" instead of "new". */
	String takeRestoredBy(String tag) {
		return restoredBy.remove(tag.toUpperCase(Locale.ROOT));
	}

	// ---------------------------------------------------------------- snapshots

	/** Copies every live clan into the archive and marks the missing ones deleted. Main thread. */
	public void snapshot() {
		if (!ClanReader.available()) return;
		List<ClanWriter.Saved> live;
		try {
			live = ClanWriter.readAll();
		} catch (ReflectiveOperationException | RuntimeException e) {
			plugin.getLogger().warning("Clan Archive couldn't read SimpleClans: " + e);
			return;
		}
		ConfigurationSection clans = section();
		long now = System.currentTimeMillis();
		Set<String> liveTags = new HashSet<>();
		Set<Long> liveFounded = new HashSet<>();
		for (ClanWriter.Saved c : live) {
			String key = c.tag().toUpperCase(Locale.ROOT);
			liveTags.add(key);
			liveFounded.add(c.founded());
			ConfigurationSection before = clans.getConfigurationSection(key + ".members");
			clans.set(key, null);
			write(clans.createSection(key), c, before);
		}
		for (String key : new ArrayList<>(clans.getKeys(false))) {
			ConfigurationSection c = clans.getConfigurationSection(key);
			if (c == null || liveTags.contains(key) || c.getLong("deleted") > 0) continue;
			// A clan whose tag changed is still alive under its new tag: drop the old copy.
			if (liveFounded.contains(c.getLong("founded"))) {
				clans.set(key, null);
				continue;
			}
			c.set("deleted", now);
			// SimpleClans only purges while it starts, so a clan already gone at the first look was
			// purged for inactivity; one that goes later was disbanded by its players (or staff).
			c.set("how", firstLook ? "purged" : "disbanded");
		}
		firstLook = false;
		save();
	}

	/** Purged clans can be restored by any old member; disbanded ones only by an old leader. */
	private static boolean mayRestore(ConfigurationSection entry, UUID player) {
		if (!entry.isConfigurationSection("members." + player)) return false;
		return !"disbanded".equals(entry.getString("how")) || entry.getBoolean("members." + player + ".leader");
	}

	/** before: the clan's members as last saved, for names SimpleClans doesn't know. */
	private static void write(ConfigurationSection s, ClanWriter.Saved c, ConfigurationSection before) {
		s.set("tag", c.tag());
		s.set("color-tag", c.colorTag());
		s.set("name", c.name());
		s.set("description", c.description());
		s.set("founded", c.founded());
		s.set("friendly-fire", c.friendlyFire());
		s.set("deleted", 0L);
		if (c.home() != null && c.home().getWorld() != null) {
			Location h = c.home();
			s.set("home", h.getWorld().getName() + "," + h.getX() + "," + h.getY() + "," + h.getZ() + "," + h.getYaw() + "," + h.getPitch());
		}
		s.set("default-rank", c.defaultRank());
		List<Map<String, Object>> ranks = new ArrayList<>();
		for (ClanWriter.SavedRank r : c.ranks()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", r.name());
			m.put("display", r.display());
			m.put("permissions", new ArrayList<>(r.permissions()));
			ranks.add(m);
		}
		s.set("ranks", ranks);
		s.set("bulletin", c.bulletin());
		s.set("allies", c.allies());
		s.set("rivals", c.rivals());
		ConfigurationSection members = s.createSection("members");
		for (ClanWriter.SavedMember m : c.members()) {
			ConfigurationSection ms = members.createSection(m.uuid().toString());
			String old = before == null ? null : before.getString(m.uuid() + ".name");
			ms.set("name", ClanWriter.knownName(m.name()) || old == null ? m.name() : old);
			ms.set("leader", m.leader());
			ms.set("trusted", m.trusted());
			ms.set("rank", m.rank());
		}
	}

	private static ClanWriter.Saved read(ConfigurationSection s) {
		List<ClanWriter.SavedRank> ranks = new ArrayList<>();
		for (Map<?, ?> m : s.getMapList("ranks")) {
			Object perms = m.get("permissions");
			Set<String> set = new HashSet<>();
			if (perms instanceof Collection<?> list) for (Object p : list) set.add(String.valueOf(p));
			ranks.add(new ClanWriter.SavedRank(String.valueOf(m.get("name")),
					m.get("display") == null ? String.valueOf(m.get("name")) : String.valueOf(m.get("display")), set));
		}
		List<ClanWriter.SavedMember> members = new ArrayList<>();
		ConfigurationSection ms = s.getConfigurationSection("members");
		if (ms != null) {
			for (String id : ms.getKeys(false)) {
				ConfigurationSection m = ms.getConfigurationSection(id);
				if (m == null) continue;
				try {
					members.add(new ClanWriter.SavedMember(UUID.fromString(id), m.getString("name", id),
							m.getBoolean("leader"), m.getBoolean("trusted"), m.getString("rank", "")));
				} catch (IllegalArgumentException ignored) {
					// not a UUID; skip it
				}
			}
		}
		String tag = s.getString("tag", s.getName());
		return new ClanWriter.Saved(tag, s.getString("color-tag", tag), s.getString("name", tag),
				s.getString("description", ""), s.getLong("founded"), s.getBoolean("friendly-fire"),
				home(s.getString("home", "")), s.getString("default-rank", ""), ranks, s.getStringList("bulletin"),
				s.getStringList("allies"), s.getStringList("rivals"), members);
	}

	private static Location home(String text) {
		String[] p = text.split(",");
		if (p.length != 6) return null;
		World world = Bukkit.getWorld(p[0]);
		if (world == null) return null;
		try {
			return new Location(world, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
					Float.parseFloat(p[4]), Float.parseFloat(p[5]));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private ConfigurationSection section() {
		ConfigurationSection clans = archive.getConfigurationSection("clans");
		return clans != null ? clans : archive.createSection("clans");
	}

	private void save() {
		String text = archive.saveToString();
		if (text.equals(lastSaved)) return;
		try {
			archive.save(file);
			lastSaved = text;
		} catch (IOException e) {
			plugin.getLogger().warning("Clan Archive couldn't save " + file.getName() + ": " + e.getMessage());
		}
	}

	// ---------------------------------------------------------------- deleted clans

	/** Deleted clans, newest deletion first. */
	private List<ConfigurationSection> deleted() {
		List<ConfigurationSection> out = new ArrayList<>();
		ConfigurationSection clans = section();
		for (String key : clans.getKeys(false)) {
			ConfigurationSection c = clans.getConfigurationSection(key);
			if (c != null && c.getLong("deleted") > 0) out.add(c);
		}
		out.sort((a, b) -> Long.compare(b.getLong("deleted"), a.getLong("deleted")));
		return out;
	}

	/** The deleted clans this player may restore. */
	private List<ConfigurationSection> deletedWith(UUID player) {
		return deleted().stream().filter(c -> mayRestore(c, player)).toList();
	}

	private ConfigurationSection deleted(String tag) {
		ConfigurationSection c = section().getConfigurationSection(tag.toUpperCase(Locale.ROOT));
		return c != null && c.getLong("deleted") > 0 ? c : null;
	}

	private static String label(ConfigurationSection c) {
		return "[" + c.getString("tag", c.getName()) + "] " + c.getString("name", "");
	}

	// ---------------------------------------------------------------- join reminder

	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		Player player = event.getPlayer();
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			if (!player.isOnline() || !ClanReader.available()) return;
			try {
				if (ClanWriter.inClan(player.getUniqueId())) return;
			} catch (ReflectiveOperationException | RuntimeException e) {
				return;
			}
			List<ConfigurationSection> mine = deletedWith(player.getUniqueId());
			if (mine.isEmpty()) return;
			ConfigurationSection c = mine.get(0);
			String tag = c.getString("tag", c.getName());
			player.sendMessage(Msg.prefix("Clans", NamedTextColor.GOLD)
					.append(Component.text("Your old clan ", NamedTextColor.YELLOW))
					.append(Component.text(label(c), NamedTextColor.WHITE))
					.append(Component.text("disbanded".equals(c.getString("how")) ? " was disbanded. Bring it back: "
							: " was deleted while it was inactive. Bring it back and lead it: ", NamedTextColor.YELLOW))
					.append(Msg.button("[Restore]", NamedTextColor.GREEN, "/clanrestore " + tag, "Restore " + label(c) + " with you as leader", true)));
		}, 20L * 8);
	}

	// ---------------------------------------------------------------- restore

	/**
	 * Restores a deleted clan with this player (online or not) as its leader. Tells whoever asked
	 * what happened, and returns false when it didn't work.
	 */
	private boolean restore(ConfigurationSection entry, UUID leaderId, String leaderName, CommandSender asker) {
		ClanWriter.Saved saved = read(entry);
		ClanWriter.Result result;
		try {
			if (ClanWriter.inClan(leaderId)) {
				Msg.error(asker, (asker instanceof Player p && p.getUniqueId().equals(leaderId) ? "You're" : leaderName + " is")
						+ " already in a clan. Leave it first (/clan resign).");
				return false;
			}
			if (ClanWriter.tagTaken(saved.tag())) {
				Msg.error(asker, "Another clan is using the tag [" + saved.tag() + "] now, so this one can't come back under it. Ask staff.");
				return false;
			}
			result = ClanWriter.restore(saved, leaderId, leaderName);
		} catch (ReflectiveOperationException | RuntimeException e) {
			plugin.getLogger().warning("Clan Archive couldn't restore " + saved.tag() + ": " + e);
			Msg.error(asker, "Something went wrong restoring the clan; the console has the error.");
			return false;
		}
		restoredBy.put(saved.tag().toUpperCase(Locale.ROOT), leaderName);
		String clanLabel = "[" + saved.tag() + "] " + saved.name();
		plugin.getLogger().info("Restored the clan " + clanLabel + " with " + leaderName + " as leader ("
				+ Msg.plural(result.added().size(), "member") + " back" + (result.busy().isEmpty() ? "" : ", in other clans now: " + String.join(", ", result.busy())) + ")");

		Bukkit.broadcast(Msg.prefix("Clans", NamedTextColor.GOLD)
				.append(Component.text("♻ ", NamedTextColor.GREEN))
				.append(Component.text(clanLabel, NamedTextColor.WHITE))
				.append(Component.text(" is back! Restored by " + leaderName + ".", NamedTextColor.GREEN)));
		String details = (result.added().isEmpty() ? "" : "Back in the clan: " + String.join(", ", result.added()) + ". ")
				+ (result.busy().isEmpty() ? "" : "Not added (in another clan now): " + String.join(", ", result.busy()) + ". ")
				+ "The bank starts empty.";
		Player leader = Bukkit.getPlayer(leaderId);
		if (leader != null) leader.sendMessage(Component.text("You lead " + clanLabel + " now. " + details, NamedTextColor.GRAY));
		if (asker != leader) asker.sendMessage(Component.text("Restored " + clanLabel + " with " + leaderName + " as leader. " + details, NamedTextColor.GREEN));
		snapshot();
		return true;
	}

	// ---------------------------------------------------------------- command

	/** /clanrestore [tag] | list | <tag> <player> (staff) | forget <tag> (staff) */
	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!ClanReader.available()) {
			Msg.error(sender, "SimpleClans isn't running, so clans can't be restored right now.");
			return true;
		}
		boolean admin = sender.hasPermission(PERMISSION_ADMIN);
		if (args.length == 0) {
			if (!(sender instanceof Player p)) return listAll(sender);
			return listMine(p);
		}
		String sub = args[0].toLowerCase(Locale.ROOT);
		if (sub.equals("list") && admin) return listAll(sender);
		if (sub.equals("forget") && admin) {
			if (args.length < 2 || deleted(args[1]) == null) {
				Msg.error(sender, "No deleted clan with that tag. See /clanrestore list.");
				return true;
			}
			section().set(deleted(args[1]).getName(), null);
			save();
			sender.sendMessage(Component.text("Forgot [" + args[1] + "]; it can't be restored now.", NamedTextColor.GREEN));
			return true;
		}

		ConfigurationSection entry = deleted(args[0]);
		if (entry == null) {
			Msg.error(sender, "No deleted clan with the tag [" + args[0] + "]. Type /clanrestore to see yours.");
			return true;
		}
		if (args.length >= 2) {
			if (!admin) {
				Msg.error(sender, "Only staff can restore a clan for someone else.");
				return true;
			}
			UUID leader = findPlayer(entry, args[1]);
			if (leader == null) {
				Msg.error(sender, "No player called " + args[1] + " has played here.");
				return true;
			}
			String name = Bukkit.getOfflinePlayer(leader).getName();
			restore(entry, leader, name != null ? name : entry.getString("members." + leader + ".name", args[1]), sender);
		} else if (sender instanceof Player p) {
			if (!mayRestore(entry, p.getUniqueId()) && !admin) {
				Msg.error(sender, "disbanded".equals(entry.getString("how"))
						? label(entry) + " was disbanded by its players, so only one of its old leaders can bring it back."
						: "Only someone who was in " + label(entry) + " when it was deleted can restore it.");
				return true;
			}
			restore(entry, p.getUniqueId(), p.getName(), sender);
		} else {
			Msg.error(sender, "Say who should lead it: /clanrestore " + args[0] + " <player>");
		}
		return true;
	}

	/** A player by name: online, one of the clan's old members, or anyone who has played here. */
	private static UUID findPlayer(ConfigurationSection entry, String name) {
		Player online = Bukkit.getPlayerExact(name);
		if (online != null) return online.getUniqueId();
		ConfigurationSection ms = entry.getConfigurationSection("members");
		if (ms != null) {
			for (String id : ms.getKeys(false)) {
				if (name.equalsIgnoreCase(ms.getString(id + ".name", ""))) {
					try {
						return UUID.fromString(id);
					} catch (IllegalArgumentException ignored) {
						// not a UUID
					}
				}
			}
		}
		org.bukkit.OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
		return cached != null && cached.hasPlayedBefore() ? cached.getUniqueId() : null;
	}

	private boolean listMine(Player p) {
		List<ConfigurationSection> mine = deletedWith(p.getUniqueId());
		if (mine.isEmpty()) {
			p.sendMessage(Component.text("None of your old clans are waiting to be restored.", NamedTextColor.GRAY));
			if (p.hasPermission(PERMISSION_ADMIN)) p.sendMessage(Component.text("Staff: /clanrestore list shows every deleted clan.", NamedTextColor.GRAY));
			return true;
		}
		p.sendMessage(Component.text("Your deleted clans. Restore one to lead it; its old members come back with it:", NamedTextColor.GOLD));
		for (ConfigurationSection c : mine) p.sendMessage(line(c));
		return true;
	}

	private boolean listAll(CommandSender sender) {
		List<ConfigurationSection> all = deleted();
		if (all.isEmpty()) {
			sender.sendMessage(Component.text("No deleted clans in the archive.", NamedTextColor.GRAY));
			return true;
		}
		sender.sendMessage(Component.text(Msg.plural(all.size(), "deleted clan") + ". Restore with /clanrestore <tag> <player>:", NamedTextColor.GOLD));
		for (ConfigurationSection c : all) sender.sendMessage(line(c));
		return true;
	}

	private static Component line(ConfigurationSection c) {
		String tag = c.getString("tag", c.getName());
		List<String> leaders = new ArrayList<>(), others = new ArrayList<>();
		ConfigurationSection ms = c.getConfigurationSection("members");
		if (ms != null) {
			for (String id : ms.getKeys(false)) {
				(ms.getBoolean(id + ".leader") ? leaders : others).add(ms.getString(id + ".name", "?"));
			}
		}
		String when = new SimpleDateFormat("MMM d", Locale.US).format(new Date(c.getLong("deleted")));
		String who = (leaders.isEmpty() ? "" : "led by " + String.join(", ", leaders))
				+ (others.isEmpty() ? "" : (leaders.isEmpty() ? "" : ", ") + Msg.plural(others.size(), "member"));
		return Component.text(" • ", NamedTextColor.DARK_GRAY)
				.append(Msg.button("[" + tag + "] " + c.getString("name", ""), NamedTextColor.WHITE, "/clanrestore " + tag,
						"Click to restore it", false))
				.append(Component.text(" — " + who + " · " + ("disbanded".equals(c.getString("how")) ? "disbanded " : "deleted ") + when, NamedTextColor.GRAY));
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		boolean admin = sender.hasPermission(PERMISSION_ADMIN);
		if (args.length == 1) {
			List<String> options = new ArrayList<>();
			List<ConfigurationSection> pool = admin || !(sender instanceof Player p) ? deleted() : deletedWith(p.getUniqueId());
			for (ConfigurationSection c : pool) options.add(c.getString("tag", c.getName()));
			if (admin) options.addAll(List.of("list", "forget"));
			return Msg.matching(options, args[0]);
		}
		if (args.length == 2 && admin) {
			if (args[0].equalsIgnoreCase("forget")) {
				return Msg.matching(deleted().stream().map(c -> c.getString("tag", c.getName())).toList(), args[1]);
			}
			return null; // online players
		}
		return List.of();
	}
}
