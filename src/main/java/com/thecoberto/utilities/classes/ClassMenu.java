package com.thecoberto.utilities.classes;

import java.util.ArrayList;
import java.util.List;

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
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

/**
 * The /class menu: a two-row chest with one item per class (hover to read
 * what it does) and a barrier for "no class". Clicking one picks it. Items in
 * it can't be taken.
 */
public class ClassMenu extends ChestMenu {
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

	private final ServerPlayer player;

	private ClassMenu(int syncId, Inventory inventory, ServerPlayer player) {
		super(MenuType.GENERIC_9x2, syncId, inventory, buildDisplay(player), 2);
		this.player = player;
	}

	static void open(ServerPlayer player) {
		PlayerClass current = Classes.classOf(player);
		Component title = current == null
				? Component.literal("Pick a class")
				: Component.literal("Class: ").append(Component.literal(current.name()).withStyle(current.color()));
		player.openMenu(new SimpleMenuProvider(
				(syncId, inventory, p) -> new ClassMenu(syncId, inventory, player), title));
	}

	private static SimpleContainer buildDisplay(ServerPlayer player) {
		PlayerClass current = Classes.classOf(player);
		SimpleContainer display = new SimpleContainer(18);
		for (int i = 0; i < ORDER.size(); i++) {
			PlayerClass c = ORDER.get(i);
			ItemStack stack = icon(c.icon(), Component.literal(c.name() + (c == current ? " (current)" : "")).withStyle(c.color()), c.about());
			if (c == current) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
			display.setItem(i, stack);
		}
		display.setItem(NONE_SLOT, icon(Items.BARRIER, Component.literal("No class").withStyle(ChatFormatting.RED), "Remove your class."));
		return display;
	}

	private static ItemStack icon(Item item, Component name, String about) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.ITEM_NAME, name);
		stack.set(DataComponents.LORE, new ItemLore(wrap(about)));
		// Hide the sword damage, armor and so on.
		stack.set(DataComponents.TOOLTIP_DISPLAY, net.minecraft.world.item.component.TooltipDisplay.DEFAULT.withHidden(DataComponents.ATTRIBUTE_MODIFIERS, true));
		return stack;
	}

	/** Lore lines of about 40 characters, gray and not italic. */
	private static List<Component> wrap(String text) {
		List<Component> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			if (line.length() > 0 && line.length() + word.length() > 40) {
				lines.add(Component.literal(line.toString()).withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));
				line.setLength(0);
			}
			if (line.length() > 0) line.append(' ');
			line.append(word);
		}
		if (line.length() > 0) lines.add(Component.literal(line.toString()).withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));
		return lines;
	}

	@Override
	public void clicked(int slotIndex, int button, ContainerInput actionType, Player clicker) {
		// Nothing in the menu (or the player's inventory, while it's open) moves.
		if (actionType == ContainerInput.PICKUP && (slotIndex == NONE_SLOT || (slotIndex >= 0 && slotIndex < ORDER.size()))) {
			PlayerClass picked = slotIndex == NONE_SLOT ? null : ORDER.get(slotIndex);
			pending.add(() -> {
				if (player.isRemoved() || !(player.containerMenu instanceof ClassMenu)) return;
				player.closeContainer();
				if (Classes.classOf(player) != picked) Classes.setClass(player, picked);
			});
		}
		sendAllDataToRemote();
	}

	@Override
	public ItemStack quickMoveStack(Player clicker, int slot) {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean stillValid(Player clicker) {
		return true;
	}
}
