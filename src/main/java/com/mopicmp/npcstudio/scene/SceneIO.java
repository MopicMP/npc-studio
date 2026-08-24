package com.mopicmp.npcstudio.scene;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A scene as a file.
 *
 * <h2>Our own format, and why</h2>
 *
 * Nothing else says this. A {@code .bbmodel} animation belongs to one model and
 * has no idea that a second character exists; Minecraft's own animation
 * definitions are code rather than data. What is being written down here is a
 * cast, several performances against one clock, and the sounds and lines between
 * them — and the whole reason for owning the format is that the relationship
 * between those is the thing being edited.
 *
 * <h2>Forgiving on the way in</h2>
 *
 * Every reader here treats a missing field as a default and an unreadable one as
 * missing. These files sit in a world folder where somebody will open them in an
 * editor, and a scene that refuses to load because one ease was spelled wrong is
 * worse than a scene with one straight line in it. What cannot be recovered is
 * dropped rather than guessed at: a track with no subject belongs to nobody and
 * inventing an owner for it would put somebody's arm somewhere they never asked.
 */
public final class SceneIO {

	private SceneIO() { }

	// ---------------------------------------------------------------- reading

	public static Scene read(JsonObject json) {
		String name = text(json, "name", "scene");
		int length = number(json, "length", Scene.RATE * 5);

		List<Role> cast = new ArrayList<>();
		for (JsonObject each : objects(json, "cast")) {
			String who = text(each, "name", "");
			if (who.isEmpty()) continue;
			cast.add(new Role(who, kindOf(text(each, "kind", "")), text(each, "bound", ""),
				number(each, "repeat", 0)));
		}

		List<Track> tracks = new ArrayList<>();
		for (JsonObject each : objects(json, "tracks")) {
			String subject = text(each, "subject", "");
			String channel = text(each, "channel", "");
			if (subject.isEmpty() || channel.isEmpty()) continue;
			tracks.add(new Track(subject, channel, keysOf(each)));
		}

		List<Cue> cues = new ArrayList<>();
		for (JsonObject each : objects(json, "cues")) {
			Cue.Kind kind = cueKindOf(text(each, "kind", ""));
			cues.add(new Cue(number(each, "at", 0), kind,
				text(each, "what", ""), number(each, "lasts", 0), lookOf(each)));
		}

		return new Scene(name, length, cast, tracks, cues);
	}

	/**
	 * The keys of one track: {@code [tick, value, ease]} apiece.
	 *
	 * An array rather than an object per key, because a thinned recording is
	 * hundreds of them per channel and {@code {"at": 4, "value": 90, "ease":
	 * "SMOOTH"}} spends four times the room saying the same thing. Three positions
	 * is little enough to remember while reading the file.
	 */
	private static List<Key> keysOf(JsonObject track) {
		List<Key> keys = new ArrayList<>();
		if (!track.has("keys") || !track.get("keys").isJsonArray()) return keys;
		for (JsonElement element : track.getAsJsonArray("keys")) {
			if (!element.isJsonArray()) continue;
			JsonArray key = element.getAsJsonArray();
			if (key.size() < 2) continue;
			try {
				Key.Ease ease = key.size() > 2 ? easeOf(key.get(2).getAsString()) : Key.Ease.SMOOTH;
				keys.add(new Key(key.get(0).getAsInt(), key.get(1).getAsFloat(), ease));
			} catch (RuntimeException unreadable) {
				// One bad key is one bad key. The rest of the performance is fine.
			}
		}
		return keys;
	}

	private static Key.Ease easeOf(String said) {
		for (Key.Ease ease : Key.Ease.values()) {
			if (ease.name().equalsIgnoreCase(said)) return ease;
		}
		return Key.Ease.SMOOTH;
	}

	/**
	 * A cue's kind, read by name.
	 *
	 * By name and over every kind there is, rather than the two-way choice this
	 * used to be. That choice was correct while there were two kinds and became
	 * silently wrong the moment there were three: a piece of music written down
	 * came back as a line of text, and a scene saved and reopened lost its music
	 * without saying so.
	 *
	 * Anything unrecognised is a line, which is the harmless one: it shows and can
	 * be deleted, where an unrecognised sound would fire.
	 */
	private static Cue.Kind cueKindOf(String said) {
		for (Cue.Kind kind : Cue.Kind.values()) {
			if (kind.name().equalsIgnoreCase(said)) return kind;
		}
		return Cue.Kind.TEXT;
	}

	private static Role.Kind kindOf(String said) {
		for (Role.Kind kind : Role.Kind.values()) {
			if (kind.name().equalsIgnoreCase(said)) return kind;
		}
		return Role.Kind.CHARACTER;
	}

	// ---------------------------------------------------------------- writing

