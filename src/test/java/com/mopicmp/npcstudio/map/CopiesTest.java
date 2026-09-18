package com.mopicmp.npcstudio.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

/**
 * Laying lessons out side by side without them touching.
 *
 * <h2>What is being protected</h2>
 *
 * Two things, and both of them fail quietly. Copies that overlap are two lessons in one
 * piece of ground, which looks like a lesson that was built wrong. And a row that only
 * ever grows is a server that, after a month, lays its lessons a hundred thousand blocks
 * from the middle for no reason anybody watching could work out.
 *
 * Neither shows up on a server with two people on it, which is every server this will be
 * tried on first.
 */
class CopiesTest {

	private static final UUID ANNA = UUID.nameUUIDFromBytes("anna".getBytes());
	private static final UUID BORIS = UUID.nameUUIDFromBytes("boris".getBytes());
	private static final UUID VERA = UUID.nameUUIDFromBytes("vera".getBytes());

	/**
	 * Somewhere on the map to have come from, so that leaving has an answer.
	 *
	 * The key is built rather than taken from {@code Level.OVERWORLD}, because reading
	 * that field initialises {@code Level}, which pulls in the particle registries, which
	 * need a game that has started. A key is two words and needs nothing — which is also
	 * why a copy holds a key and a position rather than the game's own GlobalPos.
	 */
	private static final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>
		HOME_WORLD = net.minecraft.resources.ResourceKey.create(
			net.minecraft.core.registries.Registries.DIMENSION,
			net.minecraft.resources.Identifier.withDefaultNamespace("overworld"));

	private static final BlockPos HOME_AT = new BlockPos(8, 64, 8);

	private static Copies.Copy copy(String where, UUID who, BlockPos at, int wide) {
		return new Copies.Copy(where, who, at, new Vec3i(wide, 8, wide), HOME_WORLD, HOME_AT, false);
	}

	@Test
	@DisplayName("a point in a copy names the lesson it is in, and anywhere else names none")
	void whichLessonIsThis() {
		// The one question the rest of the mod asks, and it is asked of a position
		// because a character standing in a lesson is not a player and has nobody to be
		// looked up under.
		Copies copies = new Copies();
		copies.put(copy("поляна", ANNA, new BlockPos(0, 0, 0), 16));
		assertEquals("поляна", copies.at(new BlockPos(0, 0, 0)));
		assertEquals("поляна", copies.at(new BlockPos(15, 7, 15)));
		assertEquals("", copies.at(new BlockPos(16, 0, 0)), "one past the far corner is out");
		assertEquals("", copies.at(new BlockPos(-1, 0, 0)));
		assertEquals("", copies.at(null), "and nowhere is the map's own ground");
	}

	@Test
	@DisplayName("two copies laid out one after another do not touch")
	void twoDoNotTouch() {
		Copies copies = new Copies();
		BlockPos first = copies.free(new Vec3i(16, 8, 16), 64);
		copies.put(copy("поляна", ANNA, first, 16));
		BlockPos second = copies.free(new Vec3i(16, 8, 16), 64);
		copies.put(copy("пещера", BORIS, second, 16));

		assertTrue(second.getX() >= first.getX() + 16 + 64,
			"a whole gap between them: " + first + " then " + second);
		assertEquals("поляна", copies.at(first));
		assertEquals("пещера", copies.at(second));
		assertEquals("", copies.at(new BlockPos(first.getX() + 20, 0, 0)),
			"the gap belongs to nobody");
	}

	@Test
	@DisplayName("room a guest leaves behind is used again")
	void theRowDoesNotOnlyGrow() {
		// Without this the row grows for as long as the server runs. Nothing breaks at
		// once; it just gets further away every week, and the first sign of it is a
		// lesson taking a noticeable moment to arrive.
		Copies copies = new Copies();
		BlockPos first = copies.free(new Vec3i(16, 8, 16), 64);
		copies.put(copy("поляна", ANNA, first, 16));
		BlockPos second = copies.free(new Vec3i(16, 8, 16), 64);
		copies.put(copy("пещера", BORIS, second, 16));

		copies.drop(ANNA);
		BlockPos third = copies.free(new Vec3i(16, 8, 16), 64);
		assertEquals(first, third, "the first stretch is free again and taken again");
	}

	@Test
	@DisplayName("a copy wider than the gap still gets clear room on both sides")
	void aWideOneStillFits() {
		// The arithmetic that is easy to get wrong: checking only that the new one
		// starts after the old one ends forgets that the new one has a width of its own.
		Copies copies = new Copies();
		copies.put(copy("поляна", ANNA, new BlockPos(0, 0, 0), 16));
		BlockPos next = copies.free(new Vec3i(500, 8, 8), 64);
		assertTrue(next.getX() >= 16 + 64, "it starts clear of the first: " + next);
		copies.put(new Copies.Copy("длинная", BORIS, next, new Vec3i(500, 8, 8), HOME_WORLD, HOME_AT, false));
		assertEquals("", copies.at(new BlockPos(next.getX() - 1, 0, 0)),
			"and the block before it belongs to nobody");
		assertEquals("длинная", copies.at(new BlockPos(next.getX() + 499, 0, 0)));
	}

