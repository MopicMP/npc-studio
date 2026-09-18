package com.mopicmp.npcstudio.dialogue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.mopicmp.npcstudio.dialogue.text.Words;

/**
 * A thing standing in the air: what it is and how it is drawn.
 *
 * <h2>What these are made of, and what they are not</h2>
 *
 * Not an armour stand with a name, which is what everybody remembers and what every guide
 * written before 1.19.4 describes. A display entity: real text components with their own
 * background and wrap width, or a real block, or a real item, hanging where it was put.
 *
 * <h2>Why one kind with a choice inside, and not three verbs</h2>
 *
 * Because everything around the choice is the same. All three are put somewhere, given a
 * size, named so that a later node can change or take them away, and pressed. The part
 * that differs is one field and the one setting that only means something for words. Three
 * verbs would have been three of everything else as well — three arms in the codec, three
 * in the validator, three panes in the editor — for one difference.
 *
 * It also means the next one is a case rather than a verb. A particle, a picture, a whole
 * saved building: each is one more member of {@link Kind} and one more arm where the game's
 * spelling lives, and nothing above that layer changes at all.
 *
 * <h2>Why the words are {@link Words} and not a string with a colour beside it</h2>
 *
 * Because this mod already has a window for writing dressed text, and it is the one every
 * line of every conversation is written in. A second way of colouring text — a dropdown
 * beside a plain field — was one vocabulary too many and could only ever colour the whole
 * label at once. Reported in exactly those terms, and rightly: the row was answering a
 * question that had been answered better elsewhere.
 *
 * So a hologram's text is written the way a line is, in the same window, with the same
 * looks. One thing the display cannot honour is the per-run <em>size</em>: a text display
 * has one scale for the whole entity, so {@link com.mopicmp.npcstudio.dialogue.text.Look}'s
 * size is ignored here and {@link #size} is the one that counts. Everything else — colour,
 * bold, italic, underline, strikethrough, typeface — is a text component and travels.
 *
 * <h2>Why this is data and not a pile of setters</h2>
 *
 * Because it could not have been setters. Every field a display has of its own —
 * {@code setText}, {@code setBackgroundColor}, {@code setBillboardConstraints},
 * {@code setBrightnessOverride}, {@code setViewRange} — is <b>private</b> in 26.2. The
 * supported way to build one is to load it from save data, so a hologram is a piece of data
 * whether one wanted it to be or not. Which suits a language whose whole business is data.
 *
 * <h2>Why one plaque and one size for the whole of it</h2>
 *
 * Because the game gives no choice. A text display has no per-line settings — a line at
 * another size or with its own plaque has to be a second display. So a menu of five items
 * is five shown things with five names, and pretending otherwise here would only move the
 * disappointment further from its cause.
 *
 * <h2>The three settings that are not offered, because they have one right answer</h2>
 *
 * <b>Facing.</b> Always turned to whoever is reading. Left fixed, words read from one side
 * and are a bright line from the other, which is the single thing that makes one of these
 * look like a mistake rather than a decision.
 *
 * <b>Light.</b> Overridden to full. The game has {@code Brightness.FULL_BRIGHT} for exactly
 * this and almost nothing uses it, which is why other people's holograms go out at dusk.
 *
 * <b>Through walls.</b> On. Words half-swallowed by the block they hang on are the second
 * most common way one of these looks broken.
 *
 * @param kind   whether this is words, a block or an item
 * @param what   the words, written and dressed the way a line is; or, for a block and an
 *               item, the id — see {@link Kind}
 * @param size   how large, as a multiple. 1 is about the size of a nameplate
 * @param plaque whether the dark panel behind the words is drawn. Words only
 */
public record Shown(Kind kind, Words what, float size, boolean plaque) {

	/** What sort of thing is standing there. */
	public enum Kind {
		/**
		 * Words. {@code what} is the text, with {@code \n} between lines and
		 * {@code {name}} where a variable's value goes.
		 */
		TEXT,
		/**
		 * A block. {@code what} is a block id, with its state in brackets if it has one —
		 * the same spelling {@link Effect.PutBlock} takes, because it is the same question.
		 */
		BLOCK,
		/** An item. {@code what} is an item id. */
		ITEM
	}

	/** As long as one shown thing's words may be, counted in characters rather than lines. */
	public static final int LONGEST = 512;

	/**
	 * How wide a line may get before the game wraps it, in pixels.
	 *
	 * The game's own default is 200. Left at that, a menu item that happens to be long
	 * wraps and stops lining up with the ones above it, so this is wide enough that
	 * wrapping is something an author asks for with {@code \n} rather than something that
	 * happens to them.
	 */
	public static final int LINE_WIDTH = 400;

	/**
	 * The largest one of these may be set to.
	 *
	 * A ceiling because a graph is a file somebody edits, and a scale of a thousand is a
	 * hologram that fills the sky with no way to see the node that made it. Generous enough
	 * that a title over a doorway never meets it.
	 */
	public static final float BIGGEST = 16.0f;

