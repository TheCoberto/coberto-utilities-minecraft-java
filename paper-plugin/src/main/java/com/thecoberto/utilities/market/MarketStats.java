package com.thecoberto.utilities.market;

import org.bukkit.configuration.ConfigurationSection;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Today's trading: totals, per item and per player. Starts over at midnight (server time). Main thread only. */
final class MarketStats {
	/** An amount of items and the money they went for. */
	static final class Tally {
		long amount;
		double money;
	}

	String day = LocalDate.now().toString();
	int trades;
	double spent, earned;
	final Map<String, Tally> bought = new HashMap<>(), sold = new HashMap<>();
	/** Player name -> money traded (spent + earned). */
	final Map<String, Double> traders = new HashMap<>();

	/** Starts a new day when the date changed. True if it did. */
	boolean roll() {
		String today = LocalDate.now().toString();
		if (today.equals(day)) return false;
		day = today;
		trades = 0;
		spent = earned = 0;
		bought.clear();
		sold.clear();
		traders.clear();
		return true;
	}

	void add(String player, boolean buy, Map<String, Integer> items, double price) {
		roll();
		trades++;
		if (buy) spent += price;
		else earned += price;
		traders.merge(player, price, Double::sum);
		long total = items.values().stream().mapToLong(Integer::longValue).sum();
		for (Map.Entry<String, Integer> e : items.entrySet()) {
			Tally t = (buy ? bought : sold).computeIfAbsent(e.getKey(), k -> new Tally());
			t.amount += e.getValue();
			// A mixed sale (sell-all) only has one total; share it out by amount.
			t.money += total == 0 ? 0 : price * e.getValue() / total;
		}
	}

	static List<Map.Entry<String, Tally>> top(Map<String, Tally> tallies, int n) {
		List<Map.Entry<String, Tally>> list = new ArrayList<>(tallies.entrySet());
		list.sort(Comparator.comparingDouble((Map.Entry<String, Tally> e) -> e.getValue().money).reversed()
				.thenComparing(Map.Entry::getKey));
		return list.subList(0, Math.min(n, list.size()));
	}

	List<Map.Entry<String, Double>> topTraders(int n) {
		List<Map.Entry<String, Double>> list = new ArrayList<>(traders.entrySet());
		list.sort(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry::getKey));
		return list.subList(0, Math.min(n, list.size()));
	}

	// ---------------------------------------------------------------- saving

	void save(ConfigurationSection to) {
		to.set("day", day);
		to.set("trades", trades);
		to.set("spent", spent);
		to.set("earned", earned);
		to.set("bought", tallies(bought));
		to.set("sold", tallies(sold));
		// A list, not a section: Bedrock names start with "." which YAML paths can't hold.
		List<String> list = new ArrayList<>();
		traders.forEach((name, money) -> list.add(money + "\t" + name));
		to.set("traders", list);
	}

	void load(ConfigurationSection from) {
		if (from == null) return;
		day = from.getString("day", day);
		trades = from.getInt("trades");
		spent = from.getDouble("spent");
		earned = from.getDouble("earned");
		read(from.getStringList("bought"), bought);
		read(from.getStringList("sold"), sold);
		for (String line : from.getStringList("traders")) {
			String[] p = line.split("\t", 2);
			try {
				if (p.length == 2) traders.put(p[1], Double.parseDouble(p[0]));
			} catch (NumberFormatException ignored) {
			}
		}
		roll();
	}

	private static List<String> tallies(Map<String, Tally> map) {
		List<String> out = new ArrayList<>();
		map.forEach((name, t) -> out.add(t.amount + "\t" + t.money + "\t" + name));
		return out;
	}

	private static void read(List<String> lines, Map<String, Tally> into) {
		for (String line : lines) {
			String[] p = line.split("\t", 3);
			if (p.length < 3) continue;
			try {
				Tally t = new Tally();
				t.amount = Long.parseLong(p[0]);
				t.money = Double.parseDouble(p[1]);
				into.put(p[2], t);
			} catch (NumberFormatException ignored) {
			}
		}
	}
}
