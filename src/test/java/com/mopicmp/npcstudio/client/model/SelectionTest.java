package com.mopicmp.npcstudio.client.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mopicmp.npcstudio.model.Bone;
import com.mopicmp.npcstudio.model.Cube;
import com.mopicmp.npcstudio.model.Model;

/**
 * Choosing several things at once, and what happens to them.
 *
 * Worth testing because the mistakes here are silent. A delete of three that
 * takes two of them and something else looks, at the moment it happens, exactly
 * like a delete that worked — the model is smaller and the list is shorter, and
 * whichever box went by accident is only missed later.
 */
class SelectionTest {

	/** A box wide enough to tell from the others. */
	private static Cube wide(float wide) {
		return new Cube(0, 0, 0, wide, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, "");
	}

	@BeforeEach
	void open() {
		Modelling.create("test");
		Modelling.take(new Model("test", 64, 64, List.of(
			new Bone("root", "", 0, 0, 0, 0, 0, 0,
				List.of(wide(1), wide(2), wide(3), wide(4))),
			new Bone("mast", "root", 0, 0, 0, 0, 0, 0, List.of()))));
	}

	@Test
	@DisplayName("deleting several takes exactly those, whatever order they were chosen in")
	void deletingSeveralTakesTheRightOnes() {
		Modelling.select("root", 0);
		Modelling.alsoSelect("root", 2);

		Modelling.removeSelected();

		List<Cube> left = Modelling.model().bone("root").cubes();
		assertEquals(2, left.size());
		// The rule this guards: taking box zero out first makes box two into box one,
		// so removing upwards would take the wrong second one.
		assertEquals(2, left.get(0).sizeX(), "the second box survived");
		assertEquals(4, left.get(1).sizeX(), "and so did the fourth");
	}

	@Test
	@DisplayName("control-clicking a chosen row takes it out again")
	void controlClickingTwiceUnchooses() {
		Modelling.select("root", 0);
		Modelling.alsoSelect("root", 1);
		assertEquals(2, Modelling.selectedCount());

		Modelling.alsoSelect("root", 1);

		assertEquals(1, Modelling.selectedCount());
		assertTrue(Modelling.isSelected("root", 0));
		assertFalse(Modelling.isSelected("root", 1));
		// The active row cannot be one that is no longer chosen: the handles in the
		// world and the numbers on the right both follow it.
		assertEquals(0, Modelling.selectedCube());
	}

	@Test
	@DisplayName("a range takes everything between, and ends on the row that was clicked")
	void shiftClickingTakesTheRange() {
		List<String> bones = List.of("root", "root", "root", "root", "root");
		List<Integer> cubes = List.of(-1, 0, 1, 2, 3);

		Modelling.selectThrough(bones, cubes, 1, 3);

		assertEquals(3, Modelling.selectedCount());
		assertTrue(Modelling.isSelected("root", 1));
		assertEquals(2, Modelling.selectedCube(), "the row clicked is the one in charge");
	}

	@Test
	@DisplayName("moving into a folder empties the old one and fills the new")
	void movingIntoAFolder() {
		Modelling.select("root", 1);
		Modelling.alsoSelect("root", 3);

		assertTrue(Modelling.moveInto("mast"));

		assertEquals(2, Modelling.model().bone("root").cubes().size());
		assertEquals(2, Modelling.model().bone("mast").cubes().size());
		// Everything that was there is still there, and nothing is there twice.
		assertEquals(4, Modelling.model().cubeCount());
	}

	@Test
	@DisplayName("a material chosen with several picked out dresses all of them")
	void wearingDressesTheWholeSelection() {
		Modelling.select("root", 0);
		Modelling.alsoSelect("root", 2);

		Modelling.wear("minecraft:stone");

		List<Cube> all = Modelling.model().bone("root").cubes();
		assertEquals("minecraft:stone", all.get(0).block());
		assertEquals("", all.get(1).block(), "and leaves the ones that were not picked");
		assertEquals("minecraft:stone", all.get(2).block());
	}

