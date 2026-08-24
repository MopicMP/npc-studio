package com.mopicmp.npcstudio.client.model;

import com.mopicmp.npcstudio.model.Bone;
import com.mopicmp.npcstudio.model.Cube;
import com.mopicmp.npcstudio.model.Model;

/**
 * What the modelling mode is working on.
 *
 * <h2>One document, one selection</h2>
 *
 * The same arrangement the workspace uses for the selected character, and for
 * the same reason: three panels all need to know which box is being edited, and
 * three copies of that answer is three chances to disagree.
 *
 * <h2>Every edit goes through here</h2>
 *
 * Not because it is tidy but because the model is immutable. A panel cannot
 * change a box; it can only ask for a model in which that box is different, and
 * something has to hold the result. That something also hands it to the store,
 * which is where the renderer reads from — so an edit is on screen next frame.
 */
public final class Modelling {

	private static String name = "";
	private static Model model;

	/** What is selected: a bone always, a box within it sometimes. */
	private static String bone = "";
	private static int cube = -1;

	/** Whether anything has changed since it was last written to disk. */
	private static boolean dirty;

	private Modelling() { }

	public static String name() {
		return name;
	}

	public static Model model() {
		return model;
	}

	public static boolean open() {
		return model != null;
	}

	public static boolean dirty() {
		return dirty;
	}

	public static String selectedBone() {
		return bone;
	}

	public static int selectedCube() {
		return cube;
	}

	/** The box being edited, or null when a bone is selected on its own. */
	public static Cube cube() {
		Bone holder = bone();
		if (holder == null || cube < 0 || cube >= holder.cubes().size()) return null;
		return holder.cubes().get(cube);
	}

	public static Bone bone() {
		return model == null || bone.isEmpty() ? null : model.bone(bone);
	}

	/**
	 * Everything selected, as a set, with {@link #bone} and {@link #cube} the last one.
	 *
	 * <h2>Why a set beside a single selection rather than instead of it</h2>
	 *
	 * Because the two are asked different questions. Delete, copy and moving into a
	 * folder are about everything chosen; the handles in the world, the numbers on
	 * the right and what a drag edits are about exactly one box. Every editor has
	 * both, and calls the second one the active or primary selection — the last row
	 * touched, which is the one that stays lit when the rest are merely marked.
	 *
	 * Collapsing them would mean either handles on nothing whenever two things are
	 * chosen, or a delete that takes one of five.
	 */
	public record Chosen(String bone, int cube) { }

	private static final java.util.LinkedHashSet<Chosen> marked = new java.util.LinkedHashSet<>();

	public static void select(String boneName, int cubeIndex) {
		bone = boneName == null ? "" : boneName;
		cube = cubeIndex;
		marked.clear();
		marked.add(new Chosen(bone, cube));
	}

	/** Adds one more, or takes it out again: what control-clicking a row means. */
	public static void alsoSelect(String boneName, int cubeIndex) {
		Chosen one = new Chosen(boneName == null ? "" : boneName, cubeIndex);
		if (marked.remove(one)) {
			// The last of them stays the active one unless the active one is what went.
			if (one.bone().equals(bone) && one.cube() == cube) settleActive();
			return;
		}
		marked.add(one);
		bone = one.bone();
		cube = one.cube();
	}

	/** Everything from the active row to this one, which is what shift-clicking means. */
	public static void selectThrough(java.util.List<String> bones, java.util.List<Integer> cubes,
			int from, int to) {
		if (from < 0 || to < 0) return;
		marked.clear();
		int first = Math.min(from, to);
		int last = Math.max(from, to);
		for (int at = first; at <= last && at < bones.size(); at++) {
			marked.add(new Chosen(bones.get(at), cubes.get(at)));
		}
		bone = bones.get(to);
		cube = cubes.get(to);
	}

	public static boolean isSelected(String boneName, int cubeIndex) {
		return marked.contains(new Chosen(boneName == null ? "" : boneName, cubeIndex));
	}

	public static int selectedCount() {
		return marked.size();
	}

	/** After something has gone, the active row is whatever is left, or nothing. */
	private static void settleActive() {
		Chosen last = null;
		for (Chosen one : marked) last = one;
		bone = last == null ? "" : last.bone();
		cube = last == null ? -1 : last.cube();
	}

