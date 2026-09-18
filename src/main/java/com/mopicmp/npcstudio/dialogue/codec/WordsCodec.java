package com.mopicmp.npcstudio.dialogue.codec;

import java.util.List;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mopicmp.npcstudio.dialogue.text.Look;
import com.mopicmp.npcstudio.dialogue.text.Words;

/**
 * Reading and writing a line of dialogue that may be decorated.
 *
 * <h2>The one rule this file exists to keep</h2>
 *
 * <strong>A line nobody decorated is written as a bare string.</strong> Not as an
 * array of one, not as an object with every field at its default — as the same
 * string it was before any of this existed.
 *
 * Everything else here follows from that. It is what lets every dialogue already
 * written be read and written back byte for byte, so adding colour to the mod
 * does not rewrite files nobody touched; and it is what keeps the format
 * hand-writable, which this whole package is arranged around. The test that
 * matters is not "a colour came back a colour" — it is "a plain line came back a
 * plain string", and it is easy to lose without noticing, because losing it
 * breaks nothing that anybody can see.
 *
 * <h2>Shape</h2>
 *
 * <pre>
 * "just a line"
 *
 * [
 *   {"text": "Ты ", "color": "#ffffff"},
 *   {"text": "не вернёшься", "color": "#e57373", "bold": true, "size": 1.5},
 *   {"text": ".", "font": "npc_studio:cinzel"}
 * ]
 * </pre>
 *
 * Flat rather than nested, because the alternative is {@code {"text": …, "look":
 * {…}}} and the extra level buys nothing: a run has a text and some properties,
 * and none of the properties is called text.
 */
public final class WordsCodec {

	private WordsCodec() { }

	/** Colour as {@code #rrggbb}, which is how the game writes one and how people read one. */
	private static final Codec<Integer> COLOUR = Codec.STRING.comapFlatMap(
		written -> {
			String digits = written.startsWith("#") ? written.substring(1) : written;
			if (digits.length() != 6) {
				return DataResult.error(() ->
					"a colour is six hexadecimal digits, as in #e57373; got \"" + written + "\"");
			}
			try {
				return DataResult.success(Integer.parseInt(digits, 16));
			} catch (NumberFormatException notHex) {
				return DataResult.error(() ->
					"\"" + written + "\" is not a colour; expected six hexadecimal digits, as in #e57373");
			}
		},
		value -> String.format("#%06x", value & 0xFFFFFF));

	/**
	 * One run, with every property of its look optional.
	 *
	 * Optional with a default rather than nullable, so a run that says nothing
	 * beyond its words writes exactly {@code {"text": "…"}} — the same reason the
	 * whole file avoids ceremony.
	 */
	private static final Codec<Words.Run> RUN = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf("text").forGetter(Words.Run::text),
			COLOUR.optionalFieldOf("color").forGetter(run ->
				java.util.Optional.ofNullable(run.look().colour())),
			Codec.BOOL.optionalFieldOf("bold", false).forGetter(run -> run.look().bold()),
			Codec.BOOL.optionalFieldOf("italic", false).forGetter(run -> run.look().italic()),
			Codec.BOOL.optionalFieldOf("underlined", false).forGetter(run -> run.look().underlined()),
			Codec.BOOL.optionalFieldOf("strikethrough", false).forGetter(run -> run.look().struck()),
			Codec.STRING.optionalFieldOf("font").forGetter(run ->
				java.util.Optional.ofNullable(run.look().font())),
			// Not clamped here. What is too big is a question about a screen, and this
			// side of the fence has never seen one; the run is stored as written and
			// judged where the drawing happens.
			Codec.FLOAT.optionalFieldOf("size", Look.ORDINARY).forGetter(run -> run.look().size()))
		.apply(instance, (text, colour, bold, italic, underlined, struck, font, size) ->
			new Words.Run(text, new Look(colour.orElse(null), bold, italic, underlined, struck,
				font.orElse(null), size))));

	/**
	 * A string or a list of runs, and a plain line is always written as the string.
	 *
	 * The string is tried first on the way in because it is the cheaper and by far
	 * the commoner shape; on the way out the choice is not a preference but the
	 * rule at the top of this file.
	 */
	public static final Codec<Words> WORDS = Codec.either(Codec.STRING, RUN.listOf()).xmap(
		either -> either.map(Words::of, Words::new),
		words -> words.isPlain() ? Either.left(words.plain()) : Either.right(words.runs()));
}
