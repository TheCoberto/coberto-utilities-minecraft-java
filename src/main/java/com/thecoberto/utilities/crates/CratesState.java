package com.thecoberto.utilities.crates;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.world.PersistentState;

/** What each crate drops, saved in the world (data/coberto_crates.dat). */
public class CratesState extends PersistentState {
	private static final PersistentState.Type<CratesState> TYPE = new PersistentState.Type<>(CratesState::new, CratesState::fromNbt, null);

	/** weight = how often an item is picked compared to the others in the crate. */
	public static final class Entry {
		Identifier item;
		int weight;
		int min;
		int max;

		Entry(String item, int weight, int min, int max) {
			this(Identifier.of(item), weight, min, max);
		}

		Entry(Identifier item, int weight, int min, int max) {
			this.item = item;
			this.weight = weight;
			this.min = min;
			this.max = max;
		}
	}

	public record Drop(Identifier item, int amount) {}

	public static final class Crate {
		int rollsMin;
		int rollsMax;
		List<Entry> loot = new ArrayList<>();

		/** Adds the item, or changes it if the crate already has it. */
		Entry set(Identifier item, int weight, int min, int max) {
			for (Entry e : loot) {
				if (e.item.equals(item)) {
					e.weight = weight;
					e.min = min;
					e.max = max;
					return e;
				}
			}
			Entry e = new Entry(item, weight, min, max);
			loot.add(e);
			return e;
		}
	}

	private static List<Entry> commonLoot() {
		return new ArrayList<>(List.of(
				new Entry("minecraft:diamond", 20, 1, 3),
				new Entry("minecraft:emerald", 25, 2, 6),
				new Entry("minecraft:gold_ingot", 30, 3, 8),
				new Entry("minecraft:golden_carrot", 30, 4, 12),
				new Entry("minecraft:totem_of_undying", 6, 1, 1),
				new Entry("minecraft:shulker_box", 4, 1, 1),
				new Entry("minecraft:elytra", 1, 1, 1),
				// Mace parts
				new Entry("minecraft:heavy_core", 1, 1, 1),
				new Entry("minecraft:breeze_rod", 3, 1, 3)));
	}

	static Crate defaults(String key) {
		Crate crate = new Crate();
		crate.loot = commonLoot();
		switch (key) {
			case "spooky_crate" -> {
				crate.rollsMin = 3;
				crate.rollsMax = 5;
				crate.loot.add(new Entry("minecraft:carved_pumpkin", 15, 1, 2));
				crate.loot.add(new Entry("minecraft:jack_o_lantern", 10, 1, 2));
			}
			case "holiday_crate" -> {
				crate.rollsMin = 3;
				crate.rollsMax = 5;
				crate.loot.add(new Entry("minecraft:cookie", 15, 4, 12));
				crate.loot.add(new Entry("minecraft:snowball", 15, 8, 16));
			}
			default -> {
				crate.rollsMin = 2;
				crate.rollsMax = 4;
			}
		}
		return crate;
	}

	private final Map<String, Crate> crates = new HashMap<>();

	public static CratesState get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE, "coberto_crates");
	}

	Crate crate(String key) {
		return crates.computeIfAbsent(key, CratesState::defaults);
	}

	void reset(String key) {
		crates.put(key, defaults(key));
		markDirty();
	}

	private static CratesState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		CratesState state = new CratesState();
		for (String key : nbt.getKeys()) {
			NbtCompound c = nbt.getCompound(key);
			Crate crate = new Crate();
			crate.rollsMin = Math.max(1, c.getInt("rollsMin"));
			crate.rollsMax = Math.max(crate.rollsMin, c.getInt("rollsMax"));
			NbtList loot = c.getList("loot", NbtElement.COMPOUND_TYPE);
			for (int i = 0; i < loot.size(); i++) {
				NbtCompound e = loot.getCompound(i);
				Identifier id = Identifier.tryParse(e.getString("item"));
				if (id == null) continue;
				int min = Math.max(1, e.getInt("min"));
				crate.loot.add(new Entry(id, e.getInt("weight"), min, Math.max(min, e.getInt("max"))));
			}
			state.crates.put(key, crate);
		}
		return state;
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		crates.forEach((key, crate) -> {
			NbtCompound c = new NbtCompound();
			c.putInt("rollsMin", crate.rollsMin);
			c.putInt("rollsMax", crate.rollsMax);
			NbtList loot = new NbtList();
			for (Entry entry : crate.loot) {
				NbtCompound e = new NbtCompound();
				e.putString("item", entry.item.toString());
				e.putInt("weight", entry.weight);
				e.putInt("min", entry.min);
				e.putInt("max", entry.max);
				loot.add(e);
			}
			c.put("loot", loot);
			nbt.put(key, c);
		});
		return nbt;
	}
}
