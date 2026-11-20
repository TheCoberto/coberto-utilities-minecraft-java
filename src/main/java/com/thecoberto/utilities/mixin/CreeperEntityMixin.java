package com.thecoberto.utilities.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import com.thecoberto.utilities.CreeperGriefing;

import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.world.World;

@Mixin(CreeperEntity.class)
public abstract class CreeperEntityMixin {
	/**
	 * Creepers explode as a MOB explosion, which breaks blocks. Unless creeper
	 * griefing is on, make it a NONE explosion instead: same blast and damage,
	 * but blocks are left alone.
	 */
	@ModifyArg(
			method = "explode",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;createExplosion(Lnet/minecraft/entity/Entity;DDDFLnet/minecraft/world/World$ExplosionSourceType;)Lnet/minecraft/world/explosion/Explosion;"),
			index = 5)
	private World.ExplosionSourceType coberto$keepBlocks(World.ExplosionSourceType type) {
		CreeperEntity creeper = (CreeperEntity) (Object) this;
		var server = creeper.getServer();
		if (server == null || CreeperGriefing.creepersBreakBlocks(server)) return type;
		return World.ExplosionSourceType.NONE;
	}
}
