package com.thecoberto.utilities.music;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Music Selector settings, which apply to every selector, plus when each player
 * can queue again. Saved in the world (data/coberto_music.dat).
 */
public class MusicState extends SavedData {
	private static final SavedDataType<MusicState> TYPE = new SavedDataType<>(Identifier.withDefaultNamespace("coberto_music"), MusicState::new,
			CompoundTag.CODEC.xmap(MusicState::fromNbt, s -> s.writeNbt(new CompoundTag())), null);

	int price = 500; // Money to queue a song
	Map<String, Integer> prices = new HashMap<>(); // per-song prices, overriding price
	int cooldown = 150; // seconds between a player's songs
	int maxQueue = 5;
	List<String> allowed = new ArrayList<>(); // songs players can queue
	boolean autoplay = false; // loop the playlist when nobody has queued anything
	boolean shuffle = false;
	List<String> playlist = new ArrayList<>();
	/** Songs that existed when the settings were saved. New ones start out queueable and in autoplay. */
	List<String> known = new ArrayList<>();
	/** Player -> time (ms) they can queue again. */
	Map<UUID, Long> cooldowns = new HashMap<>();

	private boolean checked;

	public static MusicState get(MinecraftServer server) {
		MusicState state = server.getDataStorage().computeIfAbsent(TYPE);
		if (!state.checked) {
			state.checked = true;
			var tracks = Track.all(server);
			state.allowed.removeIf(id -> !tracks.containsKey(id));
			state.playlist.removeIf(id -> !tracks.containsKey(id));
			for (String id : tracks.keySet()) {
				if (state.known.contains(id)) continue;
				if (!state.allowed.contains(id)) state.allowed.add(id);
				if (!state.playlist.contains(id)) state.playlist.add(id);
			}
			state.known = new ArrayList<>(tracks.keySet());
			state.setDirty();
		}
		return state;
	}

	int priceOf(String id) {
		return prices.getOrDefault(id, price);
	}

	private static List<String> readList(CompoundTag nbt, String key) {
		List<String> list = new ArrayList<>();
		ListTag nbtList = nbt.getListOrEmpty(key);
		for (int i = 0; i < nbtList.size(); i++) list.add(nbtList.getStringOr(i, ""));
		return list;
	}

	private static ListTag writeList(List<String> list) {
		ListTag nbtList = new ListTag();
		for (String s : list) nbtList.add(StringTag.valueOf(s));
		return nbtList;
	}

	private static MusicState fromNbt(CompoundTag nbt) {
		MusicState s = new MusicState();
		s.price = nbt.getIntOr("price", 0);
		CompoundTag prices = nbt.getCompoundOrEmpty("prices");
		for (String key : prices.keySet()) s.prices.put(key, prices.getIntOr(key, 0));
		s.cooldown = nbt.getIntOr("cooldown", 0);
		s.maxQueue = Math.max(1, nbt.getIntOr("maxQueue", 0));
		s.allowed = readList(nbt, "allowed");
		s.autoplay = nbt.getBooleanOr("autoplay", false);
		s.shuffle = nbt.getBooleanOr("shuffle", false);
		s.playlist = readList(nbt, "playlist");
		s.known = readList(nbt, "known");
		CompoundTag cooldowns = nbt.getCompoundOrEmpty("cooldowns");
		for (String key : cooldowns.keySet()) {
			try {
				s.cooldowns.put(UUID.fromString(key), cooldowns.getLongOr(key, 0L));
			} catch (IllegalArgumentException ignored) {
				// Not a player id.
			}
		}
		return s;
	}

		CompoundTag writeNbt(CompoundTag nbt) {
		nbt.putInt("price", price);
		CompoundTag pricesNbt = new CompoundTag();
		prices.forEach(pricesNbt::putInt);
		nbt.put("prices", pricesNbt);
		nbt.putInt("cooldown", cooldown);
		nbt.putInt("maxQueue", maxQueue);
		nbt.put("allowed", writeList(allowed));
		nbt.putBoolean("autoplay", autoplay);
		nbt.putBoolean("shuffle", shuffle);
		nbt.put("playlist", writeList(playlist));
		nbt.put("known", writeList(known));
		CompoundTag cooldownsNbt = new CompoundTag();
		long now = System.currentTimeMillis();
		cooldowns.forEach((uuid, until) -> {
			if (until > now) cooldownsNbt.putLong(uuid.toString(), until);
		});
		nbt.put("cooldowns", cooldownsNbt);
		return nbt;
	}
}
