package com.thecoberto.utilities.blackmarket;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Black Market's goods, and what makes them special: Moonshine's hangover,
 * the Mystery Brew's random effect, the Smoke Bomb's cloud, and the Shiv and
 * Bootleg Elytra that can't be repaired. Each item carries a tag saying which
 * good it is, so renaming it doesn't change what it does.
 */
public final class Contraband implements Listener {
	/** price: default dollars (changeable in black-market.yml); min-max: how many a night; chance: how often it's in stock. */
	public enum Good {
		MOONSHINE("Moonshine", 1_500, 3, 6, 0.9),
		SHIV("Shiv", 2_500, 1, 2, 0.7),
		SMOKE_BOMB("Smoke Bomb", 800, 3, 5, 0.8),
		MYSTERY_BREW("Mystery Brew", 500, 4, 8, 0.9),
		COUNTERFEIT_MONEY("Counterfeit Money", 100, 5, 10, 0.6),
		BOOTLEG_ELYTRA("Bootleg Elytra", 25_000, 1, 1, 0.2);

		public final String label;
		public final double price;
		public final int min, max;
		public final double chance;

		Good(String label, double price, int min, int max, double chance) {
			this.label = label;
			this.price = price;
			this.min = min;
			this.max = max;
			this.chance = chance;
		}

		public String id() {
			return name().toLowerCase(Locale.ROOT);
		}

		public static Good byId(String id) {
			for (Good g : values()) if (g.id().equalsIgnoreCase(id)) return g;
			return null;
		}
	}

	private static final TextColor SHADY = TextColor.color(0x9B59B6);
	private static final int HANGOVER_DELAY = 60 * 20; // when Moonshine wears off

	private final JavaPlugin plugin;
	private final NamespacedKey key;

	public Contraband(JavaPlugin plugin) {
		this.plugin = plugin;
		this.key = new NamespacedKey(plugin, "contraband");
		Bukkit.getPluginManager().registerEvents(this, plugin);
	}

	public Good goodOf(ItemStack stack) {
		if (stack == null || !stack.hasItemMeta()) return null;
		String id = stack.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
		return id == null ? null : Good.byId(id);
	}

