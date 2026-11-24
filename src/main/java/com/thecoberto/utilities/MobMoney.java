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
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.util.RandomSource;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

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

	public static class State extends SavedData {
		private static final SavedDataType<State> TYPE = new SavedDataType<>(Identifier.withDefaultNamespace("coberto_mob_money"), State::new,
			CompoundTag.CODEC.xmap(State::fromNbt, s -> s.writeNbt(new CompoundTag())), null);

		boolean enabled = false;
		int min = 5;
		int max = 25;
		String notify = "actionbar";
		boolean allMobs = false;
		Map<String, Entry> mobs = hostilePreset();

		static State get(MinecraftServer server) {
			return server.getDataStorage().computeIfAbsent(TYPE);
		}

		private static State fromNbt(CompoundTag nbt) {
			State s = new State();
			s.enabled = nbt.getBooleanOr("enabled", false);
			s.min = nbt.getIntOr("min", 0);
			s.max = nbt.getIntOr("max", 0);
			s.notify = nbt.getStringOr("notify", "");
			if (!NOTIFY_MODES.contains(s.notify)) s.notify = "actionbar";
			s.allMobs = nbt.getBooleanOr("allMobs", false);
			s.mobs = new LinkedHashMap<>();
			CompoundTag mobs = nbt.getCompoundOrEmpty("mobs");
			for (String id : mobs.keySet()) {
				if (mobs.getCompound(id).isPresent()) {
					CompoundTag range = mobs.getCompoundOrEmpty(id);
					s.mobs.put(id, Entry.range(range.getIntOr("min", 0), range.getIntOr("max", 0)));
				} else {
					s.mobs.put(id, mobs.getBooleanOr(id, false) ? Entry.DEFAULT : Entry.EXCLUDED);
				}
			}
			return s;
		}

				CompoundTag writeNbt(CompoundTag nbt) {
			nbt.putBoolean("enabled", enabled);
			nbt.putInt("min", min);
			nbt.putInt("max", max);
			nbt.putString("notify", notify);
			nbt.putBoolean("allMobs", allMobs);
			CompoundTag mobsNbt = new CompoundTag();
			mobs.forEach((id, entry) -> {
				if (entry.ownRange()) {
					CompoundTag range = new CompoundTag();
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

	private static void change(CommandSourceStack source, java.util.function.Consumer<State> change, Component announcement) {
		State state = State.get(source.getServer());
		change.accept(state);
		state.setDirty();
		source.getServer().getPlayerList().broadcastSystemMessage(prefix().append(announcement), false);
	}

	// ---------- Paying out ----------

	static void registerEvents() {
		ServerLivingEntityEvents.AFTER_DEATH.register(MobMoney::onDeath);
	}

	private static void onDeath(LivingEntity dead, DamageSource source) {
		// Arrows, tridents etc. report the player who fired them as the attacker.
		if (!(source.getEntity() instanceof ServerPlayer killer)) return;
		State settings = State.get(killer.level().getServer());
		if (!settings.enabled) return;

		String id = BuiltInRegistries.ENTITY_TYPE.getKey(dead.getType()).toString();
		int[] range = settings.rewardRange(id);
		if (range == null) return;

		RandomSource random = killer.getRandom();
		int amount = range[0] + random.nextInt(range[1] - range[0] + 1);
		if (amount <= 0) return;
		Money.add(killer, amount);

		String notify = settings.notify;
		if (notify.equals("chat") || notify.equals("both")) {
			killer.sendSystemMessage(Component.literal("You got ")
					.append(Component.literal(Money.format(amount) + " Money").withStyle(ChatFormatting.GREEN))
					.append(". ")
					.append(Component.literal("(").append(dead.getType().getDescription()).append(")").withStyle(ChatFormatting.GRAY)));
		}
		if (notify.equals("actionbar") || notify.equals("both")) {
			killer.sendOverlayMessage(Component.literal("+" + Money.format(amount) + " Money").withStyle(ChatFormatting.GREEN));
		}
	}

	// ---------- Commands ----------

	static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess) {
		dispatcher.register(Commands.literal("mobmoney")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(c -> {
					Component text = describe(State.get(c.getSource().getServer()));
					c.getSource().sendSuccess(() -> text, false);
					return 1;
				})
				.then(Commands.argument("enabled", BoolArgumentType.bool()).executes(c -> {
					boolean enabled = BoolArgumentType.getBool(c, "enabled");
					change(c.getSource(), s -> s.enabled = enabled, enabled
							? Component.empty().append(Component.literal("On").withStyle(ChatFormatting.GREEN)).append(". Players now earn Money for killing mobs.")
							: Component.empty().append(Component.literal("Off").withStyle(ChatFormatting.RED)).append("."));
					return 1;
				})));

		dispatcher.register(Commands.literal("mobmoneyrange")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("min", IntegerArgumentType.integer(0, Money.MAX_AMOUNT))
						.then(Commands.argument("max", IntegerArgumentType.integer(0, Money.MAX_AMOUNT)).executes(c -> {
							int min = IntegerArgumentType.getInteger(c, "min");
							int max = IntegerArgumentType.getInteger(c, "max");
							if (min > max) return fail(c.getSource(), "min can't be more than max.");
							change(c.getSource(), s -> { s.min = min; s.max = max; },
									Component.literal("Mob kills now pay ").append(rangeText(min, max)).append("."));
							return 1;
						}))));

		dispatcher.register(Commands.literal("mobmoneyadd")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("mob", ResourceArgument.resource(registryAccess, Registries.ENTITY_TYPE))
						.executes(c -> add(c, null, null))
						.then(Commands.argument("min", IntegerArgumentType.integer(0, Money.MAX_AMOUNT))
								.executes(c -> {
									int amount = IntegerArgumentType.getInteger(c, "min"); // one number = a fixed amount
									return add(c, amount, amount);
								})
								.then(Commands.argument("max", IntegerArgumentType.integer(0, Money.MAX_AMOUNT))
										.executes(c -> add(c, IntegerArgumentType.getInteger(c, "min"), IntegerArgumentType.getInteger(c, "max")))))));

		dispatcher.register(Commands.literal("mobmoneyremove")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("mob", ResourceArgument.resource(registryAccess, Registries.ENTITY_TYPE))
						.executes(c -> {
							EntityType<?> type = mobArgument(c);
							String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
							change(c.getSource(), s -> {
								// With "all mobs" on, remember the mob as excluded.
								if (s.allMobs) s.mobs.put(id, Entry.EXCLUDED);
								else s.mobs.remove(id);
							}, Component.empty().append(type.getDescription()).append(" no longer pays Money."));
							return 1;
						})));

		dispatcher.register(Commands.literal("mobmoneypreset")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("preset", com.mojang.brigadier.arguments.StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(PRESETS, b))
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
							}, Component.literal(announcement));
							return 1;
						})));

		dispatcher.register(Commands.literal("mobmoneynotify")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("mode", com.mojang.brigadier.arguments.StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(NOTIFY_MODES, b))
						.executes(c -> {
							String mode = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "mode").toLowerCase(Locale.ROOT);
							if (!NOTIFY_MODES.contains(mode)) return fail(c.getSource(), "Choose chat, actionbar, both or none.");
							change(c.getSource(), s -> s.notify = mode, Component.literal("Kill rewards now show " + notifyText(mode) + "."));
							return 1;
						})));
	}

	private static EntityType<?> mobArgument(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		return ResourceArgument.getResource(c, "mob", Registries.ENTITY_TYPE).value();
	}

	private static int add(CommandContext<CommandSourceStack> c, Integer min, Integer max) throws CommandSyntaxException {
		EntityType<?> type = mobArgument(c);
		String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
		if (NEVER_PAY.contains(id)) return fail(c.getSource(), type.getDescription().getString() + " can't pay Money.");
		if (min != null && min > max) return fail(c.getSource(), "min can't be more than max.");
		change(c.getSource(), s -> s.mobs.put(id, min == null ? Entry.DEFAULT : Entry.range(min, max)),
				Component.empty().append(type.getDescription()).append(" now pays ")
						.append(min == null ? Component.literal("the default range") : rangeText(min, max)).append("."));
		return 1;
	}

	// ---------- Text ----------

	private static Component describe(State s) {
		List<String> plain = new ArrayList<>();
		List<String> custom = new ArrayList<>();
		List<String> excluded = new ArrayList<>();
		s.mobs.forEach((id, entry) -> {
			String name = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
			if (!entry.pays()) excluded.add(name);
			else if (entry.ownRange()) custom.add(name + " (" + rangeText(entry.min(), entry.max()).getString() + ")");
			else plain.add(name);
		});

		MutableComponent text = Component.empty()
				.append(Component.literal("Mob Money").withStyle(ChatFormatting.GOLD)).append(" is ")
				.append(s.enabled ? Component.literal("on").withStyle(ChatFormatting.GREEN) : Component.literal("off").withStyle(ChatFormatting.RED))
				.append(".\nDefault reward: ").append(rangeText(s.min, s.max))
				.append(". Shown " + notifyText(s.notify) + ".\nPaying mobs: ");
		if (s.allMobs) {
			text.append(Component.literal("all").withStyle(ChatFormatting.YELLOW))
					.append(excluded.isEmpty() ? "" : ", except " + String.join(", ", excluded));
		} else {
			text.append(plain.isEmpty() ? Component.literal("none").withStyle(ChatFormatting.RED) : Component.literal(String.join(", ", plain)));
		}
		if (!custom.isEmpty()) text.append("\nOwn ranges: " + String.join(", ", custom));
		return text;
	}

	private static MutableComponent rangeText(int min, int max) {
		String amount = min == max ? Money.format(min) : Money.format(min) + "-" + Money.format(max);
		return Component.empty().append(Component.literal(amount).withStyle(ChatFormatting.GREEN)).append(" Money");
	}

	private static String notifyText(String mode) {
		return switch (mode) {
			case "chat" -> "in chat";
			case "both" -> "in chat and above the hotbar";
			case "none" -> "nowhere (silent)";
			default -> "above the hotbar";
		};
	}

	private static MutableComponent prefix() {
		return Component.empty().append(Component.literal("Mob Money:").withStyle(ChatFormatting.GOLD)).append(" ");
	}

	private static int fail(CommandSourceStack source, String message) {
		source.sendFailure(Component.literal(message));
		return 0;
	}
}
