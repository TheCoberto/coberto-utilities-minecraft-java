package com.thecoberto.utilities.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.text.Text;

/** Text displays only set their text from NBT; this lets the Music Selector set it directly. */
@Mixin(DisplayEntity.TextDisplayEntity.class)
public interface TextDisplayAccessor {
	@Invoker("setText")
	void coberto$setText(Text text);
}
