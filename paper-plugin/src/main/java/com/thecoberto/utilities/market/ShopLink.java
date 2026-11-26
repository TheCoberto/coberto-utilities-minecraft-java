package com.thecoberto.utilities.market;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Talks to EconomyShopGUI (Premium or free) through its public API, by name, so Utilities
 * builds and runs without it. Gives every successful trade, tells when the shop reloads,
 * and reads current (dynamic) prices. Call on the main thread.
 */
final class ShopLink implements Listener {
	private static final String API = "me.gypopo.economyshopgui.api.";

	/** One successful trade. items: shop item -> amount (as section|loc, or null), with names resolved later. */
	record Trade(UUID player, String playerName, boolean buy, Map<Object, Integer> items, double price) {}

	/** A shop item's section and page location, plus the item it gives (for a name when the catalogue lacks it). */
	record ItemRef(String section, String loc, String fallbackName) {}

	private final Plugin shop;
	private final Logger log;

	private ShopLink(Plugin shop, Logger log) {
		this.shop = shop;
		this.log = log;
	}

	/** null when EconomyShopGUI isn't installed. */
	static ShopLink find(Logger log) {
		for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
			if (p.getName().startsWith("EconomyShopGUI") && p.isEnabled()) return new ShopLink(p, log);
		}
		return null;
	}

	File shopsFolder() {
		return new File(shop.getDataFolder(), "shops");
	}

	String name() {
		return shop.getName() + " " + shop.getPluginMeta().getVersion();
	}

	@SuppressWarnings("unchecked")
	private Class<? extends Event> event(String name) throws ClassNotFoundException {
		return (Class<? extends Event>) Class.forName(API + "events." + name, true, shop.getClass().getClassLoader());
	}

	/** Calls onTrade for every successful trade, and onReload after the shop's items (re)load. */
	boolean listen(Plugin owner, Consumer<Trade> onTrade, Runnable onReload) {
		try {
			Class<? extends Event> post = event("PostTransactionEvent");
			Bukkit.getPluginManager().registerEvent(post, this, EventPriority.MONITOR, (l, e) -> {
				if (!post.isInstance(e)) return;
				try {
					Trade t = trade(e);
					if (t != null) onTrade.accept(t);
				} catch (ReflectiveOperationException | RuntimeException ex) {
					log.fine("Market Board couldn't read a trade: " + ex);
				}
			}, owner, true);
		} catch (ClassNotFoundException e) {
			log.warning("Market Board: this EconomyShopGUI has no PostTransactionEvent, so trades aren't tracked.");
			return false;
		}
		try {
			Class<? extends Event> load = event("ShopItemsLoadEvent");
			Bukkit.getPluginManager().registerEvent(load, this, EventPriority.MONITOR, (l, e) -> {
				if (load.isInstance(e)) onReload.run();
			}, owner, true);
		} catch (ClassNotFoundException e) {
			log.info("Market Board: no ShopItemsLoadEvent; shop changes are only noticed on restart and /market refresh.");
		}
		return true;
	}

	void stop() {
		HandlerList.unregisterAll(this);
	}

	private static Trade trade(Event e) throws ReflectiveOperationException {
		String result = String.valueOf(call(e, "getTransactionResult"));
		if (!result.startsWith("SUCCESS")) return null;
		String type = String.valueOf(call(e, "getTransactionType"));
		boolean buy = type.contains("BUY");
		Player player = (Player) call(e, "getPlayer");
		if (player == null) return null;

		Map<Object, Integer> items = new LinkedHashMap<>();
		Object many = call(e, "getItems");
		if (many instanceof Map<?, ?> map && !map.isEmpty()) {
			for (Map.Entry<?, ?> entry : map.entrySet()) items.merge(ref(entry.getKey()), ((Number) entry.getValue()).intValue(), Integer::sum);
		} else {
			Object one = call(e, "getShopItem");
			if (one == null) return null;
			items.put(ref(one), ((Number) call(e, "getAmount")).intValue());
		}

		double price = ((Number) call(e, "getPrice")).doubleValue();
		if (price == 0 && call(e, "getPrices") instanceof Map<?, ?> prices) {
			for (Object v : prices.values()) if (v instanceof Number n) price += n.doubleValue();
		}
		return new Trade(player.getUniqueId(), player.getName(), buy, items, price);
	}

	private static ItemRef ref(Object shopItem) throws ReflectiveOperationException {
		String fallback = "Item";
		try {
			if (call(shopItem, "getItemToGive") instanceof ItemStack stack) fallback = ShopCatalog.pretty(stack.getType().name());
		} catch (ReflectiveOperationException | RuntimeException ignored) {
		}
		return new ItemRef(String.valueOf(call(shopItem, "section")), String.valueOf(call(shopItem, "itemLoc")), fallback);
	}

	/** Current buy and sell price of each shop item (after dynamic pricing), by section|loc. Empty if it can't tell. */
	Map<String, double[]> currentPrices() {
		Map<String, double[]> out = new HashMap<>();
		try {
			Class<?> hook = Class.forName(API + "EconomyShopGUIHook", true, shop.getClass().getClassLoader());
			Map<?, ?> sections = (Map<?, ?>) hook.getMethod("getSections").invoke(null);
			for (Object section : sections.values()) {
				if (!(call(section, "getShopItems") instanceof Collection<?> items)) continue;
				for (Object item : items) {
					String key = call(item, "section") + "|" + String.valueOf(call(item, "itemLoc")).replaceFirst("^pages\\.", "");
					double buy = Boolean.TRUE.equals(call(item, "isBuyAble")) ? ((Number) call(item, "getBuyPrice")).doubleValue() : -1;
					double sell = Boolean.TRUE.equals(call(item, "isSellAble")) ? ((Number) call(item, "getSellPrice")).doubleValue() : -1;
					out.put(key, new double[]{buy, sell});
				}
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			log.fine("Market Board couldn't read current prices: " + e);
		}
		return out;
	}

	private static Object call(Object target, String method) throws ReflectiveOperationException {
		Method m = target.getClass().getMethod(method);
		return m.invoke(target);
	}

	/** Names of the items in a trade, using the catalogue where it can. */
	static List<String> lines(Map<Object, Integer> items, ShopCatalog catalog) {
		List<String> out = new ArrayList<>();
		items.forEach((ref, amount) -> out.add(amount + "× " + nameOf(ref, catalog)));
		return out;
	}

	static String nameOf(Object ref, ShopCatalog catalog) {
		if (ref instanceof ItemRef r) {
			ShopCatalog.Item item = catalog.at(r.section(), r.loc());
			return item != null ? item.name() : r.fallbackName();
		}
		return String.valueOf(ref);
	}
}
