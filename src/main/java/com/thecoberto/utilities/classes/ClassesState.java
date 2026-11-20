package com.thecoberto.utilities.classes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

/**
 * Each player's class, saved in the world (data/coberto_classes.dat) by
 * player UUID, so it stays through logging out and dying.
 */
public class ClassesState extends PersistentState {
	private static final PersistentState.Type<ClassesState> TYPE = new PersistentState.Type<>(ClassesState::new, ClassesState::fromNbt, null);

	private final Map<UUID, String> classes = new HashMap<>();

	public static ClassesState get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE, "coberto_classes");
	}

	public PlayerClass of(UUID player) {
		String id = classes.get(player);
		return id == null ? null : PlayerClass.ALL.get(id);
	}

	void set(UUID player, PlayerClass playerClass) {
		if (playerClass == null) classes.remove(player);
		else classes.put(player, playerClass.id());
		markDirty();
	}

	private static ClassesState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		ClassesState state = new ClassesState();
		for (String key : nbt.getKeys()) {
			try {
				state.classes.put(UUID.fromString(key), nbt.getString(key));
			} catch (IllegalArgumentException ignored) {
				// Not a player id: skip it.
			}
		}
		return state;
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		classes.forEach((uuid, id) -> nbt.putString(uuid.toString(), id));
		return nbt;
	}
}
