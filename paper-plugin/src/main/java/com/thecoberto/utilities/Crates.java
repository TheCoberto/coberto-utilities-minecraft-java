package com.thecoberto.utilities;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Loot crates: Crate, Spooky Crate and Holiday Crate blocks to hide around the
 * map for events. Opening one pops out random loot and the crate disappears;
 * breaking it gives nothing. Operators change what each crate drops with
 * /crates.
 */
public final class Crates implements TabExecutor {
	private record CrateKind(String key, String name, NamedTextColor color, Particle particle, Material material)
			implements SpecialBlocks.Kind {
		@Override public String id() { return key; }
		@Override public String lore() { return "Hide it for players to find. Using it pops out loot."; }
		@Override public Material defaultMaterial() { return material; }
		@Override public boolean dropsItem() { return false; }
		@Override public void use(Player player, Block block, boolean sneak) { instance.open(block, player, this); }
	}

	private static Crates instance;

	private static final class Entry {
		String item; // like minecraft:diamond
		int weight;
		int min;
		int max;

		Entry(String item, int weight, int min, int max) {
			this.item = item;
			this.weight = weight;
			this.min = min;
			this.max = max;
		}
	}

	private static final class Crate {
		int rollsMin;
		int rollsMax;
		List<Entry> loot = new ArrayList<>();
	}

	private final SpecialBlocks blocks;
	private final Store store;
	private final Map<String, CrateKind> kinds = new LinkedHashMap<>();
	private final Map<String, Crate> crates = new HashMap<>();

	Crates(JavaPlugin plugin, SpecialBlocks blocks) {
		instance = this;
		this.blocks = blocks;
		for (CrateKind kind : List.of(
				new CrateKind("crate", "Crate", NamedTextColor.GOLD, Particle.HAPPY_VILLAGER, Material.BARREL),
				new CrateKind("spooky_crate", "Spooky Crate", NamedTextColor.DARK_PURPLE, Particle.SOUL, Material.CARVED_PUMPKIN),
				new CrateKind("holiday_crate", "Holiday Crate", NamedTextColor.RED, Particle.TOTEM_OF_UNDYING, Material.SNOW_BLOCK))) {
			kinds.put(kind.key(), kind);
			blocks.register(kind);
		}
		store = new Store(plugin, "crates.yml");
		ConfigurationSection section = store.yaml.getConfigurationSection("crates");
		if (section != null) {
			for (String key : section.getKeys(false)) {
				ConfigurationSection c = section.getConfigurationSection(key);
				Crate crate = new Crate();
				crate.rollsMin = Math.max(1, c.getInt("rollsMin", 1));
				crate.rollsMax = Math.max(crate.rollsMin, c.getInt("rollsMax", crate.rollsMin));
				for (Map<?, ?> e : c.getMapList("loot")) {
					int min = Math.max(1, ((Number) e.get("min")).intValue());
					crate.loot.add(new Entry((String) e.get("item"), ((Number) e.get("weight")).intValue(), min,
							Math.max(min, ((Number) e.get("max")).intValue())));
				}
				crates.put(key, crate);
			}
		}
		plugin.getCommand("crates").setExecutor(this);
		plugin.getCommand("crates").setTabCompleter(this);
	}

	private void save() {
		store.yaml.set("crates", null);
		crates.forEach((key, crate) -> {
			store.yaml.set("crates." + key + ".rollsMin", crate.rollsMin);
			store.yaml.set("crates." + key + ".rollsMax", crate.rollsMax);
			List<Map<String, Object>> loot = new ArrayList<>();
			for (Entry e : crate.loot) loot.add(Map.of("item", e.item, "weight", e.weight, "min", e.min, "max", e.max));
			store.yaml.set("crates." + key + ".loot", loot);
		});
		store.save();
	}

	private static List<Entry> commonLoot() {
		return new ArrayList<>(List.of(
				new Entry("minecraft:diamond", 20, 1, 3),
				new Entry("minecraft:emerald", 25, 2, 6),
				new Entry("minecraft:gold_ingot", 30, 3, 8),
				new Entry("minecraft:golden_carrot", 30, 4, 12),
				new Entry("minecraft:totem_of_undying", 6, 1, 1),
				new Entry("minecraft:shulker_box", 4, 1, 1),
				new Entry("minecraft:elytra", 1, 1, 1),
				// Mace parts
				new Entry("minecraft:heavy_core", 1, 1, 1),
				new Entry("minecraft:breeze_rod", 3, 1, 3)));
	}

	private static Crate defaults(String key) {
		Crate crate = new Crate();
		crate.loot = commonLoot();
		switch (key) {
			case "spooky_crate" -> {
				crate.rollsMin = 3;
				crate.rollsMax = 5;
				crate.loot.add(new Entry("minecraft:carved_pumpkin", 15, 1, 2));
				crate.loot.add(new Entry("minecraft:jack_o_lantern", 10, 1, 2));
			}
			case "holiday_crate" -> {
				crate.rollsMin = 3;
				crate.rollsMax = 5;
				crate.loot.add(new Entry("minecraft:cookie", 15, 4, 12));
				crate.loot.add(new Entry("minecraft:snowball", 15, 8, 16));
			}
			default -> {
				crate.rollsMin = 2;
				crate.rollsMax = 4;
			}
		}
		return crate;
	}

