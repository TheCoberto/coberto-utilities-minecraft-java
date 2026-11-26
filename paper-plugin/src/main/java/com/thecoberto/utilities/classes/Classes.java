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

import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.entity.Projectile;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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
	private BedrockClassForm bedrockForm;
	/** When each player last picked a class themselves (saved on the player, so relogging doesn't skip it). */
	private final NamespacedKey changedKey;
	/** When druids were last hurt, for their out-of-combat regeneration. */
	private final Map<UUID, Long> lastHurt = new HashMap<>();

	/** How long players wait between picking classes. Operators don't wait. */
	static final long COOLDOWN_MS = 5 * 60 * 1000;
	static final String BYPASS_PERMISSION = "cobertoutilities.classes.admin";
	private static final long DRUID_CALM_MS = 5000;

	public Classes(JavaPlugin plugin) {
		this.plugin = plugin;
		this.key = new NamespacedKey(plugin, "class");
		this.changedKey = new NamespacedKey(plugin, "class_changed");
		this.undead = Bukkit.getTag(Tag.REGISTRY_ENTITY_TYPES, NamespacedKey.minecraft("undead"), EntityType.class);
	}

	public void enable() {
		menu = new ClassMenu(this, plugin);
		Bukkit.getPluginManager().registerEvents(this, plugin);
		Bukkit.getPluginManager().registerEvents(menu, plugin);
		if (Bukkit.getPluginManager().isPluginEnabled("floodgate")) bedrockForm = new BedrockClassForm(this, plugin);
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

	/** Milliseconds until this player can pick a class again (0 = now). */
	long cooldownLeft(Player player) {
		if (player.hasPermission(BYPASS_PERMISSION)) return 0;
		Long last = player.getPersistentDataContainer().get(changedKey, PersistentDataType.LONG);
		if (last == null) return 0; // their first pick is free
		return Math.max(0, last + COOLDOWN_MS - System.currentTimeMillis());
	}

	static String formatTime(long ms) {
		long seconds = (ms + 999) / 1000;
		return seconds >= 60 ? (seconds / 60) + "m " + (seconds % 60) + "s" : seconds + "s";
	}

	/** A player picking from a menu: respects the cooldown. */
	void choose(Player player, PlayerClass picked) {
		if (classOf(player) == picked) return;
		long left = cooldownLeft(player);
		if (left > 0) {
			player.sendMessage(prefix().append(Component.text("You can change your class again in " + formatTime(left) + ".", NamedTextColor.RED)));
			return;
		}
		setClass(player, picked);
		player.getPersistentDataContainer().set(changedKey, PersistentDataType.LONG, System.currentTimeMillis());
	}

	/** Opens the Bedrock form for Bedrock players, the chest menu for everyone else. */
	void openMenu(Player player) {
		if (bedrockForm != null && bedrockForm.isBedrock(player)) bedrockForm.open(player);
		else menu.open(player);
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
		List<PlayerClass.Effect> effects = c.effects().apply(player);
		for (PlayerClass.Effect effect : effects) give(player, effect);
		// Situational Night Vision (Vampire at night, Miner underground) switches off as soon
		// as it stops applying, instead of lingering and flickering out. A Night Vision
		// potion lasts longer than the class's 30 seconds, so it's left alone.
		if (effects.stream().noneMatch(e -> e.type() == PotionEffectType.NIGHT_VISION)) {
			PotionEffect nv = player.getPotionEffect(PotionEffectType.NIGHT_VISION);
			if (nv != null && !nv.isInfinite() && nv.getDuration() <= PlayerClass.LASTING && !nv.hasParticles()) {
				player.removePotionEffect(PotionEffectType.NIGHT_VISION);
			}
		}
		switch (c.id()) {
			case "plaguebearer" -> player.removePotionEffect(PotionEffectType.POISON);
			case "necromancer" -> player.removePotionEffect(PotionEffectType.WITHER);
			case "bard" -> {
				for (Player ally : player.getWorld().getPlayers()) {
					if (ally.getLocation().distanceSquared(player.getLocation()) > 64) continue;
					give(ally, PlayerClass.e(PotionEffectType.SPEED, 1, PlayerClass.SHORT));
					// A support class: the bard heals others, not themselves.
					if (ally != player) give(ally, PlayerClass.e(PotionEffectType.REGENERATION, 1, PlayerClass.REGEN));
				}
			}
			case "druid" -> {
				Long hurt = lastHurt.get(player.getUniqueId());
				boolean calm = hurt == null || System.currentTimeMillis() - hurt >= DRUID_CALM_MS;
				if (calm && PlayerClass.standingOnNature(player)) give(player, PlayerClass.e(PotionEffectType.REGENERATION, 1, PlayerClass.REGEN));
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

	/** Druids remember when they were hurt. */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onHurt(EntityDamageEvent event) {
		if (event.getEntity() instanceof Player player && isClass(player, "druid")) {
			lastHurt.put(player.getUniqueId(), System.currentTimeMillis());
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		lastHurt.remove(event.getPlayer().getUniqueId());
	}

	/** Paladins take 25% less damage from undead (melee or their arrows). */
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onUndeadHit(EntityDamageByEntityEvent event) {
		if (!(event.getEntity() instanceof Player player) || !isClass(player, "paladin") || undead == null) return;
		Entity source = event.getDamager();
		if (source instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) source = shooter;
		if (undead.isTagged(source.getType())) event.setDamage(event.getDamage() * 0.75);
	}

	/** Tanks take half knockback. */
	@EventHandler(ignoreCancelled = true)
	public void onKnockback(EntityKnockbackEvent event) {
		if (event.getEntity() instanceof Player player && isClass(player, "tank")) {
			event.setKnockback(event.getKnockback().multiply(0.5));
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onHit(EntityDamageByEntityEvent event) {
		if (!isMelee(event) || !(event.getEntity() instanceof LivingEntity target)) return;
		if (target instanceof HumanEntity human && human.isBlocking()) return; // blocked with a shield
		Entity damager = event.getDamager();
		// Abilities need a charged, direct hit: no spam-clicking, no sweeping several targets.
		boolean charged = damager instanceof Player p && event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
				&& p.getAttackCooldown() >= 0.9f;
		// After the hit lands, like the Fabric mod's after-damage event.
		Bukkit.getScheduler().runTask(plugin, () -> {
			if (!target.isValid() || target.isDead()) return;
			if (charged && damager instanceof Player attacker) onMelee(attacker, target);
			if (target instanceof Player sentinel && isClass(sentinel, "sentinel") && damager instanceof LivingEntity attacker
					&& attacker != sentinel && attacker.isValid() && !attacker.isDead()) {
				attacker.damage(1, DamageSource.builder(DamageType.THORNS).withCausingEntity(sentinel).withDirectEntity(sentinel).build());
			}
		});
	}

	/** Melee hits by a classed player. */
	private void onMelee(Player attacker, LivingEntity target) {
		PlayerClass c = classOf(attacker);
		if (c == null) return;
		switch (c.id()) {
			case "pyro" -> {
				if (!attacker.getInventory().getItemInOffHand().isEmpty()) target.setFireTicks(Math.max(target.getFireTicks(), 60));
			}
			case "paladin" -> {
				if (undead != null && undead.isTagged(target.getType())) {
					target.setNoDamageTicks(0); // let the extra damage through right after the hit
					target.damage(4, DamageSource.builder(DamageType.MAGIC).withCausingEntity(attacker).withDirectEntity(attacker).build());
					attacker.heal(2);
				}
			}
			case "plaguebearer" -> target.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 0));
			case "necromancer" -> target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 60, 0));
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
			killer.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 300, 0, false, false, true));
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
			openMenu(player);
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
