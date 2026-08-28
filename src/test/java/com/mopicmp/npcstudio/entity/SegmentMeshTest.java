package com.mopicmp.npcstudio.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a generated limb owes the model it came from.
 *
 * The first of these is the one that matters. A generator that cannot give back
 * exactly what it was handed has invented something, and everything invented in
 * this corner of the project so far has had to be taken out again.
 */
class SegmentMeshTest {

	/** A vanilla arm: four across, twelve long, four through, with a plausible skin. */
	private static SegmentMesh.Source arm() {
		float halfX = 2, halfZ = 2, top = 0, bottom = 12;
		List<SegmentMesh.Quad> faces = new ArrayList<>();
		// The four flanks, each with its own column of the skin and the same rows.
		faces.add(flank(-halfX, halfX, -halfZ, -halfZ, top, bottom, 0.10f, 0.16f, 0.20f, 0.44f));
		faces.add(flank(halfX, -halfX, halfZ, halfZ, top, bottom, 0.22f, 0.28f, 0.20f, 0.44f));
		faces.add(flank(-halfX, -halfX, halfZ, -halfZ, top, bottom, 0.04f, 0.10f, 0.20f, 0.44f));
		faces.add(flank(halfX, halfX, -halfZ, halfZ, top, bottom, 0.16f, 0.22f, 0.20f, 0.44f));
		faces.add(cap(halfX, halfZ, top, 0.10f, 0.16f, 0.14f, 0.20f));
		faces.add(cap(halfX, halfZ, bottom, 0.16f, 0.22f, 0.14f, 0.20f));
		return new SegmentMesh.Source(faces, top, bottom, 0, 0);
	}

	private static SegmentMesh.Quad flank(float x0, float x1, float z0, float z1,
			float top, float bottom, float u0, float u1, float v0, float v1) {
		return new SegmentMesh.Quad(
			new SegmentMesh.Corner(x0, top, z0, u0, v0),
			new SegmentMesh.Corner(x1, top, z1, u1, v0),
			new SegmentMesh.Corner(x1, bottom, z1, u1, v1),
			new SegmentMesh.Corner(x0, bottom, z0, u0, v1));
	}

	private static SegmentMesh.Quad cap(float halfX, float halfZ, float y,
			float u0, float u1, float v0, float v1) {
		return new SegmentMesh.Quad(
			new SegmentMesh.Corner(-halfX, y, -halfZ, u0, v0),
			new SegmentMesh.Corner(halfX, y, -halfZ, u1, v0),
			new SegmentMesh.Corner(halfX, y, halfZ, u1, v1),
			new SegmentMesh.Corner(-halfX, y, halfZ, u0, v1));
	}

	@Test
	@DisplayName("one segment with the model's own numbers gives the model back")
	void vanillaComesBackUntouched() {
		SegmentMesh.Source source = arm();
		List<SegmentMesh.Quad> out = SegmentMesh.build(source,
			List.of(new SegmentMesh.Segment(0, 12, 0, 2, 0, 2)), null);

		assertEquals(source.faces().size(), out.size(), "a face went missing or was invented");
		for (int i = 0; i < out.size(); i++) {
			SegmentMesh.Corner[] was = source.faces().get(i).corners();
			SegmentMesh.Corner[] now = out.get(i).corners();
			for (int c = 0; c < 4; c++) {
				assertEquals(was[c].x(), now[c].x(), 1e-4f, "x, face " + i + " corner " + c);
				assertEquals(was[c].y(), now[c].y(), 1e-4f, "y, face " + i + " corner " + c);
				assertEquals(was[c].z(), now[c].z(), 1e-4f, "z, face " + i + " corner " + c);
				assertEquals(was[c].u(), now[c].u(), 1e-4f, "u, face " + i + " corner " + c);
				assertEquals(was[c].v(), now[c].v(), 1e-4f, "v, face " + i + " corner " + c);
			}
		}
	}

	@Test
	@DisplayName("two segments of the same size are the same limb, cut in half")
	void cuttingChangesNothingByItself() {
		SegmentMesh.Source source = arm();
		List<SegmentMesh.Quad> out = SegmentMesh.build(source, List.of(
			new SegmentMesh.Segment(0, 6, 0, 2, 0, 2),
			new SegmentMesh.Segment(6, 12, 0, 2, 0, 2)), null);

		// Four flanks twice over, and one cap at each end of the limb — never at the
		// join, which is inside the arm.
		assertEquals(10, out.size(), "the join was capped, or an end was not");

		float lowest = Float.MAX_VALUE, highest = -Float.MAX_VALUE;
		for (SegmentMesh.Quad quad : out) {
			for (SegmentMesh.Corner corner : quad.corners()) {
				lowest = Math.min(lowest, corner.y());
				highest = Math.max(highest, corner.y());
				assertEquals(2f, Math.abs(corner.x()), 1e-4f, "the limb changed width");
			}
		}
		assertEquals(0f, lowest, 1e-4f);
		assertEquals(12f, highest, 1e-4f);
	}

