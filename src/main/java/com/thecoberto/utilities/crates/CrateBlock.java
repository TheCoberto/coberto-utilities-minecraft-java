package com.thecoberto.utilities.crates;

import com.mojang.serialization.MapCodec;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** A loot crate. Using it opens it; breaking it gives nothing. */
public class CrateBlock extends Block {
	private final String key;

	public CrateBlock(String key, Settings settings) {
		super(settings);
		this.key = key;
	}

	@Override
	protected MapCodec<? extends Block> getCodec() {
		return createCodec(settings -> new CrateBlock(key, settings));
	}

	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (world instanceof ServerWorld serverWorld && player instanceof ServerPlayerEntity serverPlayer) {
			Crates.open(serverWorld, pos, serverPlayer, key);
		}
		return ActionResult.success(world.isClient);
	}
}
