package com.thecoberto.utilities.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.world.entity.Display;

/** Lets the Music Selector make its label always face the player. */
@Mixin(Display.class)
public interface DisplayEntityAccessor {
	@Invoker("setBillboardConstraints")
	void coberto$setBillboardMode(Display.BillboardConstraints mode);
}
