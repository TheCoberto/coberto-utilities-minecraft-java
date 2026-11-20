package com.thecoberto.utilities;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.RegistryEntryReferenceArgumentType;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.PersistentState;

/**
 * Mob Money: players earn Money for killing mobs. Off by default. Operators
 * set it up with the /mobmoney* commands. Settings are saved in the world.
 */
public final class MobMoney {
	private MobMoney() {}

	/** The "hostile" preset: null = the default range, otherwise its own {min, max}. Bosses pay more. */
	private static final Map<String, int[]> HOSTILE_MOBS = new LinkedHashMap<>();
	static {
		for (String id : List.of("zombie", "husk", "drowned", "zombie_villager", "zombified_piglin", "skeleton",
				"stray", "bogged", "wither_skeleton", "creeper", "spider", "cave_spider", "enderman", "endermite",
				"silverfish", "witch", "slime", "magma_cube", "blaze", "ghast", "phantom", "pillager", "vindicator",
				"evoker", "ravager", "vex", "guardian", "shulker", "hoglin", "zoglin", "piglin_brute", "breeze")) {
			HOSTILE_MOBS.put("minecraft:" + id, null);
		}
		HOSTILE_MOBS.put("minecraft:elder_guardian", new int[] {50, 100});
		HOSTILE_MOBS.put("minecraft:warden", new int[] {100, 200});
		HOSTILE_MOBS.put("minecraft:wither", new int[] {250, 500});
		HOSTILE_MOBS.put("minecraft:ender_dragon", new int[] {500, 1000});
	}

	/** Never pay for these, even with the "all" preset. */
	private static final Set<String> NEVER_PAY = Set.of("minecraft:player", "minecraft:armor_stand");

	private static final List<String> NOTIFY_MODES = List.of("chat", "actionbar", "both", "none");
	private static final List<String> PRESETS = List.of("hostile", "all", "none");

	// ---------- Settings ----------

	/** A mob's entry: pays the default range, its own range, or (with "all" on) nothing. */
	private record Entry(boolean pays, int min, int max, boolean ownRange) {
		static final Entry DEFAULT = new Entry(true, 0, 0, false);
		static final Entry EXCLUDED = new Entry(false, 0, 0, false);

		static Entry range(int min, int max) {
			return new Entry(true, min, max, true);
		}
	}

	public static class State extends PersistentState {
		private static final PersistentState.Type<State> TYPE = new PersistentState.Type<>(State::new, State::fromNbt, null);

		boolean enabled = false;
		int min = 5;
		int max = 25;
		String notify = "actionbar";
		boolean allMobs = false;
		Map<String, Entry> mobs = hostilePreset();

		static State get(MinecraftServer server) {
			return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE, "coberto_mob_money");
		}

