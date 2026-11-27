package com.thecoberto.utilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.util.FormImage;
import org.geysermc.floodgate.api.FloodgateApi;

/**
 * Compass Menu (still called the Bedrock Menu in config.yml): a player
 * right-clicks a compass and gets a button for each handy command. Bedrock
 * players get a native Bedrock form; Java players get a framed chest menu. The
 * buttons come from the bedrock_menu section of config.yml. /servermenu opens
 * it without a compass.
 *
 * Buttons may have a description (shown as lore on Java) and a permission
 * (players without it don't see the button, on either edition).
 *
 * Players who can use WorldEdit's compass wand (operators) sneak + right-click
 * for the menu, so the wand still works.
 */
public final class BedrockMenu implements Listener, CommandExecutor {
	/** Bedrock sends a burst of interact packets for one tap. */
	private static final long DEBOUNCE_MS = 600;
	private static final String WORLDEDIT_WAND = "worldedit.navigation.thru.tool";
	private static final Material FRAME = Material.GRAY_STAINED_GLASS_PANE;
	private static final Material CLOSE = Material.BARRIER;
	/** Buttons per row inside the frame. */
	private static final int PER_ROW = 7;

	private record Button(String text, String command, String image, Material item, List<String> description,
			String permission) {}

	private static final class Holder implements InventoryHolder {
		Inventory inventory;
		final Map<Integer, Button> slots = new HashMap<>();
		int closeSlot = -1;

		@Override
		public Inventory getInventory() {
			return inventory;
		}
	}

	private final JavaPlugin plugin;
	private final boolean floodgate;
	private final Map<UUID, Long> lastOpened = new HashMap<>();
	private String title;
	private String content;
	private List<Button> buttons;

	public BedrockMenu(JavaPlugin plugin) {
		this.plugin = plugin;
		this.floodgate = Bukkit.getPluginManager().isPluginEnabled("floodgate");
		load();
		Bukkit.getPluginManager().registerEvents(this, plugin);
		PluginCommand command = plugin.getCommand("servermenu");
		if (command != null) command.setExecutor(this);
	}

	private void load() {
		ConfigurationSection section = plugin.getConfig().getConfigurationSection("bedrock_menu");
		title = section == null ? "Server Menu" : section.getString("title", "Server Menu");
		content = section == null ? "" : section.getString("content", "");
		buttons = new ArrayList<>();
		if (section == null) return;
		for (Map<?, ?> entry : section.getMapList("buttons")) {
			Object text = entry.get("text");
			Object command = entry.get("command");
			if (text == null || command == null) {
				plugin.getLogger().warning("A bedrock_menu button needs both text and command; skipped " + entry);
				continue;
			}
			Object image = entry.get("image");
			Object itemName = entry.get("item");
			Material item = itemName == null ? null : Material.matchMaterial(itemName.toString().toUpperCase(Locale.ROOT));
			if (itemName != null && item == null) plugin.getLogger().warning("Unknown bedrock_menu item " + itemName + "; using paper.");
			List<String> description = new ArrayList<>();
			Object lore = entry.get("description");
			if (lore instanceof List<?> lines) lines.forEach(line -> description.add(String.valueOf(line)));
			else if (lore != null) description.add(lore.toString());
			Object permission = entry.get("permission");
			buttons.add(new Button(text.toString(), command.toString().replaceFirst("^/", ""),
					image == null ? null : image.toString(), item == null || !item.isItem() ? Material.PAPER : item,
					description, permission == null ? null : permission.toString()));
		}
	}

	private boolean isBedrock(Player player) {
		return floodgate && FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
	}

	private List<Button> visibleTo(Player player) {
		List<Button> visible = new ArrayList<>();
		for (Button button : buttons) {
			if (button.permission() == null || player.hasPermission(button.permission())) visible.add(button);
		}
		return visible;
	}

	private void open(Player player) {
		if (isBedrock(player)) openForm(player);
		else openChest(player);
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!(sender instanceof Player player)) {
			sender.sendMessage("Only players can open the menu.");
			return true;
		}
		open(player);
		return true;
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
	public void onInteract(PlayerInteractEvent event) {
		if (event.getHand() != EquipmentSlot.HAND) return;
		Action action = event.getAction();
		if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
		if (event.getItem() == null || event.getItem().getType() != Material.COMPASS) return;
		Block clicked = event.getClickedBlock();
		if (clicked != null && clicked.getType() == Material.LODESTONE) return; // linking a compass still works

		Player player = event.getPlayer();
		boolean bedrock = isBedrock(player);
		// Leave WorldEdit's compass wand alone unless they sneak.
		if (!bedrock && player.hasPermission(WORLDEDIT_WAND) && !player.isSneaking()) return;

		long now = System.currentTimeMillis();
		Long last = lastOpened.get(player.getUniqueId());
		if (last != null && now - last < DEBOUNCE_MS) return;
		lastOpened.put(player.getUniqueId(), now);

		event.setCancelled(true);
		open(player);
	}

