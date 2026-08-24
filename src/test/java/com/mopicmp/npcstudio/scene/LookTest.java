package com.mopicmp.npcstudio.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How a line on the screen is dressed, and how that survives being saved.
 *
 * <h2>Why the alignment sum is worth a test</h2>
 *
 * Because the default is centred, so every hand-check and every screenshot is of
 * the one case where a wrong sign is invisible. Left and right are the two that
 * would be found weeks later by somebody wondering why a caption pinned to the
 * left edge is off the screen.
 *
 * <h2>And why the file format is</h2>
 *
 * Because a scene written before captions had an appearance has no such object in
 * it, and one written after usually has none either — the plain look is left out
 * on the way to disk so the file does not carry nine numbers all saying "as
 * usual". Both cases have to come back as the plain look rather than as nulls or
 * as nought-sized invisible text.
 */
class LookTest {

	@Test
	@DisplayName("a centred line straddles its point and the others hang off it")
	void alignmentPutsTheLineWhereItSays() {
		float wide = 80;
		assertEquals(0, Look.PLAIN.aligned(Look.Align.LEFT).offsetOf(wide), 1e-6,
			"aligned left, the line starts at the point");
		assertEquals(-40, Look.PLAIN.aligned(Look.Align.CENTRE).offsetOf(wide), 1e-6,
			"centred, half of it is either side");
		assertEquals(-80, Look.PLAIN.aligned(Look.Align.RIGHT).offsetOf(wide), 1e-6,
			"aligned right, the line ends at the point");
	}

	@Test
	@DisplayName("a size nobody can read is refused and a place off frame is not")
	void whatIsClampedAndWhatIsNot() {
		// The font is eight pixels tall to begin with. A quarter of that is not small
		// text, it is a smear, and a size of twenty is one word across the frame.
		assertEquals(Look.SMALLEST, Look.PLAIN.withSize(0.01f).size(), 1e-6);
		assertEquals(Look.LARGEST, Look.PLAIN.withSize(99f).size(), 1e-6);

		// The position is not clamped to the frame, and that is deliberate: a line
		// parked just off the edge to slide in is a thing somebody means. Only the
		// absurd is refused.
		assertEquals(-0.3f, Look.PLAIN.at(-0.3f, 1.2f).x(), 1e-6, "just off the left");
		assertEquals(1.2f, Look.PLAIN.at(-0.3f, 1.2f).y(), 1e-6, "just off the bottom");
	}

	@Test
	@DisplayName("only a line has an appearance; a sound and a piece of music have none")
	void onlyTextIsDressed() {
		// Nine fields that mean nothing in two thirds of the cases are nine fields
		// that get half-written by some path nobody checked. Settled in the record
		// so there is one answer rather than one per reader.
		assertNotNull(Cue.text(0, "Земля!", 40).look());
		assertNull(Cue.sound(0, "minecraft:entity.player.levelup").look());
		assertNull(Cue.music(0, "море").look());

		// And a line handed nothing still has the plain look, because a scene from
		// before any of this existed is full of exactly that.
		assertEquals(Look.PLAIN, new Cue(0, Cue.Kind.TEXT, "Земля!", 40, null).look());
	}

	@Test
	@DisplayName("an appearance survives being written down and read back")
	void itSurvivesTheFile() {
		Look dressed = Look.PLAIN
			.withFont("minecraft:illageralt")
			.withSize(3f)
			.withColour(0xFFC46B)
			.at(0.2f, 0.8f)
			.aligned(Look.Align.LEFT)
			.withBold(true)
			.withItalic(true)
			.withShadow(false);

		Scene scene = Scene.empty("корабль").with(Cue.text(20, "Земля!", 60, dressed));
		Scene back = SceneIO.read(SceneIO.write(scene));

		Cue line = back.cues().get(0);
		assertEquals("Земля!", line.what());
		assertEquals(60, line.lasts());
		assertEquals(dressed, line.look(), "every one of the nine, not most of them");
	}

	@Test
	@DisplayName("a scene written before any of this reads as the plain look")
	void oldFilesStillOpen() {
		// The exact shape on disk before captions could be dressed: a cue with four
		// fields and no look at all. It must not come back as nulls, and it must not
		// come back as a line of size nought that is written down and never visible.
		var json = com.google.gson.JsonParser.parseString("""
			{"name":"корабль","length":100,"cast":[],"tracks":[],
			 "cues":[{"at":20,"kind":"TEXT","what":"Земля!","lasts":60}]}
			""").getAsJsonObject();

		Cue line = SceneIO.read(json).cues().get(0);
		assertEquals(Look.PLAIN, line.look());
		assertTrue(line.look().size() > 0, "and it is a size somebody can see");
	}

	@Test
	@DisplayName("the plain look is left out of the file rather than written nine times")
	void theOrdinaryCaseIsNotWrittenDown() {
		Scene plain = Scene.empty("корабль").with(Cue.text(20, "Земля!", 60));
		var cue = SceneIO.write(plain).getAsJsonArray("cues").get(0).getAsJsonObject();
		assertTrue(!cue.has("look"),
			"a line nobody restyled should not carry nine numbers all saying 'as usual'");
	}
}
