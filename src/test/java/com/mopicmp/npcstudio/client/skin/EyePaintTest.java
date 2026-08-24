package com.mopicmp.npcstudio.client.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mopicmp.npcstudio.entity.FaceMask;

/**
 * The eye drawn to the shape somebody marked, tested on a face somebody marked.
 *
 * <h2>Where the fixture comes from</h2>
 *
 * It is not invented. The mask below is the one in the reporter's own world,
 * copied out of {@code wardrobe.json}, for the costume the complaints named. The
 * skin is 256 across, so the face is thirty-two cells and one cell is a quarter
 * of a model pixel, and the marking is this:
 *
 * <pre>
 * cell    4 5 6 7 8 9 10 11 12
 * row 16  W W W W E E  E  E  .
 * row 17  W W W W E E  E  E  .
 * row 18  . . W W E E  E  E  .
 * </pre>
 *
 * Checked against the picture, that marking is exact to the cell: the sclera
 * really does run 162, 171, 182, 195 across row 16 and 195, 211, 231, 255 across
 * row 17, the iris really is cells 8 to 11, and cells 4 and 5 of row 18 really
 * are cheek at 199. Nothing was wrong with the marking. Everything that was
 * wrong was the drawing treating it as the rectangle it is not.
 *
 * <h2>What these tests are really pinning</h2>
 *
 * One rule, from which all four of the complaints fall out: <b>nothing is ever
 * drawn outside the cells somebody marked</b>. An unmarked cell inside the eye's
 * bounding box is cheek, and cheek does not travel with a pupil, does not get
 * painted over when one narrows, and does not get dragged back into the eye by
 * the strip that fills in behind a glance.
 */
class EyePaintTest {

	/**
	 * The costume the complaints were made against, as its own world records it.
	 *
	 * Read back rather than rebuilt, so that a change to how a mask is stored
	 * fails here rather than in somebody's game.
	 */
	private static final String REPORTED =
		"20:208.4.8.4.10.4.8.4.10.4.8.4.1a8//204.4.10.4.8.4.10.4.a.2.10.2.1a6"
			+ "//1a7.3.c.3.b.9.6.9.7.a.6.a.203///";

	private static FaceMask reported() {
		return FaceMask.decode(REPORTED, true);
	}

	private static FaceReading face() {
		return FaceReading.of(reported());
	}

	private static EyeRows rows() {
		return EyeRows.of(reported());
	}

	// ------------------------------------------------------------------ the shape

	@Test
	@DisplayName("the eye is read as the shape it was marked, not as the box round it")
	void theShapeSurvivesReading() {
		EyeRows rows = rows();
		assertNotNull(rows);
		assertEquals(0.25f, rows.texel(), 1e-5, "a 256-wide skin has four cells to the pixel");
		assertEquals(3, rows.rows().length, "three rows were marked");

		// Rows 16 and 17 are marked from cell 4; row 18 only from cell 6, because
		// cells 4 and 5 of it are cheek. The box round the lot starts at cell 4 on
		// every row, which is exactly the two cells that used to travel with the eye.
		assertEquals(9.0f, rows.rows()[0].open()[0], 1e-5);
		assertEquals(9.0f, rows.rows()[1].open()[0], 1e-5);
		assertEquals(9.5f, rows.rows()[2].open()[0], 1e-5,
			"the last row starts two cells in, where the marking does");

		assertEquals(9.0f, face().eyeOuter(), 1e-5,
			"and the box round it does not, which is the whole difference");
	}

	@Test
	@DisplayName("each row borrows its white from the cell beside the iris, not the first one found")
	void theWhiteIsTheNearestOne() {
		// Cell 7, against the iris — which on this face is a clean 255 in row 17 and
		// 195 in row 16. Taking the first white found gave cell 4 on every row, which
		// is 162: a dull grey block behind an eye whose white is nearly paper.
		for (EyeRows.Row row : rows().rows()) {
			assertEquals(9.75f, row.white(), 1e-5, "the white nearest the iris is cell 7");
		}
	}

	@Test
	@DisplayName("the lid takes its skin from outside the eye, never from inside it")
	void theLidTakesBareSkin() {
		for (EyeRows.Row row : rows().rows()) {
			assertEquals(11.0f, row.bare(), 1e-5, "cell 12, the first unmarked one");
			assertTrue(row.bare() >= row.open()[row.open().length - 1],
				"which is past the eye rather than in it");
		}
	}

