package com.thecoberto.utilities;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Coberto's Utilities: Java port of the Bedrock bundle of world utilities.
 * Each feature lives in its own class.
 */
public class CobertoUtilities implements ModInitializer {
	@Override
	public void onInitialize() {
		com.thecoberto.utilities.crates.Crates.registerBlocks();
		MobMoney.registerEvents();
		com.thecoberto.utilities.zones.AdventureZones.registerEvents();
		ActivityRewards.registerEvents();
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			CreeperGriefing.register(dispatcher);
			GamemodeShortcuts.register(dispatcher);
			MobMoney.register(dispatcher, registryAccess);
			ActivityRewards.register(dispatcher);
			com.thecoberto.utilities.crates.Crates.register(dispatcher, registryAccess);
			com.thecoberto.utilities.zones.AdventureZones.register(dispatcher);
		});
	}

	/** "Utilities: " in gold, on an unstyled root so text appended after it isn't gold too. */
	static MutableText prefix() {
		return Text.empty().append(Text.literal("Utilities:").formatted(Formatting.GOLD)).append(" ");
	}
}
