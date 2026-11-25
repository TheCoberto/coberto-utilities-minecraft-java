package com.thecoberto.utilities;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Chestsort+ auto-sorts any chest-sized inventory when it opens, including plugin menus
 * (DeluxeMenus, CommandPanels, our own menus), which scrambles their buttons. Its clicks
 * already skip menus (menus cancel clicks), so only opening needs guarding.
 * Chestsort+ sorts at HIGHEST: this copies a menu's contents at HIGH (after the menu
 * plugin has filled it) and puts them back at MONITOR, before the player sees anything.
 * Real containers (blocks, double chests, entities like minecarts and donkeys) are left alone.
 */
final class MenuSortGuard implements Listener {
	private final Map<UUID, ItemStack[]> before = new HashMap<>();

	MenuSortGuard(Plugin plugin) {
		Bukkit.getPluginManager().registerEvents(this, plugin);
	}

	private static boolean isMenu(Inventory inventory) {
		InventoryHolder holder = inventory.getHolder(false);
		return holder != null
				&& !(holder instanceof BlockInventoryHolder)
				&& !(holder instanceof DoubleChest)
				&& !(holder instanceof Entity);
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void remember(InventoryOpenEvent event) {
		before.remove(event.getPlayer().getUniqueId());
		if (!isMenu(event.getInventory())) return;
		before.put(event.getPlayer().getUniqueId(), event.getInventory().getContents().clone());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void restore(InventoryOpenEvent event) {
		ItemStack[] had = before.remove(event.getPlayer().getUniqueId());
		if (had == null || event.isCancelled()) return;
		Inventory inventory = event.getInventory();
		if (had.length == inventory.getSize()) inventory.setContents(had);
	}
}
