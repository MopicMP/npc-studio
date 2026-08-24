package com.mopicmp.npcstudio.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Two lines may not begin on the same tick, and what that costs.
 *
 * <h2>How this was found</h2>
 *
 * Reported as "the moment field will not go past five seconds". It went anywhere;
 * the scene already had a line at six. Moving onto an occupied moment was refused,
 * the refusal was silent, and the panel's list showed only the first four of five
 * lines with no way to scroll — so the line doing the blocking was invisible,
 * unreachable and unmentioned. Three separate silences stacked into one wrong
 * conclusion about a text box.
 *
 * <h2>Why the rule stays</h2>
 *
 * A line is named by the tick it starts at, everywhere: the panel, the ruler, the
 * selection. A cue is a record, so editing one makes a different object and any
 * other handle to it is stale the instant a letter is typed; an index into the
 * list moves whenever a line is added earlier. The tick is the only name that
 * survives being edited, saved and reopened.
 *
 * What it costs is this rule, and the cost is small because two lines at one
 * instant is a thing nobody means — what is wanted is two rows, which is one line
 * with a newline in it and which the drawing already handles. What was not small
 * was enforcing it without saying so.
 */
class OneLinePerMomentTest {

	/** The reporter's own script, as it sat in the world folder. */
	private static Scene script() {
		return Scene.empty("корабль").lengthened(1077)
			.with(Cue.text(0, "ВСТАТЬ МАТРОСАМ!", 40))
			.with(Cue.text(40, "КУРС ДЕРЖАТЬ РОВНЕЙ!", 40))
			.with(Cue.text(80, "НА ГЛУБИНАХ МОРЯ", 40))
			.with(Cue.text(100, "ФАБЬЕН — КАПИТАН НАШ", 40))
			.with(Cue.text(120, "НАШИ ПРЕДКИ", 40));
	}

	private static Cue at(Scene scene, int tick) {
		for (Cue cue : scene.cues()) {
			if (cue.kind() == Cue.Kind.TEXT && cue.at() == tick) return cue;
		}
		return null;
	}

	@Test
	@DisplayName("the moment that looked like a limit was simply taken")
	void sixSecondsWasOccupied() {
		// Five seconds is where the line being edited sat; six is where the next one
		// already was. Nothing about either number is a ceiling — and the panel showed
		// four rows, so the second of those two facts was off the bottom of the list.
		Scene scene = script();
		assertNotNull(at(scene, 100), "the line being moved");
		assertNotNull(at(scene, 120), "and the one already at six seconds");
		assertEquals(5, scene.cues().size(), "five lines, of which a list of four shows four");
	}

	@Test
	@DisplayName("a moment nobody has taken accepts the line")
	void anEmptyMomentTakesIt() {
		// The same edit onto a free tick, to show the rule is about collision and not
		// about how large the number is. Seven seconds is further out than six.
		Scene scene = script();
		Cue was = at(scene, 100);
		Scene moved = scene.without(was).with(was.moved(140));
		assertNotNull(at(moved, 140), "seven seconds, further than the moment refused");
		assertEquals(null, at(moved, 100), "and it left where it was");
		assertEquals(5, moved.cues().size(), "nothing was lost or duplicated");
	}

	@Test
	@DisplayName("moving a line keeps everything about it but the moment")
	void onlyTheMomentChanges() {
		Cue was = Cue.text(100, "ФАБЬЕН — КАПИТАН НАШ", 40,
			Look.PLAIN.withSize(1.5f).withColour(0xFFC46B));
		Cue moved = was.moved(140);
		assertEquals(140, moved.at());
		assertEquals(was.what(), moved.what());
		assertEquals(was.lasts(), moved.lasts());
		assertEquals(was.look(), moved.look(), "the styling travels with the line");
	}
}
