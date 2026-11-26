package com.thecoberto.utilities.kingdom;

import com.thecoberto.utilities.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Kingdom Board: two Discord messages (Factions and Clans) that a webhook keeps editing,
 * plus an optional feed of what changed (joins, new clans, alliances...) in a thread.
 *
 * kingdom-board.yml holds the settings; kingdom-board-data.yml holds the webhook URL (a secret)
 * and the ids of the two messages. Every few minutes it takes a snapshot, and only talks to
 * Discord when something changed.
 */
public final class KingdomBoard implements TabExecutor {
	private static final int FIELD_LIMIT = 1024, EMBED_LIMIT = 5800, CONTENT_LIMIT = 1900;
	private static final DecimalFormat MONEY = new DecimalFormat("#,##0.##");
	private static final DecimalFormat KDR = new DecimalFormat("0.00");

	private final JavaPlugin plugin;
	private final File settingsFile, dataFile;
	private YamlConfiguration settings, data;
	private BukkitTask task;
	private final AtomicBoolean busy = new AtomicBoolean();
	private Snapshot last;
	private String lastFactionsJson, lastClansJson;
	private String lastProblem;

	public KingdomBoard(JavaPlugin plugin) {
		this.plugin = plugin;
		settingsFile = new File(plugin.getDataFolder(), "kingdom-board.yml");
		dataFile = new File(plugin.getDataFolder(), "kingdom-board-data.yml");
		if (!settingsFile.exists()) plugin.saveResource("kingdom-board.yml", false);
		load();
		plugin.getCommand("kingdomboard").setExecutor(this);
		plugin.getCommand("kingdomboard").setTabCompleter(this);
		// Give LuckPerms and SimpleClans time to finish loading before the first look.
		Bukkit.getScheduler().runTaskLater(plugin, this::schedule, 20L * 30);
	}

	private void load() {
		settings = YamlConfiguration.loadConfiguration(settingsFile);
		data = YamlConfiguration.loadConfiguration(dataFile);
	}

	private void schedule() {
		if (task != null) task.cancel();
		task = null;
		if (!settings.getBoolean("enabled", true)) return;
		long every = Math.max(60, settings.getInt("refresh-seconds", 120)) * 20L;
		task = Bukkit.getScheduler().runTaskTimer(plugin, () -> update(false, null), 0L, every);
	}

	// ---------------------------------------------------------------- update

