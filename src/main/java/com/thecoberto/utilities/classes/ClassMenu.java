package com.thecoberto.utilities.classes;

import java.util.ArrayList;
import java.util.List;

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
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * The /class menu: a two-row chest with one item per class (hover to read
 * what it does) and a barrier for "no class". Clicking one picks it. Items in
 * it can't be taken.
 */
public class ClassMenu extends GenericContainerScreenHandler {
	private static final int NONE_SLOT = 17;
	private static final List<PlayerClass> ORDER = new ArrayList<>(PlayerClass.ALL.values());
	/** Picks made in the menu, run at the end of the tick (not in the middle of handling the click). */
	private static final List<Runnable> pending = new ArrayList<>();

	static void registerEvents() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			List<Runnable> tasks = new ArrayList<>(pending);
			pending.clear();
			tasks.forEach(Runnable::run);
		});
	}

	private final ServerPlayerEntity player;

	private ClassMenu(int syncId, PlayerInventory inventory, ServerPlayerEntity player) {
		super(ScreenHandlerType.GENERIC_9X2, syncId, inventory, buildDisplay(player), 2);
		this.player = player;
	}

	static void open(ServerPlayerEntity player) {
		PlayerClass current = Classes.classOf(player);
		Text title = current == null
				? Text.literal("Pick a class")
				: Text.literal("Class: ").append(Text.literal(current.name()).formatted(current.color()));
		player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
				(syncId, inventory, p) -> new ClassMenu(syncId, inventory, player), title));
	}

	private static SimpleInventory buildDisplay(ServerPlayerEntity player) {
		PlayerClass current = Classes.classOf(player);
		SimpleInventory display = new SimpleInventory(18);
		for (int i = 0; i < ORDER.size(); i++) {
			PlayerClass c = ORDER.get(i);
			ItemStack stack = icon(c.icon(), Text.literal(c.name() + (c == current ? " (current)" : "")).formatted(c.color()), c.about());
			if (c == current) stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
			display.setStack(i, stack);
		}
		display.setStack(NONE_SLOT, icon(Items.BARRIER, Text.literal("No class").formatted(Formatting.RED), "Remove your class."));
		return display;
	}

	private static ItemStack icon(Item item, Text name, String about) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponentTypes.ITEM_NAME, name);
		stack.set(DataComponentTypes.LORE, new LoreComponent(wrap(about)));
		// Hide the sword damage, armor and so on.
		stack.set(DataComponentTypes.HIDE_ADDITIONAL_TOOLTIP, net.minecraft.util.Unit.INSTANCE);
		stack.set(DataComponentTypes.ATTRIBUTE_MODIFIERS, stack.getOrDefault(DataComponentTypes.ATTRIBUTE_MODIFIERS,
				net.minecraft.component.type.AttributeModifiersComponent.DEFAULT).withShowInTooltip(false));
		return stack;
	}

	/** Lore lines of about 40 characters, gray and not italic. */
	private static List<Text> wrap(String text) {
		List<Text> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			if (line.length() > 0 && line.length() + word.length() > 40) {
				lines.add(Text.literal(line.toString()).styled(s -> s.withColor(Formatting.GRAY).withItalic(false)));
				line.setLength(0);
			}
			if (line.length() > 0) line.append(' ');
			line.append(word);
		}
		if (line.length() > 0) lines.add(Text.literal(line.toString()).styled(s -> s.withColor(Formatting.GRAY).withItalic(false)));
		return lines;
	}

	@Override
	public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity clicker) {
		// Nothing in the menu (or the player's inventory, while it's open) moves.
		if (actionType == SlotActionType.PICKUP && (slotIndex == NONE_SLOT || (slotIndex >= 0 && slotIndex < ORDER.size()))) {
			PlayerClass picked = slotIndex == NONE_SLOT ? null : ORDER.get(slotIndex);
			pending.add(() -> {
				if (player.isRemoved() || !(player.currentScreenHandler instanceof ClassMenu)) return;
				player.closeHandledScreen();
				if (Classes.classOf(player) != picked) Classes.setClass(player, picked);
			});
		}
		syncState();
	}

	@Override
	public ItemStack quickMove(PlayerEntity clicker, int slot) {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean canUse(PlayerEntity clicker) {
		return true;
	}
}
