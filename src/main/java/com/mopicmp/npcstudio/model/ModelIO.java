package com.mopicmp.npcstudio.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Reading and writing models as files.
 *
 * <h2>Which format, and why only this one so far</h2>
 *
 * Blockbench's own {@code .bbmodel}. It is what our Blockbench plugin leaves on
 * disk, it is what anybody drawing a model for this mod will already have, and
 * its numbers are the same numbers this package uses — corners, an origin and a
 * rotation, in model pixels — so reading it is transcription rather than
 * conversion.
 *
 * GeckoLib's {@code .geo.json} is deliberately not here yet. It says the same
 * things in a different frame: origin and size instead of two corners, and the
 * X axis mirrored. That mirror is exactly the kind of convention this project
 * has been bitten by before — the note in the README about two UV conventions
 * that measurement did not catch was the same shape of problem — and writing it
 * from memory of the specification, with no real file to check against, is how
 * a model comes out inside out and nobody notices for a week. It goes in when
 * there is a file to verify it with.
 */
public final class ModelIO {

	private ModelIO() { }

	// --------------------------------------------------------------- reading

	/**
	 * Reads a Blockbench project.
	 *
	 * <h3>What the outliner is for</h3>
	 *
	 * Blockbench keeps the boxes in one flat list and the tree in another, with
	 * the tree naming boxes by their identifier. So the groups have to be walked
	 * to find out which box belongs to which bone, and a box named by nobody
	 * belongs to the model itself.
	 */
	public static Model readBlockbench(JsonObject json) {
		String name = json.has("name") ? json.get("name").getAsString() : "model";
		int wide = Model.DEFAULT_TEXTURE;
		int tall = Model.DEFAULT_TEXTURE;
		if (json.has("resolution") && json.get("resolution").isJsonObject()) {
			JsonObject resolution = json.getAsJsonObject("resolution");
			wide = number(resolution, "width", Model.DEFAULT_TEXTURE);
			tall = number(resolution, "height", Model.DEFAULT_TEXTURE);
		}

		Map<String, Cube> byId = new HashMap<>();
		if (json.has("elements")) {
			for (JsonElement element : json.getAsJsonArray("elements")) {
				if (!element.isJsonObject()) continue;
				JsonObject cube = element.getAsJsonObject();
				String id = cube.has("uuid") ? cube.get("uuid").getAsString() : null;
				if (id != null) byId.put(id, cubeOf(cube));
			}
		}

		List<Bone> bones = new ArrayList<>();
		List<String> claimed = new ArrayList<>();
		if (json.has("outliner")) {
			for (JsonElement child : json.getAsJsonArray("outliner")) {
				walk(child, "", byId, bones, claimed);
			}
		}

		// Whatever the tree never mentioned. A box outside every group is a box
		// somebody drew and did not file, and losing it silently would be the
		// worst of the three ways to handle it.
		List<Cube> loose = new ArrayList<>();
		for (Map.Entry<String, Cube> entry : byId.entrySet()) {
			if (!claimed.contains(entry.getKey())) loose.add(entry.getValue());
		}
		if (!loose.isEmpty() || bones.isEmpty()) {
			bones.add(new Bone("root", "", 0, 0, 0, 0, 0, 0, loose));
		}
		return new Model(name, wide, tall, bones);
	}

	/** One node of Blockbench's tree: either a group, or the identifier of a box. */
	private static void walk(JsonElement node, String parent, Map<String, Cube> byId,
			List<Bone> into, List<String> claimed) {
		if (node.isJsonPrimitive()) return;
		if (!node.isJsonObject()) return;

		JsonObject group = node.getAsJsonObject();
		String name = group.has("name") ? group.get("name").getAsString() : "bone";
		float[] origin = triple(group, "origin");
		float[] rotation = triple(group, "rotation");

		List<Cube> mine = new ArrayList<>();
		List<JsonElement> groups = new ArrayList<>();
		if (group.has("children")) {
			for (JsonElement child : group.getAsJsonArray("children")) {
				if (child.isJsonPrimitive()) {
					String id = child.getAsString();
					Cube cube = byId.get(id);
					if (cube != null) {
						mine.add(cube);
						claimed.add(id);
					}
				} else {
					groups.add(child);
				}
			}
		}

		into.add(new Bone(name, parent, origin[0], origin[1], origin[2],
			rotation[0], rotation[1], rotation[2], mine));
		for (JsonElement child : groups) walk(child, name, byId, into, claimed);
	}

