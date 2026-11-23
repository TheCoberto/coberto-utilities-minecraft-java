package com.thecoberto.utilities;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Fabric mod's new blocks (crates, the Music Selector, the Prank Button)
 * as vanilla blocks placed from special items, since players have no mods.
 * Each placed one is remembered by its position in blocks.yml. They can't be
 * blown up or moved by pistons.
 */
public final class SpecialBlocks implements Listener {
	/** A kind of special block. */
	public interface Kind {
		String id();

		String name();

		NamedTextColor color();

		String lore();

		/** The vanilla block it places, unless config.yml says otherwise. */
		Material defaultMaterial();

		/** Someone used it. Sneak tells whether they were sneaking with an empty hand. */
		void use(Player player, Block block, boolean sneak);

		default void placed(Player player, Block block) {
		}

		/** It's gone (broken, or found missing). */
		default void removed(Block block) {
		}

		/** Breaking it gives the item back (crates give nothing). */
		default boolean dropsItem() {
			return true;
		}
	}

	private final JavaPlugin plugin;
	private final NamespacedKey itemKey;
	private final Map<String, Kind> kinds = new LinkedHashMap<>();
	/** Position key -> kind id. */
	private final Map<String, String> placed = new LinkedHashMap<>();
	private final Store store;

	SpecialBlocks(JavaPlugin plugin) {
		this.plugin = plugin;
		this.itemKey = new NamespacedKey(plugin, "block");
		this.store = new Store(plugin, "blocks.yml");
		var section = store.yaml.getConfigurationSection("placed");
		if (section != null) for (String key : section.getKeys(false)) placed.put(key.replace(';', ','), section.getString(key));
		Bukkit.getPluginManager().registerEvents(this, plugin);
	}

	public void register(Kind kind) {
		kinds.put(kind.id(), kind);
	}

	public Map<String, Kind> kinds() {
		return kinds;
	}

	private void save() {
		store.yaml.set("placed", null);
		// YAML keys can't hold some characters, so ',' is stored as ';'.
		placed.forEach((key, id) -> store.yaml.set("placed." + key.replace(',', ';'), id));
		store.save();
	}

	public Material material(Kind kind) {
		Material m = Material.matchMaterial(plugin.getConfig().getString("blocks." + kind.id(), kind.defaultMaterial().name()));
		return m != null && m.isBlock() && m.isItem() ? m : kind.defaultMaterial();
	}

	public ItemStack item(Kind kind, int amount) {
		ItemStack stack = new ItemStack(material(kind), amount);
		ItemMeta meta = stack.getItemMeta();
		meta.displayName(Component.text(kind.name(), kind.color()).decoration(TextDecoration.ITALIC, false));
		meta.lore(List.of(Component.text(kind.lore(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
		meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, kind.id());
		stack.setItemMeta(meta);
		return stack;
	}

	private Kind kindOf(ItemStack stack) {
		if (stack == null || !stack.hasItemMeta()) return null;
		String id = stack.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
		return id == null ? null : kinds.get(id);
	}

	public static String key(Block block) {
		return block.getWorld().getName() + "," + block.getX() + "," + block.getY() + "," + block.getZ();
	}

	/** The block at a position key, or null if its world isn't loaded. */
	public static Block block(String key) {
		String[] p = key.split(",");
		World world = Bukkit.getWorld(p[0]);
		return world == null ? null : world.getBlockAt(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
	}

	/** The kind of special block here, or null. Forgets it if the block was replaced some other way. */
	public Kind kindAt(Block block) {
		String key = key(block);
		String id = placed.get(key);
		if (id == null) return null;
		Kind kind = kinds.get(id);
		if (kind == null) return null;
		if (block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4) && block.getType() != material(kind)) {
			forget(block, kind);
			return null;
		}
		return kind;
	}

	/** Position keys of every placed block of this kind. */
	public List<String> placedOf(Kind kind) {
		List<String> keys = new ArrayList<>();
		placed.forEach((key, id) -> {
			if (id.equals(kind.id())) keys.add(key);
		});
		return keys;
	}

	/** Loaded blocks of this kind that are still there. */
	public List<Block> loaded(Kind kind) {
		List<Block> blocks = new ArrayList<>();
		for (String key : placedOf(kind)) {
			Block block = block(key);
			if (block == null || !block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) continue;
			if (kindAt(block) == kind) blocks.add(block);
		}
		return blocks;
	}

	private void forget(Block block, Kind kind) {
		if (placed.remove(key(block)) != null) save();
		kind.removed(block);
	}

	/** Removes it (the feature already took the block away, like an opened crate). */
	public void remove(Block block) {
		Kind kind = kinds.get(placed.get(key(block)));
		if (kind != null) forget(block, kind);
	}

	// ---------- Events ----------

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlace(BlockPlaceEvent event) {
		Kind kind = kindOf(event.getItemInHand());
		if (kind == null) return;
		placed.put(key(event.getBlockPlaced()), kind.id());
		save();
		kind.placed(event.getPlayer(), event.getBlockPlaced());
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void onInteract(PlayerInteractEvent event) {
		if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
		Block block = event.getClickedBlock();
		Kind kind = block == null ? null : kindAt(block);
		if (kind == null) return;
		Player player = event.getPlayer();
		ItemStack hand = player.getInventory().getItemInMainHand();
		// Sneaking with a block in hand places it against the special block, like any other block.
		if (player.isSneaking() && !hand.isEmpty() && hand.getType().isBlock()) return;
		event.setUseInteractedBlock(Event.Result.DENY);
		event.setUseItemInHand(Event.Result.DENY);
		kind.use(player, block, player.isSneaking() && hand.isEmpty());
	}

	private void dropItem(Kind kind, Block block) {
		if (kind.dropsItem()) block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), item(kind, 1));
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBreak(BlockBreakEvent event) {
		Kind kind = kindAt(event.getBlock());
		if (kind == null) return;
		event.setDropItems(false);
		if (event.getPlayer().getGameMode() != GameMode.CREATIVE) dropItem(kind, event.getBlock());
		forget(event.getBlock(), kind);
	}

	/** Broken by physics, like a button whose wall was mined. */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onDestroy(BlockDestroyEvent event) {
		Kind kind = kindAt(event.getBlock());
		if (kind == null) return;
		event.setWillDrop(false);
		dropItem(kind, event.getBlock());
		forget(event.getBlock(), kind);
	}

	@EventHandler(ignoreCancelled = true)
	public void onEntityExplode(EntityExplodeEvent event) {
		event.blockList().removeIf(b -> kindAt(b) != null);
	}

	@EventHandler(ignoreCancelled = true)
	public void onBlockExplode(BlockExplodeEvent event) {
		event.blockList().removeIf(b -> kindAt(b) != null);
	}

	@EventHandler(ignoreCancelled = true)
	public void onPistonExtend(BlockPistonExtendEvent event) {
		if (event.getBlocks().stream().anyMatch(b -> kindAt(b) != null)) event.setCancelled(true);
	}

	@EventHandler(ignoreCancelled = true)
	public void onPistonRetract(BlockPistonRetractEvent event) {
		if (event.getBlocks().stream().anyMatch(b -> kindAt(b) != null)) event.setCancelled(true);
	}

	public static Location center(Block block) {
		return block.getLocation().add(0.5, 0.5, 0.5);
	}
}
