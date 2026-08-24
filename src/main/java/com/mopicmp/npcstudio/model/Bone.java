package com.mopicmp.npcstudio.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A group of boxes that move together, and may hang off another group.
 *
 * <h2>Why bones exist here at all</h2>
 *
 * A model of loose boxes can be built and can never be animated, and the whole
 * reason for owning this format rather than borrowing one is that the ship has
 * to rock. Rocking is a rotation applied to a bone; without bones the only
 * thing that can be animated is every box separately, which is not animation,
 * it is data entry.
 *
 * <h2>Parents by name</h2>
 *
 * A bone names its parent rather than holding it. Names survive a model being
 * written to a file and read back, and a tree of references does not — the
 * formats this has to speak both write the parent as a name, so holding the
 * object would mean resolving on the way in and unresolving on the way out for
 * no gain in between.
 *
 * The consequence is that a name that does not exist is possible. It is treated
 * as no parent, because a bone at the root is a mildly wrong model and a crash
 * is a broken one.
 */
public record Bone(
		String name,
		/** The bone this one hangs from, or empty when it hangs from the model. */
		String parent,
		/** Where the bone turns, in model pixels. */
		float pivotX, float pivotY, float pivotZ,
		/** Degrees about each axis, applied at the pivot. */
		float rotX, float rotY, float rotZ,
		List<Cube> cubes) {

	public Bone {
		cubes = List.copyOf(cubes);
	}

	public static Bone named(String name) {
		return new Bone(name, "", 0, 0, 0, 0, 0, 0, List.of());
	}

	public boolean rooted() {
		return parent == null || parent.isBlank();
	}

	public Bone with(List<Cube> replacement) {
		return new Bone(name, parent, pivotX, pivotY, pivotZ, rotX, rotY, rotZ, replacement);
	}

	public Bone plus(Cube cube) {
		List<Cube> grown = new ArrayList<>(cubes);
		grown.add(cube);
		return with(grown);
	}

	public Bone replacing(int index, Cube cube) {
		if (index < 0 || index >= cubes.size()) return this;
		List<Cube> changed = new ArrayList<>(cubes);
		changed.set(index, cube);
		return with(changed);
	}

	public Bone without(int index) {
		if (index < 0 || index >= cubes.size()) return this;
		List<Cube> left = new ArrayList<>(cubes);
		left.remove(index);
		return with(left);
	}

	public Bone pivotedAt(float x, float y, float z) {
		return new Bone(name, parent, x, y, z, rotX, rotY, rotZ, cubes);
	}

	public Bone turned(float x, float y, float z) {
		return new Bone(name, parent, pivotX, pivotY, pivotZ, x, y, z, cubes);
	}

	public Bone renamed(String fresh) {
		return new Bone(fresh, parent, pivotX, pivotY, pivotZ, rotX, rotY, rotZ, cubes);
	}

	public Bone under(String owner) {
		return new Bone(name, owner, pivotX, pivotY, pivotZ, rotX, rotY, rotZ, cubes);
	}
}