	@Test
	@DisplayName("the skin is cut where the limb is, so the two halves still line up")
	void theTextureFollowsTheCut() {
		SegmentMesh.Source source = arm();
		List<SegmentMesh.Quad> out = SegmentMesh.build(source, List.of(
			new SegmentMesh.Segment(0, 6, 0, 2, 0, 2),
			new SegmentMesh.Segment(6, 12, 0, 2, 0, 2)), null);

		// The flank of the upper segment ends on the same texture row the lower one
		// starts on: a seam in the geometry that is not a seam in the picture.
		float upperBottom = -Float.MAX_VALUE, lowerTop = Float.MAX_VALUE;
		for (SegmentMesh.Quad quad : out) {
			for (SegmentMesh.Corner corner : quad.corners()) {
				if (Math.abs(corner.y() - 6f) > 1e-4f) continue;
				upperBottom = Math.max(upperBottom, corner.v());
				lowerTop = Math.min(lowerTop, corner.v());
			}
		}
		assertEquals(upperBottom, lowerTop, 1e-4f, "the skin jumps at the join");
		// And it is the middle row of the arm's own patch, not somebody else's.
		assertEquals(0.32f, upperBottom, 1e-4f);
	}

	@Test
	@DisplayName("a thinner second segment is a calf, not a taper")
	void segmentsMayDiffer() {
		SegmentMesh.Source source = arm();
		List<SegmentMesh.Quad> out = SegmentMesh.build(source, List.of(
			new SegmentMesh.Segment(0, 7, 0, 2, 0, 2),
			new SegmentMesh.Segment(7, 14, 0, 1.5f, 0, 1.5f)), null);

		boolean wide = false, narrow = false;
		for (SegmentMesh.Quad quad : out) {
			for (SegmentMesh.Corner corner : quad.corners()) {
				if (Math.abs(Math.abs(corner.x()) - 2f) < 1e-4f) wide = true;
				if (Math.abs(Math.abs(corner.x()) - 1.5f) < 1e-4f) narrow = true;
			}
		}
		assertTrue(wide && narrow, "the two segments came out the same width");

		// And the limb is now longer than the box it came from, because length is a
		// property of the segments. A tall character is tall; it does not borrow the
		// height from its torso.
		float lowest = Float.MAX_VALUE, highest = -Float.MAX_VALUE;
		for (SegmentMesh.Quad quad : out) {
			for (SegmentMesh.Corner corner : quad.corners()) {
				lowest = Math.min(lowest, corner.y());
				highest = Math.max(highest, corner.y());
			}
		}
		assertEquals(14f, highest - lowest, 1e-4f);
	}

	@Test
	@DisplayName("a step between segments is covered, and covered from the flank")
	void theStepIsNotAHole() {
		SegmentMesh.Source source = arm();
		List<SegmentMesh.Quad> out = SegmentMesh.build(source, List.of(
			new SegmentMesh.Segment(0, 6, 0, 2, 0, 2),
			new SegmentMesh.Segment(6, 12, 0, 1.5f, 0, 1.5f)), null);

		// Every quad lying flat at the join. Without these, looking up at a knee sees
		// through the thigh, because the inside of a box is not drawn.
		List<SegmentMesh.Quad> ring = new ArrayList<>();
		for (SegmentMesh.Quad quad : out) {
			boolean flat = true;
			for (SegmentMesh.Corner corner : quad.corners()) {
				if (Math.abs(corner.y() - 6f) > 1e-4f) flat = false;
			}
			if (flat) ring.add(quad);
		}
		assertTrue(!ring.isEmpty(), "the step between the two segments was left open");

		// The frame reaches from the narrow edge out to the wide one, all the way
		// round: four strips, and between them they cover the half pixel that steps.
		float reach = 0;
		for (SegmentMesh.Quad quad : ring) {
			for (SegmentMesh.Corner corner : quad.corners()) {
				reach = Math.max(reach, Math.abs(corner.x()));
				// And the texture is the flank's, not the end's. The arm's ends live at
				// v 0.14 to 0.20 and its flanks at 0.20 to 0.44; a ring reading from an
				// end would be transparent on most skins, which is a hole with extra
				// steps.
				assertTrue(corner.v() >= 0.20f - 1e-4f,
					"the ring is reading from an end face, at v " + corner.v());
			}
		}
		assertEquals(2f, reach, 1e-4f, "the ring stops short of the wider segment");
	}

