package com.mopicmp.npcstudio.scene;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the scene is up to, and how it gets to the next moment.
 *
 * <h2>Why this is its own thing and not a float in a panel</h2>
 *
 * It was a float in a panel, and a float cannot answer the question that
 * actually matters: what did we go past. A cue sitting between one frame and the
 * next has to fire exactly once — not twice because it happened to land on a
 * tick boundary that two steps both touched, and not never because the scene
 * looped over it. Working that out is four or five decisions with an edge case
 * in each, which is a description of something to test rather than something to
 * write inline.
 *
 * <h2>Ticks, and fractions of them</h2>
 *
 * The cursor is a double even though keys are whole ticks, because it has to sit
 * between them: drawing happens between ticks, playing at half speed spends two
 * ticks crossing one, and frame capture advances by whatever a frame is worth.
 * The spans handed back are whole ticks all the same — a cue either happened or
 * did not.
 */
public record Playhead(double at, boolean playing, float speed, boolean loops) {

	/** Slower than this is a still picture; faster is not a rate anybody watches. */
	public static final float SLOWEST = 0.05f;
	public static final float FASTEST = 8f;

	public Playhead {
		at = Math.max(0, at);
		speed = Math.clamp(speed, SLOWEST, FASTEST);
	}

	public static Playhead resting() {
		return new Playhead(0, false, 1, true);
	}

	/**
	 * A stretch of time that has just gone by, as cues see it.
	 *
	 * Half open — after it, up to and including {@code upTo} — which is what
	 * {@link Scene#between(int, int)} expects and why it is expressed this way
	 * rather than as a pair of doubles.
	 */
	public record Span(int after, int upTo) { }

	/** Where the cursor got to, and what it went past on the way. */
	public record Step(Playhead head, List<Span> covered) { }

	public Playhead playing(boolean going) {
		return new Playhead(at, going, speed, loops);
	}

	public Playhead atSpeed(float rate) {
		return new Playhead(at, playing, rate, loops);
	}

	public Playhead looping(boolean round) {
		return new Playhead(at, playing, speed, round);
	}

	/**
	 * Puts the cursor somewhere by hand.
	 *
	 * Nothing fires. Dragging the cursor back across a sound is not the sound
	 * happening again, and dragging it forwards over one is not it happening
	 * early — scrubbing is looking, not playing.
	 */
	public Playhead scrubbedTo(double tick, int length) {
		return new Playhead(Math.clamp(tick, 0, Math.max(1, length)), playing, speed, loops);
	}

	/**
	 * Starts from the top.
	 *
	 * Separate from {@link #playing(boolean)} because the first moment of a scene
	 * is a moment no step can cover: every span begins after where the cursor
	 * already was, so a cue at tick zero would be stepped over on the way out of
	 * it. Beginning says so explicitly.
	 */
	public Step begun() {
		return new Step(new Playhead(0, true, speed, loops), List.of(new Span(-1, 0)));
	}

	/**
	 * Plays, from wherever makes sense.
	 *
	 * Three cases, and they are genuinely different rather than three spellings of
	 * one. From the top is beginning, which is the one moment a cue on tick zero
	 * can be heard. From the end of a scene that has finished is going back to the
	 * top, because otherwise the button does nothing and looks broken. From the
	 * middle is picking up where the cursor is, and that one must fire nothing —
	 * or pausing and unpausing would sound whatever cue you happened to be
	 * standing on, every time.
	 */
	public Step started(int length) {
		boolean fromTheTop = at <= 0 || (!loops && at >= length);
		return fromTheTop ? begun() : new Step(playing(true), List.of());
	}

	/**
	 * Moves the cursor on, and says what went by.
	 *
	 * @param ticks  how much time passed, in ticks before speed is applied
	 * @param length how long the scene is
	 */
	public Step advanced(double ticks, int length) {
		if (!playing || ticks <= 0 || length <= 0) return new Step(this, List.of());

		double travel = ticks * speed;
		List<Span> covered = new ArrayList<>(2);

		// A step longer than the whole scene is not a performance, it is the game
		// having been frozen — alt-tabbed, or loading a chunk. Playing every cue of
		// those five seconds at once would be a burst of forty overlapping sounds,
		// so the scene counts as having been passed through once and no more.
		if (travel >= length) {
			covered.add(new Span(-1, length));
			double landed = loops ? within(at + travel, length) : length;
			return new Step(new Playhead(landed, loops, speed, loops), covered);
		}

		double to = at + travel;
		if (to < length) {
			covered.add(new Span(floor(at), floor(to)));
			return new Step(new Playhead(to, true, speed, loops), covered);
		}

		covered.add(new Span(floor(at), length));
		if (!loops) return new Step(new Playhead(length, false, speed, false), covered);

		// Round again. The second span starts before zero on purpose: a cue on the
		// scene's very first tick belongs to every pass, not only to the first.
		double over = to - length;
		covered.add(new Span(-1, floor(over)));
		return new Step(new Playhead(over, true, speed, true), covered);
	}

	/** Which whole tick the cursor is on, for anything that wants one. */
	public int tick() {
		return floor(at);
	}

	/** How far through, nought to one — what a progress bar wants. */
	public float through(int length) {
		return length <= 0 ? 0 : (float) Math.clamp(at / length, 0, 1);
	}

	private static int floor(double time) {
		return (int) Math.floor(time);
	}

	private static double within(double time, int length) {
		double round = time % length;
		return round < 0 ? round + length : round;
	}
}
