package com.thecoberto.utilities.music;

import org.bukkit.Material;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A song the Music Selector can play: the vanilla music discs, in Bedrock's
 * order. Paper can't read song lengths from the game, so they're listed here.
 */
public record Track(String id, String name, int length, String sound, Material icon) {
	private static final Map<String, Track> ALL = new LinkedHashMap<>();

	private static void add(String id, String name, int length) {
		Material icon = Material.matchMaterial("music_disc_" + id);
		if (icon == null) return; // not in this Minecraft version
		ALL.put(id, new Track(id, name, length, "minecraft:music_disc." + id, icon));
	}

	static {
		add("13", "13", 178);
		add("cat", "Cat", 185);
		add("blocks", "Blocks", 345);
		add("chirp", "Chirp", 185);
		add("far", "Far", 174);
		add("mall", "Mall", 197);
		add("mellohi", "Mellohi", 96);
		add("stal", "Stal", 150);
		add("strad", "Strad", 188);
		add("ward", "Ward", 251);
		add("11", "11", 71);
		add("wait", "Wait", 238);
		add("pigstep", "Pigstep", 149);
		add("otherside", "Otherside", 195);
		add("5", "5", 178);
		add("relic", "Relic", 218);
		add("creator", "Creator", 176);
		add("creator_music_box", "Creator (Music Box)", 73);
		add("precipice", "Precipice", 299);
	}

	/** id -> track, in menu order. */
	public static Map<String, Track> all() {
		return ALL;
	}
}
