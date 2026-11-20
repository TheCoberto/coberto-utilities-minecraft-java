package com.thecoberto.utilities.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.entity.decoration.DisplayEntity;

/** Lets the Music Selector make its label always face the player. */
@Mixin(DisplayEntity.class)
public interface DisplayEntityAccessor {
	@Invoker("setBillboardMode")
	void coberto$setBillboardMode(DisplayEntity.BillboardMode mode);
}