		private static State fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
			State s = new State();
			s.enabled = nbt.getBoolean("enabled");
			s.min = nbt.getInt("min");
			s.max = nbt.getInt("max");
			s.notify = nbt.getString("notify");
			if (!NOTIFY_MODES.contains(s.notify)) s.notify = "actionbar";
			s.allMobs = nbt.getBoolean("allMobs");
			s.mobs = new LinkedHashMap<>();
			NbtCompound mobs = nbt.getCompound("mobs");
			for (String id : mobs.getKeys()) {
				if (mobs.contains(id, NbtElement.COMPOUND_TYPE)) {
					NbtCompound range = mobs.getCompound(id);
					s.mobs.put(id, Entry.range(range.getInt("min"), range.getInt("max")));
				} else {
					s.mobs.put(id, mobs.getBoolean(id) ? Entry.DEFAULT : Entry.EXCLUDED);
				}
			}
			return s;
		}

		@Override
		public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
			nbt.putBoolean("enabled", enabled);
			nbt.putInt("min", min);
			nbt.putInt("max", max);
			nbt.putString("notify", notify);
			nbt.putBoolean("allMobs", allMobs);
			NbtCompound mobsNbt = new NbtCompound();
			mobs.forEach((id, entry) -> {
				if (entry.ownRange()) {
					NbtCompound range = new NbtCompound();
					range.putInt("min", entry.min());
					range.putInt("max", entry.max());
					mobsNbt.put(id, range);
				} else {
					mobsNbt.putBoolean(id, entry.pays());
				}
			});
			nbt.put("mobs", mobsNbt);
			return nbt;
		}

		/** The {min, max} a kill of this mob pays, or null if it doesn't pay. */
		int[] rewardRange(String id) {
			if (NEVER_PAY.contains(id)) return null;
			Entry entry = mobs.get(id);
			if (entry != null && !entry.pays()) return null;
			if (entry != null && entry.ownRange()) return new int[] {entry.min(), entry.max()};
			if (entry != null || allMobs) return new int[] {min, max};
			return null;
		}
	}

	private static Map<String, Entry> hostilePreset() {
		Map<String, Entry> mobs = new LinkedHashMap<>();
		HOSTILE_MOBS.forEach((id, range) -> mobs.put(id, range == null ? Entry.DEFAULT : Entry.range(range[0], range[1])));
		return mobs;
	}

	private static void change(ServerCommandSource source, java.util.function.Consumer<State> change, Text announcement) {
		State state = State.get(source.getServer());
		change.accept(state);
		state.markDirty();
		source.getServer().getPlayerManager().broadcast(prefix().append(announcement), false);
	}

	// ---------- Paying out ----------

	static void registerEvents() {
		ServerLivingEntityEvents.AFTER_DEATH.register(MobMoney::onDeath);
	}

	private static void onDeath(LivingEntity dead, DamageSource source) {
		// Arrows, tridents etc. report the player who fired them as the attacker.
		if (!(source.getAttacker() instanceof ServerPlayerEntity killer)) return;
		State settings = State.get(killer.server);
		if (!settings.enabled) return;

		String id = Registries.ENTITY_TYPE.getId(dead.getType()).toString();
		int[] range = settings.rewardRange(id);
		if (range == null) return;

		Random random = killer.getRandom();
		int amount = range[0] + random.nextInt(range[1] - range[0] + 1);
		if (amount <= 0) return;
		Money.add(killer, amount);

		String notify = settings.notify;
		if (notify.equals("chat") || notify.equals("both")) {
			killer.sendMessage(Text.literal("You got ")
					.append(Text.literal(Money.format(amount) + " Money").formatted(Formatting.GREEN))
					.append(". ")
					.append(Text.literal("(").append(dead.getType().getName()).append(")").formatted(Formatting.GRAY)));
		}
		if (notify.equals("actionbar") || notify.equals("both")) {
			killer.sendMessage(Text.literal("+" + Money.format(amount) + " Money").formatted(Formatting.GREEN), true);
		}
	}

	// ---------- Commands ----------

	static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
		dispatcher.register(CommandManager.literal("mobmoney")
				.requires(s -> s.hasPermissionLevel(2))
				.executes(c -> {
					Text text = describe(State.get(c.getSource().getServer()));
					c.getSource().sendFeedback(() -> text, false);
					return 1;
				})
				.then(CommandManager.argument("enabled", BoolArgumentType.bool()).executes(c -> {
					boolean enabled = BoolArgumentType.getBool(c, "enabled");
					change(c.getSource(), s -> s.enabled = enabled, enabled
							? Text.empty().append(Text.literal("On").formatted(Formatting.GREEN)).append(". Players now earn Money for killing mobs.")
							: Text.empty().append(Text.literal("Off").formatted(Formatting.RED)).append("."));
					return 1;
				})));

		dispatcher.register(CommandManager.literal("mobmoneyrange")
				.requires(s -> s.hasPermissionLevel(2))
				.then(CommandManager.argument("min", IntegerArgumentType.integer(0, Money.MAX_AMOUNT))
						.then(CommandManager.argument("max", IntegerArgumentType.integer(0, Money.MAX_AMOUNT)).executes(c -> {
							int min = IntegerArgumentType.getInteger(c, "min");
							int max = IntegerArgumentType.getInteger(c, "max");
							if (min > max) return fail(c.getSource(), "min can't be more than max.");
							change(c.getSource(), s -> { s.min = min; s.max = max; },
									Text.literal("Mob kills now pay ").append(rangeText(min, max)).append("."));
							return 1;
						}))));

		dispatcher.register(CommandManager.literal("mobmoneyadd")
				.requires(s -> s.hasPermissionLevel(2))
				.then(CommandManager.argument("mob", RegistryEntryReferenceArgumentType.registryEntry(registryAccess, RegistryKeys.ENTITY_TYPE))
						.executes(c -> add(c, null, null))
						.then(CommandManager.argument("min", IntegerArgumentType.integer(0, Money.MAX_AMOUNT))
								.executes(c -> {
									int amount = IntegerArgumentType.getInteger(c, "min"); // one number = a fixed amount
									return add(c, amount, amount);
								})
								.then(CommandManager.argument("max", IntegerArgumentType.integer(0, Money.MAX_AMOUNT))
										.executes(c -> add(c, IntegerArgumentType.getInteger(c, "min"), IntegerArgumentType.getInteger(c, "max")))))));

		dispatcher.register(CommandManager.literal("mobmoneyremove")
				.requires(s -> s.hasPermissionLevel(2))
				.then(CommandManager.argument("mob", RegistryEntryReferenceArgumentType.registryEntry(registryAccess, RegistryKeys.ENTITY_TYPE))
						.executes(c -> {
							EntityType<?> type = mobArgument(c);
							String id = Registries.ENTITY_TYPE.getId(type).toString();
							change(c.getSource(), s -> {
								// With "all mobs" on, remember the mob as excluded.
								if (s.allMobs) s.mobs.put(id, Entry.EXCLUDED);
								else s.mobs.remove(id);
							}, Text.empty().append(type.getName()).append(" no longer pays Money."));
							return 1;
						})));

		dispatcher.register(CommandManager.literal("mobmoneypreset")
				.requires(s -> s.hasPermissionLevel(2))
				.then(CommandManager.argument("preset", com.mojang.brigadier.arguments.StringArgumentType.word())
						.suggests((c, b) -> CommandSource.suggestMatching(PRESETS, b))
						.executes(c -> {
							String preset = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "preset").toLowerCase(Locale.ROOT);
							if (!PRESETS.contains(preset)) return fail(c.getSource(), "Choose hostile, all or none.");
							String announcement = switch (preset) {
								case "hostile" -> "Hostile mobs now pay Money (bosses pay extra).";
								case "all" -> "Every mob now pays Money.";
								default -> "No mobs pay Money. Add some with /mobmoneyadd.";
							};
							change(c.getSource(), s -> {
								s.allMobs = preset.equals("all");
								s.mobs = preset.equals("hostile") ? hostilePreset() : new LinkedHashMap<>();
							}, Text.literal(announcement));
							return 1;
						})));

		dispatcher.register(CommandManager.literal("mobmoneynotify")
				.requires(s -> s.hasPermissionLevel(2))
				.then(CommandManager.argument("mode", com.mojang.brigadier.arguments.StringArgumentType.word())
						.suggests((c, b) -> CommandSource.suggestMatching(NOTIFY_MODES, b))
						.executes(c -> {
							String mode = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "mode").toLowerCase(Locale.ROOT);
							if (!NOTIFY_MODES.contains(mode)) return fail(c.getSource(), "Choose chat, actionbar, both or none.");
							change(c.getSource(), s -> s.notify = mode, Text.literal("Kill rewards now show " + notifyText(mode) + "."));
							return 1;
						})));
	}

	private static EntityType<?> mobArgument(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
		return RegistryEntryReferenceArgumentType.getRegistryEntry(c, "mob", RegistryKeys.ENTITY_TYPE).value();
	}

	private static int add(CommandContext<ServerCommandSource> c, Integer min, Integer max) throws CommandSyntaxException {
		EntityType<?> type = mobArgument(c);
		String id = Registries.ENTITY_TYPE.getId(type).toString();
		if (NEVER_PAY.contains(id)) return fail(c.getSource(), type.getName().getString() + " can't pay Money.");
		if (min != null && min > max) return fail(c.getSource(), "min can't be more than max.");
		change(c.getSource(), s -> s.mobs.put(id, min == null ? Entry.DEFAULT : Entry.range(min, max)),
				Text.empty().append(type.getName()).append(" now pays ")
						.append(min == null ? Text.literal("the default range") : rangeText(min, max)).append("."));
		return 1;
	}

	// ---------- Text ----------

	private static Text describe(State s) {
		List<String> plain = new ArrayList<>();
		List<String> custom = new ArrayList<>();
		List<String> excluded = new ArrayList<>();
		s.mobs.forEach((id, entry) -> {
			String name = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
			if (!entry.pays()) excluded.add(name);
			else if (entry.ownRange()) custom.add(name + " (" + rangeText(entry.min(), entry.max()).getString() + ")");
			else plain.add(name);
		});

		MutableText text = Text.empty()
				.append(Text.literal("Mob Money").formatted(Formatting.GOLD)).append(" is ")
				.append(s.enabled ? Text.literal("on").formatted(Formatting.GREEN) : Text.literal("off").formatted(Formatting.RED))
				.append(".\nDefault reward: ").append(rangeText(s.min, s.max))
				.append(". Shown " + notifyText(s.notify) + ".\nPaying mobs: ");
		if (s.allMobs) {
			text.append(Text.literal("all").formatted(Formatting.YELLOW))
					.append(excluded.isEmpty() ? "" : ", except " + String.join(", ", excluded));
		} else {
			text.append(plain.isEmpty() ? Text.literal("none").formatted(Formatting.RED) : Text.literal(String.join(", ", plain)));
		}
		if (!custom.isEmpty()) text.append("\nOwn ranges: " + String.join(", ", custom));
		return text;
	}

	private static MutableText rangeText(int min, int max) {
		String amount = min == max ? Money.format(min) : Money.format(min) + "-" + Money.format(max);
		return Text.empty().append(Text.literal(amount).formatted(Formatting.GREEN)).append(" Money");
	}

	private static String notifyText(String mode) {
		return switch (mode) {
			case "chat" -> "in chat";
			case "both" -> "in chat and above the hotbar";
			case "none" -> "nowhere (silent)";
			default -> "above the hotbar";
		};
	}

	private static MutableText prefix() {
		return Text.empty().append(Text.literal("Mob Money:").formatted(Formatting.GOLD)).append(" ");
	}

	private static int fail(ServerCommandSource source, String message) {
		source.sendError(Text.literal(message));
		return 0;
	}
}
