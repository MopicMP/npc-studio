package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

/**
 * Checking that every member of a sealed family survives being saved.
 *
 * <h2>The seam this exists for</h2>
 *
 * A graph's verbs, its conditions and its kinds of node are each a sealed
 * interface with a codec that dispatches on a name. Turning a value into its name
 * is a switch over the sealed type, so the compiler refuses to let a new member be
 * forgotten there. Turning that name back into a codec is a switch over a
 * {@code String}, and a string meeting a string is checked by nobody.
 *
 * Drift between the two halves is silent in the worst way. A member with a name
 * and no codec works perfectly while the document is in memory — it can be built
 * in the editor, it runs, it does the right thing — and it disappears when the
 * world is saved, or refuses the whole file, which is somebody's map.
 *
 * <h2>Why one helper rather than three tests</h2>
 *
 * The three families ask exactly the same question and the answer is the same
 * arithmetic of reflection. Three copies would drift the way the thing they are
 * testing drifts, which would be a poor joke.
 *
 * <h2>Why the family is asked rather than listed</h2>
 *
 * {@code getPermittedSubclasses} means a member added tomorrow is covered
 * tomorrow. A test naming the members that exist today is a test that goes on
 * passing for exactly the change it was written to catch.
 */
final class SealedRoundTrip {

	private SealedRoundTrip() { }

	/**
	 * Builds one of each member and sends it through the codec and back.
	 *
	 * @param filled whether lists and maps carry something. Both are worth trying: a
	 *               codec with a required field passes on a filled value and fails on
	 *               a bare one, and a wrong default does the opposite. Bare is not a
	 *               contrived case — it is the shape a fresh node has in the editor.
	 */
	static <T> void checkEverySort(Class<T> family, Codec<T> codec) throws ReflectiveOperationException {
		List<String> broken = new ArrayList<>();
		for (boolean filled : new boolean[] { false, true }) {
			for (T made : oneOfEach(family, filled)) {
				String where = made.getClass().getSimpleName() + (filled ? " (filled)" : " (bare)");

				var written = codec.encodeStart(JsonOps.INSTANCE, made);
				if (written.isError()) {
					broken.add(where + " cannot be written: " + written.error().orElseThrow().message());
					continue;
				}
				JsonElement json = written.getOrThrow(IllegalStateException::new);

				var read = codec.parse(JsonOps.INSTANCE, json);
				if (read.isError()) {
					broken.add(where + " cannot be read back: "
						+ read.error().orElseThrow().message() + " from " + json);
					continue;
				}
				T back = read.getOrThrow(IllegalStateException::new);
				// Records compare by their fields, so this catches a codec that drops a
				// field, defaults it to the wrong thing, or writes one into another.
				if (!made.equals(back)) broken.add(where + ": " + made + " became " + back);
			}
		}
		assertTrue(broken.isEmpty(), String.join("\n", broken));
	}

	/** That no two members are written under one name, which is worse than losing one. */
	static <T> void checkNamesAreDistinct(Class<T> family, Codec<T> codec, String field)
			throws ReflectiveOperationException {
		List<String> names = new ArrayList<>();
		for (T made : oneOfEach(family, true)) {
			names.add(codec.encodeStart(JsonOps.INSTANCE, made)
				.getOrThrow(IllegalStateException::new)
				.getAsJsonObject().get(field).getAsString());
		}
		// Sharing a name means one member is read back as another: the document loads
		// and the graph quietly does something else, which nobody goes looking for.
		assertEquals(names.size(), names.stream().distinct().count(), "shared names among " + names);
	}

