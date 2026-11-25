package com.thecoberto.utilities.prank;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Entity;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** What happens to whoever pushes a Prank Button. */
final class Pranks implements Listener {
	record Prank(String id, String name, BiConsumer<Player, PrankButton.Setup> run) {
	}

	static final Map<String, Prank> ALL = new LinkedHashMap<>();
	/** id -> name, for peck strengths. Damage is in half hearts. */
	static final Map<String, String> PECKS = new LinkedHashMap<>();
	private static final Map<String, Float> PECK_DAMAGE = Map.of("light", 2f, "medium", 4f, "brutal", 8f, "deadly", 16f);
	static final String CHICKEN_TAG = "coberto_killer_chicken";
	private static final int ANVIL_SECONDS = 15; // anvils from the shower disappear after this

	private static JavaPlugin plugin;
	private static final Set<UUID> activeChickens = new HashSet<>();
	/** Anvil showers whose anvils haven't been cleared yet. */
	private static final List<AnvilArea> anvilAreas = new ArrayList<>();
	/** Cobwebs from the cobweb trap, by position. */
	private static final Set<String> cobwebs = new HashSet<>();

	private static void add(String id, String name, BiConsumer<Player, PrankButton.Setup> run) {
		ALL.put(id, new Prank(id, name, run));
	}

