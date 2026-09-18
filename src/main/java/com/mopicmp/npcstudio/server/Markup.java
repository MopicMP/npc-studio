package com.mopicmp.npcstudio.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Somebody's project description, turned into things a game screen can draw.
 *
 * The first version of this reduced everything to one flat string, and that was
 * the right first move and the wrong place to stop. A description is not a
 * paragraph: it is headings, screenshots, a snippet of configuration in a box, a
 * line about the author's Boosty page which is a link and not an address, and
 * words in bold that are bold because they matter. Flattening all of it loses
 * exactly the parts that were emphasised on purpose.
 *
 * So this produces blocks and spans instead. It is still a <b>reducer, not a
 * browser</b> — the distinction is worth keeping clear, because the temptation
 * with markup is to keep adding cases until there is an HTML engine in the mod.
 * The rule here: anything not understood is dropped rather than guessed at, and
 * every block is one of six shapes. Tables become their rows' text. Nested lists
 * become flat ones. Styling beyond bold, italic, code and links is lost.
 *
 * Two languages arrive, and both are handled by turning the first into the
 * second: CurseForge answers with HTML, Modrinth with Markdown that has HTML
 * mixed into it. So HTML is rewritten into Markdown-shaped markers first, and
 * then one line-based reader handles everything. One reader rather than two is
 * not only shorter — it is why a Modrinth description with an {@code <img>} in
 * the middle of it produces a picture rather than nothing.
 */
public final class Markup {

	private Markup() {
	}

	/** A run of text with one appearance. {@code link} is null when it is not one. */
	public record Span(String text, boolean bold, boolean italic, boolean code, String link) {

		public static Span of(String text) {
			return new Span(text, false, false, false, null);
		}

		public boolean isLink() {
			return link != null && !link.isBlank();
		}
	}

	public sealed interface Block permits Words, Heading, Code, Picture, Rule {
	}

	/**
	 * A paragraph, or one item of a list when {@code bullet} is set.
	 *
	 * The two are one shape because they differ only by an indent and a dot — and
	 * a list item that wraps has to wrap under itself, which is a property of
	 * where it starts rather than of what it is.
	 */
	public record Words(List<Span> spans, boolean bullet, boolean spaced) implements Block {

		public Words(List<Span> spans, boolean bullet) {
			this(spans, bullet, false);
		}
	}

	/** A heading, with the level so that the size can follow it. */
	public record Heading(String text, int level) implements Block {
	}

	/** A block of code, drawn in a box of its own. */
	public record Code(String text) implements Block {
	}

	/** A picture inside the description, as opposed to one in the gallery. */
	public record Picture(String url, String alt) implements Block {
	}

	/** A line across, which authors use to separate sections. */
	public record Rule() implements Block {
	}

	/** How much of somebody's description is worth reading before it is enough. */
	private static final int MOST_BLOCKS = 400;

