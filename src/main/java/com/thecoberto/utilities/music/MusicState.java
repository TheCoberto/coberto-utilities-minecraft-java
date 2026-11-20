package com.thecoberto.utilities.music;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

/**
 * Music Selector settings, which apply to every selector, plus when each player
 * can queue again. Saved in the world (data/coberto_music.dat).
 */
public class MusicState extends PersistentState {
	private static final PersistentState.Type<MusicState> TYPE = new PersistentState.Type<>(MusicState::new, MusicState::fromNbt, null);

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
		MusicState state = server.getOverworld().getPersistentStateManager().getOrCreate(TYPE, "coberto_music");
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
			state.markDirty();
		}
		return state;
	}

	int priceOf(String id) {
		return prices.getOrDefault(id, price);
	}

	private static List<String> readList(NbtCompound nbt, String key) {
		List<String> list = new ArrayList<>();
		NbtList nbtList = nbt.getList(key, NbtElement.STRING_TYPE);
		for (int i = 0; i < nbtList.size(); i++) list.add(nbtList.getString(i));
		return list;
	}

	private static NbtList writeList(List<String> list) {
		NbtList nbtList = new NbtList();
		for (String s : list) nbtList.add(NbtString.of(s));
		return nbtList;
	}

	private static MusicState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		MusicState s = new MusicState();
		s.price = nbt.getInt("price");
		NbtCompound prices = nbt.getCompound("prices");
		for (String key : prices.getKeys()) s.prices.put(key, prices.getInt(key));
		s.cooldown = nbt.getInt("cooldown");
		s.maxQueue = Math.max(1, nbt.getInt("maxQueue"));
		s.allowed = readList(nbt, "allowed");
		s.autoplay = nbt.getBoolean("autoplay");
		s.shuffle = nbt.getBoolean("shuffle");
		s.playlist = readList(nbt, "playlist");
		s.known = readList(nbt, "known");
		NbtCompound cooldowns = nbt.getCompound("cooldowns");
		for (String key : cooldowns.getKeys()) {
			try {
				s.cooldowns.put(UUID.fromString(key), cooldowns.getLong(key));
			} catch (IllegalArgumentException ignored) {
				// Not a player id.
			}
		}
		return s;
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		nbt.putInt("price", price);
		NbtCompound pricesNbt = new NbtCompound();
		prices.forEach(pricesNbt::putInt);
		nbt.put("prices", pricesNbt);
		nbt.putInt("cooldown", cooldown);
		nbt.putInt("maxQueue", maxQueue);
		nbt.put("allowed", writeList(allowed));
		nbt.putBoolean("autoplay", autoplay);
		nbt.putBoolean("shuffle", shuffle);
		nbt.put("playlist", writeList(playlist));
		nbt.put("known", writeList(known));
		NbtCompound cooldownsNbt = new NbtCompound();
		long now = System.currentTimeMillis();
		cooldowns.forEach((uuid, until) -> {
			if (until > now) cooldownsNbt.putLong(uuid.toString(), until);
		});
		nbt.put("cooldowns", cooldownsNbt);
		return nbt;
	}
}
