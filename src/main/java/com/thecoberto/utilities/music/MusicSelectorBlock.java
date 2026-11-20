package com.thecoberto.utilities.music;

import com.mojang.serialization.MapCodec;

import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** The Music Selector block. Using it opens the song menu. */
public class MusicSelectorBlock extends BlockWithEntity {
	public static final MapCodec<MusicSelectorBlock> CODEC = createCodec(MusicSelectorBlock::new);

	public MusicSelectorBlock(Settings settings) {
		super(settings);
	}

	@Override
	protected MapCodec<? extends BlockWithEntity> getCodec() {
		return CODEC;
	}

	@Override
	protected BlockRenderType getRenderType(BlockState state) {
		return BlockRenderType.MODEL;
	}

	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return new MusicSelectorBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
		if (world.isClient) return null;
		return validateTicker(type, MusicSelector.BLOCK_ENTITY,
				(w, pos, s, station) -> MusicSelectorBlockEntity.tick((ServerWorld) w, station));
	}

	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (player instanceof ServerPlayerEntity serverPlayer
				&& world.getBlockEntity(pos) instanceof MusicSelectorBlockEntity station) {
			MusicMenu.open(serverPlayer, station);
		}
		return ActionResult.success(world.isClient);
	}

	@Override
	protected void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
		if (!state.isOf(newState.getBlock()) && !world.isClient
				&& world.getBlockEntity(pos) instanceof MusicSelectorBlockEntity station) {
			station.removed();
		}
		super.onStateReplaced(state, world, pos, newState, moved);
	}
}
