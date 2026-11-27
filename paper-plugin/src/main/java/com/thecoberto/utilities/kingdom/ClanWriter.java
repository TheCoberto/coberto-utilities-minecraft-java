package com.thecoberto.utilities.kingdom;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Reads whole clans out of SimpleClans and builds them back, for the Clan Archive. Like
 * ClanReader it goes through SimpleClans' public methods by name, so Utilities doesn't need
 * SimpleClans to build. Call on the main thread.
 */
final class ClanWriter {
	private ClanWriter() {}

	record SavedRank(String name, String display, Set<String> permissions) {}

	record SavedMember(UUID uuid, String name, boolean leader, boolean trusted, String rank) {}

	record Saved(String tag, String colorTag, String name, String description, long founded, boolean friendlyFire,
			Location home, String defaultRank, List<SavedRank> ranks, List<String> bulletin,
			List<String> allies, List<String> rivals, List<SavedMember> members) {}

	/** Who came back with the clan, and who couldn't because they're in another clan now. */
	record Result(List<String> added, List<String> busy) {}

	// ---------------------------------------------------------------- reading

	static List<Saved> readAll() throws ReflectiveOperationException {
		List<Saved> out = new ArrayList<>();
		for (Object clan : (Collection<?>) call(manager(), "getClans")) {
			List<SavedRank> ranks = new ArrayList<>();
			Object rankList = call(clan, "getRanks");
			if (rankList instanceof Collection<?> rs) {
				for (Object r : rs) {
					Set<String> perms = new HashSet<>();
					if (call(r, "getPermissions") instanceof Collection<?> ps) for (Object p : ps) perms.add(String.valueOf(p));
					ranks.add(new SavedRank(text(call(r, "getName")), text(call(r, "getDisplayName")), perms));
				}
			}
			List<SavedMember> members = new ArrayList<>();
			for (Object cp : (Collection<?>) call(clan, "getMembers")) {
				members.add(new SavedMember((UUID) call(cp, "getUniqueId"), text(call(cp, "getName")),
						(Boolean) call(cp, "isLeader"), (Boolean) call(cp, "isTrusted"), text(call(cp, "getRankId"))));
			}
			List<String> bulletin = new ArrayList<>();
			if (call(clan, "getBb") instanceof Collection<?> bb) for (Object line : bb) bulletin.add(String.valueOf(line));
			String colorTag = text(call(clan, "getColorTag"));
			out.add(new Saved(
					// The tag as players see it (TREE), not SimpleClans' lowercase key (tree)
					plain(colorTag).isBlank() ? text(call(clan, "getTag")) : plain(colorTag),
					colorTag,
					text(call(clan, "getName")),
					text(call(clan, "getDescription")),
					((Number) call(clan, "getFounded")).longValue(),
					(Boolean) call(clan, "isFriendlyFire"),
					(Location) call(clan, "getHomeLocation"),
					text(call(clan, "getDefaultRank")),
					ranks, bulletin,
					tags((Collection<?>) call(clan, "getAllies")),
					tags((Collection<?>) call(clan, "getRivals")),
					members));
		}
		return out;
	}

	static boolean inClan(UUID player) throws ReflectiveOperationException {
		return call(manager(), "getClanByPlayerUniqueId", player) != null;
	}

	static boolean tagTaken(String tag) throws ReflectiveOperationException {
		return clan(tag) != null;
	}

	// ---------------------------------------------------------------- restoring

