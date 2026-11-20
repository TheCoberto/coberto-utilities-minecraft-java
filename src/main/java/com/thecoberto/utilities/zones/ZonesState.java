package com.thecoberto.utilities.zones;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

/** The adventure zones, saved in the world (data/coberto_adventure_zones.dat). */
public class ZonesState extends PersistentState {
	private static final PersistentState.Type<ZonesState> TYPE = new PersistentState.Type<>(ZonesState::new, ZonesState::fromNbt, null);

	public static final class Zone {
		String name;
		String dimension;
		int x, y, z;
		int radius;
		boolean column; // ignore height
		boolean enabled = true;
		String enterMessage = "&aEntering {zone}";
		String leaveMessage = "&7Leaving {zone}";
	}

	final List<Zone> zones = new ArrayList<>();

	public static ZonesState get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE, "coberto_adventure_zones");
	}

	Optional<Zone> find(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		return zones.stream().filter(z -> z.name.toLowerCase(Locale.ROOT).equals(lower)).findFirst();
	}

	private static ZonesState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		ZonesState state = new ZonesState();
		NbtList list = nbt.getList("zones", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < list.size(); i++) {
			NbtCompound c = list.getCompound(i);
			Zone z = new Zone();
			z.name = c.getString("name");
			z.dimension = c.getString("dimension");
			z.x = c.getInt("x");
			z.y = c.getInt("y");
			z.z = c.getInt("z");
			z.radius = Math.max(1, c.getInt("radius"));
			z.column = c.getBoolean("column");
			z.enabled = c.getBoolean("enabled");
			z.enterMessage = c.getString("enterMessage");
			z.leaveMessage = c.getString("leaveMessage");
			state.zones.add(z);
		}
		return state;
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		NbtList list = new NbtList();
		for (Zone z : zones) {
			NbtCompound c = new NbtCompound();
			c.putString("name", z.name);
			c.putString("dimension", z.dimension);
			c.putInt("x", z.x);
			c.putInt("y", z.y);
			c.putInt("z", z.z);
			c.putInt("radius", z.radius);
			c.putBoolean("column", z.column);
			c.putBoolean("enabled", z.enabled);
			c.putString("enterMessage", z.enterMessage);
			c.putString("leaveMessage", z.leaveMessage);
			list.add(c);
		}
		nbt.put("zones", list);
		return nbt;
	}
}
