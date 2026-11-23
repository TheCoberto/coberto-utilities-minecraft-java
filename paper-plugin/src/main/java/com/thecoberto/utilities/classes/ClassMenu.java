package com.thecoberto.utilities.classes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * The /class menu, laid out like the Fabric mod's: a two-row chest with one
 * item per class (hover to read what it does) and a barrier for "no class".
 * Clicking one picks it. Items in it can't be taken.
 */
final class ClassMenu implements Listener {
	private static final int NONE_SLOT = 17;
	private static final List<PlayerClass> ORDER = new ArrayList<>(PlayerClass.ALL.values());

	private final Classes classes;
	private final JavaPlugin plugin;

	ClassMenu(Classes classes, JavaPlugin plugin) {
		this.classes = classes;
		this.plugin = plugin;
	}

	private static final class Holder implements InventoryHolder {
		Inventory inventory;

		@Override
		public Inventory getInventory() {
			return inventory;
		}
	}

	void open(Player player) {
		PlayerClass current = classes.classOf(player);
		Component title = current == null
				? Component.text("Pick a class")
				: Component.text("Class: ").append(Component.text(current.name(), current.color()));
		Holder holder = new Holder();
		Inventory inventory = Bukkit.createInventory(holder, 18, title);
		holder.inventory = inventory;
		for (int i = 0; i < ORDER.size(); i++) {
			PlayerClass c = ORDER.get(i);
			inventory.setItem(i, icon(c.icon(), Component.text(c.name() + (c == current ? " (current)" : ""), c.color()), c.about(), c == current));
		}
		inventory.setItem(NONE_SLOT, icon(Material.BARRIER, Component.text("No class", NamedTextColor.RED), "Remove your class.", false));
		player.openInventory(inventory);
	}

	private static ItemStack icon(Material material, Component name, String about, boolean glint) {
		ItemStack stack = new ItemStack(material);
		ItemMeta meta = stack.getItemMeta();
		meta.displayName(name.decoration(TextDecoration.ITALIC, false));
		meta.lore(wrap(about));
		// Hide the sword damage, armor and so on.
		meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
		if (glint) meta.setEnchantmentGlintOverride(true);
		stack.setItemMeta(meta);
		return stack;
	}

	/** Lore lines of about 40 characters, gray and not italic. */
	private static List<Component> wrap(String text) {
		List<Component> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			if (!line.isEmpty() && line.length() + word.length() > 40) {
				lines.add(Component.text(line.toString(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
				line.setLength(0);
			}
			if (!line.isEmpty()) line.append(' ');
			line.append(word);
		}
		if (!line.isEmpty()) lines.add(Component.text(line.toString(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
		return lines;
	}

	@EventHandler
	public void onClick(InventoryClickEvent event) {
		if (!(event.getView().getTopInventory().getHolder() instanceof Holder)) return;
		event.setCancelled(true); // nothing in the menu (or the player's inventory, while it's open) moves
		if (!(event.getWhoClicked() instanceof Player player)) return;
		if (event.getClickedInventory() != event.getView().getTopInventory()) return;
		int slot = event.getSlot();
		if (slot != NONE_SLOT && (slot < 0 || slot >= ORDER.size())) return;
		PlayerClass picked = slot == NONE_SLOT ? null : ORDER.get(slot);
		// Close and pick on the next tick: closing a menu inside its own click event isn't safe.
		Bukkit.getScheduler().runTask(plugin, () -> {
			if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof Holder)) return; // double click
			player.closeInventory();
			if (classes.classOf(player) != picked) classes.setClass(player, picked);
		});
	}

	@EventHandler
	public void onDrag(InventoryDragEvent event) {
		if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
	}
}