	private static Cube cubeOf(JsonObject element) {
		float[] from = triple(element, "from");
		float[] to = triple(element, "to");
		float[] origin = triple(element, "origin");
		float[] rotation = triple(element, "rotation");
		float inflate = element.has("inflate") ? element.get("inflate").getAsFloat() : 0;

		// The box UV, which is the only kind this format speaks. Blockbench also
		// has per-face UV, and a model using it cannot be said in these terms at
		// all — it is read as the box UV it does not have, which is wrong and
		// visible, rather than dropped, which is wrong and not.
		int u = 0;
		int v = 0;
		if (element.has("uv_offset") && element.get("uv_offset").isJsonArray()) {
			JsonArray offset = element.getAsJsonArray("uv_offset");
			if (offset.size() >= 2) {
				u = offset.get(0).getAsInt();
				v = offset.get(1).getAsInt();
			}
		}
		// Ours, and Blockbench keeps what it does not know, so a model that has
		// been round through Blockbench comes back still wearing its blocks.
		String block = element.has("npc_studio_block")
			? element.get("npc_studio_block").getAsString() : "";

		return new Cube(from[0], from[1], from[2], to[0], to[1], to[2],
			origin[0], origin[1], origin[2],
			rotation[0], rotation[1], rotation[2], inflate, u, v, block);
	}

	// --------------------------------------------------------------- writing

	/** Writes a Blockbench project, which is what was read. */
	public static JsonObject writeBlockbench(Model model) {
		JsonObject json = new JsonObject();
		json.addProperty("name", model.name());

		JsonObject meta = new JsonObject();
		meta.addProperty("format_version", "4.5");
		meta.addProperty("model_format", "free");
		meta.addProperty("box_uv", true);
		json.add("meta", meta);

		JsonObject resolution = new JsonObject();
		resolution.addProperty("width", model.textureWidth());
		resolution.addProperty("height", model.textureHeight());
		json.add("resolution", resolution);

		JsonArray elements = new JsonArray();
		Map<String, JsonArray> childrenOf = new HashMap<>();
		int id = 0;
		for (Bone bone : model.bones()) {
			JsonArray mine = new JsonArray();
			for (Cube cube : bone.cubes()) {
				String uuid = "cube" + id++;
				elements.add(elementOf(cube, uuid));
				mine.add(uuid);
			}
			childrenOf.put(bone.name(), mine);
		}
		json.add("elements", elements);

		JsonArray outliner = new JsonArray();
		for (Bone bone : model.bones()) {
			if (bone.rooted()) outliner.add(groupOf(model, bone, childrenOf));
		}
		json.add("outliner", outliner);
		return json;
	}

	private static JsonObject groupOf(Model model, Bone bone, Map<String, JsonArray> childrenOf) {
		JsonObject group = new JsonObject();
		group.addProperty("name", bone.name());
		group.add("origin", array(bone.pivotX(), bone.pivotY(), bone.pivotZ()));
		group.add("rotation", array(bone.rotX(), bone.rotY(), bone.rotZ()));

		JsonArray children = new JsonArray();
		JsonArray mine = childrenOf.get(bone.name());
		if (mine != null) children.addAll(mine);
		for (Bone child : model.childrenOf(bone.name())) {
			children.add(groupOf(model, child, childrenOf));
		}
		group.add("children", children);
		return group;
	}

	private static JsonObject elementOf(Cube cube, String uuid) {
		JsonObject element = new JsonObject();
		element.addProperty("uuid", uuid);
		element.add("from", array(cube.fromX(), cube.fromY(), cube.fromZ()));
		element.add("to", array(cube.toX(), cube.toY(), cube.toZ()));
		element.add("origin", array(cube.pivotX(), cube.pivotY(), cube.pivotZ()));
		element.add("rotation", array(cube.rotX(), cube.rotY(), cube.rotZ()));
		element.addProperty("inflate", cube.inflate());
		element.add("uv_offset", array(cube.u(), cube.v()));
		if (cube.textured()) element.addProperty("npc_studio_block", cube.block());
		return element;
	}

	// -------------------------------------------------------------- plumbing

	private static JsonArray array(float... values) {
		JsonArray made = new JsonArray();
		for (float value : values) made.add(value);
		return made;
	}

	private static int number(JsonObject json, String key, int fallback) {
		try {
			return json.has(key) ? json.get(key).getAsInt() : fallback;
		} catch (RuntimeException notANumber) {
			return fallback;
		}
	}

	/**
	 * Three numbers, or three zeroes.
	 *
	 * Forgiving on purpose. These files are hand-edited and machine-written by
	 * several programs, and a missing rotation is the commonest thing in them —
	 * it means no rotation, which is what three zeroes say.
	 */
	private static float[] triple(JsonObject json, String key) {
		float[] found = new float[3];
		if (!json.has(key) || !json.get(key).isJsonArray()) return found;
		JsonArray values = json.getAsJsonArray(key);
		for (int i = 0; i < 3 && i < values.size(); i++) {
			try {
				found[i] = values.get(i).getAsFloat();
			} catch (RuntimeException notANumber) {
				found[i] = 0;
			}
		}
		return found;
	}
}
