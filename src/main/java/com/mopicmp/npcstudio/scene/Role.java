package com.mopicmp.npcstudio.scene;

/**
 * Somebody or something the scene has a part for, and who is playing it.
 *
 * <h2>Why the tracks do not name the entity</h2>
 *
 * Because a scene has to be able to be wrong about who is in it, and recover.
 * If every track named an entity directly, then losing one character — deleted,
 * re-made, moved to another world — would mean editing forty tracks to point at
 * the replacement, and there would be no way to say "this scene, with those
 * three instead".
 *
 * So a track names a part and the scene keeps a list of who is playing which.
 * Re-pointing a whole performance at a different character is one field. A part
 * with nobody in it is an ordinary state rather than a broken scene: the scene
 * plays and that part does not move.
 *
 * <h2>Why the binding is a plain string</h2>
 *
 * It is an entity's own id, which survives a world being closed and opened —
 * which is the point, since the alternative is the number the server hands out
 * per session, and a scene bound to those is a scene that lasts until somebody
 * logs out. Kept as text rather than parsed, because an id that will not parse
 * has to mean "nobody is playing this part" rather than "the scene will not
 * load".
 */
public record Role(String name, Kind kind, String bound, int repeat) {

	public enum Kind {
		/** An NPC: has bones, and a pose. */
		CHARACTER,
		/** A model placed in the world: has bones too, and no face. */
		OBJECT,
		/** Where the scene is watched from. There is at most one. */
		CAMERA,
		/**
		 * The sky and the weather. There is at most one, and nobody plays it.
		 *
		 * A part rather than a field on the scene, because everything a part can do
		 * is exactly what the sky needs: tracks, keys, a row in the timeline, keys
		 * you can delete. A sunset over eight seconds is then the same kind of thing
		 * as an arm going up, made the same way, and none of the machinery had to
		 * learn a second shape.
		 */
		WORLD
	}

	public Role {
		bound = bound == null ? "" : bound.trim();
		repeat = Math.max(0, repeat);
	}

	public static Role of(String name, Kind kind) {
		return new Role(name, kind, "", 0);
	}

	/**
	 * How often this part's own performance starts over, in ticks. Nought is never.
	 *
	 * <h2>Why a part repeats and not the scene</h2>
	 *
	 * Because they are different lengths on purpose. A sailor swaying at the wheel
	 * is five seconds of movement and there is no sixth second to author — the sixth
	 * second is the first one again. The shot around him is as long as the music,
	 * which is a minute, and the words on the screen go on changing the whole way
	 * through.
	 *
	 * Looping the scene would give all three the same length and is therefore no
	 * answer at all: it would restart the music every five seconds and put the first
	 * subtitle back on the screen twelve times. What repeats is one participant's
	 * movement, so that is where the number lives.
	 *
	 * It folds the time this part's tracks are read at and nothing else. Cues are
	 * not folded — a line of text belongs to the scene's own clock — and neither is
	 * anybody else.
	 */
	public Role repeating(int ticks) {
		return new Role(name, kind, bound, ticks);
	}

	/** Where in its own loop this part is, at a moment of the scene. */
	public double fold(double time) {
		if (repeat <= 0 || time < repeat) return time;
		double folded = time % repeat;
		return folded < 0 ? folded + repeat : folded;
	}

	/** Whether anybody is playing this part. */
	public boolean cast() {
		return !bound.isEmpty();
	}

	public Role boundTo(String who) {
		return new Role(name, kind, who, repeat);
	}

	public Role renamed(String fresh) {
		return new Role(fresh, kind, bound, repeat);
	}
}
