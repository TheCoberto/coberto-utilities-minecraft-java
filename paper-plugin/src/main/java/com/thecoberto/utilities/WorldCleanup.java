package com.thecoberto.utilities;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.CommandExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Set;

/**
 * /cleanup clears hostile mobs, arrows, XP orbs and dropped items in every
 * world to reduce lag, like the World Cleanup mod and Bedrock function.
 */
public final class WorldCleanup implements CommandExecutor {
	/** What gets cleared. Same list as the Bedrock cleanup.mcfunction. */
	private static final Set<EntityType> CLEARED = Set.of(
			EntityType.ZOMBIE,
			EntityType.SKELETON,
			EntityType.CREEPER,
			EntityType.SPIDER,
			EntityType.CAVE_SPIDER,
			EntityType.PILLAGER,
			EntityType.SILVERFISH,
			EntityType.ENDERMAN,
			EntityType.SLIME,
			EntityType.WITCH,
			EntityType.DROWNED,
			EntityType.HUSK,
			EntityType.STRAY,
			EntityType.WITHER_SKELETON,
			EntityType.ARROW,
			EntityType.SPECTRAL_ARROW,
			EntityType.EXPERIENCE_ORB,
			EntityType.ITEM);

	WorldCleanup(JavaPlugin plugin) {
		plugin.getCommand("cleanup").setExecutor(this);
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		broadcast(Component.text("Clearing entities...").decorate(TextDecoration.BOLD, TextDecoration.ITALIC));
		int cleared = 0;
		for (World world : Bukkit.getWorlds()) {
			List<Entity> doomed = world.getEntities().stream().filter(WorldCleanup::shouldClear).toList();
			// Removed rather than killed, so the mobs don't drop loot and XP that would need clearing too.
			doomed.forEach(Entity::remove);
			cleared += doomed.size();
		}
		broadcast(Component.text("MOBs & dropped items cleared!", NamedTextColor.YELLOW));
		for (Player player : Bukkit.getOnlinePlayers()) player.playSound(player, Sound.UI_TOAST_IN, SoundCategory.MASTER, 1f, 1f);
		if (!(sender instanceof Player)) sender.sendMessage(Component.text("Cleared " + Msg.plural(cleared, "entity").replace("entitys", "entities") + "."));
		return true;
	}

	private static boolean shouldClear(Entity entity) {
		if (!CLEARED.contains(entity.getType())) return false;
		// Citizens NPCs can be zombies or skeletons too.
		if (entity.hasMetadata("NPC")) return false;
		// Leave name-tagged mobs alone, like someone's pet zombie. A name tag makes a mob persistent;
		// a name from a plugin (like Skeleton Variants) doesn't, so those are still cleared.
		return !(entity instanceof Mob mob && mob.customName() != null && !mob.getRemoveWhenFarAway());
	}

	private static void broadcast(Component message) {
		Bukkit.broadcast(Component.text("[")
				.append(Component.text("Server", NamedTextColor.GOLD, TextDecoration.BOLD))
				.append(Component.text("] "))
				.append(message));
	}
}
