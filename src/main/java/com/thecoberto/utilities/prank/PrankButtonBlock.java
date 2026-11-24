package com.thecoberto.utilities.prank;

import com.mojang.serialization.MapCodec;

import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;

/** A big red button. Whoever pushes it gets pranked; its owner sneaks and uses it to set it up. */
public class PrankButtonBlock extends BaseEntityBlock {
	public static final MapCodec<PrankButtonBlock> CODEC = simpleCodec(PrankButtonBlock::new);
	private static final VoxelShape SHAPE = box(3, 0, 3, 13, 5, 13);

	public PrankButtonBlock(Properties settings) {
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
	protected VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new PrankButtonBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level world, BlockState state, BlockEntityType<T> type) {
		if (world.isClientSide()) return null;
		return createTickerHelper(type, PrankButton.BLOCK_ENTITY, (w, pos, s, button) -> PrankButtonBlockEntity.tick(button));
	}

	@Override
	public void setPlacedBy(Level world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(world, pos, state, placer, stack);
		if (world.isClientSide() || !(world.getBlockEntity(pos) instanceof PrankButtonBlockEntity button)) return;
		if (placer instanceof ServerPlayer player) {
			button.owner = player.getName().getString();
			button.ownerId = player.getUUID();
			player.sendSystemMessage(PrankButton.prefix().append("Sneak and use your Prank Button (with an empty hand) to set the message and the prank."));
		}
		button.changed();
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player, BlockHitResult hit) {
		if (player instanceof ServerPlayer serverPlayer && world.getBlockEntity(pos) instanceof PrankButtonBlockEntity button) {
			PrankButton.press(serverPlayer, button);
		}
		return InteractionResult.SUCCESS;
	}
}
