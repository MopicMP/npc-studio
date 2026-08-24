package com.mopicmp.npcstudio.client.workspace.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The timeline's left column has to be wide enough for what is put in it.
 *
 * <h2>What broke</h2>
 *
 * The corner of the timeline holds a row of buttons and, after them, the name of
 * the open scene — which is also the control that changes scenes. The column was
 * ninety-six pixels wide because somebody once wrote ninety-six. The buttons were
 * however many buttons there happened to be.
 *
 * At six buttons those two just fitted, with ten pixels left over. A seventh
 * button was added and the name moved to a hundred, past the end of its own
 * column: drawn off the edge, and hit-tested as {@code x >= 100 && x < 96}, which
 * is a rectangle with nothing in it. The scene picker stopped answering clicks and
 * left no trace at all — no error, no exception, nothing in a log.
 *
 * <h2>Why a test when the code was changed to make it impossible</h2>
 *
 * The column is worked out from the buttons now, so the two cannot drift apart by
 * arithmetic. They can still drift by a person: another button goes in and the
 * count is not bumped, or the count is bumped and the switch that handles the
 * presses is not. Neither of those is arithmetic and neither would fail to
 * compile.
 *
 * Read by reflection because these are private and should stay private. What is
 * being pinned is not an interface — it is that a rectangle used for hit testing
 * contains the thing it is drawn around.
 */
class TimelineLayoutTest {

	private static int constant(String named) throws ReflectiveOperationException {
		Field field = TimelinePanel.class.getDeclaredField(named);
		field.setAccessible(true);
		return field.getInt(null);
	}

	@Test
	@DisplayName("the name sits inside the column it is hit-tested against")
	void theNameIsInsideItsColumn() throws ReflectiveOperationException {
		int names = constant("NAMES");
		int nameLeft = constant("NAME_LEFT");

		// The exact comparison drawTransport makes. Off by one pixel the wrong way
		// and the scene cannot be changed.
		assertTrue(nameLeft < names,
			"the scene name starts at " + nameLeft + " and its column ends at " + names
				+ " — every click on it would miss");

		// And enough of it that there is something to aim at, rather than a sliver
		// that happens to satisfy the line above.
		assertTrue(names - nameLeft >= 40,
			"only " + (names - nameLeft) + " pixels of name, which is an arrow and no word");
	}

	@Test
	@DisplayName("the buttons fit in the strip before the name begins")
	void theButtonsFit() throws ReflectiveOperationException {
		int button = constant("BUTTON");
		int buttons = constant("BUTTONS");
		assertTrue(button * buttons <= constant("NAME_LEFT"),
			"the buttons run past where the name starts and would be drawn over");
	}

	@Test
	@DisplayName("there are as many buttons as there are presses handled")
	void everyButtonDoesSomething() throws Exception {
		// The other way the two drift, and the one arithmetic cannot catch: a button
		// drawn with no case behind it falls through to `default`, which opens the
		// scene list — so the new button would silently do the wrong thing rather
		// than nothing, which is worse.
		//
		// Counted out of the source, because a switch is not something reflection can
		// look inside. Crude and it is the only thing here that would have caught the
		// last one of these.
		String source = java.nio.file.Files.readString(java.nio.file.Path.of(
			"src/client/java/com/mopicmp/npcstudio/client/workspace/panel/TimelinePanel.java"));
		int highest = -1;
		var cases = java.util.regex.Pattern.compile("(?m)^\\s+case (\\d+) ->").matcher(source);
		while (cases.find()) highest = Math.max(highest, Integer.parseInt(cases.group(1)));

		assertEquals(constant("BUTTONS") - 1, highest,
			"there are " + constant("BUTTONS") + " buttons and the presses handled run "
				+ "to slot " + highest + " — one of them does nothing, or does the "
				+ "wrong thing by falling through");
	}
}
