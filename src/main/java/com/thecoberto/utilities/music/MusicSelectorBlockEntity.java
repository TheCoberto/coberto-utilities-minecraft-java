package com.thecoberto.utilities.music;

import net.minecraft.world.entity.EntitySpawnReason;

import net.minecraft.world.entity.EntityTypes;

import java.util.ArrayList;
import java.util.List;

import com.thecoberto.utilities.mixin.DisplayEntityAccessor;
import com.thecoberto.utilities.mixin.TextDisplayAccessor;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Display;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * One Music Selector's "station": what it's playing and its queue. Like on
 * Bedrock, queues aren't saved: they reset when the level restarts, and a
 * station only moves on while its selector is loaded.
 */
public class MusicSelectorBlockEntity extends BlockEntity {
	static final String LABEL_TAG = "coberto_music_label";
	static final int HEAR_RANGE = 64; // blocks: who gets chat messages and has the song stopped

	record Entry(String id, String by) {}

	Entry current;
	long endsAt;
	final List<Entry> queue = new ArrayList<>();
	private int autoplayIndex;
	private int ticks;
	private Display.TextDisplay lastLabel;
	private Component lastLabelText;

	public MusicSelectorBlockEntity(BlockPos worldPosition, BlockState state) {
		super(MusicSelector.BLOCK_ENTITY, worldPosition, state);
	}

	ServerLevel serverLevel() {
		return (ServerLevel) level;
	}

	// ---------- Playing ----------

	static void tick(ServerLevel level, MusicSelectorBlockEntity station) {
		MusicSelector.LOADED.add(station);
		if (++station.ticks % 20 != 0) return;
		MusicState settings = MusicState.get(level.getServer());
		boolean due = station.current != null
				? level.getServer().getTickCount() >= station.endsAt
				: !station.queue.isEmpty() || settings.autoplay;
		if (due) station.playNext();
		station.updateLabel();
	}

	private Vec3 center() {
		return Vec3.atCenterOf(worldPosition);
	}

	List<ServerPlayer> nearbyPlayers() {
		return serverLevel().getPlayers(p -> p.distanceToSqr(center()) <= HEAR_RANGE * HEAR_RANGE);
	}

	void tellNearby(Component message) {
		for (ServerPlayer p : nearbyPlayers()) p.sendSystemMessage(message);
	}

	private void play(Entry entry) {
		Track track = Track.all(serverLevel().getServer()).get(entry.id());
		if (track == null) return;
		current = entry;
		endsAt = serverLevel().getServer().getTickCount() + track.length() * 20L;
		Vec3 c = center();
		// Volume 4 = heard up to 64 blocks away, like a jukebox.
		serverLevel().playSound(null, c.x, c.y, c.z, track.sound(), SoundSource.RECORDS, 4.0f, 1.0f);
		MutableComponent message = MusicSelector.prefix().append("Now playing ")
				.append(Component.literal(track.name()).withStyle(ChatFormatting.AQUA));
		if (entry.by() != null) message.append(" (queued by " + entry.by() + ")");
		tellNearby(message.append("."));
	}

	void stopCurrent() {
		if (current == null) return;
		Track track = Track.all(serverLevel().getServer()).get(current.id());
		if (track != null) {
			var packet = new ClientboundStopSoundPacket(track.sound().value().location(), SoundSource.RECORDS);
			for (ServerPlayer p : nearbyPlayers()) p.connection.send(packet);
		}
		current = null;
	}

	private Entry nextAutoplay() {
		MusicState s = MusicState.get(serverLevel().getServer());
		if (!s.autoplay || s.playlist.isEmpty()) return null;
		String id;
		if (s.shuffle) {
			List<String> choices = new ArrayList<>(s.playlist);
			if (choices.size() > 1 && current != null) choices.remove(current.id());
			id = choices.get(serverLevel().getRandom().nextInt(choices.size()));
		} else {
			id = s.playlist.get(autoplayIndex % s.playlist.size());
			autoplayIndex = (autoplayIndex + 1) % s.playlist.size();
		}
		return new Entry(id, null);
	}

	void playNext() {
		stopCurrent();
		Entry next = queue.isEmpty() ? nextAutoplay() : queue.remove(0);
		if (next != null) play(next);
		updateLabel();
	}

	void skip() {
		if (current == null) return;
		Track track = Track.all(serverLevel().getServer()).get(current.id());
		tellNearby(MusicSelector.prefix().append("Skipped ")
				.append(Component.literal(track == null ? current.id() : track.name()).withStyle(ChatFormatting.AQUA)).append("."));
		playNext();
	}

	void clear() {
		if (current == null && queue.isEmpty()) return;
		queue.clear();
		stopCurrent();
		tellNearby(MusicSelector.prefix().append("The music was stopped."));
		updateLabel();
	}

	void resetAutoplay() {
		autoplayIndex = 0;
	}

	// ---------- The floating label ----------

	private Component labelText() {
		var tracks = Track.all(serverLevel().getServer());
		if (current == null) {
			return Component.empty().append(Component.literal("Music Selector").withStyle(ChatFormatting.LIGHT_PURPLE))
					.append("\n").append(Component.literal("Use me to pick a song!").withStyle(ChatFormatting.GRAY));
		}
		MutableComponent text = Component.empty().append(Component.literal("Now Playing: ").withStyle(ChatFormatting.LIGHT_PURPLE))
				.append(Component.literal(name(tracks.get(current.id()), current.id())).withStyle(ChatFormatting.WHITE));
		if (!queue.isEmpty()) {
			text.append("\n").append(Component.literal("Up next: " + name(tracks.get(queue.get(0).id()), queue.get(0).id())).withStyle(ChatFormatting.GRAY));
		}
		return text;
	}

	private static String name(Track track, String id) {
		return track == null ? id : track.name();
	}

	private AABB labelBox() {
		return new AABB(worldPosition.above()).inflate(0.5);
	}

	private List<Display.TextDisplay> labels() {
		return serverLevel().getEntitiesOfClass(Display.TextDisplay.class, labelBox(),
				e -> e.entityTags().contains(LABEL_TAG));
	}

	void updateLabel() {
		List<Display.TextDisplay> labels = labels();
		Display.TextDisplay label;
		if (labels.isEmpty()) {
			label = EntityTypes.TEXT_DISPLAY.create(serverLevel(), EntitySpawnReason.TRIGGERED);
			if (label == null) return;
			label.snapTo(worldPosition.getX() + 0.5, worldPosition.getY() + 1.25, worldPosition.getZ() + 0.5, 0, 0);
			label.addTag(LABEL_TAG);
			((DisplayEntityAccessor) label).coberto$setBillboardMode(Display.BillboardConstraints.CENTER);
			serverLevel().addFreshEntity(label);
		} else {
			label = labels.get(0);
			for (int i = 1; i < labels.size(); i++) labels.get(i).discard();
		}
		Component text = labelText();
		if (label != lastLabel || !text.equals(lastLabelText)) {
			((TextDisplayAccessor) label).coberto$setText(text);
			lastLabel = label;
			lastLabelText = text;
		}
	}

	@Override
	public void setRemoved() {
		super.setRemoved();
		MusicSelector.LOADED.remove(this);
	}

	@Override
	public void preRemoveSideEffects(BlockPos blockPos, BlockState state) {
		if (level instanceof ServerLevel) removed();
		super.preRemoveSideEffects(blockPos, state);
	}

	/** The selector was broken: stop the song and remove the label. */
	void removed() {
		stopCurrent();
		queue.clear();
		for (var label : labels()) label.discard();
	}
}