	// -------------------------------------------------------- nothing leaves the eye

	/**
	 * The rule the whole rewrite exists for, checked over everything an eye does.
	 *
	 * A glance every tenth of the way across, a pupil at every size from shut to
	 * wide, and a lid at every stage of closing — on both eyes. Not one quad of any
	 * of it may touch a cell nobody marked.
	 */
	@Test
	@DisplayName("no quad of a moving eye ever lands on skin nobody marked")
	void nothingIsDrawnOutsideTheMarking() {
		FaceReading face = face();
		for (boolean right : new boolean[] { true, false }) {
			EyeRows rows = right ? face.rows() : face.rows().mirrored();
			for (int aside = -10; aside <= 10; aside++) {
				for (int pupil = -10; pupil <= 10; pupil++) {
					Glance glance = Glance.of(face, aside / 10f, 0f, pupil / 10f, right);
					if (glance == null) continue;
					inside(rows, EyePaint.eye(rows, glance),
						"glance " + aside + " pupil " + pupil + (right ? " right" : " left"));
				}
			}
			for (int shut = 1; shut <= 10; shut++) {
				inside(rows, EyePaint.lid(rows, face.eyeTop(), face.eyeBottom(), shut / 10f),
					"lid " + shut);
				inside(rows, EyePaint.lash(rows, face.eyeTop(), face.eyeBottom(), shut / 10f),
					"lash " + shut);
			}
		}
	}

	/** Every patch lies within the marked opening of the rows it covers. */
	private static void inside(EyeRows rows, List<EyePaint.Patch> patches, String what) {
		for (EyePaint.Patch patch : patches) {
			boolean held = false;
			for (EyeRows.Row row : rows.rows()) {
				if (patch.v0() < row.top() - 1e-4 || patch.v1() > row.bottom() + 1e-4) continue;
				for (int i = 0; i + 1 < row.open().length; i += 2) {
					if (patch.u0() >= row.open()[i] - 1e-4
						&& patch.u1() <= row.open()[i + 1] + 1e-4) {
						held = true;
					}
				}
			}
			assertTrue(held, what + ": a quad at u " + patch.u0() + ".." + patch.u1()
				+ " v " + patch.v0() + ".." + patch.v1() + " is outside the marking");
		}
	}

	@Test
	@DisplayName("a moving eye covers its own opening exactly once, with no seams and no overlap")
	void theOpeningIsPartitioned() {
		FaceReading face = face();
		EyeRows rows = face.rows();
		for (int aside = -10; aside <= 10; aside++) {
			for (int pupil = -10; pupil <= 10; pupil++) {
				Glance glance = Glance.of(face, aside / 10f, 0f, pupil / 10f, true);
				if (glance == null) continue;
				List<EyePaint.Patch> patches = EyePaint.eye(rows, glance);

				for (EyeRows.Row row : rows.rows()) {
					float marked = 0;
					for (int i = 0; i + 1 < row.open().length; i += 2) {
						marked += row.open()[i + 1] - row.open()[i];
					}
					// Weighted by how much of the row's height each quad covers, since
					// a narrowing pupil cuts a row into bands.
					float drawn = 0;
					float height = row.bottom() - row.top();
					for (EyePaint.Patch patch : patches) {
						float from = Math.max(patch.v0(), row.top());
						float to = Math.min(patch.v1(), row.bottom());
						if (to <= from) continue;
						drawn += (patch.u1() - patch.u0()) * (to - from) / height;
					}
					assertEquals(marked, drawn, 1e-3,
						"glance " + aside + " pupil " + pupil + ": the opening of the row at "
							+ row.top() + " was covered " + (drawn / marked) + " times over");
				}
			}
		}
	}

	// ------------------------------------------------------------------ the glance

