package com.thecoberto.utilities.blackmarket;

import com.thecoberto.utilities.Money;
import com.thecoberto.utilities.Msg;
import com.thecoberto.utilities.Store;
import com.thecoberto.utilities.blackmarket.Contraband.Good;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Black Market: a shady dealer who shows up at dusk at one of the saved
 * spots (in a puff of smoke, with a hint in chat), sells a few illegal goods
 * in limited stock, and vanishes at sunrise. Each night he picks a different
 * spot and new stock. He's a wandering trader with no AI that this plugin
 * spawns and removes itself, so no NPC plugin is needed.
 */
public final class BlackMarket implements Listener, TabExecutor {
	private static final TextColor SHADY = TextColor.color(0x9B59B6);
	private static final long DUSK = 13_000, DAWN = 23_000; // when mobs start and stop spawning
	private static final double SCAM_LIMIT = 5_000; // nothing pricier than this is ever swapped for dirt

	private final JavaPlugin plugin;
	private final Store store;
	private final Contraband goods;
	private final NamespacedKey npcKey;
	private final Map<Good, Integer> stock = new EnumMap<>(Good.class);
	private boolean open;
	private boolean wasNight;
	private String spot;
	private String lastSpot;
	private UUID npc;

	public BlackMarket(JavaPlugin plugin) {
		this.plugin = plugin;
		this.store = new Store(plugin, "black-market.yml");
		this.goods = new Contraband(plugin);
		this.npcKey = new NamespacedKey(plugin, "black_market_dealer");
		load();
		Bukkit.getPluginManager().registerEvents(this, plugin);
		plugin.getCommand("blackmarket").setExecutor(this);
		plugin.getCommand("blackmarket").setTabCompleter(this);

		// Clear leftover dealers, then put him back if the server restarted at night.
		for (World world : Bukkit.getWorlds()) world.getEntities().forEach(this::removeIfStray);
		if (open && dealer() == null) spawnDealer(false);
		wasNight = open; // the first check opens or closes him if that's out of date
		Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, 100L);
	}

	// ---- settings ----

	private void load() {
		var yaml = store.yaml;
		yaml.addDefault("name", "Shady Pete");
		yaml.addDefault("scam_chance", 0.05);
		for (Good g : Good.values()) yaml.addDefault("prices." + g.id(), g.price);
		yaml.options().copyDefaults(true);
		open = yaml.getBoolean("state.open");
		spot = yaml.getString("state.spot");
		lastSpot = yaml.getString("state.last_spot");
		String id = yaml.getString("state.dealer");
		npc = id == null ? null : UUID.fromString(id);
		stock.clear();
		ConfigurationSection s = yaml.getConfigurationSection("state.stock");
		if (s != null) for (String key : s.getKeys(false)) {
			Good g = Good.byId(key);
			if (g != null) stock.put(g, s.getInt(key));
		}
		store.save();
	}

	private void save() {
		var yaml = store.yaml;
		yaml.set("state.open", open);
		yaml.set("state.spot", spot);
		yaml.set("state.last_spot", lastSpot);
		yaml.set("state.dealer", npc == null ? null : npc.toString());
		yaml.set("state.stock", null);
		stock.forEach((g, n) -> yaml.set("state.stock." + g.id(), n));
		store.save();
	}

	private String name() {
		return store.yaml.getString("name", "Shady Pete");
	}

	private double price(Good g) {
		return store.yaml.getDouble("prices." + g.id(), g.price);
	}

	private List<String> spots() {
		ConfigurationSection s = store.yaml.getConfigurationSection("spots");
		return s == null ? List.of() : new ArrayList<>(s.getKeys(false));
	}

	private Location location(String name) {
		return name == null ? null : store.yaml.getLocation("spots." + name + ".location");
	}

	private String hint(String name) {
		return store.yaml.getString("spots." + name + ".hint", "somewhere out there");
	}

	// ---- day and night ----

	private static boolean isNight(World world) {
		long t = world.getTime();
		return t >= DUSK && t < DAWN;
	}

	private void tick() {
		World world = Bukkit.getWorlds().get(0);
		boolean night = isNight(world);
		if (night == wasNight) return;
		wasNight = night;
		if (night && !open) appear(null);
		else if (!night && open) vanish(true);
	}

	/** Shows up at the given spot, or a random one that isn't where he was last. */
	private boolean appear(String at) {
		List<String> all = spots();
		if (all.isEmpty()) return false;
		if (at == null) {
			List<String> choices = new ArrayList<>(all);
			if (choices.size() > 1) choices.remove(lastSpot);
			at = choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
		}
		if (location(at) == null) return false;
		spot = at;
		open = true;
		rollStock();
		spawnDealer(true);
		save();
		broadcast("Word is " + name() + " has been seen " + hint(spot) + "...");
		return true;
	}

	private void vanish(boolean announce) {
		Entity dealer = dealer();
		if (dealer != null) {
			Location at = dealer.getLocation();
			Contraband.smoke(at, 1.0);
			at.getWorld().playSound(at, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1f, 0.8f);
			at.getWorld().playSound(at, Sound.BLOCK_FIRE_EXTINGUISH, 1f, 0.6f);
			for (Player p : at.getNearbyPlayers(4)) p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0));
			dealer.remove();
		}
		// A dealer in an unloaded chunk is removed when it loads (see onEntitiesLoad).
		for (Player p : Bukkit.getOnlinePlayers()) {
			if (p.getOpenInventory().getTopInventory().getHolder() instanceof Shop) p.closeInventory();
		}
		open = false;
		lastSpot = spot;
		npc = null;
		save();
		if (announce) broadcast(name() + " slipped away with the dawn. Come back after dark.");
	}

	private void rollStock() {
		stock.clear();
		List<Good> order = new ArrayList<>(List.of(Good.values()));
		Collections.shuffle(order);
		ThreadLocalRandom r = ThreadLocalRandom.current();
		for (Good g : order) {
			if (stock.size() >= 4) break;
			if (r.nextDouble() < g.chance) stock.put(g, r.nextInt(g.min, g.max + 1));
		}
		for (Good g : order) { // always at least 3 goods (never forcing the elytra in)
			if (stock.size() >= 3) break;
			if (g != Good.BOOTLEG_ELYTRA && !stock.containsKey(g)) stock.put(g, r.nextInt(g.min, g.max + 1));
		}
	}

	// ---- the dealer ----

	private Entity dealer() {
		return npc == null ? null : Bukkit.getEntity(npc);
	}

	private boolean isDealer(Entity e) {
		return e.getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE);
	}

	private void removeIfStray(Entity e) {
		if (isDealer(e) && (!open || !e.getUniqueId().equals(npc))) e.remove();
	}

	private void spawnDealer(boolean effects) {
		Location at = location(spot);
		if (at == null || at.getWorld() == null) {
			plugin.getLogger().warning("Black Market spot " + spot + " is missing or its world isn't loaded.");
			return;
		}
		WanderingTrader trader = at.getWorld().spawn(at, WanderingTrader.class, t -> {
			t.setAI(false);
			t.setInvulnerable(true);
			t.setPersistent(true);
			t.setRemoveWhenFarAway(false);
			t.setDespawnDelay(0);
			t.setCanPickupItems(false);
			t.customName(Component.text(name(), SHADY));
			t.setCustomNameVisible(true);
			t.getPersistentDataContainer().set(npcKey, PersistentDataType.BYTE, (byte) 1);
		});
		npc = trader.getUniqueId();
		if (!trader.isValid()) {
			plugin.getLogger().warning("Another plugin stopped " + name() + " from spawning at Black Market spot " + spot + ". Pick a spot outside that protection.");
		}
		if (effects) {
			Contraband.smoke(at, 1.0);
			at.getWorld().playSound(at, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1f, 0.6f);
		}
	}

	/** Spawn protection (WorldGuard mob-spawning: deny, with block-plugin-spawning) would cancel him at spots inside it. */
	@EventHandler(priority = EventPriority.HIGHEST)
	public void onDealerSpawn(CreatureSpawnEvent event) {
		if (event.isCancelled() && isDealer(event.getEntity())) event.setCancelled(false);
	}

	@EventHandler
	public void onEntitiesLoad(EntitiesLoadEvent event) {
		for (Entity e : event.getEntities()) removeIfStray(e);
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onInteract(PlayerInteractEntityEvent event) {
		if (!isDealer(event.getRightClicked())) return;
		event.setCancelled(true); // no trading screen, name tags or leads
		if (open) openShop(event.getPlayer());
	}

	@EventHandler(ignoreCancelled = true)
	public void onDamage(EntityDamageEvent event) {
		if (isDealer(event.getEntity())) event.setCancelled(true);
	}

	// ---- the shop menu ----

	private static final class Shop implements InventoryHolder {
		final Map<Integer, Good> slots = new HashMap<>();
		Inventory inventory;

		@Override
		public Inventory getInventory() {
			return inventory;
		}
	}

	private void openShop(Player player) {
		Shop shop = new Shop();
		Inventory inv = Bukkit.createInventory(shop, 27, Component.text(name() + "'s Goods", SHADY));
		shop.inventory = inv;
		ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
		ItemMeta pm = pane.getItemMeta();
		pm.displayName(Component.text(" "));
		pm.setHideTooltip(true);
		pane.setItemMeta(pm);
		for (int i = 0; i < 27; i++) inv.setItem(i, pane);

		List<Good> list = new ArrayList<>(stock.keySet());
		int first = 13 - (list.size() - 1); // centered in the middle row, a gap between each
		for (int i = 0; i < list.size(); i++) {
			Good g = list.get(i);
			int slot = first + i * 2;
			shop.slots.put(slot, g);
			inv.setItem(slot, icon(g, stock.get(g)));
		}
		player.openInventory(inv);
		player.playSound(player.getLocation(), Sound.ENTITY_WANDERING_TRADER_AMBIENT, 1f, 0.8f);
	}

	private ItemStack icon(Good g, int left) {
		ItemStack stack = goods.make(g);
		ItemMeta meta = stack.getItemMeta();
		List<Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
		lore.add(Component.empty());
		lore.add(Component.text("Price: " + Money.format(price(g)), NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
		lore.add(left > 0
				? Component.text("Left tonight: " + left, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
				: Component.text("Sold out", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
		meta.lore(lore);
		stack.setItemMeta(meta);
		if (left <= 0) stack.setAmount(1);
		return stack;
	}

	@EventHandler
	public void onClick(InventoryClickEvent event) {
		if (!(event.getView().getTopInventory().getHolder() instanceof Shop shop)) return;
		event.setCancelled(true);
		if (!(event.getWhoClicked() instanceof Player player)) return;
		if (event.getClickedInventory() != event.getView().getTopInventory()) return;
		Good g = shop.slots.get(event.getSlot());
		if (g == null) return;
		buy(player, g);
		Bukkit.getScheduler().runTask(plugin, () -> {
			if (open && player.getOpenInventory().getTopInventory().getHolder() == shop) openShop(player);
		});
	}

	@EventHandler
	public void onDrag(InventoryDragEvent event) {
		if (event.getView().getTopInventory().getHolder() instanceof Shop) event.setCancelled(true);
	}

	private void buy(Player player, Good g) {
		if (!open) return;
		int left = stock.getOrDefault(g, 0);
		if (left <= 0) {
			Msg.error(player, "Sold out. Come back tomorrow night.");
			return;
		}
		double price = price(g);
		if (!Money.take(player, price)) {
			Msg.error(player, "You can't afford that. " + name() + " doesn't do credit.");
			player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 0.8f);
			return;
		}
		stock.put(g, left - 1);
		save();
		double scam = store.yaml.getDouble("scam_chance", 0.05);
		boolean scammed = price <= SCAM_LIMIT && ThreadLocalRandom.current().nextDouble() < scam;
		ItemStack item = scammed ? Contraband.bagOfDirt() : goods.make(g);
		player.getInventory().addItem(item).values().forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
		if (scammed) {
			player.sendMessage(Msg.prefix("[Black Market]", SHADY).append(Component.text(
					name() + " hands you a lumpy sack... it's full of dirt. No refunds.", NamedTextColor.RED)));
			player.playSound(player.getLocation(), Sound.ENTITY_WANDERING_TRADER_NO, 1f, 0.6f);
		} else {
			player.sendMessage(Msg.prefix("[Black Market]", SHADY).append(Component.text(
					"You bought " + g.label + " for " + Money.format(price) + ". You didn't get it from me.", NamedTextColor.GRAY)));
			player.playSound(player.getLocation(), Sound.ENTITY_WANDERING_TRADER_YES, 1f, 0.8f);
		}
	}

	// ---- chat ----

	private static void broadcast(String text) {
		Bukkit.broadcast(Msg.prefix("[Black Market]", SHADY).append(Component.text(text, NamedTextColor.GRAY, TextDecoration.ITALIC)));
	}

	private void tell(CommandSender to, String text) {
		to.sendMessage(Msg.prefix("[Black Market]", SHADY).append(Component.text(text, NamedTextColor.GRAY)));
	}

	// ---- /blackmarket ----

	private static final List<String> ADMIN = List.of("addspot", "removespot", "spots", "open", "close", "status", "price");

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		boolean admin = sender.hasPermission("cobertoutilities.admin");
		if (args.length == 0 || !admin) {
			tell(sender, open
					? "Word is " + name() + " has been seen " + hint(spot) + "."
					: "Nobody's seen " + name() + ". He only comes out at night.");
			return true;
		}
		switch (args[0].toLowerCase(Locale.ROOT)) {
			case "addspot" -> {
				if (args.length < 3) {
					Msg.error(sender, "/blackmarket addspot <name> <hint>, like: /blackmarket addspot windmill near the old windmill");
					return true;
				}
				Location at = Msg.where(sender);
				if (at == null) {
					Msg.error(sender, "Stand where he should appear.");
					return true;
				}
				String name = args[1].toLowerCase(Locale.ROOT);
				store.yaml.set("spots." + name + ".location", at);
				store.yaml.set("spots." + name + ".hint", Msg.join(args, 2));
				store.save();
				tell(sender, "Saved spot " + name + " (" + Msg.plural(spots().size(), "spot") + " in all). Hint: \"" + Msg.join(args, 2) + "\"");
			}
			case "removespot" -> {
				if (args.length != 2 || location(args[1].toLowerCase(Locale.ROOT)) == null) {
					Msg.error(sender, "Choose one of: " + String.join(", ", spots()));
					return true;
				}
				String name = args[1].toLowerCase(Locale.ROOT);
				if (open && name.equals(spot)) vanish(false);
				store.yaml.set("spots." + name, null);
				store.save();
				tell(sender, "Removed spot " + name + ".");
			}
			case "spots" -> {
				if (spots().isEmpty()) tell(sender, "No spots yet. Stand somewhere and use /blackmarket addspot <name> <hint>.");
				for (String s : spots()) {
					Location l = location(s);
					String where = l == null ? "?" : l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
					tell(sender, s + (s.equals(spot) && open ? " (here now)" : "") + ": " + where + ", \"" + hint(s) + "\"");
				}
			}
			case "open" -> {
				String at = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : null;
				if (at != null && location(at) == null) {
					Msg.error(sender, "No spot called " + at + ".");
					return true;
				}
				if (open) vanish(false);
				if (!appear(at)) Msg.error(sender, "Add a spot first with /blackmarket addspot.");
				else tell(sender, "He's at " + spot + " until sunrise, with new stock.");
			}
			case "close" -> {
				if (!open) tell(sender, "He isn't out.");
				else vanish(true);
			}
			case "status" -> {
				tell(sender, open ? "Open at " + spot + "." : "Closed. Last spot: " + (lastSpot == null ? "none" : lastSpot) + ".");
				stock.forEach((g, n) -> tell(sender, g.label + ": " + n + " left at " + Money.format(price(g))));
				tell(sender, "Scam chance: " + Math.round(store.yaml.getDouble("scam_chance") * 100) + "% (items up to " + Money.format(SCAM_LIMIT) + ")");
			}
			case "price" -> {
				Good g = args.length == 3 ? Good.byId(args[1]) : null;
				Integer amount = args.length == 3 ? Msg.parseInt(args[2], 1, 10_000_000) : null;
				if (g == null || amount == null) {
					Msg.error(sender, "/blackmarket price <item> <dollars>");
					return true;
				}
				store.yaml.set("prices." + g.id(), (double) amount);
				store.save();
				tell(sender, g.label + " now costs " + Money.format(amount) + ".");
			}
			default -> Msg.error(sender, "/blackmarket [" + String.join("|", ADMIN) + "]");
		}
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (!sender.hasPermission("cobertoutilities.admin")) return List.of();
		if (args.length == 1) return Msg.matching(ADMIN, args[0]);
		if (args.length == 2) {
			String sub = args[0].toLowerCase(Locale.ROOT);
			if (sub.equals("removespot") || sub.equals("open")) return Msg.matching(spots(), args[1]);
			if (sub.equals("price")) return Msg.matching(List.of(Good.values()).stream().map(Good::id).toList(), args[1]);
		}
		return List.of();
	}
}
