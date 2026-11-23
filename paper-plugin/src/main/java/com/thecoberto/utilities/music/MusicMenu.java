package com.thecoberto.utilities.music;

import com.thecoberto.utilities.Money;
import com.thecoberto.utilities.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The song menu, laid out like the Fabric mod's: a four-row chest with a disc
 * for every song players can queue (and its price), what's playing and the
 * queue on the jukebox, and for operators Settings, Skip and Stop buttons.
 */
final class MusicMenu implements Listener {
	private static final int SONG_SLOTS = 27;
	private static final int INFO_SLOT = 27;
	private static final int SETTINGS_SLOT = 33;
	private static final int SKIP_SLOT = 34;
	private static final int STOP_SLOT = 35;

	private final JavaPlugin plugin;
	private final MusicSelector music;

	MusicMenu(JavaPlugin plugin, MusicSelector music) {
		this.plugin = plugin;
		this.music = music;
	}

	private static final class Holder implements InventoryHolder {
		Inventory inventory;
		Station station;
		List<Track> songs;

		@Override
		public Inventory getInventory() {
			return inventory;
		}
	}

	void open(Player player, Station station) {
		Holder holder = new Holder();
		holder.station = station;
		holder.songs = music.allowed.stream().map(id -> Track.all().get(id)).filter(Objects::nonNull).limit(SONG_SLOTS).toList();
		Inventory inv = Bukkit.createInventory(holder, 36, Component.text("Music Selector", NamedTextColor.LIGHT_PURPLE));
		holder.inventory = inv;

		for (int i = SONG_SLOTS; i < 36; i++) inv.setItem(i, item(Material.GRAY_STAINED_GLASS_PANE, Component.text(" ")));
		for (int i = 0; i < holder.songs.size(); i++) {
			Track t = holder.songs.get(i);
			inv.setItem(i, item(t.icon(), Component.text(t.name(), NamedTextColor.AQUA),
					gray(Money.format(music.priceOf(t.id()))), gray(Msg.time(t.length()))));
		}

		List<Component> info = new ArrayList<>();
		if (station.current != null) {
			long left = Math.max(0, (station.endsAt - Bukkit.getCurrentTick()) / 20);
			Track t = Track.all().get(station.current.id());
			info.add(Component.text("Now Playing: ", NamedTextColor.LIGHT_PURPLE)
					.append(Component.text((t == null ? station.current.id() : t.name()) + " ", NamedTextColor.WHITE))
					.append(gray("(" + Msg.time((int) left) + " left)")));
		} else {
			info.add(gray("Nothing is playing."));
		}
		if (!station.queue.isEmpty()) {
			info.add(Component.text("Up next:", NamedTextColor.YELLOW));
			for (int i = 0; i < station.queue.size(); i++) {
				var q = station.queue.get(i);
				Track t = Track.all().get(q.id());
				info.add(Component.text(" " + (i + 1) + ". " + (t == null ? q.id() : t.name()) + " - " + q.by(), NamedTextColor.WHITE));
			}
		}
		info.add(Component.text("Balance: ", NamedTextColor.WHITE).append(Component.text(Money.format(Money.get(player)), NamedTextColor.GREEN)));
		int wait = music.cooldownLeft(player);
		if (wait > 0) info.add(Component.text("You can queue another song in " + Msg.time(wait) + ".", NamedTextColor.RED));
		if (holder.songs.isEmpty()) info.add(gray("No songs can be queued right now."));
		inv.setItem(INFO_SLOT, item(Material.JUKEBOX, Component.text("Music Selector", NamedTextColor.LIGHT_PURPLE), info.toArray(Component[]::new)));

		if (player.hasPermission(MusicSelector.ADMIN)) {
			inv.setItem(SETTINGS_SLOT, item(Material.COMPARATOR, Component.text("Settings", NamedTextColor.GOLD),
					gray("Price, cooldown, songs and autoplay"), gray("(for every selector)")));
			if (station.current != null) inv.setItem(SKIP_SLOT, item(Material.SPECTRAL_ARROW, Component.text("Skip song", NamedTextColor.RED)));
			if (station.current != null || !station.queue.isEmpty()) {
				inv.setItem(STOP_SLOT, item(Material.BARRIER, Component.text("Stop and clear queue", NamedTextColor.RED)));
			}
		}
		player.openInventory(inv);
	}

	private static Component gray(String text) {
		return Component.text(text, NamedTextColor.GRAY);
	}

	private static ItemStack item(Material material, Component name, Component... lore) {
		ItemStack stack = new ItemStack(material);
		ItemMeta meta = stack.getItemMeta();
		meta.displayName(name.decoration(TextDecoration.ITALIC, false));
		if (lore.length > 0) meta.lore(List.of(lore).stream().map(l -> l.decoration(TextDecoration.ITALIC, false)).toList());
		meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP); // hides the disc's song line
		stack.setItemMeta(meta);
		return stack;
	}

	@EventHandler
	public void onClick(InventoryClickEvent event) {
		if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
		event.setCancelled(true); // nothing in the menu (or the player's inventory, while it's open) moves
		if (!(event.getWhoClicked() instanceof Player player)) return;
		if (event.getClickedInventory() != event.getView().getTopInventory()) return;
		int slot = event.getSlot();
		Station station = holder.station;
		Runnable action = null;
		if (slot < holder.songs.size()) {
			Track track = holder.songs.get(slot);
			action = () -> music.queueSong(player, station, track);
		} else if (player.hasPermission(MusicSelector.ADMIN)) {
			if (slot == SETTINGS_SLOT) action = () -> music.showSettings(player);
			else if (slot == SKIP_SLOT && station.current != null) action = station::skip;
			else if (slot == STOP_SLOT) action = station::clear;
		}
		if (action == null) return;
		Runnable run = action;
		// Close and act on the next tick: closing a menu inside its own click event isn't safe.
		Bukkit.getScheduler().runTask(plugin, () -> {
			if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof Holder)) return; // double click
			player.closeInventory();
			run.run();
		});
	}

	@EventHandler
	public void onDrag(InventoryDragEvent event) {
		if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
	}
}