	@Test
	@DisplayName("a full glance moves the iris onto the white and puts the white behind it")
	void theEyeSlidesWithinItsOwnShape() {
		FaceReading face = face();
		EyeRows rows = face.rows();
		Glance glance = Glance.of(face, 1f, 0f, 0f, true);
		assertNotNull(glance);
		assertEquals(-1f, glance.shift(), 1e-5, "a whole pixel, which here is four cells");

		List<EyePaint.Patch> patches = EyePaint.eye(rows, glance);
		// The top row: the iris, four cells of it, drawn where the white was.
		EyePaint.Patch moved = at(patches, 9.0f, 12.0f);
		assertNotNull(moved, "something is drawn at the eye's outer corner");
		assertEquals(10.0f, moved.su0(), 1e-5, "and it is the iris, taken from where it was drawn");
		assertEquals(11.0f, moved.su1(), 1e-5);

		// And behind it, the white — from this row's own white cell rather than from
		// one column stretched over all three.
		EyePaint.Patch filled = at(patches, 10.0f, 12.0f);
		assertNotNull(filled, "the place it left is filled in");
		assertEquals(9.75f, filled.su0(), 1e-5, "with the white beside the iris");
		assertEquals(10.0f, filled.su1(), 1e-5, "one cell of it, not one pixel");
	}

	@Test
	@DisplayName("the eye's narrow bottom row clips the iris rather than dragging cheek along")
	void theNarrowRowClips() {
		FaceReading face = face();
		Glance glance = Glance.of(face, 1f, 0f, 0f, true);
		List<EyePaint.Patch> patches = EyePaint.eye(face.rows(), glance);

		// Row 18 is six cells wide where the others are eight, so its iris has two
		// cells of room rather than four and is cut off by the eye's own corner —
		// which is what an artist drawing that eye looking aside would have done.
		// The box drew it the full four and painted two cells of cheek with iris.
		for (EyePaint.Patch patch : patches) {
			if (patch.v0() < 12.49f) continue;
			assertTrue(patch.u0() >= 9.5f - 1e-4,
				"nothing in the bottom row starts before the marking does: " + patch.u0());
		}
	}

	// ------------------------------------------------------------------ the pupil

	@Test
	@DisplayName("a widening pupil is a share of the white, not a number of pixels")
	void wideningIsAShare() {
		FaceReading face = face();
		// Half means half the white given up. It used to mean half a model pixel,
		// which on this eye is two of its four white cells — the same answer by
		// luck — and on an eye with a wider white it was the whole of it, which is
		// why every character in an unlit room was all pupil.
		Glance half = Glance.of(face, 0f, 0f, 0.5f, true);
		assertNotNull(half);
		assertEquals(9.5f, half.pupil().from() + EyePaint.MIDDLE, 1e-5,
			"the iris starts at 10 and the white reaches to 9, so half of it is 9.5");

		Glance whole = Glance.of(face, 0f, 0f, 1f, true);
		assertEquals(face.eyeOuter(), whole.pupil().from() + EyePaint.MIDDLE, 1e-5,
			"and only a one takes all of it");
	}

	@Test
	@DisplayName("a narrowing pupil leaves white behind it rather than tearing the drawing")
	void narrowingFillsWithWhite() {
		FaceReading face = face();
		EyeRows rows = face.rows();
		Glance narrow = Glance.of(face, 0f, 0f, -1f, true);
		assertNotNull(narrow);

		List<EyePaint.Patch> patches = EyePaint.eye(rows, narrow);
		assertFalse(patches.isEmpty());
		// Everything that actually changes is either the pupil or this row's own
		// white. The rest of the partition is the sclera sitting where it already
		// sits, which the drawing throws away rather than painting over itself.
		for (EyePaint.Patch patch : patches) {
			if (patch.redundant()) continue;
			boolean iris = patch.su0() >= 10.0f - 1e-4 && patch.su1() <= 11.0f + 1e-4;
			boolean white = Math.abs(patch.su0() - 9.75f) < 1e-4;
			assertTrue(iris || white,
				"a narrowing pupil drew from u " + patch.su0() + ", which is neither");
		}
		// And it keeps half of itself: a pupil closed to nothing is not a bright day.
		float kept = narrow.pupil().to() - narrow.pupil().from();
		float drawn = narrow.erase().to() - narrow.erase().from();
		assertEquals(0.5f, kept / drawn, 1e-4, "half its width, as the doc has always said");
	}

	// ------------------------------------------------------------------ the blink

