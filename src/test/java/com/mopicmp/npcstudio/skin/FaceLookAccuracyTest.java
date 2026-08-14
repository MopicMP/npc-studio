package com.mopicmp.npcstudio.skin;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mopicmp.npcstudio.entity.EyeMap;

/**
 * How often the reading agrees with a person, counted on every build.
 *
 * <h2>Why this test is the important one</h2>
 *
 * Every other test of the reading is a face somebody made up, and a made-up face
 * proves only that the code does what its author thought. The reading had eight
 * such tests, all green, while it agreed with a person about the eyes on two
 * faces in thirty-eight — because the faces in the tests were the faces the rule
 * had been written for.
 *
 * These thirty-eight are real skins marked by hand in the game's own eye grid,
 * exported by {@code ./gradlew exportFaceMarks} into {@code test/Eyes/faces.txt}
 * with the sixty-four pixels each mark was made on. So the number below is what
 * a person would say looking at the result, not what the author hoped.
 *
 * <h2>Why it is a floor rather than an equality</h2>
 *
 * The reading is a heuristic against drawings, so it will never be right every
 * time and the number is not meant to be pinned. What must not happen is it
 * quietly getting worse — which is exactly what happened while the tests were
 * all about invented faces. Improve the reading and the floor is raised with it;
 * that is a deliberate act, and the diff says so.
 */
class FaceLookAccuracyTest {

	/**
	 * What the reading manages today. Raise these when it does better.
	 *
	 * <h2>The corpus is eighty-three faces now, and it is not one corpus</h2>
	 *
	 * Twenty-five of them came off HD skins, marked at the size they were drawn and
	 * squeezed into eighths for this test, and the reading is <b>far</b> worse on
	 * those: the rows a blink lands on come out right on forty-one of fifty-eight
	 * ordinary faces and on <b>five of twenty-five</b> HD ones. So a single number
	 * here now averages two quite different problems, and improving the total by
	 * improving the easy half would be a way of hiding that.
	 *
	 * It is left as one number anyway, because splitting it would mean the test
	 * deciding which face is which, and what makes an HD face hard is not its size
	 * — it is that its eye is drawn with more detail than eight pixels can hold.
	 * The split is measured and written down instead; see {@code docs/eyes.md}.
	 */
	private static final int EYES = 46;
	private static final int WHITES = 41;
	private static final int BROWS = 35;
	private static final int ROWS = 47;

	private record Marked(String name, int[] face, EyeMap by) { }

	@Test
	@DisplayName("the reading agrees with the faces marked by hand as often as it did")
	void theReadingAgreesWithAPerson() throws IOException {
		List<Marked> faces = marked();
		Assumptions.assumeFalse(faces.isEmpty(), "no marked faces exported yet");

		int eyes = 0;
		int whites = 0;
		int brows = 0;
		int rows = 0;
		StringBuilder wrong = new StringBuilder();
		for (Marked face : faces) {
			EyeMap got = FaceLook.read(face.face());
			EyeMap want = face.by();

			if (got.eyes() == want.eyes()) eyes++;
			else wrong.append("\n  ").append(face.name()).append(" eyes")
				.append(picture(face.face(), want, got));
			if (got.whites() == want.whites()) whites++;
			if (got.brows() == want.brows()) brows++;
			// What a blink actually needs: the rows the lid comes down over.
			if (java.util.Arrays.equals(EyeMap.rows(got.eyes()), EyeMap.rows(want.eyes()))) rows++;
		}

		String tally = String.format(
			"of %d faces marked by hand: eyes %d, whites %d, brows %d, eye rows %d",
			faces.size(), eyes, whites, brows, rows);
		System.out.println(tally);

		assertTrue(eyes >= EYES, tally + " — the eyes got worse" + wrong);
		assertTrue(whites >= WHITES, tally + " — the whites got worse");
		assertTrue(brows >= BROWS, tally + " — the brows got worse");
		assertTrue(rows >= ROWS, tally + " — the blink would land wrong more often than it did");
	}

	/** A face nobody could read must be refused rather than guessed at. */
	@Test
	@DisplayName("a face with nothing on it is refused")
	void aBlankFaceIsRefused() {
		int[] face = new int[EyeMap.SIZE * EyeMap.SIZE];
		java.util.Arrays.fill(face, 0xFFC49A6C);
		assertTrue(FaceLook.read(face).isNone(), "a blank face was read as eyes");
	}

