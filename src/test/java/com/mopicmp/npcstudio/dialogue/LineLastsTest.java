package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mopicmp.npcstudio.dialogue.text.Words;

/**
 * How long one line stays on the screen.
 *
 * The whole of what matters here is what the <em>absence</em> of an answer means,
 * so that is what is asked.
 */
class LineLastsTest {

	private static Node.Line plain() {
		return new Node.Line("say", "", Words.of("Follow me."), Presentation.SUBTITLE, null, "walk");
	}

	@Test
	@DisplayName("a line that says nothing leaves it to the document, as every line so far does")
	void noughtMeansTheDocument() {
		// Every line written before this field existed comes back through the six-part
		// form, and it has to go on meaning "not asking" rather than "asking for none".
		assertEquals(Node.Line.USES_DOCUMENT, plain().lasts());
		assertEquals(0, Node.Line.USES_DOCUMENT, "nought is the word for it, and files hold nought");
	}

	@Test
	@DisplayName("a length below nothing is not a length")
	void nonsenseIsCorrected() {
		// It arrives from a file as well as from the editor, and a conversation must not
		// fail to open because a number in it is silly.
		Node.Line odd = new Node.Line("say", "", Words.of("."), Presentation.SUBTITLE,
			null, "next", -40);
		assertEquals(Node.Line.USES_DOCUMENT, odd.lasts());
	}

	@Test
	@DisplayName("it survives being written down and read back")
	void throughAFile() {
		// Without this, a line told to take itself away would do so until the world was
		// reloaded — and then sit there for ever, which is the report this came from.
		Node.Line was = new Node.Line("say", "", Words.of("Follow me."),
			Presentation.SUBTITLE, null, "walk", 80);
		var written = com.mopicmp.npcstudio.dialogue.codec.DialogueCodecs.NODE
			.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, was)
			.getOrThrow(message -> new AssertionError(message));
		Node back = com.mopicmp.npcstudio.dialogue.codec.DialogueCodecs.NODE
			.parse(com.mojang.serialization.JsonOps.INSTANCE, written)
			.getOrThrow(message -> new AssertionError(message));
		assertEquals(was, back);
	}
}
