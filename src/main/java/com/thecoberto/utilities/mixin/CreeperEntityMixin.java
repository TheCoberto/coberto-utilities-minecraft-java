package com.thecoberto.utilities.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import com.thecoberto.utilities.CreeperGriefing;

import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.Level;

@Mixin(Creeper.class)
public abstract class CreeperEntityMixin {
	/**
	 * Creepers explode as a MOB explosion, which breaks blocks. Unless creeper
	 * griefing is on, make it a NONE explosion instead: same blast and damage,
	 * but blocks are left alone.
	 */
	@ModifyArg(
			method = "explodeCreeper",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;explode(Lnet/minecraft/world/entity/Entity;DDDFLnet/minecraft/world/level/Level$ExplosionInteraction;)V"),
			index = 5)
	private Level.ExplosionInteraction coberto$keepBlocks(Level.ExplosionInteraction type) {
		Creeper creeper = (Creeper) (Object) this;
		var server = creeper.level().getServer();
		if (server == null || CreeperGriefing.creepersBreakBlocks(server)) return type;
		return Level.ExplosionInteraction.NONE;
	}
}
