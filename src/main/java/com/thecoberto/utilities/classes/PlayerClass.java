package com.thecoberto.utilities.classes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * The 15 classes. Each lists the effects to keep on a player right now; the
 * abilities (fire, poison, healing...) are in Classes.
 */
public record PlayerClass(String id, String name, Formatting color, Item icon, String about,
		Function<ServerPlayerEntity, List<Effect>> effects) {

	/** level 1 = amplifier 0. */
	public record Effect(RegistryEntry<StatusEffect> type, int amplifier, int duration) {}

	// Lasting effects run 30 seconds and are topped up halfway, so they never
	// flicker (Night Vision blinks under 10 seconds). Situational ones are short
	// so they wear off soon after the situation ends. Regeneration needs a longer
	// time to actually heal, because topping it up resets its healing timer.
	static final int LASTING = 600;
	static final int SHORT = 40;
	static final int REGEN = 120;

	static Effect e(RegistryEntry<StatusEffect> type) {
		return new Effect(type, 0, LASTING);
	}

	static Effect e(RegistryEntry<StatusEffect> type, int level) {
		return new Effect(type, level - 1, LASTING);
	}

	static Effect e(RegistryEntry<StatusEffect> type, int level, int duration) {
		return new Effect(type, level - 1, duration);
	}

	/** Every effect a class might give, so switching classes can clear them. */
	static final List<RegistryEntry<StatusEffect>> ALL_CLASS_EFFECTS = List.of(
			StatusEffects.STRENGTH, StatusEffects.HASTE, StatusEffects.SPEED, StatusEffects.JUMP_BOOST,
			StatusEffects.NIGHT_VISION, StatusEffects.OOZING, StatusEffects.REGENERATION, StatusEffects.WEAKNESS,
			StatusEffects.FIRE_RESISTANCE, StatusEffects.WATER_BREATHING, StatusEffects.CONDUIT_POWER,
			StatusEffects.DOLPHINS_GRACE, StatusEffects.SLOW_FALLING, StatusEffects.RESISTANCE, StatusEffects.SLOWNESS,
			StatusEffects.HERO_OF_THE_VILLAGE);

	public static final Map<String, PlayerClass> ALL = new LinkedHashMap<>();

	private static void add(String id, String name, Formatting color, Item icon, String about,
			Function<ServerPlayerEntity, List<Effect>> effects) {
		ALL.put(id, new PlayerClass(id, name, color, icon, about, effects));
	}

	static boolean isNight(ServerPlayerEntity player) {
		long t = player.server.getOverworld().getTimeOfDay() % 24000;
		return t >= 13000 && t < 23000;
	}

	static boolean underOpenSky(ServerPlayerEntity player) {
		return player.getWorld().getRegistryKey() == World.OVERWORLD
				&& player.getWorld().isSkyVisible(BlockPos.ofFloored(player.getEyePos()));
	}

	static boolean standingOnNature(ServerPlayerEntity player) {
		ServerWorld world = player.getServerWorld();
		BlockPos below = BlockPos.ofFloored(player.getX(), player.getY() - 0.5, player.getZ());
		String path = Registries.BLOCK.getId(world.getBlockState(below).getBlock()).getPath();
		return path.matches(".*(grass|moss|dirt|podzol|mycelium|leaves|log|mud).*");
	}

	private static List<Effect> list(Effect... effects) {
		return new ArrayList<>(List.of(effects));
	}

	static {
		add("warrior", "Warrior", Formatting.RED, Items.IRON_SWORD, "Strength I and Haste I.",
				p -> list(e(StatusEffects.STRENGTH), e(StatusEffects.HASTE)));
		add("acrobat", "Acrobat", Formatting.GREEN, Items.RABBIT_FOOT, "Speed II and Jump Boost II. Never takes fall damage.",
				p -> list(e(StatusEffects.SPEED, 2), e(StatusEffects.JUMP_BOOST, 2)));
		add("ghoul", "Ghoul", Formatting.DARK_GREEN, Items.ROTTEN_FLESH,
				"Night Vision and Oozing. At night: Strength II, Speed I and Regeneration I. In daylight under the open sky: Weakness I.",
				p -> {
					List<Effect> l = list(e(StatusEffects.NIGHT_VISION), e(StatusEffects.OOZING));
					if (isNight(p)) {
						l.add(e(StatusEffects.STRENGTH, 2, SHORT));
						l.add(e(StatusEffects.SPEED, 1, SHORT));
						l.add(e(StatusEffects.REGENERATION, 1, REGEN));
					} else if (underOpenSky(p)) {
						l.add(e(StatusEffects.WEAKNESS, 1, SHORT));
					}
					return l;
				});
		add("pyro", "Pyro", Formatting.GOLD, Items.BLAZE_POWDER,
				"Fire Resistance. With something in your off-hand, your melee hits set enemies on fire.",
				p -> list(e(StatusEffects.FIRE_RESISTANCE)));
		add("aquarian", "Aquarian", Formatting.AQUA, Items.HEART_OF_THE_SEA,
				"Water Breathing. In water: Conduit Power and Dolphin's Grace.",
				p -> {
					List<Effect> l = list(e(StatusEffects.WATER_BREATHING));
					if (p.isTouchingWater()) {
						l.add(e(StatusEffects.CONDUIT_POWER, 1, SHORT));
						l.add(e(StatusEffects.DOLPHINS_GRACE, 1, SHORT));
					}
					return l;
				});
		add("glider", "Glider", Formatting.WHITE, Items.FEATHER, "Speed I. Falls slowly while in the air (sneak to drop fast).",
				p -> {
					List<Effect> l = list(e(StatusEffects.SPEED));
					if (!p.isOnGround() && !p.isSneaking() && !p.isTouchingWater() && !p.isFallFlying()) {
						l.add(e(StatusEffects.SLOW_FALLING, 1, SHORT));
					}
					return l;
				});
		add("paladin", "Paladin", Formatting.YELLOW, Items.GOLDEN_SWORD,
				"Resistance I. Melee hits on undead mobs deal 4 extra damage and heal you 1 heart.",
				p -> list(e(StatusEffects.RESISTANCE)));
		add("sentinel", "Sentinel", Formatting.GRAY, Items.SHIELD,
				"Resistance III while sneaking. Anyone who hits you in melee takes 2 damage back.",
				p -> p.isSneaking() ? list(e(StatusEffects.RESISTANCE, 3, SHORT)) : list());
		add("miner", "Miner", Formatting.DARK_GRAY, Items.IRON_PICKAXE, "Haste II. Night Vision while below Y 50.",
				p -> {
					List<Effect> l = list(e(StatusEffects.HASTE, 2));
					if (p.getY() < 50) l.add(e(StatusEffects.NIGHT_VISION, 1, 300));
					return l;
				});
		add("tank", "Tank", Formatting.BLUE, Items.IRON_CHESTPLATE, "Resistance II and Slowness I.",
				p -> list(e(StatusEffects.RESISTANCE, 2), e(StatusEffects.SLOWNESS)));
		add("plaguebearer", "Plaguebearer", Formatting.DARK_PURPLE, Items.SPIDER_EYE,
				"Immune to Poison. Melee hits poison enemies (Poison II, 4 seconds).",
				p -> list());
		add("bard", "Bard", Formatting.LIGHT_PURPLE, Items.NOTE_BLOCK,
				"Hero of the Village (better trades). Gives Regeneration I and Speed I to you and every player within 8 blocks.",
				p -> list(e(StatusEffects.HERO_OF_THE_VILLAGE)));
		add("druid", "Druid", Formatting.GREEN, Items.OAK_SAPLING,
				"Regeneration I while standing on grass, dirt, moss, logs or leaves.",
				p -> standingOnNature(p) ? list(e(StatusEffects.REGENERATION, 1, REGEN)) : list());
		add("necromancer", "Necromancer", Formatting.DARK_GRAY, Items.WITHER_SKELETON_SKULL,
				"Immune to Wither. Melee hits wither enemies (Wither I, 4 seconds). Killing a mob gives Absorption I for 30 seconds.",
				p -> list());
		add("vampire", "Vampire", Formatting.DARK_RED, Items.REDSTONE,
				"Melee hits heal you half a heart. At night: Night Vision, Strength I and Speed I. In daylight under the open sky: Weakness I and Slowness I.",
				p -> {
					if (isNight(p)) return list(e(StatusEffects.NIGHT_VISION, 1, 300), e(StatusEffects.STRENGTH, 1, SHORT), e(StatusEffects.SPEED, 1, SHORT));
					if (underOpenSky(p)) return list(e(StatusEffects.WEAKNESS, 1, SHORT), e(StatusEffects.SLOWNESS, 1, SHORT));
					return list();
				});
	}
}
