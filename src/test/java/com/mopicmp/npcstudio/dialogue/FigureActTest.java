package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mojang.serialization.JsonOps;
import com.mopicmp.npcstudio.dialogue.codec.DialogueCodecs;
import com.mopicmp.npcstudio.net.ShowPortraitPayload;
import com.mopicmp.npcstudio.puppet.Puppet;

/**
 * An act that shows somebody, whether she is one picture or a stack of them.
 *
 * <h2>What is worth guarding here</h2>
 *
 * That the two ways of naming a portrait do not become one muddle. A picture and a
 * figure answer the same question — who is standing there — and a record holding both
 * would leave the drawing to pick, which is a decision nobody made taken somewhere
 * nobody would look.
 *
 * And that every act written before figures existed still means what it meant. There are
 * such acts in world saves, and they say a fingerprint and nothing else.
 */
class FigureActTest {

	@Test
	@DisplayName("an act written before figures existed is untouched by them")
	void theOldFormIsStillItself() {
		Effect.Portrait was = new Effect.Portrait("abc", Effect.Portrait.Side.LEFT, true);
		assertTrue(was.showing());
		assertFalse(was.assembled(), "a picture is not a figure");
		assertEquals("", was.figure());
		assertTrue(was.choice().isEmpty());

		var written = DialogueCodecs.EFFECT.encodeStart(JsonOps.INSTANCE, was)
			.getOrThrow(message -> new AssertionError(message));
		// The two new fields are absent, so a map nobody has opened does not change on
		// the day this field arrives.
		assertFalse(written.toString().contains("figure"), "no fattening: " + written);
		assertEquals(was, DialogueCodecs.EFFECT.parse(JsonOps.INSTANCE, written)
			.getOrThrow(message -> new AssertionError(message)));
	}

	@Test
	@DisplayName("a figure and its choice survive being saved")
	void aFigureRoundTrips() {
		Effect.Portrait was = new Effect.Portrait("", Effect.Portrait.Side.RIGHT, false,
			"Lona", Map.of("body", "plain", "eyes", "sad"));
		assertTrue(was.assembled());

		var written = DialogueCodecs.EFFECT.encodeStart(JsonOps.INSTANCE, was)
			.getOrThrow(message -> new AssertionError(message));
		assertEquals(was, DialogueCodecs.EFFECT.parse(JsonOps.INSTANCE, written)
			.getOrThrow(message -> new AssertionError(message)));
	}

	@Test
	@DisplayName("dressing a figure adds and removes without touching anything else")
	void wearingIsOneSlotAtATime() {
		Effect.Portrait was = new Effect.Portrait("", Effect.Portrait.Side.LEFT, true,
			"Lona", Map.of("body", "plain"));

		Effect.Portrait now = was.wearing("eyes", "sad");
		assertEquals(Map.of("body", "plain", "eyes", "sad"), now.choice());
		assertEquals(Effect.Portrait.Side.LEFT, now.side(), "the staging is not a costume");
		assertTrue(now.mirrored());

		// Taking a slot off writes nothing rather than writing emptiness. "Nobody said"
		// and "somebody said none" have to be the same state, or a document saved twice
		// stops matching itself.
		assertEquals(Map.of("body", "plain"), now.wearing("eyes", "").choice());
	}

	@Test
	@DisplayName("a figure's choice becomes a stack of pictures with corners")
	void theStackIsWhatTravels() {
		// The shape the server sends. Worked out there rather than on the client, so that
		// a player who has never opened the layout window draws the figure correctly.
		Puppet sheet = new Puppet("Lona", 380, 637, List.of(
			new Puppet.Slot("body", 0, 0, List.of(new Puppet.Part("plain", "aaa"))),
			new Puppet.Slot("eyes", 140, 96, List.of(new Puppet.Part("sad", "eee", 3, 2)))));

		var worn = sheet.worn(Map.of("body", "plain", "eyes", "sad"));
		var layers = worn.stream()
			.map(placed -> new ShowPortraitPayload.Layer(placed.picture(), placed.x(), placed.y()))
			.toList();
		var packet = new ShowPortraitPayload(layers, sheet.wide(), sheet.high(), 0, false);

		assertEquals(2, packet.layers().size());
		assertEquals(143, packet.layers().get(1).x(), "slot plus the variant's own nudge");
		assertEquals(380, packet.wide());
	}

	@Test
	@DisplayName("one whole picture is a stack of one with no canvas of its own")
	void aSinglePictureIsTheSamePath() {
		// No second way through the drawing for the simple case. The canvas is nought
		// because the server does not know how big the picture is — it is a file on a
		// shelf — and the client already holds that number.
		var one = ShowPortraitPayload.one("abc", 1, true);
		assertEquals(1, one.layers().size());
		assertEquals(0, one.wide());
		assertEquals(0, one.layers().get(0).x());

		assertTrue(ShowPortraitPayload.none().layers().isEmpty(),
			"and nothing showing is an empty stack, which every scene already knows how to be");
	}
}
