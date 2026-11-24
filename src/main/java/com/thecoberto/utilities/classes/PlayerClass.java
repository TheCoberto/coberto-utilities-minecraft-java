package com.thecoberto.utilities.classes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * The 15 classes. Each lists the effects to keep on a player right now; the
 * abilities (fire, poison, healing...) are in Classes.
 */
public record PlayerClass(String id, String name, ChatFormatting color, Item icon, String about,
		Function<ServerPlayer, List<Effect>> effects) {

	/** level 1 = amplifier 0. */
	public record Effect(Holder<MobEffect> type, int amplifier, int duration) {}

	// Lasting effects run 30 seconds and are topped up halfway, so they never
	// flicker (Night Vision blinks under 10 seconds). Situational ones are short
	// so they wear off soon after the situation ends. Regeneration needs a longer
	// time to actually heal, because topping it up resets its healing timer.
	static final int LASTING = 600;
	static final int SHORT = 40;
	static final int REGEN = 120;

	static Effect e(Holder<MobEffect> type) {
		return new Effect(type, 0, LASTING);
	}

	static Effect e(Holder<MobEffect> type, int level) {
		return new Effect(type, level - 1, LASTING);
	}

	static Effect e(Holder<MobEffect> type, int level, int duration) {
		return new Effect(type, level - 1, duration);
	}

	/** Every effect a class might give, so switching classes can clear them. */
	static final List<Holder<MobEffect>> ALL_CLASS_EFFECTS = List.of(
			MobEffects.STRENGTH, MobEffects.HASTE, MobEffects.SPEED, MobEffects.JUMP_BOOST,
			MobEffects.NIGHT_VISION, MobEffects.OOZING, MobEffects.REGENERATION, MobEffects.WEAKNESS,
			MobEffects.FIRE_RESISTANCE, MobEffects.WATER_BREATHING, MobEffects.CONDUIT_POWER,
			MobEffects.DOLPHINS_GRACE, MobEffects.SLOW_FALLING, MobEffects.RESISTANCE, MobEffects.SLOWNESS,
			MobEffects.HERO_OF_THE_VILLAGE);

	public static final Map<String, PlayerClass> ALL = new LinkedHashMap<>();

	private static void add(String id, String name, ChatFormatting color, Item icon, String about,
			Function<ServerPlayer, List<Effect>> effects) {
		ALL.put(id, new PlayerClass(id, name, color, icon, about, effects));
	}

	static boolean isNight(ServerPlayer player) {
		long t = player.level().getServer().overworld().getOverworldClockTime() % 24000;
		return t >= 13000 && t < 23000;
	}

	static boolean underOpenSky(ServerPlayer player) {
		return player.level().dimension() == Level.OVERWORLD
				&& player.level().canSeeSky(BlockPos.containing(player.getEyePosition()));
	}

	static boolean standingOnNature(ServerPlayer player) {
		ServerLevel world = player.level();
		BlockPos below = BlockPos.containing(player.getX(), player.getY() - 0.5, player.getZ());
		String path = BuiltInRegistries.BLOCK.getKey(world.getBlockState(below).getBlock()).getPath();
		return path.matches(".*(grass|moss|dirt|podzol|mycelium|leaves|log|mud).*");
	}

	private static List<Effect> list(Effect... effects) {
		return new ArrayList<>(List.of(effects));
	}

	static {
		add("warrior", "Warrior", ChatFormatting.RED, Items.IRON_SWORD, "Strength I and Haste I.",
				p -> list(e(MobEffects.STRENGTH), e(MobEffects.HASTE)));
		add("acrobat", "Acrobat", ChatFormatting.GREEN, Items.RABBIT_FOOT, "Speed II and Jump Boost II. Never takes fall damage.",
				p -> list(e(MobEffects.SPEED, 2), e(MobEffects.JUMP_BOOST, 2)));
		add("ghoul", "Ghoul", ChatFormatting.DARK_GREEN, Items.ROTTEN_FLESH,
				"Night Vision and Oozing. At night: Strength II, Speed I and Regeneration I. In daylight under the open sky: Weakness I.",
				p -> {
					List<Effect> l = list(e(MobEffects.NIGHT_VISION), e(MobEffects.OOZING));
					if (isNight(p)) {
						l.add(e(MobEffects.STRENGTH, 2, SHORT));
						l.add(e(MobEffects.SPEED, 1, SHORT));
						l.add(e(MobEffects.REGENERATION, 1, REGEN));
					} else if (underOpenSky(p)) {
						l.add(e(MobEffects.WEAKNESS, 1, SHORT));
					}
					return l;
				});
		add("pyro", "Pyro", ChatFormatting.GOLD, Items.BLAZE_POWDER,
				"Fire Resistance. With something in your off-hand, your melee hits set enemies on fire.",
				p -> list(e(MobEffects.FIRE_RESISTANCE)));
		add("aquarian", "Aquarian", ChatFormatting.AQUA, Items.HEART_OF_THE_SEA,
				"Water Breathing. In water: Conduit Power and Dolphin's Grace.",
				p -> {
					List<Effect> l = list(e(MobEffects.WATER_BREATHING));
					if (p.isInWater()) {
						l.add(e(MobEffects.CONDUIT_POWER, 1, SHORT));
						l.add(e(MobEffects.DOLPHINS_GRACE, 1, SHORT));
					}
					return l;
				});
		add("glider", "Glider", ChatFormatting.WHITE, Items.FEATHER, "Speed I. Falls slowly while in the air (sneak to drop fast).",
				p -> {
					List<Effect> l = list(e(MobEffects.SPEED));
					if (!p.onGround() && !p.isShiftKeyDown() && !p.isInWater() && !p.isFallFlying()) {
						l.add(e(MobEffects.SLOW_FALLING, 1, SHORT));
					}
					return l;
				});
		add("paladin", "Paladin", ChatFormatting.YELLOW, Items.GOLDEN_SWORD,
				"Resistance I. Melee hits on undead mobs deal 4 extra damage and heal you 1 heart.",
				p -> list(e(MobEffects.RESISTANCE)));
		add("sentinel", "Sentinel", ChatFormatting.GRAY, Items.SHIELD,
				"Resistance III while sneaking. Anyone who hits you in melee takes 2 damage back.",
				p -> p.isShiftKeyDown() ? list(e(MobEffects.RESISTANCE, 3, SHORT)) : list());
		add("miner", "Miner", ChatFormatting.DARK_GRAY, Items.IRON_PICKAXE, "Haste II. Night Vision while below Y 50.",
				p -> {
					List<Effect> l = list(e(MobEffects.HASTE, 2));
					if (p.getY() < 50) l.add(e(MobEffects.NIGHT_VISION, 1, 300));
					return l;
				});
		add("tank", "Tank", ChatFormatting.BLUE, Items.IRON_CHESTPLATE, "Resistance II and Slowness I.",
				p -> list(e(MobEffects.RESISTANCE, 2), e(MobEffects.SLOWNESS)));
		add("plaguebearer", "Plaguebearer", ChatFormatting.DARK_PURPLE, Items.SPIDER_EYE,
				"Immune to Poison. Melee hits poison enemies (Poison II, 4 seconds).",
				p -> list());
		add("bard", "Bard", ChatFormatting.LIGHT_PURPLE, Items.NOTE_BLOCK,
				"Hero of the Village (better trades). Gives Regeneration I and Speed I to you and every player within 8 blocks.",
				p -> list(e(MobEffects.HERO_OF_THE_VILLAGE)));
		add("druid", "Druid", ChatFormatting.GREEN, Items.OAK_SAPLING,
				"Regeneration I while standing on grass, dirt, moss, logs or leaves.",
				p -> standingOnNature(p) ? list(e(MobEffects.REGENERATION, 1, REGEN)) : list());
		add("necromancer", "Necromancer", ChatFormatting.DARK_GRAY, Items.WITHER_SKELETON_SKULL,
				"Immune to Wither. Melee hits wither enemies (Wither I, 4 seconds). Killing a mob gives Absorption I for 30 seconds.",
				p -> list());
		add("vampire", "Vampire", ChatFormatting.DARK_RED, Items.REDSTONE,
				"Melee hits heal you half a heart. At night: Night Vision, Strength I and Speed I. In daylight under the open sky: Weakness I and Slowness I.",
				p -> {
					if (isNight(p)) return list(e(MobEffects.NIGHT_VISION, 1, 300), e(MobEffects.STRENGTH, 1, SHORT), e(MobEffects.SPEED, 1, SHORT));
					if (underOpenSky(p)) return list(e(MobEffects.WEAKNESS, 1, SHORT), e(MobEffects.SLOWNESS, 1, SHORT));
					return list();
				});
	}
}