	/** Takes a snapshot and sends whatever changed. force re-sends both boards. Starts on the main thread. */
	private void update(boolean force, CommandSender reportTo) {
		String webhookUrl = data.getString("webhook-url", "");
		if (!Webhook.looksValid(webhookUrl)) {
			problem("No webhook set. Run /kingdomboard webhook <url>.", reportTo);
			return;
		}
		if (!busy.compareAndSet(false, true)) {
			if (reportTo != null) Msg.error(reportTo, "The board is already updating; try again in a moment.");
			return;
		}
		try {
			Map<String, FactionConfig> factions = factionConfigs();
			List<Snapshot.Clan> clans = List.of();
			if (ClanReader.available()) {
				try {
					clans = ClanReader.read();
				} catch (ReflectiveOperationException | RuntimeException e) {
					plugin.getLogger().warning("Kingdom Board couldn't read SimpleClans: " + e);
				}
			}
			Set<UUID> online = new HashSet<>();
			for (Player p : Bukkit.getOnlinePlayers()) online.add(p.getUniqueId());
			List<Snapshot.Clan> clanList = clans;

			CompletableFuture<Map<UUID, String>> members = Bukkit.getPluginManager().isPluginEnabled("LuckPerms")
					? FactionLookup.lookup(groupsOf(factions))
					: CompletableFuture.completedFuture(Map.of());

			Webhook webhook = new Webhook(webhookUrl);
			members.thenAcceptAsync(factionOf -> {
				Snapshot now = new Snapshot(factionList(factions, factionOf, online), factionOf, clanList);
				send(webhook, now, force, reportTo);
			}, runnable -> Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable)).whenComplete((v, error) -> {
				busy.set(false);
				if (error != null) {
					plugin.getLogger().warning("Kingdom Board update failed: " + error);
					report(reportTo, "Update failed: " + error.getMessage(), true);
				}
			});
		} catch (RuntimeException e) {
			busy.set(false);
			throw e;
		}
	}

	/** Off the main thread: edits the boards if they changed, then posts the events. */
	private void send(Webhook webhook, Snapshot now, boolean force, CommandSender reportTo) {
		String factionsJson = Json.write(message(factionsEmbed(now)));
		String clansJson = Json.write(message(clansEmbed(now)));
		boolean factionsSent = true, clansSent = true;
		if (force || !factionsJson.equals(lastFactionsJson)) {
			factionsSent = board(webhook, "factions-message-id", factionsJson);
			if (factionsSent) lastFactionsJson = factionsJson;
		}
		if (force || !clansJson.equals(lastClansJson)) {
			clansSent = board(webhook, "clans-message-id", clansJson);
			if (clansSent) lastClansJson = clansJson;
		}

		if (last != null && settings.getBoolean("events.enabled", true)) {
			String factionThread = thread("factions"), clanThread = thread("clans");
			List<String> factionEvents = factionThread.isBlank() ? List.of() : factionEvents(last, now);
			List<String> clanEvents = clanThread.isBlank() ? List.of() : clanEvents(last, now);
			if (factionThread.equals(clanThread)) {
				List<String> all = new ArrayList<>(factionEvents);
				all.addAll(clanEvents);
				if (!all.isEmpty()) postEvents(webhook, factionThread, all);
			} else {
				if (!factionEvents.isEmpty()) postEvents(webhook, factionThread, factionEvents);
				if (!clanEvents.isEmpty()) postEvents(webhook, clanThread, clanEvents);
			}
		}
		last = now;
		if (factionsSent && clansSent) lastProblem = null;
		report(reportTo, factionsSent && clansSent ? "Kingdom Board is up to date." : "Some of the board didn't send; see the console.", !(factionsSent && clansSent));
	}

	/** Edits the board message, or posts it (and remembers its id) when there isn't one yet or it was deleted. */
	private boolean board(Webhook webhook, String idKey, String json) {
		try {
			String id = data.getString(idKey, "");
			if (!id.isBlank()) {
				Webhook.Result edited = webhook.edit(id, json);
				if (edited.ok()) return true;
				if (edited.status() != 404) return failed("editing " + idKey, edited);
			}
			Webhook.Result posted = webhook.post(json, null);
			if (!posted.ok() || posted.messageId() == null) return failed("posting " + idKey, posted);
			data.set(idKey, posted.messageId());
			saveData();
			return true;
		} catch (Exception e) {
			plugin.getLogger().warning("Kingdom Board couldn't reach Discord: " + e);
			return false;
		}
	}

	private boolean failed(String what, Webhook.Result result) {
		String body = result.body() == null ? "" : result.body();
		problem("Discord said " + result.status() + " when " + what + ": " + body.substring(0, Math.min(200, body.length())), null);
		return false;
	}

	private void postEvents(Webhook webhook, String thread, List<String> events) {
		StringBuilder chunk = new StringBuilder();
		List<String> chunks = new ArrayList<>();
		for (String event : events) {
			if (chunk.length() + event.length() + 1 > CONTENT_LIMIT) {
				chunks.add(chunk.toString());
				chunk.setLength(0);
			}
			chunk.append(event).append('\n');
		}
		if (!chunk.isEmpty()) chunks.add(chunk.toString());
		for (String content : chunks) {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("username", settings.getString("webhook-name", "Kingdom Crobus"));
			body.put("content", content.strip());
			body.put("allowed_mentions", Map.of("parse", List.of()));
			try {
				Webhook.Result r = webhook.post(Json.write(body), thread);
				if (!r.ok()) failed("posting events", r);
			} catch (Exception e) {
				plugin.getLogger().warning("Kingdom Board couldn't post events: " + e);
			}
		}
	}

	// ---------------------------------------------------------------- embeds

	private Map<String, Object> message(Map<String, Object> embed) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("username", settings.getString("webhook-name", "Kingdom Crobus"));
		body.put("content", "");
		body.put("embeds", List.of(embed));
		body.put("allowed_mentions", Map.of("parse", List.of()));
		return body;
	}

	private Map<String, Object> factionsEmbed(Snapshot s) {
		List<Map<String, Object>> fields = new ArrayList<>();
		int total = 0;
		for (Snapshot.Faction f : s.factions()) {
			total += f.members().size();
			String title = f.emoji() + " " + f.name() + " — " + Msg.plural(f.members().size(), "member")
					+ (f.online() > 0 ? " · " + f.online() + " online" : "");
			fields.add(field(title, f.members().isEmpty() ? "*No members yet*" : nameList(f.members(), f.online()), false));
		}
		Snapshot.Faction leader = s.factions().stream().max(Comparator.comparingInt(f -> f.members().size())).orElse(null);
		String description = total == 0 ? "No one has picked a faction yet."
				: Msg.plural(total, "player") + " sworn to a faction."
				+ (leader != null && isSoleLeader(s, leader) ? " **" + leader.name() + "** leads in numbers." : "")
				+ "\nOnline players are in **bold**.";
		return embed(settings.getString("factions-title", "⚔️ Factions of Kingdom Crobus"), description,
				settings.getInt("factions-color", 0xF5A623), fields, null);
	}

	private static boolean isSoleLeader(Snapshot s, Snapshot.Faction leader) {
		return s.factions().stream().filter(f -> f.members().size() == leader.members().size()).count() == 1;
	}

	private Map<String, Object> clansEmbed(Snapshot s) {
		List<Snapshot.Clan> clans = new ArrayList<>(s.clans());
		clans.sort(Comparator.comparingInt((Snapshot.Clan c) -> c.members().size()).reversed()
				.thenComparing(Comparator.comparingInt(Snapshot.Clan::kills).reversed())
				.thenComparing(Snapshot.Clan::tag));
		int players = clans.stream().mapToInt(c -> c.members().size()).sum();
		String description = clans.isEmpty() ? "No clans yet. Start one in-game with `/clan create`."
				: Msg.plural(clans.size(), "clan") + " · " + Msg.plural(players, "member") + ". Biggest first.";

		int max = Math.max(1, Math.min(24, settings.getInt("max-clans", 15)));
		List<Map<String, Object>> fields = new ArrayList<>();
		int length = description.length() + 100;
		int shown = 0;
		SimpleDateFormat founded = new SimpleDateFormat("MMM d, yyyy", Locale.US);
		for (Snapshot.Clan c : clans) {
			if (shown >= max) break;
			String title = "[" + c.tag() + "] " + c.name() + " — " + Msg.plural(c.members().size(), "member");
			StringBuilder v = new StringBuilder();
			v.append("👑 ").append(c.leaders().isEmpty() ? "No leader" : String.join(", ", c.leaders())).append('\n');
			v.append("⚔️ ").append(c.kills()).append(" kills · ").append(c.deaths()).append(" deaths · KDR ").append(KDR.format(c.kdr()));
			v.append(" · 💰 $").append(MONEY.format(c.balance())).append('\n');
			if (!c.allies().isEmpty()) v.append("🤝 Allies: ").append(tagList(c.allies(), s)).append('\n');
			if (!c.rivals().isEmpty()) v.append("⚡ Rivals: ").append(tagList(c.rivals(), s)).append('\n');
			List<String> others = c.members().stream().filter(m -> !c.leaders().contains(m)).toList();
			if (!others.isEmpty()) v.append("👥 ").append(String.join(", ", others)).append('\n');
			if (c.founded() > 0) v.append("📅 Founded ").append(founded.format(new Date(c.founded())));
			Map<String, Object> field = field(title, v.toString().strip(), false);
			int size = title.length() + ((String) field.get("value")).length();
			if (length + size > EMBED_LIMIT) break;
			length += size;
			fields.add(field);
			shown++;
		}
		if (shown < clans.size()) {
			fields.add(field("…and " + Msg.plural(clans.size() - shown, "more clan"), "See them all in-game with `/clan list`.", false));
		}
		return embed(settings.getString("clans-title", "🛡️ Clans of Kingdom Crobus"), description,
				settings.getInt("clans-color", 0x5865F2), fields, null);
	}

	private static String tagList(List<String> tags, Snapshot s) {
		List<String> out = new ArrayList<>();
		for (String tag : tags) {
			Snapshot.Clan c = s.clan(tag);
			out.add(c == null ? "[" + tag + "]" : "[" + tag + "] " + c.name());
		}
		return String.join(", ", out);
	}

	/** Names with the first `online` of them in bold, cut to fit a field. */
	private static String nameList(List<String> names, int online) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < names.size(); i++) {
			String name = escape(names.get(i));
			String piece = (i == 0 ? "" : ", ") + (i < online ? "**" + name + "**" : name);
			String more = " …and " + (names.size() - i) + " more";
			if (out.length() + piece.length() + more.length() > FIELD_LIMIT) return out + more;
			out.append(piece);
		}
		return out.toString();
	}

	private static Map<String, Object> field(String name, String value, boolean inline) {
		Map<String, Object> f = new LinkedHashMap<>();
		f.put("name", cut(name, 256));
		f.put("value", cut(value.isBlank() ? "-" : value, FIELD_LIMIT));
		f.put("inline", inline);
		return f;
	}

	private static Map<String, Object> embed(String title, String description, int color, List<Map<String, Object>> fields, String footer) {
		Map<String, Object> e = new LinkedHashMap<>();
		e.put("title", cut(title, 256));
		e.put("description", cut(description, 4000));
		e.put("color", color);
		e.put("fields", fields);
		e.put("footer", Map.of("text", footer == null ? "Last change" : footer));
		// Rounded to the minute so the timestamp alone never counts as a change.
		e.put("timestamp", Instant.ofEpochSecond(System.currentTimeMillis() / 60000 * 60).toString());
		return e;
	}

	private static String cut(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max - 1) + "…";
	}

	/** Player and clan names can have underscores, which Discord would read as italics. */
	private static String escape(String text) {
		return text.replace("\\", "\\\\").replace("_", "\\_").replace("*", "\\*").replace("~", "\\~").replace("`", "\\`");
	}

	// ---------------------------------------------------------------- events

	/**
	 * The thread for "factions" or "clans" events: its own events.<kind>-thread-id, or else the shared
	 * events.thread-id. Blank when that kind is off or has nowhere to go.
	 */
	private String thread(String kind) {
		ConfigurationSection on = settings.getConfigurationSection("events");
		if (on == null || !on.getBoolean(kind, true)) return "";
		String own = on.getString(kind + "-thread-id", "");
		String id = own == null || own.isBlank() ? on.getString("thread-id", "") : own;
		return id == null ? "" : id.trim();
	}

	/** Faction joins, switches and leaves between two snapshots. */
	private List<String> factionEvents(Snapshot before, Snapshot now) {
		List<String> out = new ArrayList<>();
		{
			Set<UUID> everyone = new HashSet<>(before.factionOf().keySet());
			everyone.addAll(now.factionOf().keySet());
			for (UUID uuid : everyone) {
				String was = before.factionOf().get(uuid), is = now.factionOf().get(uuid);
				if (Objects.equals(was, is)) continue;
				String who = "**" + escape(name(uuid)) + "**";
				Snapshot.Faction to = is == null ? null : now.faction(is);
				Snapshot.Faction from = was == null ? null : before.faction(was);
				if (to != null && from != null) out.add("🔁 " + who + " left the " + from.name() + " for the " + to.emoji() + " **" + to.name() + "**");
				else if (to != null) out.add(to.emoji() + " " + who + " joined the **" + to.name() + "**");
				else if (from != null) out.add("🏳️ " + who + " left the " + from.name());
			}
		}
		return out;
	}

	/** New and disbanded clans, members, leaders, alliances and rivalries between two snapshots. */
	private List<String> clanEvents(Snapshot before, Snapshot now) {
		List<String> out = new ArrayList<>();
		{
			for (Snapshot.Clan c : now.clans()) {
				Snapshot.Clan old = before.clan(c.tag());
				String label = "**[" + c.tag() + "] " + escape(c.name()) + "**";
				if (old == null) {
					out.add("🛡️ New clan " + label + (c.leaders().isEmpty() ? "" : ", led by " + escape(String.join(", ", c.leaders()))));
					continue;
				}
				for (String m : c.members()) if (!old.members().contains(m)) out.add("➕ **" + escape(m) + "** joined " + label);
				for (String m : old.members()) if (!c.members().contains(m)) out.add("➖ **" + escape(m) + "** left " + label);
				for (String l : c.leaders()) if (!old.leaders().contains(l) && old.members().contains(l)) out.add("👑 **" + escape(l) + "** now leads " + label);
				// Alliances and rivalries are listed on both clans; report each pair once.
				for (String a : c.allies()) if (!old.allies().contains(a) && c.tag().compareTo(a) < 0) out.add("🤝 " + label + " and " + pair(a, now) + " are now allies");
				for (String a : old.allies()) if (!c.allies().contains(a) && c.tag().compareTo(a) < 0) out.add("💔 " + label + " and " + pair(a, now) + " are no longer allies");
				for (String r : c.rivals()) if (!old.rivals().contains(r) && c.tag().compareTo(r) < 0) out.add("⚡ " + label + " and " + pair(r, now) + " are now rivals");
				for (String r : old.rivals()) if (!c.rivals().contains(r) && c.tag().compareTo(r) < 0) out.add("🕊️ " + label + " and " + pair(r, now) + " ended their rivalry");
			}
			for (Snapshot.Clan old : before.clans()) {
				if (now.clan(old.tag()) == null) out.add("💥 Clan **[" + old.tag() + "] " + escape(old.name()) + "** has disbanded");
			}
		}
		return out;
	}

	private static String pair(String tag, Snapshot s) {
		Snapshot.Clan c = s.clan(tag);
		return "**[" + tag + "]" + (c == null ? "" : " " + escape(c.name())) + "**";
	}

	// ---------------------------------------------------------------- factions from settings

	private record FactionConfig(String name, String emoji, List<String> groups) {}

	private Map<String, FactionConfig> factionConfigs() {
		Map<String, FactionConfig> out = new LinkedHashMap<>();
		ConfigurationSection section = settings.getConfigurationSection("factions");
		if (section == null) return out;
		for (String id : section.getKeys(false)) {
			ConfigurationSection f = section.getConfigurationSection(id);
			if (f == null) continue;
			out.put(id.toLowerCase(Locale.ROOT), new FactionConfig(
					f.getString("name", id), f.getString("emoji", "⚔️"), f.getStringList("groups")));
		}
		return out;
	}

	private static Map<String, List<String>> groupsOf(Map<String, FactionConfig> factions) {
		Map<String, List<String>> out = new LinkedHashMap<>();
		factions.forEach((id, f) -> out.put(id, f.groups()));
		return out;
	}

	private static List<Snapshot.Faction> factionList(Map<String, FactionConfig> factions, Map<UUID, String> factionOf, Set<UUID> online) {
		Map<String, List<UUID>> byFaction = new HashMap<>();
		factionOf.forEach((uuid, id) -> byFaction.computeIfAbsent(id, k -> new ArrayList<>()).add(uuid));
		List<Snapshot.Faction> out = new ArrayList<>();
		factions.forEach((id, f) -> {
			List<UUID> uuids = byFaction.getOrDefault(id, List.of());
			List<String> on = new ArrayList<>(), off = new ArrayList<>();
			for (UUID uuid : uuids) (online.contains(uuid) ? on : off).add(name(uuid));
			on.sort(String.CASE_INSENSITIVE_ORDER);
			off.sort(String.CASE_INSENSITIVE_ORDER);
			List<String> names = new ArrayList<>(on);
			names.addAll(off);
			out.add(new Snapshot.Faction(id, f.name(), f.emoji(), names, on.size()));
		});
		return out;
	}

	private static String name(UUID uuid) {
		OfflinePlayer p = Bukkit.getOfflinePlayer(uuid);
		return p.getName() == null ? uuid.toString().substring(0, 8) : p.getName();
	}

	// ---------------------------------------------------------------- data, messages

	private synchronized void saveData() {
		try {
			data.save(dataFile);
		} catch (IOException e) {
			plugin.getLogger().warning("Kingdom Board couldn't save " + dataFile.getName() + ": " + e.getMessage());
		}
	}

	private void problem(String message, CommandSender reportTo) {
		if (reportTo != null) Msg.error(reportTo, message);
		if (!message.equals(lastProblem)) plugin.getLogger().warning("Kingdom Board: " + message);
		lastProblem = message;
	}

	private void report(CommandSender to, String message, boolean error) {
		if (to == null) return;
		Bukkit.getScheduler().runTask(plugin, () -> to.sendMessage(Component.text(message, error ? NamedTextColor.RED : NamedTextColor.GREEN)));
	}

	// ---------------------------------------------------------------- command

	/** /kingdomboard [refresh|repost|reload|webhook <url>|status] */
	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
		switch (sub) {
			case "refresh" -> {
				sender.sendMessage(Component.text("Updating the Kingdom Board...", NamedTextColor.GRAY));
				update(true, sender);
			}
			case "repost" -> {
				// New messages at the bottom of the channel; delete the old ones in Discord yourself.
				data.set("factions-message-id", null);
				data.set("clans-message-id", null);
				saveData();
				sender.sendMessage(Component.text("Posting new board messages...", NamedTextColor.GRAY));
				update(true, sender);
			}
			case "reload" -> {
				load();
				lastFactionsJson = lastClansJson = null;
				schedule();
				sender.sendMessage(Component.text("Reloaded kingdom-board.yml.", NamedTextColor.GREEN));
			}
			case "webhook" -> {
				if (args.length < 2 || !Webhook.looksValid(args[1])) {
					Msg.error(sender, "Usage: /kingdomboard webhook <Discord webhook URL> (Channel settings > Integrations > Webhooks).");
					return true;
				}
				data.set("webhook-url", args[1]);
				data.set("factions-message-id", null);
				data.set("clans-message-id", null);
				saveData();
				sender.sendMessage(Component.text("Webhook saved. Posting the board...", NamedTextColor.GREEN));
				update(true, sender);
			}
			case "status" -> {
				boolean hooked = Webhook.looksValid(data.getString("webhook-url", ""));
				sender.sendMessage(Component.text("Kingdom Board: " + (task != null ? "on" : "off")
						+ ", every " + Math.max(60, settings.getInt("refresh-seconds", 120)) + "s", NamedTextColor.GOLD));
				sender.sendMessage(Component.text("Webhook: " + (hooked ? "set" : "not set")
						+ " · SimpleClans: " + (ClanReader.available() ? "found" : "missing")
						+ " · LuckPerms: " + (Bukkit.getPluginManager().isPluginEnabled("LuckPerms") ? "found" : "missing")
						+ " · Faction events: " + (thread("factions").isBlank() ? "off" : "on")
						+ " · Clan events: " + (thread("clans").isBlank() ? "off" : thread("clans").equals(thread("factions")) ? "on (same thread)" : "on"), NamedTextColor.GRAY));
				if (lastProblem != null) Msg.error(sender, "Last problem: " + lastProblem);
			}
			default -> {
				return false;
			}
		}
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length == 1) return Msg.matching(List.of("status", "refresh", "repost", "reload", "webhook"), args[0]);
		return List.of();
	}
}
