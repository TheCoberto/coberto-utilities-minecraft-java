package com.thecoberto.utilities.prank;

import net.minecraft.world.entity.EntitySpawnReason;

import net.minecraft.world.entity.EntityTypes;

import java.util.List;
import java.util.UUID;

import com.thecoberto.utilities.mixin.DisplayEntityAccessor;
import com.thecoberto.utilities.mixin.TextDisplayAccessor;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Display;
import com.mojang.serialization.Codec;

import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/** One Prank Button's setup, saved with the button. */
public class PrankButtonBlockEntity extends BlockEntity {
	static final String LABEL_TAG = "coberto_prank_label";
	static final String DEFAULT_MESSAGE = "Don't Push This Button";

	String owner = "";
	UUID ownerId;
	String message = DEFAULT_MESSAGE;
	String prank = "anvils";
	int cooldown = 5; // seconds before the button works again
	boolean ownerSafe = true; // the owner can press it without getting pranked
	boolean announce = true; // tell everyone nearby who pressed it
	int anvilHeight = 25; // blocks above them the anvil shower drops from
	boolean anvilsKill = true; // anvils kill even through full netherite with Protection
	int chickenCount = 12; // killer chickens in the swarm
	int chickenSeconds = 20; // how long the swarm chases them
	String peck = "medium"; // how hard killer chickens peck
	int launchHeight = 30; // blocks the launch pranks send them up
	int slowFallSeconds = 30; // slow falling after the slow-fall launch

	/** Server tick it was last pressed (not saved). */
	long lastPress = Long.MIN_VALUE;

	public PrankButtonBlockEntity(BlockPos worldPosition, BlockState state) {
		super(PrankButton.BLOCK_ENTITY, worldPosition, state);
	}

	boolean isOwner(UUID player) {
		return ownerId != null && ownerId.equals(player);
	}

	void changed() {
		setChanged();
		updateLabel();
	}

	@Override
	protected void saveAdditional(ValueOutput nbt) {
		super.saveAdditional(nbt);
		nbt.putString("owner", owner);
		if (ownerId != null) nbt.store("ownerId", UUIDUtil.CODEC, ownerId);
		nbt.putString("message", message);
		nbt.putString("prank", prank);
		nbt.putInt("cooldown", cooldown);
		nbt.putBoolean("ownerSafe", ownerSafe);
		nbt.putBoolean("announce", announce);
		nbt.putInt("anvilHeight", anvilHeight);
		nbt.putBoolean("anvilsKill", anvilsKill);
		nbt.putInt("chickenCount", chickenCount);
		nbt.putInt("chickenSeconds", chickenSeconds);
		nbt.putString("peck", peck);
		nbt.putInt("launchHeight", launchHeight);
		nbt.putInt("slowFallSeconds", slowFallSeconds);
	}

	@Override
	protected void loadAdditional(ValueInput nbt) {
		super.loadAdditional(nbt);
		if (nbt.read("prank", Codec.STRING).isEmpty()) return; // a new button: keep the defaults
		owner = nbt.getStringOr("owner", "");
		ownerId = nbt.read("ownerId", UUIDUtil.CODEC).orElse(null);
		message = nbt.getStringOr("message", "");
		prank = nbt.getStringOr("prank", "");
		cooldown = nbt.getIntOr("cooldown", 0);
		ownerSafe = nbt.getBooleanOr("ownerSafe", false);
		announce = nbt.getBooleanOr("announce", false);
		anvilHeight = nbt.getIntOr("anvilHeight", 0);
		anvilsKill = nbt.getBooleanOr("anvilsKill", false);
		chickenCount = nbt.getIntOr("chickenCount", 0);
		chickenSeconds = nbt.getIntOr("chickenSeconds", 0);
		peck = nbt.getStringOr("peck", "");
		launchHeight = nbt.getIntOr("launchHeight", 0);
		slowFallSeconds = nbt.getIntOr("slowFallSeconds", 0);
	}

	// ---------- The floating sign ----------

	private List<Display.TextDisplay> labels() {
		return ((ServerLevel) level).getEntitiesOfClass(Display.TextDisplay.class,
				new AABB(worldPosition).move(0, 0.9, 0).inflate(0.3), e -> e.entityTags().contains(LABEL_TAG));
	}

	void updateLabel() {
		if (!(level instanceof ServerLevel serverLevel)) return;
		List<Display.TextDisplay> labels = labels();
		Display.TextDisplay label;
		if (labels.isEmpty()) {
			label = EntityTypes.TEXT_DISPLAY.create(serverLevel, EntitySpawnReason.TRIGGERED);
			if (label == null) return;
			label.snapTo(worldPosition.getX() + 0.5, worldPosition.getY() + 0.9, worldPosition.getZ() + 0.5, 0, 0);
			label.addTag(LABEL_TAG);
			((DisplayEntityAccessor) label).coberto$setBillboardMode(Display.BillboardConstraints.CENTER);
			serverLevel.addFreshEntity(label);
		} else {
			label = labels.get(0);
			for (int i = 1; i < labels.size(); i++) labels.get(i).discard();
		}
		((TextDisplayAccessor) label).coberto$setText(Component.literal(message).withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
	}

	private int ticks;

	/** Every 2 seconds, puts the sign back if it's missing (placed by a command, or the sign was killed). */
	static void tick(PrankButtonBlockEntity button) {
		if (++button.ticks % 40 == 0 && button.labels().isEmpty()) button.updateLabel();
	}

	/** The button was broken: take its sign with it. */
	@Override
	public void preRemoveSideEffects(BlockPos blockPos, BlockState state) {
		removeLabel();
		super.preRemoveSideEffects(blockPos, state);
	}

	void removeLabel() {
		if (level instanceof ServerLevel) for (var label : labels()) label.discard();
	}
}
