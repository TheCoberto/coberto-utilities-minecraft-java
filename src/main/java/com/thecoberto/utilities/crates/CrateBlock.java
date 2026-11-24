package com.thecoberto.utilities.crates;

import com.mojang.serialization.MapCodec;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** A loot crate. Using it opens it; breaking it gives nothing. */
public class CrateBlock extends Block {
	private final String key;

	public CrateBlock(String key, Properties settings) {
		super(settings);
		this.key = key;
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return simpleCodec(settings -> new CrateBlock(key, settings));
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player, BlockHitResult hit) {
		if (world instanceof ServerLevel serverWorld && player instanceof ServerPlayer serverPlayer) {
			Crates.open(serverWorld, pos, serverPlayer, key);
		}
		return InteractionResult.SUCCESS;
	}
}
