package com.mopicmp.npcstudio.client.scene;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.net.ScenePayloads;
import com.mopicmp.npcstudio.scene.Scene;
import com.mopicmp.npcstudio.scene.SceneIO;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * The world's scenes as this client knows them.
 *
 * <h2>Why the client keeps its own copy at all</h2>
 *
 * Because editing is a hundred small changes a minute and each of them has to be
 * on the screen immediately. Asking the server after every drag would put a
 * round trip inside a gesture. So the client edits its copy and sends the result
 * — and because the server is the one that writes the file, the copy is not the
 * record, it is a draft that keeps agreeing with the record.
 *
 * <h2>Saved by itself</h2>
 *
 * There is no save button and there is not going to be one. Every edit marks the
 * scene and a moment later it goes; a person editing a scene is not thinking
 * about files. The delay is the whole trick — without it, dragging a key across
 * two seconds of timeline would be forty packets and forty versions on disk.
 */
public final class Scenes {

	/**
	 * How long an edit sits before it is sent, in client ticks.
	 *
	 * Half a second. Long enough that a drag is one save rather than forty, short
	 * enough that nobody manages to close the game inside it — and closing is
	 * covered anyway, since leaving a world flushes whatever is waiting.
	 */
	private static final int SETTLES = 10;

	private static final Gson GSON = new Gson();

	private static final Map<String, Scene> known = new LinkedHashMap<>();
	private static int generation;

	/** Which scene has an unsent change, and how long it has been waiting. */
	private static String dirty;
	private static int waited;

	private Scenes() { }

	public static int generation() {
		return generation;
	}

	public static List<String> names() {
		List<String> found = new ArrayList<>(known.keySet());
		found.sort(String::compareToIgnoreCase);
		return found;
	}

	public static Scene get(String name) {
		return name == null ? null : known.get(name);
	}

	/** Asks the server for everything. Cheap, and the panels need it to open. */
	public static void refresh() {
		ClientPlayNetworking.send(new ScenePayloads.Please());
	}

	/**
	 * A scene arriving from the server.
	 *
	 * A scene with an edit of ours still waiting is left alone: the copy here is
	 * newer than the one being described, and taking the older one would undo
	 * whatever was typed in the last half second in front of the person who typed
	 * it.
	 */
	public static void accept(String name, String json) {
		if (name.equals(dirty)) return;
		try {
			known.put(name, SceneIO.read(JsonParser.parseString(json).getAsJsonObject()));
			generation++;
		} catch (RuntimeException unreadable) {
			NpcStudio.LOGGER.warn("The server sent a scene that will not read: {}",
				unreadable.toString());
		}
	}

	public static void gone(String name) {
		if (known.remove(name) != null) generation++;
		if (name.equals(dirty)) dirty = null;
	}

	/** Dropped on leaving a world; the next one has its own scenes. */
	public static void forget() {
		known.clear();
		dirty = null;
		waited = 0;
		generation++;
	}

	/**
	 * Writes a change down here and starts the clock on sending it.
	 *
	 * Every edit goes through this. That is what makes "no save button" true
	 * rather than aspirational: there is no other way to change a scene, so there
	 * is no way to change one and forget to keep it.
	 */
	public static void keep(String name, Scene scene) {
		if (name == null || scene == null) return;
		// Another scene had something waiting. It goes now rather than being
		// dropped: the clock belongs to the editing, not to the document.
		if (dirty != null && !dirty.equals(name)) flush();
		known.put(name, scene);
		generation++;
		dirty = name;
		waited = 0;
	}

	/** Called once a client tick. */
	public static void tick() {
		if (dirty == null) return;
		if (++waited >= SETTLES) flush();
	}

	/** Sends whatever is waiting, now. */
	public static void flush() {
		if (dirty == null) return;
		Scene scene = known.get(dirty);
		String name = dirty;
		dirty = null;
		waited = 0;
		if (scene == null) return;

		byte[] whole = GSON.toJson(SceneIO.write(scene))
			.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		if (whole.length > ScenePayloads.MOST) {
			// Not a wire limit any more — the pieces take care of that — but a bound
			// on what a server may be asked to hold. A scene this size is a mistake
			// rather than a performance.
			NpcStudio.LOGGER.warn("Scene {} is {} bytes, which is past anything sane",
				name, whole.length);
			return;
		}

		// Every scene goes in pieces, not only the large ones. One path is one path
		// to get right, and a small scene is a transfer of a single piece.
		String upload = java.util.UUID.randomUUID().toString();
		int count = Math.max(1, (whole.length + ScenePayloads.PART - 1) / ScenePayloads.PART);
		for (int i = 0; i < count; i++) {
			int from = i * ScenePayloads.PART;
			int to = Math.min(whole.length, from + ScenePayloads.PART);
			ClientPlayNetworking.send(new ScenePayloads.Part(upload, i, count, name,
				java.util.Arrays.copyOfRange(whole, from, to)));
		}
	}

	public static void remove(String name) {
		if (known.remove(name) != null) generation++;
		if (name.equals(dirty)) {
			dirty = null;
			waited = 0;
		}
		ClientPlayNetworking.send(new ScenePayloads.Gone(name));
	}
}
