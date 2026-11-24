package com.thecoberto.utilities.music;

import com.mojang.serialization.MapCodec;

import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** The Music Selector block. Using it opens the song menu. */
public class MusicSelectorBlock extends BaseEntityBlock {
	public static final MapCodec<MusicSelectorBlock> CODEC = simpleCodec(MusicSelectorBlock::new);

	public MusicSelectorBlock(Properties settings) {
		super(settings);
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new MusicSelectorBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state, BlockEntityType<T> type) {
		if (world.isClientSide()) return null;
		return createTickerHelper(type, MusicSelector.BLOCK_ENTITY,
				(w, pos, s, station) -> MusicSelectorBlockEntity.tick((ServerLevel) w, station));
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player, BlockHitResult hit) {
		if (player instanceof ServerPlayer serverPlayer
				&& world.getBlockEntity(pos) instanceof MusicSelectorBlockEntity station) {
			MusicMenu.open(serverPlayer, station);
		}
		return InteractionResult.SUCCESS;
	}
}
