package com.thecoberto.utilities;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;

import java.util.List;

/** Floating text above a block (a text display), found again by its tag. */
public final class Labels {
	private Labels() {}

	private static List<TextDisplay> find(Block block, double height, String tag) {
		Location at = block.getLocation().add(0.5, height, 0.5);
		return at.getWorld().getNearbyEntitiesByType(TextDisplay.class, at, 0.6).stream()
				.filter(e -> e.getScoreboardTags().contains(tag)).toList();
	}

	/** Shows text above the block, making the label if it's missing. */
	public static void set(Block block, double height, String tag, Component text) {
		List<TextDisplay> labels = find(block, height, tag);
		TextDisplay label;
		if (labels.isEmpty()) {
			label = block.getWorld().spawn(block.getLocation().add(0.5, height, 0.5), TextDisplay.class, d -> {
				d.setBillboard(Display.Billboard.CENTER);
				d.addScoreboardTag(tag);
			});
		} else {
			label = labels.get(0);
			for (int i = 1; i < labels.size(); i++) labels.get(i).remove();
		}
		if (!text.equals(label.text())) label.text(text);
	}

	public static boolean missing(Block block, double height, String tag) {
		return find(block, height, tag).isEmpty();
	}

	public static void remove(Block block, double height, String tag) {
		find(block, height, tag).forEach(TextDisplay::remove);
	}
}
