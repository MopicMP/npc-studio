package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.mopicmp.npcstudio.dialogue.runtime.Walls;

import net.minecraft.core.BlockPos;

/**
 * The blocks a conversation puts in somebody's world, and getting them all back.
 *
 * <h2>Why this is the test that matters most in the feature</h2>
 *
 * Because its failure is the one nobody can see. A wall left standing is a
 * barrier: no picture, full collision, and not breakable outside creative. The
 * person building the map walks into thin air, in a corridor they walked down an
 * hour ago, with no error, no log line and nothing to look at. They will not
 * suspect the dialogue they were testing; they will suspect their own build.
 *
 * Every case below is a way of leaving one behind:
 *
 * <ul>
 * <li>raising the same wall twice, so two notes exist for one wall and dropping
 *     it once leaves the other with nobody left who knows how to clear it;</li>
 * <li>two conversations sealing one passage, where the first to finish takes the
 *     blocks out from under the second;</li>
 * <li>clearing a cell somebody has since filled themselves, which is the same
 *     bug pointing the other way — not a wall left behind but a block taken;</li>
 * <li>and a graph that raised two and drops one, which is not a broken graph.</li>
 * </ul>
 */
class WallsTest {

	/**
	 * A world of cells, as a set of the ones that hold something.
	 *
	 * Ours and somebody else's are told apart, because half of what this class does
	 * is refuse to touch the second kind.
	 */
	private static final class Ground implements Walls.Ground {

		private final Set<BlockPos> barriers = new HashSet<>();
		private final Set<BlockPos> theirs = new HashSet<>();

		/** A block somebody else put there, which we may neither fill nor clear. */
		void somebodyPut(int x, int y, int z) {
			theirs.add(new BlockPos(x, y, z));
		}

		@Override public boolean empty(BlockPos at) {
			return !barriers.contains(at) && !theirs.contains(at);
		}

		@Override public boolean ours(BlockPos at) {
			return barriers.contains(at);
		}

		@Override public void fill(BlockPos at) {
			barriers.add(at);
		}

		@Override public void clear(BlockPos at) {
			barriers.remove(at);
		}

		int standing() {
			return barriers.size();
		}

		boolean holds(int x, int y, int z) {
			return barriers.contains(new BlockPos(x, y, z));
		}
	}

	private static final UUID ANNA = UUID.nameUUIDFromBytes("anna".getBytes());
	private static final UUID BORIS = UUID.nameUUIDFromBytes("boris".getBytes());
	private static final UUID GUARD = UUID.nameUUIDFromBytes("guard".getBytes());

	/** A doorway: three wide, three tall, one deep. */
	private static final int[] LOW = { 0, 64, 0 };
	private static final int[] HIGH = { 2, 66, 0 };

	@Nested
	@DisplayName("putting one up")
	class Raising {

		@Test
		@DisplayName("a doorway is filled block for block")
		void fills() {
			Ground ground = new Ground();
			Walls walls = new Walls();

			assertEquals(9, walls.raise(ground, ANNA, GUARD, "door", LOW, HIGH));
			assertEquals(9, ground.standing());
			assertTrue(ground.holds(0, 64, 0));
			assertTrue(ground.holds(2, 66, 0));
			assertFalse(ground.holds(3, 64, 0), "and not one block past it");
		}

		@Test
		@DisplayName("what was already solid is left alone")
		void skipsWhatIsThere() {
			Ground ground = new Ground();
			ground.somebodyPut(1, 64, 0);
			ground.somebodyPut(1, 65, 0);
			Walls walls = new Walls();

			// The passage is sealed exactly as well, and nothing has to remember a
			// block in order to hand it back. That is the whole reason only air is
			// filled: clearing air cannot lose anything.
			assertEquals(7, walls.raise(ground, ANNA, GUARD, "door", LOW, HIGH));
		}

		@Test
		@DisplayName("raising the same wall twice does nothing the second time")
		void notTwice() {
			Ground ground = new Ground();
			Walls walls = new Walls();
			walls.raise(ground, ANNA, GUARD, "door", LOW, HIGH);

			assertEquals(0, walls.raise(ground, ANNA, GUARD, "door", LOW, HIGH));
			assertEquals(1, walls.count(), "one wall, one note");

			// The fault this guards. Two notes for one wall means dropping it once
			// leaves the other standing with nobody left who knows how to clear it —
			// and a graph reaching the same act twice is an ordinary loop.
			walls.drop(ground, ANNA, GUARD, "door");
			assertEquals(0, ground.standing());
		}

		@Test
		@DisplayName("a box too big to fill is refused rather than attempted")
		void refusesTheAbsurd() {
			Ground ground = new Ground();
			Walls walls = new Walls();

			// Sixty-four cubed is a quarter of a million blocks. The answer to a
			// mistake in a box is to refuse it and say so, not to spend a minute of the
			// server proving that it was a mistake.
			assertEquals(-1, walls.raise(ground, ANNA, GUARD, "vast",
				new int[] { 0, 0, 0 }, new int[] { 63, 63, 63 }));
			assertEquals(0, ground.standing());
			assertEquals(0, walls.count());
		}
	}

	@Nested
	@DisplayName("taking one down")
	class Dropping {

		@Test
		@DisplayName("the passage comes back")
		void clears() {
			Ground ground = new Ground();
			Walls walls = new Walls();
			walls.raise(ground, ANNA, GUARD, "door", LOW, HIGH);
			walls.drop(ground, ANNA, GUARD, "door");

			assertEquals(0, ground.standing());
			assertEquals(0, walls.count());
		}

