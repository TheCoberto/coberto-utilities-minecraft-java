package com.thecoberto.utilities.kingdom;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Reads clans from SimpleClans through its public methods, by name, so Utilities doesn't need
 * SimpleClans to build or run. Call on the main thread.
 */
final class ClanReader {
	private ClanReader() {}

	static boolean available() {
		Plugin p = Bukkit.getPluginManager().getPlugin("SimpleClans");
		return p != null && p.isEnabled();
	}

	static List<Snapshot.Clan> read() throws ReflectiveOperationException {
		Plugin simpleClans = Bukkit.getPluginManager().getPlugin("SimpleClans");
		Object manager = call(simpleClans, "getClanManager");
		List<Snapshot.Clan> clans = new ArrayList<>();
		for (Object clan : (Collection<?>) call(manager, "getClans")) {
			List<String> leaders = names((Collection<?>) call(clan, "getLeaders"));
			List<String> members = names((Collection<?>) call(clan, "getMembers"));
			clans.add(new Snapshot.Clan(
					plain(call(clan, "getTag")).toUpperCase(Locale.ROOT),
					plain(call(clan, "getName")),
					leaders,
					members,
					((Number) call(clan, "getTotalKills")).intValue(),
					((Number) call(clan, "getTotalDeaths")).intValue(),
					((Number) call(clan, "getTotalKDR")).doubleValue(),
					((Number) call(clan, "getBalance")).doubleValue(),
					tags((Collection<?>) call(clan, "getAllies")),
					tags((Collection<?>) call(clan, "getRivals")),
					((Number) call(clan, "getFounded")).longValue()));
		}
		return clans;
	}

	private static List<String> names(Collection<?> clanPlayers) throws ReflectiveOperationException {
		List<String> names = new ArrayList<>();
		for (Object cp : clanPlayers) names.add(plain(call(cp, "getName")));
		names.sort(String.CASE_INSENSITIVE_ORDER);
		return names;
	}

	/** Ally and rival tags; SimpleClans can list the same one twice. */
	private static List<String> tags(Collection<?> tags) {
		LinkedHashSet<String> unique = new LinkedHashSet<>();
		for (Object t : tags) unique.add(plain(t).toUpperCase(Locale.ROOT));
		return new ArrayList<>(unique);
	}

	private static String plain(Object text) {
		return text == null ? "" : text.toString().replaceAll("[§&][0-9a-fk-orx]", "").trim();
	}

	private static Object call(Object target, String method) throws ReflectiveOperationException {
		Method m = target.getClass().getMethod(method);
		return m.invoke(target);
	}
}
