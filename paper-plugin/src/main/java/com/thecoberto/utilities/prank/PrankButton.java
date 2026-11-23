package com.thecoberto.utilities.prank;

import com.thecoberto.utilities.Labels;
import com.thecoberto.utilities.Msg;
import com.thecoberto.utilities.SpecialBlocks;
import com.thecoberto.utilities.Store;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Prank Button: a red button with a floating sign above it saying something
 * like "Don't Push This Button". Whoever pushes it gets pranked. The player who
 * placed it (or an operator) sneaks and uses it with an empty hand to change
 * the message, the prank, the cooldown and so on, in clickable chat.
 */
public final class PrankButton implements SpecialBlocks.Kind, TabExecutor {
	static final String LABEL_TAG = "coberto_prank_label";
	static final double LABEL_HEIGHT = 0.9;
	static final String DEFAULT_MESSAGE = "Don't Push This Button";
	private static final String ADMIN = "cobertoutilities.admin";

	/** One button's setup, saved in prank.yml. */
	static final class Setup {
		String owner = "";
		UUID ownerId;
		String message = DEFAULT_MESSAGE;
		String prank = "anvils";
		int cooldown = 5; // seconds before the button works again
		boolean ownerSafe = true; // the owner can press it without getting pranked
		boolean announce = true; // tell everyone nearby who pressed it
		int anvilHeight = 25; // blocks above them the anvil shower drops from
		boolean anvilsKill = true; // anvils kill even through full netherite with Protection
		int chickenCount = 12;
		int chickenSeconds = 20;
		String peck = "medium";
		int launchHeight = 30;
		int slowFallSeconds = 30;
		/** Server tick it was last pressed (not saved). */
		long lastPress = Long.MIN_VALUE;

		boolean isOwner(UUID player) {
			return ownerId != null && ownerId.equals(player);
		}
	}

	private final SpecialBlocks blocks;
	private final Store store;
	private final Map<String, Setup> setups = new HashMap<>();

	public PrankButton(JavaPlugin plugin, SpecialBlocks blocks) {
		this.blocks = blocks;
		blocks.register(this);
		new Pranks(plugin);
		store = new Store(plugin, "prank.yml");
		ConfigurationSection section = store.yaml.getConfigurationSection("buttons");
		if (section != null) {
			for (String key : section.getKeys(false)) {
				ConfigurationSection c = section.getConfigurationSection(key);
				Setup s = new Setup();
				s.owner = c.getString("owner", "");
				String id = c.getString("ownerId");
				s.ownerId = id == null ? null : UUID.fromString(id);
				s.message = c.getString("message", DEFAULT_MESSAGE);
				s.prank = c.getString("prank", "anvils");
				s.cooldown = c.getInt("cooldown", 5);
				s.ownerSafe = c.getBoolean("ownerSafe", true);
				s.announce = c.getBoolean("announce", true);
				s.anvilHeight = c.getInt("anvilHeight", 25);
				s.anvilsKill = c.getBoolean("anvilsKill", true);
				s.chickenCount = c.getInt("chickenCount", 12);
				s.chickenSeconds = c.getInt("chickenSeconds", 20);
				s.peck = c.getString("peck", "medium");
				s.launchHeight = c.getInt("launchHeight", 30);
				s.slowFallSeconds = c.getInt("slowFallSeconds", 30);
				setups.put(key.replace(';', ','), s);
			}
		}
		plugin.getCommand("prankbutton").setExecutor(this);
		plugin.getCommand("prankbutton").setTabCompleter(this);
		// Every 2 seconds, puts signs back if they're missing (the sign was killed, or the chunk was just loaded).
		Bukkit.getScheduler().runTaskTimer(plugin, () -> {
			for (Block block : blocks.loaded(this)) {
				if (Labels.missing(block, LABEL_HEIGHT, LABEL_TAG)) updateLabel(block);
			}
		}, 40, 40);
	}