	@Test
	@DisplayName("a material chosen with a folder picked out dresses everything inside it")
	void wearingReachesIntoFolders() {
		Modelling.select("root", 1);
		assertTrue(Modelling.moveInto("mast"));
		// The folder itself, the way clicking its row in the list leaves things.
		Modelling.select("mast", -1);

		Modelling.wear("minecraft:stone");

		assertEquals("minecraft:stone", Modelling.model().bone("mast").cubes().get(0).block());
	}

	@Test
	@DisplayName("a group turns about one shared point, not about each box in it")
	void turningAGroupSharesOnePivot() {
		// Two boxes a long way apart, the way a cone's first and last are.
		Modelling.take(new Model("test", 64, 64, List.of(
			new Bone("root", "", 0, 0, 0, 0, 0, 0, List.of(
				new Cube(0, 0, 0, 2, 2, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, ""),
				new Cube(20, 0, 0, 22, 2, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, ""))))));
		Modelling.select("root", 0);
		Modelling.alsoSelect("root", 1);

		float[] middle = Modelling.middleOfSelection();

		// Eleven, which is the middle of the pair — not one and not twenty-one, which
		// is what turning each box about itself would use. That is the whole
		// difference between a cone turning and a cone coming apart.
		assertEquals(11, middle[0], 0.001f);
		assertEquals(1, middle[1], 0.001f);
	}

	@Test
	@DisplayName("choosing a folder chooses the folder and everything in it")
	void choosingAFolderTakesItsContents() {
		Modelling.select("root", 1);
		assertTrue(Modelling.moveInto("mast"));

		Modelling.selectFolder("mast");

		assertTrue(Modelling.isSelected("mast", -1), "the folder, for a delete or a move");
		assertTrue(Modelling.isSelected("mast", 0), "and its contents, for everything else");
		assertEquals(0, Modelling.selectedCube(), "the handles land on a box, not on the folder");
	}

	@Test
	@DisplayName("moving a chosen folder carries its boxes rather than emptying it")
	void movingAFolderKeepsItsContents() {
		Modelling.select("root", 0);
		Modelling.alsoSelect("root", 1);
		assertTrue(Modelling.moveInto("mast"));

		Modelling.take(Modelling.model().plus(Bone.named("hold").under("root")));
		Modelling.selectFolder("mast");
		assertTrue(Modelling.moveInto("hold"));

		assertEquals("hold", Modelling.model().bone("mast").parent(), "the folder moved");
		assertEquals(2, Modelling.model().bone("mast").cubes().size(),
			"and took what was in it, rather than being emptied on the way");
		assertEquals(0, Modelling.model().bone("hold").cubes().size(),
			"nothing was tipped out into the destination");
	}

	@Test
	@DisplayName("duplicating a folder makes a folder beside it, not a folder holding two")
	void duplicatingAFolderMakesASibling() {
		Modelling.select("root", 0);
		Modelling.alsoSelect("root", 1);
		assertTrue(Modelling.moveInto("mast"));
		Modelling.selectFolder("mast");

		assertTrue(Modelling.duplicate());

		// The original is untouched and there is a second folder under the same
		// parent. Pouring the copy into "mast" would leave one folder of four boxes,
		// with no way left to tell the two shapes apart.
		assertEquals(2, Modelling.model().bone("mast").cubes().size());
		assertEquals(3, Modelling.model().bones().size(), "root, mast, and the copy");
		Bone copy = Modelling.model().bone("mast2");
		assertEquals(2, copy.cubes().size());
		assertEquals("root", copy.parent(), "beside the original, not inside it");
	}

	@Test
	@DisplayName("copying several and pasting keeps them apart rather than stacking them")
	void pastingKeepsTheShapeOfWhatWasCopied() {
		Modelling.select("root", 0);
		Modelling.alsoSelect("root", 1);
		assertTrue(Modelling.copy());

		assertTrue(Modelling.paste());

		List<Cube> all = Modelling.model().bone("root").cubes();
		assertEquals(6, all.size());
		// The pair moved together, so the gap between the two copies is the gap the
		// two originals had. Pasting each one beside the selection would pile them up.
		float gap = all.get(5).fromX() - all.get(4).fromX();
		assertEquals(0, gap, 0.001f, "both originals started at zero, so both copies do");
		assertEquals(2, Modelling.selectedCount(), "what was pasted is what is now chosen");
	}
}
