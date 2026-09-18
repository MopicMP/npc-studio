package com.mopicmp.npcstudio.dialogue;

/**
 * A variable the player can see.
 *
 * <h2>Why this is the other half of a made-up property</h2>
 *
 * "The player has stamina" turned out to be a variable, which existed all along, plus
 * events that move it, which the graph does. What was missing is that <b>a variable the
 * player cannot see is a variable they do not know about</b> — so an ability gated on
 * energy is, from inside the game, an ability that works sometimes.
 *
 * That is the whole of what this is for, and it is why it was left until last rather
 * than dropped: it is not language, it is presentation, and presentation done in passing
 * is presentation done badly.
 *
 * <h2>Why it is a table like the standing properties</h2>
 *
 * Because a gauge is not an event either. It does not happen; it is on screen while
 * something holds. Same shape, same reasons, and deliberately the same page of the
 * editor.
 *
 * <h2>Why the server works out the number</h2>
 *
 * Because the client has no idea what a document is. It is told a label, a number and
 * where to put it, and it draws that — which means the whole language stays on one side
 * and nothing about variables, scopes or conditions has to be taught to the other.
 *
 * @param label  what is written beside it, and the handle it is edited under
 * @param most   what a full bar means. Meaningless for a number, and refused as nought
 *               for a bar, which could not be drawn
 * @param when   while this holds. {@link Condition.Always} for "all the time" — and
 *               "while it is not full" is the ordinary way to keep a screen quiet
 */
public record Gauge(String label, String variable, Scope scope, Look look, double most,
		Corner corner, String colour, Condition when) {

	/** How it is drawn. Both, because both are asked for and they are not alike. */
	public enum Look {
		/** A bar that fills. Wants a maximum, and is what a stamina is. */
		BAR,

		/** The number itself. What a count of coins is, where a bar would say nothing. */
		NUMBER
	}

	/**
	 * Which corner it sits in.
	 *
	 * Corners rather than coordinates, because a coordinate is right on one screen and
	 * wrong on every other — and because the four corners are what anybody actually
	 * means when they say where something should go.
	 */
	public enum Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

	public Gauge {
		label = label == null ? "" : label;
		variable = variable == null ? "" : variable;
		scope = scope == null ? Scope.PLAYER : scope;
		look = look == null ? Look.BAR : look;
		corner = corner == null ? Corner.TOP_LEFT : corner;
		colour = colour == null ? "" : colour;
		when = when == null ? new Condition.Always() : when;
	}

	/** The plainest form: a bar in the top left, on all the time. */
	public static Gauge bar(String label, String variable, double most) {
		return new Gauge(label, variable, Scope.PLAYER, Look.BAR, most,
			Corner.TOP_LEFT, "", new Condition.Always());
	}

	public Gauge shownWhen(Condition now) {
		return new Gauge(label, variable, scope, look, most, corner, colour, now);
	}

	public Gauge looking(Look now, double nowMost) {
		return new Gauge(label, variable, scope, now, nowMost, corner, colour, when);
	}

	public Gauge placed(Corner now, String nowColour) {
		return new Gauge(label, variable, scope, look, most, now, nowColour, when);
	}

	public Gauge reading(String nowVariable, Scope nowScope) {
		return new Gauge(label, nowVariable, nowScope, look, most, corner, colour, when);
	}
}
