package com.thecoberto.utilities.zones;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.GameType;

/**
 * Adventure zones: areas where players in Survival are switched to Adventure
 * mode, and back to Survival when they leave. Players inside also get the
 * inside_adventure_zone tag, which the Teams mod uses to block PvP. Operators
 * in Creative or Spectator are left alone. Zones are saved in the world.
 *
 * With no zones, this does nothing at all: it only ever removes tags and
 * Adventure mode that it gave out itself.
 */
public final class AdventureZones {
	private AdventureZones() {}

	public static final String ZONE_TAG = "inside_adventure_zone"; // same tag as the Teams mod
	/** We gave them the zone tag, so we take it away when they leave. */
	private static final String TAGGED_TAG = "coberto_zone_tagged";
	/** We put them in Adventure, so we put them back. */
	private static final String CHANGED_TAG = "coberto_zone_adventure";

	private static final SuggestionProvider<CommandSourceStack> ZONE_NAMES = (c, b) ->
			SharedSuggestionProvider.suggest(ZonesState.get(c.getSource().getServer()).zones.stream().map(z -> z.name), b);

	/** Player -> the zone they were last in, for the leave message. */
	private static final Map<UUID, ZonesState.Zone> lastZone = new HashMap<>();
	private static int ticks;

	// ---------- Entering and leaving ----------