	public Shown {
		kind = kind == null ? Kind.TEXT : kind;
		what = what == null ? Words.EMPTY : what;
		if (what.length() > LONGEST) what = what.first(LONGEST);
		size = Float.isFinite(size) ? Math.clamp(size, 0.1f, BIGGEST) : 1.0f;
	}

	/** A kind by name, and words for anything else. See the codec, which is where this is used. */
	public static Kind kindOf(String said) {
		for (Kind kind : Kind.values()) {
			if (kind.name().equalsIgnoreCase(said)) return kind;
		}
		// Words rather than a refusal, because the alternative is a document that will not
		// load at all over one misspelt word in one node. A thing that came out as words
		// when it was meant to be a block says so the moment anybody looks at it.
		return Kind.TEXT;
	}

	/** The kind as the file writes it. */
	public String kindName() {
		return kind.name().toLowerCase(java.util.Locale.ROOT);
	}

	/** The plainest one: words in the air, ordinary size, no plaque. */
	public static Shown of(String says) {
		return new Shown(Kind.TEXT, Words.of(says), 1.0f, false);
	}

	/** What it says or which thing it is, undressed — an id, or the text without its looks. */
	public String plain() {
		return what.plain();
	}

	public Shown saying(Words now) {
		return new Shown(kind, now, size, plaque);
	}

	/** The same, reworded but dressed as it was. What a plain field beside the window does. */
	public Shown saying(String now) {
		return new Shown(kind, what.reworded(now), size, plaque);
	}

	public Shown being(Kind now) {
		return new Shown(now, what, size, plaque);
	}

	public Shown looking(float nowSize, boolean nowPlaque) {
		return new Shown(kind, what, nowSize, nowPlaque);
	}

	/** Whether the setting that is only about words means anything here. */
	public boolean isText() {
		return kind == Kind.TEXT;
	}

	/**
	 * What this reads once the variables in it have been filled in, dressed as it was.
	 *
	 * <h2>Why there are placeholders at all before anybody can be told apart</h2>
	 *
	 * Because the author asked for one hologram shared by everybody <em>now</em>, with the
	 * text written through variables so that showing each person their own could be turned
	 * on later without the file format changing. Placeholders are the whole of that
	 * promise: the format has them today, and what changes later is only whose values are
	 * handed in.
	 *
	 * A name nothing answers for is left standing in the text rather than blanked, so that
	 * a misspelt one reads as {@code {прогрес}} on the wall — visible, and pointing at
	 * itself — instead of as a gap nobody can account for.
	 *
	 * <h2>Why a name has to be written in one look</h2>
	 *
	 * The substitution is made inside each stretch of one look, so the value comes out
	 * wearing that look. A {@code {name}} whose brace is red and whose word is white is two
	 * stretches, and neither of them holds a whole name — so it is left standing, which is
	 * the same thing a misspelt one does and reads the same way on the wall.
	 */
	public Words filled(Map<String, String> values) {
		if (kind != Kind.TEXT || what.plain().indexOf('{') < 0) return what;
		List<Words.Run> out = new ArrayList<>(what.runs().size());
		for (Words.Run run : what.runs()) {
			out.add(new Words.Run(fill(run.text(), values), run.look()));
		}
		return new Words(out);
	}

	private static String fill(String text, Map<String, String> values) {
		if (text.indexOf('{') < 0) return text;
		StringBuilder out = new StringBuilder(text.length());
		int at = 0;
		while (at < text.length()) {
			int open = text.indexOf('{', at);
			if (open < 0) {
				out.append(text, at, text.length());
				break;
			}
			int shut = text.indexOf('}', open + 1);
			if (shut < 0) {
				out.append(text, at, text.length());
				break;
			}
			out.append(text, at, open);
			String name = text.substring(open + 1, shut);
			String had = values == null ? null : values.get(name);
			out.append(had != null ? had : text.substring(open, shut + 1));
			at = shut + 1;
		}
		return out.toString();
	}

	/** Every variable this reads, so a document can be checked for having them. */
	public List<String> names() {
		if (kind != Kind.TEXT) return List.of();
		List<String> found = new ArrayList<>();
		for (Words.Run run : what.runs()) {
			String text = run.text();
			int at = 0;
			while (true) {
				int open = text.indexOf('{', at);
				if (open < 0) break;
				int shut = text.indexOf('}', open + 1);
				if (shut < 0) break;
				String name = text.substring(open + 1, shut);
				if (!name.isEmpty() && !found.contains(name)) found.add(name);
				at = shut + 1;
			}
		}
		return List.copyOf(found);
	}

	/**
	 * The background as the game wants it.
	 *
	 * Below an alpha of {@code 0x1A} the game draws no panel at all — so "words in the air"
	 * is a value rather than a flag, and this is the one place that has to know it.
	 */
	public int behind() {
		return plaque ? 0x40000000 : 0x00000000;
	}
}
