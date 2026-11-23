package com.thecoberto.utilities;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.Location;

import java.util.List;
import java.util.Locale;

/** Chat helpers shared by the features. */
public final class Msg {
	private Msg() {}

	public static Component prefix(String label, TextColor color) {
		return Component.text(label, color).append(Component.text(" ", NamedTextColor.WHITE));
	}

	/** A clickable chat button. run = run the command; otherwise put it in the chat box to edit. */
	public static Component button(String label, TextColor color, String command, String hover, boolean run) {
		return Component.text(label, color)
				.clickEvent(run ? ClickEvent.runCommand(command) : ClickEvent.suggestCommand(command))
				.hoverEvent(HoverEvent.showText(Component.text(hover == null ? command : hover)));
	}

	public static Component button(String label, TextColor color, String command, boolean run) {
		return button(label, color, command, null, run);
	}

	/** [✔ label] / [✘ label] that runs command. */
	public static Component toggle(String label, boolean on, String command) {
		return button("[" + (on ? "✔ " : "✘ ") + label + "]", on ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY, command, true);
	}

	/** &a and the like become colors. */
	public static Component legacy(String text) {
		return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
	}

	public static String plural(long n, String word) {
		return n + " " + word + (n == 1 ? "" : "s");
	}

	public static String time(int seconds) {
		return seconds / 60 + ":" + String.format(Locale.US, "%02d", seconds % 60);
	}

	public static void error(CommandSender sender, String message) {
		sender.sendMessage(Component.text(message, NamedTextColor.RED));
	}

	/** Where a command runs from: the player, or the command block. */
	public static Location where(CommandSender sender) {
		if (sender instanceof Entity entity) return entity.getLocation();
		if (sender instanceof BlockCommandSender block) return block.getBlock().getLocation().add(0.5, 0, 0.5);
		return null;
	}

	public static Integer parseInt(String text, int min, int max) {
		try {
			int value = Integer.parseInt(text);
			return value < min || value > max ? null : value;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	public static Boolean parseBool(String text) {
		return switch (text.toLowerCase(Locale.ROOT)) {
			case "true", "on", "yes" -> true;
			case "false", "off", "no" -> false;
			default -> null;
		};
	}

	/** Tab completion: the options that start with what's typed. */
	public static List<String> matching(List<String> options, String typed) {
		String lower = typed.toLowerCase(Locale.ROOT);
		return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
	}

	public static String join(String[] args, int from) {
		return String.join(" ", java.util.Arrays.copyOfRange(args, from, args.length)).trim();
	}
}
