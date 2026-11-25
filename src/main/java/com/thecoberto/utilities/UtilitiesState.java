package com.thecoberto.utilities;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** World settings, saved in the world (data/coberto_utilities.dat). */
public class UtilitiesState extends SavedData {
	private static final String FILE = "coberto_utilities";
	private static final SavedDataType<UtilitiesState> TYPE = new SavedDataType<>(Identifier.withDefaultNamespace(FILE), UtilitiesState::new,
			CompoundTag.CODEC.xmap(UtilitiesState::fromNbt, s -> s.writeNbt(new CompoundTag())), null);

	/** True = creepers break blocks like vanilla. Off by default. */
	private boolean creeperGriefing;

	public static UtilitiesState get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	private static UtilitiesState fromNbt(CompoundTag nbt) {
		UtilitiesState state = new UtilitiesState();
		state.creeperGriefing = nbt.getBooleanOr("creeperGriefing", false);
		return state;
	}

		CompoundTag writeNbt(CompoundTag nbt) {
		nbt.putBoolean("creeperGriefing", creeperGriefing);
		return nbt;
	}

	public boolean creeperGriefing() {
		return creeperGriefing;
	}

	public void setCreeperGriefing(boolean value) {
		creeperGriefing = value;
		setDirty();
	}
}
