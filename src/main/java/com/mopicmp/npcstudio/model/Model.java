package com.mopicmp.npcstudio.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A model: a texture size and some bones.
 *
 * <h2>Immutable, and edited by replacement</h2>
 *
 * Every change makes a new model. That is the same bargain the dialogue editor
 * struck and for the same reason: an undo history is free when the old value is
 * still a value, and expensive when it has to be reconstructed from a diff.
 * Models here are tens of boxes, not millions of triangles, so copying one is
 * not a cost worth designing around.
 *
 * <h2>Why not just use Minecraft's own model classes</h2>
 *
 * Because they are built to be drawn, not to be edited. {@code ModelPart} is
 * assembled once from a definition and then has no way to say "this box is now
 * one pixel wider" — the definition it came from is gone. An editor needs the
 * document, and the drawing is made from the document every time it changes.
 */
public record Model(String name, int textureWidth, int textureHeight, List<Bone> bones) {

	/** What a texture is until somebody says otherwise. Blockbench's own default. */
	public static final int DEFAULT_TEXTURE = 64;

	public Model {
		bones = List.copyOf(bones);
		textureWidth = Math.max(1, textureWidth);
		textureHeight = Math.max(1, textureHeight);
	}

	/** A new model: one bone, nothing in it. Add puts the first box there. */
	public static Model empty(String name) {
		return new Model(name, DEFAULT_TEXTURE, DEFAULT_TEXTURE, List.of(Bone.named("root")));
	}

	public int indexOf(String bone) {
		for (int i = 0; i < bones.size(); i++) {
			if (bones.get(i).name().equals(bone)) return i;
		}
		return -1;
	}

	public Bone bone(String name) {
		int at = indexOf(name);
		return at < 0 ? null : bones.get(at);
	}

	public Model with(List<Bone> replacement) {
		return new Model(name, textureWidth, textureHeight, replacement);
	}

	public Model replacing(int index, Bone bone) {
		if (index < 0 || index >= bones.size()) return this;
		List<Bone> changed = new ArrayList<>(bones);
		changed.set(index, bone);
		return with(changed);
	}

	public Model replacing(Bone bone) {
		return replacing(indexOf(bone.name()), bone);
	}

	/**
	 * Adds a bone under a name nothing else is using.
	 *
	 * Renamed rather than refused, because a duplicate name is not something
	 * anybody means and stopping to say so is a dialog box in the way of work.
	 * Two bones with one name is worse than either: a parent reference then means
	 * two different things.
	 */
	public Model plus(Bone bone) {
		List<Bone> grown = new ArrayList<>(bones);
		grown.add(indexOf(bone.name()) < 0 ? bone : bone.renamed(freshName(bone.name())));
		return with(grown);
	}

	/**
	 * Removes a bone, and everything that hung off it.
	 *
	 * Children go too. Leaving them behind would make them roots, which silently
	 * moves them: a hand whose arm has gone does not stay where the hand was, it
	 * springs back to the middle of the model.
	 */
	public Model without(String bone) {
		Set<String> going = new LinkedHashSet<>();
		going.add(bone);
		boolean grew = true;
		while (grew) {
			grew = false;
			for (Bone one : bones) {
				if (!going.contains(one.name()) && going.contains(one.parent())) {
					going.add(one.name());
					grew = true;
				}
			}
		}
		return with(bones.stream().filter(one -> !going.contains(one.name())).toList());
	}

	public String freshName(String wanted) {
		if (indexOf(wanted) < 0) return wanted;
		for (int n = 2; ; n++) {
			String candidate = wanted + n;
			if (indexOf(candidate) < 0) return candidate;
		}
	}

	/** How many boxes there are altogether, which is the number worth showing. */
	public int cubeCount() {
		int total = 0;
		for (Bone bone : bones) total += bone.cubes().size();
		return total;
	}

	/**
	 * The bones hanging directly off a given one, in the order they were made.
	 *
	 * Worked out rather than stored, so that re-parenting is one field on one
	 * bone rather than an edit to two lists that can disagree.
	 */
	public List<Bone> childrenOf(String parent) {
		return bones.stream()
			.filter(bone -> parent.isEmpty() ? bone.rooted() : parent.equals(bone.parent()))
			.toList();
	}

	/**
	 * Whether hanging one bone off another would make a loop.
	 *
	 * Asked before every re-parent. A loop is not a wrong picture, it is a
	 * renderer that never returns, and the only cheap moment to notice is before
	 * it is made.
	 */
	public boolean wouldLoop(String bone, String newParent) {
		String walk = newParent;
		for (int steps = 0; steps < bones.size() + 1; steps++) {
			if (walk == null || walk.isEmpty()) return false;
			if (walk.equals(bone)) return true;
			Bone up = bone(walk);
			if (up == null) return false;
			walk = up.parent();
		}
		return true;
	}
}
