package com.thecoberto.utilities;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Activity Rewards and AFK: players earn Money for every so many minutes
 * they're active. Players who stop moving are marked AFK: they get the "afk"
 * tag, a big "You are AFK!" title, and their reward timer pauses until they
 * come back. Rewards are off by default; AFK detection is on.
 */
public final class ActivityRewards {
	private ActivityRewards() {}

	public static final String AFK_TAG = "afk";
	private static final String DEFAULT_MESSAGE = "You have been awarded &a{amount} Money&r for being active!";
	private static final String DEFAULT_SOUND = "minecraft:entity.player.levelup";

	// ---------- Settings ----------

	public static class State extends SavedData {
		private static final SavedDataType<State> TYPE = new SavedDataType<>(Identifier.withDefaultNamespace("coberto_activity_rewards"), State::new,
			CompoundTag.CODEC.xmap(State::fromNbt, s -> s.writeNbt(new CompoundTag())), null);

		boolean enabled = false;
		int amount = 100;
		int minutes = 10;
		String message = DEFAULT_MESSAGE;
		String sound = DEFAULT_SOUND; // "" = none
		boolean afkEnabled = true;
		int afkSeconds = 300;

		static State get(MinecraftServer server) {
			return server.getDataStorage().computeIfAbsent(TYPE);
		}

		private static State fromNbt(CompoundTag nbt) {
			State s = new State();
			s.enabled = nbt.getBooleanOr("enabled", false);
			s.amount = nbt.getIntOr("amount", 0);
			s.minutes = nbt.getIntOr("minutes", 0);
			s.message = nbt.getStringOr("message", "");
			s.sound = nbt.getStringOr("sound", "");
			s.afkEnabled = nbt.getBooleanOr("afkEnabled", false);
			s.afkSeconds = nbt.getIntOr("afkSeconds", 0);
			return s;
		}

				CompoundTag writeNbt(CompoundTag nbt) {
			nbt.putBoolean("enabled", enabled);
			nbt.putInt("amount", amount);
			nbt.putInt("minutes", minutes);
			nbt.putString("message", message);
			nbt.putString("sound", sound);
			nbt.putBoolean("afkEnabled", afkEnabled);
			nbt.putInt("afkSeconds", afkSeconds);
			return nbt;
		}
	}

	private static void change(CommandSourceStack source, Consumer<State> change, Component announcement) {
		State state = State.get(source.getServer());
		change.accept(state);
		state.setDirty();
		source.getServer().getPlayerList().broadcastSystemMessage(
				Component.empty().append(Component.literal("Activity Rewards:").withStyle(ChatFormatting.GOLD)).append(" ").append(announcement),
				false);
	}

	// ---------- Tracking ----------

	private static final class Tracked {
		Vec3 pos;
		float yaw;
		float pitch;
		int idleSeconds;
		int activeSeconds;
		boolean afk;
	}

	private static final Map<UUID, Tracked> tracked = new HashMap<>();
	private static int ticks;

