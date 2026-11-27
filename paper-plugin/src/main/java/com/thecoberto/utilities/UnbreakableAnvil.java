package com.thecoberto.utilities;

import com.destroystokyo.paper.event.block.AnvilDamagedEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * An anvil for public places like Spawn: it never chips or breaks from use, doesn't fall,
 * and only admins can break it. Otherwise it works like a normal anvil.
 */
public final class UnbreakableAnvil implements SpecialBlocks.Kind, Listener {
	private final SpecialBlocks blocks;

	UnbreakableAnvil(JavaPlugin plugin, SpecialBlocks blocks) {
		this.blocks = blocks;
		blocks.register(this);
		Bukkit.getPluginManager().registerEvents(this, plugin);
	}

	@Override
	public String id() {
		return "unbreakable_anvil";
	}

	@Override
	public String name() {
		return "Unbreakable Anvil";
	}

	@Override
	public NamedTextColor color() {
		return NamedTextColor.AQUA;
	}

	@Override
	public String lore() {
		return "Never wears out. Only admins can break it.";
	}

	@Override
	public Material defaultMaterial() {
		return Material.ANVIL;
	}

	@Override
	public boolean vanillaUse() {
		return true;
	}

	@Override
	public void use(Player player, Block block, boolean sneak) {
	}

	@Override
	public boolean adminOnly() {
		return true;
	}

	@EventHandler(ignoreCancelled = true)
	public void onDamage(AnvilDamagedEvent event) {
		Location at = event.getInventory().getLocation();
		if (at != null && blocks.kindAt(at.getBlock()) == this) event.setCancelled(true);
	}
}