	@Test
	@DisplayName("a gap smaller than the floor is raised to it")
	void theGapHasAFloor() {
		// A server set to a view distance of two would otherwise put lessons close
		// enough that walking the wrong way leaves one and arrives in the next, which
		// is worse than merely seeing it.
		Copies copies = new Copies();
		copies.put(copy("поляна", ANNA, new BlockPos(0, 0, 0), 16));
		BlockPos next = copies.free(new Vec3i(16, 8, 16), 0);
		assertTrue(next.getX() >= 16 + Copies.LEAST_GAP, "floored: " + next);
	}

	@Test
	@DisplayName("one person is in one lesson, and walking into another replaces it")
	void onePersonOneLesson() {
		// The rule the author set: leaving and coming back resets. A second note for the
		// same person would keep a stretch of the row reserved for ground that is no
		// longer anywhere.
		Copies copies = new Copies();
		copies.put(copy("поляна", ANNA, new BlockPos(0, 0, 0), 16));
		copies.put(copy("пещера", ANNA, new BlockPos(500, 0, 0), 16));
		assertEquals(1, copies.count());
		assertEquals("пещера", copies.forGuest(ANNA).location());
		assertEquals("", copies.at(new BlockPos(0, 0, 0)), "the old ground is nobody's");
	}

	@Test
	@DisplayName("everything is dropped at once, and says what it was")
	void sweeping() {
		// What a server does on the way up, because copies do not survive a restart but
		// their blocks do — the same shape as a wall left standing by a conversation
		// that never ended.
		Copies copies = new Copies();
		copies.put(copy("поляна", ANNA, new BlockPos(0, 0, 0), 16));
		copies.put(copy("пещера", BORIS, new BlockPos(500, 0, 0), 16));
		var had = copies.dropEverything();
		assertEquals(2, had.size(), "the sweep is told what to clear");
		assertEquals(0, copies.count());
		assertTrue(copies.dropEverything().isEmpty(), "and again is nothing to do");
	}

	@Test
	@DisplayName("the hub stands apart: not swept, and not in the way of the row")
	void theHubIsKept() {
		// The whole of what makes it the hub. Swept with the rest, its ground would go
		// on every restart and the next visitor would find an empty place; counted in
		// the row, it would push every lesson along by its own width for no reason.
		Copies copies = new Copies();
		copies.put(new Copies.Copy("хаб", VERA, new BlockPos(-1024, 0, 0),
			new Vec3i(64, 16, 64), HOME_WORLD, HOME_AT, true));
		copies.put(copy("поляна", ANNA, new BlockPos(0, 0, 0), 16));

		assertEquals(new BlockPos(-1024, 0, 0), copies.hubStanding().origin());
		assertEquals("хаб", copies.at(new BlockPos(-1000, 0, 0)),
			"and it is still somewhere you can be standing");

		var swept = copies.dropEverything();
		assertEquals(1, swept.size(), "only the lesson is taken down");
		assertEquals("поляна", swept.get(0).location());
		assertNotNull(copies.hubStanding(), "the hub is the one thing a map keeps");

		BlockPos next = copies.free(new Vec3i(16, 8, 16), 64);
		assertEquals(0, next.getX(), "the hub does not push the row along");
	}

	@Test
	@DisplayName("nobody has a copy until they are given one")
	void nobodyByDefault() {
		Copies copies = new Copies();
		assertNull(copies.forGuest(VERA));
		assertNull(copies.forGuest(null));
		assertEquals("", copies.at(new BlockPos(0, 0, 0)));
	}

	@Test
	@DisplayName("the notes survive the file, so a restart knows what to sweep")
	void throughTheFile() {
		Copies copies = new Copies();
		copies.put(copy("поляна", ANNA, new BlockPos(0, 0, 0), 16));
		copies.put(copy("пещера", BORIS, new BlockPos(500, 0, 0), 16));
		Copies back = Copies.CODEC
			.parse(JsonOps.INSTANCE, Copies.CODEC
				.encodeStart(JsonOps.INSTANCE, copies).getOrThrow(IllegalStateException::new))
			.getOrThrow(IllegalStateException::new);
		assertEquals(2, back.count());
		assertNotNull(back.forGuest(ANNA));
		assertEquals("пещера", back.at(new BlockPos(500, 0, 0)));
	}
}
