package com.thecoberto.utilities.prank;

import net.minecraft.world.entity.EntitySpawnReason;
import com.thecoberto.utilities.Sounds;

import net.minecraft.world.entity.EntityTypes;

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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.BlockTags;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;

/** What happens to whoever pushes a Prank Button. */
public final class Pranks {
	/** A normal adult chicken's sounds (26.x gave chickens sound variants). */
	private static final net.minecraft.world.entity.animal.chicken.ChickenSoundVariant.ChickenSoundSet CHICKEN =
			net.minecraft.sounds.SoundEvents.CHICKEN_SOUNDS.get(net.minecraft.world.entity.animal.chicken.ChickenSoundVariants.SoundSet.CLASSIC).adultSounds();

	private Pranks() {}

	record Prank(String id, String name, BiConsumer<ServerPlayer, PrankButtonBlockEntity> run) {}

	static final Map<String, Prank> ALL = new LinkedHashMap<>();
	/** id -> name, for peck strengths. Damage is in half hearts. */
	static final Map<String, String> PECKS = new LinkedHashMap<>();
	private static final Map<String, Float> PECK_DAMAGE = Map.of("light", 2f, "medium", 4f, "brutal", 8f, "deadly", 16f);

	private static void add(String id, String name, BiConsumer<ServerPlayer, PrankButtonBlockEntity> run) {
		ALL.put(id, new Prank(id, name, run));
	}