	/** Switching to a compass shows a hint above the hotbar, so players know it opens the menu. */
	@EventHandler(ignoreCancelled = true)
	public void onHeld(PlayerItemHeldEvent event) {
		Player player = event.getPlayer();
		ItemStack item = player.getInventory().getItem(event.getNewSlot());
		if (item == null || item.getType() != Material.COMPASS) return;
		String how = !isBedrock(player) && player.hasPermission(WORLDEDIT_WAND) ? "Sneak + right-click" : "Right-click";
		player.sendActionBar(Component.text(how + " to open the server menu", NamedTextColor.GOLD));
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		lastOpened.remove(event.getPlayer().getUniqueId());
	}

	// ---------- Bedrock ----------

	private void openForm(Player player) {
		List<Button> shown = visibleTo(player);
		if (shown.isEmpty()) return;
		SimpleForm.Builder form = SimpleForm.builder().title(title).content(content);
		for (Button button : shown) {
			if (button.image() == null) form.button(button.text());
			else form.button(button.text(), button.image().startsWith("http") ? FormImage.Type.URL : FormImage.Type.PATH, button.image());
		}
		UUID id = player.getUniqueId();
		form.validResultHandler(response -> {
			int index = response.clickedButtonId();
			if (index < 0 || index >= shown.size()) return;
			String command = shown.get(index).command();
			// The response comes in off the main thread.
			Bukkit.getScheduler().runTask(plugin, () -> {
				Player online = Bukkit.getPlayer(id);
				if (online != null) online.performCommand(command);
			});
		});
		FloodgateApi.getInstance().sendForm(id, form.build());
	}

	// ---------- Java ----------

	private void openChest(Player player) {
		List<Button> shown = visibleTo(player);
		if (shown.isEmpty()) return;
		Holder holder = new Holder();
		// Framed: 7 buttons a row inside a glass border, each row centered, a close
		// button in the bottom middle. Too many buttons for 6 rows? A plain grid.
		int innerRows = (shown.size() + PER_ROW - 1) / PER_ROW;
		boolean framed = innerRows <= 4;
		int rows = framed ? innerRows + 2 : Math.min(6, (shown.size() + 8) / 9);
		Inventory inventory = Bukkit.createInventory(holder, rows * 9, Component.text(title));
		holder.inventory = inventory;
		if (framed) {
			ItemStack frame = icon(FRAME, Component.text(" "), List.of());
			for (int slot = 0; slot < rows * 9; slot++) inventory.setItem(slot, frame);
			for (int i = 0; i < shown.size(); i++) {
				int row = i / PER_ROW;
				int inRow = Math.min(PER_ROW, shown.size() - row * PER_ROW);
				int column = 1 + (PER_ROW - inRow) / 2 + i % PER_ROW;
				holder.slots.put((row + 1) * 9 + column, shown.get(i));
			}
			holder.closeSlot = (rows - 1) * 9 + 4;
			inventory.setItem(holder.closeSlot, icon(CLOSE,
					Component.text("Close", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false), List.of()));
		} else {
			for (int i = 0; i < shown.size() && i < rows * 9; i++) holder.slots.put(i, shown.get(i));
		}
		holder.slots.forEach((slot, button) -> inventory.setItem(slot, buttonIcon(button)));
		player.openInventory(inventory);
		player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.3f);
	}

	private static ItemStack buttonIcon(Button button) {
		List<Component> lore = new ArrayList<>();
		for (String line : button.description()) {
			lore.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
		}
		lore.add(Component.text("/" + button.command(), NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
		return icon(button.item(), Component.text(button.text(), NamedTextColor.YELLOW)
				.decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true), lore);
	}

	private static ItemStack icon(Material material, Component name, List<Component> lore) {
		ItemStack stack = new ItemStack(material);
		ItemMeta meta = stack.getItemMeta();
		meta.displayName(name);
		meta.lore(lore);
		meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
		stack.setItemMeta(meta);
		return stack;
	}

	@EventHandler
	public void onClick(InventoryClickEvent event) {
		if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
		event.setCancelled(true); // nothing in the menu (or the player's inventory, while it's open) moves
		if (!(event.getWhoClicked() instanceof Player player)) return;
		if (event.getClickedInventory() != event.getView().getTopInventory()) return;
		int slot = event.getSlot();
		// Close and run on the next tick: closing a menu inside its own click event isn't safe.
		if (slot == holder.closeSlot) {
			Bukkit.getScheduler().runTask(plugin, () -> player.closeInventory());
			return;
		}
		Button button = holder.slots.get(slot);
		if (button == null) return;
		player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.0f);
		Bukkit.getScheduler().runTask(plugin, () -> {
			if (player.getOpenInventory().getTopInventory().getHolder() != holder) return; // double click
			player.closeInventory();
			player.performCommand(button.command());
		});
	}

	@EventHandler
	public void onDrag(InventoryDragEvent event) {
		if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
	}
}
