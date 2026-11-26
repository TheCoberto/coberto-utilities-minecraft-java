package com.thecoberto.utilities.kingdom;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Everything the Kingdom Board shows, taken at one moment. Compared with the last one to find what changed. */
record Snapshot(List<Faction> factions, Map<UUID, String> factionOf, List<Clan> clans) {

	/** One faction from kingdom-board.yml with its members' names (online ones first). */
	record Faction(String id, String name, String emoji, List<String> members, int online) {}

	record Clan(String tag, String name, List<String> leaders, List<String> members,
			int kills, int deaths, double kdr, double balance,
			List<String> allies, List<String> rivals, long founded) {}

	Faction faction(String id) {
		return factions.stream().filter(f -> f.id().equals(id)).findFirst().orElse(null);
	}

	Clan clan(String tag) {
		return clans.stream().filter(c -> c.tag().equals(tag)).findFirst().orElse(null);
	}
}
