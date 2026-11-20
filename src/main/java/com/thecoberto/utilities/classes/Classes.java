package com.thecoberto.utilities.classes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.registry.tag.EntityTypeTags;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Classes: players get one of 15 classes, each with lasting effects and
 * sometimes an ability. One class at a time; picking another replaces it. The
 * class is saved with the world and kept through death. Operators pick their
 * own from a menu with /class, and set anyone's with /setclass (works from
 * command blocks and signs too).
 */
public final class Classes {
	private Classes() {}

	private static int ticks;

	public static PlayerClass classOf(ServerPlayerEntity player) {
		return ClassesState.get(player.server).of(player.getUuid());
	}

	static void setClass(ServerPlayerEntity player, PlayerClass playerClass) {
		for (var effect : PlayerClass.ALL_CLASS_EFFECTS) player.removeStatusEffect(effect);
		ClassesState.get(player.server).set(player.getUuid(), playerClass);
		if (playerClass != null) {
			player.sendMessage(prefix().append("You are now a ")
					.append(Text.literal(playerClass.name()).formatted(playerClass.color()))
					.append(". " + playerClass.about()));
			player.playSoundToPlayer(SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 1.0f, 1.0f);
		} else {
			player.sendMessage(prefix().append("You no longer have a class."));
		}
		applyEffects(player);
	}

	// ---------- Keeping effects on ----------

	private static void give(LivingEntity entity, PlayerClass.Effect effect) {
		StatusEffectInstance current = entity.getStatusEffect(effect.type());
		// Leave stronger effects (from potions or beacons) alone, and don't top up too often.
		if (current != null && (current.getAmplifier() > effect.amplifier()
				|| (current.getAmplifier() == effect.amplifier()
						&& (current.isInfinite() || current.getDuration() > effect.duration() / 2)))) {
			return;
		}
		entity.addStatusEffect(new StatusEffectInstance(effect.type(), effect.duration(), effect.amplifier(), false, false, true));
	}

	private static void applyEffects(ServerPlayerEntity player) {
		PlayerClass c = classOf(player);
		if (c == null || !player.isAlive()) return;
		for (PlayerClass.Effect effect : c.effects().apply(player)) give(player, effect);
		switch (c.id()) {
			case "plaguebearer" -> player.removeStatusEffect(StatusEffects.POISON);
			case "necromancer" -> player.removeStatusEffect(StatusEffects.WITHER);
			case "bard" -> {
				for (ServerPlayerEntity ally : player.getServerWorld().getPlayers(p -> p.squaredDistanceTo(player) <= 64)) {
					give(ally, PlayerClass.e(StatusEffects.REGENERATION, 1, PlayerClass.REGEN));
					give(ally, PlayerClass.e(StatusEffects.SPEED, 1, PlayerClass.SHORT));
				}
			}
			default -> {}
		}
	}

	public static void registerEvents() {
		ClassMenu.registerEvents();
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (++ticks % 10 != 0) return;
			for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) applyEffects(player);
		});

		// Acrobats take no fall damage.
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
				!(entity instanceof ServerPlayerEntity player && source.isIn(DamageTypeTags.IS_FALL)
						&& classOf(player) != null && classOf(player).id().equals("acrobat")));

		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damage, blocked) -> {
			if (blocked || !entity.isAlive()) return;
			onMelee(entity, source);
			onSentinelHit(entity, source);
		});

		// Necromancers draw strength from kills.
		ServerLivingEntityEvents.AFTER_DEATH.register((dead, source) -> {
			if (source.getAttacker() instanceof ServerPlayerEntity killer && isClass(killer, "necromancer")) {
				killer.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 600, 0, false, false, true));
			}
		});
	}

	private static boolean isClass(ServerPlayerEntity player, String id) {
		PlayerClass c = classOf(player);
		return c != null && c.id().equals(id);
	}

	/** Melee hits by a classed player. */
	private static void onMelee(LivingEntity target, DamageSource source) {
		if (!source.isOf(DamageTypes.PLAYER_ATTACK) || !(source.getAttacker() instanceof ServerPlayerEntity attacker)) return;
		PlayerClass c = classOf(attacker);
		if (c == null) return;
		switch (c.id()) {
			case "pyro" -> {
				if (!attacker.getOffHandStack().isEmpty()) target.setOnFireFor(4);
			}
			case "paladin" -> {
				if (target.getType().isIn(EntityTypeTags.UNDEAD)) {
					target.timeUntilRegen = 0; // let the extra damage through right after the hit
					target.damage(attacker.getDamageSources().magic(), 4);
					attacker.heal(2);
				}
			}
			case "plaguebearer" -> target.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, 80, 1), attacker);
			case "necromancer" -> target.addStatusEffect(new StatusEffectInstance(StatusEffects.WITHER, 80, 0), attacker);
			case "vampire" -> attacker.heal(1);
			default -> {}
		}
	}

	/** Sentinels hurt whoever hits them in melee. */
	private static void onSentinelHit(LivingEntity target, DamageSource source) {
		if (!(target instanceof ServerPlayerEntity sentinel) || !isClass(sentinel, "sentinel")) return;
		if (!(source.isOf(DamageTypes.PLAYER_ATTACK) || source.isOf(DamageTypes.MOB_ATTACK) || source.isOf(DamageTypes.MOB_ATTACK_NO_AGGRO))) return;
		Entity attacker = source.getAttacker();
		if (attacker == null || attacker == sentinel || !attacker.isAlive()) return;
		attacker.damage(sentinel.getDamageSources().thorns(sentinel), 2);
	}

	// ---------- Commands ----------

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("class")
				.requires(s -> s.hasPermissionLevel(2))
				.executes(c -> {
					ServerPlayerEntity player = c.getSource().getPlayer();
					if (player == null) {
						c.getSource().sendError(Text.literal("Only players can choose a class. Use /setclass for others."));
						return 0;
					}
					ClassMenu.open(player);
					return 1;
				}));

		List<String> names = new ArrayList<>(PlayerClass.ALL.keySet());
		names.add("none");
		dispatcher.register(CommandManager.literal("setclass")
				.requires(s -> s.hasPermissionLevel(2))
				.then(CommandManager.argument("players", EntityArgumentType.players())
						.then(CommandManager.argument("class", StringArgumentType.word())
								.suggests((c, b) -> CommandSource.suggestMatching(names, b))
								.executes(c -> {
									String name = StringArgumentType.getString(c, "class").toLowerCase(java.util.Locale.ROOT);
									if (!names.contains(name)) {
										c.getSource().sendError(Text.literal("No class called " + name + ". Choose one of: " + String.join(", ", names)));
										return 0;
									}
									PlayerClass target = PlayerClass.ALL.get(name);
									Collection<ServerPlayerEntity> players = EntityArgumentType.getPlayers(c, "players");
									for (ServerPlayerEntity player : players) {
										if (classOf(player) != target) setClass(player, target);
									}
									String who = players.stream().map(p -> p.getName().getString()).collect(Collectors.joining(", "));
									c.getSource().sendFeedback(() -> Text.literal("Set " + who + " to "
											+ (target == null ? "no class" : target.name()) + "."), true);
									return players.size();
								}))));
	}

	static MutableText prefix() {
		return Text.empty().append(Text.literal("Classes:").formatted(Formatting.GOLD)).append(" ");
	}
}
