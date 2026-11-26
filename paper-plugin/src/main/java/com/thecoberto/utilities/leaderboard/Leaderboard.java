package com.thecoberto.utilities.leaderboard;

import com.thecoberto.utilities.Money;
import com.thecoberto.utilities.Msg;
import com.thecoberto.utilities.discord.Json;
import com.thecoberto.utilities.discord.Webhook;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToDoubleFunction;

/**
 * The Leaderboard: one Discord message a webhook keeps editing, with the top players in each
 * category (money, kills, claim blocks, playtime...), plus an optional thread that announces
 * each new #1.
 *
 * leaderboard.yml holds the settings; leaderboard-data.yml the webhook URL (a secret) and the
 * message id. Vanilla stats of offline players are read from their stats file once and kept,
 * and read again when they leave, so a refresh only reads files for players it hasn't seen.
 */
public final class Leaderboard implements TabExecutor, Listener {
	private static final DecimalFormat MONEY = new DecimalFormat("#,##0");
	private static final DecimalFormat ONE = new DecimalFormat("#,##0.#");
	private static final DecimalFormat KDR = new DecimalFormat("0.00");
	private static final String[] MEDALS = {"🥇", "🥈", "🥉", "4.", "5.", "6.", "7.", "8.", "9.", "10."};

	/** Vanilla stats of one player. Distance in centimetres, playtime in ticks. */
	private record Vanilla(int kills, int deaths, int mobKills, long playTicks, long distanceCm, int fish) {}

	/** Everything a category can rank by. claimBlocks/claims are -1 without GriefPrevention. */
	private record Row(UUID uuid, String name, double balance, Vanilla v, int claimBlocks, int claims) {
		double kdr() { return v.deaths() == 0 ? v.kills() : (double) v.kills() / v.deaths(); }
		double hours() { return v.playTicks() / 20.0 / 3600.0; }
	}

	/** A category: its key in leaderboard.yml, title, the value to rank by and how to show it. */
	private record Category(String key, String title, ToDoubleFunction<Row> value, java.util.function.Function<Row, String> shown) {}

	private final JavaPlugin plugin;
	private final File settingsFile, dataFile;
	private YamlConfiguration settings, data;
	private BukkitTask task;
	private final AtomicBoolean busy = new AtomicBoolean();
	private final Map<UUID, Vanilla> vanilla = new HashMap<>();
	private Map<String, UUID> lastLeaders;
	private String lastJson;
	private String lastProblem;

	public Leaderboard(JavaPlugin plugin) {
		this.plugin = plugin;
		settingsFile = new File(plugin.getDataFolder(), "leaderboard.yml");
		dataFile = new File(plugin.getDataFolder(), "leaderboard-data.yml");
		if (!settingsFile.exists()) plugin.saveResource("leaderboard.yml", false);
		load();
		plugin.getCommand("leaderboard").setExecutor(this);
		plugin.getCommand("leaderboard").setTabCompleter(this);
		Bukkit.getPluginManager().registerEvents(this, plugin);
		// Let the economy and GriefPrevention finish loading first.
		Bukkit.getScheduler().runTaskLater(plugin, this::schedule, 20L * 45);
	}

	private void load() {
		settings = YamlConfiguration.loadConfiguration(settingsFile);
		data = YamlConfiguration.loadConfiguration(dataFile);
	}

