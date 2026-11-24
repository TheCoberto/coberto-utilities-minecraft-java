package com.thecoberto.utilities.zones;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.ListTag;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** The adventure zones, saved in the world (data/coberto_adventure_zones.dat). */
public class ZonesState extends SavedData {
	private static final SavedDataType<ZonesState> TYPE = new SavedDataType<>(Identifier.withDefaultNamespace("coberto_adventure_zones"), ZonesState::new,
			CompoundTag.CODEC.xmap(ZonesState::fromNbt, s -> s.writeNbt(new CompoundTag())), null);

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
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	Optional<Zone> find(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		return zones.stream().filter(z -> z.name.toLowerCase(Locale.ROOT).equals(lower)).findFirst();
	}

	private static ZonesState fromNbt(CompoundTag nbt) {
		ZonesState state = new ZonesState();
		ListTag list = nbt.getListOrEmpty("zones");
		for (int i = 0; i < list.size(); i++) {
			CompoundTag c = list.getCompoundOrEmpty(i);
			Zone z = new Zone();
			z.name = c.getStringOr("name", "");
			z.dimension = c.getStringOr("dimension", "");
			z.x = c.getIntOr("x", 0);
			z.y = c.getIntOr("y", 0);
			z.z = c.getIntOr("z", 0);
			z.radius = Math.max(1, c.getIntOr("radius", 0));
			z.column = c.getBooleanOr("column", false);
			z.enabled = c.getBooleanOr("enabled", false);
			z.enterMessage = c.getStringOr("enterMessage", "");
			z.leaveMessage = c.getStringOr("leaveMessage", "");
			state.zones.add(z);
		}
		return state;
	}

		CompoundTag writeNbt(CompoundTag nbt) {
		ListTag list = new ListTag();
		for (Zone z : zones) {
			CompoundTag c = new CompoundTag();
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