	/**
	 * A mouth is not a pair of eyes, however symmetric it is.
	 *
	 * The failure worth guarding: a mouth is marks on both sides of the middle,
	 * mirrored, in the rows eyes live in. What tells it apart is that it is one
	 * colour lying <em>across</em> the middle, so what is between its ends is the
	 * mouth again.
	 */
	@Test
	@DisplayName("a mouth is not read as eyes")
	void aMouthIsNotEyes() {
		int[] face = new int[EyeMap.SIZE * EyeMap.SIZE];
		java.util.Arrays.fill(face, 0xFFC49A6C);
		for (int x = 2; x <= 5; x++) face[6 * EyeMap.SIZE + x] = 0xFF2B1A0F;
		assertTrue(FaceLook.read(face).isNone(), "a mouth was read as eyes");
	}

	/** The pair, drawn the way nearly every face draws it. */
	@Test
	@DisplayName("an ordinary face is read the way a person marks it")
	void anOrdinaryFaceIsRead() {
		int[] face = new int[EyeMap.SIZE * EyeMap.SIZE];
		java.util.Arrays.fill(face, 0xFFC49A6C);
		for (int y = 4; y <= 5; y++) {
			face[y * EyeMap.SIZE + 1] = 0xFFFFFFFF;
			face[y * EyeMap.SIZE + 2] = 0xFF2B1A0F;
			face[y * EyeMap.SIZE + 5] = 0xFF2B1A0F;
			face[y * EyeMap.SIZE + 6] = 0xFFFFFFFF;
		}
		for (int x : new int[] { 1, 2, 5, 6 }) face[3 * EyeMap.SIZE + x] = 0xFF3A2410;

		EyeMap read = FaceLook.read(face);

		assertTrue(read.isEye(2, 4) && read.isEye(2, 5) && read.isEye(5, 4) && read.isEye(5, 5),
			"the iris is the darker half of the pair, both rows of it");
		assertTrue(read.isWhite(1, 4) && read.isWhite(6, 5),
			"and the white is the lighter half, outside it");
		assertTrue(read.isBrow(1, 3) && read.isBrow(2, 3),
			"the stroke above the eye is a brow");
		assertTrue(!read.isEye(1, 4) && !read.isBrow(2, 4),
			"and the three do not overlap");
	}

	// ------------------------------------------------------------------ the file

	private static List<Marked> marked() throws IOException {
		Path file = Path.of("../test/Eyes/faces.txt");
		if (!Files.exists(file)) return List.of();

		List<Marked> faces = new ArrayList<>();
		String name = null;
		long eyes = 0;
		long whites = 0;
		long brows = 0;
		int[] pixels = new int[EyeMap.SIZE * EyeMap.SIZE];
		int filled = 0;

		for (String line : Files.readAllLines(file)) {
			String text = line.strip();
			if (text.startsWith("#") || text.isEmpty()) continue;
			int space = text.indexOf(' ');
			if (space < 0) continue;
			String what = text.substring(0, space);
			String rest = text.substring(space + 1).split("#")[0].strip();

			switch (what) {
				case "face" -> {
					name = rest;
					eyes = whites = brows = 0;
					filled = 0;
					pixels = new int[EyeMap.SIZE * EyeMap.SIZE];
				}
				case "eyes" -> eyes = Long.parseUnsignedLong(rest, 16);
				case "whites" -> whites = Long.parseUnsignedLong(rest, 16);
				case "brows" -> brows = Long.parseUnsignedLong(rest, 16);
				case "pixels" -> {
					for (String value : rest.split("\\s+")) {
						if (filled < pixels.length) pixels[filled++] = (int) Long.parseLong(value, 16);
					}
					if (filled == pixels.length) {
						faces.add(new Marked(name, pixels, new EyeMap(eyes, whites, brows, true)));
					}
				}
				default -> { }
			}
		}
		return faces;
	}

	/** The face, what a person marked and what the reading made of it, side by side. */
	private static String picture(int[] face, EyeMap want, EyeMap got) {
		StringBuilder text = new StringBuilder();
		for (int y = 0; y < EyeMap.SIZE; y++) {
			text.append("\n    ");
			for (int x = 0; x < EyeMap.SIZE; x++) text.append(shade(face[y * EyeMap.SIZE + x]));
			text.append("  ");
			for (int x = 0; x < EyeMap.SIZE; x++) text.append(letter(want, x, y));
			text.append("  ");
			for (int x = 0; x < EyeMap.SIZE; x++) text.append(letter(got, x, y));
		}
		return text.toString();
	}

	private static char letter(EyeMap map, int x, int y) {
		return map.isEye(x, y) ? 'e' : map.isWhite(x, y) ? 'w' : map.isBrow(x, y) ? 'b' : '.';
	}

	private static char shade(int colour) {
		return " .:-=+*#%@".charAt(Math.clamp(9 - FaceLook.light(colour) * 10 / 256, 0, 9));
	}
}
