package com.mopicmp.npcstudio.dialogue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A place you go to, rather than a place you are standing in.
 *
 * <h2>Why a location is a document and not a machine beside the language</h2>
 *
 * Because the author said so, and the reason holds: "стоит сделать граф по типу как мы
 * сделали с игроками, но для вот картвоских измерений". It is the same move that
 * {@link Dialogue.Kind#PLAYER} and {@link Dialogue.Kind#THING} were — a new subject in
 * one language, not a second language for a new subject.
 *
 * What makes it convincing is how little it needs. "This place lets you fly" is a
 * {@link Standing}, already built. "Here you may change the graphs" is a
 * {@link Condition}, already built. "This begins when somebody walks in" is a
 * {@link Trigger}, already built. The only thing the language did not have was the
 * ground itself and the fact that it travels — which is what {@link #region()} is.
 *
 * A separate machine would have had to grow its own conditions eventually, and they
 * would have been these ones spelt differently.
 *
 * <h2>Why resetting is the only way in, and why that is less work rather than more</h2>
 *
 * The requirement sounded like the strictest of them: leaving and coming back resets the
 * location completely, whatever happened and whatever it led to — blocks broken,
 * documents rewritten, a conversation abandoned halfway through a line.
 *
 * It is the opposite of strict. A location that is always rebuilt on entry has nothing
 * to undo, so none of the hard half exists: no incremental rollback of the ground, no
 * repair of a half-run thread, no question about what a guest is allowed to break.
 *
 * And it is what makes a copy per visitor affordable. A copy only has to exist while
 * somebody is standing in it, so the ceiling is the number of people inside at once and
 * not the number of people who have accounts — which are different numbers by an order
 * of magnitude.
 *
 * <h2>Why the hub is not a special case</h2>
 *
 * It is a location with {@link #resets()} off and nothing allowed to be edited. Written
 * as a branch in the code instead, the hub would be the one piece of the map nobody
 * could ask about in the same words as everything else — and the first thing anybody
 * would want is a second hub.
 *
 * <h2>Why the rules are written as words</h2>
 *
 * Because this package does not know the game exists, deliberately and everywhere: a
 * block wanted by a condition is the string {@code minecraft:campfire[lit=true]} and
 * something in the runtime reads it. Game rules take the same bargain for the same
 * reason — a document has to survive being read by a version of the game that has one
 * more rule than this one, or one fewer.
 *
 * In 26.2 the game made these per-dimension itself: {@code GameRuleMap} became saved
 * data belonging to a level, with {@code withOther} to lay one over another. So nothing
 * here is being faked; this is the list that gets handed to the dimension the guest is
 * standing in.
 *
 * @param region  the captured ground laid out on the way in, by name. Empty means
 *                nobody has built anything yet, which is a location in progress rather
 *                than a broken one
 * @param resets  rebuilt fresh for every visit. False is the hub, and the hub is the
 *                only thing any of this keeps
 * @param mayEdit while this holds, a guest may change the documents that live here.
 *                Reading them is never in question — see the field's own note
 * @param rules   game rules this location sets, under the names the game uses for them
 */
public record Location(String region, boolean resets, Condition mayEdit,
		Map<String, String> rules) {

	/**
	 * As long as a region's name may be. The same allowance a named place gets, because
	 * it is chosen from the same kind of dropdown and read by the same kind of person.
	 */
	public static final int LONGEST = 24;

	/**
	 * As many rules as one location may set.
	 *
	 * Measured rather than guessed: 26.2 ships 59 of them. The ceiling is over twice
	 * that because mods add their own and a location has every right to set one, and it
	 * exists at all because this arrives from a packet — an unbounded map from a client
	 * is a save file that grows until it will not open, however trusted the client.
	 */
	public static final int RULES_MOST = 128;

	public Location {
		region = region == null ? "" : region;
		// Forbidden unless the location says otherwise. A lesson is a thing somebody
		// arranged, and "change whatever you like" is the exception it opts into rather
		// than the state it starts in — the author's own division: some locations are
		// about rearranging a graph, most are about watching one work.
		mayEdit = mayEdit == null ? new Condition.Not(new Condition.Always()) : mayEdit;
		rules = Collections.unmodifiableMap(
			new LinkedHashMap<>(rules == null ? Map.of() : rules));
	}

	/** The plainest one: built fresh every visit, look but do not touch, no rules of its own. */
	public static Location of(String region) {
		return new Location(region, true, new Condition.Not(new Condition.Always()), Map.of());
	}

	/**
	 * The hub: laid out once and never rebuilt.
	 *
	 * Named here rather than left to whoever remembers which flag means what, because
	 * "the one place that survives" is a thing a map has exactly one of and a thing that
	 * is very bad to get backwards.
	 */
	public static Location hub(String region) {
		return new Location(region, false, new Condition.Not(new Condition.Always()), Map.of());
	}

	public Location editableWhen(Condition now) {
		return new Location(region, resets, now, rules);
	}

	public Location ruled(Map<String, String> now) {
		return new Location(region, resets, mayEdit, now);
	}

	/** Whether a guest may change what lives here, given how things stand for them. */
	public boolean editable(DialogueState state, Condition.World world) {
		return mayEdit.test(state, world);
	}
}