	private void schedule() {
		if (task != null) task.cancel();
		task = null;
		if (!settings.getBoolean("enabled", true)) return;
		long every = Math.max(60, settings.getInt("refresh-seconds", 300)) * 20L;
		task = Bukkit.getScheduler().runTaskTimer(plugin, () -> update(false, null), 0L, every);
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onQuit(PlayerQuitEvent event) {
		// Their stats are still in memory here; keep them for while they're away.
		vanilla.put(event.getPlayer().getUniqueId(), read(event.getPlayer()));
	}

	// ---------------------------------------------------------------- gathering

	private static Vanilla read(OfflinePlayer p) {
		return new Vanilla(stat(p, Statistic.PLAYER_KILLS), stat(p, Statistic.DEATHS), stat(p, Statistic.MOB_KILLS),
				stat(p, Statistic.PLAY_ONE_MINUTE),
				(long) stat(p, Statistic.WALK_ONE_CM) + stat(p, Statistic.SPRINT_ONE_CM) + stat(p, Statistic.CROUCH_ONE_CM)
						+ stat(p, Statistic.SWIM_ONE_CM) + stat(p, Statistic.WALK_UNDER_WATER_ONE_CM) + stat(p, Statistic.WALK_ON_WATER_ONE_CM)
						+ stat(p, Statistic.AVIATE_ONE_CM) + stat(p, Statistic.BOAT_ONE_CM) + stat(p, Statistic.HORSE_ONE_CM)
						+ stat(p, Statistic.MINECART_ONE_CM) + stat(p, Statistic.CLIMB_ONE_CM),
				stat(p, Statistic.FISH_CAUGHT));
	}

	private static int stat(OfflinePlayer p, Statistic s) {
		try {
			return p.getStatistic(s);
		} catch (RuntimeException e) {
			return 0;
		}
	}

	/** On the main thread: one row per player who has played long enough and isn't hidden. */
	private List<Row> rows() {
		Set<String> hidden = new HashSet<>();
		for (String n : settings.getStringList("hidden-players")) hidden.add(n.toLowerCase(Locale.ROOT));
		double minHours = settings.getDouble("min-playtime-minutes", 30) / 60.0;
		Claims claims = Claims.find();
		Set<UUID> online = new HashSet<>();
		for (Player p : Bukkit.getOnlinePlayers()) {
			online.add(p.getUniqueId());
			vanilla.put(p.getUniqueId(), read(p));
		}
		List<Row> out = new ArrayList<>();
		for (OfflinePlayer p : Bukkit.getOfflinePlayers()) {
			String name = p.getName();
			if (name == null || hidden.contains(name.toLowerCase(Locale.ROOT))) continue;
			Vanilla v = vanilla.computeIfAbsent(p.getUniqueId(), id -> read(p));
			if (v.playTicks() / 20.0 / 3600.0 < minHours) continue;
			int[] c = claims == null ? new int[]{-1, -1} : claims.of(p.getUniqueId());
			double balance = 0;
			try {
				balance = Money.get(p);
			} catch (RuntimeException ignored) {
			}
			out.add(new Row(p.getUniqueId(), name, balance, v, c[0], c[1]));
		}
		return out;
	}

	private List<Category> categories(boolean hasClaims) {
		int minKills = Math.max(1, settings.getInt("kdr-min-kills", 5));
		List<Category> all = new ArrayList<>();
		if (Money.available()) all.add(new Category("richest", "💰 Richest", Row::balance, r -> "$" + MONEY.format(r.balance())));
		all.add(new Category("kills", "⚔️ Player Kills", r -> r.v().kills(), r -> MONEY.format(r.v().kills())));
		all.add(new Category("kdr", "🎯 Best KDR", r -> r.v().kills() >= minKills ? r.kdr() : -1,
				r -> KDR.format(r.kdr()) + " (" + r.v().kills() + "/" + r.v().deaths() + ")"));
		all.add(new Category("deaths", "💀 Most Deaths", r -> r.v().deaths(), r -> MONEY.format(r.v().deaths())));
		if (hasClaims) all.add(new Category("claim-blocks", "🏡 Claim Blocks", Row::claimBlocks,
				r -> MONEY.format(r.claimBlocks()) + " · " + Msg.plural(r.claims(), "claim")));
		all.add(new Category("playtime", "⏱️ Playtime", Row::hours, r -> ONE.format(r.hours()) + " h"));
		all.add(new Category("mob-kills", "🧟 Mob Kills", r -> r.v().mobKills(), r -> MONEY.format(r.v().mobKills())));
		all.add(new Category("distance", "🏃 Distance Traveled", r -> r.v().distanceCm(), r -> ONE.format(r.v().distanceCm() / 100000.0) + " km"));
		all.add(new Category("fish", "🎣 Fish Caught", r -> r.v().fish(), r -> MONEY.format(r.v().fish())));
		all.removeIf(c -> !settings.getBoolean("categories." + c.key(), true));
		return all;
	}

	/** Top players of a category; nobody with 0 (or a KDR below the minimum kills). */
	private static List<Row> top(List<Row> rows, Category c, int n) {
		return rows.stream().filter(r -> c.value().applyAsDouble(r) > 0)
				.sorted(Comparator.comparingDouble(c.value()).reversed().thenComparing(Row::name, String.CASE_INSENSITIVE_ORDER))
				.limit(n).toList();
	}

	// ---------------------------------------------------------------- update

	/** Gathers on the main thread, then sends off it if anything changed (or force). */
	private void update(boolean force, CommandSender reportTo) {
		String webhookUrl = data.getString("webhook-url", "");
		if (!Webhook.looksValid(webhookUrl)) {
			problem("No webhook set. Run /leaderboard webhook <url>.", reportTo);
			return;
		}
		if (!busy.compareAndSet(false, true)) {
			if (reportTo != null) Msg.error(reportTo, "The Leaderboard is already updating; try again in a moment.");
			return;
		}
		List<Row> rows;
		List<Category> cats;
		try {
			rows = rows();
			cats = categories(rows.stream().anyMatch(r -> r.claimBlocks() >= 0));
		} catch (RuntimeException e) {
			busy.set(false);
			plugin.getLogger().warning("Leaderboard couldn't gather stats: " + e);
			report(reportTo, "Couldn't gather stats: " + e.getMessage(), true);
			return;
		}
		int n = Math.max(1, Math.min(10, settings.getInt("top", 5)));
		Map<String, List<Row>> tops = new LinkedHashMap<>();
		for (Category c : cats) tops.put(c.key(), top(rows, c, n));

		Map<String, Object> embed = embed(rows, cats, tops);
		String compare = Json.write(embed);
		// Rounded to the minute; added after comparing so the time alone never counts as a change.
		embed.put("timestamp", Instant.ofEpochSecond(System.currentTimeMillis() / 60000 * 60).toString());
		String json = Json.write(message(List.of(embed), null));
		List<String> news = newLeaders(cats, tops);

		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			try {
				Webhook webhook = new Webhook(webhookUrl);
				boolean ok = true;
				if (force || !compare.equals(lastJson)) {
					ok = board(webhook, json);
					if (ok) lastJson = compare;
				}
				String thread = thread();
				if (!news.isEmpty() && !thread.isBlank()) {
					try {
						Webhook.Result r = webhook.post(Json.write(message(List.of(), String.join("\n", news))), thread);
						if (!r.ok()) failed("posting new leaders", r);
					} catch (Exception e) {
						plugin.getLogger().warning("Leaderboard couldn't post new leaders: " + e);
					}
				}
				if (ok) lastProblem = null;
				report(reportTo, ok ? "Leaderboard is up to date." : "The Leaderboard didn't send; see the console.", !ok);
			} finally {
				busy.set(false);
			}
		});
	}

	/** "👑 X took #1 in Richest from Y" for each category whose leader changed since last time. */
	private List<String> newLeaders(List<Category> cats, Map<String, List<Row>> tops) {
		Map<String, UUID> now = new HashMap<>();
		Map<UUID, String> names = new HashMap<>();
		tops.forEach((key, list) -> {
			if (!list.isEmpty()) now.put(key, list.get(0).uuid());
			for (Row r : list) names.put(r.uuid(), r.name());
		});
		List<String> out = new ArrayList<>();
		if (lastLeaders != null) {
			for (Category c : cats) {
				UUID was = lastLeaders.get(c.key()), is = now.get(c.key());
				if (is == null || Objects.equals(was, is)) continue;
				Row leader = tops.get(c.key()).get(0);
				String from = was == null ? "" : " from **" + escape(name(was)) + "**";
				out.add("👑 **" + escape(leader.name()) + "** took #1 in " + c.title() + from + " · " + c.shown().apply(leader));
			}
		}
		lastLeaders = now;
		return out;
	}

	/** Off the main thread. Edits the message, or posts it when there isn't one yet (or it was deleted). */
	private boolean board(Webhook webhook, String json) {
		try {
			String id = data.getString("message-id", "");
			if (!id.isBlank()) {
				Webhook.Result edited = webhook.edit(id, json);
				if (edited.ok()) return true;
				if (edited.status() != 404) return failed("editing the Leaderboard", edited);
			}
			Webhook.Result posted = webhook.post(json, null);
			if (!posted.ok() || posted.messageId() == null) return failed("posting the Leaderboard", posted);
			data.set("message-id", posted.messageId());
			saveData();
			return true;
		} catch (Exception e) {
			plugin.getLogger().warning("Leaderboard couldn't reach Discord: " + e);
			return false;
		}
	}

	private boolean failed(String what, Webhook.Result result) {
		String body = result.body() == null ? "" : result.body();
		String message = "Discord said " + result.status() + " when " + what + ": " + body.substring(0, Math.min(200, body.length()));
		Bukkit.getScheduler().runTask(plugin, () -> problem(message, null));
		return false;
	}

	// ---------------------------------------------------------------- the message

	private Map<String, Object> message(List<Map<String, Object>> embeds, String content) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("username", settings.getString("webhook-name", "Kingdom Leaderboard"));
		body.put("content", content == null ? "" : content);
		body.put("embeds", embeds);
		body.put("allowed_mentions", Map.of("parse", List.of()));
		return body;
	}

	private Map<String, Object> embed(List<Row> rows, List<Category> cats, Map<String, List<Row>> tops) {
		double money = rows.stream().mapToDouble(Row::balance).sum();
		long deaths = rows.stream().mapToLong(r -> r.v().deaths()).sum();
		long kills = rows.stream().mapToLong(r -> r.v().kills()).sum();
		double hours = rows.stream().mapToDouble(Row::hours).sum();
		StringBuilder d = new StringBuilder();
		d.append(Msg.plural(rows.size(), "player")).append(" ranked · ⏱️ ").append(MONEY.format(hours)).append(" hours played");
		if (Money.available()) d.append("\n💰 $").append(MONEY.format(money)).append(" in the economy");
		d.append(" · ⚔️ ").append(MONEY.format(kills)).append(" PvP kills · 💀 ").append(MONEY.format(deaths)).append(" deaths");

		List<Map<String, Object>> fields = new ArrayList<>();
		for (Category c : cats) {
			List<Row> list = tops.get(c.key());
			StringBuilder v = new StringBuilder();
			for (int i = 0; i < list.size(); i++) {
				Row r = list.get(i);
				v.append(MEDALS[i]).append(" **").append(escape(r.name())).append("** · ").append(c.shown().apply(r)).append('\n');
			}
			Map<String, Object> f = new LinkedHashMap<>();
			f.put("name", c.title());
			f.put("value", list.isEmpty() ? "*Nobody yet*" : cut(v.toString().strip(), 1024));
			f.put("inline", true);
			fields.add(f);
		}
		Map<String, Object> e = new LinkedHashMap<>();
		e.put("title", cut(settings.getString("title", "🏆 Kingdom Leaderboard"), 256));
		e.put("description", cut(d.toString(), 4000));
		e.put("color", settings.getInt("color", 0xE67E22));
		e.put("fields", fields);
		e.put("footer", Map.of("text", "Updates every few minutes · Last change"));
		return e;
	}

	private static String cut(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max - 1) + "…";
	}

	/** Player names can have underscores, which Discord would read as italics. */
	private static String escape(String text) {
		return text.replace("\\", "\\\\").replace("_", "\\_").replace("*", "\\*").replace("~", "\\~").replace("`", "\\`");
	}

	private static String name(UUID uuid) {
		String n = Bukkit.getOfflinePlayer(uuid).getName();
		return n == null ? uuid.toString().substring(0, 8) : n;
	}

	private String thread() {
		String id = settings.getString("new-leaders.thread-id", "");
		return settings.getBoolean("new-leaders.enabled", true) && id != null ? id.trim() : "";
	}

	// ---------------------------------------------------------------- data, messages

	private synchronized void saveData() {
		try {
			data.save(dataFile);
		} catch (IOException e) {
			plugin.getLogger().warning("Leaderboard couldn't save " + dataFile.getName() + ": " + e.getMessage());
		}
	}

	private void problem(String message, CommandSender reportTo) {
		if (reportTo != null) Msg.error(reportTo, message);
		if (!message.equals(lastProblem)) plugin.getLogger().warning("Leaderboard: " + message);
		lastProblem = message;
	}

	private void report(CommandSender to, String message, boolean error) {
		if (to == null) return;
		Bukkit.getScheduler().runTask(plugin, () -> to.sendMessage(Component.text(message, error ? NamedTextColor.RED : NamedTextColor.GREEN)));
	}

	// ---------------------------------------------------------------- command

	/** /leaderboard [status|refresh|repost|reload|webhook <url>] */
	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
		switch (sub) {
			case "refresh" -> {
				sender.sendMessage(Component.text("Updating the Leaderboard...", NamedTextColor.GRAY));
				update(true, sender);
			}
			case "repost" -> {
				// A new message at the bottom of the channel; delete the old one in Discord yourself.
				data.set("message-id", null);
				saveData();
				sender.sendMessage(Component.text("Posting a new Leaderboard message...", NamedTextColor.GRAY));
				update(true, sender);
			}
			case "reload" -> {
				load();
				lastJson = null;
				vanilla.clear();
				schedule();
				sender.sendMessage(Component.text("Reloaded leaderboard.yml.", NamedTextColor.GREEN));
			}
			case "webhook" -> {
				if (args.length < 2 || !Webhook.looksValid(args[1])) {
					Msg.error(sender, "Usage: /leaderboard webhook <Discord webhook URL> (Channel settings > Integrations > Webhooks).");
					return true;
				}
				data.set("webhook-url", args[1]);
				data.set("message-id", null);
				saveData();
				if (task == null) schedule();
				sender.sendMessage(Component.text("Webhook saved. Posting the Leaderboard...", NamedTextColor.GREEN));
				update(true, sender);
			}
			case "status" -> {
				boolean hooked = Webhook.looksValid(data.getString("webhook-url", ""));
				sender.sendMessage(Component.text("Leaderboard: " + (task != null ? "on" : "off")
						+ ", every " + Math.max(60, settings.getInt("refresh-seconds", 300)) + "s", NamedTextColor.GOLD));
				sender.sendMessage(Component.text("Webhook: " + (hooked ? "set" : "not set")
						+ " · Economy: " + (Money.available() ? "found" : "missing")
						+ " · GriefPrevention: " + (Claims.find() != null ? "found" : "missing")
						+ " · New #1 posts: " + (thread().isBlank() ? "off" : "on"), NamedTextColor.GRAY));
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

	// ---------------------------------------------------------------- GriefPrevention

	/** GriefPrevention's claim blocks, by name so Utilities builds and runs without it. Main thread. */
	private static final class Claims {
		private final Object dataStore;

		private Claims(Object dataStore) {
			this.dataStore = dataStore;
		}

		static Claims find() {
			var gp = Bukkit.getPluginManager().getPlugin("GriefPrevention");
			if (gp == null || !gp.isEnabled()) return null;
			try {
				Object store = gp.getClass().getField("dataStore").get(gp);
				return store == null ? null : new Claims(store);
			} catch (ReflectiveOperationException | RuntimeException e) {
				return null;
			}
		}

		/** {total claim blocks (earned + bonus), number of claims}; zeros if it can't tell. */
		int[] of(UUID uuid) {
			try {
				Object pd = dataStore.getClass().getMethod("getPlayerData", UUID.class).invoke(dataStore, uuid);
				int accrued = ((Number) pd.getClass().getMethod("getAccruedClaimBlocks").invoke(pd)).intValue();
				int bonus = ((Number) pd.getClass().getMethod("getBonusClaimBlocks").invoke(pd)).intValue();
				Object claims = pd.getClass().getMethod("getClaims").invoke(pd);
				return new int[]{accrued + bonus, claims instanceof Collection<?> c ? c.size() : 0};
			} catch (ReflectiveOperationException | RuntimeException e) {
				return new int[]{0, 0};
			}
		}
	}
}