	public static void registerEvents() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (++ticks % 10 != 0) return;
			for (ServerPlayer player : server.getPlayerList().getPlayers()) check(player);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> lastZone.remove(handler.player.getUUID()));
	}

	private static boolean contains(ZonesState.Zone zone, ServerPlayer player) {
		if (!zone.enabled || !player.level().dimension().identifier().toString().equals(zone.dimension)) return false;
		Vec3 pos = player.position();
		double dx = pos.x - (zone.x + 0.5);
		double dz = pos.z - (zone.z + 0.5);
		double dy = zone.column ? 0 : pos.y - zone.y;
		return dx * dx + dy * dy + dz * dz <= (double) zone.radius * zone.radius;
	}

	private static void check(ServerPlayer player) {
		var tags = player.entityTags();
		ZonesState.Zone zone = ZonesState.get(player.level().getServer()).zones.stream()
				.filter(z -> contains(z, player)).findFirst().orElse(null);

		if (zone != null) {
			if (!tags.contains(TAGGED_TAG)) {
				player.addTag(TAGGED_TAG);
				player.addTag(ZONE_TAG);
				if (!zone.enterMessage.isEmpty()) player.sendOverlayMessage(fill(zone.enterMessage, player, zone));
			}
			// Checked every time, so someone switched to Survival inside goes back to Adventure.
			if (player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL) {
				player.setGameMode(GameType.ADVENTURE);
				player.addTag(CHANGED_TAG);
			}
			lastZone.put(player.getUUID(), zone);
		} else if (tags.contains(TAGGED_TAG) || tags.contains(CHANGED_TAG)) {
			if (tags.contains(TAGGED_TAG)) {
				player.removeTag(TAGGED_TAG);
				player.removeTag(ZONE_TAG);
			}
			if (tags.contains(CHANGED_TAG)) {
				player.removeTag(CHANGED_TAG);
				if (player.gameMode.getGameModeForPlayer() == GameType.ADVENTURE) player.setGameMode(GameType.SURVIVAL);
			}
			ZonesState.Zone left = lastZone.remove(player.getUUID());
			if (left != null && !left.leaveMessage.isEmpty()) player.sendOverlayMessage(fill(left.leaveMessage, player, left));
		}
	}

	private static Component fill(String message, ServerPlayer player, ZonesState.Zone zone) {
		return Component.literal(colorize(message.replace("{player}", player.getName().getString()).replace("{zone}", zone.name)));
	}

	private static String colorize(String text) {
		return text.replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1");
	}

	// ---------- Commands ----------

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("zonecreate")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("name", StringArgumentType.word())
						.then(Commands.argument("radius", IntegerArgumentType.integer(1, 10_000))
								.executes(c -> create(c, false))
								.then(Commands.argument("allHeights", BoolArgumentType.bool())
										.executes(c -> create(c, BoolArgumentType.getBool(c, "allHeights")))))));

		dispatcher.register(Commands.literal("zonedelete")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("name", StringArgumentType.word()).suggests(ZONE_NAMES)
						.executes(c -> edit(c, zone -> {
							ZonesState state = ZonesState.get(c.getSource().getServer());
							state.zones.remove(zone);
						}, "Deleted " + StringArgumentType.getString(c, "name") + ". Players inside go back to Survival.", false))));

		dispatcher.register(Commands.literal("zones")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(c -> list(c.getSource())));

		LiteralArgumentBuilder<CommandSourceStack> zone = Commands.literal("zone")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(c -> list(c.getSource()));
		zone.then(Commands.argument("name", StringArgumentType.word()).suggests(ZONE_NAMES)
				.executes(c -> show(c.getSource(), StringArgumentType.getString(c, "name")))
				.then(Commands.literal("rename").then(Commands.argument("newName", StringArgumentType.word()).executes(c -> {
					String newName = StringArgumentType.getString(c, "newName");
					Optional<ZonesState.Zone> clash = ZonesState.get(c.getSource().getServer()).find(newName);
					Optional<ZonesState.Zone> self = ZonesState.get(c.getSource().getServer()).find(StringArgumentType.getString(c, "name"));
					if (clash.isPresent() && !clash.equals(self)) return fail(c.getSource(), "There's already a zone called " + newName + ".");
					return edit(c, z -> z.name = newName, null, true);
				})))
				.then(Commands.literal("enabled").then(Commands.argument("on", BoolArgumentType.bool())
						.executes(c -> edit(c, z -> z.enabled = BoolArgumentType.getBool(c, "on"), null, true))))
				.then(Commands.literal("radius").then(Commands.argument("radius", IntegerArgumentType.integer(1, 10_000))
						.executes(c -> edit(c, z -> z.radius = IntegerArgumentType.getInteger(c, "radius"), null, true))))
				.then(Commands.literal("allheights").then(Commands.argument("on", BoolArgumentType.bool())
						.executes(c -> edit(c, z -> z.column = BoolArgumentType.getBool(c, "on"), null, true))))
				.then(Commands.literal("movehere").executes(c -> edit(c, z -> moveTo(z, c.getSource()), null, true)))
				.then(Commands.literal("entermessage").then(Commands.argument("message", StringArgumentType.greedyString())
						.executes(c -> edit(c, z -> z.enterMessage = message(c), null, true))))
				.then(Commands.literal("leavemessage").then(Commands.argument("message", StringArgumentType.greedyString())
						.executes(c -> edit(c, z -> z.leaveMessage = message(c), null, true))))
				.then(Commands.literal("delete").executes(c -> edit(c, z -> ZonesState.get(c.getSource().getServer()).zones.remove(z),
						"Deleted " + StringArgumentType.getString(c, "name") + ". Players inside go back to Survival.", false))));
		dispatcher.register(zone);
	}

	/** "none" clears the message. */
	private static String message(CommandContext<CommandSourceStack> c) {
		String message = StringArgumentType.getString(c, "message").trim();
		return message.equalsIgnoreCase("none") ? "" : message;
	}

	private static void moveTo(ZonesState.Zone zone, CommandSourceStack source) {
		Vec3 pos = source.getPosition();
		zone.dimension = source.getLevel().dimension().identifier().toString();
		zone.x = (int) Math.floor(pos.x);
		zone.y = (int) Math.floor(pos.y);
		zone.z = (int) Math.floor(pos.z);
	}

	private static int create(CommandContext<CommandSourceStack> c, boolean allHeights) {
		CommandSourceStack source = c.getSource();
		String name = StringArgumentType.getString(c, "name");
		ZonesState state = ZonesState.get(source.getServer());
		if (state.find(name).isPresent()) return fail(source, "There's already a zone called " + name + ". Use /zone " + name + " to change it.");
		ZonesState.Zone zone = new ZonesState.Zone();
		zone.name = name;
		zone.radius = IntegerArgumentType.getInteger(c, "radius");
		zone.column = allHeights;
		moveTo(zone, source);
		state.zones.add(zone);
		state.setDirty();
		source.sendSuccess(() -> prefix().append("Created ").append(describe(zone)).append("."), true);
		return 1;
	}

	/** Changes the named zone. With no message, shows the zone again (for the clickable editor). */
	private static int edit(CommandContext<CommandSourceStack> c, Consumer<ZonesState.Zone> change, String message, boolean showAfter) {
		CommandSourceStack source = c.getSource();
		ZonesState state = ZonesState.get(source.getServer());
		Optional<ZonesState.Zone> zone = state.find(StringArgumentType.getString(c, "name"));
		if (zone.isEmpty()) return fail(source, "No zone called " + StringArgumentType.getString(c, "name") + ". Use /zones to see them.");
		change.accept(zone.get());
		state.setDirty();
		if (message != null) source.sendSuccess(() -> prefix().append(message), true);
		if (showAfter) {
			source.sendSuccess(() -> prefix().append("Saved ").append(describe(zone.get())).append("."), true);
			show(source, zone.get().name);
		}
		return 1;
	}

	private static int list(CommandSourceStack source) {
		var zones = ZonesState.get(source.getServer()).zones;
		MutableComponent text = prefix();
		if (zones.isEmpty()) {
			text.append("No zones yet. Stand in the middle and use /zonecreate <name> <radius> [allHeights].");
		} else {
			text.append(zones.size() + " zone(s). Click one to change it:");
			for (ZonesState.Zone z : zones) {
				text.append("\n ").append(describe(z).withStyle(s -> s
						.withClickEvent(new ClickEvent.RunCommand("/zone " + z.name))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal("/zone " + z.name)))));
			}
		}
		source.sendSuccess(() -> text, false);
		return zones.size();
	}

	/** The clickable editor for one zone. */
	private static int show(CommandSourceStack source, String name) {
		Optional<ZonesState.Zone> found = ZonesState.get(source.getServer()).find(name);
		if (found.isEmpty()) return fail(source, "No zone called " + name + ". Use /zones to see them.");
		ZonesState.Zone z = found.get();
		String cmd = "/zone " + z.name + " ";
		MutableComponent text = prefix().append(describe(z))
				.append("\n ").append(button(z.enabled ? "[Turn off]" : "[Turn on]", z.enabled ? ChatFormatting.RED : ChatFormatting.GREEN, cmd + "enabled " + !z.enabled, true))
				.append(" ").append(button("[Rename]", ChatFormatting.YELLOW, cmd + "rename ", false))
				.append(" ").append(button("[Radius: " + z.radius + "]", ChatFormatting.AQUA, cmd + "radius " + z.radius, false))
				.append(" ").append(button(z.column ? "[All heights: on]" : "[All heights: off]", ChatFormatting.AQUA, cmd + "allheights " + !z.column, true))
				.append("\n ").append(button("[Move center here]", ChatFormatting.AQUA, cmd + "movehere", true))
				.append(" ").append(button("[Delete]", ChatFormatting.RED, cmd + "delete", false))
				.append("\n Entering: ").append(messageButton(z.enterMessage, cmd + "entermessage "))
				.append("\n Leaving: ").append(messageButton(z.leaveMessage, cmd + "leavemessage "))
				.append(Component.literal("\n {player}, {zone} and & colors work in messages. \"none\" = no message.").withStyle(ChatFormatting.GRAY));
		source.sendSuccess(() -> text, false);
		return 1;
	}

	private static MutableComponent messageButton(String message, String command) {
		Component shown = message.isEmpty() ? Component.literal("(none)").withStyle(ChatFormatting.GRAY) : Component.literal(colorize(message));
		return Component.empty().append(shown).append(" ").append(button("[Change]", ChatFormatting.YELLOW, command + (message.isEmpty() ? "" : message), false));
	}

	private static MutableComponent button(String label, ChatFormatting color, String command, boolean run) {
		return Component.literal(label).withStyle(s -> s.withColor(color)
				.withClickEvent((run ? new ClickEvent.RunCommand(command) : new ClickEvent.SuggestCommand(command)))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
	}

	private static MutableComponent describe(ZonesState.Zone z) {
		String dim = switch (z.dimension) {
			case "minecraft:overworld" -> "Overworld";
			case "minecraft:the_nether" -> "Nether";
			case "minecraft:the_end" -> "End";
			default -> z.dimension;
		};
		MutableComponent text = Component.empty().append(Component.literal(z.name).withStyle(ChatFormatting.YELLOW));
		if (!z.enabled) text.append(Component.literal(" (off)").withStyle(ChatFormatting.RED));
		return text.append(": " + dim + " " + z.x + " " + z.y + " " + z.z + ", radius " + z.radius + (z.column ? " (all heights)" : ""));
	}

	private static MutableComponent prefix() {
		return Component.empty().append(Component.literal("Zones:").withStyle(ChatFormatting.DARK_GREEN)).append(" ");
	}

	private static int fail(CommandSourceStack source, String message) {
		source.sendFailure(Component.literal(message));
		return 0;
	}
}
