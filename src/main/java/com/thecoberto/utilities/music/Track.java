package com.thecoberto.utilities.music;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.resources.Identifier;

/**
 * A song the Music Selector can play: every jukebox song the server knows,
 * so the music discs plus any songs a data pack adds.
 */
public record Track(String id, String name, int length, Holder<SoundEvent> sound, Item icon) {
	/** Disc order, as on Bedrock. Songs not listed go after these. */
	private static final List<String> ORDER = List.of("13", "cat", "blocks", "chirp", "far", "mall", "mellohi", "stal",
			"strad", "ward", "11", "wait", "pigstep", "otherside", "5", "relic", "creator", "creator_music_box", "precipice");

	private static Map<String, Track> cache;

	/** id -> track, in menu order. Song ids drop "minecraft:", others keep their namespace. */
	public static Map<String, Track> all(MinecraftServer server) {
		if (cache != null) return cache;
		List<Track> tracks = new ArrayList<>();
		var registry = server.registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG);
		for (var entry : registry.entrySet()) {
			Identifier key = entry.getKey().identifier();
			JukeboxSong song = entry.getValue();
			boolean vanilla = key.getNamespace().equals("minecraft");
			String id = vanilla ? key.getPath() : key.toString();
			String name = vanilla ? prettyName(key.getPath()) : song.description().getString();
			Item icon = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(key.getNamespace(), "music_disc_" + key.getPath()));
			if (icon == Items.AIR) icon = Items.MUSIC_DISC_CAT;
			tracks.add(new Track(id, name, Math.max(1, Math.round(song.lengthInSeconds())), song.soundEvent(), icon));
		}
		tracks.sort(Comparator.comparingInt((Track t) -> ORDER.contains(t.id()) ? ORDER.indexOf(t.id()) : ORDER.size())
				.thenComparing(Track::name));
		Map<String, Track> map = new LinkedHashMap<>();
		for (Track t : tracks) map.put(t.id(), t);
		cache = map;
		return map;
	}

	static void clearCache() {
		cache = null;
	}

	private static String prettyName(String path) {
		if (path.equals("creator_music_box")) return "Creator (Music Box)";
		StringBuilder name = new StringBuilder();
		for (String word : path.split("_")) {
			if (name.length() > 0) name.append(' ');
			name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return name.toString();
	}
}
