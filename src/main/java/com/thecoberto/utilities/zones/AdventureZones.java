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
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

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

	private static final SuggestionProvider<ServerCommandSource> ZONE_NAMES = (c, b) ->
			CommandSource.suggestMatching(ZonesState.get(c.getSource().getServer()).zones.stream().map(z -> z.name), b);

	/** Player -> the zone they were last in, for the leave message. */
	private static final Map<UUID, ZonesState.Zone> lastZone = new HashMap<>();
	private static int ticks;

	// ---------- Entering and leaving ----------

	public static void registerEvents() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (++ticks % 10 != 0) return;
			for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) check(player);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> lastZone.remove(handler.player.getUuid()));
	}

	private static boolean contains(ZonesState.Zone zone, ServerPlayerEntity player) {
		if (!zone.enabled || !player.getWorld().getRegistryKey().getValue().toString().equals(zone.dimension)) return false;
		Vec3d pos = player.getPos();
		double dx = pos.x - (zone.x + 0.5);
		double dz = pos.z - (zone.z + 0.5);
		double dy = zone.column ? 0 : pos.y - zone.y;
		return dx * dx + dy * dy + dz * dz <= (double) zone.radius * zone.radius;
	}

	private static void check(ServerPlayerEntity player) {
		var tags = player.getCommandTags();
		ZonesState.Zone zone = ZonesState.get(player.server).zones.stream()
				.filter(z -> contains(z, player)).findFirst().orElse(null);

		if (zone != null) {
			if (!tags.contains(TAGGED_TAG)) {
				player.addCommandTag(TAGGED_TAG);
				player.addCommandTag(ZONE_TAG);
				if (!zone.enterMessage.isEmpty()) player.sendMessage(fill(zone.enterMessage, player, zone), true);
			}
			// Checked every time, so someone switched to Survival inside goes back to Adventure.
			if (player.interactionManager.getGameMode() == GameMode.SURVIVAL) {
				player.changeGameMode(GameMode.ADVENTURE);
				player.addCommandTag(CHANGED_TAG);
			}
			lastZone.put(player.getUuid(), zone);
		} else if (tags.contains(TAGGED_TAG) || tags.contains(CHANGED_TAG)) {
			if (tags.contains(TAGGED_TAG)) {
				player.removeCommandTag(TAGGED_TAG);
				player.removeCommandTag(ZONE_TAG);
			}
			if (tags.contains(CHANGED_TAG)) {
				player.removeCommandTag(CHANGED_TAG);
				if (player.interactionManager.getGameMode() == GameMode.ADVENTURE) player.changeGameMode(GameMode.SURVIVAL);
			}
			ZonesState.Zone left = lastZone.remove(player.getUuid());
			if (left != null && !left.leaveMessage.isEmpty()) player.sendMessage(fill(left.leaveMessage, player, left), true);
		}
	}

	private static Text fill(String message, ServerPlayerEntity player, ZonesState.Zone zone) {
		return Text.literal(colorize(message.replace("{player}", player.getName().getString()).replace("{zone}", zone.name)));
	}

	private static String colorize(String text) {
		return text.replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1");
	}

	// ---------- Commands ----------

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("zonecreate")
				.requires(s -> s.hasPermissionLevel(2))
				.then(CommandManager.argument("name", StringArgumentType.word())
						.then(CommandManager.argument("radius", IntegerArgumentType.integer(1, 10_000))
								.executes(c -> create(c, false))
								.then(CommandManager.argument("allHeights", BoolArgumentType.bool())
										.executes(c -> create(c, BoolArgumentType.getBool(c, "allHeights")))))));

		dispatcher.register(CommandManager.literal("zonedelete")
				.requires(s -> s.hasPermissionLevel(2))
				.then(CommandManager.argument("name", StringArgumentType.word()).suggests(ZONE_NAMES)
						.executes(c -> edit(c, zone -> {
							ZonesState state = ZonesState.get(c.getSource().getServer());
							state.zones.remove(zone);
						}, "Deleted " + StringArgumentType.getString(c, "name") + ". Players inside go back to Survival.", false))));

		dispatcher.register(CommandManager.literal("zones")
				.requires(s -> s.hasPermissionLevel(2))
				.executes(c -> list(c.getSource())));

		LiteralArgumentBuilder<ServerCommandSource> zone = CommandManager.literal("zone")
				.requires(s -> s.hasPermissionLevel(2))
				.executes(c -> list(c.getSource()));
		zone.then(CommandManager.argument("name", StringArgumentType.word()).suggests(ZONE_NAMES)
				.executes(c -> show(c.getSource(), StringArgumentType.getString(c, "name")))
				.then(CommandManager.literal("rename").then(CommandManager.argument("newName", StringArgumentType.word()).executes(c -> {
					String newName = StringArgumentType.getString(c, "newName");
					Optional<ZonesState.Zone> clash = ZonesState.get(c.getSource().getServer()).find(newName);
					Optional<ZonesState.Zone> self = ZonesState.get(c.getSource().getServer()).find(StringArgumentType.getString(c, "name"));
					if (clash.isPresent() && !clash.equals(self)) return fail(c.getSource(), "There's already a zone called " + newName + ".");
					return edit(c, z -> z.name = newName, null, true);
				})))
				.then(CommandManager.literal("enabled").then(CommandManager.argument("on", BoolArgumentType.bool())
						.executes(c -> edit(c, z -> z.enabled = BoolArgumentType.getBool(c, "on"), null, true))))
				.then(CommandManager.literal("radius").then(CommandManager.argument("radius", IntegerArgumentType.integer(1, 10_000))
						.executes(c -> edit(c, z -> z.radius = IntegerArgumentType.getInteger(c, "radius"), null, true))))
				.then(CommandManager.literal("allheights").then(CommandManager.argument("on", BoolArgumentType.bool())
						.executes(c -> edit(c, z -> z.column = BoolArgumentType.getBool(c, "on"), null, true))))
				.then(CommandManager.literal("movehere").executes(c -> edit(c, z -> moveTo(z, c.getSource()), null, true)))
				.then(CommandManager.literal("entermessage").then(CommandManager.argument("message", StringArgumentType.greedyString())
						.executes(c -> edit(c, z -> z.enterMessage = message(c), null, true))))
				.then(CommandManager.literal("leavemessage").then(CommandManager.argument("message", StringArgumentType.greedyString())
						.executes(c -> edit(c, z -> z.leaveMessage = message(c), null, true))))
				.then(CommandManager.literal("delete").executes(c -> edit(c, z -> ZonesState.get(c.getSource().getServer()).zones.remove(z),
						"Deleted " + StringArgumentType.getString(c, "name") + ". Players inside go back to Survival.", false))));
		dispatcher.register(zone);
	}

	/** "none" clears the message. */
	private static String message(CommandContext<ServerCommandSource> c) {
		String message = StringArgumentType.getString(c, "message").trim();
		return message.equalsIgnoreCase("none") ? "" : message;
	}

	private static void moveTo(ZonesState.Zone zone, ServerCommandSource source) {
		Vec3d pos = source.getPosition();
		zone.dimension = source.getWorld().getRegistryKey().getValue().toString();
		zone.x = (int) Math.floor(pos.x);
		zone.y = (int) Math.floor(pos.y);
		zone.z = (int) Math.floor(pos.z);
	}

	private static int create(CommandContext<ServerCommandSource> c, boolean allHeights) {
		ServerCommandSource source = c.getSource();
		String name = StringArgumentType.getString(c, "name");
		ZonesState state = ZonesState.get(source.getServer());
		if (state.find(name).isPresent()) return fail(source, "There's already a zone called " + name + ". Use /zone " + name + " to change it.");
		ZonesState.Zone zone = new ZonesState.Zone();
		zone.name = name;
		zone.radius = IntegerArgumentType.getInteger(c, "radius");
		zone.column = allHeights;
		moveTo(zone, source);
		state.zones.add(zone);
		state.markDirty();
		source.sendFeedback(() -> prefix().append("Created ").append(describe(zone)).append("."), true);
		return 1;
	}

	/** Changes the named zone. With no message, shows the zone again (for the clickable editor). */
	private static int edit(CommandContext<ServerCommandSource> c, Consumer<ZonesState.Zone> change, String message, boolean showAfter) {
		ServerCommandSource source = c.getSource();
		ZonesState state = ZonesState.get(source.getServer());
		Optional<ZonesState.Zone> zone = state.find(StringArgumentType.getString(c, "name"));
		if (zone.isEmpty()) return fail(source, "No zone called " + StringArgumentType.getString(c, "name") + ". Use /zones to see them.");
		change.accept(zone.get());
		state.markDirty();
		if (message != null) source.sendFeedback(() -> prefix().append(message), true);
		if (showAfter) {
			source.sendFeedback(() -> prefix().append("Saved ").append(describe(zone.get())).append("."), true);
			show(source, zone.get().name);
		}
		return 1;
	}

	private static int list(ServerCommandSource source) {
		var zones = ZonesState.get(source.getServer()).zones;
		MutableText text = prefix();
		if (zones.isEmpty()) {
			text.append("No zones yet. Stand in the middle and use /zonecreate <name> <radius> [allHeights].");
		} else {
			text.append(zones.size() + " zone(s). Click one to change it:");
			for (ZonesState.Zone z : zones) {
				text.append("\n ").append(describe(z).styled(s -> s
						.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/zone " + z.name))
						.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("/zone " + z.name)))));
			}
		}
		source.sendFeedback(() -> text, false);
		return zones.size();
	}

	/** The clickable editor for one zone. */
	private static int show(ServerCommandSource source, String name) {
		Optional<ZonesState.Zone> found = ZonesState.get(source.getServer()).find(name);
		if (found.isEmpty()) return fail(source, "No zone called " + name + ". Use /zones to see them.");
		ZonesState.Zone z = found.get();
		String cmd = "/zone " + z.name + " ";
		MutableText text = prefix().append(describe(z))
				.append("\n ").append(button(z.enabled ? "[Turn off]" : "[Turn on]", z.enabled ? Formatting.RED : Formatting.GREEN, cmd + "enabled " + !z.enabled, true))
				.append(" ").append(button("[Rename]", Formatting.YELLOW, cmd + "rename ", false))
				.append(" ").append(button("[Radius: " + z.radius + "]", Formatting.AQUA, cmd + "radius " + z.radius, false))
				.append(" ").append(button(z.column ? "[All heights: on]" : "[All heights: off]", Formatting.AQUA, cmd + "allheights " + !z.column, true))
				.append("\n ").append(button("[Move center here]", Formatting.AQUA, cmd + "movehere", true))
				.append(" ").append(button("[Delete]", Formatting.RED, cmd + "delete", false))
				.append("\n Entering: ").append(messageButton(z.enterMessage, cmd + "entermessage "))
				.append("\n Leaving: ").append(messageButton(z.leaveMessage, cmd + "leavemessage "))
				.append(Text.literal("\n {player}, {zone} and & colors work in messages. \"none\" = no message.").formatted(Formatting.GRAY));
		source.sendFeedback(() -> text, false);
		return 1;
	}

	private static MutableText messageButton(String message, String command) {
		Text shown = message.isEmpty() ? Text.literal("(none)").formatted(Formatting.GRAY) : Text.literal(colorize(message));
		return Text.empty().append(shown).append(" ").append(button("[Change]", Formatting.YELLOW, command + (message.isEmpty() ? "" : message), false));
	}

	private static MutableText button(String label, Formatting color, String command, boolean run) {
		return Text.literal(label).styled(s -> s.withColor(color)
				.withClickEvent(new ClickEvent(run ? ClickEvent.Action.RUN_COMMAND : ClickEvent.Action.SUGGEST_COMMAND, command))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(command))));
	}

	private static MutableText describe(ZonesState.Zone z) {
		String dim = switch (z.dimension) {
			case "minecraft:overworld" -> "Overworld";
			case "minecraft:the_nether" -> "Nether";
			case "minecraft:the_end" -> "End";
			default -> z.dimension;
		};
		MutableText text = Text.empty().append(Text.literal(z.name).formatted(Formatting.YELLOW));
		if (!z.enabled) text.append(Text.literal(" (off)").formatted(Formatting.RED));
		return text.append(": " + dim + " " + z.x + " " + z.y + " " + z.z + ", radius " + z.radius + (z.column ? " (all heights)" : ""));
	}

	private static MutableText prefix() {
		return Text.empty().append(Text.literal("Zones:").formatted(Formatting.DARK_GREEN)).append(" ");
	}

	private static int fail(ServerCommandSource source, String message) {
		source.sendError(Text.literal(message));
		return 0;
	}
}