		@Test
		@DisplayName("a block somebody has since put in our cell is not taken")
		void doesNotStealBlocks() {
			Ground ground = new Ground();
			Walls walls = new Walls();
			walls.raise(ground, ANNA, GUARD, "door", LOW, HIGH);

			// Somebody in creative broke one of ours and built there. Our note still
			// names the cell, and clearing it on the strength of that note would delete
			// their block — the same bug as leaving a wall behind, pointing the other way.
			ground.clear(new BlockPos(1, 65, 0));
			ground.somebodyPut(1, 65, 0);

			walls.drop(ground, ANNA, GUARD, "door");
			assertTrue(ground.theirs.contains(new BlockPos(1, 65, 0)), "their block stayed");
			assertEquals(0, ground.standing(), "and every one of ours went");
		}

		@Test
		@DisplayName("dropping a wall nobody raised is quiet")
		void unknownIsQuiet() {
			Ground ground = new Ground();
			Walls walls = new Walls();

			// An `act` that opens a box the graph never sealed — a branch that skipped
			// the raising, or an author being careful. Nothing to do and nothing to say.
			walls.drop(ground, ANNA, GUARD, "door");
			assertEquals(0, walls.count());
		}
	}

	@Nested
	@DisplayName("two conversations at once")
	class Sharing {

		@Test
		@DisplayName("the first to finish does not open the passage for the second")
		void theSharedCellStays() {
			Ground ground = new Ground();
			Walls walls = new Walls();

			// Two players in one corridor, each with their own guard, each graph sealing
			// the same doorway. Anna fills it; Boris finds it already full and fills
			// nothing — so his note is empty and his cells are hers.
			assertEquals(9, walls.raise(ground, ANNA, GUARD, "door", LOW, HIGH));
			assertEquals(0, walls.raise(ground, BORIS, GUARD, "door", LOW, HIGH));

			// Anna finishes. The wall must stay, because Boris is still shut in — and
			// this is the case where the honest answer is not the obvious one.
			walls.dropAll(ground, ANNA);
			assertEquals(9, ground.standing(), "the passage is still sealed for Boris");
		}

		@Test
		@DisplayName("overlapping walls each keep what is theirs")
		void overlapping() {
			Ground ground = new Ground();
			Walls walls = new Walls();

			// Two boxes over the same doorway, drawn a block apart, by two different
			// conversations. Anna's covers rows 64 to 66; Boris's covers 65 to 67, so
			// the second only fills the row Anna did not reach.
			walls.raise(ground, ANNA, GUARD, "door", LOW, HIGH);
			assertEquals(3, walls.raise(ground, BORIS, GUARD, "door",
				new int[] { 0, 65, 0 }, new int[] { 2, 67, 0 }));

			walls.dropAll(ground, ANNA);
			// The row only Anna sealed comes back. The two rows both of them are sealing
			// stay, because Boris is still sealing them — his box covers 65 to 67 and he
			// has not finished. Nine minus the three of row 64.
			assertFalse(ground.holds(0, 64, 0), "the row that was only Anna's");
			assertTrue(ground.holds(0, 65, 0), "the rows Boris is still sealing");
			assertTrue(ground.holds(0, 67, 0), "and the row that was only his");
			assertEquals(9, ground.standing());
		}
	}

	@Nested
	@DisplayName("sweeping up")
	class Sweeping {

		@Test
		@DisplayName("a graph that raised two and dropped one still leaves nothing")
		void everythingOfOneConversation() {
			Ground ground = new Ground();
			Walls walls = new Walls();
			walls.raise(ground, ANNA, GUARD, "behind", LOW, HIGH);
			walls.raise(ground, ANNA, GUARD, "ahead",
				new int[] { 0, 64, 8 }, new int[] { 2, 66, 8 });
			walls.drop(ground, ANNA, GUARD, "behind");

			// Not a broken graph. Somebody wrote it thinking about the scene rather
			// than about the blocks, and the ending is what has to sweep up — which is
			// why the ending calls this rather than trusting the author.
			walls.dropAll(ground, ANNA);
			assertEquals(0, ground.standing());
			assertEquals(0, walls.count());
		}

		@Test
		@DisplayName("one player leaving does not open another player's walls")
		void onlyTheirOwn() {
			Ground ground = new Ground();
			Walls walls = new Walls();
			walls.raise(ground, ANNA, GUARD, "door", LOW, HIGH);
			walls.raise(ground, BORIS, GUARD, "far",
				new int[] { 20, 64, 0 }, new int[] { 22, 66, 0 });

			walls.dropAll(ground, ANNA);
			assertEquals(9, ground.standing(), "Boris is still shut in his own corridor");
			assertEquals(1, walls.count());
		}

		@Test
		@DisplayName("a world that did not stop cleanly is swept whoever left them")
		void everything() {
			Ground ground = new Ground();
			Walls walls = new Walls();
			walls.raise(ground, ANNA, GUARD, "door", LOW, HIGH);
			walls.raise(ground, BORIS, GUARD, "far",
				new int[] { 20, 64, 0 }, new int[] { 22, 66, 0 });

			// The case the list is written into the save for: the power went, and the
			// blocks came back with the world while every conversation did not. Nobody
			// is left to own them, so nobody is left to ask.
			walls.dropEverything(ground);
			assertEquals(0, ground.standing());
			assertEquals(0, walls.count());
		}
	}
}
