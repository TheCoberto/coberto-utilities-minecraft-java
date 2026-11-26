package com.thecoberto.utilities.kingdom;

import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.matcher.NodeMatcher;
import net.luckperms.api.node.types.MetaNode;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Which faction each player is in, from LuckPerms: their "faction" meta (set by the faction menus),
 * or else one of the faction's groups. One storage query per update, so it doesn't load every
 * player who has ever joined. Only loaded when LuckPerms is installed.
 */
final class FactionLookup {
	private FactionLookup() {}

	/** factions: id -> its groups. Answers player -> faction id. */
	static CompletableFuture<Map<UUID, String>> lookup(Map<String, List<String>> factions) {
		var users = LuckPermsProvider.get().getUserManager();
		CompletableFuture<Map<UUID, Collection<MetaNode>>> meta = users.searchAll(NodeMatcher.metaKey("faction"));
		Map<String, CompletableFuture<Map<UUID, Collection<Node>>>> groups = new HashMap<>();
		factions.forEach((id, groupNames) -> groupNames.forEach(group ->
				groups.put(group.toLowerCase(Locale.ROOT) + "\0" + id, users.searchAll(NodeMatcher.key("group." + group.toLowerCase(Locale.ROOT))))));

		return CompletableFuture.allOf(groups.values().toArray(new CompletableFuture[0])).thenCombine(meta, (ignored, metaResult) -> {
			Map<UUID, String> result = new HashMap<>();
			// Groups first, so the meta (what the menus set most recently) wins.
			groups.forEach((key, future) -> {
				String id = key.substring(key.indexOf('\0') + 1);
				future.join().forEach((uuid, nodes) -> {
					if (nodes.stream().anyMatch(n -> !n.hasExpired() && n.getValue() && n.getContexts().isEmpty())) result.put(uuid, id);
				});
			});
			metaResult.forEach((uuid, nodes) -> nodes.stream()
					.filter(n -> !n.hasExpired() && n.getContexts().isEmpty())
					.map(n -> match(n.getMetaValue(), factions))
					.filter(id -> id != null)
					.findFirst()
					.ifPresent(id -> result.put(uuid, id)));
			return result;
		});
	}

	/** "Azure", "azure" or "Azure Order" all mean the faction with id azure. */
	private static String match(String value, Map<String, List<String>> factions) {
		if (value == null) return null;
		String v = value.trim().toLowerCase(Locale.ROOT);
		for (String id : factions.keySet()) {
			if (v.equals(id) || v.startsWith(id + " ")) return id;
		}
		return null;
	}
}
