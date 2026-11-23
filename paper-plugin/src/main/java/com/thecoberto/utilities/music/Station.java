package com.thecoberto.utilities.music;

import com.thecoberto.utilities.Labels;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One Music Selector's "station": what it's playing and its queue. Queues
 * aren't saved: they reset when the server restarts, and a station only moves
 * on while its selector is loaded.
 */
final class Station {
	static final String LABEL_TAG = "coberto_music_label";
	static final double LABEL_HEIGHT = 1.25;
	static final int HEAR_RANGE = 64; // blocks: who gets chat messages and has the song stopped

	record Entry(String id, String by) {
	}

	private final MusicSelector music;
	final Block block;
	Entry current;
	long endsAt; // server tick
	final List<Entry> queue = new ArrayList<>();
	private int autoplayIndex;

	Station(MusicSelector music, Block block) {
		this.music = music;
		this.block = block;
	}

	private Location center() {
		return block.getLocation().add(0.5, 0.5, 0.5);
	}

	List<Player> nearbyPlayers() {
		return block.getWorld().getPlayers().stream()
				.filter(p -> p.getLocation().distanceSquared(center()) <= HEAR_RANGE * HEAR_RANGE).toList();
	}

	void tellNearby(Component message) {
		for (Player p : nearbyPlayers()) p.sendMessage(message);
	}

	/** Every second while loaded. */
	void tick() {
		boolean due = current != null ? Bukkit.getCurrentTick() >= endsAt : !queue.isEmpty() || music.autoplay;
		if (due) playNext();
		updateLabel();
	}

	private void play(Entry entry) {
		Track track = Track.all().get(entry.id());
		if (track == null) return;
		current = entry;
		endsAt = Bukkit.getCurrentTick() + track.length() * 20L;
		// Volume 4 = heard up to 64 blocks away, like a jukebox.
		block.getWorld().playSound(center(), track.sound(), SoundCategory.RECORDS, 4f, 1f);
		Component message = MusicSelector.prefix().append(Component.text("Now playing "))
				.append(Component.text(track.name(), NamedTextColor.AQUA));
		if (entry.by() != null) message = message.append(Component.text(" (queued by " + entry.by() + ")"));
		tellNearby(message.append(Component.text(".")));
	}

	void stopCurrent() {
		if (current == null) return;
		Track track = Track.all().get(current.id());
		if (track != null) for (Player p : nearbyPlayers()) p.stopSound(track.sound(), SoundCategory.RECORDS);
		current = null;
	}

	private Entry nextAutoplay() {
		if (!music.autoplay || music.playlist.isEmpty()) return null;
		String id;
		if (music.shuffle) {
			List<String> choices = new ArrayList<>(music.playlist);
			if (choices.size() > 1 && current != null) choices.remove(current.id());
			id = choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
		} else {
			id = music.playlist.get(autoplayIndex % music.playlist.size());
			autoplayIndex = (autoplayIndex + 1) % music.playlist.size();
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
		Track track = Track.all().get(current.id());
		tellNearby(MusicSelector.prefix().append(Component.text("Skipped "))
				.append(Component.text(track == null ? current.id() : track.name(), NamedTextColor.AQUA)).append(Component.text(".")));
		playNext();
	}

	void clear() {
		if (current == null && queue.isEmpty()) return;
		queue.clear();
		stopCurrent();
		tellNearby(MusicSelector.prefix().append(Component.text("The music was stopped.")));
		updateLabel();
	}

	void resetAutoplay() {
		autoplayIndex = 0;
	}

	// ---------- The floating label ----------

	private static String name(String id) {
		Track track = Track.all().get(id);
		return track == null ? id : track.name();
	}

	void updateLabel() {
		Component text;
		if (current == null) {
			text = Component.text("Music Selector", NamedTextColor.LIGHT_PURPLE)
					.append(Component.text("\nUse me to pick a song!", NamedTextColor.GRAY));
		} else {
			text = Component.text("Now Playing: ", NamedTextColor.LIGHT_PURPLE)
					.append(Component.text(name(current.id()), NamedTextColor.WHITE));
			if (!queue.isEmpty()) text = text.append(Component.text("\nUp next: " + name(queue.get(0).id()), NamedTextColor.GRAY));
		}
		Labels.set(block, LABEL_HEIGHT, LABEL_TAG, text);
	}

	/** The selector was broken: stop the song and remove the label. */
	void removed() {
		stopCurrent();
		queue.clear();
		Labels.remove(block, LABEL_HEIGHT, LABEL_TAG);
	}
}
