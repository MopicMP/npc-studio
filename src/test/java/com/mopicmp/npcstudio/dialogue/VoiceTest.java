package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mopicmp.npcstudio.dialogue.text.Tint;

/**
 * Whose face is beside a line, and what colour their name is.
 *
 * Two settings that arrived together because they are the same question asked
 * twice: who is speaking. The argument each of them settles is written down in
 * {@link Node.Line.Face} and {@link Dialogue#voiceOf}.
 */
class VoiceTest {

	private static Node.Line plain() {
		return new Node.Line("hello", "Лона", "Мммм?", Presentation.SUBTITLE, null, "end");
	}

	@Test
	@DisplayName("a line written before any of this shows the character and the usual colour")
	void theOldMeaningSurvives() {
		// The promise every field added to this record has to make. A line saved last
		// month says nothing about a face or a colour, and saying nothing has to go on
		// meaning what it meant then — not "nobody" and not "black".
		Node.Line was = plain();
		assertEquals(Node.Line.Face.SPEAKER, was.face());
		assertEquals("", was.nameColour());
	}

	@Test
	@DisplayName("a line the player speaks says so, and keeps everything else")
	void theWitherChangesOneThing() {
		// Written through the withers rather than the constructor, which is the whole
		// point of them: the editor changes one field at a time from a dozen places,
		// and every one of those places used to spell out the constructor — and several
		// spelled out the short one, quietly resetting how long the line lasts.
		Node.Line said = plain().withLasts(60).withFace(Node.Line.Face.PLAYER);
		assertEquals(Node.Line.Face.PLAYER, said.face());
		assertEquals(60, said.lasts(), "the timer is still there");
		assertEquals("Лона", said.speaker());
		assertEquals("Мммм?", said.text().plain());
		assertEquals("end", said.next());
	}

	@Test
	@DisplayName("a name's colour is said once in the document, and a line may overrule it")
	void theDocumentSaysItOnce() {
		Dialogue graph = Dialogue.builder("scene")
			.start("hello")
			.add(plain())
			.add(new Node.End("end"))
			.build();
		Dialogue coloured = new Dialogue(graph.id(), graph.formatVersion(), graph.start(),
			graph.nodes(), graph.variableTypes(), graph.segments(), graph.areas(),
			graph.manner(), graph.places(), graph.triggers(), Map.of("Лона", "yellow"));

		assertEquals("yellow", coloured.voiceOf("Лона"));
		// A name nobody has coloured is absent rather than empty-stringed, so that
		// "nobody said" and "somebody chose the default" cannot drift apart.
		assertEquals("", coloured.voiceOf("Страж"));
	}

	@Test
	@DisplayName("the colours offered are the colours the drawing knows")
	void onePaletteAndNotThree() {
		// This is the bug the shared list exists to stop, and it was reported: a colour
		// chosen in the text window changed nothing, because the picker and the drawing
		// kept separate vocabularies. Every name a picker offers must draw as itself.
		for (String name : Tint.NAMES) {
			assertNotEquals(0, Tint.of(name), name + " draws as nothing at all");
		}
		assertEquals(Tint.of("yellow"), Tint.of("yellow"));
		assertNotEquals(Tint.of("yellow"), Tint.of("red"));
		// Both spellings of the ones that have two, because a file may be typed by hand.
		assertEquals(Tint.of("grey"), Tint.of("gray"));
		assertEquals(Tint.of("pink"), Tint.of("magenta"));
		// And an unknown name is readable rather than fatal: a spelling mistake in a
		// word nobody sees must not be a scene that cannot be played.
		assertEquals(Tint.DEFAULT, Tint.of("chartreuse"));
		assertEquals(Tint.DEFAULT, Tint.of(""));
	}
}