	private void save() {
		store.yaml.set("buttons", null);
		setups.forEach((key, s) -> {
			String p = "buttons." + key.replace(',', ';') + ".";
			store.yaml.set(p + "owner", s.owner);
			store.yaml.set(p + "ownerId", s.ownerId == null ? null : s.ownerId.toString());
			store.yaml.set(p + "message", s.message);
			store.yaml.set(p + "prank", s.prank);
			store.yaml.set(p + "cooldown", s.cooldown);
			store.yaml.set(p + "ownerSafe", s.ownerSafe);
			store.yaml.set(p + "announce", s.announce);
			store.yaml.set(p + "anvilHeight", s.anvilHeight);
			store.yaml.set(p + "anvilsKill", s.anvilsKill);
			store.yaml.set(p + "chickenCount", s.chickenCount);
			store.yaml.set(p + "chickenSeconds", s.chickenSeconds);
			store.yaml.set(p + "peck", s.peck);
			store.yaml.set(p + "launchHeight", s.launchHeight);
			store.yaml.set(p + "slowFallSeconds", s.slowFallSeconds);
		});
		store.save();
	}

	private Setup setup(Block block) {
		return setups.computeIfAbsent(SpecialBlocks.key(block), k -> new Setup());
	}

	private void updateLabel(Block block) {
		Labels.set(block, LABEL_HEIGHT, LABEL_TAG, Component.text(setup(block).message, NamedTextColor.RED, TextDecoration.BOLD));
	}

	// ---------- The block ----------

	@Override public String id() { return "prank_button"; }
	@Override public String name() { return "Prank Button"; }
	@Override public NamedTextColor color() { return NamedTextColor.RED; }
	@Override public String lore() { return "Whoever pushes it gets pranked. Sneak and use it to set it up."; }
	@Override public Material defaultMaterial() { return Material.CRIMSON_BUTTON; }

	@Override
	public void placed(Player player, Block block) {
		Setup s = setup(block);
		s.owner = player.getName();
		s.ownerId = player.getUniqueId();
		save();
		updateLabel(block);
		player.sendMessage(prefix().append(Component.text("Sneak and use your Prank Button (with an empty hand) to set the message and the prank.")));
	}

	@Override
	public void removed(Block block) {
		if (setups.remove(SpecialBlocks.key(block)) != null) save();
		Labels.remove(block, LABEL_HEIGHT, LABEL_TAG);
	}

	private static boolean canEdit(Player player, Setup s) {
		return s.isOwner(player.getUniqueId()) || player.hasPermission(ADMIN) || s.ownerId == null;
	}

	@Override
	public void use(Player player, Block block, boolean sneak) {
		Setup s = setup(block);
		if (sneak && canEdit(player, s)) {
			showSetup(player, block, s);
			return;
		}
		block.getWorld().playSound(block.getLocation(), Sound.BLOCK_STONE_BUTTON_CLICK_ON, SoundCategory.BLOCKS, 1f, 1f);
		if (s.isOwner(player.getUniqueId()) && s.ownerSafe) {
			player.sendMessage(prefix().append(Component.text("It's your button, so nothing happens. Sneak and use it to change it.")));
			return;
		}
		long now = Bukkit.getCurrentTick();
		if (s.lastPress != Long.MIN_VALUE && now - s.lastPress < s.cooldown * 20L) return;
		s.lastPress = now;

		Pranks.pick(s.prank).run().accept(player, s);
		if (s.announce) {
			Component message = prefix().append(Component.text(player.getName(), NamedTextColor.YELLOW)).append(Component.text(" pushed the button."));
			for (Player p : block.getWorld().getPlayers()) {
				if (p.getLocation().distanceSquared(block.getLocation()) <= 64 * 64) p.sendMessage(message);
			}
		}
	}

	// ---------- Setup (/prankbutton) ----------

	static Component prefix() {
		return Msg.prefix("Prank:", NamedTextColor.RED);
	}

	private static List<String> prankChoices() {
		List<String> choices = new ArrayList<>(Pranks.ALL.keySet());
		choices.add("random");
		return choices;
	}

	private static String prankName(String id) {
		return id.equals("random") ? "Random prank" : Pranks.ALL.containsKey(id) ? Pranks.ALL.get(id).name() : id;
	}

