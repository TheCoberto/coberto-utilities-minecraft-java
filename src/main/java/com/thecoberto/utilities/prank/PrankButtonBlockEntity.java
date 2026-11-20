package com.thecoberto.utilities.prank;

import java.util.List;
import java.util.UUID;

import com.thecoberto.utilities.mixin.DisplayEntityAccessor;
import com.thecoberto.utilities.mixin.TextDisplayAccessor;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

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

	public PrankButtonBlockEntity(BlockPos pos, BlockState state) {
		super(PrankButton.BLOCK_ENTITY, pos, state);
	}

	boolean isOwner(UUID player) {
		return ownerId != null && ownerId.equals(player);
	}

	void changed() {
		markDirty();
		updateLabel();
	}

	@Override
	protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		super.writeNbt(nbt, registries);
		nbt.putString("owner", owner);
		if (ownerId != null) nbt.putUuid("ownerId", ownerId);
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
	protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		super.readNbt(nbt, registries);
		if (!nbt.contains("prank")) return; // a new button: keep the defaults
		owner = nbt.getString("owner");
		ownerId = nbt.containsUuid("ownerId") ? nbt.getUuid("ownerId") : null;
		message = nbt.getString("message");
		prank = nbt.getString("prank");
		cooldown = nbt.getInt("cooldown");
		ownerSafe = nbt.getBoolean("ownerSafe");
		announce = nbt.getBoolean("announce");
		anvilHeight = nbt.getInt("anvilHeight");
		anvilsKill = nbt.getBoolean("anvilsKill");
		chickenCount = nbt.getInt("chickenCount");
		chickenSeconds = nbt.getInt("chickenSeconds");
		peck = nbt.getString("peck");
		launchHeight = nbt.getInt("launchHeight");
		slowFallSeconds = nbt.getInt("slowFallSeconds");
	}

	// ---------- The floating sign ----------

	private List<DisplayEntity.TextDisplayEntity> labels() {
		return ((ServerWorld) world).getEntitiesByClass(DisplayEntity.TextDisplayEntity.class,
				new Box(pos).offset(0, 0.9, 0).expand(0.3), e -> e.getCommandTags().contains(LABEL_TAG));
	}

	void updateLabel() {
		if (!(world instanceof ServerWorld serverWorld)) return;
		List<DisplayEntity.TextDisplayEntity> labels = labels();
		DisplayEntity.TextDisplayEntity label;
		if (labels.isEmpty()) {
			label = EntityType.TEXT_DISPLAY.create(serverWorld);
			if (label == null) return;
			label.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY() + 0.9, pos.getZ() + 0.5, 0, 0);
			label.addCommandTag(LABEL_TAG);
			((DisplayEntityAccessor) label).coberto$setBillboardMode(DisplayEntity.BillboardMode.CENTER);
			serverWorld.spawnEntity(label);
		} else {
			label = labels.get(0);
			for (int i = 1; i < labels.size(); i++) labels.get(i).discard();
		}
		((TextDisplayAccessor) label).coberto$setText(Text.literal(message).formatted(Formatting.RED, Formatting.BOLD));
	}

	private int ticks;

	/** Every 2 seconds, puts the sign back if it's missing (placed by a command, or the sign was killed). */
	static void tick(PrankButtonBlockEntity button) {
		if (++button.ticks % 40 == 0 && button.labels().isEmpty()) button.updateLabel();
	}

	void removeLabel() {
		if (world instanceof ServerWorld) for (var label : labels()) label.discard();
	}
}
