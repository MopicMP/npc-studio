package com.mopicmp.npcstudio.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mopicmp.npcstudio.dialogue.text.Look;
import com.mopicmp.npcstudio.dialogue.text.Words;

import io.netty.buffer.Unpooled;

/**
 * A decorated line across the wire.
 *
 * <h2>The seam this watches</h2>
 *
 * There are two descriptions of a {@link Look} in this mod: the one written to a
 * file and the one sent to a client. Nothing makes them agree — no compiler
 * checks that a property added to one was added to the other — and the symptom
 * of them disagreeing is not a crash. It is a colour that works in single player
 * and not on a server, or the other way about, noticed weeks later by somebody
 * who cannot reproduce it.
 *
 * So the run below has <em>every</em> property set, and set to something that is
 * not its default. A plausible-looking sample would sail through a codec that
 * had quietly stopped carrying half of them.
 */
class WordsWireTest {

	private static Words there(Words words) {
		var buffer = Unpooled.buffer();
		WordsWire.WORDS.encode(buffer, words);
		Words back = WordsWire.WORDS.decode(buffer);
		assertEquals(0, buffer.readableBytes(),
			"the reader stopped somewhere other than where the writer did");
		return back;
	}

	@Test
	@DisplayName("every property of a look makes the journey")
	void everythingTravels() {
		Words written = new Words(List.of(
			new Words.Run("Ты ", Look.PLAIN),
			new Words.Run("не вернёшься", new Look(0xE57373, true, true, true, true,
				"npc_studio:cinzel", 1.5f))));

		assertEquals(written, there(written));
	}

	@Test
	@DisplayName("the four faces are told apart, one by one")
	void facesArePackedAndUnpackedInTheRightOrder() {
		// Packed into one byte, which is where an ordering mistake would hide: three
		// of the four would still be right, and the fourth would be somebody else's.
		for (int which = 0; which < 4; which++) {
			Look look = switch (which) {
				case 0 -> Look.PLAIN.withBold(true);
				case 1 -> Look.PLAIN.withItalic(true);
				case 2 -> Look.PLAIN.withUnderlined(true);
				default -> Look.PLAIN.withStruck(true);
			};
			Words words = new Words(List.of(new Words.Run("a", look)));
			assertEquals(look, there(words).runs().get(0).look(), "face " + which);
		}
	}

	@Test
	@DisplayName("no colour and black are not the same thing")
	void unchosenIsNotNought() {
		// Nought is a perfectly ordinary colour and a perfectly ordinary way to spell
		// "nothing here", which is why it has to be sent as an absence rather than as
		// a number — otherwise black text quietly becomes undecorated text.
		Words none = new Words(List.of(new Words.Run("a", Look.PLAIN)));
		Words black = new Words(List.of(new Words.Run("a", Look.PLAIN.withColour(0))));

		assertEquals(null, there(none).runs().get(0).look().colour());
		assertEquals(0, there(black).runs().get(0).look().colour());
	}

	@Test
	@DisplayName("a plain line travels and comes back plain")
	void plainSurvives() {
		Words plain = Words.of("Привет.");
		assertEquals(plain, there(plain));
		assertTrue(there(plain).isPlain());
	}

	@Test
	@DisplayName("nothing at all is a line the wire can carry")
	void emptinessTravels() {
		assertEquals(Words.EMPTY, there(Words.EMPTY));
	}

	@Test
	@DisplayName("a line where every letter is drawn differently still fits")
	void aRainbowFits() {
		// The worst a line can do to this codec, and the number the cap was chosen
		// against: a run per character of the longest line the editor will accept.
		StringBuilder text = new StringBuilder();
		java.util.List<Look> looks = new java.util.ArrayList<>();
		for (int at = 0; at < 512; at++) {
			text.append('a');
			looks.add(Look.PLAIN.withColour(at));
		}
		Words rainbow = Words.of(text.toString(), looks);

		assertEquals(512, rainbow.runs().size(), "the sample is what it claims to be");
		assertEquals(rainbow, there(rainbow));
	}
}