	private Crate crate(String key) {
		return crates.computeIfAbsent(key, Crates::defaults);
	}

	private static Component itemName(Material material) {
		return Component.translatable(material.translationKey());
	}

	// ---------- Opening a crate ----------

	private void open(Block block, Player player, CrateKind kind) {
		Crate crate = crate(kind.key());
		Location center = SpecialBlocks.center(block);
		block.setType(Material.AIR);
		blocks.remove(block);
		block.getWorld().playSound(center, Sound.BLOCK_CHEST_OPEN, SoundCategory.BLOCKS, 1f, 1f);
		block.getWorld().playSound(center, Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.BLOCKS, 0.6f, 1.4f);
		block.getWorld().spawnParticle(kind.particle(), center, 20, 0.4, 0.4, 0.4, 0.05);

		List<Component> found = new ArrayList<>();
		ThreadLocalRandom random = ThreadLocalRandom.current();
		for (Entry drop : roll(crate, random)) {
			Material material = Material.matchMaterial(drop.item);
			if (material == null || !material.isItem() || material.isAir()) continue;
			int amount = drop.min + random.nextInt(drop.max - drop.min + 1);
			int left = amount;
			while (left > 0) {
				int count = Math.min(left, material.getMaxStackSize());
				block.getWorld().dropItemNaturally(center, new ItemStack(material, count));
				left -= count;
			}
			found.add(Component.text(amount + "x ").append(itemName(material)));
			if (material == Material.ELYTRA || material == Material.HEAVY_CORE) {
				Bukkit.broadcast(Component.text(player.getName() + " found ", NamedTextColor.GOLD)
						.append(itemName(material).color(NamedTextColor.LIGHT_PURPLE))
						.append(Component.text(" in a " + kind.name() + "!", NamedTextColor.GOLD)));
			}
		}

		Component message = Component.text(kind.name() + ":", kind.color()).append(Component.text(" You found ", NamedTextColor.WHITE));
		if (found.isEmpty()) message = message.append(Component.text("nothing... it was empty!"));
		for (int i = 0; i < found.size(); i++) {
			if (i > 0) message = message.append(Component.text(", "));
			message = message.append(found.get(i));
		}
		player.sendMessage(message);
	}

	private static List<Entry> roll(Crate crate, ThreadLocalRandom random) {
		List<Entry> drops = new ArrayList<>();
		int total = crate.loot.stream().mapToInt(e -> Math.max(0, e.weight)).sum();
		if (total <= 0) return drops;
		int rolls = crate.rollsMin + random.nextInt(crate.rollsMax - crate.rollsMin + 1);
		for (int i = 0; i < rolls; i++) {
			int pick = random.nextInt(total);
			for (Entry entry : crate.loot) {
				if (entry.weight <= 0) continue;
				pick -= entry.weight;
				if (pick < 0) {
					drops.add(entry);
					break;
				}
			}
		}
		return drops;
	}

	// ---------- Commands (/crates) ----------

