package com.thecoberto.utilities.music;

import net.minecraft.world.phys.Vec3;

import net.minecraft.server.permissions.Permissions;

import java.util.ArrayList;
import java.util.List;

import com.thecoberto.utilities.Money;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

/**
 * The song menu: a four-row chest with a disc for every song players can
 * queue (and its price), what's playing and the queue on the jukebox, and for
 * operators Settings, Skip and Stop buttons. Items in it can't be taken.
 */
public class MusicMenu extends ChestMenu {
	private static final int SONG_SLOTS = 27;
	private static final int INFO_SLOT = 27;
	private static final int SETTINGS_SLOT = 33;
	private static final int SKIP_SLOT = 34;
	private static final int STOP_SLOT = 35;

	/** Clicks, run at the end of the tick (not in the middle of handling the click). */
	private static final List<Runnable> pending = new ArrayList<>();

	static void registerEvents() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			List<Runnable> tasks = new ArrayList<>(pending);
			pending.clear();
			tasks.forEach(Runnable::run);
		});
	}

	private final ServerPlayer player;
	private final MusicSelectorBlockEntity station;
	private final List<Track> songs;

	private MusicMenu(int syncId, Inventory inventory, ServerPlayer player, MusicSelectorBlockEntity station, List<Track> songs) {
		super(MenuType.GENERIC_9x4, syncId, inventory, buildDisplay(player, station, songs), 4);
		this.player = player;
		this.station = station;
		this.songs = songs;
	}

	static void open(ServerPlayer player, MusicSelectorBlockEntity station) {
		MusicState s = MusicState.get(player.level().getServer());
		var tracks = Track.all(player.level().getServer());
		List<Track> songs = s.allowed.stream().map(tracks::get).filter(t -> t != null).limit(SONG_SLOTS).toList();
		player.openMenu(new SimpleMenuProvider(
				(syncId, inventory, p) -> new MusicMenu(syncId, inventory, player, station, songs),
				Component.literal("Music Selector").withStyle(ChatFormatting.LIGHT_PURPLE)));
	}

	private static SimpleContainer buildDisplay(ServerPlayer player, MusicSelectorBlockEntity station, List<Track> songs) {
		MusicState s = MusicState.get(player.level().getServer());
		var tracks = Track.all(player.level().getServer());
		SimpleContainer display = new SimpleContainer(36);
		for (int i = SONG_SLOTS; i < 36; i++) display.setItem(i, item(Items.STAINED_GLASS_PANE.gray(), Component.literal(" ")));

		for (int i = 0; i < songs.size(); i++) {
			Track t = songs.get(i);
			display.setItem(i, item(t.icon(), Component.literal(t.name()).withStyle(ChatFormatting.AQUA),
					gray(Money.format(s.priceOf(t.id())) + " Money"),
					gray(MusicSelector.formatTime(t.length()))));
		}

		List<Component> info = new ArrayList<>();
		if (station.current != null) {
			long left = Math.max(0, (station.endsAt - player.level().getServer().getTickCount()) / 20);
			Track t = tracks.get(station.current.id());
			info.add(Component.literal("Now Playing: ").withStyle(ChatFormatting.LIGHT_PURPLE)
					.append(Component.literal((t == null ? station.current.id() : t.name()) + " ").withStyle(ChatFormatting.WHITE))
					.append(gray("(" + MusicSelector.formatTime((int) left) + " left)")));
		} else {
			info.add(gray("Nothing is playing."));
		}
		if (!station.queue.isEmpty()) {
			info.add(Component.literal("Up next:").withStyle(ChatFormatting.YELLOW));
			for (int i = 0; i < station.queue.size(); i++) {
				var q = station.queue.get(i);
				Track t = tracks.get(q.id());
				info.add(Component.literal(" " + (i + 1) + ". " + (t == null ? q.id() : t.name()) + " - " + q.by()).withStyle(ChatFormatting.WHITE));
			}
		}
		info.add(Component.literal("Balance: ").withStyle(ChatFormatting.WHITE)
				.append(Component.literal(Money.format(Money.get(player)) + " Money").withStyle(ChatFormatting.GREEN)));
		int wait = MusicSelector.cooldownLeft(player);
		if (wait > 0) info.add(Component.literal("You can queue another song in " + MusicSelector.formatTime(wait) + ".").withStyle(ChatFormatting.RED));
		if (songs.isEmpty()) info.add(gray("No songs can be queued right now."));
		display.setItem(INFO_SLOT, item(Items.JUKEBOX, Component.literal("Music Selector").withStyle(ChatFormatting.LIGHT_PURPLE), info.toArray(Component[]::new)));

		if (player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
			display.setItem(SETTINGS_SLOT, item(Items.COMPARATOR, Component.literal("Settings").withStyle(ChatFormatting.GOLD),
					gray("Price, cooldown, songs and autoplay"), gray("(for every selector)")));
			if (station.current != null) display.setItem(SKIP_SLOT, item(Items.SPECTRAL_ARROW, Component.literal("Skip song").withStyle(ChatFormatting.RED)));
			if (station.current != null || !station.queue.isEmpty()) {
				display.setItem(STOP_SLOT, item(Items.BARRIER, Component.literal("Stop and clear queue").withStyle(ChatFormatting.RED)));
			}
		}
		return display;
	}

	private static MutableComponent gray(String text) {
		return Component.literal(text).withStyle(ChatFormatting.GRAY);
	}

	private static ItemStack item(Item item, Component name, Component... lore) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.ITEM_NAME, name);
		if (lore.length > 0) {
			stack.set(DataComponents.LORE, new ItemLore(
					List.of(lore).stream().map(t -> (Component) t.copy().withStyle(s -> s.withItalic(false))).toList()));
		}
		stack.set(DataComponents.TOOLTIP_DISPLAY, net.minecraft.world.item.component.TooltipDisplay.DEFAULT.withHidden(DataComponents.JUKEBOX_PLAYABLE, true)); // hides the disc's song line
		return stack;
	}

	@Override
	public void clicked(int slotIndex, int button, ContainerInput actionType, Player clicker) {
		// Nothing in the menu (or the player's inventory, while it's open) moves.
		if (actionType == ContainerInput.PICKUP) {
			Runnable action = null;
			if (slotIndex >= 0 && slotIndex < songs.size()) {
				Track track = songs.get(slotIndex);
				action = () -> MusicSelector.queueSong(player, station, track);
			} else if (player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
				if (slotIndex == SETTINGS_SLOT) action = () -> MusicSelector.showSettings(player.createCommandSourceStack());
				else if (slotIndex == SKIP_SLOT && station.current != null) action = station::skip;
				else if (slotIndex == STOP_SLOT) action = station::clear;
			}
			if (action != null) {
				Runnable run = action;
				pending.add(() -> {
					if (player.isRemoved() || !(player.containerMenu instanceof MusicMenu)) return;
					player.closeContainer();
					if (!station.isRemoved()) run.run();
				});
			}
		}
		sendAllDataToRemote();
	}

	@Override
	public ItemStack quickMoveStack(Player clicker, int slot) {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean stillValid(Player clicker) {
		return !station.isRemoved() && clicker.distanceToSqr(Vec3.atCenterOf(station.getBlockPos())) < 64;
	}
}