	public static JsonObject write(Scene scene) {
		JsonObject json = new JsonObject();
		json.addProperty("format", 1);
		json.addProperty("name", scene.name());
		json.addProperty("length", scene.length());

		JsonArray cast = new JsonArray();
		for (Role role : scene.cast()) {
			JsonObject each = new JsonObject();
			each.addProperty("name", role.name());
			each.addProperty("kind", role.kind().name());
			each.addProperty("bound", role.bound());
			// Left out when a part plays once, which is nearly all of them. See
			// Role.repeating for why the loop belongs to a part and not to the scene.
			if (role.repeat() > 0) each.addProperty("repeat", role.repeat());
			cast.add(each);
		}
		json.add("cast", cast);

		JsonArray tracks = new JsonArray();
		for (Track track : scene.tracks()) {
			JsonObject each = new JsonObject();
			each.addProperty("subject", track.subject());
			each.addProperty("channel", track.channel());
			JsonArray keys = new JsonArray();
			for (Key key : track.keys()) {
				JsonArray one = new JsonArray();
				one.add(key.at());
				one.add(key.value());
				one.add(key.ease().name());
				keys.add(one);
			}
			each.add("keys", keys);
			tracks.add(each);
		}
		json.add("tracks", tracks);

		JsonArray cues = new JsonArray();
		for (Cue cue : scene.cues()) {
			JsonObject each = new JsonObject();
			each.addProperty("at", cue.at());
			each.addProperty("kind", cue.kind().name());
			each.addProperty("what", cue.what());
			each.addProperty("lasts", cue.lasts());
			if (cue.look() != null && !cue.look().equals(Look.PLAIN)) {
				each.add("look", lookJson(cue.look()));
			}
			cues.add(each);
		}
		json.add("cues", cues);
		return json;
	}

	/**
	 * How a line is drawn, or the plain look when the file does not say.
	 *
	 * Absent is the ordinary case rather than an error: every scene written before
	 * captions had an appearance has no such object, and every line since that
	 * nobody has restyled has none either — it is left out on the way in when it is
	 * the default, so the file stays readable rather than carrying nine numbers
	 * that all say "as usual".
	 */
	private static Look lookOf(JsonObject cue) {
		if (!cue.has("look") || !cue.get("look").isJsonObject()) return Look.PLAIN;
		JsonObject look = cue.getAsJsonObject("look");
		return new Look(
			text(look, "font", Look.PLAIN.font()),
			decimal(look, "size", Look.PLAIN.size()),
			number(look, "colour", Look.PLAIN.colour()),
			decimal(look, "x", Look.PLAIN.x()),
			decimal(look, "y", Look.PLAIN.y()),
			alignOf(text(look, "align", "")),
			flag(look, "bold", Look.PLAIN.bold()),
			flag(look, "italic", Look.PLAIN.italic()),
			flag(look, "shadow", Look.PLAIN.shadow()));
	}

	private static JsonObject lookJson(Look look) {
		JsonObject json = new JsonObject();
		json.addProperty("font", look.font());
		json.addProperty("size", look.size());
		json.addProperty("colour", look.colour());
		json.addProperty("x", look.x());
		json.addProperty("y", look.y());
		json.addProperty("align", look.align().name());
		json.addProperty("bold", look.bold());
		json.addProperty("italic", look.italic());
		json.addProperty("shadow", look.shadow());
		return json;
	}

	/**
	 * Where a line sits relative to its point, read by name.
	 *
	 * Written out for the reason {@link #cueKindOf} is: a two-way conditional over
	 * three cases is a bug waiting for the third one, and that exact bug has been
	 * had here already — music saved and came back as text.
	 */
	private static Look.Align alignOf(String said) {
		for (Look.Align align : Look.Align.values()) {
			if (align.name().equalsIgnoreCase(said)) return align;
		}
		return Look.PLAIN.align();
	}

	private static boolean flag(JsonObject json, String named, boolean unsaid) {
		return json.has(named) && json.get(named).isJsonPrimitive()
			? json.get(named).getAsBoolean() : unsaid;
	}

	// --------------------------------------------------------------- plumbing

	private static List<JsonObject> objects(JsonObject json, String key) {
		List<JsonObject> found = new ArrayList<>();
		if (!json.has(key) || !json.get(key).isJsonArray()) return found;
		for (JsonElement element : json.getAsJsonArray(key)) {
			if (element.isJsonObject()) found.add(element.getAsJsonObject());
		}
		return found;
	}

	private static String text(JsonObject json, String key, String fallback) {
		try {
			return json.has(key) && json.get(key).isJsonPrimitive()
				? json.get(key).getAsString() : fallback;
		} catch (RuntimeException notText) {
			return fallback;
		}
	}

	private static int number(JsonObject json, String key, int fallback) {
		try {
			return json.has(key) ? json.get(key).getAsInt() : fallback;
		} catch (RuntimeException notANumber) {
			return fallback;
		}
	}

	private static float decimal(JsonObject json, String key, float fallback) {
		try {
			return json.has(key) ? json.get(key).getAsFloat() : fallback;
		} catch (RuntimeException notANumber) {
			return fallback;
		}
	}
}
