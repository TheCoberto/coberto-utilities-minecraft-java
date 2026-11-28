package com.thecoberto.utilities;

import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.bedrock.SessionLoadResourcePacksEvent;
import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;

import java.util.function.BooleanSupplier;
import java.util.logging.Level;

/**
 * Face Moon for Bedrock players. Geyser only sends packs when a player joins, so
 * Bedrock players who join during a face night get the face-moon pack; players
 * already online see it after they rejoin. Only loaded when Geyser is installed.
 */
final class FaceMoonBedrock {
	private volatile ResourcePack pack;
	private volatile String url;

	FaceMoonBedrock(JavaPlugin plugin, BooleanSupplier active) {
		GeyserApi.api().eventBus().subscribe(EventRegistrar.of(plugin), SessionLoadResourcePacksEvent.class, event -> {
			ResourcePack p = pack;
			if (p != null && active.getAsBoolean()) event.register(p);
		});
	}

	/** Loads (Geyser downloads and caches) the Bedrock pack at this URL. Blank turns Bedrock off. */
	void setUrl(JavaPlugin plugin, String newUrl) {
		if (newUrl == null || newUrl.isBlank()) { pack = null; url = null; return; }
		if (newUrl.equals(url)) return;
		url = newUrl;
		try {
			pack = ResourcePack.create(PackCodec.url(newUrl));
		} catch (Exception e) {
			pack = null;
			plugin.getLogger().log(Level.WARNING, "Face Moon: couldn't load the Bedrock pack from " + newUrl, e);
		}
	}

	boolean loaded() { return pack != null; }

	boolean isBedrock(java.util.UUID player) { return GeyserApi.api().isBedrockPlayer(player); }
}