	// ------------------------------------------------------------ documents

	public static void openModel(String which) {
		Model found = ModelStore.get(which);
		if (found == null) return;
		name = which;
		model = found;
		dirty = false;
		Bone first = found.bones().isEmpty() ? null : found.bones().get(0);
		select(first == null ? "" : first.name(), first != null && !first.cubes().isEmpty() ? 0 : -1);
	}

	public static void create(String which) {
		name = which;
		model = Model.empty(which);
		dirty = true;
		// The box, not the bone. Selecting the bone showed a panel with two rows of
		// zeroes and no way to tell that a box was what was missing.
		select("root", 0);
		ModelStore.hold(which, model);
	}

	/** Flag six is glowing, as {@code Entity.FLAG_GLOWING} has it. */
	private static final int GLOWING = 6;

	/**
	 * Lights up every placed object while the modelling window is open.
	 *
	 * Because they are easy to lose. An object is a model somebody made, put down
	 * once, and then looked for — and a white plate on a grey hillside is not
	 * something the eye finds. The outline is the game's own, set on this client
	 * only: it is a note about what is being worked on, not a property of the
	 * object, and nobody else should see it.
	 */
	public static void lightPlaced(boolean on) {
		net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
		if (client.level == null) return;
		for (net.minecraft.world.entity.Entity entity : client.level.entitiesForRendering()) {
			if (!(entity instanceof com.mopicmp.npcstudio.entity.ModelObject)) continue;
			((com.mopicmp.npcstudio.client.mixin.EntityAccessor) entity)
				.npcStudio$setSharedFlag(GLOWING, on);
		}
	}

	/** Every placed object this client can see, nearest first. */
	public static java.util.List<com.mopicmp.npcstudio.entity.ModelObject> placed() {
		net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
		java.util.List<com.mopicmp.npcstudio.entity.ModelObject> found = new java.util.ArrayList<>();
		if (client.level == null || client.player == null) return found;
		for (net.minecraft.world.entity.Entity entity : client.level.entitiesForRendering()) {
			if (entity instanceof com.mopicmp.npcstudio.entity.ModelObject object) found.add(object);
		}
		found.sort((a, b) -> Float.compare(client.player.distanceTo(a), client.player.distanceTo(b)));
		return found;
	}

	/**
	 * Whether placed objects are things to walk into.
	 *
	 * Off to begin with, and that is a retreat rather than a preference. Solidity is
	 * built and mostly right, and "mostly right" is a bad thing for a floor to be:
	 * being held in mid-air by something you cannot see stops the work, and the work
	 * is drawing. On is a button away for whoever wants to stand on a deck.
	 */
	private static boolean solid;

	public static boolean solid() {
		return solid;
	}

	public static void solid(boolean on) {
		solid = on;
		tellShape();
	}

	/**
	 * Tells the server how big every placed object is.
	 *
	 * The client is the only one that can say: the server has never seen a box. Sent
	 * on placing and after an edit settles rather than on every keystroke — a packet
	 * per nudge of a face would be a packet per frame of a drag, and what it is for
	 * is collision, which nobody is testing in the middle of a pull.
	 *
	 * <h2>Every object, not only the one being edited</h2>
	 *
	 * Because an object that was put down before any of this existed has never been
	 * measured, and an object of a model nobody has opened this session never will
	 * be. Both load with the box the entity type gives them — one block, centred on
	 * the object's own point — which is the wrong size and, since a model does not
	 * sit centred on anything, in the wrong place as well. That is a collision half
	 * a block from where the thing is drawn, on a thing you were not editing, and it
	 * would have stayed that way indefinitely.
	 */
	public static void tellShape() {
		if (!open()) return;
		String json = new com.google.gson.GsonBuilder().create().toJson(
			com.mopicmp.npcstudio.model.ModelIO.writeBlockbench(model));
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
			new com.mopicmp.npcstudio.net.NpcPayloads.ModelDocument(name, json));

