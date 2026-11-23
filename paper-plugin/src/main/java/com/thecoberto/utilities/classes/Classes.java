package com.thecoberto.utilities.classes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Classes: players get one of 15 classes, each with lasting effects and
 * sometimes an ability. One class at a time; picking another replaces it. The
 * class is saved on the player (like the Bedrock add-on), so it's kept through
 * logging out and dying. Operators pick their own from a menu with /class, and
 * set anyone's with /setclass (works from command blocks and Citizens NPCs too).
 */
public final class Classes implements Listener, TabExecutor {
	private final JavaPlugin plugin;
	private final NamespacedKey key;
	private final Tag<EntityType> undead;
	private ClassMenu menu;

	public Classes(JavaPlugin plugin) {
		this.plugin = plugin;
		this.key = new NamespacedKey(plugin, "class");
		this.undead = Bukkit.getTag(Tag.REGISTRY_ENTITY_TYPES, NamespacedKey.minecraft("undead"), EntityType.class);
	}

	public void enable() {
		menu = new ClassMenu(this, plugin);
		Bukkit.getPluginManager().registerEvents(this, plugin);
		Bukkit.getPluginManager().registerEvents(menu, plugin);
		plugin.getCommand("class").setExecutor(this);
		plugin.getCommand("setclass").setExecutor(this);
		plugin.getCommand("setclass").setTabCompleter(this);
		Bukkit.getScheduler().runTaskTimer(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::applyEffects), 10, 10);
	}

	public PlayerClass classOf(Player player) {
		String id = player.getPersistentDataContainer().get(key, PersistentDataType.STRING);
		return id == null ? null : PlayerClass.ALL.get(id);
	}

	private boolean isClass(Player player, String id) {
		PlayerClass c = classOf(player);
		return c != null && c.id().equals(id);
	}

	void setClass(Player player, PlayerClass playerClass) {
		for (PotionEffectType effect : PlayerClass.ALL_CLASS_EFFECTS) player.removePotionEffect(effect);
		if (playerClass == null) {
			player.getPersistentDataContainer().remove(key);
			player.sendMessage(prefix().append(Component.text("You no longer have a class.")));
			return;
		}
		player.getPersistentDataContainer().set(key, PersistentDataType.STRING, playerClass.id());
		player.sendMessage(prefix().append(Component.text("You are now a "))
				.append(Component.text(playerClass.name(), playerClass.color()))
				.append(Component.text(". " + playerClass.about())));
		player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
		applyEffects(player);
	}

	static Component prefix() {
		return Component.text("Classes:", NamedTextColor.GOLD).append(Component.text(" ", NamedTextColor.WHITE));
	}

	// ---------- Keeping effects on ----------

	private static void give(LivingEntity entity, PlayerClass.Effect effect) {
		PotionEffect current = entity.getPotionEffect(effect.type());
		// Leave stronger effects (from potions or beacons) alone, and don't top up too often.
		if (current != null && (current.getAmplifier() > effect.amplifier()
				|| (current.getAmplifier() == effect.amplifier()
						&& (current.isInfinite() || current.getDuration() > effect.duration() / 2)))) {
			return;
		}
		entity.addPotionEffect(new PotionEffect(effect.type(), effect.duration(), effect.amplifier(), false, false, true));
	}

	private void applyEffects(Player player) {
		PlayerClass c = classOf(player);
		if (c == null || player.isDead()) return;
		for (PlayerClass.Effect effect : c.effects().apply(player)) give(player, effect);
		switch (c.id()) {
			case "plaguebearer" -> player.removePotionEffect(PotionEffectType.POISON);
			case "necromancer" -> player.removePotionEffect(PotionEffectType.WITHER);
			case "bard" -> {
				for (Player ally : player.getWorld().getPlayers()) {
					if (ally.getLocation().distanceSquared(player.getLocation()) > 64) continue;
					give(ally, PlayerClass.e(PotionEffectType.REGENERATION, 1, PlayerClass.REGEN));
					give(ally, PlayerClass.e(PotionEffectType.SPEED, 1, PlayerClass.SHORT));
				}
			}
			default -> {
			}
		}
	}

	// ---------- Abilities ----------

	/** Acrobats take no fall damage. */
	@EventHandler(ignoreCancelled = true)
	public void onFall(EntityDamageEvent event) {
		if (event.getCause() == EntityDamageEvent.DamageCause.FALL && event.getEntity() instanceof Player player
				&& isClass(player, "acrobat")) {
			event.setCancelled(true);
		}
	}

	private static boolean isMelee(EntityDamageByEntityEvent event) {
		return event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
				|| event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK;
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onHit(EntityDamageByEntityEvent event) {
		if (!isMelee(event) || !(event.getEntity() instanceof LivingEntity target)) return;
		if (target instanceof HumanEntity human && human.isBlocking()) return; // blocked with a shield
		Entity damager = event.getDamager();
		// After the hit lands, like the Fabric mod's after-damage event.
		Bukkit.getScheduler().runTask(plugin, () -> {
			if (!target.isValid() || target.isDead()) return;
			if (damager instanceof Player attacker) onMelee(attacker, target);
			if (target instanceof Player sentinel && isClass(sentinel, "sentinel") && damager instanceof LivingEntity attacker
					&& attacker != sentinel && attacker.isValid() && !attacker.isDead()) {
				attacker.damage(2, DamageSource.builder(DamageType.THORNS).withCausingEntity(sentinel).withDirectEntity(sentinel).build());
			}
		});
	}

	/** Melee hits by a classed player. */
	private void onMelee(Player attacker, LivingEntity target) {
		PlayerClass c = classOf(attacker);
		if (c == null) return;
		switch (c.id()) {
			case "pyro" -> {
				if (!attacker.getInventory().getItemInOffHand().isEmpty()) target.setFireTicks(Math.max(target.getFireTicks(), 80));
			}
			case "paladin" -> {
				if (undead != null && undead.isTagged(target.getType())) {
					target.setNoDamageTicks(0); // let the extra damage through right after the hit
					target.damage(4, DamageSource.builder(DamageType.MAGIC).withCausingEntity(attacker).withDirectEntity(attacker).build());
					attacker.heal(2);
				}
			}
			case "plaguebearer" -> target.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 80, 1));
			case "necromancer" -> target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 80, 0));
			case "vampire" -> attacker.heal(1);
			default -> {
			}
		}
	}

	/** Necromancers draw strength from kills. */
	@EventHandler
	public void onDeath(EntityDeathEvent event) {
		Player killer = event.getEntity().getKiller();
		if (killer != null && isClass(killer, "necromancer")) {
			killer.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 600, 0, false, false, true));
		}
	}

	// ---------- Commands ----------

	private static List<String> names() {
		List<String> names = new ArrayList<>(PlayerClass.ALL.keySet());
		names.add("none");
		return names;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (command.getName().equals("class")) {
			if (!(sender instanceof Player player)) {
				sender.sendMessage(Component.text("Only players can choose a class. Use /setclass for others.", NamedTextColor.RED));
				return true;
			}
			menu.open(player);
			return true;
		}

		if (args.length != 2) return false;
		String name = args[1].toLowerCase(Locale.ROOT);
		if (!names().contains(name)) {
			sender.sendMessage(Component.text("No class called " + name + ". Choose one of: " + String.join(", ", names()), NamedTextColor.RED));
			return true;
		}
		List<Player> players;
		try {
			players = Bukkit.selectEntities(sender, args[0]).stream()
					.filter(e -> e instanceof Player).map(e -> (Player) e).toList();
		} catch (IllegalArgumentException e) {
			sender.sendMessage(Component.text("Couldn't read " + args[0] + ": " + e.getMessage(), NamedTextColor.RED));
			return true;
		}
		if (players.isEmpty()) {
			sender.sendMessage(Component.text("No player was found.", NamedTextColor.RED));
			return true;
		}
		PlayerClass target = PlayerClass.ALL.get(name);
		for (Player player : players) {
			if (classOf(player) != target) setClass(player, target);
		}
		String who = players.stream().map(Player::getName).collect(Collectors.joining(", "));
		sender.sendMessage(Component.text("Set " + who + " to " + (target == null ? "no class" : target.name()) + "."));
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (!command.getName().equals("setclass")) return List.of();
		if (args.length == 1) {
			List<String> options = new ArrayList<>(List.of("@a", "@p", "@s", "@r"));
			Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
			return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
		}
		if (args.length == 2) {
			return names().stream().filter(n -> n.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
		}
		return List.of();
	}
}
