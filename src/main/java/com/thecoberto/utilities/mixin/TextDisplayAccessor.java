package com.thecoberto.utilities.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.world.entity.Display;
import net.minecraft.network.chat.Component;

/** Text displays only set their text from NBT; this lets the Music Selector set it directly. */
@Mixin(Display.TextDisplay.class)
public interface TextDisplayAccessor {
	@Invoker("setText")
	void coberto$setText(Component text);
}
