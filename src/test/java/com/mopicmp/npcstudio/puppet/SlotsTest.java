package com.mopicmp.npcstudio.puppet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one guess this whole thing makes about somebody else's filenames.
 *
 * <h2>What is being guarded</h2>
 *
 * Not that the guess is right — it cannot be, since nobody agreed on the names in
 * advance. What has to hold is that it is wrong in the harmless direction.
 *
 * There are two ways to be wrong. Splitting one slot into two costs a person a drag:
 * the eyes appear as two rows and they merge them. Merging two slots into one is
 * different in kind — the eyes and the tear are worn <em>together</em>, and made
 * variants of each other one of them can never be shown at all, with nothing on the
 * screen to say why.
 *
 * So the rule is deliberately timid, and these tests are mostly about it staying timid.
 */
class SlotsTest {

	@Test
	@DisplayName("variants are the same name with a different number on the end")
	void numberedVariantsAreOneSlot() {
		assertEquals("eyes", Slots.of("eyes01"));
		assertEquals("eyes", Slots.of("eyes11"));
		assertTrue(Slots.sameSlot("eyes01", "eyes02"));
		// The real shape from a measured set: group, part, variant, all under one slot.
		assertTrue(Slots.sameSlot("pose1_head_eyes01", "pose1_head_eyes03"));
	}

	@Test
	@DisplayName("two things worn together are never made variants of each other")
	void separateThingsStaySeparate() {
		// The fault worth having a test for. Grouped by a shared prefix these would both
		// be "pose1_head" — a head is a part of a figure, so eyes and tears are parts of a
		// head — and then a scene could show one or the other but never a crying face.
		assertFalse(Slots.sameSlot("pose1_head_eyes01", "pose1_head_tear"));
		assertFalse(Slots.sameSlot("HairMessy", "HairPony"));
		assertFalse(Slots.sameSlot("body", "equip"));
	}

	@Test
	@DisplayName("a name that is nothing but digits keeps them, and is its own slot")
	void digitsAloneAreStillAName() {
		// The digits stay, because taking them off would leave a slot with no name for
		// anybody to point at.
		assertEquals("000", Slots.of("000"));
		assertEquals("", Slots.of(""));
		assertEquals("", Slots.of(null));

		// And so 000 and 001 are two slots, not one. I wrote the opposite here first,
		// reasoning that frame sequences are named this way and a sequence is one thing —
		// but that is a different feature, and this rule cannot serve it without inventing
		// a name that is not in the file. Two slots is the timid answer: more rows to
		// look at, nothing made unreachable.
		assertFalse(Slots.sameSlot("000", "001"));
	}

	@Test
	@DisplayName("the grouping keeps the order the pictures arrived in")
	void orderSurvives() {
		// The order of a folder read off disk, which is the arrangement the person made.
		// Sorted alphabetically it would be somebody else's arrangement, and they would
		// have to find their own things in it twice.
		var found = Slots.group(List.of(
			"body", "eyes01", "eyes02", "tear", "eyes03", "HairMessy"));

		assertEquals(List.of("body", "eyes", "tear", "HairMessy"),
			List.copyOf(found.keySet()));
		assertEquals(List.of("eyes01", "eyes02", "eyes03"), found.get("eyes"));
		assertEquals(1, found.get("body").size());
	}

	@Test
	@DisplayName("an unrecognised name is a slot of its own, which costs nothing")
	void theTimidFailure() {
		// Names nobody numbered: every one is its own slot. That is more rows to look at
		// and not one thing that cannot be done — which is the whole argument for
		// preferring this failure to the other one.
		var found = Slots.group(List.of("front", "back", "middle"));
		assertEquals(3, found.size());
	}
}