	@Test
	@DisplayName("segments that do not step leave no ring behind")
	void nothingIsAddedWhereNothingSteps() {
		SegmentMesh.Source source = arm();
		// The same width all the way down, cut in three. There is no step anywhere,
		// so there is nothing to cover — and covering it anyway would put a surface
		// inside the limb and a duplicated row of texels on it.
		List<SegmentMesh.Quad> out = SegmentMesh.build(source, List.of(
			new SegmentMesh.Segment(0, 4, 0, 2, 0, 2),
			new SegmentMesh.Segment(4, 8, 0, 2, 0, 2),
			new SegmentMesh.Segment(8, 12, 0, 2, 0, 2)), null);

		for (SegmentMesh.Quad quad : out) {
			boolean flat = true;
			for (SegmentMesh.Corner corner : quad.corners()) {
				if (Math.abs(corner.y() - quad.a().y()) > 1e-4f) flat = false;
			}
			if (!flat) continue;
			assertTrue(Math.abs(quad.a().y()) < 1e-4f || Math.abs(quad.a().y() - 12f) < 1e-4f,
				"a flat face turned up at y " + quad.a().y() + ", which is inside the limb");
		}
	}

	@Test
	@DisplayName("a ring faces out of the limb, not into it")
	void theRingFacesTheRightWay() {
		SegmentMesh.Source source = arm();
		List<SegmentMesh.Quad> out = SegmentMesh.build(source, List.of(
			new SegmentMesh.Segment(0, 6, 0, 2, 0, 2),
			new SegmentMesh.Segment(6, 12, 0, 1.5f, 0, 1.5f)), null);

		// The underside of the thicker segment, which is what the step exposes. It has
		// to be wound the same way as the limb's own bottom end, or it is drawn from
		// the inside and shows as nothing at all.
		float end = 0;
		for (SegmentMesh.Quad quad : out) {
			if (Math.abs(quad.a().y() - 12f) < 1e-4f && flatQuad(quad)) end = normalY(quad);
		}
		assertTrue(end != 0, "the limb has no bottom end to compare against");

		boolean found = false;
		for (SegmentMesh.Quad quad : out) {
			if (Math.abs(quad.a().y() - 6f) > 1e-4f || !flatQuad(quad)) continue;
			found = true;
			assertEquals(Math.signum(end), Math.signum(normalY(quad)), 1e-4f,
				"a ring at the join is wound the other way from the limb's own end");
		}
		assertTrue(found, "no ring to check");
	}

	private static boolean flatQuad(SegmentMesh.Quad quad) {
		for (SegmentMesh.Corner corner : quad.corners()) {
			if (Math.abs(corner.y() - quad.a().y()) > 1e-4f) return false;
		}
		return true;
	}

	/** The y of a quad's normal, whichever way round it is wound. */
	private static float normalY(SegmentMesh.Quad quad) {
		SegmentMesh.Corner a = quad.a(), b = quad.b(), c = quad.c();
		return (b.z() - a.z()) * (c.x() - a.x()) - (b.x() - a.x()) * (c.z() - a.z());
	}

	@Test
	@DisplayName("shares decide how much skin a segment gets, not its length")
	void sharesAreToldRatherThanMeasured() {
		SegmentMesh.Source source = arm();
		// A short calf that still owns half the texture: a boot drawn on the lower
		// half of the skin has to stay on the lower half of the leg however short the
		// leg is made.
		List<SegmentMesh.Quad> out = SegmentMesh.build(source, List.of(
			new SegmentMesh.Segment(0, 9, 0, 2, 0, 2),
			new SegmentMesh.Segment(9, 12, 0, 2, 0, 2)), new float[] { 1f, 1f });

		float atJoin = Float.NaN;
		for (SegmentMesh.Quad quad : out) {
			for (SegmentMesh.Corner corner : quad.corners()) {
				if (Math.abs(corner.y() - 9f) < 1e-4f) atJoin = corner.v();
			}
		}
		assertEquals(0.32f, atJoin, 1e-4f, "the join did not take half the texture");
	}
}
