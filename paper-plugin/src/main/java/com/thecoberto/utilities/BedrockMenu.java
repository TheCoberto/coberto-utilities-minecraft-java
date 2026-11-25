package com.thecoberto.utilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.util.FormImage;
import org.geysermc.floodgate.api.FloodgateApi;

/**
 * Bedrock Menu: a Bedrock player right-clicks a compass and gets a native Bedrock
 * form with a button for each handy command. The buttons come from the
 * bedrock_menu section of config.yml.
 *
 * Uses the Floodgate API, so only create this when Floodgate is enabled.
 */
public final class BedrockMenu implements Listener {
	/** Bedrock sends a burst of interact packets for one tap. */
	private static final long DEBOUNCE_MS = 600;

	private record Button(String text, String command, String image) {}

	private final JavaPlugin plugin;
	private final Map<UUID, Long> lastOpened = new HashMap<>();
	private String title;
	private String content;
	private List<Button> buttons;

	public BedrockMenu(JavaPlugin plugin) {
		this.plugin = plugin;
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
			buttons.add(new Button(text.toString(), command.toString().replaceFirst("^/", ""), image == null ? null : image.toString()));
		}
	}

	@EventHandler(ignoreCancelled = false)
	public void onInteract(PlayerInteractEvent event) {
		if (event.getHand() != EquipmentSlot.HAND) return;
		Action action = event.getAction();
		if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
		if (event.getItem() == null || event.getItem().getType() != Material.COMPASS) return;
		Block clicked = event.getClickedBlock();
		if (clicked != null && clicked.getType() == Material.LODESTONE) return; // linking a compass still works

		Player player = event.getPlayer();
		if (!FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId())) return;

		long now = System.currentTimeMillis();
		Long last = lastOpened.get(player.getUniqueId());
		if (last != null && now - last < DEBOUNCE_MS) return;
		lastOpened.put(player.getUniqueId(), now);

		event.setCancelled(true);
		open(player);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		lastOpened.remove(event.getPlayer().getUniqueId());
	}

	private void open(Player player) {
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
}