	private static final List<String> SETTINGS = List.of("message", "prank", "cooldown", "ownersafe", "announce", "anvilheight",
			"anvilskill", "chickens", "chickenseconds", "peck", "launchheight", "slowfall");

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length < 3) return false;
		World world = sender instanceof Player p ? p.getWorld() : Msg.where(sender) != null ? Msg.where(sender).getWorld() : Bukkit.getWorlds().get(0);
		Integer x = parseCoord(args[0]), y = parseCoord(args[1]), z = parseCoord(args[2]);
		if (x == null || y == null || z == null) return false;
		Block block = world.getBlockAt(x, y, z);
		if (blocks.kindAt(block) != this) {
			Msg.error(sender, "There's no Prank Button there.");
			return true;
		}
		Setup s = setup(block);
		boolean allowed = sender.hasPermission(ADMIN) || (sender instanceof Player p && canEdit(p, s));
		if (!allowed) {
			Msg.error(sender, "Only " + (s.owner.isEmpty() ? "its owner" : s.owner) + " or an operator can change this button.");
			return true;
		}
		if (args.length == 3) {
			showSetup(sender, block, s);
			return true;
		}
		String value = Msg.join(args, 4);
		switch (args[3].toLowerCase(Locale.ROOT)) {
			case "message" -> s.message = value.isEmpty() ? DEFAULT_MESSAGE : value.substring(0, Math.min(80, value.length()));
			case "prank" -> {
				String prank = value.toLowerCase(Locale.ROOT);
				if (!prankChoices().contains(prank)) {
					Msg.error(sender, "No prank called " + prank + ". Choose one of: " + String.join(", ", prankChoices()));
					return true;
				}
				s.prank = prank;
			}
			case "peck" -> {
				String peck = value.toLowerCase(Locale.ROOT);
				if (!Pranks.PECKS.containsKey(peck)) {
					Msg.error(sender, "Choose light, medium, brutal or deadly.");
					return true;
				}
				s.peck = peck;
			}
			case "cooldown" -> { if (!setInt(value, 0, 60, v -> s.cooldown = v)) return false; }
			case "anvilheight" -> { if (!setInt(value, 5, 100, v -> s.anvilHeight = v)) return false; }
			case "chickens" -> { if (!setInt(value, 3, 30, v -> s.chickenCount = v)) return false; }
			case "chickenseconds" -> { if (!setInt(value, 5, 60, v -> s.chickenSeconds = v)) return false; }
			case "launchheight" -> { if (!setInt(value, 10, 500, v -> s.launchHeight = v)) return false; }
			case "slowfall" -> { if (!setInt(value, 5, 300, v -> s.slowFallSeconds = v)) return false; }
			case "ownersafe" -> { if (!setBool(value, v -> s.ownerSafe = v)) return false; }
			case "announce" -> { if (!setBool(value, v -> s.announce = v)) return false; }
			case "anvilskill" -> { if (!setBool(value, v -> s.anvilsKill = v)) return false; }
			default -> {
				return false;
			}
		}
		if (s.ownerId == null && sender instanceof Player p) {
			s.owner = p.getName();
			s.ownerId = p.getUniqueId();
		}
		save();
		updateLabel(block);
		showSetup(sender, block, s);
		return true;
	}

	private static Integer parseCoord(String text) {
		return Msg.parseInt(text, -30_000_000, 30_000_000);
	}

	private static boolean setInt(String text, int min, int max, java.util.function.IntConsumer set) {
		Integer v = Msg.parseInt(text, min, max);
		if (v == null) return false;
		set.accept(v);
		return true;
	}

	private static boolean setBool(String text, java.util.function.Consumer<Boolean> set) {
		Boolean v = Msg.parseBool(text);
		if (v == null) return false;
		set.accept(v);
		return true;
	}

	private static void showSetup(CommandSender sender, Block block, Setup b) {
		String cmd = "/prankbutton " + block.getX() + " " + block.getY() + " " + block.getZ() + " ";
		NamedTextColor y = NamedTextColor.YELLOW;
		Component text = Component.text("Prank Button", NamedTextColor.RED, TextDecoration.BOLD)
				.append(Component.text(b.owner.isEmpty() ? "" : " (" + b.owner + "'s)", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))
				.append(Component.text(": click to change.", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false))
				.append(Component.text("\n Sign: ")).append(Component.text(b.message, NamedTextColor.RED))
				.append(Component.text(" ")).append(Msg.button("[Change]", y, cmd + "message " + b.message, false))
				.append(Component.text("\n Prank: "));
		for (String id : prankChoices()) {
			boolean on = id.equals(b.prank);
			text = text.append(Msg.button(on ? "[" + prankName(id) + "]" : prankName(id), on ? NamedTextColor.GREEN : NamedTextColor.GRAY,
					cmd + "prank " + id, true)).append(Component.text(" "));
		}
		text = text.append(Component.text("\n Works again after " + b.cooldown + "s ")).append(Msg.button("[Change]", y, cmd + "cooldown " + b.cooldown, false))
				.append(Component.text("  ")).append(Msg.toggle("Owner is safe", b.ownerSafe, cmd + "ownersafe " + !b.ownerSafe))
				.append(Component.text(" ")).append(Msg.toggle("Tell everyone who pushed it", b.announce, cmd + "announce " + !b.announce))
				.append(Component.text("\n Anvil shower: ", NamedTextColor.GOLD))
				.append(Component.text("drops from " + b.anvilHeight + " blocks ")).append(Msg.button("[Change]", y, cmd + "anvilheight " + b.anvilHeight, false))
				.append(Component.text(" ")).append(Msg.toggle("Always kill, even through netherite", b.anvilsKill, cmd + "anvilskill " + !b.anvilsKill))
				.append(Component.text("\n Killer chickens: ", NamedTextColor.GOLD))
				.append(Component.text(b.chickenCount + " ")).append(Msg.button("[Change]", y, cmd + "chickens " + b.chickenCount, false))
				.append(Component.text(", chase for " + b.chickenSeconds + "s ")).append(Msg.button("[Change]", y, cmd + "chickenseconds " + b.chickenSeconds, false))
				.append(Component.text(", peck: "));
		for (var peck : Pranks.PECKS.entrySet()) {
			boolean on = peck.getKey().equals(b.peck);
			text = text.append(Msg.button(on ? "[" + peck.getValue() + "]" : peck.getValue(), on ? NamedTextColor.GREEN : NamedTextColor.GRAY,
					cmd + "peck " + peck.getKey(), true)).append(Component.text(" "));
		}
		text = text.append(Component.text("\n Launch: ", NamedTextColor.GOLD))
				.append(Component.text(b.launchHeight + " blocks up (300+ is space) ")).append(Msg.button("[Change]", y, cmd + "launchheight " + b.launchHeight, false))
				.append(Component.text(", " + b.slowFallSeconds + "s of slow falling ")).append(Msg.button("[Change]", y, cmd + "slowfall " + b.slowFallSeconds, false))
				.append(Component.text(" (about 1 second per 2 blocks)", NamedTextColor.GRAY));
		sender.sendMessage(text);
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length >= 1 && args.length <= 3 && sender instanceof Player p) {
			Block target = p.getTargetBlockExact(6);
			if (target != null) {
				int[] c = {target.getX(), target.getY(), target.getZ()};
				return List.of(String.valueOf(c[args.length - 1]));
			}
			return List.of();
		}
		if (args.length == 4) return Msg.matching(SETTINGS, args[3]);
		if (args.length == 5) {
			return switch (args[3].toLowerCase(Locale.ROOT)) {
				case "prank" -> Msg.matching(prankChoices(), args[4]);
				case "peck" -> Msg.matching(new ArrayList<>(Pranks.PECKS.keySet()), args[4]);
				case "ownersafe", "announce", "anvilskill" -> Msg.matching(List.of("true", "false"), args[4]);
				default -> List.of();
			};
		}
		return List.of();
	}
}
