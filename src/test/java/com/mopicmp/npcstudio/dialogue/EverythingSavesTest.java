package com.mopicmp.npcstudio.dialogue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.mopicmp.npcstudio.dialogue.codec.DialogueCodecs;

/**
 * Every part a graph is made of, written down and read back.
 *
 * <h2>What this is guarding</h2>
 *
 * Three sealed families — the verbs, the conditions and the kinds of node — each
 * saved through a codec that dispatches on a name. Half of that seam is checked by
 * the compiler and half of it is not; see {@link SealedRoundTrip} for which half
 * and why the failure is silent.
 *
 * <h2>Why the kinds of node matter most of the three</h2>
 *
 * A verb is an instruction inside a document and a condition is a question asked
 * in one. A node is a <em>place</em> in it. A verb that fails to save loses a line;
 * a node that fails to save loses everything downstream of it or refuses the whole
 * file, and the file is somebody's map.
 */
class EverythingSavesTest {

	@Nested
	@DisplayName("the verbs")
	class Verbs {

		@Test
		@DisplayName("every one survives being written down and read back")
		void roundTrip() throws ReflectiveOperationException {
			SealedRoundTrip.checkEverySort(Effect.class, DialogueCodecs.EFFECT);
		}

		@Test
		@DisplayName("no two are written under the same name")
		void distinctNames() throws ReflectiveOperationException {
			SealedRoundTrip.checkNamesAreDistinct(Effect.class, DialogueCodecs.EFFECT, "type");
		}
	}

	@Nested
	@DisplayName("the conditions")
	class Conditions {

		@Test
		@DisplayName("every one survives being written down and read back")
		void roundTrip() throws ReflectiveOperationException {
			SealedRoundTrip.checkEverySort(Condition.class, DialogueCodecs.CONDITION);
		}

		@Test
		@DisplayName("no two are written under the same name")
		void distinctNames() throws ReflectiveOperationException {
			SealedRoundTrip.checkNamesAreDistinct(Condition.class, DialogueCodecs.CONDITION, "type");
		}
	}

	@Nested
	@DisplayName("the values")
	class Values {

		/**
		 * The leaf of the whole language, and the one saved without a name on it.
		 *
		 * A value is written as itself — {@code true}, {@code 3}, {@code "gate"} — and
		 * read back by trying three codecs in turn. That is the right shape for a file
		 * somebody edits by hand, and it puts the whole weight on the order: a codec
		 * earlier in the chain that accepts more than it should swallows everything
		 * after it, and the file itself says so in a note about exactly that.
		 *
		 * The failure is not a crash. A flag read back as a number is a variable that
		 * compares wrong from then on, in a graph that loads without complaint.
		 */
		@Test
		@DisplayName("a flag, a number and a word each come back as what they were")
		void roundTrip() throws ReflectiveOperationException {
			SealedRoundTrip.checkEverySort(Value.class, DialogueCodecs.VALUE);
		}

		@Test
		@DisplayName("the awkward ones do too")
		void theAwkwardOnes() {
			// Written out rather than generated, because these are the values where one
			// branch of the chain could plausibly take another's: a whole number is
			// still a number, "true" is a word and not a flag, and a word that looks
			// like a number is a word.
			for (Value value : new Value[] {
					new Value.Flag(false), new Value.Flag(true),
					new Value.Num(0), new Value.Num(1), new Value.Num(-3.5),
					new Value.Text("true"), new Value.Text("3"), new Value.Text(""),
					new Value.Text("ворота") }) {
				var written = DialogueCodecs.VALUE.encodeStart(
					com.mojang.serialization.JsonOps.INSTANCE, value);
				org.junit.jupiter.api.Assertions.assertEquals(value,
					DialogueCodecs.VALUE.parse(com.mojang.serialization.JsonOps.INSTANCE,
						written.getOrThrow(IllegalStateException::new))
						.getOrThrow(IllegalStateException::new),
					"as written: " + written.getOrThrow(IllegalStateException::new));
			}
		}
	}

	@Nested
	@DisplayName("the kinds of node")
	class Nodes {

		@Test
		@DisplayName("every one survives being written down and read back")
		void roundTrip() throws ReflectiveOperationException {
			SealedRoundTrip.checkEverySort(Node.class, DialogueCodecs.NODE);
		}

		@Test
		@DisplayName("no two are written under the same name")
		void distinctNames() throws ReflectiveOperationException {
			SealedRoundTrip.checkNamesAreDistinct(Node.class, DialogueCodecs.NODE, "type");
		}
	}
}
