package com.thecoberto.utilities;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/**
 * /unload and /dump, like the old InvUnload plugin.
 *
 * /unload puts the items in your inventory into nearby chests that already hold the
 * same item. /dump empties your inventory into nearby chests, nearest first. Both leave
 * your hotbar, armor and offhand alone, and only use chests you could open yourself
 * (claims and regions are respected), with a particle trail to each chest that was used.
 */
public final class Unload implements CommandExecutor {
	private static final int RADIUS = 10;
	private static final int FIRST_MAIN_SLOT = 9; // 0-8 are the hotbar
	private static final int LAST_MAIN_SLOT = 35;

	private final JavaPlugin plugin;

	Unload(JavaPlugin plugin) {
		this.plugin = plugin;
		plugin.getCommand("unload").setExecutor(this);
		plugin.getCommand("dump").setExecutor(this);
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!(sender instanceof Player player)) {
			sender.sendMessage(Component.text("Only players can unload.", NamedTextColor.RED));
			return true;
		}
		boolean dump = command.getName().equals("dump");
		List<Inventory> chests = nearbyChests(player);
		if (chests.isEmpty()) {
			player.sendMessage(prefix().append(Component.text("There are no chests you can use within " + RADIUS + " blocks.", NamedTextColor.GRAY)));
			return true;
		}

		Map<Inventory, Integer> movedInto = new HashMap<>();
		int moved = 0;
		PlayerInventory inventory = player.getInventory();
		for (int slot = FIRST_MAIN_SLOT; slot <= LAST_MAIN_SLOT; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack == null || stack.getType().isAir()) continue;
			for (Inventory chest : chests) {
				if (!dump && !chest.contains(stack.getType())) continue;
				int before = stack.getAmount();
				Map<Integer, ItemStack> left = chest.addItem(stack.clone());
				int remaining = left.values().stream().mapToInt(ItemStack::getAmount).sum();
				int put = before - remaining;
				if (put <= 0) continue;
				moved += put;
				movedInto.merge(chest, put, Integer::sum);
				if (remaining == 0) {
					inventory.setItem(slot, null);
					break;
				}
				stack.setAmount(remaining);
				inventory.setItem(slot, stack);
			}
		}

		if (moved == 0) {
			player.sendMessage(prefix().append(Component.text(dump
					? "The chests nearby are full."
					: "None of the chests nearby hold the items you're carrying. Try /dump.", NamedTextColor.GRAY)));
			return true;
		}
		for (Inventory chest : movedInto.keySet()) trail(player, chest.getLocation());
		player.playSound(player, Sound.ENTITY_ITEM_PICKUP, 0.8f, 0.8f);
		player.sendMessage(prefix().append(Component.text((dump ? "Dumped " : "Unloaded ") + moved + " item" + (moved == 1 ? "" : "s")
				+ " into " + movedInto.size() + " chest" + (movedInto.size() == 1 ? "" : "s") + ".", NamedTextColor.WHITE)));
		return true;
	}

	/** Chests, trapped chests and barrels within range that the player could open, nearest first. Double chests count once. */
	private List<Inventory> nearbyChests(Player player) {
		Location origin = player.getLocation();
		List<Container> found = new ArrayList<>();
		List<Inventory> seen = new ArrayList<>();
		int cx = origin.getBlockX() >> 4, cz = origin.getBlockZ() >> 4, chunks = (RADIUS >> 4) + 1;
		for (int x = cx - chunks; x <= cx + chunks; x++) {
			for (int z = cz - chunks; z <= cz + chunks; z++) {
				if (!origin.getWorld().isChunkLoaded(x, z)) continue;
				for (BlockState state : origin.getWorld().getChunkAt(x, z).getTileEntities()) {
					if (!(state instanceof Chest || state instanceof Barrel) || state instanceof ShulkerBox) continue;
					if (state.getLocation().add(0.5, 0.5, 0.5).distance(origin) > RADIUS) continue;
					found.add((Container) state);
				}
			}
		}
		found.sort(Comparator.comparingDouble(c -> c.getLocation().distanceSquared(origin)));
		List<Inventory> chests = new ArrayList<>();
		for (Container container : found) {
			Inventory inv = container.getInventory(); // a double chest's whole inventory
			if (seen.contains(inv) || !canOpen(player, container.getBlock())) continue;
			seen.add(inv);
			chests.add(inv);
		}
		return chests;
	}

	/**
	 * Asks the other plugins (GriefPrevention, WorldGuard...) whether this player could open the
	 * chest, by firing the same event a real right-click would. Nothing actually opens.
	 */
	private static boolean canOpen(Player player, Block block) {
		PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null, block, BlockFace.UP, EquipmentSlot.HAND);
		Bukkit.getPluginManager().callEvent(event);
		return event.useInteractedBlock() != Event.Result.DENY;
	}

	private static void trail(Player player, Location chest) {
		if (chest == null) return;
		Location from = player.getLocation().add(0, 1, 0);
		Location to = chest.clone().add(0.5, 0.5, 0.5);
		Vector step = to.toVector().subtract(from.toVector());
		int points = (int) Math.max(2, step.length() * 3);
		step.multiply(1.0 / points);
		Location at = from.clone();
		for (int i = 0; i <= points; i++) {
			player.spawnParticle(Particle.HAPPY_VILLAGER, at, 1, 0, 0, 0, 0);
			at.add(step);
		}
	}

	private static Component prefix() {
		return Msg.prefix("Unload:", NamedTextColor.GOLD);
	}
}