	static void registerEvents() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (++ticks % 20 == 0) everySecond(server, ticks / 20);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> tracked.remove(handler.player.getUUID()));

		// Chatting and using things count as activity too.
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> markActive(sender));
		UseItemCallback.EVENT.register((player, world, hand) -> {
			markActive(player);
			return InteractionResult.PASS;
		});
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			markActive(player);
			return InteractionResult.PASS;
		});
		UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			markActive(player);
			return InteractionResult.PASS;
		});
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> markActive(player));
	}

	private static void everySecond(MinecraftServer server, int seconds) {
		State settings = State.get(server);
		Set<UUID> online = new HashSet<>();

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			online.add(player.getUUID());
			Tracked state = tracked.get(player.getUUID());
			if (state == null) {
				state = new Tracked();
				state.pos = player.position();
				state.yaw = player.getYRot();
				state.pitch = player.getXRot();
				tracked.put(player.getUUID(), state);
				// A leftover tag from before a restart shouldn't stick.
				player.removeTag(AFK_TAG);
			}

			boolean moved = player.position().distanceToSqr(state.pos) > 0.01
					|| Math.abs(player.getYRot() - state.yaw) > 1
					|| Math.abs(player.getXRot() - state.pitch) > 1;
			if (moved) markActive(player);
			else state.idleSeconds++;
			state.pos = player.position();
			state.yaw = player.getYRot();
			state.pitch = player.getXRot();

			if (settings.afkEnabled && !state.afk && state.idleSeconds >= settings.afkSeconds) {
				setAfk(player, state, true);
			} else if (!settings.afkEnabled && state.afk) {
				setAfk(player, state, false);
			}
			if (state.afk && seconds % 2 == 0) showAfkTitle(player, settings, false);

			// The timer only runs while the player isn't AFK.
			if (settings.enabled && !state.afk) {
				state.activeSeconds++;
				if (state.activeSeconds >= settings.minutes * 60) {
					state.activeSeconds = 0;
					pay(player, settings);
				}
			}
		}
		tracked.keySet().removeIf(id -> !online.contains(id));
	}

	private static void markActive(Player player) {
		if (!(player instanceof ServerPlayer serverPlayer)) return;
		Tracked state = tracked.get(player.getUUID());
		if (state == null) return;
		state.idleSeconds = 0;
		if (state.afk) setAfk(serverPlayer, state, false);
	}

	private static void setAfk(ServerPlayer player, Tracked state, boolean afk) {
		state.afk = afk;
		if (afk) {
			player.addTag(AFK_TAG);
			showAfkTitle(player, State.get(player.level().getServer()), true);
		} else {
			player.removeTag(AFK_TAG);
			player.connection.send(new ClientboundClearTitlesPacket(true));
			player.sendOverlayMessage(Component.literal("Welcome back!").withStyle(ChatFormatting.GREEN));
		}
	}

	private static void showAfkTitle(ServerPlayer player, State settings, boolean first) {
		// Refreshed every 2 seconds while AFK, so it stays up.
		player.connection.send(new ClientboundSetTitlesAnimationPacket(first ? 10 : 0, 60, 10));
		player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(
				settings.enabled ? "Move to keep earning activity rewards" : "Move to come back").withStyle(ChatFormatting.GRAY)));
		player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("You are AFK!").withStyle(ChatFormatting.RED)));
	}

	private static void pay(ServerPlayer player, State settings) {
		Money.add(player, settings.amount);
		player.sendSystemMessage(Component.literal(colorize(settings.message)
				.replace("{amount}", Money.format(settings.amount))
				.replace("{player}", player.getName().getString())
				.replace("{minutes}", String.valueOf(settings.minutes))));
		Identifier sound = settings.sound.isEmpty() ? null : Identifier.tryParse(settings.sound);
		if (sound != null) {
			Sounds.play(player, SoundEvent.createVariableRangeEvent(sound), SoundSource.MASTER, 1.0f, 1.0f);
		}
	}

	// ---------- Commands ----------

	static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("activity")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(c -> {
					Component text = describe(State.get(c.getSource().getServer()));
					c.getSource().sendSuccess(() -> text, false);
					return 1;
				})
				.then(Commands.argument("enabled", BoolArgumentType.bool()).executes(c -> {
					boolean enabled = BoolArgumentType.getBool(c, "enabled");
					change(c.getSource(), s -> s.enabled = enabled, enabled
							? Component.empty().append(Component.literal("On").withStyle(ChatFormatting.GREEN)).append(". Active players now earn Money over time.")
							: Component.empty().append(Component.literal("Off").withStyle(ChatFormatting.RED)).append("."));
					return 1;
				})));

		dispatcher.register(Commands.literal("activityreward")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("amount", IntegerArgumentType.integer(1, Money.MAX_AMOUNT))
						.then(Commands.argument("minutes", IntegerArgumentType.integer(1, 1440)).executes(c -> {
							int amount = IntegerArgumentType.getInteger(c, "amount");
							int minutes = IntegerArgumentType.getInteger(c, "minutes");
							change(c.getSource(), s -> { s.amount = amount; s.minutes = minutes; },
									Component.literal("Players now earn ")
											.append(Component.literal(Money.format(amount) + " Money").withStyle(ChatFormatting.GREEN))
											.append(" every " + plural(minutes, "minute") + " they're active."));
							return 1;
						}))));

		dispatcher.register(Commands.literal("activitymessage")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("message", StringArgumentType.greedyString()).executes(c -> {
					String message = StringArgumentType.getString(c, "message").trim();
					// Quotes are optional, like on Bedrock.
					if (message.length() >= 2 && message.startsWith("\"") && message.endsWith("\"")) {
						message = message.substring(1, message.length() - 1);
					}
					boolean reset = message.equalsIgnoreCase("reset");
					String newMessage = reset ? DEFAULT_MESSAGE : message;
					change(c.getSource(), s -> s.message = newMessage,
							Component.literal(reset ? "Reward message reset." : "Reward message is now: " + colorize(newMessage)));
					return 1;
				})));

		dispatcher.register(Commands.literal("activitysound")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("sound", StringArgumentType.greedyString())
						.suggests((c, b) -> SharedSuggestionProvider.suggestResource(BuiltInRegistries.SOUND_EVENT.keySet(), b))
						.executes(c -> {
							String sound = StringArgumentType.getString(c, "sound").trim();
							if (sound.equalsIgnoreCase("none")) {
								change(c.getSource(), s -> s.sound = "", Component.literal("Rewards no longer play a sound."));
								return 1;
							}
							Identifier id = Identifier.tryParse(sound);
							if (id == null) {
								c.getSource().sendFailure(Component.literal("That isn't a sound name. Try entity.player.levelup, or none."));
								return 0;
							}
							change(c.getSource(), s -> s.sound = id.toString(), Component.literal("Rewards now play ")
									.append(Component.literal(id.toString()).withStyle(ChatFormatting.YELLOW)).append("."));
							return 1;
						})));

		dispatcher.register(Commands.literal("afk")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("enabled", BoolArgumentType.bool()).executes(c -> {
					boolean enabled = BoolArgumentType.getBool(c, "enabled");
					change(c.getSource(), s -> s.afkEnabled = enabled, enabled
							? Component.literal("AFK detection ").append(Component.literal("on").withStyle(ChatFormatting.GREEN)).append(".")
							: Component.literal("AFK detection ").append(Component.literal("off").withStyle(ChatFormatting.RED)).append(". Nobody is marked AFK."));
					return 1;
				})));

		dispatcher.register(Commands.literal("afktime")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("seconds", IntegerArgumentType.integer(10, 86400)).executes(c -> {
					int seconds = IntegerArgumentType.getInteger(c, "seconds");
					change(c.getSource(), s -> s.afkSeconds = seconds,
							Component.literal("Players are now AFK after " + durationText(seconds) + " without moving."));
					return 1;
				})));
	}

	// ---------- Text ----------

	private static Component describe(State s) {
		MutableComponent on = Component.literal("on").withStyle(ChatFormatting.GREEN);
		MutableComponent off = Component.literal("off").withStyle(ChatFormatting.RED);
		return Component.empty()
				.append(Component.literal("Activity Rewards").withStyle(ChatFormatting.GOLD)).append(" are ")
				.append(s.enabled ? on : off).append(".\nReward: ")
				.append(Component.literal(Money.format(s.amount) + " Money").withStyle(ChatFormatting.GREEN))
				.append(" every " + plural(s.minutes, "minute") + " of activity.\nMessage: " + colorize(s.message)
						+ "\nSound: " + (s.sound.isEmpty() ? "none" : s.sound) + "\nAFK detection: ")
				.append(s.afkEnabled ? on.copy() : off.copy())
				.append(", after " + durationText(s.afkSeconds) + " without moving (tag: " + AFK_TAG + ").");
	}

	/** &a etc. become color codes. */
	private static String colorize(String text) {
		return text.replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1");
	}

	private static String plural(int n, String word) {
		return n + " " + word + (n == 1 ? "" : "s");
	}

	private static String durationText(int seconds) {
		return seconds % 60 == 0 ? plural(seconds / 60, "minute") : plural(seconds, "second");
	}
}