	private static Component prefix() {
		return Msg.prefix("Crates:", NamedTextColor.GOLD);
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length == 0) {
			Component text = Component.text("Loot Crates", NamedTextColor.GOLD)
					.append(Component.text(": click one to see and change what it drops. Get them with /utilitiesgive.", NamedTextColor.WHITE));
			for (CrateKind kind : kinds.values()) {
				text = text.append(Component.text("\n ")).append(Msg.button("[" + kind.name() + "]", kind.color(), "/crates " + kind.key(), true));
			}
			sender.sendMessage(text);
			return true;
		}
		CrateKind kind = kinds.get(args[0].toLowerCase(Locale.ROOT));
		if (kind == null) {
			Msg.error(sender, "There's no crate called that. Use crate, spooky_crate or holiday_crate.");
			return true;
		}
		if (args.length == 1) {
			show(sender, kind);
			return true;
		}
		Crate crate = crate(kind.key());
		switch (args[1].toLowerCase(Locale.ROOT)) {
			case "add" -> {
				if (args.length < 4 || args.length > 6) return false;
				Material material = Material.matchMaterial(args[2]);
				Integer weight = Msg.parseInt(args[3], 0, 1_000_000);
				Integer min = args.length >= 5 ? Msg.parseInt(args[4], 1, 10_000) : Integer.valueOf(1);
				Integer max = args.length == 6 ? Msg.parseInt(args[5], 1, 10_000) : min;
				if (material == null || !material.isItem() || material.isAir()) {
					Msg.error(sender, "No item called " + args[2] + ".");
					return true;
				}
				if (weight == null || min == null || max == null) return false;
				if (min > max) {
					Msg.error(sender, "The least amount can't be more than the most.");
					return true;
				}
				String id = material.getKey().toString();
				Entry entry = crate.loot.stream().filter(e -> e.item.equals(id)).findFirst().orElse(null);
				if (entry == null) crate.loot.add(entry = new Entry(id, weight, min, max));
				else {
					entry.weight = weight;
					entry.min = min;
					entry.max = max;
				}
				save();
				sender.sendMessage(prefix().append(Component.text(kind.name() + " now drops ")).append(itemName(material))
						.append(Component.text(" (" + chance(crate, entry) + " each pick).")));
			}
			case "remove" -> {
				if (args.length != 3) return false;
				Material material = Material.matchMaterial(args[2]);
				String id = material == null ? args[2] : material.getKey().toString();
				if (!crate.loot.removeIf(e -> e.item.equals(id))) {
					Msg.error(sender, "The " + kind.name() + " doesn't drop that.");
					return true;
				}
				save();
				sender.sendMessage(prefix().append(Component.text("Removed "))
						.append(material == null ? Component.text(id) : itemName(material))
						.append(Component.text(" from the " + kind.name() + ".")));
			}
			case "items" -> {
				if (args.length != 4) return false;
				Integer a = Msg.parseInt(args[2], 1, 27);
				Integer b = Msg.parseInt(args[3], 1, 27);
				if (a == null || b == null) return false;
				crate.rollsMin = Math.min(a, b);
				crate.rollsMax = Math.max(a, b);
				save();
				sender.sendMessage(prefix().append(Component.text(kind.name() + " now gives " + crate.rollsMin + "-" + crate.rollsMax + " items.")));
			}
			case "reset" -> {
				crates.put(kind.key(), defaults(kind.key()));
				save();
				sender.sendMessage(prefix().append(Component.text(kind.name() + " reset to the default loot.")));
			}
			default -> {
				return false;
			}
		}
		return true;
	}

	private void show(CommandSender sender, CrateKind kind) {
		Crate crate = crate(kind.key());
		String cmd = "/crates " + kind.key();
		Component text = Component.text(kind.name(), kind.color())
				.append(Component.text(" gives " + crate.rollsMin + "-" + crate.rollsMax + " items when opened. Each item is picked by chance.", NamedTextColor.WHITE));
		for (Entry entry : crate.loot) {
			Material material = Material.matchMaterial(entry.item);
			Component name = material == null ? Component.text(entry.item) : itemName(material);
			String amount = entry.min == entry.max ? "" + entry.min : entry.min + "-" + entry.max;
			text = text.append(Component.text("\n "))
					.append(name.append(Component.text(" x" + amount)).color(NamedTextColor.YELLOW)
							.clickEvent(ClickEvent.suggestCommand(cmd + " add " + entry.item + " " + entry.weight + " " + entry.min + " " + entry.max))
							.hoverEvent(HoverEvent.showText(Component.text("Click to change it: weight, then least and most amount"))))
					.append(Component.text(" " + chance(crate, entry) + " each pick (weight " + entry.weight + ") ", NamedTextColor.GRAY))
					.append(Msg.button("[x]", NamedTextColor.RED, cmd + " remove " + entry.item, "Remove it", false));
		}
		text = text.append(Component.text("\n "))
				.append(Msg.button("[+ Add item]", NamedTextColor.DARK_GREEN, cmd + " add ", cmd + " add <item> <weight> [min] [max]", false))
				.append(Component.text(" "))
				.append(Msg.button("[Items per crate]", NamedTextColor.AQUA, cmd + " items " + crate.rollsMin + " " + crate.rollsMax, cmd + " items <least> <most>", false))
				.append(Component.text(" "))
				.append(Msg.button("[Reset]", NamedTextColor.RED, cmd + " reset", "Put the loot back to the defaults", false));
		sender.sendMessage(text);
	}

	private static String chance(Crate crate, Entry entry) {
		int total = crate.loot.stream().mapToInt(e -> Math.max(0, e.weight)).sum();
		if (total <= 0 || entry.weight <= 0) return "0%";
		double pct = entry.weight * 100.0 / total;
		return String.format(Locale.US, pct < 1 ? "%.2f%%" : "%.1f%%", pct);
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length == 1) return Msg.matching(new ArrayList<>(kinds.keySet()), args[0]);
		if (args.length == 2) return Msg.matching(List.of("add", "remove", "items", "reset"), args[1]);
		if (args.length == 3 && args[1].equalsIgnoreCase("remove")) {
			Crate crate = crates.get(args[0].toLowerCase(Locale.ROOT));
			return crate == null ? List.of() : Msg.matching(crate.loot.stream().map(e -> e.item).toList(), args[2]);
		}
		if (args.length == 3 && args[1].equalsIgnoreCase("add")) {
			String typed = args[2].toLowerCase(Locale.ROOT).replace("minecraft:", "");
			return java.util.Arrays.stream(Material.values()).filter(m -> m.isItem() && !m.isAir() && !m.isLegacy())
					.map(m -> m.getKey().getKey()).filter(k -> k.startsWith(typed)).limit(50).toList();
		}
		return List.of();
	}
}