	/**
	 * Builds the clan again with leader as its leader. The old leaders come back as leaders too,
	 * and every old member who isn't in another clan is added back with their rank.
	 */
	static Result restore(Saved saved, UUID leaderId, String leaderName) throws ReflectiveOperationException {
		Object manager = manager();
		String colorTag = saved.colorTag().isBlank() ? saved.tag() : saved.colorTag();
		Player online = Bukkit.getPlayer(leaderId);
		if (online != null) call(manager, "createClan", online, colorTag, saved.name());
		else createOffline(manager, colorTag, saved.name(), leaderId, leaderName);
		Object clan = clan(saved.tag());
		if (clan == null) throw new IllegalStateException("SimpleClans didn't create the clan [" + saved.tag() + "]");

		call(clan, "setVerified", true);
		if (saved.founded() > 0) call(clan, "setFounded", saved.founded());
		if (!saved.description().isBlank()) call(clan, "setDescription", saved.description());
		call(clan, "setFriendlyFire", saved.friendlyFire());
		if (saved.home() != null) call(clan, "setHomeLocation", saved.home());
		if (!saved.bulletin().isEmpty()) call(clan, "setBb", new ArrayList<>(saved.bulletin()));
		if (!saved.ranks().isEmpty()) {
			Class<?> rankClass = Class.forName("net.sacredlabyrinth.phaed.simpleclans.Rank", true, simpleClans().getClass().getClassLoader());
			Constructor<?> make = rankClass.getConstructor(String.class, String.class, Set.class);
			List<Object> ranks = new ArrayList<>();
			for (SavedRank r : saved.ranks()) ranks.add(make.newInstance(r.name(), r.display(), new HashSet<>(r.permissions())));
			call(clan, "setRanks", ranks);
			if (!saved.defaultRank().isBlank()) call(clan, "setDefaultRank", saved.defaultRank());
		}

		Object storage = call(simpleClans(), "getStorageManager");
		List<String> added = new ArrayList<>(), busy = new ArrayList<>();
		for (SavedMember m : saved.members()) {
			Object cp;
			if (m.uuid().equals(leaderId)) {
				cp = call(manager, "getClanPlayer", leaderId);
				if (cp == null) continue;
			} else {
				if (inClan(m.uuid())) {
					busy.add(m.name());
					continue;
				}
				cp = clanPlayer(manager, m.uuid(), m.name());
				call(clan, "addPlayerToClan", cp);
				added.add(m.name());
			}
			call(cp, "setLeader", m.leader() || m.uuid().equals(leaderId));
			call(cp, "setTrusted", m.trusted() || m.leader());
			if (!m.rank().isBlank()) call(cp, "setRank", m.rank());
			call(storage, "updateClanPlayer", cp);
		}
		// The restorer leads it even when they weren't a leader before (or weren't in it, for staff restores).
		Object me = call(manager, "getClanPlayer", leaderId);
		if (me != null) {
			call(me, "setLeader", true);
			call(storage, "updateClanPlayer", me);
		}

		// addAlly and addRival link both clans.
		for (String tag : saved.allies()) {
			Object other = clan(tag);
			if (other == null || other == clan || listed(clan, "getAllies", tag)) continue;
			call(clan, "addAlly", other);
			call(storage, "updateClan", other);
		}
		for (String tag : saved.rivals()) {
			Object other = clan(tag);
			if (other == null || other == clan || listed(clan, "getRivals", tag)) continue;
			call(clan, "addRival", other);
			call(storage, "updateClan", other);
		}
		call(storage, "updateClan", clan);
		try {
			call(call(simpleClans(), "getPermissionsManager"), "updateClanPermissions", clan);
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// only matters for clan permission groups, which this server doesn't use
		}
		return new Result(added, busy);
	}

	/**
	 * What SimpleClans' createClan does, for a leader who isn't online (createClan needs a Player):
	 * a verified clan with the leader in it and the starter ranks, saved and loaded.
	 */
	private static void createOffline(Object manager, String colorTag, String name, UUID leaderId, String leaderName)
			throws ReflectiveOperationException {
		Object cp = clanPlayer(manager, leaderId, leaderName);
		Class<?> clanClass = Class.forName("net.sacredlabyrinth.phaed.simpleclans.Clan", true, simpleClans().getClass().getClassLoader());
		Object clan = clanClass.getConstructor(String.class, String.class, boolean.class).newInstance(colorTag, name, true);
		call(clan, "addPlayerToClan", cp);
		call(cp, "setLeader", true);
		if (call(clan, "getRanks") instanceof Collection<?> ranks && ranks.isEmpty()
				&& call(call(simpleClans(), "getSettingsManager"), "getStarterRanks") instanceof Collection<?> starter) {
			@SuppressWarnings("unchecked") Collection<Object> into = (Collection<Object>) ranks;
			into.addAll(starter);
		}
		Object storage = call(simpleClans(), "getStorageManager");
		call(storage, "insertClan", clan);
		call(manager, "importClan", clan);
		call(storage, "updateClanPlayer", cp);
	}

