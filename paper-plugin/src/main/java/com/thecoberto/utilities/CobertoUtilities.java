package com.thecoberto.utilities;

import com.thecoberto.utilities.classes.Classes;
import org.bukkit.plugin.java.JavaPlugin;

/** Coberto's Utilities for Paper. Each feature lives in its own package. */
public final class CobertoUtilities extends JavaPlugin {
	@Override
	public void onEnable() {
		new Classes(this).enable();
	}
}