		for (com.mopicmp.npcstudio.entity.ModelObject object : placed()) {
			net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
				new com.mopicmp.npcstudio.net.NpcPayloads.SolidModel(object.getId(), solid));
		}
	}

	/**
	 * Keeps every placed object's own idea of its solid parts up to date.
	 *
	 * On a client, and every client, whether or not the editor is open: the client
	 * works out where the player may walk before the server confirms it, so a client
	 * that thinks an object is one shape and a server that thinks it is another will
	 * disagree on every step near it, and disagreeing about collision is what
	 * rubber-banding is.
	 *
	 * They cannot disagree any more, because neither of them measures anything: both
	 * call {@link com.mopicmp.npcstudio.entity.ModelObject#shapeFrom} on the same
	 * document. What used to be here — a second copy of that arithmetic, on the
	 * client, whose answers were then posted to the server — is gone.
	 */
	public static void refreshColliders() {
		for (com.mopicmp.npcstudio.entity.ModelObject object : placed()) {
			Model theirs = ModelStore.get(object.model());
			if (theirs != null) object.shapeFrom(theirs);
		}
	}

	/**
	 * Takes every copy of the open model back out of the world.
	 *
	 * Called when the last box goes, because a model with no boxes draws nothing:
	 * what would be left standing is an invisible thing that still catches clicks
	 * and, if it is solid, still stops people walking. Emptying a model and having
	 * the ghost of it remain is worse than either.
	 */
	public static void unplace() {
		for (com.mopicmp.npcstudio.entity.ModelObject object : placed()) {
			if (!object.model().equals(name)) continue;
			net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
				new com.mopicmp.npcstudio.net.NpcPayloads.RemoveModel(object.getId()));
		}
	}

	/** Asks the server to put the open model where the player stands. */
	public static void place() {
		if (!open()) return;
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
			new com.mopicmp.npcstudio.net.NpcPayloads.PlaceModel(name));
	}

	public static void close() {
		name = "";
		model = null;
		dirty = false;
		select("", -1);
	}

	public static boolean save() {
		if (model == null || name.isEmpty()) return false;
		boolean written = ModelStore.save(name, model);
		if (written) dirty = false;
		return written;
	}

	// -------------------------------------------------------------- editing

	/**
	 * Takes a changed model, and makes sure it is what gets drawn.
	 *
	 * The hand-off to the store is the whole point: the renderer reads documents
	 * from there by name, so this is what puts the typed number on screen.
	 * Forgetting it is how an editor comes to show the model as it was ten edits
	 * ago.
	 */
	public static void take(Model changed) {
		if (changed == null) return;
		remember();
		model = changed;
		dirty = true;
		ModelStore.hold(name, changed);
	}

	// ---------------------------------------------------------------- undoing

	/**
	 * What the model was, and what it was before that.
	 *
	 * Cheap because the document is immutable: a step back is a reference, not a
	 * copy, and the whole stack is thirty-odd pointers to models that share almost
	 * all of their parts.
	 */
	private static final java.util.Deque<Model> past = new java.util.ArrayDeque<>();
	private static final java.util.Deque<Model> ahead = new java.util.ArrayDeque<>();

	private static final int REMEMBERED = 64;

	/**
	 * Whether a drag is in progress, and whether it has been remembered yet.
	 *
	 * A pull of a handle is one action to whoever pulled it and forty edits to this
	 * file, because the box is worked out afresh from every position of the mouse.
	 * Without this, undo would step back a pixel at a time and take forty presses to
	 * put back one drag. So the state before the drag is kept once, and the rest of
	 * the drag adds nothing.
	 */
	private static boolean dragging;
	private static boolean draggingRemembered;

	public static void gesture(boolean on) {
		dragging = on;
		if (!on) draggingRemembered = false;
	}

	private static void remember() {
		if (model == null) return;
		if (dragging) {
			if (draggingRemembered) return;
			draggingRemembered = true;
		}
		past.push(model);
		while (past.size() > REMEMBERED) past.removeLast();
		// A new edit is a new branch: whatever was undone is not coming back.
		ahead.clear();
	}

	public static boolean undo() {
		if (past.isEmpty() || model == null) return false;
		ahead.push(model);
		settle(past.pop());
		return true;
	}

	public static boolean redo() {
		if (ahead.isEmpty() || model == null) return false;
		past.push(model);
		settle(ahead.pop());
		return true;
	}

	/** Puts a remembered model back, and makes sure the selection still points at something. */
	private static void settle(Model back) {
		model = back;
		dirty = true;
		ModelStore.hold(name, back);

		Bone holder = model.bone(bone);
		if (holder == null) {
			Bone first = model.bones().isEmpty() ? null : model.bones().get(0);
			select(first == null ? "" : first.name(), -1);
			return;
		}
		if (cube >= holder.cubes().size()) select(holder.name(), holder.cubes().size() - 1);
	}

	// -------------------------------------------------------------- the clipboard

	/**
	 * One box, kept whole.
	 *
	 * A box and not a selection, because there is one selection and it is a box. The
	 * day there are several, this becomes a list and nothing else changes.
	 */
	private static final java.util.List<Cube> copied = new java.util.ArrayList<>();

	public static boolean copy() {
		java.util.List<Cube> taken = markedCubes();
		if (taken.isEmpty()) return false;
		copied.clear();
		copied.addAll(taken);
		return true;
	}

	/** Which boxes are marked, as places in the document rather than as boxes. */
	public static java.util.List<Chosen> markedBoxes() {
		java.util.List<Chosen> found = new java.util.ArrayList<>();
		if (model == null) return found;
		for (Chosen one : marked) {
			Bone holder = model.bone(one.bone());
			if (holder != null && one.cube() >= 0 && one.cube() < holder.cubes().size()) {
				found.add(one);
			}
		}
		return found;
	}

	/**
	 * The middle of everything selected, in the coordinates the boxes are written in.
	 *
	 * What a group turns about. Turning each box about its own middle turns each box
	 * — a cone comes apart into two hundred separately spinning bricks, which is
	 * exactly what it did, and is a correct reading of "turn every selected box" that
	 * nobody ever means. Round one shared point they move as one thing.
	 *
	 * Null when nothing is selected, so a caller can tell that from a group whose
	 * middle happens to be the origin.
	 */
	public static float[] middleOfSelection() {
		java.util.List<Chosen> where = markedBoxes();
		if (where.isEmpty()) return null;

		float[] box = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
			-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
		for (Chosen one : where) {
			Cube cube = cubeAt(one);
			box[0] = Math.min(box[0], cube.fromX());
			box[1] = Math.min(box[1], cube.fromY());
			box[2] = Math.min(box[2], cube.fromZ());
			box[3] = Math.max(box[3], cube.toX());
			box[4] = Math.max(box[4], cube.toY());
			box[5] = Math.max(box[5], cube.toZ());
		}
		return new float[] {
			(box[0] + box[3]) / 2, (box[1] + box[4]) / 2, (box[2] + box[5]) / 2 };
	}

	public static Cube cubeAt(Chosen where) {
		if (model == null) return null;
		Bone holder = model.bone(where.bone());
		if (holder == null || where.cube() < 0 || where.cube() >= holder.cubes().size()) return null;
		return holder.cubes().get(where.cube());
	}

	/**
	 * Puts a whole batch of changed boxes back, in one edit.
	 *
	 * One edit rather than a loop of them, because a drag of four walls is one thing
	 * somebody did and should be one thing to undo. It is also the difference
	 * between rebuilding the document once per frame and four times.
	 */
	public static void replace(java.util.List<Chosen> where, java.util.List<Cube> what) {
		if (model == null || where.size() != what.size() || where.isEmpty()) return;
		Model changed = model;
		for (int at = 0; at < where.size(); at++) {
			Bone holder = changed.bone(where.get(at).bone());
			if (holder == null || where.get(at).cube() >= holder.cubes().size()) continue;
			changed = changed.replacing(holder.replacing(where.get(at).cube(), what.get(at)));
		}
		take(changed);
	}

	/** The boxes that are marked, in the order the document holds them. */
	private static java.util.List<Cube> markedCubes() {
		java.util.List<Cube> found = new java.util.ArrayList<>();
		if (model == null) return found;
		for (Chosen one : marked) {
			Bone holder = model.bone(one.bone());
			if (holder == null || one.cube() < 0 || one.cube() >= holder.cubes().size()) continue;
			found.add(holder.cubes().get(one.cube()));
		}
		return found;
	}

	public static boolean paste() {
		if (copied.isEmpty() || model == null) return false;
		Bone holder = bone() == null ? model.bones().get(0) : bone();

		// Beside whatever is selected rather than on top of the original, because two
		// boxes in one place look like one box and the second is then impossible to
		// pick up. The whole batch moves together, so a copied pair keeps its shape.
		Cube chosen = cube();
		float shift = chosen == null ? 0 : chosen.toX() + 1 - copied.get(0).fromX();

		Model changed = model;
		Bone into = changed.bone(holder.name());
		for (Cube one : copied) {
			into = into.plus(one.movedTo(one.fromX() + shift, one.fromY(), one.fromZ()));
		}
		take(changed.replacing(into));

		Bone after = model.bone(holder.name());
		marked.clear();
		for (int at = after.cubes().size() - copied.size(); at < after.cubes().size(); at++) {
			marked.add(new Chosen(after.name(), at));
		}
		settleActive();
		return true;
	}

	/**
	 * Copy and paste in one, which is what duplicating is.
	 *
	 * <h2>A folder duplicates as a folder</h2>
	 *
	 * When what is selected is a whole folder, the copy is a new folder beside it,
	 * not two hundred more boxes poured into the one that is already there. That was
	 * the behaviour and it is wrong in the way that is hardest to undo: a cone
	 * duplicated into its own folder is a folder holding two cones, and the two are
	 * now indistinguishable in the list, so telling them apart again means picking
	 * boxes out by eye.
	 *
	 * A copy of a thing goes next to the thing, at the same level as it.
	 */
	public static boolean duplicate() {
		String folder = onlyFolder();
		if (folder != null) return duplicateFolder(folder);
		return duplicateBoxes();
	}

	/**
	 * The one folder this selection is, or null.
	 *
	 * "Is a folder" means the folder itself is marked and every marked box lives in
	 * it or under it — which is exactly what clicking a folder leaves behind, and is
	 * not what picking a few boxes out of one leaves behind.
	 */
	private static String onlyFolder() {
		if (model == null) return null;
		String found = null;
		for (Chosen one : marked) {
			if (one.cube() >= 0) continue;
			if (found != null) return null;
			found = one.bone();
		}
		if (found == null) return null;

		java.util.List<Chosen> inside = new java.util.ArrayList<>();
		gather(found, inside);
		for (Chosen one : marked) {
			if (one.cube() >= 0 && !inside.contains(one)) return null;
		}
		return found;
	}

	private static boolean duplicateFolder(String folder) {
		Bone original = model.bone(folder);
		if (original == null) return false;

		Bone made = Bone.named(model.freshName(folder)).under(original.parent());
		take(model.plus(made));

		String name = made.name();
		Model changed = model;
		Bone into = changed.bone(name);
		for (Cube one : original.cubes()) into = into.plus(one);
		take(changed.replacing(into));

		selectFolder(name);
		return true;
	}

	private static boolean duplicateBoxes() {
		java.util.List<Cube> mine = markedCubes();
		if (mine.isEmpty()) return false;
		java.util.List<Cube> kept = new java.util.ArrayList<>(copied);
		copied.clear();
		copied.addAll(mine);
		boolean did = paste();
		copied.clear();
		copied.addAll(kept.isEmpty() ? mine : kept);
		return did;
	}

	/**
	 * Moves everything marked into a folder.
	 *
	 * Boxes are taken out of wherever they were and added at the end of the folder,
	 * and folders are re-hung under it. Highest index first within each folder,
	 * because taking box two out of a folder makes box three into box two — remove
	 * upwards and the numbers of everything still to go are wrong.
	 */
	public static boolean moveInto(String folder) {
		if (model == null || model.bone(folder) == null) return false;

		java.util.List<Cube> moving = new java.util.ArrayList<>();
		java.util.List<Chosen> boxes = new java.util.ArrayList<>();
		for (Chosen one : marked) {
			// A box whose own folder is going travels inside it. Taking it out and
			// putting it in the target separately would empty the folder on the way
			// past and leave a shell behind.
			if (one.cube() >= 0 && !insideMarkedFolder(one.bone())) boxes.add(one);
			else if (one.cube() < 0
				&& !one.bone().equals(folder) && !model.wouldLoop(one.bone(), folder)) {
				Bone moved = model.bone(one.bone());
				if (moved != null) take(model.replacing(moved.under(folder)));
			}
		}

		boxes.sort((a, b) -> Integer.compare(b.cube(), a.cube()));
		Model changed = model;
		for (Chosen one : boxes) {
			Bone from = changed.bone(one.bone());
			if (from == null || one.cube() >= from.cubes().size()) continue;
			if (one.bone().equals(folder)) continue;
			moving.add(from.cubes().get(one.cube()));
			changed = changed.replacing(from.without(one.cube()));
		}
		if (moving.isEmpty()) return true;

		Bone into = changed.bone(folder);
		for (Cube one : moving) into = into.plus(one);
		take(changed.replacing(into));

		Bone after = model.bone(folder);
		marked.clear();
		for (int at = after.cubes().size() - moving.size(); at < after.cubes().size(); at++) {
			marked.add(new Chosen(folder, at));
		}
		settleActive();
		return true;
	}

	/** Replaces the selected box with a changed one. */
	public static void changeCube(Cube changed) {
		Bone holder = bone();
		if (holder == null || changed == null || cube < 0) return;
		take(model.replacing(holder.replacing(cube, changed)));
	}

	public static void changeBone(Bone changed) {
		if (model == null || changed == null) return;
		take(model.replacing(changed));
	}

	/**
	 * Dresses everything selected in a block, or strips it when given nothing.
	 *
	 * Everything, not the active box. A sphere is two hundred boxes and choosing a
	 * material for one of them is choosing a material for one two-hundredth of a
	 * sphere — which is what it did, and what made the material picker look broken
	 * rather than narrow.
	 */
	public static void wear(String block) {
		java.util.List<Chosen> where = markedBoxes();
		// A folder that is marked means everything in it, and everything in the
		// folders under it. Choosing a material with a folder picked out and having
		// nothing happen is the same complaint as choosing one for a sphere and
		// having one box of two hundred change, arrived at from the other side.
		for (Chosen one : marked) {
			if (one.cube() >= 0) continue;
			gather(one.bone(), where);
		}
		if (where.isEmpty()) return;

		String which = block == null ? "" : block;
		java.util.List<Cube> dressed = new java.util.ArrayList<>(where.size());
		for (Chosen one : where) dressed.add(cubeAt(one).wearing(which));
		replace(where, dressed);
	}

	/** Every box in a folder and in the folders under it, added to a list. */
	private static void gather(String folder, java.util.List<Chosen> into) {
		Bone holder = model == null ? null : model.bone(folder);
		if (holder == null) return;
		for (int at = 0; at < holder.cubes().size(); at++) {
			Chosen one = new Chosen(folder, at);
			if (!into.contains(one)) into.add(one);
		}
		for (Bone child : model.childrenOf(folder)) gather(child.name(), into);
	}

	/**
	 * A folder and everything in it, which is what clicking a folder means.
	 *
	 * The folder itself is marked as well as its contents, and both are needed. The
	 * contents are what a material, a drag and a turn act on; the folder is what a
	 * delete and a move act on. Marking only the folder made choosing it do nothing
	 * visible; marking only the contents would leave the folder behind, empty, after
	 * a delete of everything in it.
	 */
	public static void selectFolder(String folder) {
		if (model == null || model.bone(folder) == null) return;
		marked.clear();
		markFolder(folder);
		settleActive();
	}

	private static void markFolder(String folder) {
		Bone holder = model.bone(folder);
		if (holder == null) return;
		marked.add(new Chosen(folder, -1));
		for (int at = 0; at < holder.cubes().size(); at++) marked.add(new Chosen(folder, at));
		for (Bone child : model.childrenOf(folder)) markFolder(child.name());
	}

	/** Whether a box is inside a folder that is itself marked, and so travels with it. */
	private static boolean insideMarkedFolder(String bone) {
		return marked.contains(new Chosen(bone, -1));
	}

	/**
	 * Add ▸ Cube, and it never fails.
	 *
	 * <h2>Why this does four things instead of one</h2>
	 *
	 * Because the four things were four steps and nobody found the order. It used
	 * to be: type a name, press create, press place, then find the add-cube button
	 * in another panel — and any of those missed left a button that did nothing
	 * and said nothing. The proof is that after all of it the world contained no
	 * objects at all.
	 *
	 * So this makes the model if there is none, names it itself, puts it in the
	 * world if it is not there, adds the box, and selects it. One action, one
	 * visible result, which is what Add ▸ Cube means everywhere else.
	 */
	public static void addCube() {
		boolean fresh = !open();
		if (fresh) create(freeName());

		Bone holder = bone() == null ? model.bones().get(0) : bone();
		// A block by default, because an untextured box is a white shape somebody
		// has to go and give a material before it looks like anything.
		Cube box = beside(cube()).wearing("minecraft:oak_planks");
		take(model.replacing(holder.plus(box)));
		select(holder.name(), model.bone(holder.name()).cubes().size() - 1);

		// Into the world straight away when the model is new, or when the one that
		// is open is not standing anywhere. A box that exists only in a panel is the
		// thing that was wrong with all of this.
		if (fresh || nowhere()) place();
	}

	/**
	 * Where a new box goes: next to the one that was selected, or at the origin.
	 *
	 * Not always at the origin, which is what it used to be. Every new box landing
	 * in the same place is a stack of boxes that looks like one box, and a list of
	 * entries that all say the same thing — which is precisely the "everything goes
	 * into one item" that was seen. Beside the last one, they are separate things
	 * you can see and tell apart.
	 */
	private static Cube beside(Cube last) {
		// A whole block, upright, every time. It used to take the size and the swell
		// of whatever was selected, on the theory that a row of planks wants planks —
		// and that reads as the new box being a copy of the old one, which is a
		// different action and has its own key now. Add makes a box; duplicate copies
		// one.
		if (last == null) return new Cube(0, 0, 0, 16, 16, 16, 0, 0, 0, 0, 0, 0, 0, 0, 0, "");
		float left = last.toX() + 1;
		return new Cube(left, last.fromY(), last.fromZ(),
			left + 16, last.fromY() + 16, last.fromZ() + 16,
			0, 0, 0, 0, 0, 0, 0, 0, 0, "");
	}

	/** Whether the open model has no copy standing in the world. */
	private static boolean nowhere() {
		for (com.mopicmp.npcstudio.entity.ModelObject object : placed()) {
			if (object.model().equals(name)) return false;
		}
		return true;
	}

	/**
	 * Adds a shape made of boxes, beside whatever is selected.
	 *
	 * The same act as adding one box, and it goes through the same door: a model if
	 * there is none, in the world if it is not there, and the new boxes selected so
	 * the handles land on what was just made. A ring of forty boxes that arrives
	 * unselected is forty boxes to find in a list.
	 */
	public static void addShape(String called, java.util.List<Cube> shape) {
		if (shape.isEmpty()) return;
		boolean fresh = !open();
		if (fresh) create(freeName());

		// Into a folder of its own, always. A sphere is two hundred boxes, and two
		// hundred boxes loose in the list is a list nobody can find anything in ever
		// again — including the sphere. In a folder it is one line that opens, it can
		// be moved, deleted and turned as one thing, and the boxes inside it are still
		// boxes for whoever wants one of them.
		Bone under = bone() == null ? model.bones().get(0) : bone();
		Bone folder = Bone.named(model.freshName(called)).under(under.name());
		take(model.plus(folder));

		String name = folder.name();
		// Named boxes inside a folder start where the folder does. There is no need to
		// stand them beside anything: a folder is the thing that gets moved.
		Model changed = model;
		Bone into = changed.bone(name);
		for (Cube one : shape) into = into.plus(one);
		take(changed.replacing(into));

		selectFolder(name);
		if (fresh || nowhere()) place();
	}

	/**
	 * Turns everything selected inside out along one axis.
	 *
	 * Mirrored about the middle of the selection, not about the model's origin. The
	 * origin is where the numbers happen to start; the middle of what is selected is
	 * where somebody looking at it thinks the mirror is, and a flip that sends a
	 * wall to the other side of the ship is not a flip anybody asked for.
	 *
	 * Turns about the other two axes are negated, which is what makes it a mirror
	 * rather than a move: a plank leaning left comes back leaning right.
	 */
	public static boolean mirror(int axis) {
		java.util.List<Chosen> where = markedBoxes();
		if (where.isEmpty()) return false;

		float low = Float.MAX_VALUE;
		float high = -Float.MAX_VALUE;
		for (Chosen one : where) {
			Cube box = cubeAt(one);
			low = Math.min(low, along(box, axis, false));
			high = Math.max(high, along(box, axis, true));
		}
		float middle = (low + high) / 2;

		java.util.List<Cube> flipped = new java.util.ArrayList<>();
		for (Chosen one : where) flipped.add(flip(cubeAt(one), axis, middle));
		replace(where, flipped);
		return true;
	}

	private static float along(Cube box, int axis, boolean far) {
		return switch (axis) {
			case 0 -> far ? box.toX() : box.fromX();
			case 1 -> far ? box.toY() : box.fromY();
			default -> far ? box.toZ() : box.fromZ();
		};
	}

	private static Cube flip(Cube box, int axis, float middle) {
		float x0 = box.fromX();
		float y0 = box.fromY();
		float z0 = box.fromZ();
		float x1 = box.toX();
		float y1 = box.toY();
		float z1 = box.toZ();

		Cube turned = switch (axis) {
			case 0 -> box.corners(2 * middle - x1, y0, z0, 2 * middle - x0, y1, z1)
				.turned(box.rotX(), -box.rotY(), -box.rotZ())
				.pivotedAt(2 * middle - box.pivotX(), box.pivotY(), box.pivotZ());
			case 1 -> box.corners(x0, 2 * middle - y1, z0, x1, 2 * middle - y0, z1)
				.turned(-box.rotX(), box.rotY(), -box.rotZ())
				.pivotedAt(box.pivotX(), 2 * middle - box.pivotY(), box.pivotZ());
			default -> box.corners(x0, y0, 2 * middle - z1, x1, y1, 2 * middle - z0)
				.turned(-box.rotX(), -box.rotY(), box.rotZ())
				.pivotedAt(box.pivotX(), box.pivotY(), 2 * middle - box.pivotZ());
		};
		return turned;
	}

	/** A name nothing on disk is using, so nobody has to invent one. */
	private static String freeName() {
		java.util.List<String> taken = ModelStore.names();
		for (int n = 1; ; n++) {
			String candidate = "model" + n;
			if (!taken.contains(candidate)) return candidate;
		}
	}

	public static void addBone() {
		if (model == null) return;
		// Under whatever is selected, because a bone made while looking at an arm is
		// almost always a part of that arm. Made at the root it would have to be
		// dragged there, and re-parenting is the fiddliest thing in the panel.
		Bone parent = bone();
		Bone fresh = Bone.named(model.freshName("bone"))
			.under(parent == null ? "" : parent.name());
		take(model.plus(fresh));
		select(fresh.name(), -1);
	}

	/**
	 * Removes everything selected: the boxes, and the folders that were marked alone.
	 *
	 * Boxes go highest index first within each folder. Taking box two out makes box
	 * three into box two, so removing upwards would leave every number still to come
	 * pointing at the wrong thing — a delete of three that takes two of them and
	 * something else.
	 */
	public static void removeSelected() {
		Bone holder = bone();
		if (holder == null) return;

		java.util.List<Chosen> boxes = new java.util.ArrayList<>();
		java.util.List<String> folders = new java.util.ArrayList<>();
		for (Chosen one : marked) {
			if (one.cube() >= 0) boxes.add(one);
			else folders.add(one.bone());
		}

		if (!boxes.isEmpty()) {
			boxes.sort((a, b) -> Integer.compare(b.cube(), a.cube()));
			Model changed = model;
			for (Chosen one : boxes) {
				Bone from = changed.bone(one.bone());
				if (from == null || one.cube() >= from.cubes().size()) continue;
				changed = changed.replacing(from.without(one.cube()));
			}
			take(changed);

			// The one before whatever went, so there is still something under the
			// handles. Landing on nothing after every delete means reaching for the
			// list again to carry on, which is a step nobody asked for.
			Bone left = model.bone(holder.name());
			select(holder.name(), left == null ? -1 : Math.min(cube, left.cubes().size() - 1));
			if (model.cubeCount() == 0) unplace();
			if (folders.isEmpty()) return;
		}

		if (folders.isEmpty()) return;

		for (String name : folders) {
			// The last folder stays. A model with none has nowhere to put a box, so the
			// delete would be followed immediately by having to make one.
			if (model.bones().size() <= 1) break;
			if (model.bone(name) != null) take(model.without(name));
		}
		select(model.bones().isEmpty() ? "" : model.bones().get(0).name(), -1);
		if (model.cubeCount() == 0) unplace();
	}
}
