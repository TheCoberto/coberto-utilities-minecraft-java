package com.thecoberto.utilities.music;

import java.util.ArrayList;
import java.util.List;

import com.thecoberto.utilities.Money;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Unit;

/**
 * The song menu: a four-row chest with a disc for every song players can
 * queue (and its price), what's playing and the queue on the jukebox, and for
 * operators Settings, Skip and Stop buttons. Items in it can't be taken.
 */
public class MusicMenu extends GenericContainerScreenHandler {
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

	private final ServerPlayerEntity player;
	private final MusicSelectorBlockEntity station;
	private final List<Track> songs;

	private MusicMenu(int syncId, PlayerInventory inventory, ServerPlayerEntity player, MusicSelectorBlockEntity station, List<Track> songs) {
		super(ScreenHandlerType.GENERIC_9X4, syncId, inventory, buildDisplay(player, station, songs), 4);
		this.player = player;
		this.station = station;
		this.songs = songs;
	}

	static void open(ServerPlayerEntity player, MusicSelectorBlockEntity station) {
		MusicState s = MusicState.get(player.server);
		var tracks = Track.all(player.server);
		List<Track> songs = s.allowed.stream().map(tracks::get).filter(t -> t != null).limit(SONG_SLOTS).toList();
		player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
				(syncId, inventory, p) -> new MusicMenu(syncId, inventory, player, station, songs),
				Text.literal("Music Selector").formatted(Formatting.LIGHT_PURPLE)));
	}

	private static SimpleInventory buildDisplay(ServerPlayerEntity player, MusicSelectorBlockEntity station, List<Track> songs) {
		MusicState s = MusicState.get(player.server);
		var tracks = Track.all(player.server);
		SimpleInventory display = new SimpleInventory(36);
		for (int i = SONG_SLOTS; i < 36; i++) display.setStack(i, item(Items.GRAY_STAINED_GLASS_PANE, Text.literal(" ")));

		for (int i = 0; i < songs.size(); i++) {
			Track t = songs.get(i);
			display.setStack(i, item(t.icon(), Text.literal(t.name()).formatted(Formatting.AQUA),
					gray(Money.format(s.priceOf(t.id())) + " Money"),
					gray(MusicSelector.formatTime(t.length()))));
		}

		List<Text> info = new ArrayList<>();
		if (station.current != null) {
			long left = Math.max(0, (station.endsAt - player.server.getTicks()) / 20);
			Track t = tracks.get(station.current.id());
			info.add(Text.literal("Now Playing: ").formatted(Formatting.LIGHT_PURPLE)
					.append(Text.literal((t == null ? station.current.id() : t.name()) + " ").formatted(Formatting.WHITE))
					.append(gray("(" + MusicSelector.formatTime((int) left) + " left)")));
		} else {
			info.add(gray("Nothing is playing."));
		}
		if (!station.queue.isEmpty()) {
			info.add(Text.literal("Up next:").formatted(Formatting.YELLOW));
			for (int i = 0; i < station.queue.size(); i++) {
				var q = station.queue.get(i);
				Track t = tracks.get(q.id());
				info.add(Text.literal(" " + (i + 1) + ". " + (t == null ? q.id() : t.name()) + " - " + q.by()).formatted(Formatting.WHITE));
			}
		}
		info.add(Text.literal("Balance: ").formatted(Formatting.WHITE)
				.append(Text.literal(Money.format(Money.get(player)) + " Money").formatted(Formatting.GREEN)));
		int wait = MusicSelector.cooldownLeft(player);
		if (wait > 0) info.add(Text.literal("You can queue another song in " + MusicSelector.formatTime(wait) + ".").formatted(Formatting.RED));
		if (songs.isEmpty()) info.add(gray("No songs can be queued right now."));
		display.setStack(INFO_SLOT, item(Items.JUKEBOX, Text.literal("Music Selector").formatted(Formatting.LIGHT_PURPLE), info.toArray(Text[]::new)));

		if (player.hasPermissionLevel(2)) {
			display.setStack(SETTINGS_SLOT, item(Items.COMPARATOR, Text.literal("Settings").formatted(Formatting.GOLD),
					gray("Price, cooldown, songs and autoplay"), gray("(for every selector)")));
			if (station.current != null) display.setStack(SKIP_SLOT, item(Items.SPECTRAL_ARROW, Text.literal("Skip song").formatted(Formatting.RED)));
			if (station.current != null || !station.queue.isEmpty()) {
				display.setStack(STOP_SLOT, item(Items.BARRIER, Text.literal("Stop and clear queue").formatted(Formatting.RED)));
			}
		}
		return display;
	}

	private static MutableText gray(String text) {
		return Text.literal(text).formatted(Formatting.GRAY);
	}

	private static ItemStack item(Item item, Text name, Text... lore) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponentTypes.ITEM_NAME, name);
		if (lore.length > 0) {
			stack.set(DataComponentTypes.LORE, new LoreComponent(
					List.of(lore).stream().map(t -> (Text) t.copy().styled(s -> s.withItalic(false))).toList()));
		}
		stack.set(DataComponentTypes.HIDE_ADDITIONAL_TOOLTIP, Unit.INSTANCE); // hides the disc's song line
		return stack;
	}

	@Override
	public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity clicker) {
		// Nothing in the menu (or the player's inventory, while it's open) moves.
		if (actionType == SlotActionType.PICKUP) {
			Runnable action = null;
			if (slotIndex >= 0 && slotIndex < songs.size()) {
				Track track = songs.get(slotIndex);
				action = () -> MusicSelector.queueSong(player, station, track);
			} else if (player.hasPermissionLevel(2)) {
				if (slotIndex == SETTINGS_SLOT) action = () -> MusicSelector.showSettings(player.getCommandSource());
				else if (slotIndex == SKIP_SLOT && station.current != null) action = station::skip;
				else if (slotIndex == STOP_SLOT) action = station::clear;
			}
			if (action != null) {
				Runnable run = action;
				pending.add(() -> {
					if (player.isRemoved() || !(player.currentScreenHandler instanceof MusicMenu)) return;
					player.closeHandledScreen();
					if (!station.isRemoved()) run.run();
				});
			}
		}
		syncState();
	}

	@Override
	public ItemStack quickMove(PlayerEntity clicker, int slot) {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean canUse(PlayerEntity clicker) {
		return !station.isRemoved() && clicker.squaredDistanceTo(station.getPos().toCenterPos()) < 64;
	}
}
