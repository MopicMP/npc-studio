package com.mopicmp.npcstudio.net;

import java.util.Optional;

import com.mopicmp.npcstudio.dialogue.text.Look;
import com.mopicmp.npcstudio.dialogue.text.Words;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A decorated line on its way across the wire.
 *
 * <h2>Why this is written out rather than borrowed from the file codec</h2>
 *
 * Because they answer different questions. The file codec is arranged for
 * somebody reading and writing JSON by hand: names, defaults left out, colours
 * spelled in hexadecimal. None of that is a virtue here, where nobody reads it
 * and every byte is sent per line of dialogue spoken.
 *
 * The cost of having two is the usual one, and it is worth naming rather than
 * discovering: they can disagree. A property added to {@link Look} and put in
 * only one of them travels or is stored but not both, and the symptom is a
 * colour that works in single player and not on a server, or the other way
 * round. That is what {@code WordsWireTest} is for, and why it asks about a run
 * with <em>every</em> property set rather than a plausible one.
 *
 * <h2>The caps</h2>
 *
 * Both are 512, and it is the same 512: a line of dialogue is capped at 512
 * characters by the field that types it, so it cannot hold more than 512 runs
 * either — one per character is the worst a rainbow can do.
 */
public final class WordsWire {

	private WordsWire() { }

	private static final int MOST_RUNS = 512;
	private static final int LONGEST = 512;

	/** How long a font's name may be. Long enough for a namespace and a word. */
	private static final int NAMED = 128;

	private static final int BOLD = 1;
	private static final int ITALIC = 2;
	private static final int UNDERLINED = 4;
	private static final int STRUCK = 8;

	/**
	 * The four faces in one byte.
	 *
	 * Not thrift for its own sake. Four separate booleans is four bytes per run,
	 * and a run is a stretch of a line — on a rainbow line that is four bytes per
	 * character, of which every one says false.
	 */
	private static byte facesOf(Look look) {
		int packed = 0;
		if (look.bold()) packed |= BOLD;
		if (look.italic()) packed |= ITALIC;
		if (look.underlined()) packed |= UNDERLINED;
		if (look.struck()) packed |= STRUCK;
		return (byte) packed;
	}

	private static final StreamCodec<ByteBuf, Words.Run> RUN = StreamCodec.composite(
		ByteBufCodecs.stringUtf8(LONGEST), Words.Run::text,
		ByteBufCodecs.optional(ByteBufCodecs.INT), run -> Optional.ofNullable(run.look().colour()),
		ByteBufCodecs.BYTE, run -> facesOf(run.look()),
		// Sent as a name and not as an identifier, because this side of the mod is
		// where the dialogue engine lives and it has never been allowed to know what
		// an identifier is. Empty means none, which is what an empty name already
		// means to Look.
		ByteBufCodecs.stringUtf8(NAMED), run -> run.look().font() == null ? "" : run.look().font(),
		ByteBufCodecs.FLOAT, run -> run.look().size(),
		(text, colour, faces, font, size) -> new Words.Run(text, new Look(
			colour.orElse(null),
			(faces & BOLD) != 0,
			(faces & ITALIC) != 0,
			(faces & UNDERLINED) != 0,
			(faces & STRUCK) != 0,
			font,
			size)));

	public static final StreamCodec<ByteBuf, Words> WORDS =
		RUN.apply(ByteBufCodecs.list(MOST_RUNS)).map(Words::new, Words::runs);
}