	private static <T> List<T> oneOfEach(Class<T> family, boolean filled)
			throws ReflectiveOperationException {
		Class<?>[] kinds = family.getPermittedSubclasses();
		assertTrue(kinds != null && kinds.length > 0,
			family.getSimpleName() + " is no longer a sealed interface");

		List<T> made = new ArrayList<>();
		for (Class<?> kind : kinds) {
			RecordComponent[] fields = kind.getRecordComponents();
			assertTrue(fields != null, kind.getSimpleName() + " is not a record");

			Class<?>[] types = new Class<?>[fields.length];
			Object[] values = new Object[fields.length];
			for (int i = 0; i < fields.length; i++) {
				types[i] = fields[i].getType();
				values[i] = sample(fields[i], i, filled);
			}
			// By its exact parameter types, so a record with a shortened convenience
			// constructor beside it cannot be picked by accident.
			Constructor<?> canonical = kind.getDeclaredConstructor(types);
			canonical.setAccessible(true);
			made.add(family.cast(canonical.newInstance(values)));
		}
		return made;
	}

	/**
	 * A value for one field.
	 *
	 * Distinct per position so that a codec quietly writing one field into another
	 * shows up as a mismatch rather than as two zeroes agreeing with each other.
	 */
	private static Object sample(RecordComponent field, int nth, boolean filled) {
		Class<?> type = field.getType();

		if (type == String.class) return "sample_" + nth;
		if (type == int.class) return 7 + nth;
		if (type == float.class) return 0.25f + nth;
		if (type == double.class) return 0.5 + nth;
		if (type == boolean.class) return nth % 2 == 0;
		if (type == Presentation.class) return Presentation.CUTSCENE;
		if (type == Scope.class) return Scope.WORLD;
		if (type == Condition.Op.class) return Condition.Op.values()[1];
		if (type == Value.class) return new Value.Num(3.5);
		if (type == Condition.class) return new Condition.Not(new Condition.Always());
		if (type == Effect.class) return new Effect.Express("cross", 20);
		// A route with everything different from its default, for the same reason the
		// decorated line below is: a route sampled as the empty one would round-trip
		// through a codec that had quietly forgotten how to carry points at all.
		if (type == Route.class) {
			// Both forms of point, in one route, with every other field away from its
			// default. A route sampled with one kind of point would round-trip through
			// a codec that had quietly forgotten how to carry the other — and the two
			// are told apart by which key they use, which is exactly the sort of seam
			// that goes wrong silently.
			return new Route(List.of(
					new Route.Point.At(14 + nth, 71, -88, 40),
					new Route.Point.Go(4 + nth, 1, -2, 20)),
				Route.Gait.RUN, 0.8f, Route.From.WORLD);
		}
		if (type == Node.Homing.class) return Node.Homing.TELEPORT;
		// ADD, not PUT. Same reason as the face and the side below: sampled with the
		// value a field takes when nobody sets it, a codec that has forgotten the field
		// altogether reads back as the default and compares equal.
		if (type == Node.Set.Change.class) return Node.Set.Change.ADD;
		// Neither is the default, for the reason every sample here avoids one: a codec
		// that has forgotten the field entirely reads back as the default and compares
		// equal, so a sample sitting on the default proves nothing.
		if (type == Effect.Trait.How.class) return Effect.Trait.How.TIMES_ALL;
		if (type == Effect.Trait.Whose.class) return Effect.Trait.Whose.CHARACTER;
		// Not SPEAKER, which is the default. Sampling a field with the value it takes
		// when nobody sets it is how a codec that has forgotten the field entirely
		// passes: absent reads back as the default and the two compare equal.
		if (type == Node.Line.Face.class) return Node.Line.Face.PLAYER;
		// LEFT, not the default. Same reason as the face above: sampling a field with
		// the value it takes when nobody sets it lets a codec that has forgotten the
		// field entirely pass, because absent reads back as the default.
		if (type == Effect.Portrait.Side.class) return Effect.Portrait.Side.LEFT;
		// One point on its own, which a verb may hold without a route around it. Written
		// as steps, because that is the form that carries the anchor and so the form
		// with something to lose — a codec that had forgotten how to write "go" would
		// pass a test sampled with coordinates while quietly dropping every scene built
		// off to one side of a map.
		if (type == Route.Point.class) return new Route.Point.Go(3 + nth, 1, -2, 0);
		// A thing shown in the air, with every field away from what it takes when nobody
		// says: a block rather than words, coloured, larger than one, with its plaque on.
		// Sampled as words at ordinary size it would pass through a codec that had
		// forgotten the whole look and read back as the same plain label.
		if (type == com.mopicmp.npcstudio.dialogue.Shown.class) {
			return new com.mopicmp.npcstudio.dialogue.Shown(
				com.mopicmp.npcstudio.dialogue.Shown.Kind.BLOCK,
				com.mopicmp.npcstudio.dialogue.text.Words.of("minecraft:campfire[lit=false]"),
				2.5f, true);
		}
		// Decorated, and every property of the look set to something different from
		// its default. A plain line would round-trip through a codec that had quietly
		// forgotten how to carry colour at all, and the test would pass while saying
		// nothing — which is the failure this whole class exists to prevent.
		if (type == com.mopicmp.npcstudio.dialogue.text.Words.class) {
			return new com.mopicmp.npcstudio.dialogue.text.Words(List.of(
				new com.mopicmp.npcstudio.dialogue.text.Words.Run("plain_" + nth,
					com.mopicmp.npcstudio.dialogue.text.Look.PLAIN),
				new com.mopicmp.npcstudio.dialogue.text.Words.Run("dressed_" + nth,
					new com.mopicmp.npcstudio.dialogue.text.Look(0xE57373 + nth, true, true, true,
						true, "npc_studio:sample", 1.5f))));
		}

		if (type == List.class) {
			if (!filled) return List.of();
			return List.of(oneElement(elementOf(field.getGenericType())));
		}
		if (type == Map.class) {
			// What the map holds, rather than what the first map here happened to hold.
			// It was Value for everybody, and the day a second kind of map arrived the
			// sample was cast straight into a codec expecting strings — loudly, which is
			// the only reason this was a minute's work rather than a save that silently
			// wrote the wrong shape.
			if (!filled) return Map.of();
			return Map.of("who", oneElement(valueOf(field.getGenericType())));
		}
		// Deliberately loud. The right answer to a new kind of field is a sample for
		// it, not a quieter test: a family member this cannot build is a family member
		// nothing is checking.
		throw new AssertionError(field.getDeclaringRecord().getSimpleName() + "." + field.getName()
			+ " is a " + type + ", which this has never seen — add a sample for it");
	}

