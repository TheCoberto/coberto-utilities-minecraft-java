package com.thecoberto.utilities.prank;

import com.mojang.serialization.MapCodec;

import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/** A big red button. Whoever pushes it gets pranked; its owner sneaks and uses it to set it up. */
public class PrankButtonBlock extends BlockWithEntity {
	public static final MapCodec<PrankButtonBlock> CODEC = createCodec(PrankButtonBlock::new);
	private static final VoxelShape SHAPE = createCuboidShape(3, 0, 3, 13, 5, 13);

	public PrankButtonBlock(Settings settings) {
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
	protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return SHAPE;
	}

	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return new PrankButtonBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
		if (world.isClient) return null;
		return validateTicker(type, PrankButton.BLOCK_ENTITY, (w, pos, s, button) -> PrankButtonBlockEntity.tick(button));
	}

	@Override
	public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.onPlaced(world, pos, state, placer, stack);
		if (world.isClient || !(world.getBlockEntity(pos) instanceof PrankButtonBlockEntity button)) return;
		if (placer instanceof ServerPlayerEntity player) {
			button.owner = player.getName().getString();
			button.ownerId = player.getUuid();
			player.sendMessage(PrankButton.prefix().append("Sneak and use your Prank Button (with an empty hand) to set the message and the prank."));
		}
		button.changed();
	}

	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (player instanceof ServerPlayerEntity serverPlayer && world.getBlockEntity(pos) instanceof PrankButtonBlockEntity button) {
			PrankButton.press(serverPlayer, button);
		}
		return ActionResult.success(world.isClient);
	}

	@Override
	protected void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
		if (!state.isOf(newState.getBlock()) && world.getBlockEntity(pos) instanceof PrankButtonBlockEntity button) {
			button.removeLabel();
		}
		super.onStateReplaced(state, world, pos, newState, moved);
	}
}
