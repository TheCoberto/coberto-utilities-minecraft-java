package com.thecoberto.utilities.music;

import java.util.ArrayList;
import java.util.List;

import com.thecoberto.utilities.mixin.DisplayEntityAccessor;
import com.thecoberto.utilities.mixin.TextDisplayAccessor;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.network.packet.s2c.play.StopSoundS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * One Music Selector's "station": what it's playing and its queue. Like on
 * Bedrock, queues aren't saved: they reset when the world restarts, and a
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
	private DisplayEntity.TextDisplayEntity lastLabel;
	private Text lastLabelText;

	public MusicSelectorBlockEntity(BlockPos pos, BlockState state) {
		super(MusicSelector.BLOCK_ENTITY, pos, state);
	}

	ServerWorld serverWorld() {
		return (ServerWorld) world;
	}

	// ---------- Playing ----------

	static void tick(ServerWorld world, MusicSelectorBlockEntity station) {
		MusicSelector.LOADED.add(station);
		if (++station.ticks % 20 != 0) return;
		MusicState settings = MusicState.get(world.getServer());
		boolean due = station.current != null
				? world.getServer().getTicks() >= station.endsAt
				: !station.queue.isEmpty() || settings.autoplay;
		if (due) station.playNext();
		station.updateLabel();
	}

	private Vec3d center() {
		return Vec3d.ofCenter(pos);
	}

	List<ServerPlayerEntity> nearbyPlayers() {
		return serverWorld().getPlayers(p -> p.squaredDistanceTo(center()) <= HEAR_RANGE * HEAR_RANGE);
	}

	void tellNearby(Text message) {
		for (ServerPlayerEntity p : nearbyPlayers()) p.sendMessage(message);
	}

	private void play(Entry entry) {
		Track track = Track.all(serverWorld().getServer()).get(entry.id());
		if (track == null) return;
		current = entry;
		endsAt = serverWorld().getServer().getTicks() + track.length() * 20L;
		Vec3d c = center();
		// Volume 4 = heard up to 64 blocks away, like a jukebox.
		serverWorld().playSound(null, c.x, c.y, c.z, track.sound(), SoundCategory.RECORDS, 4.0f, 1.0f, serverWorld().getRandom().nextLong());
		MutableText message = MusicSelector.prefix().append("Now playing ")
				.append(Text.literal(track.name()).formatted(Formatting.AQUA));
		if (entry.by() != null) message.append(" (queued by " + entry.by() + ")");
		tellNearby(message.append("."));
	}

	void stopCurrent() {
		if (current == null) return;
		Track track = Track.all(serverWorld().getServer()).get(current.id());
		if (track != null) {
			var packet = new StopSoundS2CPacket(track.sound().value().getId(), SoundCategory.RECORDS);
			for (ServerPlayerEntity p : nearbyPlayers()) p.networkHandler.sendPacket(packet);
		}
		current = null;
	}

	private Entry nextAutoplay() {
		MusicState s = MusicState.get(serverWorld().getServer());
		if (!s.autoplay || s.playlist.isEmpty()) return null;
		String id;
		if (s.shuffle) {
			List<String> choices = new ArrayList<>(s.playlist);
			if (choices.size() > 1 && current != null) choices.remove(current.id());
			id = choices.get(serverWorld().getRandom().nextInt(choices.size()));
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
		Track track = Track.all(serverWorld().getServer()).get(current.id());
		tellNearby(MusicSelector.prefix().append("Skipped ")
				.append(Text.literal(track == null ? current.id() : track.name()).formatted(Formatting.AQUA)).append("."));
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

	private Text labelText() {
		var tracks = Track.all(serverWorld().getServer());
		if (current == null) {
			return Text.empty().append(Text.literal("Music Selector").formatted(Formatting.LIGHT_PURPLE))
					.append("\n").append(Text.literal("Use me to pick a song!").formatted(Formatting.GRAY));
		}
		MutableText text = Text.empty().append(Text.literal("Now Playing: ").formatted(Formatting.LIGHT_PURPLE))
				.append(Text.literal(name(tracks.get(current.id()), current.id())).formatted(Formatting.WHITE));
		if (!queue.isEmpty()) {
			text.append("\n").append(Text.literal("Up next: " + name(tracks.get(queue.get(0).id()), queue.get(0).id())).formatted(Formatting.GRAY));
		}
		return text;
	}

	private static String name(Track track, String id) {
		return track == null ? id : track.name();
	}

	private Box labelBox() {
		return new Box(pos.up()).expand(0.5);
	}

	private List<DisplayEntity.TextDisplayEntity> labels() {
		return serverWorld().getEntitiesByClass(DisplayEntity.TextDisplayEntity.class, labelBox(),
				e -> e.getCommandTags().contains(LABEL_TAG));
	}

	void updateLabel() {
		List<DisplayEntity.TextDisplayEntity> labels = labels();
		DisplayEntity.TextDisplayEntity label;
		if (labels.isEmpty()) {
			label = EntityType.TEXT_DISPLAY.create(serverWorld());
			if (label == null) return;
			label.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY() + 1.25, pos.getZ() + 0.5, 0, 0);
			label.addCommandTag(LABEL_TAG);
			((DisplayEntityAccessor) label).coberto$setBillboardMode(DisplayEntity.BillboardMode.CENTER);
			serverWorld().spawnEntity(label);
		} else {
			label = labels.get(0);
			for (int i = 1; i < labels.size(); i++) labels.get(i).discard();
		}
		Text text = labelText();
		if (label != lastLabel || !text.equals(lastLabelText)) {
			((TextDisplayAccessor) label).coberto$setText(text);
			lastLabel = label;
			lastLabelText = text;
		}
	}

	@Override
	public void markRemoved() {
		super.markRemoved();
		MusicSelector.LOADED.remove(this);
	}

	/** The selector was broken: stop the song and remove the label. */
	void removed() {
		stopCurrent();
		queue.clear();
		for (var label : labels()) label.discard();
	}
}
