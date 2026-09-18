package com.mopicmp.npcstudio.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the operations on a scene tidy up after themselves.
 *
 * <h2>The rule, which is narrower than it first looks</h2>
 *
 * Taking a part out of the cast takes its tracks with it, and renaming a part
 * carries its tracks across. Those two are being tidy about the thing they are
 * doing: a name is the only link between a track and a part, so an operation that
 * moves a name has to move everything answering to it or leave the difference
 * behind.
 *
 * <h2>What is not the rule, learnt the hard way</h2>
 *
 * That every track must name a part in the cast. It must not — and this file was
 * first written believing it must. A guard went into {@code keyed} on that
 * reasoning and eight existing tests failed at once, which was them being right
 * and the guard being wrong.
 *
 * The two lists are not one list. A part binds a name to something in the world:
 * who is playing the guard. A track moves a name. Animating "the guard" before
 * anybody is cast as the guard is an ordinary way to work, and the sampling
 * answers for an uncast name deliberately, so that a limb nobody animated keeps
 * the pose it had instead of snapping to nought.
 *
 * Reading tidiness in two operations as a law over the whole document was the
 * mistake. It is written down here because the next person will read those same
 * two operations the same way.
 *
 * <h2>Why the narrow rule is still worth pinning</h2>
 *
 * Because breaking it is silent. A track left behind by a departed part does not
 * throw and does not spoil the file — it plays, and the file goes on carrying a
 * row nobody can reach in the timeline. The scene's own note applies: <em>"a
 * mistake does not crash, it produces an animation that is slightly wrong in a way
 * nobody can point at."</em>
 */
class SceneStaysWholeTest {

	private static Scene peopled() {
		Scene scene = new Scene("gate", 100,
			List.of(new Role("Guard", Role.Kind.CHARACTER, "42", 1),
				new Role("ворота", Role.Kind.OBJECT, "gate_left", 1),
				new Role("watcher", Role.Kind.CAMERA, "7", 1)),
			List.of(), List.of());

		// Two tracks on one part and one on another, so that removing a part is a real
		// removal of several and not of a single convenient one.
		return scene
			.keyed("Guard", "head.yaw", new Key(3, 12.5f, Key.Ease.SMOOTH))
			.keyed("Guard", "body.yaw", new Key(9, -4f, Key.Ease.LINEAR))
			.keyed("watcher", "camera.fov", new Key(1, 65f, Key.Ease.HOLD));
	}

	/** That nothing still answers to a name the cast no longer has. */
	private static void noneLeftBy(Scene scene, String gone, String after) {
		assertTrue(scene.tracksOf(gone).isEmpty(),
			after + ": tracks still answer to \"" + gone + "\", who has left the cast");
	}

	@Test
	@DisplayName("taking a part out takes its tracks with it")
	void removingAPartRemovesItsTracks() {
		Scene without = peopled().withoutRole("Guard");
		noneLeftBy(without, "Guard", "after removing a part");
		assertEquals(1, without.tracks().size(), "the other part's track should remain");
		assertEquals(2, without.cast().size());
	}

	@Test
	@DisplayName("renaming a part carries its tracks across")
	void renamingAPartCarriesItsTracks() {
		Scene renamed = peopled().renaming("Guard", "Стражник");
		noneLeftBy(renamed, "Guard", "after renaming a part");
		assertEquals(2, renamed.tracksOf("Стражник").size(), "both tracks should have moved");
		assertEquals(3, renamed.tracks().size(), "and nothing else should have moved with them");
	}

	@Test
	@DisplayName("renaming onto a name already taken changes nothing at all")
	void renamingOntoATakenNameIsRefused() {
		// Two parts with one name would leave the tracks of both answering to one, with
		// no way afterwards to tell which belonged where. Refusing is right, and the
		// refusal has to be total: half a rename is the broken state itself.
		assertEquals(peopled(), peopled().renaming("Guard", "watcher"));
	}

	@Test
	@DisplayName("renaming a part that is not there changes nothing at all")
	void renamingNobodyIsRefused() {
		assertEquals(peopled(), peopled().renaming("nobody", "somebody"));
	}

	@Test
	@DisplayName("keying a name with no part is allowed, and is not an accident")
	void keyingAnUncastNameIsFine() {
		// The behaviour the guard would have broken, pinned so that nobody adds it back
		// on the same reasoning. A track can be animated before anybody is cast in it.
		Scene scene = peopled().keyed("nobody", "head.yaw", new Key(1, 1f, Key.Ease.LINEAR));
		assertEquals(1, scene.tracksOf("nobody").size());
		assertEquals(4, scene.tracks().size());
	}

	@Test
	@DisplayName("shortening a scene does not drop the tracks that reach past the end")
	void shorteningKeepsTheTracks() {
		// Deliberately: a key past the end is invisible rather than gone, so pulling the
		// length back and pushing it out again returns what was there. Dropping them
		// would make the length control destructive, which is not what a number saying
		// how long something is should be.
		Scene shortened = peopled().lengthened(2);
		assertEquals(3, shortened.tracks().size());
		assertEquals(peopled().tracks(), shortened.lengthened(100).tracks());
	}

	@Test
	@DisplayName("removing a part leaves everybody else exactly as they were")
	void removingOneLeavesTheRest() {
		// The other half of tidiness, and the easier one to get wrong in the direction
		// nobody notices: taking too much. A removal that swept up a neighbour's tracks
		// would be a part standing still with no reason on screen.
		Scene without = peopled().withoutRole("Guard");
		assertEquals(peopled().tracksOf("watcher"), without.tracksOf("watcher"));
		assertEquals(peopled().role("watcher"), without.role("watcher"));
		assertEquals(peopled().role("ворота"), without.role("ворота"));
	}
}
