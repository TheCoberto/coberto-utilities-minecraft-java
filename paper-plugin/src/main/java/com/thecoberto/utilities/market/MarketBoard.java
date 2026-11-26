package com.thecoberto.utilities.market;

import com.thecoberto.utilities.Msg;
import com.thecoberto.utilities.discord.Json;
import com.thecoberto.utilities.discord.Webhook;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Market Board: a Discord channel for the shop.
 * <ul>
 *   <li>A board message the webhook keeps editing: today's trades, most bought and sold items,
 *       top traders, and prices that dynamic pricing has pushed down or up.</li>
 *   <li>"New in the Shop" posts when items are added, removed or repriced (checked on start and
 *       whenever the shop reloads), with an optional note from /market note on top.</li>
 *   <li>/market announce posts an announcement.</li>
 *   <li>A feed of trades in a thread, a message a minute at most.</li>
 * </ul>
 * market-board.yml holds the settings; market-board-data.yml the webhook URL (a secret), the
 * board's message id, the note, the catalogue last announced and today's numbers.
 */
public final class MarketBoard implements TabExecutor {
	private static final int FIELD_LIMIT = 1024, CONTENT_LIMIT = 1900;
	private static final DecimalFormat MONEY = new DecimalFormat("#,##0.##");

	private final JavaPlugin plugin;
	private final File settingsFile, dataFile;
	private YamlConfiguration settings, data;
	private ShopLink shop;
	private ShopCatalog catalog = ShopCatalog.empty();
	private final MarketStats stats = new MarketStats();
	private final List<BukkitTask> tasks = new ArrayList<>();
	/** Trades waiting for the feed, merged by player + buy/sell + item. */
	private final Map<String, FeedLine> feed = new LinkedHashMap<>();
	private int feedCounter;
	private String lastBoardJson;
	private boolean statsChanged, sending, catalogCheckQueued;
	private String lastProblem;

	private static final class FeedLine {
		final String player;
		final boolean buy;
		final Map<String, Integer> items = new LinkedHashMap<>();
		double price;

		FeedLine(String player, boolean buy) {
			this.player = player;
			this.buy = buy;
		}
	}

	public MarketBoard(JavaPlugin plugin) {
		this.plugin = plugin;
		settingsFile = new File(plugin.getDataFolder(), "market-board.yml");
		dataFile = new File(plugin.getDataFolder(), "market-board-data.yml");
		if (!settingsFile.exists()) plugin.saveResource("market-board.yml", false);
		load();
		stats.load(data.getConfigurationSection("today"));
		plugin.getCommand("market").setExecutor(this);
		plugin.getCommand("market").setTabCompleter(this);
		// After the shop plugin has loaded its items
		Bukkit.getScheduler().runTaskLater(plugin, this::start, 20L * 30);
	}

	private void load() {
		settings = YamlConfiguration.loadConfiguration(settingsFile);
		data = YamlConfiguration.loadConfiguration(dataFile);
	}

