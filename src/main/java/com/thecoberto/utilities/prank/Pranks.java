package com.thecoberto.utilities.prank;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

import com.thecoberto.utilities.Tasks;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;

/** What happens to whoever pushes a Prank Button. */
public final class Pranks {
	private Pranks() {}

	record Prank(String id, String name, BiConsumer<ServerPlayerEntity, PrankButtonBlockEntity> run) {}

	static final Map<String, Prank> ALL = new LinkedHashMap<>();
	/** id -> name, for peck strengths. Damage is in half hearts. */
	static final Map<String, String> PECKS = new LinkedHashMap<>();
	private static final Map<String, Float> PECK_DAMAGE = Map.of("light", 2f, "medium", 4f, "brutal", 8f, "deadly", 16f);

	private static void add(String id, String name, BiConsumer<ServerPlayerEntity, PrankButtonBlockEntity> run) {
		ALL.put(id, new Prank(id, name, run));
	}

	static {
		add("anvils", "Anvil shower", Pranks::anvils);
		add("launch", "Launch into the sky (slow fall)", (p, b) -> launch(p, b, () ->
				p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, b.slowFallSeconds * 20, 0, false, false, true))));
		add("launch_fall", "Launch into the sky (no slow fall)", (p, b) -> launch(p, b, null));
		add("lightning", "Lightning strike", (p, b) -> {
			LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(p.getServerWorld());
			if (bolt == null) return;
			bolt.refreshPositionAfterTeleport(p.getPos());
			p.getServerWorld().spawnEntity(bolt);
		});
		add("cobweb", "Cobweb trap", (p, b) -> {
			BlockPos feet = p.getBlockPos();
			for (BlockPos pos : List.of(feet, feet.up())) {
				if (p.getServerWorld().getBlockState(pos).isAir()) p.getServerWorld().setBlockState(pos, Blocks.COBWEB.getDefaultState());
			}
			p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 100, 2, false, false, true));
		});
		add("chickens", "Killer chicken swarm", Pranks::chickenSwarm);
		add("fake_tnt", "Fake TNT (harmless)", (p, b) -> {
			sound(p, SoundEvents.ENTITY_TNT_PRIMED, 1f);
			title(p, Text.literal("!!!").formatted(Formatting.RED, Formatting.BOLD), Text.literal("RUN").formatted(Formatting.GRAY), 40);
			Tasks.later(60, () -> {
				if (p.isRemoved()) return;
				sound(p, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 1f);
				title(p, Text.literal("Just kidding").formatted(Formatting.GREEN), Text.empty(), 30);
			});
		});
		add("teleport", "Teleport somewhere nearby", Pranks::teleport);
		add("hotbar", "Scramble their hotbar", (p, b) -> {
			PlayerInventory inv = p.getInventory();
			List<ItemStack> items = new ArrayList<>();
			for (int i = 0; i < 9; i++) items.add(inv.getStack(i));
			java.util.Collections.shuffle(items, new java.util.Random(p.getRandom().nextLong()));
			for (int i = 0; i < 9; i++) inv.setStack(i, items.get(i));
			sound(p, SoundEvents.ENTITY_ITEM_PICKUP, 1f);
		});
		add("spooky", "Spooky darkness", (p, b) -> {
			p.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 160, 0, false, false, true));
			p.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 160, 0, false, false, true));
			sound(p, SoundEvents.ENTITY_GHAST_SCREAM, 1f);
		});

		PECKS.put("light", "Light (1 heart)");
		PECKS.put("medium", "Medium (2 hearts)");
		PECKS.put("brutal", "Brutal (4 hearts)");
		PECKS.put("deadly", "Deadly (8 hearts)");
	}

	static Prank pick(String id, Random random) {
		if (id.equals("random")) {
			List<Prank> list = new ArrayList<>(ALL.values());
			return list.get(random.nextInt(list.size()));
		}
		return ALL.getOrDefault(id, ALL.get("anvils"));
	}

	private static void sound(ServerPlayerEntity p, SoundEvent sound, float pitch) {
		p.playSoundToPlayer(sound, SoundCategory.MASTER, 1f, pitch);
	}

	private static void title(ServerPlayerEntity p, Text title, Text subtitle, int stay) {
		p.networkHandler.sendPacket(new TitleFadeS2CPacket(0, stay, 10));
		p.networkHandler.sendPacket(new SubtitleS2CPacket(subtitle));
		p.networkHandler.sendPacket(new TitleS2CPacket(title));
	}

	// ---------- Anvil shower ----------

	private static final int ANVIL_SECONDS = 30; // anvils from the shower disappear after this

	private static void anvils(ServerPlayerEntity player, PrankButtonBlockEntity setup) {
		// A 3x3 of anvils as high above them as the ceiling allows (up to
		// anvilHeight blocks). Anvils hurt more the farther they fall.
		ServerWorld world = player.getServerWorld();
		BlockPos base = player.getBlockPos();
		int highest = 0;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				int top = -1;
				for (int dy = 2; dy <= setup.anvilHeight; dy++) {
					if (!world.getBlockState(base.add(dx, dy, dz)).isAir()) break;
					top = dy;
				}
				if (top >= 3) {
					world.setBlockState(base.add(dx, top, dz), Blocks.ANVIL.getDefaultState());
					highest = Math.max(highest, top);
				}
			}
		}
		sound(player, SoundEvents.BLOCK_ANVIL_LAND, 1f);
		if (highest == 0) return; // a ceiling right above them: no room for anvils
		// Hold them still until the anvils reach their head (about 2 blocks up).
		int landTicks = fallTicks(highest - 2);
		freeze(player, landTicks + 10);
		if (setup.anvilsKill) squashWhenHit(player, landTicks);
		int height = highest;
		Tasks.later(ANVIL_SECONDS * 20, () -> clearAnvils(world, base, height));
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
	private static void squashWhenHit(ServerPlayerEntity player, int landTicks) {
		int[] ticks = {0};
		Tasks.every(1, () -> {
			if (player.isRemoved() || !player.isAlive()) return false;
			boolean hit = ++ticks[0] >= landTicks;
			if (!hit) {
				Box head = new Box(player.getEyePos(), player.getEyePos()).expand(1.0, 1.5, 1.0);
				hit = !player.getServerWorld().getEntitiesByClass(FallingBlockEntity.class, head, e -> true).isEmpty();
			}
			if (!hit) return true;
			player.damage(player.getDamageSources().fallingAnvil(null), 5000);
			return false;
		});
	}

	/** Removes anvils around where the shower landed, from 16 blocks below up to where they were dropped. */
	private static void clearAnvils(ServerWorld world, BlockPos base, int height) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dy = -16; dy <= height; dy++) {
					BlockPos pos = base.add(dx, dy, dz);
					if (world.isChunkLoaded(pos) && world.getBlockState(pos).isIn(BlockTags.ANVIL)) world.removeBlock(pos, false);
				}
			}
		}
	}

	/** Holds the player on the spot, so they can't walk or jump away. Lets them sink if pushed down. */
	private static void freeze(ServerPlayerEntity player, int ticks) {
		Vec3d spot = player.getPos();
		int[] left = {ticks};
		Tasks.every(1, () -> {
			if (player.isRemoved() || --left[0] < 0) return false;
			Vec3d pos = player.getPos();
			if (Math.abs(pos.x - spot.x) > 0.05 || Math.abs(pos.z - spot.z) > 0.05 || pos.y > spot.y + 0.05) {
				player.networkHandler.requestTeleport(spot.x, Math.min(pos.y, spot.y), spot.z, player.getYaw(), player.getPitch());
			}
			return true;
		});
	}

	// ---------- Launch ----------

	/** Rockets them straight up until they've climbed launchHeight blocks, then runs atTop. */
	private static void launch(ServerPlayerEntity player, PrankButtonBlockEntity setup, Runnable atTop) {
		double target = player.getY() + setup.launchHeight;
		int[] ticks = {0};
		sound(player, SoundEvents.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f);
		Tasks.every(1, () -> {
			ticks[0]++;
			if (player.isRemoved() || player.getY() >= target || ticks[0] > 20 * 30) { // give up after 30 seconds (hit a ceiling)
				if (!player.isRemoved() && atTop != null) atTop.run();
				return false;
			}
			player.setVelocity(0, 2.5, 0);
			player.velocityModified = true;
			if (ticks[0] % 10 == 0) {
				player.getServerWorld().spawnParticles(ParticleTypes.FLAME, player.getX(), player.getY(), player.getZ(), 8, 0.2, 0.2, 0.2, 0.02);
			}
			return true;
		});
	}

	// ---------- Teleport ----------

	private static void teleport(ServerPlayerEntity player, PrankButtonBlockEntity setup) {
		ServerWorld world = player.getServerWorld();
		Random random = player.getRandom();
		for (int tries = 0; tries < 10; tries++) {
			double angle = random.nextDouble() * Math.PI * 2;
			double distance = 15 + random.nextDouble() * 15;
			int x = (int) Math.floor(player.getX() + Math.cos(angle) * distance);
			int z = (int) Math.floor(player.getZ() + Math.sin(angle) * distance);
			int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
			if (y <= world.getBottomY() || !world.getFluidState(new BlockPos(x, y - 1, z)).isEmpty()) continue;
			player.teleport(world, x + 0.5, y, z + 0.5, player.getYaw(), player.getPitch());
			world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1f, 1f);
			return;
		}
	}

	// ---------- Killer chickens ----------

	static final String CHICKEN_TAG = "coberto_killer_chicken";
	private static final Set<UUID> activeChickens = new HashSet<>();

	static void registerEvents() {
		// Killer chickens left over from a restart mid-swarm vanish when they load.
		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (entity.getCommandTags().contains(CHICKEN_TAG) && !activeChickens.contains(entity.getUuid())) entity.discard();
		});
	}

	/**
	 * A ring of chickens that chase and peck the player, clucking like crazy
	 * and hopping around, then vanish in a puff of smoke.
	 */
	private static void chickenSwarm(ServerPlayerEntity player, PrankButtonBlockEntity setup) {
		ServerWorld world = player.getServerWorld();
		List<ChickenEntity> chickens = new ArrayList<>();
		for (int i = 0; i < setup.chickenCount; i++) {
			double angle = (double) i / setup.chickenCount * Math.PI * 2;
			ChickenEntity chicken = EntityType.CHICKEN.create(world);
			if (chicken == null) continue;
			chicken.refreshPositionAndAngles(player.getX() + Math.cos(angle) * 3, player.getY() + 0.5, player.getZ() + Math.sin(angle) * 3, 0, 0);
			chicken.addCommandTag(CHICKEN_TAG);
			activeChickens.add(chicken.getUuid());
			world.spawnEntity(chicken);
			chickens.add(chicken);
		}
		if (chickens.isEmpty()) return;

		sound(player, SoundEvents.ENTITY_CHICKEN_HURT, 0.5f);
		title(player, Text.literal("BAWK").formatted(Formatting.RED, Formatting.BOLD), Text.empty(), 30);

		float damage = PECK_DAMAGE.getOrDefault(setup.peck, 4f);
		Map<UUID, Integer> nextPeck = new HashMap<>();
		int[] ticks = {0};
		int end = setup.chickenSeconds * 20;
		Random random = world.getRandom();
		SoundEvent[] clucks = {SoundEvents.ENTITY_CHICKEN_AMBIENT, SoundEvents.ENTITY_CHICKEN_HURT, SoundEvents.ENTITY_CHICKEN_EGG};

		Tasks.every(1, () -> {
			ticks[0]++;
			boolean victimGone = player.isRemoved() || !player.isAlive() || player.getServerWorld() != world;
			if (ticks[0] >= end || victimGone || chickens.stream().noneMatch(ChickenEntity::isAlive)) {
				for (ChickenEntity chicken : chickens) {
					activeChickens.remove(chicken.getUuid());
					if (!chicken.isAlive()) continue;
					world.spawnParticles(ParticleTypes.EXPLOSION, chicken.getX(), chicken.getY() + 0.3, chicken.getZ(), 1, 0, 0, 0, 0);
					world.playSound(null, chicken.getBlockPos(), SoundEvents.ENTITY_CHICKEN_EGG, SoundCategory.NEUTRAL, 1f, 1f);
					chicken.discard();
				}
				return false;
			}
			for (ChickenEntity chicken : chickens) {
				if (!chicken.isAlive()) continue;
				// Chase: re-aim every few ticks so their normal wandering doesn't win.
				if (ticks[0] % 5 == 0) {
					chicken.getNavigation().startMovingTo(player, 1.5);
					// Chaos: random clucks at random pitches, and random hops.
					if (random.nextFloat() < 0.5f) {
						world.playSound(null, chicken.getBlockPos(), clucks[random.nextInt(clucks.length)], SoundCategory.NEUTRAL,
								1.2f, 0.8f + random.nextFloat() * 0.9f);
					}
					if (chicken.isOnGround() && random.nextFloat() < 0.15f) {
						chicken.addVelocity((random.nextDouble() - 0.5) * 0.4, 0.35 + random.nextDouble() * 0.25, (random.nextDouble() - 0.5) * 0.4);
						chicken.velocityModified = true;
					}
				}
				// Peck when close, at most once a second each.
				if (chicken.squaredDistanceTo(player) < 2.5 && ticks[0] >= nextPeck.getOrDefault(chicken.getUuid(), 0)) {
					nextPeck.put(chicken.getUuid(), ticks[0] + 20);
					chicken.lookAtEntity(player, 30, 30);
					player.damage(world.getDamageSources().mobAttack(chicken), damage);
				}
			}
			return true;
		});
	}
}
