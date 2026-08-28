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

	/** A loft of two rings with the source's own numbers: a plain box. */
	private static List<SegmentMesh.Ring> plain() {
		return List.of(
			new SegmentMesh.Ring(0, 0f, 0, 2, 0, 2),
			new SegmentMesh.Ring(12, 1f, 0, 2, 0, 2));
	}

	@Test
	@DisplayName("two rings with the model's own numbers give the model back")
	void vanillaComesBackUntouched() {
		SegmentMesh.Source source = arm();
		List<SegmentMesh.Quad> out = SegmentMesh.build(source, plain());

		assertEquals(source.faces().size(), out.size(), "a face went missing or was invented");
		for (SegmentMesh.Quad was : source.faces()) {
			assertTrue(out.stream().anyMatch(now -> same(was, now)),
				"a face came back changed: " + was);
		}
	}

	private static boolean same(SegmentMesh.Quad a, SegmentMesh.Quad b) {
		SegmentMesh.Corner[] one = a.corners(), two = b.corners();
		for (int i = 0; i < 4; i++) {
			if (Math.abs(one[i].x() - two[i].x()) > 1e-4f) return false;
			if (Math.abs(one[i].y() - two[i].y()) > 1e-4f) return false;
			if (Math.abs(one[i].z() - two[i].z()) > 1e-4f) return false;
			if (Math.abs(one[i].u() - two[i].u()) > 1e-4f) return false;
			if (Math.abs(one[i].v() - two[i].v()) > 1e-4f) return false;
		}
		return true;
	}

	@Test
	@DisplayName("more rings of the same size are the same limb, drawn in bands")
	void moreRingsChangeNothingByThemselves() {
		SegmentMesh.Source source = arm();
		List<SegmentMesh.Quad> out = SegmentMesh.build(source, List.of(
			new SegmentMesh.Ring(0, 0f, 0, 2, 0, 2),
			new SegmentMesh.Ring(6, 0.5f, 0, 2, 0, 2),
			new SegmentMesh.Ring(12, 1f, 0, 2, 0, 2)));

		// Four flanks in two bands each, and the two ends. Nothing else: a loft has no
		// inside, so there is no face at the join to draw or to leave out.
		assertEquals(10, out.size());
		for (SegmentMesh.Quad quad : out) {
			for (SegmentMesh.Corner corner : quad.corners()) {
				assertEquals(2f, Math.abs(corner.x()) + Math.abs(corner.z()) - 2f, 1e-4f,
					"a corner left the box the rings describe");
			}
		}
	}

	@Test
	@DisplayName("the skin is cut where the ring is, so the bands still line up")
	void theTextureFollowsTheRings() {
		SegmentMesh.Source source = arm();
		List<SegmentMesh.Quad> out = SegmentMesh.build(source, List.of(
			new SegmentMesh.Ring(0, 0f, 0, 2, 0, 2),
			new SegmentMesh.Ring(6, 0.5f, 0, 2, 0, 2),
			new SegmentMesh.Ring(12, 1f, 0, 2, 0, 2)));

		// The arm's flanks run v 0.20 to 0.44, so halfway is 0.32. Every corner sitting
		// at the middle ring has to read from there — otherwise the two bands show
		// different parts of the sleeve and the join is a tear.
		for (SegmentMesh.Quad quad : out) {
			for (SegmentMesh.Corner corner : quad.corners()) {
				if (Math.abs(corner.y() - 6f) < 1e-4f) {
					assertEquals(0.32f, corner.v(), 1e-4f, "the middle ring reads the wrong row");
				}
			}
		}
	}

	@Test
	@DisplayName("a narrower ring makes a slope, not a step and a hole")
	void ringsOfDifferentSizeAreBridged() {
		SegmentMesh.Source source = arm();
		List<SegmentMesh.Quad> out = SegmentMesh.build(source, List.of(
			new SegmentMesh.Ring(0, 0f, 0, 2, 0, 2),
			new SegmentMesh.Ring(6, 0.5f, 0, 2, 0, 2),
			new SegmentMesh.Ring(12, 1f, 0, 1.5f, 0, 1.5f)));

		boolean wide = false, narrow = false, slanted = false;
		for (SegmentMesh.Quad quad : out) {
			float low = Float.MAX_VALUE, high = -Float.MAX_VALUE;
			for (SegmentMesh.Corner corner : quad.corners()) {
				if (Math.abs(Math.abs(corner.x()) - 2f) < 1e-4f) wide = true;
				if (Math.abs(Math.abs(corner.x()) - 1.5f) < 1e-4f) narrow = true;
				low = Math.min(low, Math.abs(corner.x()));
				high = Math.max(high, Math.abs(corner.x()));
			}
			// One face reaching from the wide ring to the narrow one: the slope. In the
			// stacked version this did not exist and its place was a hole, patched with
			// a strip that borrowed a row of texels from its neighbour.
			if (high - low > 0.4f) slanted = true;
		}
		assertTrue(wide && narrow, "the two rings came out the same width");
		assertTrue(slanted, "the change of width was not bridged by anything");
	}

	@Test
	@DisplayName("nothing is drawn inside the limb, at any number of rings")
	void thereAreNoFacesInside() {
		SegmentMesh.Source source = arm();
		List<SegmentMesh.Quad> out = SegmentMesh.build(source, List.of(
			new SegmentMesh.Ring(0, 0f, 0, 2, 0, 2),
			new SegmentMesh.Ring(4, 0.33f, 0, 1.5f, 0, 1.5f),
			new SegmentMesh.Ring(8, 0.66f, 0, 2, 0, 2),
			new SegmentMesh.Ring(12, 1f, 0, 1f, 0, 1f)));

		// A stack of boxes had an end face at every join, and leaving them out was what
		// opened the hole this replaced. A loft has none to leave out: the only faces
		// lying flat are the two the limb actually ends with.
		for (SegmentMesh.Quad quad : out) {
			float y = quad.a().y();
			boolean flat = true;
			for (SegmentMesh.Corner corner : quad.corners()) {
				if (Math.abs(corner.y() - y) > 1e-4f) flat = false;
			}
			if (!flat) continue;
			assertTrue(Math.abs(y) < 1e-4f || Math.abs(y - 12f) < 1e-4f,
				"a face lying flat turned up at y " + y + ", which is inside the limb");
		}
	}

	@Test
	@DisplayName("shares decide how much skin a ring reads, not where it sits")
	void sharesAreToldRatherThanMeasured() {
		SegmentMesh.Source source = arm();
		// A short calf that still reads half the texture: a boot drawn on the lower half
		// of the skin has to stay on the lower half of the leg however short it is made.
		List<SegmentMesh.Quad> out = SegmentMesh.build(source, List.of(
			new SegmentMesh.Ring(0, 0f, 0, 2, 0, 2),
			new SegmentMesh.Ring(9, 0.5f, 0, 2, 0, 2),
			new SegmentMesh.Ring(12, 1f, 0, 2, 0, 2)));

		float atJoin = Float.NaN;
		for (SegmentMesh.Quad quad : out) {
			for (SegmentMesh.Corner corner : quad.corners()) {
				if (Math.abs(corner.y() - 9f) < 1e-4f) atJoin = corner.v();
			}
		}
		assertEquals(0.32f, atJoin, 1e-4f, "the ring did not read half the texture");
	}
}
