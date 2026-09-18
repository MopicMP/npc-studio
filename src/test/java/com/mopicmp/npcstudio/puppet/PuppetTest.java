package com.mopicmp.npcstudio.puppet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A figure assembled from parts, and what a scene naming those parts gets back.
 *
 * <h2>What this is really guarding</h2>
 *
 * That a sheet can be edited without changing scenes written against it. A layout sheet
 * is a living thing — parts get added, renamed, thrown out — and every one of those is a
 * chance for last week's scene to start showing somebody else's hat.
 */
class PuppetTest {

	private static Puppet lona() {
		return new Puppet("Lona", 380, 637, List.of(
			new Puppet.Slot("body", 0, 0, List.of(new Puppet.Part("plain", "aaa"))),
			new Puppet.Slot("cloak", 12, 180, List.of(
				new Puppet.Part("red", "bbb"), new Puppet.Part("none", "ccc"))),
			new Puppet.Slot("eyes", 140, 96, List.of(
				new Puppet.Part("calm", "ddd"),
				// The variant whose own trim was three pixels tighter. Its nudge is what
				// Fitting worked out, and it is the part's, not the slot's.
				new Puppet.Part("sad", "eee", 3, 2)))));
	}

	@Test
	@DisplayName("a choice comes back as pictures with corners, back to front")
	void wearingIsAListToDraw() {
		var shown = lona().worn(Map.of("body", "plain", "cloak", "red", "eyes", "sad"));

		assertEquals(3, shown.size());
		// The order is the sheet's, not the choice's. Hair goes behind a face and in front
		// of a shoulder, and nothing about the pictures says which — so the one thing the
		// person orders by hand must survive being asked for in any order.
		assertEquals(List.of("aaa", "bbb", "eee"),
			shown.stream().map(Puppet.Placed::picture).toList());
		// Slot plus the part's own nudge, added once, here.
		assertEquals(143, shown.get(2).x());
		assertEquals(98, shown.get(2).y());
	}

	@Test
	@DisplayName("a slot nobody named shows nothing, so an added slot changes no old scene")
	void anUnnamedSlotIsEmpty() {
		// The reason there is no default. A sheet that gains a hat slot must not put a hat
		// on every scene written before the hat existed — and a default would do exactly
		// that, quietly, in scenes nobody reopened.
		var shown = lona().worn(Map.of("body", "plain"));
		assertEquals(1, shown.size());
		assertEquals("aaa", shown.get(0).picture());
	}

	@Test
	@DisplayName("a part that has been deleted is skipped rather than breaking the figure")
	void aMissingPartIsNotAMissingFigure() {
		// Somebody throws a costume off the sheet. Every scene that named it is still a
		// scene, and the choice between drawing the rest of the person and refusing to
		// draw her at all is not close.
		var shown = lona().worn(Map.of("body", "plain", "cloak", "gone", "eyes", "calm"));
		assertEquals(List.of("aaa", "ddd"),
			shown.stream().map(Puppet.Placed::picture).toList());
	}

	@Test
	@DisplayName("a fresh choice wears the first of everything")
	void freshChoicesAreDressed() {
		// Not empty. A figure that starts as a blank rectangle teaches nobody what they
		// are looking at, and a sheet is mostly body and clothes that are mostly wanted —
		// so taking things off is the shorter journey than putting them on.
		var choice = lona().everything();
		assertEquals(Map.of("body", "plain", "cloak", "red", "eyes", "calm"), choice);
		assertEquals(3, lona().worn(choice).size());
	}

	@Test
	@DisplayName("moving a slot moves every variant in it at once")
	void placingIsPerSlot() {
		// The whole reason a slot exists. Eleven pairs of eyes go in one place, and
		// placing them eleven times is the work this is meant to avoid.
		Puppet was = lona();
		Puppet now = was.with(was.slot("eyes").moved(150, 100));

		var shown = now.worn(Map.of("eyes", "calm"));
		assertEquals(150, shown.get(0).x());
		var other = now.worn(Map.of("eyes", "sad"));
		assertEquals(153, other.get(0).x(), "and the variant keeps its own small nudge");

		assertTrue(now.slot("body").x() == 0, "nothing else moved");
	}
}