	/**
	 * The player's SimpleClans record, made if needed. SimpleClans names a new record after the
	 * server's cached name, or "null" when it has none, and names must be unique; so when the
	 * server doesn't know the name, the record is made here with the archived name instead.
	 */
	private static Object clanPlayer(Object manager, UUID uuid, String archivedName) throws ReflectiveOperationException {
		Object existing = call(manager, "getAnyClanPlayer", uuid);
		if (existing != null || Bukkit.getOfflinePlayer(uuid).getName() != null) {
			Object cp = existing != null ? existing : call(manager, "getCreateClanPlayer", uuid);
			if (!knownName(text(call(cp, "getName"))) && knownName(archivedName)) call(cp, "setName", archivedName);
			return cp;
		}
		Class<?> type = Class.forName("net.sacredlabyrinth.phaed.simpleclans.ClanPlayer", true, simpleClans().getClass().getClassLoader());
		Object cp = type.getConstructor(UUID.class).newInstance(uuid);
		call(cp, "setName", archivedName);
		call(manager, "importClanPlayer", cp);
		call(call(simpleClans(), "getStorageManager"), "insertClanPlayer", cp);
		return cp;
	}

	static boolean knownName(String name) {
		return name != null && !name.isBlank() && !name.equals("null");
	}

	/** Whether the clan already lists this tag as an ally or rival. */
	private static boolean listed(Object clan, String getter, String tag) throws ReflectiveOperationException {
		for (Object t : (Collection<?>) call(clan, getter)) if (plain(String.valueOf(t)).equalsIgnoreCase(tag)) return true;
		return false;
	}

	// ---------------------------------------------------------------- reflection

	private static Plugin simpleClans() {
		Plugin p = Bukkit.getPluginManager().getPlugin("SimpleClans");
		if (p == null || !p.isEnabled()) throw new IllegalStateException("SimpleClans isn't running");
		return p;
	}

	private static Object manager() throws ReflectiveOperationException {
		return call(simpleClans(), "getClanManager");
	}

	private static Object clan(String tag) throws ReflectiveOperationException {
		return call(manager(), "getClan", tag.toLowerCase(Locale.ROOT));
	}

	private static List<String> tags(Collection<?> tags) {
		LinkedHashSet<String> unique = new LinkedHashSet<>();
		for (Object t : tags) unique.add(plain(String.valueOf(t)).toUpperCase(Locale.ROOT));
		return new ArrayList<>(unique);
	}

	private static String text(Object value) {
		return value == null ? "" : value.toString();
	}

	private static String plain(String text) {
		return text.replaceAll("[§&][0-9a-fk-orx]", "").trim();
	}

	/** Calls the public method with this name whose parameters fit the arguments. */
	private static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
		for (Method m : target.getClass().getMethods()) {
			if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
			if (fits(m.getParameterTypes(), args)) return m.invoke(target, args);
		}
		throw new NoSuchMethodException(target.getClass().getSimpleName() + "." + name + " with " + args.length + " argument(s)");
	}

	private static boolean fits(Class<?>[] types, Object[] args) {
		for (int i = 0; i < types.length; i++) {
			Class<?> t = types[i];
			Object a = args[i];
			if (a == null) {
				if (t.isPrimitive()) return false;
				continue;
			}
			if (t.isPrimitive()) {
				if (t == boolean.class && a instanceof Boolean) continue;
				if (t == long.class && a instanceof Long) continue;
				if (t == int.class && a instanceof Integer) continue;
				if (t == double.class && a instanceof Double) continue;
				return false;
			}
			if (!t.isInstance(a)) return false;
		}
		return true;
	}
}
