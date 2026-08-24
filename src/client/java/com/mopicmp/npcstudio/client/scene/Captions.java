package com.mopicmp.npcstudio.client.scene;

import java.util.ArrayList;
import java.util.List;

import com.mopicmp.npcstudio.scene.Cue;
import com.mopicmp.npcstudio.scene.Look;
import com.mopicmp.npcstudio.scene.Scene;

/**
 * Which line on the screen is being worked on.
 *
 * <h2>Why this is a class and not a field on a panel</h2>
 *
 * Because two places choose one: the mark on the timeline's ruler and the list in
 * the caption panel are two views of the same choice, exactly as the bone rings
 * and the pose panel's list are. A panel holding its own copy is a panel that
 * disagrees with the ruler the first time either is used — which is the lesson
 * {@code BoneHandles.bone()} was moved out of a panel for.
 *
 * <h2>Why a line is named by the moment it starts</h2>
 *
 * A cue is a record, so editing one produces a different object and any handle to
 * the old one is stale the instant a letter is typed. An index into the list moves
 * whenever a line is added earlier. The tick it sits at is the one thing about a
 * line that survives editing it, and it survives being saved and reopened as well.
 *
 * The price is that two lines may not start on the same tick, and that is paid
 * knowingly: two lines at one instant is a thing the format could express and
 * nobody means — what people actually want is two rows, which is one line with a
 * newline in it, and which the drawing already handles.
 */
public final class Captions {

	/** Nobody is chosen. Not nought, which is a perfectly good moment. */
	public static final int NONE = -1;

	private static int picked = NONE;

	private Captions() { }

	public static int picked() {
		return picked;
	}

	public static void pick(int tick) {
		picked = tick;
		refused = NONE;
	}

	public static void forget() {
		picked = NONE;
	}

	/** Every line in the open scene, earliest first. */
	public static List<Cue> lines() {
		Scene scene = Playing.scene();
		List<Cue> found = new ArrayList<>();
		if (scene == null) return found;
		for (Cue cue : scene.cues()) {
			if (cue.kind() == Cue.Kind.TEXT) found.add(cue);
		}
		found.sort((a, b) -> Integer.compare(a.at(), b.at()));
		return found;
	}

	/** The line that starts at a moment, or null. */
	public static Cue at(int tick) {
		for (Cue cue : lines()) {
			if (cue.at() == tick) return cue;
		}
		return null;
	}

	/** The line being worked on, or null. */
	public static Cue chosen() {
		return picked == NONE ? null : at(picked);
	}

	/**
	 * Puts a line at the cursor and chooses it, or chooses the one already there.
	 *
	 * Both from one press, because "add" and "edit" are the same intention aimed at
	 * a moment: somebody who puts the cursor on a line and presses this wants that
	 * line, and somebody who puts it anywhere else wants a new one. Two buttons for
	 * that would be a choice nobody has to make.
	 */
	public static void addOrPick() {
		Scene scene = Playing.scene();
		if (scene == null) return;
		int at = Playing.head().tick();
		picked = at;
		if (Captions.at(at) != null) return;
		Scenes.keep(Playing.openName(), scene.with(Cue.text(at, "", STAYS)));
	}

	/** How long a new line stays, in ticks. Three seconds reads once, slowly. */
	public static final int STAYS = 60;

	/** Puts a changed line back, keeping the choice on it. */
	public static void change(Cue changed) {
		Scene scene = Playing.scene();
		Cue was = chosen();
		if (scene == null || was == null || changed == null) return;
		picked = changed.at();
		Scenes.keep(Playing.openName(), scene.without(was).with(changed));
	}

	/**
	 * Moves a line to another moment, unless something is already there.
	 *
	 * <h2>Why the refusal has to be heard</h2>
	 *
	 * It used to be silent, and silence is the worst possible answer here. Somebody
	 * typed six seconds into a scene that already had a line at six, nothing
	 * happened, and what it looked like was a field that would not go past five —
	 * which is what it was reported as. The number was fine, the scene was fine, and
	 * the one fact that would have explained it was never said.
	 *
	 * So a refusal is recorded and the panel prints it. See {@link #refused()}.
	 */
	public static void moveTo(int tick) {
		Cue was = chosen();
		if (was == null || tick == was.at()) return;
		if (at(tick) != null) {
			refused = tick;
			return;
		}
		refused = NONE;
		change(was.moved(tick));
	}

	/**
	 * The moment a move was turned down at, or {@link #NONE}.
	 *
	 * Kept rather than thrown, because this happens on a keystroke and a keystroke
	 * is not a place to interrupt somebody. It clears itself the moment a move
	 * works, or the moment a different line is chosen.
	 */
	private static int refused = NONE;

	public static int refused() {
		return refused;
	}

	public static void remove() {
		Scene scene = Playing.scene();
		Cue was = chosen();
		if (scene == null || was == null) return;
		picked = NONE;
		Scenes.keep(Playing.openName(), scene.without(was));
	}

	/** The look of whatever is chosen, or the plain one, so a panel never sees null. */
	public static Look look() {
		Cue chosen = chosen();
		return chosen == null || chosen.look() == null ? Look.PLAIN : chosen.look();
	}

	/** Restyles the chosen line. */
	public static void restyle(Look how) {
		Cue chosen = chosen();
		if (chosen != null) change(chosen.looking(how));
	}
}
