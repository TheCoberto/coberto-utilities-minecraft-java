package com.thecoberto.utilities.classes;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The /class menu as a native Bedrock form: one button per class, its powers
 * in the text above. Uses the Floodgate API, so only create this when Floodgate
 * is enabled.
 */
final class BedrockClassForm {
	private final Classes classes;
	private final JavaPlugin plugin;

	BedrockClassForm(Classes classes, JavaPlugin plugin) {
		this.classes = classes;
		this.plugin = plugin;
	}

	boolean isBedrock(Player player) {
		return FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
	}

	void open(Player player) {
		PlayerClass current = classes.classOf(player);
		List<PlayerClass> order = new ArrayList<>(PlayerClass.ALL.values());
		StringBuilder content = new StringBuilder();
		content.append(current == null ? "You have no class." : "Your class: §l" + current.name() + "§r");
		long left = classes.cooldownLeft(player);
		if (left > 0) content.append("\n§cYou can change it in ").append(Classes.formatTime(left)).append(".§r");
		else if (!player.hasPermission(Classes.BYPASS_PERMISSION)) content.append("\nAfter picking, you can't change again for 5 minutes.");
		content.append("\n");
		for (PlayerClass c : order) content.append("\n§l").append(c.name()).append("§r: ").append(c.about());

		SimpleForm.Builder form = SimpleForm.builder().title("Pick a Class").content(content.toString());
		for (PlayerClass c : order) form.button(c == current ? c.name() + " (current)" : c.name());
		form.button("No class");

		UUID id = player.getUniqueId();
		form.validResultHandler(response -> {
			int index = response.clickedButtonId();
			if (index < 0 || index > order.size()) return;
			PlayerClass picked = index == order.size() ? null : order.get(index);
			// The response comes in off the main thread.
			Bukkit.getScheduler().runTask(plugin, () -> {
				Player online = Bukkit.getPlayer(id);
				if (online != null) classes.choose(online, picked);
			});
		});
		FloodgateApi.getInstance().sendForm(id, form.build());
	}
}