	@Test
	@DisplayName("a blink is skin over the eye, not a rectangle of iris over the face")
	void theLidIsSkinAndTheLashIsOneCell() {
		FaceReading face = face();
		EyeRows rows = face.rows();

		List<EyePaint.Patch> lid = EyePaint.lid(rows, face.eyeTop(), face.eyeBottom(), 1f);
		assertFalse(lid.isEmpty());
		for (EyePaint.Patch patch : lid) {
			assertEquals(11.0f, patch.su0(), 1e-5, "the lid is bare skin from beside the eye");
			assertEquals(0.25f, patch.su1() - patch.su0(), 1e-5, "one cell of it");
		}

		// The lash. It was a whole model pixel tall — four cells, on an eye three
		// cells tall — so it covered the entire lid in the colour of the iris and
		// every blink came down as a solid dark block. That is what "the blink uses
		// the pupil's texture" was, and it is one cell now.
		List<EyePaint.Patch> lash = EyePaint.lash(rows, face.eyeTop(), face.eyeBottom(), 1f);
		assertEquals(1, lash.size(), "one band, on the row the lid's edge has reached");
		assertEquals(0.1875f, lash.get(0).v1() - lash.get(0).v0(), 1e-5,
			"a quarter of this three-cell eye, which is thinner than its cell");
		assertTrue(lash.get(0).v1() <= face.eyeBottom() + 1e-4, "at the lid's own edge");

		float tall = 0;
		for (EyePaint.Patch patch : lash) tall += patch.v1() - patch.v0();
		assertTrue(tall < (face.eyeBottom() - face.eyeTop()) / 2f,
			"and never most of the eye, which is what it was");
	}

	@Test
	@DisplayName("a half-shut eye is half covered, and the half below it is untouched")
	void theLidComesDownGradually() {
		FaceReading face = face();
		EyeRows rows = face.rows();
		float top = face.eyeTop();
		float bottom = face.eyeBottom();

		float last = -1;
		for (int step = 0; step <= 10; step++) {
			float covered = 0;
			for (EyePaint.Patch patch : EyePaint.lid(rows, top, bottom, step / 10f)) {
				covered += (patch.u1() - patch.u0()) * (patch.v1() - patch.v0());
			}
			assertTrue(covered >= last - 1e-4, "a closing lid covers more, never less");
			last = covered;
			for (EyePaint.Patch patch : EyePaint.lid(rows, top, bottom, step / 10f)) {
				assertTrue(patch.v1() <= top + (bottom - top) * (step / 10f) + 1e-4,
					"and never below its own edge");
			}
		}
	}

	// ------------------------------------------------------------------ both eyes

	@Test
	@DisplayName("the other eye is this one reflected, and lands where the other eye is")
	void theFarEyeIsMirrored() {
		EyeRows near = rows();
		EyeRows far = near.mirrored();

		assertEquals(near.rows().length, far.rows().length);
		for (int i = 0; i < near.rows().length; i++) {
			EyeRows.Row a = near.rows()[i];
			EyeRows.Row b = far.rows()[i];
			assertEquals(a.top(), b.top(), 1e-5, "the same rows of the face");
			// The face's middle is 24 in sixty-fourths, so a run at 9..11 belongs at
			// 13..15 — which is where the other eye of this skin actually is.
			assertEquals(EyeRows.MIRROR - a.open()[a.open().length - 1], b.open()[0], 1e-5);
			assertEquals(EyeRows.MIRROR - a.open()[0], b.open()[b.open().length - 1], 1e-5);
		}
		assertEquals(13.0f, far.rows()[0].open()[0], 1e-5);
		assertEquals(15.0f, far.rows()[0].open()[1], 1e-5);
	}

	// ------------------------------------------------------------------ plain skins

	@Test
	@DisplayName("an ordinary eight-cell face still works, and a cell there is a whole pixel")
	void anOrdinaryFaceIsUnchanged() {
		// One white and one iris, the eye every plain skin has, at the rows the
		// reading always put them. Nothing about this face has anything to gain from
		// runs — and that is the point: the general path has to be right on it too.
		long eyes = com.mopicmp.npcstudio.entity.EyeMap.with(
			com.mopicmp.npcstudio.entity.EyeMap.with(0L, 2, 4, true), 5, 4, true);
		long whites = com.mopicmp.npcstudio.entity.EyeMap.with(
			com.mopicmp.npcstudio.entity.EyeMap.with(0L, 1, 4, true), 6, 4, true);
		FaceReading face = FaceReading.of(
			new com.mopicmp.npcstudio.entity.EyeMap(eyes, whites, 0L, true));

		assertNotNull(face.rows());
		assertEquals(1f, face.rows().texel(), 1e-5, "a cell and a pixel are the same thing here");
		assertEquals(1, face.rows().rows().length);
		assertEquals(9f, face.rows().rows()[0].open()[0], 1e-5);
		assertEquals(11f, face.rows().rows()[0].open()[1], 1e-5);

		Glance glance = Glance.of(face, 1f, 0f, 0f, true);
		assertNotNull(glance);
		inside(face.rows(), EyePaint.eye(face.rows(), glance), "an ordinary face");
	}

