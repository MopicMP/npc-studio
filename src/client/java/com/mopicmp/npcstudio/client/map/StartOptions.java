package com.mopicmp.npcstudio.client.map;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.map.Firmness;
import com.mopicmp.npcstudio.map.MapStart;
import com.mopicmp.npcstudio.map.Setting;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

/**
 * Putting a map's settings on, and — the part that matters — taking them off.
 *
 * <h2>The thing that makes this feature hated when it is done badly</h2>
 *
 * Client settings live in {@code options.txt} and outlast the session. Set
 * somebody's brightness for a dark map and fail to put it back, and they leave
 * with a game that looks wrong everywhere else and no idea why. So nothing here
 * is allowed to change a value without first writing down what it was, and the
 * writing down goes to <em>disk before the change</em>, not to a field.
 *
 * A field would be enough if the game always closed politely. It does not: it
 * crashes, and after a crash a field is gone while {@code options.txt} still has
 * the map's numbers in it. The file is read on the next join and put back before
 * anything else happens, so the worst a crash costs is one session of looking odd
 * rather than a permanent change nobody can attribute.
 *
 * <h2>Every value is read back</h2>
 *
 * {@code OptionInstance.set} validates, and on failure substitutes the option's
 * <em>default</em> rather than clamping — so an out-of-range brightness silently
 * becomes 0.5 instead of 1.0. Reading the value back afterwards is the only way
 * to know that what was asked for is what happened, and the panel shows the
 * difference rather than assuming there is none.
 */
public final class StartOptions {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/**
	 * Outside the mod's own config file on purpose.
	 *
	 * That file is settings somebody chose. This one is a half-finished operation:
	 * it exists only between putting a map's numbers on and taking them off, and
	 * finding it at startup means the last session ended badly. Two different
	 * lifetimes in one file is how a crash recovery ends up being overwritten by
	 * an unrelated save.
	 */
	private static final Path FILE =
		FabricLoader.getInstance().getConfigDir().resolve("npc_studio-start.json");

	private StartOptions() { }

	/** What each held setting is doing, for the refusal in {@code OptionInstance.set}. */
	private static final Map<Setting, MapStart.Entry> held = new EnumMap<>(Setting.class);

	/** What the values were before this map touched them. */
	private static final Map<Setting, Double> before = new EnumMap<>(Setting.class);

	/**
	 * Set while we are the ones writing.
	 *
	 * Without it the refusal below would refuse our own restore, and a held setting
	 * would be held for ever — which is the one failure of this feature that the
	 * player cannot undo from inside the game.
	 */
	private static boolean applying;

	private static long saidAt;

	// --------------------------------------------------------------- the options

	private static Options options() {
		Minecraft client = Minecraft.getInstance();
		return client == null ? null : client.options;
	}

	static OptionInstance<?> instance(Setting what, Options options) {
		return switch (what) {
			case RENDER_DISTANCE -> options.renderDistance();
			case SIMULATION_DISTANCE -> options.simulationDistance();
			case BRIGHTNESS -> options.gamma();
			case FOV -> options.fov();
			case GUI_SCALE -> options.guiScale();
		};
	}

	/** Which of ours an option is, or null when it is one we have no opinion about. */
	static Setting which(OptionInstance<?> instance, Options options) {
		for (Setting setting : Setting.values()) {
			if (instance(setting, options) == instance) return setting;
		}
		return null;
	}

	public static double read(Setting what) {
		Options options = options();
		return options == null ? what.usual() : read(what, options);
	}

	private static double read(Setting what, Options options) {
		return switch (what) {
			case RENDER_DISTANCE -> options.renderDistance().get();
			case SIMULATION_DISTANCE -> options.simulationDistance().get();
			case BRIGHTNESS -> options.gamma().get();
			case FOV -> options.fov().get();
			case GUI_SCALE -> options.guiScale().get();
		};
	}

	private static void write(Setting what, Options options, double value) {
		applying = true;
		try {
			switch (what) {
				case RENDER_DISTANCE -> options.renderDistance().set((int) Math.round(value));
				case SIMULATION_DISTANCE -> options.simulationDistance().set((int) Math.round(value));
				case BRIGHTNESS -> options.gamma().set(value);
				case FOV -> options.fov().set((int) Math.round(value));
				case GUI_SCALE -> options.guiScale().set((int) Math.round(value));
			}
		} finally {
			applying = false;
		}
	}

	// ------------------------------------------------------------------ arriving

	/**
	 * What a map asks for, applied — and anything it has stopped asking for, undone.
	 *
	 * Safe to call again with a different map, which it has to be: the author edits
	 * these settings while standing on the map, and every edit arrives here. The
	 * originals are captured once per setting and never recaptured, so a second
	 * arrival does not save the map's own value as "what it was before".
	 */
	public static void arrive(MapStart start) {
		Options options = options();
		if (options == null) return;

		recover(options);

		for (Setting setting : Setting.values()) {
			MapStart.Entry entry = start.entry(setting);
			boolean wanted = entry != null && entry.firmness().applies();

			if (!wanted) {
				// Dropped from the map, or downgraded to a suggestion. Put it back now
				// rather than at the end of the session: the author who just took a
				// setting out is watching for it to stop applying.
				Double had = before.remove(setting);
				if (had != null) write(setting, options, had);
				held.remove(setting);
				continue;
			}

			before.putIfAbsent(setting, read(setting, options));
			write(setting, options, setting.sane(
				entry.bound().applied(read(setting, options), entry.value())));

			if (entry.firmness() == Firmness.HELD) {
				held.put(setting, entry);
			} else {
				held.remove(setting);
			}
		}

		remember();
		changedAt = System.currentTimeMillis();
	}

