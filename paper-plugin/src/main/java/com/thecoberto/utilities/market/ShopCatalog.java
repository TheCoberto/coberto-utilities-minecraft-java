package com.thecoberto.utilities.market;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every item in the EconomyShopGUI shop, read from its shops/*.yml files: what it is, its
 * readable name and its configured (base) prices. Used to announce what was added, removed
 * or repriced, and as the base that dynamic prices are compared with.
 */
final class ShopCatalog {
	/** buy or sell is -1 when the shop doesn't buy or sell it. */
	record Item(String key, String section, String loc, String name, double buy, double sell) {
		String saved() {
			return key + '\t' + name + '\t' + buy + '\t' + sell;
		}

		static Item fromSaved(String line) {
			String[] p = line.split("\t", -1);
			if (p.length < 4) return null;
			try {
				return new Item(p[0], "", "", p[1], Double.parseDouble(p[2]), Double.parseDouble(p[3]));
			} catch (NumberFormatException e) {
				return null;
			}
		}
	}

	/** By key: what the item is, which stays the same when items are moved around the pages. */
	final Map<String, Item> byKey;
	/** By section + "|" + page location, which is how a sale names its item. */
	final Map<String, Item> byLoc;

	private ShopCatalog(Map<String, Item> byKey, Map<String, Item> byLoc) {
		this.byKey = byKey;
		this.byLoc = byLoc;
	}

	static ShopCatalog empty() {
		return new ShopCatalog(Map.of(), Map.of());
	}

	static ShopCatalog read(File shopsDir) {
		Map<String, Item> byKey = new LinkedHashMap<>();
		Map<String, Item> byLoc = new HashMap<>();
		File[] files = shopsDir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml"));
		if (files == null) return new ShopCatalog(byKey, byLoc);
		Arrays.sort(files);
		for (File file : files) {
			String section = file.getName().replaceFirst("(?i)\\.yml$", "");
			ConfigurationSection pages = YamlConfiguration.loadConfiguration(file).getConfigurationSection("pages");
			if (pages == null) continue;
			for (String page : pages.getKeys(false)) {
				ConfigurationSection items = pages.getConfigurationSection(page + ".items");
				if (items == null) continue;
				for (String id : items.getKeys(false)) {
					ConfigurationSection c = items.getConfigurationSection(id);
					if (c == null || c.getString("material") == null) continue;
					String key = section + "|" + identity(c);
					String unique = key;
					for (int n = 2; byKey.containsKey(unique); n++) unique = key + "#" + n;
					String loc = page + ".items." + id;
					Item item = new Item(unique, section, loc, name(c), c.getDouble("buy", -1), c.getDouble("sell", -1));
					byKey.put(unique, item);
					byLoc.put(section + "|" + loc, item);
				}
			}
		}
		return new ShopCatalog(byKey, byLoc);
	}

	/** A sale names its item by section and location like "page1.items.2" (maybe with "pages." first). */
	Item at(String section, String loc) {
		if (section == null || loc == null) return null;
		return byLoc.get(section + "|" + loc.replaceFirst("^pages\\.", ""));
	}

	List<String> saved() {
		List<String> out = new ArrayList<>();
		for (Item i : byKey.values()) out.add(i.saved());
		return out;
	}

	static Map<String, Item> fromSaved(List<String> lines) {
		Map<String, Item> out = new LinkedHashMap<>();
		for (String line : lines) {
			Item i = Item.fromSaved(line);
			if (i != null) out.put(i.key(), i);
		}
		return out;
	}

	/** What makes two shop entries the same item. */
	private static String identity(ConfigurationSection c) {
		return String.join("|",
				c.getString("material", "").toUpperCase(Locale.ROOT),
				String.join(",", c.getStringList("potiontypes")).toLowerCase(Locale.ROOT),
				String.join(",", c.getStringList("enchantments")).toUpperCase(Locale.ROOT),
				c.getString("spawnertype", "").toLowerCase(Locale.ROOT),
				c.getString("instrument", "").toLowerCase(Locale.ROOT),
				plain(c.getString("name", "")));
	}

	// ---------------------------------------------------------------- names

