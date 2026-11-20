package com.thecoberto.utilities;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/** Runs things later, or every so many ticks, on the server thread. */
public final class Tasks {
	private Tasks() {}

	private static final class Task {
		final int interval;
		final BooleanSupplier run; // returns false to stop
		int wait;

		Task(int delay, int interval, BooleanSupplier run) {
			this.wait = delay;
			this.interval = interval;
			this.run = run;
		}
	}

	private static final List<Task> tasks = new ArrayList<>();

	public static void registerEvents() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (Task task : new ArrayList<>(tasks)) {
				if (--task.wait > 0) continue;
				boolean again;
				try {
					again = task.run.getAsBoolean();
				} catch (RuntimeException e) {
					again = false;
				}
				if (again && task.interval > 0) task.wait = task.interval;
				else tasks.remove(task);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> tasks.clear());
	}

	/** Runs once after this many ticks. */
	public static void later(int ticks, Runnable run) {
		tasks.add(new Task(Math.max(1, ticks), 0, () -> {
			run.run();
			return false;
		}));
	}

	/** Runs every this many ticks (starting next tick) until it returns false. */
	public static void every(int interval, BooleanSupplier run) {
		tasks.add(new Task(1, Math.max(1, interval), run));
	}
}