	/**
	 * The same face squeezed to eight cells — which is what the game was drawing.
	 *
	 * <h2>How that happened, since the marking is right and the file is right</h2>
	 *
	 * A character keeps its own copy of the face, taken when it was dressed. One
	 * dressed before masks could keep their own size is carrying the eight-by-eight
	 * answer it was handed, and no amount of marking the costume afterwards changes
	 * what is already on the character. So the pictures came out right and the game
	 * did not, from the same code, on the same skin.
	 *
	 * Squeezed, the reported eye becomes a single cell of iris beside a single cell
	 * of white — one cell tall, and a cell here is four texels. Its opening
	 * therefore covers a whole texel row <em>below</em> the eye and two texels of
	 * cheek beside it, which is the iris sliding onto skin and skin travelling with
	 * the iris, exactly as reported.
	 *
	 * The character taking its face from the wardrobe is the fix for that. What is
	 * pinned here is the part of it that must hold anyway: on such a face nothing
	 * may go dark that is not the eye, whatever the opening happens to include.
	 */
	private static EyeRows coarse() {
		return EyeRows.of(com.mopicmp.npcstudio.entity.FaceMask.of(reported().reduce()));
	}

	@Test
	@DisplayName("even on an eye one cell tall the lash is a line, not the whole lid")
	void theLashIsNeverTheWholeLid() {
		EyeRows rows = coarse();
		assertNotNull(rows);
		assertEquals(1f, rows.texel(), 1e-5, "squeezed to eighths, a cell is a whole pixel");
		assertEquals(1, rows.rows().length, "and the eye is one row tall");

		FaceReading face = FaceReading.of(com.mopicmp.npcstudio.entity.FaceMask.of(
			reported().reduce()));
		float tall = face.eyeBottom() - face.eyeTop();

		// One cell was the answer that fixed the fine face and it is the whole eye
		// here — which is the same solid dark rectangle by a different route.
		for (int shut = 1; shut <= 10; shut++) {
			float dark = 0;
			for (EyePaint.Patch patch : EyePaint.lash(
					rows, face.eyeTop(), face.eyeBottom(), shut / 10f)) {
				dark = Math.max(dark, patch.v1() - patch.v0());
			}
			assertTrue(dark <= tall * 0.25f + 1e-4,
				"at " + (shut / 10f) + " shut the lash was " + (dark / tall) + " of the eye");
		}
	}

	@Test
	@DisplayName("a squeezed face still draws nothing outside its own opening")
	void theCoarseFaceStaysInsideItself() {
		FaceReading face = FaceReading.of(com.mopicmp.npcstudio.entity.FaceMask.of(
			reported().reduce()));
		for (boolean right : new boolean[] { true, false }) {
			EyeRows rows = right ? face.rows() : face.rows().mirrored();
			for (int aside = -10; aside <= 10; aside++) {
				Glance glance = Glance.of(face, aside / 10f, 0f, 0f, right);
				if (glance == null) continue;
				inside(rows, EyePaint.eye(rows, glance), "coarse glance " + aside);
			}
			inside(rows, EyePaint.lid(rows, face.eyeTop(), face.eyeBottom(), 1f), "coarse lid");
		}
	}

	private static EyePaint.Patch at(List<EyePaint.Patch> patches, float u, float v) {
		for (EyePaint.Patch patch : patches) {
			if (patch.u0() <= u + 1e-4 && patch.u1() > u + 1e-4
				&& patch.v0() <= v + 1e-4 && patch.v1() > v + 1e-4) {
				return patch;
			}
		}
		return null;
	}
}