	static String name(ConfigurationSection c) {
		String custom = plain(c.getString("name", c.getString("displayname", "")));
		if (!custom.isEmpty()) return custom;
		String material = c.getString("material", "").toUpperCase(Locale.ROOT);
		List<String> potions = c.getStringList("potiontypes");
		if (!potions.isEmpty() && (material.contains("POTION") || material.equals("TIPPED_ARROW"))) {
			String effect = pretty(potions.get(0));
			return switch (material) {
				case "SPLASH_POTION" -> "Splash Potion of " + effect;
				case "LINGERING_POTION" -> "Lingering Potion of " + effect;
				case "TIPPED_ARROW" -> "Arrow of " + effect;
				default -> "Potion of " + effect;
			};
		}
		List<String> enchants = c.getStringList("enchantments");
		if (!enchants.isEmpty() && material.equals("ENCHANTED_BOOK")) {
			List<String> parts = new ArrayList<>();
			for (String e : enchants) parts.add(enchantment(e));
			return String.join(", ", parts) + " Book";
		}
		String spawner = c.getString("spawnertype", "");
		if (!spawner.isEmpty()) return pretty(spawner) + " Spawner";
		String instrument = c.getString("instrument", "");
		if (!instrument.isEmpty()) return pretty(instrument) + " Goat Horn";
		return pretty(material);
	}

	/** "DAMAGE_ARTHROPODS:5" becomes "Bane of Arthropods V". */
	private static String enchantment(String spec) {
		String[] p = spec.split(":");
		String id = p[0].toUpperCase(Locale.ROOT);
		String name = switch (id) {
			case "DAMAGE_ALL" -> "Sharpness";
			case "DAMAGE_ARTHROPODS" -> "Bane of Arthropods";
			case "DAMAGE_UNDEAD" -> "Smite";
			case "DIG_SPEED" -> "Efficiency";
			case "DURABILITY" -> "Unbreaking";
			case "LOOT_BONUS_BLOCKS" -> "Fortune";
			case "LOOT_BONUS_MOBS" -> "Looting";
			case "PROTECTION_ENVIRONMENTAL" -> "Protection";
			case "PROTECTION_FIRE" -> "Fire Protection";
			case "PROTECTION_FALL" -> "Feather Falling";
			case "PROTECTION_EXPLOSIONS" -> "Blast Protection";
			case "PROTECTION_PROJECTILE" -> "Projectile Protection";
			case "OXYGEN" -> "Respiration";
			case "WATER_WORKER" -> "Aqua Affinity";
			case "ARROW_DAMAGE" -> "Power";
			case "ARROW_KNOCKBACK" -> "Punch";
			case "ARROW_FIRE" -> "Flame";
			case "ARROW_INFINITE" -> "Infinity";
			case "LUCK" -> "Luck of the Sea";
			case "BINDING_CURSE" -> "Curse of Binding";
			case "VANISHING_CURSE" -> "Curse of Vanishing";
			default -> pretty(id);
		};
		int level = 1;
		if (p.length > 1) {
			try {
				level = Integer.parseInt(p[1].trim());
			} catch (NumberFormatException ignored) {
			}
		}
		return level <= 1 && maxOne(id) ? name : name + " " + roman(level);
	}

	private static boolean maxOne(String id) {
		return switch (id) {
			case "WATER_WORKER", "ARROW_FIRE", "ARROW_INFINITE", "MENDING", "SILK_TOUCH", "CHANNELING", "MULTISHOT", "AQUA_AFFINITY", "FLAME", "INFINITY",
					"BINDING_CURSE", "VANISHING_CURSE" -> true;
			default -> false;
		};
	}

	private static String roman(int n) {
		String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
		return n >= 0 && n < r.length ? r[n] : String.valueOf(n);
	}

	/** "PALE_OAK_LOG" or "fire_resistance" becomes "Pale Oak Log" / "Fire Resistance". */
	static String pretty(String id) {
		StringBuilder out = new StringBuilder();
		for (String word : id.toLowerCase(Locale.ROOT).replace("minecraft:", "").split("[_\\s]+")) {
			if (word.isEmpty()) continue;
			if (!out.isEmpty()) out.append(' ');
			boolean small = !out.isEmpty() && (word.equals("of") || word.equals("the") || word.equals("on") || word.equals("a"));
			out.append(small ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1));
		}
		return out.toString();
	}

	/** Without &/§ colour codes and hex colours. */
	static String plain(String text) {
		if (text == null) return "";
		return text.replaceAll("(?i)[&§]#[0-9a-f]{6}", "").replaceAll("(?i)[&§]x([&§][0-9a-f]){6}", "")
				.replaceAll("(?i)[&§][0-9a-fk-or]", "").strip();
	}
}
