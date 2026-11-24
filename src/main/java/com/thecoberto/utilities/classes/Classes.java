package com.thecoberto.utilities.classes;

import com.thecoberto.utilities.Sounds;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

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

	public static PlayerClass classOf(ServerPlayer player) {
		return ClassesState.get(player.level().getServer()).of(player.getUUID());
	}

	static void setClass(ServerPlayer player, PlayerClass playerClass) {
		for (var effect : PlayerClass.ALL_CLASS_EFFECTS) player.removeEffect(effect);
		ClassesState.get(player.level().getServer()).set(player.getUUID(), playerClass);
		if (playerClass != null) {
			player.sendSystemMessage(prefix().append("You are now a ")
					.append(Component.literal(playerClass.name()).withStyle(playerClass.color()))
					.append(". " + playerClass.about()));
			Sounds.play(player, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0f, 1.0f);
		} else {
			player.sendSystemMessage(prefix().append("You no longer have a class."));
		}
		applyEffects(player);
	}

	// ---------- Keeping effects on ----------

	private static void give(LivingEntity entity, PlayerClass.Effect effect) {
		MobEffectInstance current = entity.getEffect(effect.type());
		// Leave stronger effects (from potions or beacons) alone, and don't top up too often.
		if (current != null && (current.getAmplifier() > effect.amplifier()
				|| (current.getAmplifier() == effect.amplifier()
						&& (current.isInfiniteDuration() || current.getDuration() > effect.duration() / 2)))) {
			return;
		}
		entity.addEffect(new MobEffectInstance(effect.type(), effect.duration(), effect.amplifier(), false, false, true));
	}

	private static void applyEffects(ServerPlayer player) {
		PlayerClass c = classOf(player);
		if (c == null || !player.isAlive()) return;
		for (PlayerClass.Effect effect : c.effects().apply(player)) give(player, effect);
		switch (c.id()) {
			case "plaguebearer" -> player.removeEffect(MobEffects.POISON);
			case "necromancer" -> player.removeEffect(MobEffects.WITHER);
			case "bard" -> {
				for (ServerPlayer ally : player.level().getPlayers(p -> p.distanceToSqr(player) <= 64)) {
					give(ally, PlayerClass.e(MobEffects.REGENERATION, 1, PlayerClass.REGEN));
					give(ally, PlayerClass.e(MobEffects.SPEED, 1, PlayerClass.SHORT));
				}
			}
			default -> {}
		}
	}

	public static void registerEvents() {
		ClassMenu.registerEvents();
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (++ticks % 10 != 0) return;
			for (ServerPlayer player : server.getPlayerList().getPlayers()) applyEffects(player);
		});

		// Acrobats take no fall damage.
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
				!(entity instanceof ServerPlayer player && source.is(DamageTypeTags.IS_FALL)
						&& classOf(player) != null && classOf(player).id().equals("acrobat")));

		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damage, blocked) -> {
			if (blocked || !entity.isAlive()) return;
			onMelee(entity, source);
			onSentinelHit(entity, source);
		});

		// Necromancers draw strength from kills.
		ServerLivingEntityEvents.AFTER_DEATH.register((dead, source) -> {
			if (source.getEntity() instanceof ServerPlayer killer && isClass(killer, "necromancer")) {
				killer.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 600, 0, false, false, true));
			}
		});
	}

	private static boolean isClass(ServerPlayer player, String id) {
		PlayerClass c = classOf(player);
		return c != null && c.id().equals(id);
	}

	/** Melee hits by a classed player. */
	private static void onMelee(LivingEntity target, DamageSource source) {
		if (!source.is(DamageTypes.PLAYER_ATTACK) || !(source.getEntity() instanceof ServerPlayer attacker)) return;
		PlayerClass c = classOf(attacker);
		if (c == null) return;
		switch (c.id()) {
			case "pyro" -> {
				if (!attacker.getOffhandItem().isEmpty()) target.igniteForSeconds(4);
			}
			case "paladin" -> {
				if (target.typeHolder().is(EntityTypeTags.UNDEAD)) {
					target.invulnerableTime = 0; // let the extra damage through right after the hit
					target.hurtServer((ServerLevel) target.level(), attacker.damageSources().magic(), 4);
					attacker.heal(2);
				}
			}
			case "plaguebearer" -> target.addEffect(new MobEffectInstance(MobEffects.POISON, 80, 1), attacker);
			case "necromancer" -> target.addEffect(new MobEffectInstance(MobEffects.WITHER, 80, 0), attacker);
			case "vampire" -> attacker.heal(1);
			default -> {}
		}
	}

	/** Sentinels hurt whoever hits them in melee. */
	private static void onSentinelHit(LivingEntity target, DamageSource source) {
		if (!(target instanceof ServerPlayer sentinel) || !isClass(sentinel, "sentinel")) return;
		if (!(source.is(DamageTypes.PLAYER_ATTACK) || source.is(DamageTypes.MOB_ATTACK) || source.is(DamageTypes.MOB_ATTACK_NO_AGGRO))) return;
		Entity attacker = source.getEntity();
		if (attacker == null || attacker == sentinel || !attacker.isAlive()) return;
		attacker.hurtServer((ServerLevel) attacker.level(), sentinel.damageSources().thorns(sentinel), 2);
	}

	// ---------- Commands ----------

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("class")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(c -> {
					ServerPlayer player = c.getSource().getPlayer();
					if (player == null) {
						c.getSource().sendFailure(Component.literal("Only players can choose a class. Use /setclass for others."));
						return 0;
					}
					ClassMenu.open(player);
					return 1;
				}));

		List<String> names = new ArrayList<>(PlayerClass.ALL.keySet());
		names.add("none");
		dispatcher.register(Commands.literal("setclass")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("players", EntityArgument.players())
						.then(Commands.argument("class", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(names, b))
								.executes(c -> {
									String name = StringArgumentType.getString(c, "class").toLowerCase(java.util.Locale.ROOT);
									if (!names.contains(name)) {
										c.getSource().sendFailure(Component.literal("No class called " + name + ". Choose one of: " + String.join(", ", names)));
										return 0;
									}
									PlayerClass target = PlayerClass.ALL.get(name);
									Collection<ServerPlayer> players = EntityArgument.getPlayers(c, "players");
									for (ServerPlayer player : players) {
										if (classOf(player) != target) setClass(player, target);
									}
									String who = players.stream().map(p -> p.getName().getString()).collect(Collectors.joining(", "));
									c.getSource().sendSuccess(() -> Component.literal("Set " + who + " to "
											+ (target == null ? "no class" : target.name()) + "."), true);
									return players.size();
								}))));
	}

	static MutableComponent prefix() {
		return Component.empty().append(Component.literal("Classes:").withStyle(ChatFormatting.GOLD)).append(" ");
	}
}