	public static List<Block> read(String markup) {
		List<Block> blocks = new ArrayList<>();
		if (markup == null || markup.isBlank()) return blocks;

		String text = fromHtml(markup);
		String[] lines = text.split("\n", -1);
		List<Span> paragraph = new ArrayList<>();
		boolean bullet = false;
		StringBuilder code = null;
		// Whether an empty line came before what is about to be added. It decides
		// nothing about the text and everything about the space above it: a
		// description where every line is a paragraph reads as a list of
		// unconnected sentences.
		boolean[] spaced = {true};

		for (String raw : lines) {
			if (blocks.size() >= MOST_BLOCKS) break;
			String line = raw.stripTrailing();
			String trimmed = line.strip();
			// Both are reassigned below once it is known this is not code.

			// A fence opens and closes a code block, and everything between the two
			// is taken exactly as it stands — which is the whole point of it.
			if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
				if (code == null) {
					flush(blocks, paragraph, bullet, spaced);
					bullet = false;
					code = new StringBuilder();
				} else {
					blocks.add(new Code(code.toString().stripTrailing()));
					code = null;
					spaced[0] = true;
				}
				continue;
			}
			if (code != null) {
				code.append(line).append('\n');
				continue;
			}
			// Outside a fence, runs of spaces are one space: they are how somebody
			// lined a sentence up in an editor and mean nothing here. Inside one
			// they are the code, which is why this is not done any earlier.
			line = line.replaceAll("[ \\t]+", " ");
			trimmed = line.strip();

			if (trimmed.isEmpty()) {
				flush(blocks, paragraph, bullet, spaced);
				bullet = false;
				spaced[0] = true;
				continue;
			}
			if (trimmed.matches("^([-*_])\\1{2,}$")) {
				flush(blocks, paragraph, bullet, spaced);
				bullet = false;
				blocks.add(new Rule());
				spaced[0] = true;
				continue;
			}

			java.util.regex.Matcher heading =
				java.util.regex.Pattern.compile("^(#{1,6})\\s+(.*)$").matcher(trimmed);
			if (heading.matches()) {
				flush(blocks, paragraph, bullet, spaced);
				bullet = false;
				String title = plainOf(inline(heading.group(2)));
				if (!title.isBlank()) blocks.add(new Heading(title, heading.group(1).length()));
				spaced[0] = true;
				continue;
			}

			java.util.regex.Matcher item = java.util.regex.Pattern
				.compile("^\\s{0,8}(?:[-*+]|\\d{1,3}[.)])\\s+(.*)$").matcher(line);
			if (item.matches()) {
				flush(blocks, paragraph, bullet, spaced);
				bullet = true;
				paragraph.addAll(inline(item.group(1)));
				flush(blocks, paragraph, true, spaced);
				bullet = false;
				continue;
			}

			// A picture on a line of its own is a block; one inside a sentence is
			// pulled out into its own block too, because a screen that draws text
			// in rows has nowhere to put a picture inside a row.
			List<Span> spans = inline(line);
			for (Span span : spans) {
				if (span.text().startsWith(PICTURE)) {
					flush(blocks, paragraph, bullet, spaced);
					bullet = false;
					String rest = span.text().substring(PICTURE.length());
					int split = rest.indexOf('\u0000');
					blocks.add(new Picture(split < 0 ? rest : rest.substring(0, split),
						split < 0 ? "" : rest.substring(split + 1)));
					spaced[0] = true;
				} else {
					paragraph.add(span);
				}
			}
			// A single newline inside a paragraph is a line break in both languages
			// as people actually write them, so each source line ends its own line.
			flush(blocks, paragraph, bullet, spaced);
			bullet = false;
		}
		if (code != null) blocks.add(new Code(code.toString().stripTrailing()));
		flush(blocks, paragraph, bullet, spaced);
		return blocks;
	}

	/** A private marker for a picture found among words; never appears in output. */
	private static final String PICTURE = "\u0001img\u0000";

	private static void flush(List<Block> blocks, List<Span> paragraph, boolean bullet,
			boolean[] spaced) {
		if (paragraph.isEmpty()) return;
		List<Span> spans = List.copyOf(paragraph);
		paragraph.clear();
		if (spans.stream().allMatch(span -> span.text().isBlank())) return;
		blocks.add(new Words(spans, bullet, spaced[0]));
		spaced[0] = false;
	}

	/** The words of a run of spans, for a heading or for a test. */
	public static String plainOf(List<Span> spans) {
		StringBuilder out = new StringBuilder();
		for (Span span : spans) out.append(span.text());
		return out.toString().strip();
	}

	/** Everything as one string, for anywhere that still wants only the words. */
	public static String plain(String markup) {
		StringBuilder out = new StringBuilder();
		for (Block block : read(markup)) {
			switch (block) {
				case Heading heading -> out.append(heading.text()).append('\n');
				case Words words -> out.append(words.bullet() ? "· " : "")
					.append(plainOf(words.spans())).append('\n');
				case Code code -> out.append(code.text()).append('\n');
				case Picture picture -> { }
				case Rule rule -> out.append('\n');
			}
		}
		return out.toString().strip();
	}

	// ------------------------------------------------------------------ html

	/**
	 * HTML rewritten as the markers the line reader understands.
	 *
	 * Not parsing: matching. A description is not a document to be validated, and
	 * an unclosed tag in somebody's Boosty banner must not cost the rest of the
	 * page. Everything below is "if this shape appears, replace it", and every
	 * tag that is not named is deleted.
	 */
	static String fromHtml(String html) {
		String text = html;
		text = text.replaceAll("(?s)<!--.*?-->", "");
		text = text.replaceAll("(?is)<(script|style|head)[^>]*>.*?</\\1>", "");

		// Links and pictures first, because they carry an address that everything
		// afterwards would strip.
		text = text.replaceAll("(?is)<a[^>]*href\\s*=\\s*\"([^\"]*)\"[^>]*>(.*?)</a>", " [$2]($1) ");
		text = text.replaceAll("(?is)<a[^>]*href\\s*=\\s*'([^']*)'[^>]*>(.*?)</a>", " [$2]($1) ");
		text = text.replaceAll("(?is)<img[^>]*alt\\s*=\\s*\"([^\"]*)\"[^>]*src\\s*=\\s*\"([^\"]*)\"[^>]*>",
			"\n![$1]($2)\n");
		text = text.replaceAll("(?is)<img[^>]*src\\s*=\\s*\"([^\"]*)\"[^>]*>", "\n![]($1)\n");
		text = text.replaceAll("(?is)<img[^>]*src\\s*=\\s*'([^']*)'[^>]*>", "\n![]($1)\n");

		text = text.replaceAll("(?is)<pre[^>]*>", "\n```\n").replaceAll("(?is)</pre>", "\n```\n");
		text = text.replaceAll("(?is)<code[^>]*>", "`").replaceAll("(?is)</code>", "`");
		text = text.replaceAll("(?is)<(b|strong)[^>]*>", "**").replaceAll("(?is)</(b|strong)>", "**");
		text = text.replaceAll("(?is)<(i|em)[^>]*>", "*").replaceAll("(?is)</(i|em)>", "*");
		// Written out per level rather than with a group: the marker has to be
		// that many hashes, and a backreference cannot repeat itself.
		for (int level = 1; level <= 6; level++) {
			text = text.replaceAll("(?is)<h" + level + "[^>]*>",
				"\n\n" + "#".repeat(level) + " ");
		}
		text = text.replaceAll("(?is)</h[1-6]>", "\n\n");
		text = text.replaceAll("(?is)<li[^>]*>", "\n- ");
		text = text.replaceAll("(?is)<br\\s*/?>", "\n");
		text = text.replaceAll("(?is)<hr\\s*/?>", "\n\n---\n\n");
		text = text.replaceAll("(?is)</(p|div|tr|ul|ol|li|table|blockquote)>", "\n\n");
		text = text.replaceAll("(?is)<(p|div|blockquote)[^>]*>", "\n\n");
		text = text.replaceAll("(?is)</?t[dh][^>]*>", " ");
		text = text.replaceAll("<[^>]*>", "");

		text = entities(text);
		// Runs of spaces are <b>not</b> collapsed here, and that is the whole
		// reason a code block works: its own indentation is the only thing making
		// it readable, and this method cannot see where the fences are. The
		// collapsing happens line by line in the reader, which can.
		text = text.replaceAll("\n{3,}", "\n\n");
		return text.strip();
	}

	private static String entities(String text) {
		String out = text;
		for (String[] pair : new String[][] {
			{"&nbsp;", " "}, {"&lt;", "<"}, {"&gt;", ">"}, {"&quot;", "\""},
			{"&#34;", "\""}, {"&#39;", "'"}, {"&apos;", "'"}, {"&mdash;", "—"},
			{"&ndash;", "–"}, {"&hellip;", "…"}, {"&laquo;", "«"}, {"&raquo;", "»"},
			{"&bull;", "·"}, {"&copy;", "©"}, {"&reg;", "®"}, {"&trade;", "™"},
			// Last, or an escaped ampersand becomes the start of another entity.
			{"&amp;", "&"}}) {
			out = out.replace(pair[0], pair[1]);
		}
		// Numeric ones, which are how non-Latin alphabets survive some editors.
		java.util.regex.Matcher numeric =
			java.util.regex.Pattern.compile("&#(x?)([0-9a-fA-F]{1,6});").matcher(out);
		StringBuilder built = new StringBuilder();
		while (numeric.find()) {
			String replacement;
			try {
				int code = Integer.parseInt(numeric.group(2),
					numeric.group(1).isEmpty() ? 10 : 16);
				replacement = code > 0 && code <= 0x10FFFF
					? new String(Character.toChars(code)) : "";
			} catch (RuntimeException notACharacter) {
				replacement = "";
			}
			numeric.appendReplacement(built, java.util.regex.Matcher.quoteReplacement(replacement));
		}
		numeric.appendTail(built);
		return built.toString();
	}

	// ------------------------------------------------------------------ inline

	/**
	 * One line of text, split into runs by what they look like.
	 *
	 * Written by hand rather than by regular expressions because these markers
	 * nest — a link whose words are bold, a bold run with code inside it — and a
	 * pattern that handles nesting is a pattern nobody can read afterwards.
	 */
	static List<Span> inline(String line) {
		List<Span> spans = new ArrayList<>();
		StringBuilder run = new StringBuilder();
		boolean bold = false;
		boolean italic = false;
		String link = null;
		int at = 0;

		while (at < line.length()) {
			char c = line.charAt(at);

			if (c == '`') {
				int close = line.indexOf('`', at + 1);
				if (close > at) {
					push(spans, run, bold, italic, false, link);
					spans.add(new Span(line.substring(at + 1, close), bold, italic, true, link));
					at = close + 1;
					continue;
				}
			}
			if (c == '!' && at + 1 < line.length() && line.charAt(at + 1) == '[') {
				int[] parts = bracketed(line, at + 1);
				if (parts != null) {
					push(spans, run, bold, italic, false, link);
					String alt = line.substring(at + 2, parts[0]);
					String url = line.substring(parts[0] + 2, parts[1]);
					spans.add(Span.of(PICTURE + url + "\u0000" + alt));
					at = parts[1] + 1;
					continue;
				}
			}
			if (c == '[') {
				int[] parts = bracketed(line, at);
				if (parts != null) {
					push(spans, run, bold, italic, false, link);
					String words = line.substring(at + 1, parts[0]);
					String url = line.substring(parts[0] + 2, parts[1]).trim();
					// A title after the address — [text](url "title") — is theirs to
					// write and not ours to show.
					int space = url.indexOf(' ');
					if (space > 0) url = url.substring(0, space);
					for (Span inner : inline(words)) {
						spans.add(new Span(inner.text(), inner.bold() || bold,
							inner.italic() || italic, inner.code(), safe(url)));
					}
					at = parts[1] + 1;
					continue;
				}
			}
			if (line.startsWith("**", at) || line.startsWith("__", at)) {
				push(spans, run, bold, italic, false, link);
				bold = !bold;
				at += 2;
				continue;
			}
			if (c == '*' || c == '_') {
				// An underscore inside a word is part of the word — file_name_here
				// is a file name and not three italic runs.
				boolean insideWord = at > 0 && Character.isLetterOrDigit(line.charAt(at - 1))
					&& at + 1 < line.length() && Character.isLetterOrDigit(line.charAt(at + 1));
				if (!insideWord) {
					push(spans, run, bold, italic, false, link);
					italic = !italic;
					at++;
					continue;
				}
			}
			if ((line.startsWith("http://", at) || line.startsWith("https://", at))
				&& link == null) {
				int end = at;
				while (end < line.length() && !Character.isWhitespace(line.charAt(end))) end++;
				// Punctuation at the end of a sentence is not part of the address.
				while (end > at && ".,;:!?)\"'".indexOf(line.charAt(end - 1)) >= 0) end--;
				String bare = line.substring(at, end);
				push(spans, run, bold, italic, false, link);
				spans.add(new Span(bare, bold, italic, false, safe(bare)));
				at = end;
				continue;
			}
			run.append(c);
			at++;
		}
		push(spans, run, bold, italic, false, link);
		return spans;
	}

	/**
	 * Only addresses that go somewhere a browser goes.
	 *
	 * A description is written by a stranger and its links are pressed by
	 * somebody who trusts this window, not them. {@code javascript:} and
	 * {@code file:} are the two that matter and neither has any business here, so
	 * anything that is not plain web is kept as words and not as a link.
	 */
	private static String safe(String url) {
		String lower = url.toLowerCase(Locale.ROOT);
		return lower.startsWith("http://") || lower.startsWith("https://") ? url : null;
	}

	/** The end of {@code [words]} and of the {@code (url)} after it, or null. */
	private static int[] bracketed(String line, int open) {
		int depth = 0;
		for (int at = open; at < line.length(); at++) {
			char c = line.charAt(at);
			if (c == '[') depth++;
			else if (c == ']') {
				depth--;
				if (depth == 0) {
					if (at + 1 >= line.length() || line.charAt(at + 1) != '(') return null;
					// Counted rather than searched for, because an address may hold
					// brackets of its own — a wiki link ending in "_(disambiguation)"
					// does, and so does "javascript:alert(1)", which is the one that
					// mattered: stopping at the first bracket left the closing one
					// behind as a stray character in the sentence.
					int inner = 0;
					for (int end = at + 1; end < line.length(); end++) {
						char d = line.charAt(end);
						if (d == '(') inner++;
						else if (d == ')' && --inner == 0) return new int[] {at, end};
					}
					return null;
				}
			}
		}
		return null;
	}

	private static void push(List<Span> spans, StringBuilder run, boolean bold, boolean italic,
			boolean code, String link) {
		if (run.isEmpty()) return;
		spans.add(new Span(run.toString(), bold, italic, code, link));
		run.setLength(0);
	}
}
