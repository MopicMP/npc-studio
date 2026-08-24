package com.mopicmp.npcstudio.scene;

/**
 * Something that happens at a moment rather than something that moves over one.
 *
 * <h2>Why these are not tracks</h2>
 *
 * A line of text appearing has no halfway. Neither has a sound starting. Pushed
 * into a track they would need a number that means "played" and a rule that
 * interpolating it is forbidden, which is a way of writing down that they do not
 * belong there. Two shapes for two kinds of thing is shorter than one shape with
 * an exception in it.
 *
 * The cost is that playback has to ask two questions instead of one — where
 * everything is, and what has happened since it last looked. That second
 * question is one it has to ask anyway: a sound that was skipped over because
 * the scene was scrubbed must not play, and one that was passed at ordinary
 * speed must not play twice. See {@link Scene#between(int, int)}.
 */
public record Cue(int at, Kind kind, String what, int lasts, Look look) {

	public enum Kind {
		/** A sound, named as the game names it. It lasts as long as it lasts. */
		SOUND,
		/** A line, shown for {@link #lasts()} ticks. */
		TEXT,
		/**
		 * A piece of music, named as a file in the mod's own folder.
		 *
		 * <h2>Why this is a cue and yet not an event</h2>
		 *
		 * It is written down like the others — a name at a moment — and it behaves
		 * unlike them. A sound fires and is gone; music is a <em>state</em>, and the
		 * state is decided by the last one of these the cursor has passed. Scrub back
		 * before it and it stops; scrub forward over it and it starts, from as far in
		 * as the cursor has gone.
		 *
		 * Kept as a cue anyway, because everything else about it is one: it sits at a
		 * moment, it has a name, it belongs on the ruler and it is dragged about like
		 * the rest. Only the reading of it differs, and that is one method.
		 *
		 * An empty name is silence — the way to say "and here the music stops".
		 */
		MUSIC
	}

	public Cue {
		at = Math.max(0, at);
		lasts = Math.max(0, lasts);
		what = what == null ? "" : what;
		// Every line has an appearance whether or not anybody has chosen one, and a
		// sound has none whatever is handed in. Settled here rather than at each
		// reader, so that "what does a cue from an older file look like" has one
		// answer instead of one per place that asks.
		look = kind == Kind.TEXT ? (look == null ? Look.PLAIN : look) : null;
	}

	public static Cue sound(int at, String named) {
		return new Cue(at, Kind.SOUND, named, 0, null);
	}

	public static Cue music(int at, String named) {
		return new Cue(at, Kind.MUSIC, named, 0, null);
	}

	public static Cue text(int at, String line, int lasts) {
		return new Cue(at, Kind.TEXT, line, lasts, Look.PLAIN);
	}

	public static Cue text(int at, String line, int lasts, Look look) {
		return new Cue(at, Kind.TEXT, line, lasts, look);
	}

	public Cue moved(int tick) {
		return new Cue(tick, kind, what, lasts, look);
	}

	public Cue saying(String line) {
		return new Cue(at, kind, line, lasts, look);
	}

	public Cue lasting(int ticks) {
		return new Cue(at, kind, what, ticks, look);
	}

	public Cue looking(Look how) {
		return new Cue(at, kind, what, lasts, how);
	}

	/** When this stops mattering — the same moment it starts, for a sound. */
	public int ends() {
		return at + lasts;
	}
}
