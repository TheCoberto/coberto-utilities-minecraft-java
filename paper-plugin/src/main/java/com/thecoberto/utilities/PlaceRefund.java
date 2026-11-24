package com.thecoberto.utilities;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * When a protection plugin (WorldGuard, GriefPrevention...) cancels a block placement, the
 * block should stay in the player's hand. Sometimes it doesn't: the client (especially
 * Bedrock through Geyser) is left showing it used up, or the stack really is short.
 * This remembers the stack before the placement and, if the placement was cancelled and
 * the stack shrank, puts it back, then resyncs the player's inventory either way.
 */
final class PlaceRefund implements Listener {
	private final Plugin plugin;
	private final Map<UUID, ItemStack> before = new HashMap<>();

	PlaceRefund(Plugin plugin) {
		this.plugin = plugin;
		Bukkit.getPluginManager().registerEvents(this, plugin);
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void remember(BlockPlaceEvent event) {
		before.put(event.getPlayer().getUniqueId(), event.getItemInHand().clone());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void refund(BlockPlaceEvent event) {
		Player player = event.getPlayer();
		ItemStack had = before.remove(player.getUniqueId());
		if (!event.isCancelled() || had == null || player.getGameMode() == GameMode.CREATIVE) return;
		EquipmentSlot hand = event.getHand();
		Bukkit.getScheduler().runTask(plugin, () -> {
			if (!player.isOnline()) return;
			ItemStack now = player.getInventory().getItem(hand);
			boolean shrank = now == null || now.isEmpty() || now.isSimilar(had) && now.getAmount() < had.getAmount();
			if (shrank) player.getInventory().setItem(hand, had);
			player.updateInventory();
		});
	}
}
