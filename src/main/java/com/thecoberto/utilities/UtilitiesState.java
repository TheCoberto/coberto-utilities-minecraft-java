package com.thecoberto.utilities;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

/** World settings, saved in the world (data/coberto_utilities.dat). */
public class UtilitiesState extends PersistentState {
	private static final String FILE = "coberto_utilities";
	private static final PersistentState.Type<UtilitiesState> TYPE =
			new PersistentState.Type<>(UtilitiesState::new, UtilitiesState::fromNbt, null);

	/** True = creepers break blocks like vanilla. Off by default. */
	private boolean creeperGriefing;

	public static UtilitiesState get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE, FILE);
	}

	private static UtilitiesState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		UtilitiesState state = new UtilitiesState();
		state.creeperGriefing = nbt.getBoolean("creeperGriefing");
		return state;
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		nbt.putBoolean("creeperGriefing", creeperGriefing);
		return nbt;
	}

	public boolean creeperGriefing() {
		return creeperGriefing;
	}

	public void setCreeperGriefing(boolean value) {
		creeperGriefing = value;
		markDirty();
	}
}
