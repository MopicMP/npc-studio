package com.mopicmp.npcstudio.client.emote;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mopicmp.npcstudio.client.emote.Emote.Bone;
import com.mopicmp.npcstudio.client.emote.Emote.Channel;
import com.mopicmp.npcstudio.client.emote.Emote.Easing;
import com.mopicmp.npcstudio.client.emote.Emote.Track;

/**
 * Reads the EmoteCraft format.
 *
 * Written by hand rather than as a codec because the format is not tidy in the
 * way codecs want. {@code isLoop} is a string holding a boolean — and one file
 * in seven hundred capitalises it. Keyframes are partial and repeat the same
 * tick. A codec would either reject the pack or need so many exceptions that
 * the exceptions would be the parser.
 *
 * Being forgiving is the point. This reads other people's files, and a pack
 * that loses one emote to a stray field is better than a pack that will not
 * load at all.
 */
public final class EmoteParser {

	private EmoteParser() { }

	public static Emote parse(String id, JsonObject root) {
		JsonObject body = root.getAsJsonObject("emote");
		if (body == null) throw new IllegalArgumentException("no emote in " + id);

		// Gathered into lists first because keyframes arrive interleaved: several
		// entries share a tick, each carrying a different limb, so no track is
		// complete until the whole file has been read.
		Map<Bone, Map<Channel, List<Key>>> gathered = new EnumMap<>(Bone.class);
		JsonElement moves = body.get("moves");
		if (moves != null && moves.isJsonArray()) {
			for (JsonElement element : moves.getAsJsonArray()) {
				if (element.isJsonObject()) read(element.getAsJsonObject(), gathered);
			}
		}

		Map<Bone, Map<Channel, Track>> tracks = new EnumMap<>(Bone.class);
		gathered.forEach((bone, channels) -> {
			Map<Channel, Track> built = new EnumMap<>(Channel.class);
			channels.forEach((channel, keys) -> built.put(channel, compile(keys)));
			tracks.put(bone, built);
		});

		return new Emote(id,
			text(root.get("name"), id),
			string(root.get("author"), ""),
			text(root.get("description"), ""),
			bool(body.get("isLoop")),
			integer(body.get("beginTick"), 0),
			integer(body.get("endTick"), 0),
			integer(body.get("stopTick"), integer(body.get("endTick"), 0)),
			integer(body.get("returnTick"), 0),
			tracks);
	}

	private record Key(int tick, float value, Easing easing) { }

	private static void read(JsonObject move, Map<Bone, Map<Channel, List<Key>>> into) {
		int tick = integer(move.get("tick"), 0);
		Easing easing = Easing.of(string(move.get("easing"), "LINEAR"));

		for (Map.Entry<String, JsonElement> entry : move.entrySet()) {
			Bone bone = bone(entry.getKey());
			if (bone == null || !entry.getValue().isJsonObject()) continue;

			Map<Channel, List<Key>> channels = into.computeIfAbsent(bone, key -> new EnumMap<>(Channel.class));
			for (Map.Entry<String, JsonElement> axis : entry.getValue().getAsJsonObject().entrySet()) {
				Channel channel = channel(axis.getKey());
				if (channel == null) continue;
				channels.computeIfAbsent(channel, key -> new ArrayList<>())
					.add(new Key(tick, number(axis.getValue(), 0), easing));
			}
		}
	}

	/**
	 * Sorts a channel's keys and flattens them into arrays.
	 *
	 * Keys landing on the same tick are a real thing in these files. The last one
	 * written wins, which matches how they are laid down — a later line in the
	 * file is a later edit.
	 */
	private static Track compile(List<Key> keys) {
		keys.sort((a, b) -> Integer.compare(a.tick, b.tick));

		int[] ticks = new int[keys.size()];
		float[] values = new float[keys.size()];
		Easing[] easings = new Easing[keys.size()];
		int size = 0;
		for (Key key : keys) {
			if (size > 0 && ticks[size - 1] == key.tick) size--;
			ticks[size] = key.tick;
			values[size] = key.value;
			easings[size] = key.easing;
			size++;
		}
		if (size == keys.size()) return new Track(ticks, values, easings);
		return new Track(java.util.Arrays.copyOf(ticks, size),
			java.util.Arrays.copyOf(values, size),
			java.util.Arrays.copyOf(easings, size));
	}

	private static Bone bone(String name) {
		return switch (name) {
			case "head" -> Bone.HEAD;
			case "torso" -> Bone.TORSO;
			case "rightArm" -> Bone.RIGHT_ARM;
			case "leftArm" -> Bone.LEFT_ARM;
			case "rightLeg" -> Bone.RIGHT_LEG;
			case "leftLeg" -> Bone.LEFT_LEG;
			case "rightItem" -> Bone.RIGHT_ITEM;
			case "leftItem" -> Bone.LEFT_ITEM;
			default -> null;
		};
	}

	private static Channel channel(String name) {
		return switch (name) {
			case "x" -> Channel.X;
			case "y" -> Channel.Y;
			case "z" -> Channel.Z;
			case "pitch" -> Channel.PITCH;
			case "yaw" -> Channel.YAW;
			case "roll" -> Channel.ROLL;
			case "bend" -> Channel.BEND;
			default -> null;
		};
	}

	/** The readable half of a translatable field, which is what these packs fill in. */
	public static String text(JsonElement element, String fallback) {
		if (element == null) return fallback;
		if (element.isJsonObject()) {
			JsonObject object = element.getAsJsonObject();
			String value = string(object.get("fallback"), "");
			if (value.isEmpty()) value = string(object.get("translate"), "");
			return value.isEmpty() ? fallback : value;
		}
		return string(element, fallback);
	}

	private static String string(JsonElement element, String fallback) {
		return element == null || !element.isJsonPrimitive() ? fallback : element.getAsString();
	}

	private static int integer(JsonElement element, int fallback) {
		try {
			return element == null || !element.isJsonPrimitive() ? fallback : element.getAsInt();
		} catch (NumberFormatException notANumber) {
			return fallback;
		}
	}

	private static float number(JsonElement element, float fallback) {
		try {
			return element == null || !element.isJsonPrimitive() ? fallback : element.getAsFloat();
		} catch (NumberFormatException notANumber) {
			return fallback;
		}
	}

	/** Written as a string in this format, and capitalised inconsistently. */
	private static boolean bool(JsonElement element) {
		if (element == null || !element.isJsonPrimitive()) return false;
		return "true".equals(element.getAsString().trim().toLowerCase(Locale.ROOT));
	}
}
