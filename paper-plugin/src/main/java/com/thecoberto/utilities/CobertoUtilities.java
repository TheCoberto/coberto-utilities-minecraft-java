package com.thecoberto.utilities;

import com.thecoberto.utilities.classes.Classes;
import com.thecoberto.utilities.music.MusicSelector;
import com.thecoberto.utilities.prank.PrankButton;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Coberto's Utilities for Paper: Classes, Activity Rewards, Loot Crates, the
 * Music Selector and the Prank Button. Each feature lives in its own class.
 */
public final class CobertoUtilities extends JavaPlugin implements TabExecutor {
	private SpecialBlocks blocks;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		Money.hook();
		if (!Money.available()) getLogger().warning("No economy found through Vault. Activity Rewards and song prices won't work.");

		blocks = new SpecialBlocks(this);
		new Classes(this).enable();
		new ActivityRewards(this);
		new Crates(this, blocks);
		new MusicSelector(this, blocks);
		new PrankButton(this, blocks);
		new WorldCleanup(this);
		new Unload(this);

		getCommand("utilitiesgive").setExecutor(this);
		getCommand("utilitiesgive").setTabCompleter(this);
	}

	/** /utilitiesgive <block> [player] [amount] */
	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length < 1 || args.length > 3) return false;
		SpecialBlocks.Kind kind = blocks.kinds().get(args[0].toLowerCase(Locale.ROOT));
		if (kind == null) {
			Msg.error(sender, "Choose one of: " + String.join(", ", blocks.kinds().keySet()));
			return true;
		}
		Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player p ? p : null;
		if (target == null) {
			Msg.error(sender, args.length >= 2 ? "No player called " + args[1] + " is online." : "Say who to give it to.");
			return true;
		}
		Integer amount = args.length == 3 ? Msg.parseInt(args[2], 1, 64) : Integer.valueOf(1);
		if (amount == null) return false;
		ItemStack item = blocks.item(kind, amount);
		target.getInventory().addItem(item).values().forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
		sender.sendMessage(Component.text("Gave " + amount + " " + kind.name() + " to " + target.getName() + "."));
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length == 1) return Msg.matching(new ArrayList<>(blocks.kinds().keySet()), args[0]);
		if (args.length == 2) return null; // online player names
		return List.of();
	}
}