	/**
	 * When something last changed, or nought when {@code options.txt} is up to date.
	 *
	 * The values are already in force — {@code OptionInstance.set} does that — and
	 * the file is only what survives a restart. Writing it on every change would be
	 * a file write per step while somebody drags the brightness in the panel, which
	 * is the kind of thing that turns a slider into a stutter.
	 */
	private static long changedAt;

	private static final long SETTLE = 700;

	public static void tick() {
		if (changedAt == 0 || System.currentTimeMillis() - changedAt < SETTLE) return;
		changedAt = 0;
		Options options = options();
		if (options != null) options.save();
	}

	/**
	 * The suggestions, taken up because somebody said yes.
	 *
	 * Nothing is written down and nothing is put back afterwards, and that is the
	 * whole difference between a suggestion and the other two. The player changed
	 * their own settings, having been asked; undoing that on the way out would be
	 * taking back a choice they made.
	 */
	public static void accept(MapStart start) {
		Options options = options();
		if (options == null) return;
		for (MapStart.Entry entry : start.settings()) {
			if (entry.firmness() != Firmness.SUGGESTED) continue;
			Setting setting = entry.what();
			write(setting, options, setting.sane(
				entry.bound().applied(read(setting, options), entry.value())));
		}
		options.save();
	}

	/** Everything back as it was, and the note on disk thrown away. */
	public static void leave() {
		Options options = options();
		held.clear();
		if (options == null) {
			before.clear();
			forget();
			return;
		}
		for (Map.Entry<Setting, Double> had : before.entrySet()) {
			write(had.getKey(), options, had.getValue());
		}
		before.clear();
		changedAt = 0;
		options.save();
		forget();
	}

	/**
	 * Puts back what a session that ended badly left behind.
	 *
	 * Runs before a map's own settings are applied, so the originals captured a
	 * moment later are the player's own numbers rather than the previous map's.
	 */
	private static void recover(Options options) {
		if (!before.isEmpty() || !Files.exists(FILE)) return;
		Map<Setting, Double> left = readFile();
		if (left.isEmpty()) {
			forget();
			return;
		}
		for (Map.Entry<Setting, Double> had : left.entrySet()) {
			write(had.getKey(), options, had.getValue());
		}
		options.save();
		forget();
		NpcStudio.LOGGER.info("Put back {} settings a previous session had not restored", left.size());
	}

	// ------------------------------------------------------------------- the file

	private static Map<Setting, Double> readFile() {
		Map<Setting, Double> found = new LinkedHashMap<>();
		try {
			JsonObject read = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
			JsonObject saved = read.getAsJsonObject("before");
			if (saved == null) return found;
			for (String name : saved.keySet()) {
				Setting setting = Setting.named(name);
				if (setting != null) found.put(setting, saved.get(name).getAsDouble());
			}
		} catch (Exception broken) {
			// Anything unreadable is treated as nothing to put back. The alternative —
			// refusing to continue — would leave a player stuck behind a file they
			// cannot see and did not write.
			NpcStudio.LOGGER.warn("Could not read the settings left by a previous session", broken);
		}
		return found;
	}

	/** What was last written to the file, so an unchanged one is not rewritten. */
	private static String written = "";

	private static void remember() {
		if (before.isEmpty()) {
			written = "";
			forget();
			return;
		}
		JsonObject saved = new JsonObject();
		for (Map.Entry<Setting, Double> had : before.entrySet()) {
			saved.addProperty(had.getKey().getSerializedName(), had.getValue());
		}
		JsonObject whole = new JsonObject();
		whole.addProperty("note", "Settings a map changed. Put back on leaving; left here if the game stops first.");
		whole.add("before", saved);

		// Only the originals go in here, so this file changes when a setting is added
		// to the map or taken out of it and at no other time. Dragging a value writes
		// nothing: what it was before did not change.
		String next = GSON.toJson(whole);
		if (next.equals(written)) return;
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, next);
			written = next;
		} catch (Exception failed) {
			NpcStudio.LOGGER.warn("Could not write down the settings to put back", failed);
		}
	}

	private static void forget() {
		written = "";
		try {
			Files.deleteIfExists(FILE);
		} catch (Exception failed) {
			NpcStudio.LOGGER.warn("Could not remove the note of settings to put back", failed);
		}
	}

	// ---------------------------------------------------------------- the holding

	/**
	 * Whether a change to an option is one the map will not allow.
	 *
	 * Asked from {@code OptionInstance.set}, which is the single point every change
	 * goes through — the sliders, the keys, and loading the file all end up here.
	 * Refusing at the source is why this is a refusal rather than a fight: nothing
	 * has to put the value back, because it never moved.
	 */
	public static boolean refuses(OptionInstance<?> instance, Object value) {
		if (applying || held.isEmpty()) return false;
		Minecraft client = Minecraft.getInstance();
		// Called during startup while Options is still being built, before the field
		// that names it exists. Nothing is held then, but the null is real.
		if (client == null || client.options == null) return false;
		if (!(value instanceof Number moving)) return false;

		Setting setting = which(instance, client.options);
		if (setting == null) return false;
		MapStart.Entry entry = held.get(setting);
		if (entry == null || entry.bound().allows(moving.doubleValue(), entry.value())) return false;

		say(setting);
		return true;
	}

	/**
	 * Says why nothing happened.
	 *
	 * Without this the map is a game where a slider does not move, which reads as a
	 * bug and gets reported as one. Once a second, because a slider dragged across
	 * its range is a hundred refusals and a hundred messages is its own fault.
	 */
	private static void say(Setting setting) {
		long now = System.currentTimeMillis();
		if (now - saidAt < 1000) return;
		saidAt = now;
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.player == null) return;
		client.player.sendOverlayMessage(Component.translatable(
			"npc_studio.start.held", Component.translatable(setting.key())));
	}
}
