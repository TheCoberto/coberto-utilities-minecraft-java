package com.thecoberto.utilities.classes;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The 15 classes, the same as the Fabric mod and the Bedrock add-on. Each lists
 * the effects to keep on a player right now; the abilities (fire, poison,
 * healing...) are in Classes.
 */
public record PlayerClass(String id, String name, NamedTextColor color, Material icon, String about,
		Function<Player, List<Effect>> effects) {

	/** level 1 = amplifier 0. */
	public record Effect(PotionEffectType type, int amplifier, int duration) {
	}

	// Lasting effects run 30 seconds and are topped up halfway, so they never
	// flicker (Night Vision blinks under 10 seconds). Situational ones are short
	// so they wear off soon after the situation ends. Regeneration needs a longer
	// time to actually heal, because topping it up resets its healing timer.
	static final int LASTING = 600;
	static final int SHORT = 40;
	static final int REGEN = 120;

	static Effect e(PotionEffectType type) {
		return new Effect(type, 0, LASTING);
	}

	static Effect e(PotionEffectType type, int level) {
		return new Effect(type, level - 1, LASTING);
	}

	static Effect e(PotionEffectType type, int level, int duration) {
		return new Effect(type, level - 1, duration);
	}

	/** Every effect a class might give, so switching classes can clear them. */
	static final List<PotionEffectType> ALL_CLASS_EFFECTS = List.of(
			PotionEffectType.STRENGTH, PotionEffectType.HASTE, PotionEffectType.SPEED, PotionEffectType.JUMP_BOOST,
			PotionEffectType.NIGHT_VISION, PotionEffectType.OOZING, PotionEffectType.REGENERATION, PotionEffectType.WEAKNESS,
			PotionEffectType.FIRE_RESISTANCE, PotionEffectType.WATER_BREATHING, PotionEffectType.CONDUIT_POWER,
			PotionEffectType.DOLPHINS_GRACE, PotionEffectType.SLOW_FALLING, PotionEffectType.RESISTANCE,
			PotionEffectType.SLOWNESS, PotionEffectType.HERO_OF_THE_VILLAGE);

	public static final Map<String, PlayerClass> ALL = new LinkedHashMap<>();

	private static void add(String id, String name, NamedTextColor color, Material icon, String about,
			Function<Player, List<Effect>> effects) {
		ALL.put(id, new PlayerClass(id, name, color, icon, about, effects));
	}

	/** Night in the overworld (the main world), like the Fabric and Bedrock versions. */
	static boolean isNight(Player player) {
		World overworld = player.getServer().getWorlds().get(0);
		long t = overworld.getTime() % 24000;
		return t >= 13000 && t < 23000;
	}

	static boolean underOpenSky(Player player) {
		if (player.getWorld().getEnvironment() != World.Environment.NORMAL) return false;
		var eye = player.getEyeLocation();
		return player.getWorld().getHighestBlockYAt(eye.getBlockX(), eye.getBlockZ(), HeightMap.MOTION_BLOCKING) < eye.getY();
	}

	static boolean standingOnNature(Player player) {
		Block below = player.getLocation().subtract(0, 0.5, 0).getBlock();
		return below.getType().getKey().getKey().matches(".*(grass|moss|dirt|podzol|mycelium|leaves|log|mud).*");
	}

	private static List<Effect> list(Effect... effects) {
		return new ArrayList<>(List.of(effects));
	}

	@SuppressWarnings("deprecation") // isOnGround is client-reported, but it's what the class needs
	private static boolean inAir(Player p) {
		return !p.isOnGround();
	}

	static {
		add("warrior", "Warrior", NamedTextColor.RED, Material.IRON_SWORD, "Strength I and Haste I.",
				p -> list(e(PotionEffectType.STRENGTH), e(PotionEffectType.HASTE)));
		add("acrobat", "Acrobat", NamedTextColor.GREEN, Material.RABBIT_FOOT, "Speed II and Jump Boost II. Never takes fall damage.",
				p -> list(e(PotionEffectType.SPEED, 2), e(PotionEffectType.JUMP_BOOST, 2)));
		add("ghoul", "Ghoul", NamedTextColor.DARK_GREEN, Material.ROTTEN_FLESH,
				"Night Vision and Oozing. At night: Strength II, Speed I and Regeneration I. In daylight under the open sky: Weakness I.",
				p -> {
					List<Effect> l = list(e(PotionEffectType.NIGHT_VISION), e(PotionEffectType.OOZING));
					if (isNight(p)) {
						l.add(e(PotionEffectType.STRENGTH, 2, SHORT));
						l.add(e(PotionEffectType.SPEED, 1, SHORT));
						l.add(e(PotionEffectType.REGENERATION, 1, REGEN));
					} else if (underOpenSky(p)) {
						l.add(e(PotionEffectType.WEAKNESS, 1, SHORT));
					}
					return l;
				});
		add("pyro", "Pyro", NamedTextColor.GOLD, Material.BLAZE_POWDER,
				"Fire Resistance. With something in your off-hand, your melee hits set enemies on fire.",
				p -> list(e(PotionEffectType.FIRE_RESISTANCE)));
		add("aquarian", "Aquarian", NamedTextColor.AQUA, Material.HEART_OF_THE_SEA,
				"Water Breathing. In water: Conduit Power and Dolphin's Grace.",
				p -> {
					List<Effect> l = list(e(PotionEffectType.WATER_BREATHING));
					if (p.isInWater()) {
						l.add(e(PotionEffectType.CONDUIT_POWER, 1, SHORT));
						l.add(e(PotionEffectType.DOLPHINS_GRACE, 1, SHORT));
					}
					return l;
				});
		add("glider", "Glider", NamedTextColor.WHITE, Material.FEATHER, "Speed I. Falls slowly while in the air (sneak to drop fast).",
				p -> {
					List<Effect> l = list(e(PotionEffectType.SPEED));
					if (inAir(p) && !p.isSneaking() && !p.isInWater() && !p.isGliding()) {
						l.add(e(PotionEffectType.SLOW_FALLING, 1, SHORT));
					}
					return l;
				});
		add("paladin", "Paladin", NamedTextColor.YELLOW, Material.GOLDEN_SWORD,
				"Resistance I. Melee hits on undead mobs deal 4 extra damage and heal you 1 heart.",
				p -> list(e(PotionEffectType.RESISTANCE)));
		add("sentinel", "Sentinel", NamedTextColor.GRAY, Material.SHIELD,
				"Resistance III while sneaking. Anyone who hits you in melee takes 2 damage back.",
				p -> p.isSneaking() ? list(e(PotionEffectType.RESISTANCE, 3, SHORT)) : list());
		add("miner", "Miner", NamedTextColor.DARK_GRAY, Material.IRON_PICKAXE, "Haste II. Night Vision while below Y 50.",
				p -> {
					List<Effect> l = list(e(PotionEffectType.HASTE, 2));
					if (p.getLocation().getY() < 50) l.add(e(PotionEffectType.NIGHT_VISION, 1, 300));
					return l;
				});
		add("tank", "Tank", NamedTextColor.BLUE, Material.IRON_CHESTPLATE, "Resistance II and Slowness I.",
				p -> list(e(PotionEffectType.RESISTANCE, 2), e(PotionEffectType.SLOWNESS)));
		add("plaguebearer", "Plaguebearer", NamedTextColor.DARK_PURPLE, Material.SPIDER_EYE,
				"Immune to Poison. Melee hits poison enemies (Poison II, 4 seconds).",
				p -> list());
		add("bard", "Bard", NamedTextColor.LIGHT_PURPLE, Material.NOTE_BLOCK,
				"Hero of the Village (better trades). Gives Regeneration I and Speed I to you and every player within 8 blocks.",
				p -> list(e(PotionEffectType.HERO_OF_THE_VILLAGE)));
		add("druid", "Druid", NamedTextColor.GREEN, Material.OAK_SAPLING,
				"Regeneration I while standing on grass, dirt, moss, logs or leaves.",
				p -> standingOnNature(p) ? list(e(PotionEffectType.REGENERATION, 1, REGEN)) : list());
		add("necromancer", "Necromancer", NamedTextColor.DARK_GRAY, Material.WITHER_SKELETON_SKULL,
				"Immune to Wither. Melee hits wither enemies (Wither I, 4 seconds). Killing a mob gives Absorption I for 30 seconds.",
				p -> list());
		add("vampire", "Vampire", NamedTextColor.DARK_RED, Material.REDSTONE,
				"Melee hits heal you half a heart. At night: Night Vision, Strength I and Speed I. In daylight under the open sky: Weakness I and Slowness I.",
				p -> {
					if (isNight(p)) return list(e(PotionEffectType.NIGHT_VISION, 1, 300), e(PotionEffectType.STRENGTH, 1, SHORT), e(PotionEffectType.SPEED, 1, SHORT));
					if (underOpenSky(p)) return list(e(PotionEffectType.WEAKNESS, 1, SHORT), e(PotionEffectType.SLOWNESS, 1, SHORT));
					return list();
				});
	}
}
