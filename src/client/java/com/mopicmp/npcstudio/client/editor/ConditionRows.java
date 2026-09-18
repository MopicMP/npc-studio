package com.mopicmp.npcstudio.client.editor;

import java.util.ArrayList;
import java.util.List;

import com.mopicmp.npcstudio.dialogue.Condition;

/**
 * A condition seen as a list of rows, which is the shape a panel can show.
 *
 * <h2>Why a list rather than a tree</h2>
 *
 * Because a tree editor is a different piece of software, and the conditions
 * people actually write are not trees. Every one in every graph that ships is
 * either a single test, a single test negated, or several of those joined by
 * "all" or "any" — which is a list with a join and a tick-box per row.
 *
 * Building the tree editor first would mean shipping nothing for a long while,
 * and shipping the list means the half of the language that is currently
 * file-only stops being file-only today.
 *
 * <h2>What happens to the conditions that do not fit</h2>
 *
 * They are refused, and left alone. {@link #read} answers null for anything
 * nested deeper than this — an "any" inside an "all", a negated group — and the
 * panel says so rather than showing an approximation.
 *
 * That refusal is the whole point. The alternative is to flatten what does not
 * fit and write it back, which loses somebody's meaning without saying a word;
 * this project has met that bug three times and it is always the same shape —
 * something is quietly not what the author wrote, and nothing anywhere says so.
 *
 * <h2>Writing back is normalising, deliberately</h2>
 *
 * A single row comes back as the bare test rather than as an "all" wrapped round
 * one thing. So a file may come out tidier than it went in — but never meaning
 * anything different, which is the property the test actually checks: the same
 * answers for the same states rather than the same characters in the file.
 */
public record ConditionRows(boolean all, List<Row> rows) {

	/**
	 * One test, possibly turned round.
	 *
	 * The leaf is always one of the three that ask a single question — a
	 * comparison, an item, a node already visited. Never a group: a group in a row
	 * is exactly the nesting this shape does not have.
	 */
	public record Row(boolean not, Condition leaf) { }

	public ConditionRows {
		rows = List.copyOf(rows);
	}

	/** True and nothing to show, which is what a fresh condition is. */
	public static final ConditionRows EMPTY = new ConditionRows(true, List.of());

	private static boolean isLeaf(Condition condition) {
		return condition instanceof Condition.Compare
			|| condition instanceof Condition.HasItem
			|| condition instanceof Condition.Visited
			// A box the player is standing in: one question with one answer, so it is a
			// row like the others. And it is the one that most wants turning round —
			// "until they are inside" and "while they are still outside" are both
			// ordinary things to write, and the second is this with the not.
			|| condition instanceof Condition.Inside
		// A block at a place: one question with one answer, so it is a row like the
		// rest. It wants turning round quite as much as the box does — "while the fire
		// is still out" is this with the not.
		|| condition instanceof Condition.Block;
	}

	/** The row a part makes, or null if that part is itself a group. */
	private static Row rowOf(Condition part) {
		if (isLeaf(part)) return new Row(false, part);
		if (part instanceof Condition.Not(Condition inner) && isLeaf(inner)) {
			return new Row(true, inner);
		}
		return null;
	}

	/**
	 * The rows this condition is made of, or null when it is deeper than rows.
	 *
	 * Null is an answer, not a failure. It means "do not edit this here", and the
	 * caller is expected to say so and leave the condition as it found it.
	 */
	public static ConditionRows read(Condition condition) {
		if (condition == null || condition instanceof Condition.Always) return EMPTY;

		Row single = rowOf(condition);
		if (single != null) return new ConditionRows(true, List.of(single));

		List<Condition> parts;
		boolean all;
		if (condition instanceof Condition.All(List<Condition> inner)) {
			parts = inner;
			all = true;
		} else if (condition instanceof Condition.Any(List<Condition> inner)) {
			parts = inner;
			all = false;
		} else {
			return null;
		}

		List<Row> rows = new ArrayList<>();
		for (Condition part : parts) {
			Row row = rowOf(part);
			if (row == null) return null;
			rows.add(row);
		}
		return new ConditionRows(all, rows);
	}

	/** The condition these rows mean. */
	public Condition write() {
		if (rows.isEmpty()) {
			// No rows under "all" is true and under "any" is false, which is what the
			// language already says about empty groups — so both are written out as
			// themselves rather than as a bare `Always`, which would only be right
			// for one of them.
			return all ? new Condition.Always() : new Condition.Any(List.of());
		}

		List<Condition> parts = new ArrayList<>();
		for (Row row : rows) {
			parts.add(row.not() ? new Condition.Not(row.leaf()) : row.leaf());
		}
		// One row needs no group round it, and leaving one off keeps a simple
		// condition simple in the file — which matters, because the file is the
		// thing people read when the editor cannot show them something.
		if (parts.size() == 1 && all) return parts.get(0);
		return all ? new Condition.All(List.copyOf(parts)) : new Condition.Any(List.copyOf(parts));
	}

	public ConditionRows withRow(int at, Row row) {
		List<Row> now = new ArrayList<>(rows);
		now.set(at, row);
		return new ConditionRows(all, now);
	}

	public ConditionRows plus(Row row) {
		List<Row> now = new ArrayList<>(rows);
		now.add(row);
		return new ConditionRows(all, now);
	}

	public ConditionRows without(int at) {
		List<Row> now = new ArrayList<>(rows);
		now.remove(at);
		return new ConditionRows(all, now);
	}

	/** The same rows with one put in at a given place rather than at the end. */
	public ConditionRows plusAt(int at, Row row) {
		List<Row> now = new ArrayList<>(rows);
		now.add(Math.max(0, Math.min(at, now.size())), row);
		return new ConditionRows(all, now);
	}

	/**
	 * The same rows with one moved.
	 *
	 * <h2>Why order matters here at all</h2>
	 *
	 * Under "all of" it does not: every row has to hold, and they hold in any order.
	 * Under "any of" it does not either. So moving a row inside one condition changes
	 * nothing about what it means — and that is exactly why it is worth allowing:
	 * the rows are read by a person, and a person reads them top to bottom. Grouping
	 * the two tests about the same errand together is a real edit to the document as
	 * a thing that gets read, and no edit at all to the document as a thing that runs.
	 *
	 * Where order does decide the answer is one level up, between the arms of a
	 * branch — see the panel, which is why both are draggable by the same gesture.
	 *
	 * @param to where it should end up once it has been taken out, so that dropping a
	 *           row back where it started is not an off-by-one
	 */
	public ConditionRows moved(int from, int to) {
		if (from < 0 || from >= rows.size()) return this;
		List<Row> now = new ArrayList<>(rows);
		Row carried = now.remove(from);
		now.add(Math.max(0, Math.min(to, now.size())), carried);
		return new ConditionRows(all, now);
	}

	public ConditionRows joinedBy(boolean everyOne) {
		return new ConditionRows(everyOne, rows);
	}
}
