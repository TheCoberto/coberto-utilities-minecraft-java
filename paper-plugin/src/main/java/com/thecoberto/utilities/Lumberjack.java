package com.thecoberto.utilities;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lumberjack: chop one log of a tree with an axe and the rest of the tree comes down too.
 * Each log is broken as if the player broke it (claims and regions respected, the axe
 * wears per log, normal drops). Sneak to chop a single log; /lumberjack turns it off.
 * Only natural trees: logs players placed are remembered and never chained, and the
 * logs must have natural leaves (or nether wart) touching them.
 */
final class Lumberjack implements Listener, TabExecutor {
	private static final int MAX_LOGS = 256;

	private final NamespacedKey offKey;
	private final NamespacedKey placedKey;
	private boolean chopping;

	Lumberjack(JavaPlugin plugin) {
		offKey = new NamespacedKey(plugin, "lumberjack_off");
		placedKey = new NamespacedKey(plugin, "placed_logs");
		Bukkit.getPluginManager().registerEvents(this, plugin);
		plugin.getCommand("lumberjack").setExecutor(this);
		plugin.getCommand("lumberjack").setTabCompleter(this);
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!(sender instanceof Player player)) {
			Msg.error(sender, "Only players can use this.");
			return true;
		}
		boolean on;
		if (args.length == 0) on = !isOn(player);
		else {
			Boolean parsed = args.length == 1 ? Msg.parseBool(args[0]) : null;
			if (parsed == null) return false;
			on = parsed;
		}
		if (on) player.getPersistentDataContainer().remove(offKey);
		else player.getPersistentDataContainer().set(offKey, PersistentDataType.BYTE, (byte) 1);
		player.sendMessage(Msg.prefix("Lumberjack:", NamedTextColor.GOLD).append(on
				? Component.text("On. Chop a tree with an axe to fell it (sneak to chop one log).", NamedTextColor.GREEN)
				: Component.text("Off. Logs break one at a time.", NamedTextColor.GRAY)));
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		return args.length == 1 ? Msg.matching(List.of("on", "off"), args[0]) : List.of();
	}

	private boolean isOn(Player player) {
		return !player.getPersistentDataContainer().has(offKey);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void placed(BlockPlaceEvent event) {
		Block block = event.getBlockPlaced();
		if (!Tag.LOGS.isTagged(block.getType())) return;
		PersistentDataContainer data = block.getChunk().getPersistentDataContainer();
		long[] old = data.getOrDefault(placedKey, PersistentDataType.LONG_ARRAY, new long[0]);
		long[] grown = Arrays.copyOf(old, old.length + 1);
		grown[old.length] = pack(block);
		data.set(placedKey, PersistentDataType.LONG_ARRAY, grown);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void broken(BlockBreakEvent event) {
		Block block = event.getBlock();
		if (!Tag.LOGS.isTagged(block.getType())) return;
		boolean wasPlaced = forget(block);
		if (chopping || wasPlaced) return;

		Player player = event.getPlayer();
		if (player.isSneaking() || player.getGameMode() == GameMode.CREATIVE || !isOn(player)) return;
		if (!isAxe(player.getInventory().getItemInMainHand())) return;

		List<Block> tree = tree(block);
		if (tree.isEmpty()) return;
		chopping = true;
		try {
			for (Block log : tree) {
				ItemStack axe = player.getInventory().getItemInMainHand();
				if (!isAxe(axe) || almostBroken(axe)) break;
				player.breakBlock(log);
			}
		} finally {
			chopping = false;
		}
	}

	/** The logs of the tree above and beside the broken one, lowest first; empty if it isn't a natural tree. */
	private List<Block> tree(Block start) {
		Set<Block> seen = new HashSet<>();
		ArrayDeque<Block> queue = new ArrayDeque<>();
		List<Block> logs = new ArrayList<>();
		boolean leafy = false;
		seen.add(start);
		queue.add(start);
		while (!queue.isEmpty()) {
			Block current = queue.poll();
			for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
				Block next = current.getRelative(dx, dy, dz);
				if (!leafy && isNaturalLeaf(next)) leafy = true;
				if (next.getY() < start.getY() || !seen.add(next)) continue;
				if (!Tag.LOGS.isTagged(next.getType()) || isPlaced(next)) continue;
				logs.add(next);
				if (logs.size() >= MAX_LOGS) return leafy ? sorted(logs) : List.of();
				queue.add(next);
			}
		}
		return leafy ? sorted(logs) : List.of();
	}

	private static List<Block> sorted(List<Block> logs) {
		logs.sort(Comparator.comparingInt(Block::getY));
		return logs;
	}

	private static boolean isNaturalLeaf(Block block) {
		if (Tag.WART_BLOCKS.isTagged(block.getType())) return true;
		return Tag.LEAVES.isTagged(block.getType()) && block.getBlockData() instanceof Leaves leaves && !leaves.isPersistent();
	}

	private static boolean isAxe(ItemStack item) {
		return item != null && Tag.ITEMS_AXES.isTagged(item.getType());
	}

	/** Stop one hit before the axe breaks, so chopping never destroys it. */
	private static boolean almostBroken(ItemStack axe) {
		int max = axe.getType().getMaxDurability();
		return max > 0 && axe.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable d && d.getDamage() >= max - 1;
	}

	private boolean isPlaced(Block block) {
		long[] placed = block.getChunk().getPersistentDataContainer().get(placedKey, PersistentDataType.LONG_ARRAY);
		if (placed == null) return false;
		long key = pack(block);
		for (long p : placed) if (p == key) return true;
		return false;
	}

	/** Forgets a placed log; true if it was one. */
	private boolean forget(Block block) {
		Chunk chunk = block.getChunk();
		PersistentDataContainer data = chunk.getPersistentDataContainer();
		long[] placed = data.get(placedKey, PersistentDataType.LONG_ARRAY);
		if (placed == null) return false;
		long key = pack(block);
		long[] kept = Arrays.stream(placed).filter(p -> p != key).toArray();
		if (kept.length == placed.length) return false;
		if (kept.length == 0) data.remove(placedKey);
		else data.set(placedKey, PersistentDataType.LONG_ARRAY, kept);
		return true;
	}

	private static long pack(Block block) {
		return ((long) block.getX() & 0x3FFFFFF) << 38 | ((long) block.getZ() & 0x3FFFFFF) << 12 | (block.getY() & 0xFFF);
	}
}