	static {
		add("anvils", "Anvil shower", Pranks::anvils);
		add("launch", "Launch into the sky (slow fall)", (p, b) -> launch(p, b, () ->
				p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, b.slowFallSeconds * 20, 0, false, false, true))));
		add("launch_fall", "Launch into the sky (no slow fall)", (p, b) -> launch(p, b, null));
		add("lightning", "Lightning strike", (p, b) -> {
			LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(p.level(), EntitySpawnReason.TRIGGERED);
			if (bolt == null) return;
			bolt.snapTo(p.position());
			p.level().addFreshEntity(bolt);
		});
		add("cobweb", "Cobweb trap", (p, b) -> {
			BlockPos feet = p.blockPosition();
			for (BlockPos pos : List.of(feet, feet.above())) {
				if (p.level().getBlockState(pos).isAir()) p.level().setBlockAndUpdate(pos, Blocks.COBWEB.defaultBlockState());
			}
			p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 100, 2, false, false, true));
		});
		add("chickens", "Killer chicken swarm", Pranks::chickenSwarm);
		add("fake_tnt", "Fake TNT (harmless)", (p, b) -> {
			sound(p, SoundEvents.TNT_PRIMED, 1f);
			title(p, Component.literal("!!!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), Component.literal("RUN").withStyle(ChatFormatting.GRAY), 40);
			Tasks.later(60, () -> {
				if (p.isRemoved()) return;
				sound(p, SoundEvents.GENERIC_EXPLODE.value(), 1f);
				title(p, Component.literal("Just kidding").withStyle(ChatFormatting.GREEN), Component.empty(), 30);
			});
		});
		add("teleport", "Teleport somewhere nearby", Pranks::teleport);
		add("hotbar", "Scramble their hotbar", (p, b) -> {
			Inventory inv = p.getInventory();
			List<ItemStack> items = new ArrayList<>();
			for (int i = 0; i < 9; i++) items.add(inv.getItem(i));
			java.util.Collections.shuffle(items, new java.util.Random(p.getRandom().nextLong()));
			for (int i = 0; i < 9; i++) inv.setItem(i, items.get(i));
			sound(p, SoundEvents.ITEM_PICKUP, 1f);
		});
		add("spooky", "Spooky darkness", (p, b) -> {
			p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 160, 0, false, false, true));
			p.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 160, 0, false, false, true));
			sound(p, SoundEvents.GHAST_SCREAM, 1f);
		});

		PECKS.put("light", "Light (1 heart)");
		PECKS.put("medium", "Medium (2 hearts)");
		PECKS.put("brutal", "Brutal (4 hearts)");
		PECKS.put("deadly", "Deadly (8 hearts)");
	}

	static Prank pick(String id, RandomSource random) {
		if (id.equals("random")) {
			List<Prank> list = new ArrayList<>(ALL.values());
			return list.get(random.nextInt(list.size()));
		}
		return ALL.getOrDefault(id, ALL.get("anvils"));
	}

	private static void sound(ServerPlayer p, SoundEvent sound, float pitch) {
		Sounds.play(p, sound, SoundSource.MASTER, 1f, pitch);
	}

	private static void title(ServerPlayer p, Component title, Component subtitle, int stay) {
		p.connection.send(new ClientboundSetTitlesAnimationPacket(0, stay, 10));
		p.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
		p.connection.send(new ClientboundSetTitleTextPacket(title));
	}

	// ---------- Anvil shower ----------

	private static final int ANVIL_SECONDS = 30; // anvils from the shower disappear after this

	private static void anvils(ServerPlayer player, PrankButtonBlockEntity setup) {
		// A 3x3 of anvils as high above them as the ceiling allows (up to
		// anvilHeight blocks). Anvils hurt more the farther they fall.
		ServerLevel world = player.level();
		BlockPos base = player.blockPosition();
		int highest = 0;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				int top = -1;
				for (int dy = 2; dy <= setup.anvilHeight; dy++) {
					if (!world.getBlockState(base.offset(dx, dy, dz)).isAir()) break;
					top = dy;
				}
				if (top >= 3) {
					world.setBlockAndUpdate(base.offset(dx, top, dz), Blocks.ANVIL.defaultBlockState());
					highest = Math.max(highest, top);
				}
			}
		}
		sound(player, SoundEvents.ANVIL_LAND, 1f);
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
	private static void squashWhenHit(ServerPlayer player, int landTicks) {
		int[] ticks = {0};
		Tasks.every(1, () -> {
			if (player.isRemoved() || !player.isAlive()) return false;
			boolean hit = ++ticks[0] >= landTicks;
			if (!hit) {
				AABB head = new AABB(player.getEyePosition(), player.getEyePosition()).inflate(1.0, 1.5, 1.0);
				hit = !player.level().getEntitiesOfClass(FallingBlockEntity.class, head, e -> true).isEmpty();
			}
			if (!hit) return true;
			player.hurtServer((ServerLevel) player.level(), player.damageSources().anvil(null), 5000);
			return false;
		});
	}

	/** Removes anvils around where the shower landed, from 16 blocks below up to where they were dropped. */
	private static void clearAnvils(ServerLevel world, BlockPos base, int height) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dy = -16; dy <= height; dy++) {
					BlockPos pos = base.offset(dx, dy, dz);
					if (world.isLoaded(pos) && world.getBlockState(pos).is(BlockTags.ANVIL)) world.removeBlock(pos, false);
				}
			}
		}
	}

	/** Holds the player on the spot, so they can't walk or jump away. Lets them sink if pushed down. */
	private static void freeze(ServerPlayer player, int ticks) {
		Vec3 spot = player.position();
		int[] left = {ticks};
		Tasks.every(1, () -> {
			if (player.isRemoved() || --left[0] < 0) return false;
			Vec3 pos = player.position();
			if (Math.abs(pos.x - spot.x) > 0.05 || Math.abs(pos.z - spot.z) > 0.05 || pos.y > spot.y + 0.05) {
				player.connection.teleport(spot.x, Math.min(pos.y, spot.y), spot.z, player.getYRot(), player.getXRot());
			}
			return true;
		});
	}

	// ---------- Launch ----------

	/** Rockets them straight up until they've climbed launchHeight blocks, then runs atTop. */
	private static void launch(ServerPlayer player, PrankButtonBlockEntity setup, Runnable atTop) {
		double target = player.getY() + setup.launchHeight;
		int[] ticks = {0};
		sound(player, SoundEvents.FIREWORK_ROCKET_LAUNCH, 1f);
		Tasks.every(1, () -> {
			ticks[0]++;
			if (player.isRemoved() || player.getY() >= target || ticks[0] > 20 * 30) { // give up after 30 seconds (hit a ceiling)
				if (!player.isRemoved() && atTop != null) atTop.run();
				return false;
			}
			player.setDeltaMovement(0, 2.5, 0);
			player.hurtMarked = true;
			if (ticks[0] % 10 == 0) {
				player.level().sendParticles(ParticleTypes.FLAME, player.getX(), player.getY(), player.getZ(), 8, 0.2, 0.2, 0.2, 0.02);
			}
			return true;
		});
	}

	// ---------- Teleport ----------

	private static void teleport(ServerPlayer player, PrankButtonBlockEntity setup) {
		ServerLevel world = player.level();
		RandomSource random = player.getRandom();
		for (int tries = 0; tries < 10; tries++) {
			double angle = random.nextDouble() * Math.PI * 2;
			double distance = 15 + random.nextDouble() * 15;
			int x = (int) Math.floor(player.getX() + Math.cos(angle) * distance);
			int z = (int) Math.floor(player.getZ() + Math.sin(angle) * distance);
			int y = world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
			if (y <= world.getMinY() || !world.getFluidState(new BlockPos(x, y - 1, z)).isEmpty()) continue;
			player.teleportTo(world, x + 0.5, y, z + 0.5, java.util.Set.of(), player.getYRot(), player.getXRot(), false);
			world.playSound(null, player.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1f, 1f);
			return;
		}
	}

	// ---------- Killer chickens ----------

	static final String CHICKEN_TAG = "coberto_killer_chicken";
	private static final Set<UUID> activeChickens = new HashSet<>();

	static void registerEvents() {
		// Killer chickens left over from a restart mid-swarm vanish when they load.
		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (entity.entityTags().contains(CHICKEN_TAG) && !activeChickens.contains(entity.getUUID())) entity.discard();
		});
	}

	/**
	 * A ring of chickens that chase and peck the player, clucking like crazy
	 * and hopping around, then vanish in a puff of smoke.
	 */
	private static void chickenSwarm(ServerPlayer player, PrankButtonBlockEntity setup) {
		ServerLevel world = player.level();
		List<Chicken> chickens = new ArrayList<>();
		for (int i = 0; i < setup.chickenCount; i++) {
			double angle = (double) i / setup.chickenCount * Math.PI * 2;
			Chicken chicken = EntityTypes.CHICKEN.create(world, EntitySpawnReason.TRIGGERED);
			if (chicken == null) continue;
			chicken.snapTo(player.getX() + Math.cos(angle) * 3, player.getY() + 0.5, player.getZ() + Math.sin(angle) * 3, 0, 0);
			chicken.addTag(CHICKEN_TAG);
			activeChickens.add(chicken.getUUID());
			world.addFreshEntity(chicken);
			chickens.add(chicken);
		}
		if (chickens.isEmpty()) return;

		sound(player, CHICKEN.hurtSound().value(), 0.5f);
		title(player, Component.literal("BAWK").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), Component.empty(), 30);

		float damage = PECK_DAMAGE.getOrDefault(setup.peck, 4f);
		Map<UUID, Integer> nextPeck = new HashMap<>();
		int[] ticks = {0};
		int end = setup.chickenSeconds * 20;
		RandomSource random = world.getRandom();
		SoundEvent[] clucks = {CHICKEN.ambientSound().value(), CHICKEN.hurtSound().value(), SoundEvents.CHICKEN_EGG};

		Tasks.every(1, () -> {
			ticks[0]++;
			boolean victimGone = player.isRemoved() || !player.isAlive() || player.level() != world;
			if (ticks[0] >= end || victimGone || chickens.stream().noneMatch(Chicken::isAlive)) {
				for (Chicken chicken : chickens) {
					activeChickens.remove(chicken.getUUID());
					if (!chicken.isAlive()) continue;
					world.sendParticles(ParticleTypes.EXPLOSION, chicken.getX(), chicken.getY() + 0.3, chicken.getZ(), 1, 0, 0, 0, 0);
					world.playSound(null, chicken.blockPosition(), SoundEvents.CHICKEN_EGG, SoundSource.NEUTRAL, 1f, 1f);
					chicken.discard();
				}
				return false;
			}
			for (Chicken chicken : chickens) {
				if (!chicken.isAlive()) continue;
				// Chase: re-aim every few ticks so their normal wandering doesn't win.
				if (ticks[0] % 5 == 0) {
					chicken.getNavigation().moveTo(player, 1.5);
					// Chaos: random clucks at random pitches, and random hops.
					if (random.nextFloat() < 0.5f) {
						world.playSound(null, chicken.blockPosition(), clucks[random.nextInt(clucks.length)], SoundSource.NEUTRAL,
								1.2f, 0.8f + random.nextFloat() * 0.9f);
					}
					if (chicken.onGround() && random.nextFloat() < 0.15f) {
						chicken.push((random.nextDouble() - 0.5) * 0.4, 0.35 + random.nextDouble() * 0.25, (random.nextDouble() - 0.5) * 0.4);
						chicken.hurtMarked = true;
					}
				}
				// Peck when close, at most once a second each.
				if (chicken.distanceToSqr(player) < 2.5 && ticks[0] >= nextPeck.getOrDefault(chicken.getUUID(), 0)) {
					nextPeck.put(chicken.getUUID(), ticks[0] + 20);
					chicken.lookAt(player, 30, 30);
					player.hurtServer((ServerLevel) player.level(), world.damageSources().mobAttack(chicken), damage);
				}
			}
			return true;
		});
	}
}