	private void start() {
		tasks.forEach(BukkitTask::cancel);
		tasks.clear();
		if (shop != null) shop.stop();
		shop = null;
		if (!settings.getBoolean("enabled", true)) return;
		shop = ShopLink.find(plugin.getLogger());
		if (shop == null) {
			problem("EconomyShopGUI isn't installed, so there's no shop to show.", null);
			return;
		}
		catalog = ShopCatalog.read(shop.shopsFolder());
		shop.listen(plugin, this::onTrade, this::queueCatalogCheck);
		checkCatalog(false);

		long board = Math.max(60, settings.getInt("refresh-seconds", 120)) * 20L;
		long feedEvery = Math.max(15, settings.getInt("transactions.every-seconds", 60)) * 20L;
		tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> updateBoard(false, null), 20L, board));
		tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::flushFeed, feedEvery, feedEvery));
	}

	// ---------------------------------------------------------------- trades

	private void onTrade(ShopLink.Trade trade) {
		Map<String, Integer> items = new LinkedHashMap<>();
		trade.items().forEach((ref, amount) -> items.merge(ShopLink.nameOf(ref, catalog), amount, Integer::sum));
		if (items.isEmpty()) return;
		stats.add(trade.playerName(), trade.buy(), items, trade.price());
		statsChanged = true;

		if (transactionsThread().isBlank()) return;
		// Repeat trades of the same item (clicking Buy over and over, sell-all) become one line.
		String key = items.size() == 1
				? trade.playerName() + "|" + trade.buy() + "|" + items.keySet().iterator().next()
				: "multi" + (feedCounter++);
		FeedLine line = feed.computeIfAbsent(key, k -> new FeedLine(trade.playerName(), trade.buy()));
		items.forEach((name, amount) -> line.items.merge(name, amount, Integer::sum));
		line.price += trade.price();
	}

	private void flushFeed() {
		if (statsChanged) saveStats();
		if (feed.isEmpty()) return;
		String webhookUrl = data.getString("webhook-url", "");
		String thread = transactionsThread();
		if (!Webhook.looksValid(webhookUrl) || thread.isBlank()) {
			feed.clear();
			return;
		}
		List<String> lines = new ArrayList<>();
		for (FeedLine l : feed.values()) {
			List<String> parts = new ArrayList<>();
			l.items.forEach((name, amount) -> parts.add(amount + "× " + escape(name)));
			lines.add((l.buy ? "🛒 **" : "💸 **") + escape(l.player) + "** " + (l.buy ? "bought " : "sold ")
					+ String.join(", ", parts) + " · $" + MONEY.format(l.price));
		}
		feed.clear();
		String name = webhookName();
		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			Webhook webhook = new Webhook(webhookUrl);
			for (String content : chunks(lines)) {
				Map<String, Object> body = new LinkedHashMap<>();
				body.put("username", name);
				body.put("content", content);
				body.put("allowed_mentions", Map.of("parse", List.of()));
				send(() -> webhook.post(Json.write(body), thread), "posting trades");
			}
		});
	}

	private String transactionsThread() {
		String id = settings.getString("transactions.thread-id", "");
		return settings.getBoolean("transactions.enabled", true) && id != null ? id.trim() : "";
	}

	private void saveStats() {
		stats.roll();
		stats.save(data.createSection("today"));
		saveData();
		statsChanged = false;
	}

	// ---------------------------------------------------------------- the board

	/** Builds the board on the main thread and sends it if it changed (or force). */
	private void updateBoard(boolean force, CommandSender reportTo) {
		String webhookUrl = data.getString("webhook-url", "");
		if (!Webhook.looksValid(webhookUrl)) {
			problem("No webhook set. Run /market webhook <url>.", reportTo);
			return;
		}
		if (stats.roll()) statsChanged = true;
		String json = Json.write(message(List.of(boardEmbed())));
		if (!force && json.equals(lastBoardJson)) {
			report(reportTo, "The Market Board is up to date.", false);
			return;
		}
		if (sending) return;
		sending = true;
		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			boolean ok = board(new Webhook(webhookUrl), json);
			Bukkit.getScheduler().runTask(plugin, () -> {
				sending = false;
				if (ok) {
					lastBoardJson = json;
					lastProblem = null;
				}
				report(reportTo, ok ? "Market Board sent." : "The Market Board didn't send; see the console.", !ok);
			});
		});
	}

	/** Off the main thread. Edits the board, or posts a new one when there isn't one (or it was deleted). */
	private boolean board(Webhook webhook, String json) {
		try {
			String id = data.getString("board-message-id", "");
			if (!id.isBlank()) {
				Webhook.Result edited = webhook.edit(id, json);
				if (edited.ok()) return true;
				if (edited.status() != 404) return failed("editing the board", edited);
			}
			Webhook.Result posted = webhook.post(json, null);
			if (!posted.ok() || posted.messageId() == null) return failed("posting the board", posted);
			Bukkit.getScheduler().runTask(plugin, () -> {
				data.set("board-message-id", posted.messageId());
				saveData();
			});
			return true;
		} catch (Exception e) {
			plugin.getLogger().warning("Market Board couldn't reach Discord: " + e);
			return false;
		}
	}

	private Map<String, Object> boardEmbed() {
		int top = Math.max(1, Math.min(10, settings.getInt("top-items", 5)));
		String description = stats.trades == 0
				? "No trades yet today. Open the shop with `/shop`."
				: "**Today:** " + Msg.plural(stats.trades, "trade") + " · players spent **$" + MONEY.format(stats.spent)
						+ "** and earned **$" + MONEY.format(stats.earned) + "**";

		List<Map<String, Object>> fields = new ArrayList<>();
		fields.add(field("🔥 Most bought today", tallyList(MarketStats.top(stats.bought, top)), true));
		fields.add(field("💸 Most sold today", tallyList(MarketStats.top(stats.sold, top)), true));
		if (settings.getBoolean("show-top-traders", true)) {
			StringBuilder t = new StringBuilder();
			int rank = 1;
			for (Map.Entry<String, Double> e : stats.topTraders(top)) {
				t.append(rank == 1 ? "🥇 " : rank == 2 ? "🥈 " : rank == 3 ? "🥉 " : rank + ". ")
						.append(escape(e.getKey())).append(" · $").append(MONEY.format(e.getValue())).append('\n');
				rank++;
			}
			fields.add(field("🏆 Top traders today", t.isEmpty() ? "*Nobody yet*" : t.toString(), false));
		}

		if (settings.getBoolean("show-price-moves", true) && shop != null) {
			int moves = Math.max(1, Math.min(15, settings.getInt("price-moves", 8)));
			List<String[]> down = new ArrayList<>(), up = new ArrayList<>();
			priceMoves(down, up);
			fields.add(field("📉 Prices down", moveList(down, moves, "*Everything is at its normal price*",
					"Sold a lot lately: cheaper to buy, and sells for less"), true));
			fields.add(field("📈 Prices up", moveList(up, moves, "*Nothing is in short supply*",
					"Bought a lot lately: pricier to buy, and sells for more"), true));
		}

		Map<String, Object> e = new LinkedHashMap<>();
		e.put("title", settings.getString("board-title", "🪙 Kingdom Market"));
		e.put("description", description);
		e.put("color", settings.getInt("board-color", 0xF1C40F));
		e.put("fields", fields);
		e.put("footer", Map.of("text", "Today's numbers start over at midnight · Last change"));
		e.put("timestamp", Instant.ofEpochSecond(System.currentTimeMillis() / 60000 * 60).toString());
		return e;
	}

	/** Items whose dynamic price is off its configured price by at least the threshold. Entries: name, text, |percent|. */
	private void priceMoves(List<String[]> down, List<String[]> up) {
		double threshold = settings.getDouble("price-move-threshold-percent", 2);
		Map<String, double[]> now = shop.currentPrices();
		for (ShopCatalog.Item item : catalog.byKey.values()) {
			double[] p = now.get(item.section() + "|" + item.loc());
			if (p == null) continue;
			// Sell price first: that's what most of the market is about. Buy-only items use their buy price.
			boolean useSell = item.sell() > 0 && p[1] > 0;
			double base = useSell ? item.sell() : item.buy(), current = useSell ? p[1] : p[0];
			if (base <= 0 || current <= 0) continue;
			double change = (current - base) / base * 100;
			if (Math.abs(change) < threshold) continue;
			String text = escape(item.name()) + " · " + (useSell ? "sells" : "buy") + " $" + MONEY.format(current)
					+ " (" + (change > 0 ? "+" : "") + Math.round(change) + "%)";
			(change < 0 ? down : up).add(new String[]{item.name(), text, String.valueOf(Math.abs(change))});
		}
		Comparator<String[]> biggest = Comparator.comparingDouble((String[] a) -> Double.parseDouble(a[2])).reversed()
				.thenComparing(a -> a[0]);
		down.sort(biggest);
		up.sort(biggest);
	}

	private static String moveList(List<String[]> moves, int max, String none, String hint) {
		if (moves.isEmpty()) return none;
		StringBuilder out = new StringBuilder("*" + hint + "*\n");
		for (int i = 0; i < Math.min(max, moves.size()); i++) out.append(moves.get(i)[1]).append('\n');
		if (moves.size() > max) out.append("…and ").append(moves.size() - max).append(" more");
		return out.toString();
	}

	private static String tallyList(List<Map.Entry<String, MarketStats.Tally>> list) {
		if (list.isEmpty()) return "*Nothing yet*";
		StringBuilder out = new StringBuilder();
		for (Map.Entry<String, MarketStats.Tally> e : list) {
			out.append(escape(e.getKey())).append(" ×").append(e.getValue().amount)
					.append(" · $").append(MONEY.format(e.getValue().money)).append('\n');
		}
		return out.toString();
	}

	// ---------------------------------------------------------------- new in the shop

	/** The shop reloaded: look once things settle (an editor save can reload several times). */
	private void queueCatalogCheck() {
		if (catalogCheckQueued) return;
		catalogCheckQueued = true;
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			catalogCheckQueued = false;
			checkCatalog(false);
		}, 20L * 10);
	}

	/** Compares the shop with what was last announced and posts the difference. Main thread. */
	private void checkCatalog(boolean report) {
		if (shop == null) return;
		catalog = ShopCatalog.read(shop.shopsFolder());
		if (catalog.byKey.isEmpty()) return;
		List<String> saved = data.getStringList("catalog");
		if (saved.isEmpty()) {
			// First run: remember the shop as it is; only later changes are news.
			data.set("catalog", catalog.saved());
			saveData();
			return;
		}
		Map<String, ShopCatalog.Item> before = ShopCatalog.fromSaved(saved);
		List<ShopCatalog.Item> added = new ArrayList<>(), removed = new ArrayList<>();
		List<String> changed = new ArrayList<>();
		for (ShopCatalog.Item item : catalog.byKey.values()) {
			ShopCatalog.Item old = before.get(item.key());
			if (old == null) added.add(item);
			else {
				String change = priceChange(old, item);
				if (change != null) changed.add(change);
			}
		}
		for (ShopCatalog.Item old : before.values()) if (!catalog.byKey.containsKey(old.key())) removed.add(old);

		boolean announce = settings.getBoolean("shop-changes.enabled", true);
		boolean priceNews = settings.getBoolean("shop-changes.price-changes", true);
		if (!priceNews) changed.clear();
		if (added.isEmpty() && removed.isEmpty() && changed.isEmpty()) {
			data.set("catalog", catalog.saved());
			saveData();
			return;
		}
		String webhookUrl = data.getString("webhook-url", "");
		if (!announce || !Webhook.looksValid(webhookUrl)) {
			// Nowhere to post: remember it anyway, so it isn't announced weeks late.
			data.set("catalog", catalog.saved());
			saveData();
			return;
		}

		// One embed per message: Discord's 6,000-character limit covers every embed in a message.
		List<String> posts = changesEmbeds(added, removed, changed, data.getString("note", "")).stream()
				.map(e -> Json.write(message(List.of(e)))).toList();
		List<String> newCatalog = catalog.saved();
		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			boolean ok = true;
			for (String json : posts) {
				if (!send(() -> new Webhook(webhookUrl).post(json, null), "posting shop changes")) {
					ok = false;
					break;
				}
			}
			final boolean done = ok;
			Bukkit.getScheduler().runTask(plugin, () -> {
				if (!done) return; // try again on the next reload or restart
				data.set("catalog", newCatalog);
				data.set("note", null);
				saveData();
				plugin.getLogger().info("Market Board: announced " + added.size() + " new, " + removed.size()
						+ " removed and " + changed.size() + " repriced shop item(s).");
			});
		});
	}

	private static String priceChange(ShopCatalog.Item old, ShopCatalog.Item now) {
		List<String> parts = new ArrayList<>();
		if (Math.abs(old.buy() - now.buy()) > 0.001) parts.add("buy " + price(old.buy()) + " → " + price(now.buy()));
		if (Math.abs(old.sell() - now.sell()) > 0.001) parts.add("sell " + price(old.sell()) + " → " + price(now.sell()));
		return parts.isEmpty() ? null : "**" + escape(now.name()) + "** · " + String.join(" · ", parts);
	}

	private static String price(double p) {
		return p < 0 ? "not sold" : "$" + MONEY.format(p);
	}

	/**
	 * Every change listed in full. A long list is split over several fields, and the fields over
	 * several embeds (Discord: 1,024 characters a field, 25 fields and 6,000 characters an embed).
	 */
	private List<Map<String, Object>> changesEmbeds(List<ShopCatalog.Item> added, List<ShopCatalog.Item> removed, List<String> changed, String note) {
		List<Map<String, Object>> fields = new ArrayList<>();
		if (!added.isEmpty()) {
			List<String> lines = new ArrayList<>();
			for (ShopCatalog.Item i : added) {
				List<String> prices = new ArrayList<>();
				if (i.buy() >= 0) prices.add("buy $" + MONEY.format(i.buy()));
				if (i.sell() >= 0) prices.add("sell $" + MONEY.format(i.sell()));
				lines.add("**" + escape(i.name()) + "**" + (prices.isEmpty() ? "" : " · " + String.join(" · ", prices)));
			}
			addFields(fields, "🆕 New (" + added.size() + ")", lines);
		}
		if (!changed.isEmpty()) addFields(fields, "💲 New prices (" + changed.size() + ")", changed);
		if (!removed.isEmpty()) addFields(fields, "🗑️ Removed (" + removed.size() + ")", removed.stream().map(i -> escape(i.name())).toList());

		String title = added.isEmpty() ? "🛒 Shop Update" : "🆕 New in the Shop";
		String description = note != null && !note.isBlank() ? note.replace("\\n", "\n") : null;
		List<Map<String, Object>> embeds = new ArrayList<>();
		List<Map<String, Object>> page = new ArrayList<>();
		int size = title.length() + (description == null ? 0 : description.length()) + 100;
		for (Map<String, Object> f : fields) {
			int fieldSize = ((String) f.get("name")).length() + ((String) f.get("value")).length();
			if (!page.isEmpty() && (page.size() == 25 || size + fieldSize > 5800)) {
				embeds.add(changesEmbed(embeds.isEmpty() ? title : null, embeds.isEmpty() ? description : null, page));
				page = new ArrayList<>();
				size = 100;
			}
			page.add(f);
			size += fieldSize;
		}
		embeds.add(changesEmbed(embeds.isEmpty() ? title : null, embeds.isEmpty() ? description : null, page));
		Map<String, Object> last = embeds.get(embeds.size() - 1);
		last.put("footer", Map.of("text", "Open the shop with /shop"));
		last.put("timestamp", Instant.now().toString());
		return embeds;
	}

	private static Map<String, Object> changesEmbed(String title, String description, List<Map<String, Object>> fields) {
		Map<String, Object> e = new LinkedHashMap<>();
		if (title != null) e.put("title", title);
		if (description != null) e.put("description", description);
		e.put("color", 0x2ECC71);
		e.put("fields", fields);
		return e;
	}

	/** The lines as one field, or as several (the later ones headed "… continued") when they don't fit in one. */
	private static void addFields(List<Map<String, Object>> fields, String name, List<String> lines) {
		StringBuilder out = new StringBuilder();
		String heading = name;
		for (String line : lines) {
			if (!out.isEmpty() && out.length() + line.length() + 1 > FIELD_LIMIT) {
				fields.add(field(heading, out.toString(), false));
				out.setLength(0);
				heading = name.replaceAll(" \\(\\d+\\)$", "") + " continued";
			}
			out.append(line).append('\n');
		}
		fields.add(field(heading, out.toString(), false));
	}

	// ---------------------------------------------------------------- announcements

	private void announce(CommandSender sender, String text) {
		String webhookUrl = data.getString("webhook-url", "");
		if (!Webhook.looksValid(webhookUrl)) {
			Msg.error(sender, "No webhook set. Run /market webhook <url>.");
			return;
		}
		Map<String, Object> e = new LinkedHashMap<>();
		e.put("title", settings.getString("announce-title", "📢 Shop Announcement"));
		e.put("description", text.replace("\\n", "\n"));
		e.put("color", settings.getInt("board-color", 0xF1C40F));
		e.put("timestamp", Instant.now().toString());
		String json = Json.write(message(List.of(e)));
		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			boolean ok = send(() -> new Webhook(webhookUrl).post(json, null), "posting an announcement");
			report(sender, ok ? "Announcement posted." : "The announcement didn't post; see the console.", !ok);
		});
	}

	// ---------------------------------------------------------------- Discord helpers

	private interface Call {
		Webhook.Result run() throws Exception;
	}

	/** Off the main thread. */
	private boolean send(Call call, String what) {
		try {
			Webhook.Result r = call.run();
			return r.ok() || failed(what, r);
		} catch (Exception e) {
			plugin.getLogger().warning("Market Board couldn't reach Discord (" + what + "): " + e);
			return false;
		}
	}

	private boolean failed(String what, Webhook.Result result) {
		String body = result.body() == null ? "" : result.body();
		String message = "Discord said " + result.status() + " when " + what + ": " + body.substring(0, Math.min(200, body.length()));
		Bukkit.getScheduler().runTask(plugin, () -> problem(message, null));
		return false;
	}

	private String webhookName() {
		return settings.getString("webhook-name", "Kingdom Market");
	}

	private Map<String, Object> message(List<Map<String, Object>> embeds) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("username", webhookName());
		body.put("content", "");
		body.put("embeds", embeds);
		body.put("allowed_mentions", Map.of("parse", List.of()));
		return body;
	}

	private static Map<String, Object> field(String name, String value, boolean inline) {
		Map<String, Object> f = new LinkedHashMap<>();
		f.put("name", name.length() > 256 ? name.substring(0, 255) + "…" : name);
		String v = value.isBlank() ? "-" : value.strip();
		f.put("value", v.length() > FIELD_LIMIT ? v.substring(0, FIELD_LIMIT - 1) + "…" : v);
		f.put("inline", inline);
		return f;
	}

	private static List<String> chunks(List<String> lines) {
		List<String> out = new ArrayList<>();
		StringBuilder chunk = new StringBuilder();
		for (String line : lines) {
			if (chunk.length() + line.length() + 1 > CONTENT_LIMIT && !chunk.isEmpty()) {
				out.add(chunk.toString().strip());
				chunk.setLength(0);
			}
			chunk.append(line).append('\n');
		}
		if (!chunk.isEmpty()) out.add(chunk.toString().strip());
		return out;
	}

	/** Names can have underscores, which Discord would read as italics. */
	private static String escape(String text) {
		return text.replace("\\", "\\\\").replace("_", "\\_").replace("*", "\\*").replace("~", "\\~").replace("`", "\\`");
	}

	private void saveData() {
		try {
			data.save(dataFile);
		} catch (IOException e) {
			plugin.getLogger().warning("Market Board couldn't save " + dataFile.getName() + ": " + e.getMessage());
		}
	}

	/** Main thread. */
	private void problem(String message, CommandSender reportTo) {
		if (reportTo != null) Msg.error(reportTo, message);
		if (!message.equals(lastProblem)) plugin.getLogger().warning("Market Board: " + message);
		lastProblem = message;
	}

	private void report(CommandSender to, String message, boolean error) {
		if (to == null) return;
		Bukkit.getScheduler().runTask(plugin, () -> to.sendMessage(Component.text(message, error ? NamedTextColor.RED : NamedTextColor.GREEN)));
	}

	// ---------------------------------------------------------------- /market

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
		switch (sub) {
			case "refresh" -> {
				checkCatalog(true);
				updateBoard(true, sender);
			}
			case "repost" -> {
				data.set("board-message-id", null);
				saveData();
				sender.sendMessage(Component.text("Posting a new Market Board (delete the old one in Discord)...", NamedTextColor.GRAY));
				updateBoard(true, sender);
			}
			case "reload" -> {
				load();
				lastBoardJson = null;
				start();
				sender.sendMessage(Component.text("Reloaded market-board.yml.", NamedTextColor.GREEN));
			}
			case "webhook" -> {
				if (args.length < 2 || !Webhook.looksValid(args[1])) {
					Msg.error(sender, "Usage: /market webhook <Discord webhook URL> (Channel settings > Integrations > Webhooks).");
					return true;
				}
				data.set("webhook-url", args[1]);
				data.set("board-message-id", null);
				saveData();
				sender.sendMessage(Component.text("Webhook saved. Posting the Market Board...", NamedTextColor.GREEN));
				updateBoard(true, sender);
			}
			case "note" -> {
				String text = Msg.join(args, 1);
				if (text.isEmpty()) {
					String note = data.getString("note", "");
					sender.sendMessage(Component.text(note.isBlank()
							? "No note. /market note <text> puts one on top of the next New in the Shop post (\\n for a new line)."
							: "Next shop post starts with: " + note, NamedTextColor.GRAY));
				} else if (text.equalsIgnoreCase("clear")) {
					data.set("note", null);
					saveData();
					sender.sendMessage(Component.text("Note cleared.", NamedTextColor.GREEN));
				} else {
					data.set("note", text);
					saveData();
					sender.sendMessage(Component.text("Saved. It goes on top of the next New in the Shop post (after the shop reloads).", NamedTextColor.GREEN));
				}
			}
			case "announce" -> {
				String text = Msg.join(args, 1);
				if (text.isEmpty()) {
					Msg.error(sender, "Usage: /market announce <text> (\\n for a new line, **bold** works).");
					return true;
				}
				announce(sender, text);
			}
			case "status" -> {
				sender.sendMessage(Component.text("Market Board: " + (tasks.isEmpty() ? "off" : "on")
						+ " · shop: " + (shop == null ? "not found" : shop.name() + ", " + catalog.byKey.size() + " items"), NamedTextColor.GOLD));
				sender.sendMessage(Component.text("Webhook: " + (Webhook.looksValid(data.getString("webhook-url", "")) ? "set" : "not set")
						+ " · Trades thread: " + (transactionsThread().isBlank() ? "off" : "on")
						+ " · Today: " + stats.trades + " trades · Note: " + (data.getString("note", "").isBlank() ? "none" : "set"), NamedTextColor.GRAY));
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
		if (args.length == 1) return Msg.matching(List.of("status", "refresh", "note", "announce", "repost", "reload", "webhook"), args[0]);
		if (args.length == 2 && args[0].equalsIgnoreCase("note")) return Msg.matching(List.of("clear"), args[1]);
		return List.of();
	}
}