	private static Class<?> elementOf(Type generic) {
		if (generic instanceof ParameterizedType parameterised
				&& parameterised.getActualTypeArguments()[0] instanceof Class<?> plain) {
			return plain;
		}
		throw new AssertionError("cannot tell what this list holds: " + generic);
	}

	/** What a map's values are, which is its second type argument. */
	private static Class<?> valueOf(Type generic) {
		if (generic instanceof ParameterizedType parameterised
				&& parameterised.getActualTypeArguments().length == 2
				&& parameterised.getActualTypeArguments()[1] instanceof Class<?> plain) {
			return plain;
		}
		throw new AssertionError("cannot tell what this map holds: " + generic);
	}

	private static Object oneElement(Class<?> of) {
		if (of == Node.Option.class) {
			return new Node.Option("yes", "green", new Condition.Always(), "after");
		}
		if (of == Node.Arm.class) return new Node.Arm(new Condition.Always(), "after");
		if (of == Node.Press.class) return new Node.Press("menu.play", "after");
		if (of == Condition.class) return new Condition.Always();
		// A map of names to values: what a graph's variables are declared as.
		if (of == Value.class) return new Value.Text("gate");
		// A list of plain names: the ways out of a fork decided by a throw. And a map of
		// them: which part of a figure is worn in which slot. Anything that is not
		// another node id would do; this one says what it is, so a failure reads as a
		// fork rather than as an unexplained string.
		if (of == String.class) return "after";
		throw new AssertionError("a list or map of something new: " + of);
	}
}
