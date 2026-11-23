package com.thecoberto.utilities;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

/** One feature's saved settings, as a YAML file in the plugin's folder. */
public final class Store {
	private final JavaPlugin plugin;
	private final File file;
	public final YamlConfiguration yaml;

	public Store(JavaPlugin plugin, String name) {
		this.plugin = plugin;
		this.file = new File(plugin.getDataFolder(), name);
		this.yaml = YamlConfiguration.loadConfiguration(file);
	}

	public void save() {
		try {
			plugin.getDataFolder().mkdirs();
			yaml.save(file);
		} catch (IOException e) {
			plugin.getLogger().log(Level.WARNING, "Couldn't save " + file.getName(), e);
		}
	}
}
