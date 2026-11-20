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
import net.minecraft.command.CommandSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.ClearTitleS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.PersistentState;

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

	public static class State extends PersistentState {
		private static final PersistentState.Type<State> TYPE = new PersistentState.Type<>(State::new, State::fromNbt, null);

		boolean enabled = false;
		int amount = 100;
		int minutes = 10;
		String message = DEFAULT_MESSAGE;
		String sound = DEFAULT_SOUND; // "" = none
		boolean afkEnabled = true;
		int afkSeconds = 300;

		static State get(MinecraftServer server) {
			return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE, "coberto_activity_rewards");
		}

		private static State fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
			State s = new State();
			s.enabled = nbt.getBoolean("enabled");
			s.amount = nbt.getInt("amount");
			s.minutes = nbt.getInt("minutes");
			s.message = nbt.getString("message");
			s.sound = nbt.getString("sound");
			s.afkEnabled = nbt.getBoolean("afkEnabled");
			s.afkSeconds = nbt.getInt("afkSeconds");
			return s;
		}

		@Override
		public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
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

	private static void change(ServerCommandSource source, Consumer<State> change, Text announcement) {
		State state = State.get(source.getServer());
		change.accept(state);
		state.markDirty();
		source.getServer().getPlayerManager().broadcast(
				Text.empty().append(Text.literal("Activity Rewards:").formatted(Formatting.GOLD)).append(" ").append(announcement),
				false);
	}

	// ---------- Tracking ----------

	private static final class Tracked {
		Vec3d pos;
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
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> tracked.remove(handler.player.getUuid()));

		// Chatting and using things count as activity too.
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> markActive(sender));
		UseItemCallback.EVENT.register((player, world, hand) -> {
			markActive(player);
			return TypedActionResult.pass(player.getStackInHand(hand));
		});
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			markActive(player);
			return ActionResult.PASS;
		});
		UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			markActive(player);
			return ActionResult.PASS;
		});
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> markActive(player));
	}

	private static void everySecond(MinecraftServer server, int seconds) {
		State settings = State.get(server);
		Set<UUID> online = new HashSet<>();

		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			online.add(player.getUuid());
			Tracked state = tracked.get(player.getUuid());
			if (state == null) {
				state = new Tracked();
				state.pos = player.getPos();
				state.yaw = player.getYaw();
				state.pitch = player.getPitch();
				tracked.put(player.getUuid(), state);
				// A leftover tag from before a restart shouldn't stick.
				player.removeCommandTag(AFK_TAG);
			}

			boolean moved = player.getPos().squaredDistanceTo(state.pos) > 0.01
					|| Math.abs(player.getYaw() - state.yaw) > 1
					|| Math.abs(player.getPitch() - state.pitch) > 1;
			if (moved) markActive(player);
			else state.idleSeconds++;
			state.pos = player.getPos();
			state.yaw = player.getYaw();
			state.pitch = player.getPitch();

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

	private static void markActive(PlayerEntity player) {
		if (!(player instanceof ServerPlayerEntity serverPlayer)) return;
		Tracked state = tracked.get(player.getUuid());
		if (state == null) return;
		state.idleSeconds = 0;
		if (state.afk) setAfk(serverPlayer, state, false);
	}

	private static void setAfk(ServerPlayerEntity player, Tracked state, boolean afk) {
		state.afk = afk;
		if (afk) {
			player.addCommandTag(AFK_TAG);
			showAfkTitle(player, State.get(player.server), true);
		} else {
			player.removeCommandTag(AFK_TAG);
			player.networkHandler.sendPacket(new ClearTitleS2CPacket(true));
			player.sendMessage(Text.literal("Welcome back!").formatted(Formatting.GREEN), true);
		}
	}

	private static void showAfkTitle(ServerPlayerEntity player, State settings, boolean first) {
		// Refreshed every 2 seconds while AFK, so it stays up.
		player.networkHandler.sendPacket(new TitleFadeS2CPacket(first ? 10 : 0, 60, 10));
		player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(
				settings.enabled ? "Move to keep earning activity rewards" : "Move to come back").formatted(Formatting.GRAY)));
		player.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("You are AFK!").formatted(Formatting.RED)));
	}

	private static void pay(ServerPlayerEntity player, State settings) {
		Money.add(player, settings.amount);
		player.sendMessage(Text.literal(colorize(settings.message)
				.replace("{amount}", Money.format(settings.amount))
				.replace("{player}", player.getName().getString())
				.replace("{minutes}", String.valueOf(settings.minutes))));
		Identifier sound = settings.sound.isEmpty() ? null : Identifier.tryParse(settings.sound);
		if (sound != null) {
			player.playSoundToPlayer(SoundEvent.of(sound), SoundCategory.MASTER, 1.0f, 1.0f);
		}
	}

	// ---------- Commands ----------

	static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("activity")
				.requires(s -> s.hasPermissionLevel(2))
				.executes(c -> {
					Text text = describe(State.get(c.getSource().getServer()));
					c.getSource().sendFeedback(() -> text, false);
					return 1;
				})
				.then(CommandManager.argument("enabled", BoolArgumentType.bool()).executes(c -> {
					boolean enabled = BoolArgumentType.getBool(c, "enabled");
					change(c.getSource(), s -> s.enabled = enabled, enabled
							? Text.empty().append(Text.literal("On").formatted(Formatting.GREEN)).append(". Active players now earn Money over time.")
							: Text.empty().append(Text.literal("Off").formatted(Formatting.RED)).append("."));
					return 1;
				})));

		dispatcher.register(CommandManager.literal("activityreward")
				.requires(s -> s.hasPermissionLevel(2))
				.then(CommandManager.argument("amount", IntegerArgumentType.integer(1, Money.MAX_AMOUNT))
						.then(CommandManager.argument("minutes", IntegerArgumentType.integer(1, 1440)).executes(c -> {
							int amount = IntegerArgumentType.getInteger(c, "amount");
							int minutes = IntegerArgumentType.getInteger(c, "minutes");
							change(c.getSource(), s -> { s.amount = amount; s.minutes = minutes; },
									Text.literal("Players now earn ")
											.append(Text.literal(Money.format(amount) + " Money").formatted(Formatting.GREEN))
											.append(" every " + plural(minutes, "minute") + " they're active."));
							return 1;
						}))));

		dispatcher.register(CommandManager.literal("activitymessage")
				.requires(s -> s.hasPermissionLevel(2))
				.then(CommandManager.argument("message", StringArgumentType.greedyString()).executes(c -> {
					String message = StringArgumentType.getString(c, "message").trim();
					// Quotes are optional, like on Bedrock.
					if (message.length() >= 2 && message.startsWith("\"") && message.endsWith("\"")) {
						message = message.substring(1, message.length() - 1);
					}
					boolean reset = message.equalsIgnoreCase("reset");
					String newMessage = reset ? DEFAULT_MESSAGE : message;
					change(c.getSource(), s -> s.message = newMessage,
							Text.literal(reset ? "Reward message reset." : "Reward message is now: " + colorize(newMessage)));
					return 1;
				})));

		dispatcher.register(CommandManager.literal("activitysound")
				.requires(s -> s.hasPermissionLevel(2))
				.then(CommandManager.argument("sound", StringArgumentType.greedyString())
						.suggests((c, b) -> CommandSource.suggestIdentifiers(Registries.SOUND_EVENT.getIds(), b))
						.executes(c -> {
							String sound = StringArgumentType.getString(c, "sound").trim();
							if (sound.equalsIgnoreCase("none")) {
								change(c.getSource(), s -> s.sound = "", Text.literal("Rewards no longer play a sound."));
								return 1;
							}
							Identifier id = Identifier.tryParse(sound);
							if (id == null) {
								c.getSource().sendError(Text.literal("That isn't a sound name. Try entity.player.levelup, or none."));
								return 0;
							}
							change(c.getSource(), s -> s.sound = id.toString(), Text.literal("Rewards now play ")
									.append(Text.literal(id.toString()).formatted(Formatting.YELLOW)).append("."));
							return 1;
						})));

		dispatcher.register(CommandManager.literal("afk")
				.requires(s -> s.hasPermissionLevel(2))
				.then(CommandManager.argument("enabled", BoolArgumentType.bool()).executes(c -> {
					boolean enabled = BoolArgumentType.getBool(c, "enabled");
					change(c.getSource(), s -> s.afkEnabled = enabled, enabled
							? Text.literal("AFK detection ").append(Text.literal("on").formatted(Formatting.GREEN)).append(".")
							: Text.literal("AFK detection ").append(Text.literal("off").formatted(Formatting.RED)).append(". Nobody is marked AFK."));
					return 1;
				})));

		dispatcher.register(CommandManager.literal("afktime")
				.requires(s -> s.hasPermissionLevel(2))
				.then(CommandManager.argument("seconds", IntegerArgumentType.integer(10, 86400)).executes(c -> {
					int seconds = IntegerArgumentType.getInteger(c, "seconds");
					change(c.getSource(), s -> s.afkSeconds = seconds,
							Text.literal("Players are now AFK after " + durationText(seconds) + " without moving."));
					return 1;
				})));
	}

	// ---------- Text ----------

	private static Text describe(State s) {
		MutableText on = Text.literal("on").formatted(Formatting.GREEN);
		MutableText off = Text.literal("off").formatted(Formatting.RED);
		return Text.empty()
				.append(Text.literal("Activity Rewards").formatted(Formatting.GOLD)).append(" are ")
				.append(s.enabled ? on : off).append(".\nReward: ")
				.append(Text.literal(Money.format(s.amount) + " Money").formatted(Formatting.GREEN))
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
