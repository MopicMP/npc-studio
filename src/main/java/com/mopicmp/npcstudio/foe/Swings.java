package com.mopicmp.npcstudio.foe;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A question with an answer already in it, rather than a constant.
 *
 * <h2>What this is instead of</h2>
 *
 * A table in java. The first version of the fighting had one — {@code Style} held
 * a shape and every sword in the world was that shape — and it was wrong twice
 * over: wrong about the animations, because a blow's timing belongs to its
 * picture and not to its weapon, and wrong in kind, because a number written into
 * a class is a number nobody but us can ever change.
 *
 * The rule this file exists to keep: <b>java does not decide, java asks.</b>
 * Every number has a measured default and an override that wins over it, and the
 * default is good enough that somebody who never overrides anything still gets a
 * fight that works. "Fully configurable" without that is only "nothing works
 * until you configure it", which is the opposite of quality.
 *
 * <h2>Two levels, and the third is coming</h2>
 *
 * What ships, and what a world says instead. The third — what a particular item
 * says about itself, carried as a component on the stack — is where "this
 * greatsword is slower than that one" belongs, and it goes in when there is a
 * panel to set it from.
 *
 * The overrides are held here rather than in a file only. A pack author wants to
 * ship numbers in a file and a player wants to change them without leaving the
 * world; those are the same storage read two ways, not two systems.
 */
public final class Swings {

	private Swings() { }

	/** Where the shipped numbers live, readable from either side of the game. */
	private static final String FILE = "/assets/npc_studio/fight/swings.json";

	private static final Map<String, Swing> SHIPPED = new ConcurrentHashMap<>();
	private static final Map<String, Swing> OVERRIDDEN = new ConcurrentHashMap<>();
	private static volatile boolean loaded;

	/**
	 * What to say about an animation nobody has measured.
	 *
	 * A modded pack's own strike, most likely. It is a guess and it is marked as
	 * one — {@code checked} is false and so is everything about it — but a guess
	 * that lands in the middle of a swing beats refusing to swing at all, which
	 * from outside is a character who has decided not to fight.
	 */
	public static final Swing UNKNOWN = new Swing(8, 3, 14, false);

	private static void load() {
		if (loaded) return;
		synchronized (Swings.class) {
			if (loaded) return;
			try (var in = Swings.class.getResourceAsStream(FILE)) {
				if (in != null) {
					JsonObject root = JsonParser
						.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
						.getAsJsonObject();
					for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
						// Anything that is not an object is a note to whoever opens the
						// file. Skipped rather than refused: a file people are meant to
						// edit should be a file people can write in.
						if (!entry.getValue().isJsonObject()) continue;
						SHIPPED.put(entry.getKey(), read(entry.getValue().getAsJsonObject()));
					}
				}
			} catch (Exception unreadable) {
				// Not fatal. Every animation falls back to the guess, which is worse
				// than the measured numbers and far better than a mod that will not
				// start because one file is malformed.
				com.mopicmp.npcstudio.NpcStudio.LOGGER.error(
					"Could not read {}; every blow falls back to a guess.", FILE, unreadable);
			}
			loaded = true;
		}
	}

	private static Swing read(JsonObject from) {
		return new Swing(
			from.has("contact") ? from.get("contact").getAsInt() : UNKNOWN.contact(),
			from.has("through") ? from.get("through").getAsInt() : UNKNOWN.through(),
			from.has("cancel") ? from.get("cancel").getAsInt() : UNKNOWN.cancel(),
			from.has("checked") && from.get("checked").getAsBoolean());
	}

	/** When this animation's blow lands, whoever last had an opinion about it. */
	public static Swing of(String animation) {
		load();
		if (animation == null || animation.isEmpty()) return UNKNOWN;
		Swing said = OVERRIDDEN.get(animation);
		if (said != null) return said;
		return SHIPPED.getOrDefault(animation, UNKNOWN);
	}

	/** Whether anybody has measured this one at all, for a readout that can say so. */
	public static boolean known(String animation) {
		load();
		return OVERRIDDEN.containsKey(animation) || SHIPPED.containsKey(animation);
	}

	/** What ships, ignoring anything said since — for showing the two side by side. */
	public static Optional<Swing> shipped(String animation) {
		load();
		return Optional.ofNullable(SHIPPED.get(animation));
	}

	public static void override(String animation, Swing swing) {
		if (animation == null || animation.isEmpty() || swing == null) return;
		OVERRIDDEN.put(animation, swing);
	}

	/** Puts back what ships. The way out of a change, which every change needs. */
	public static void forget(String animation) {
		OVERRIDDEN.remove(animation);
	}

	public static Map<String, Swing> overrides() {
		return Map.copyOf(OVERRIDDEN);
	}

	public static void replaceOverrides(Map<String, Swing> now) {
		OVERRIDDEN.clear();
		OVERRIDDEN.putAll(now);
	}

	/** Every animation anybody has numbers for, shipped or said. */
	public static java.util.Set<String> names() {
		load();
		var all = new java.util.TreeSet<>(SHIPPED.keySet());
		all.addAll(OVERRIDDEN.keySet());
		return all;
	}
}