	static {
		add("anvils", "Anvil shower", Pranks::anvils);
		add("launch", "Launch into the sky (slow fall)", (p, b) -> launch(p, b, () ->
				p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, b.slowFallSeconds * 20, 0, false, false, true))));
		add("launch_fall", "Launch into the sky (no slow fall)", (p, b) -> launch(p, b, null));
		add("lightning", "Lightning strike", (p, b) -> p.getWorld().strikeLightning(p.getLocation()));
		add("cobweb", "Cobweb trap", (p, b) -> {
			Block feet = p.getLocation().getBlock();
			for (Block block : List.of(feet, feet.getRelative(0, 1, 0))) {
				if (block.getType().isAir()) {
					block.setType(Material.COBWEB);
					cobwebs.add(pos(block));
				}
			}
			p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 2, false, false, true));
		});
		add("chickens", "Killer chicken swarm", Pranks::chickenSwarm);
		add("fake_tnt", "Fake TNT (harmless)", (p, b) -> {
			sound(p, Sound.ENTITY_TNT_PRIMED, 1f);
			title(p, Component.text("!!!", NamedTextColor.RED, TextDecoration.BOLD), Component.text("RUN", NamedTextColor.GRAY), 40);
			later(60, () -> {
				if (!p.isOnline()) return;
				sound(p, Sound.ENTITY_GENERIC_EXPLODE, 1f);
				title(p, Component.text("Just kidding", NamedTextColor.GREEN), Component.empty(), 30);
			});
		});
		add("teleport", "Teleport somewhere nearby", (p, b) -> teleport(p));
		add("hotbar", "Scramble their hotbar", (p, b) -> {
			PlayerInventory inv = p.getInventory();
			List<ItemStack> items = new ArrayList<>();
			for (int i = 0; i < 9; i++) items.add(inv.getItem(i));
			Collections.shuffle(items);
			for (int i = 0; i < 9; i++) inv.setItem(i, items.get(i));
			sound(p, Sound.ENTITY_ITEM_PICKUP, 1f);
		});
		add("spooky", "Spooky darkness", (p, b) -> {
			p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 160, 0, false, false, true));
			p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 160, 0, false, false, true));
			sound(p, Sound.ENTITY_GHAST_SCREAM, 1f);
		});

		PECKS.put("light", "Light (1 heart)");
		PECKS.put("medium", "Medium (2 hearts)");
		PECKS.put("brutal", "Brutal (4 hearts)");
		PECKS.put("deadly", "Deadly (8 hearts)");
	}

	Pranks(JavaPlugin plugin) {
		Pranks.plugin = plugin;
		Bukkit.getPluginManager().registerEvents(this, plugin);
	}

	static Prank pick(String id) {
		if (id.equals("random")) {
			List<Prank> list = new ArrayList<>(ALL.values());
			return list.get(ThreadLocalRandom.current().nextInt(list.size()));
		}
		return ALL.getOrDefault(id, ALL.get("anvils"));
	}

	private static void later(int ticks, Runnable run) {
		Bukkit.getScheduler().runTaskLater(plugin, run, Math.max(1, ticks));
	}

	/** Runs every tick until it returns false. */
	private static void everyTick(BooleanSupplier run) {
		new BukkitRunnable() {
			@Override
			public void run() {
				boolean again;
				try {
					again = run.getAsBoolean();
				} catch (RuntimeException e) {
					again = false;
				}
				if (!again) cancel();
			}
		}.runTaskTimer(plugin, 1, 1);
	}

	private static void sound(Player p, Sound sound, float pitch) {
		p.playSound(p, sound, SoundCategory.MASTER, 1f, pitch);
	}

	private static void title(Player p, Component title, Component subtitle, int stayTicks) {
		p.showTitle(Title.title(title, subtitle, Title.Times.times(Duration.ZERO, Duration.ofMillis(stayTicks * 50L), Duration.ofMillis(500))));
	}

	// ---------- Anvil shower ----------

	private static void anvils(Player player, PrankButton.Setup setup) {
		// A 3x3 of anvils as high above them as the ceiling allows (up to
		// anvilHeight blocks). Anvils hurt more the farther they fall.
		Block base = player.getLocation().getBlock();
		int highest = 0;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				int top = -1;
				for (int dy = 2; dy <= setup.anvilHeight; dy++) {
					if (!base.getRelative(dx, dy, dz).getType().isAir()) break;
					top = dy;
				}
				if (top >= 3) {
					// Placed with physics, so it starts falling straight away.
					base.getRelative(dx, top, dz).setType(Material.ANVIL, true);
					highest = Math.max(highest, top);
				}
			}
		}
		sound(player, Sound.BLOCK_ANVIL_LAND, 1f);
		if (highest == 0) return; // a ceiling right above them: no room for anvils
		// Hold them still until the anvils reach their head (about 2 blocks up).
		int landTicks = fallTicks(highest - 2);
		freeze(player, landTicks + 10);
		if (setup.anvilsKill) squashWhenHit(player, landTicks);
		int height = highest;
		AnvilArea area = new AnvilArea(base.getWorld().getUID(), base.getX() - 1, base.getY() - 16, base.getZ() - 1,
				base.getX() + 1, base.getY() + height, base.getZ() + 1);
		anvilAreas.add(area);
		later(ANVIL_SECONDS * 20, () -> {
			clearAnvils(base, height);
			anvilAreas.remove(area);
		});
	}

	/** Ticks a falling block takes to fall this many blocks (speeds up 0.04 a tick, 2% drag). */
	private static int fallTicks(int blocks) {
		double fallen = 0, speed = 0;
		int ticks = 0;
		while (fallen < blocks && ticks < 2000) {
			speed = (speed + 0.04) * 0.98;
			fallen += speed;
			ticks++;
		}
		return ticks;
	}

	/**
	 * "Anvils always kill": when an anvil reaches their head, or when it should
	 * have by now, deal anvil damage big enough to get through full netherite
	 * with Protection IV.
	 */
	private static void squashWhenHit(Player player, int landTicks) {
		int[] ticks = {0};
		everyTick(() -> {
			if (!player.isOnline() || player.isDead()) return false;
			boolean hit = ++ticks[0] >= landTicks;
			if (!hit) {
				Location eye = player.getEyeLocation();
				hit = !eye.getWorld().getNearbyEntitiesByType(FallingBlock.class, eye, 1.0, 1.5, 1.0).isEmpty();
			}
			if (!hit) return true;
			player.damage(5000, DamageSource.builder(DamageType.FALLING_ANVIL).build());
			return false;
		});
	}

	/** Removes anvils around where the shower landed, from 16 blocks below up to where they were dropped. */
	private static void clearAnvils(Block base, int height) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dy = -16; dy <= height; dy++) {
					Block block = base.getRelative(dx, dy, dz);
					if (block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4) && Tag.ANVIL.isTagged(block.getType())) {
						block.setType(Material.AIR, false);
					}
				}
			}
		}
	}

	/** Holds the player on the spot, so they can't walk or jump away. Lets them sink if pushed down. */
	private static void freeze(Player player, int ticks) {
		Location spot = player.getLocation();
		int[] left = {ticks};
		everyTick(() -> {
			if (!player.isOnline() || --left[0] < 0) return false;
			Location pos = player.getLocation();
			if (Math.abs(pos.getX() - spot.getX()) > 0.05 || Math.abs(pos.getZ() - spot.getZ()) > 0.05 || pos.getY() > spot.getY() + 0.05) {
				Location back = spot.clone();
				back.setY(Math.min(pos.getY(), spot.getY()));
				back.setYaw(pos.getYaw());
				back.setPitch(pos.getPitch());
				player.teleport(back);
			}
			return true;
		});
	}

	// ---------- Launch ----------

	/** Rockets them straight up until they've climbed launchHeight blocks, then runs atTop. */
	private static void launch(Player player, PrankButton.Setup setup, Runnable atTop) {
		double target = player.getLocation().getY() + setup.launchHeight;
		int[] ticks = {0};
		sound(player, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f);
		everyTick(() -> {
			ticks[0]++;
			if (!player.isOnline() || player.getLocation().getY() >= target || ticks[0] > 20 * 30) { // give up after 30 seconds (hit a ceiling)
				if (player.isOnline() && atTop != null) atTop.run();
				return false;
			}
			player.setVelocity(new Vector(0, 2.5, 0));
			if (ticks[0] % 10 == 0) player.getWorld().spawnParticle(Particle.FLAME, player.getLocation(), 8, 0.2, 0.2, 0.2, 0.02);
			return true;
		});
	}

	// ---------- Teleport ----------

	private static void teleport(Player player) {
		World world = player.getWorld();
		ThreadLocalRandom random = ThreadLocalRandom.current();
		for (int tries = 0; tries < 10; tries++) {
			double angle = random.nextDouble() * Math.PI * 2;
			double distance = 15 + random.nextDouble() * 15;
			int x = (int) Math.floor(player.getLocation().getX() + Math.cos(angle) * distance);
			int z = (int) Math.floor(player.getLocation().getZ() + Math.sin(angle) * distance);
			Block top = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING);
			if (top.getY() <= world.getMinHeight() || top.isLiquid()) continue;
			Location to = new Location(world, x + 0.5, top.getY() + 1, z + 0.5, player.getLocation().getYaw(), player.getLocation().getPitch());
			player.teleport(to);
			world.playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1f, 1f);
			return;
		}
	}

	// ---------- Nothing to farm ----------
	// Prank leftovers (chickens, anvils, cobwebs) drop nothing, so a Prank Button can't be farmed.

	/** Where an anvil shower's anvils can be, until they're cleared. */
	private record AnvilArea(UUID world, int x1, int y1, int z1, int x2, int y2, int z2) {
		boolean contains(Block b) {
			return b.getWorld().getUID().equals(world) && b.getX() >= x1 && b.getX() <= x2
					&& b.getY() >= y1 && b.getY() <= y2 && b.getZ() >= z1 && b.getZ() <= z2;
		}
	}

	private static String pos(Block b) {
		return b.getWorld().getUID() + "," + b.getX() + "," + b.getY() + "," + b.getZ();
	}

	private static boolean inAnvilShower(Block b) {
		return anvilAreas.stream().anyMatch(a -> a.contains(b));
	}

	@EventHandler(ignoreCancelled = true)
	public void onPrankBlockBreak(BlockBreakEvent event) {
		Block b = event.getBlock();
		boolean prankAnvil = Tag.ANVIL.isTagged(b.getType()) && inAnvilShower(b);
		if (prankAnvil || (b.getType() == Material.COBWEB && cobwebs.remove(pos(b)))) {
			event.setDropItems(false);
			event.setExpToDrop(0);
		}
	}

	/** Eggs from killer chickens, and prank anvils that break or can't land and would drop as items. */
	@EventHandler(ignoreCancelled = true)
	public void onDropItem(EntityDropItemEvent event) {
		Entity e = event.getEntity();
		if (e.getScoreboardTags().contains(CHICKEN_TAG) || (e instanceof FallingBlock && inAnvilShower(e.getLocation().getBlock()))) {
			event.setCancelled(true);
		}
	}

	@EventHandler
	public void onChickenDeath(EntityDeathEvent event) {
		if (!event.getEntity().getScoreboardTags().contains(CHICKEN_TAG)) return;
		event.getDrops().clear();
		event.setDroppedExp(0);
	}

	@EventHandler(ignoreCancelled = true)
	public void onChickenBreed(EntityBreedEvent event) {
		if (event.getMother().getScoreboardTags().contains(CHICKEN_TAG) || event.getFather().getScoreboardTags().contains(CHICKEN_TAG)) {
			event.setCancelled(true);
		}
	}

	// ---------- Killer chickens ----------

	/** Killer chickens left over from a restart mid-swarm vanish when they load. */
	@EventHandler
	public void onEntitiesLoad(EntitiesLoadEvent event) {
		event.getEntities().stream()
				.filter(e -> e.getScoreboardTags().contains(CHICKEN_TAG) && !activeChickens.contains(e.getUniqueId()))
				.forEach(org.bukkit.entity.Entity::remove);
	}

	/**
	 * A ring of chickens that chase and peck the player, clucking like crazy
	 * and hopping around, then vanish in a puff of smoke.
	 */
	private static void chickenSwarm(Player player, PrankButton.Setup setup) {
		World world = player.getWorld();
		List<Chicken> chickens = new ArrayList<>();
		for (int i = 0; i < setup.chickenCount; i++) {
			double angle = (double) i / setup.chickenCount * Math.PI * 2;
			Location at = player.getLocation().add(Math.cos(angle) * 3, 0.5, Math.sin(angle) * 3);
			Chicken chicken = world.spawn(at, Chicken.class, c -> c.addScoreboardTag(CHICKEN_TAG));
			activeChickens.add(chicken.getUniqueId());
			chickens.add(chicken);
		}
		if (chickens.isEmpty()) return;

		sound(player, Sound.ENTITY_CHICKEN_HURT, 0.5f);
		title(player, Component.text("BAWK", NamedTextColor.RED, TextDecoration.BOLD), Component.empty(), 30);

		float damage = PECK_DAMAGE.getOrDefault(setup.peck, 4f);
		Map<UUID, Integer> nextPeck = new HashMap<>();
		int[] ticks = {0};
		int end = setup.chickenSeconds * 20;
		ThreadLocalRandom random = ThreadLocalRandom.current();
		Sound[] clucks = {Sound.ENTITY_CHICKEN_AMBIENT, Sound.ENTITY_CHICKEN_HURT, Sound.ENTITY_CHICKEN_EGG};

		everyTick(() -> {
			ticks[0]++;
			boolean victimGone = !player.isOnline() || player.isDead() || player.getWorld() != world;
			if (ticks[0] >= end || victimGone || chickens.stream().noneMatch(Chicken::isValid)) {
				for (Chicken chicken : chickens) {
					activeChickens.remove(chicken.getUniqueId());
					if (!chicken.isValid()) continue;
					world.spawnParticle(Particle.EXPLOSION, chicken.getLocation().add(0, 0.3, 0), 1);
					world.playSound(chicken.getLocation(), Sound.ENTITY_CHICKEN_EGG, SoundCategory.NEUTRAL, 1f, 1f);
					chicken.remove();
				}
				return false;
			}
			for (Chicken chicken : chickens) {
				if (!chicken.isValid()) continue;
				// Chase: re-aim every few ticks so their normal wandering doesn't win.
				if (ticks[0] % 5 == 0) {
					chicken.getPathfinder().moveTo(player, 1.5);
					// Chaos: random clucks at random pitches, and random hops.
					if (random.nextFloat() < 0.5f) {
						world.playSound(chicken.getLocation(), clucks[random.nextInt(clucks.length)], SoundCategory.NEUTRAL,
								1.2f, 0.8f + random.nextFloat() * 0.9f);
					}
					if (chicken.isOnGround() && random.nextFloat() < 0.15f) {
						chicken.setVelocity(chicken.getVelocity().add(new Vector((random.nextDouble() - 0.5) * 0.4,
								0.35 + random.nextDouble() * 0.25, (random.nextDouble() - 0.5) * 0.4)));
					}
				}
				// Peck when close, at most once a second each.
				if (chicken.getLocation().distanceSquared(player.getLocation()) < 2.5
						&& ticks[0] >= nextPeck.getOrDefault(chicken.getUniqueId(), 0)) {
					nextPeck.put(chicken.getUniqueId(), ticks[0] + 20);
					chicken.lookAt(player);
					player.damage(damage, DamageSource.builder(DamageType.MOB_ATTACK).withCausingEntity(chicken).withDirectEntity(chicken).build());
				}
			}
			return true;
		});
	}
}
