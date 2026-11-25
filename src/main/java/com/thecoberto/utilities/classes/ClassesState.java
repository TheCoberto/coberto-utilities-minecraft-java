package com.thecoberto.utilities.classes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Each player's class, saved in the world (data/coberto_classes.dat) by
 * player UUID, so it stays through logging out and dying.
 */
public class ClassesState extends SavedData {
	private static final SavedDataType<ClassesState> TYPE = new SavedDataType<>(Identifier.withDefaultNamespace("coberto_classes"), ClassesState::new,
			CompoundTag.CODEC.xmap(ClassesState::fromNbt, s -> s.writeNbt(new CompoundTag())), null);

	private final Map<UUID, String> classes = new HashMap<>();

	public static ClassesState get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	public PlayerClass of(UUID player) {
		String id = classes.get(player);
		return id == null ? null : PlayerClass.ALL.get(id);
	}

	void set(UUID player, PlayerClass playerClass) {
		if (playerClass == null) classes.remove(player);
		else classes.put(player, playerClass.id());
		setDirty();
	}

	private static ClassesState fromNbt(CompoundTag nbt) {
		ClassesState state = new ClassesState();
		for (String key : nbt.keySet()) {
			try {
				state.classes.put(UUID.fromString(key), nbt.getStringOr(key, ""));
			} catch (IllegalArgumentException ignored) {
				// Not a player id: skip it.
			}
		}
		return state;
	}

		CompoundTag writeNbt(CompoundTag nbt) {
		classes.forEach((uuid, id) -> nbt.putString(uuid.toString(), id));
		return nbt;
	}
}