	public ItemStack make(Good good) {
		ItemStack stack = switch (good) {
			case MOONSHINE -> potion(Material.POTION, Color.fromRGB(0xE8D9A0),
					new PotionEffect(PotionEffectType.STRENGTH, 60 * 20, 1),
					new PotionEffect(PotionEffectType.SPEED, 60 * 20, 1));
			case SMOKE_BOMB -> potion(Material.SPLASH_POTION, Color.fromRGB(0x404040),
					new PotionEffect(PotionEffectType.BLINDNESS, 5 * 20, 0),
					new PotionEffect(PotionEffectType.SLOWNESS, 3 * 20, 0));
			case MYSTERY_BREW -> potion(Material.POTION, Color.fromRGB(0x6A0DAD));
			case SHIV -> new ItemStack(Material.IRON_SWORD);
			case COUNTERFEIT_MONEY -> new ItemStack(Material.PAPER);
			case BOOTLEG_ELYTRA -> new ItemStack(Material.ELYTRA);
		};
		ItemMeta meta = stack.getItemMeta();
		List<String> lore = new ArrayList<>();
		switch (good) {
			case MOONSHINE -> {
				lore.add("Strength II and Speed II for 60 seconds.");
				lore.add("Then the hangover hits: nausea and slowness.");
			}
			case SMOKE_BOMB -> {
				lore.add("Throw it. Everyone nearby is blinded");
				lore.add("and slowed in a cloud of smoke.");
			}
			case MYSTERY_BREW -> {
				lore.add("Could be anything.");
				lore.add("Could be good. Could be very bad.");
				meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
			}
			case SHIV -> {
				meta.addEnchant(Enchantment.SHARPNESS, 6, true);
				((Damageable) meta).setMaxDamage(20);
				lore.add("Hits hard. Falls apart fast.");
				lore.add("Breaks after about 20 hits. Can't be repaired.");
			}
			case COUNTERFEIT_MONEY -> {
				lore.add("A crisp $1,000 bill.");
				lore.add("Almost too crisp.");
			}
			case BOOTLEG_ELYTRA -> {
				((Damageable) meta).setMaxDamage(108);
				lore.add("Fell off the back of a cart.");
				lore.add("A quarter of the usual durability.");
				lore.add("Can't be repaired or mended.");
			}
		}
		String name = good == Good.COUNTERFEIT_MONEY ? "$1,000 Bill" : good.label;
		meta.displayName(Component.text(name, good == Good.COUNTERFEIT_MONEY ? NamedTextColor.GREEN : SHADY).decoration(TextDecoration.ITALIC, false));
		meta.lore(lore.stream().map(l -> (Component) Component.text(l, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)).toList());
		meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, good.id());
		stack.setItemMeta(meta);
		return stack;
	}

	/** What a scammed buyer gets. */
	public static ItemStack bagOfDirt() {
		ItemStack stack = new ItemStack(Material.DIRT);
		ItemMeta meta = stack.getItemMeta();
		meta.displayName(Component.text("Bag of \"Goods\"", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
		meta.lore(List.of(Component.text("It's dirt. You got scammed.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
		stack.setItemMeta(meta);
		return stack;
	}

	private static ItemStack potion(Material material, Color color, PotionEffect... effects) {
		ItemStack stack = new ItemStack(material);
		PotionMeta meta = (PotionMeta) stack.getItemMeta();
		meta.setBasePotionType(PotionType.AWKWARD);
		meta.setColor(color);
		for (PotionEffect effect : effects) meta.addCustomEffect(effect, true);
		stack.setItemMeta(meta);
		return stack;
	}

	@EventHandler(ignoreCancelled = true)
	public void onDrink(PlayerItemConsumeEvent event) {
		Good good = goodOf(event.getItem());
		Player player = event.getPlayer();
		if (good == Good.MOONSHINE) {
			Bukkit.getScheduler().runTaskLater(plugin, () -> {
				if (!player.isOnline()) return;
				player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 10 * 20, 0));
				player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20 * 20, 0));
				player.sendMessage(Component.text("Ugh. Your head is pounding.", NamedTextColor.GRAY, TextDecoration.ITALIC));
			}, HANGOVER_DELAY);
		} else if (good == Good.MYSTERY_BREW) {
			Bukkit.getScheduler().runTask(plugin, () -> mystery(player));
		}
	}

	private record Outcome(PotionEffectType type, int seconds, int level, boolean good, String text) {}

	private static final List<Outcome> MYSTERY = List.of(
			new Outcome(PotionEffectType.SPEED, 60, 1, true, "You feel quick."),
			new Outcome(PotionEffectType.REGENERATION, 30, 1, true, "Warmth spreads through you."),
			new Outcome(PotionEffectType.STRENGTH, 60, 0, true, "You feel strong."),
			new Outcome(PotionEffectType.NIGHT_VISION, 180, 0, true, "Your eyes adjust to the dark."),
			new Outcome(PotionEffectType.FIRE_RESISTANCE, 180, 0, true, "Your skin feels cool."),
			new Outcome(PotionEffectType.ABSORPTION, 120, 2, true, "You feel tough."),
			new Outcome(PotionEffectType.POISON, 15, 0, false, "That tasted wrong."),
			new Outcome(PotionEffectType.NAUSEA, 15, 0, false, "The world starts spinning."),
			new Outcome(PotionEffectType.BLINDNESS, 10, 0, false, "Everything goes dark."),
			new Outcome(PotionEffectType.LEVITATION, 5, 0, false, "Your feet leave the ground..."),
			new Outcome(PotionEffectType.HUNGER, 30, 2, false, "You're suddenly starving."),
			new Outcome(PotionEffectType.GLOWING, 60, 0, false, "You start to glow. Everyone can see you."));

	private static void mystery(Player player) {
		if (!player.isOnline()) return;
		Outcome o = MYSTERY.get(ThreadLocalRandom.current().nextInt(MYSTERY.size()));
		player.addPotionEffect(new PotionEffect(o.type(), o.seconds() * 20, o.level()));
		player.sendMessage(Component.text(o.text(), o.good() ? NamedTextColor.GREEN : NamedTextColor.RED, TextDecoration.ITALIC));
	}

	@EventHandler(ignoreCancelled = true)
	public void onSplash(PotionSplashEvent event) {
		if (goodOf(event.getPotion().getItem()) != Good.SMOKE_BOMB) return;
		Location at = event.getPotion().getLocation();
		at.getWorld().playSound(at, Sound.BLOCK_FIRE_EXTINGUISH, 1.2f, 0.6f);
		for (int i = 0; i < 8; i++) {
			Bukkit.getScheduler().runTaskLater(plugin, () -> smoke(at, 1.0), i * 5L);
		}
	}

	/** A cloud of smoke, used by the Smoke Bomb and when the dealer comes and goes. */
	public static void smoke(Location at, double scale) {
		if (at.getWorld() == null) return;
		Location center = at.clone().add(0, 1, 0);
		at.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, center, (int) (40 * scale), 1.2 * scale, 0.8 * scale, 1.2 * scale, 0.01);
		at.getWorld().spawnParticle(Particle.LARGE_SMOKE, center, (int) (60 * scale), 1.5 * scale, 1.0 * scale, 1.5 * scale, 0.02);
	}

	// The Shiv and the Bootleg Elytra can't be repaired, mended, or used to copy their enchantments.
	private boolean unfixable(ItemStack stack) {
		Good good = goodOf(stack);
		return good == Good.SHIV || good == Good.BOOTLEG_ELYTRA;
	}

	@EventHandler
	public void onAnvil(PrepareAnvilEvent event) {
		for (ItemStack stack : event.getInventory().getContents()) {
			if (unfixable(stack)) {
				event.setResult(null);
				return;
			}
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onMend(PlayerItemMendEvent event) {
		if (unfixable(event.getItem())) event.setCancelled(true);
	}
}
