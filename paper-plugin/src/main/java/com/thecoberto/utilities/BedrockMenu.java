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
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
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
 * players get a native Bedrock form; Java players get a chest menu. The buttons
 * come from the bedrock_menu section of config.yml.
 *
 * Players who can use WorldEdit's compass wand (operators) sneak + right-click
 * for the menu, so the wand still works.
 */
public final class BedrockMenu implements Listener {
	/** Bedrock sends a burst of interact packets for one tap. */
	private static final long DEBOUNCE_MS = 600;
	private static final String WORLDEDIT_WAND = "worldedit.navigation.thru.tool";

	private record Button(String text, String command, String image, Material item) {}

	private static final class Holder implements InventoryHolder {
		Inventory inventory;

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
			buttons.add(new Button(text.toString(), command.toString().replaceFirst("^/", ""),
					image == null ? null : image.toString(), item == null || !item.isItem() ? Material.PAPER : item));
		}
	}

	private boolean isBedrock(Player player) {
		return floodgate && FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
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
		if (bedrock) openForm(player);
		else openChest(player);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		lastOpened.remove(event.getPlayer().getUniqueId());
	}

	// ---------- Bedrock ----------

	private void openForm(Player player) {
		if (buttons.isEmpty()) return;
		SimpleForm.Builder form = SimpleForm.builder().title(title).content(content);
		for (Button button : buttons) {
			if (button.image() == null) form.button(button.text());
			else form.button(button.text(), button.image().startsWith("http") ? FormImage.Type.URL : FormImage.Type.PATH, button.image());
		}
		UUID id = player.getUniqueId();
		form.validResultHandler(response -> {
			int index = response.clickedButtonId();
			if (index < 0 || index >= buttons.size()) return;
			String command = buttons.get(index).command();
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
		if (buttons.isEmpty()) return;
		int rows = Math.min(6, (buttons.size() + 8) / 9);
		Holder holder = new Holder();
		Inventory inventory = Bukkit.createInventory(holder, rows * 9, Component.text(title));
		holder.inventory = inventory;
		for (int i = 0; i < buttons.size() && i < rows * 9; i++) {
			Button button = buttons.get(i);
			ItemStack stack = new ItemStack(button.item());
			ItemMeta meta = stack.getItemMeta();
			meta.displayName(Component.text(button.text(), NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
			meta.lore(List.of(Component.text("/" + button.command(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
			meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
			stack.setItemMeta(meta);
			inventory.setItem(i, stack);
		}
		player.openInventory(inventory);
	}

	@EventHandler
	public void onClick(InventoryClickEvent event) {
		if (!(event.getView().getTopInventory().getHolder() instanceof Holder)) return;
		event.setCancelled(true); // nothing in the menu (or the player's inventory, while it's open) moves
		if (!(event.getWhoClicked() instanceof Player player)) return;
		if (event.getClickedInventory() != event.getView().getTopInventory()) return;
		int slot = event.getSlot();
		if (slot < 0 || slot >= buttons.size()) return;
		String command = buttons.get(slot).command();
		// Close and run on the next tick: closing a menu inside its own click event isn't safe.
		Bukkit.getScheduler().runTask(plugin, () -> {
			if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof Holder)) return; // double click
			player.closeInventory();
			player.performCommand(command);
		});
	}

	@EventHandler
	public void onDrag(InventoryDragEvent event) {
		if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
	}
}
