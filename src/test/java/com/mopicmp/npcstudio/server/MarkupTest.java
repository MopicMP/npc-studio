package com.mopicmp.npcstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading a description written by somebody who was not thinking about us.
 *
 * The samples here are shaped like what the two catalogues actually carry:
 * Markdown with HTML mixed into it, HTML on its own, badge images, a link to a
 * Boosty page, a block of configuration, and Cyrillic — because that last one
 * broke a console once already and the way it breaks is silent.
 *
 * What is being guarded is the reducer's one promise: <b>everything it does not
 * understand it drops, and nothing it drops is a sentence</b>. A parser that
 * guesses is worse here than one that is plain, because the input is written by
 * thousands of people and half of it is malformed.
 */
class MarkupTest {

	@Test
	@DisplayName("Headings, paragraphs and bullets come out as what they are")
	void shapes() {
		List<Markup.Block> blocks = Markup.read("""
			# GeckoLib

			An animation library.

			- thirty easings
			- sound keyframes
			""");
		assertEquals(4, blocks.size(), blocks.toString());
		Markup.Heading heading = assertInstanceOf(Markup.Heading.class, blocks.get(0));
		assertEquals("GeckoLib", heading.text());
		assertEquals(1, heading.level());
		assertFalse(assertInstanceOf(Markup.Words.class, blocks.get(1)).bullet());
		assertTrue(assertInstanceOf(Markup.Words.class, blocks.get(2)).bullet());
		assertEquals("sound keyframes",
			Markup.plainOf(((Markup.Words) blocks.get(3)).spans()));
	}

	@Test
	@DisplayName("Bold and italic survive, and an underscore inside a word does not become italic")
	void emphasis() {
		List<Markup.Span> spans = Markup.inline("Put **this** in your _mods_ folder as my_file_name");
		assertTrue(spans.stream().anyMatch(span -> span.bold() && span.text().equals("this")),
			spans.toString());
		assertTrue(spans.stream().anyMatch(span -> span.italic() && span.text().equals("mods")),
			spans.toString());
		// The file name is one word. Treating its underscores as markers is the
		// classic way a reducer turns a path into three italic fragments.
		assertTrue(Markup.plainOf(spans).contains("my_file_name"), Markup.plainOf(spans));
	}

	@Test
	@DisplayName("A hyperlink keeps its words and gains an address")
	void links() {
		// The case that started this: people put their Boosty and Patreon pages in
		// a description as words, not as addresses, and there was no way to reach
		// them at all.
		List<Markup.Span> spans = Markup.inline("Support me on [Boosty](https://boosty.to/someone)!");
		Markup.Span link = spans.stream().filter(Markup.Span::isLink).findFirst().orElseThrow();
		assertEquals("Boosty", link.text());
		assertEquals("https://boosty.to/someone", link.link());
		// And the sentence around it is untouched.
		assertEquals("Support me on Boosty!", Markup.plainOf(spans));
	}

	@Test
	@DisplayName("A bare address is a link too, without the full stop after it")
	void bareAddress() {
		List<Markup.Span> spans = Markup.inline("See https://example.com/wiki, then come back.");
		Markup.Span link = spans.stream().filter(Markup.Span::isLink).findFirst().orElseThrow();
		assertEquals("https://example.com/wiki", link.link());
	}

	@Test
	@DisplayName("An address that is not the web stays words and never becomes a link")
	void notEveryAddress() {
		// A description is written by a stranger and its links are pressed by
		// somebody who trusts this window rather than them. Only http and https
		// are ever handed to the machine.
		assertNull(Markup.inline("[click](javascript:alert(1))").getFirst().link());
		assertNull(Markup.inline("[open](file:///C:/Windows)").getFirst().link());
		assertEquals("click", Markup.plainOf(Markup.inline("[click](javascript:alert(1))")));
	}

	@Test
	@DisplayName("A block of code is kept whole and marked as code")
	void code() {
		List<Markup.Block> blocks = Markup.read("""
			Put this in the config:

			```
			max-players = 20
			  motd = hello
			```

			Then restart.
			""");
		Markup.Code code = blocks.stream().filter(Markup.Code.class::isInstance)
			.map(Markup.Code.class::cast).findFirst().orElseThrow();
		// Its own spacing survives, which is the whole reason a box exists for it:
		// configuration that has been re-wrapped as prose is configuration that
		// will be copied wrongly.
		assertTrue(code.text().contains("max-players = 20"), code.text());
		assertTrue(code.text().contains("  motd = hello"), code.text());
	}

	@Test
	@DisplayName("Pictures inside the words become blocks of their own")
	void pictures() {
		List<Markup.Block> blocks = Markup.read(
			"Look at this:\n\n![a screenshot](https://cdn.modrinth.com/one.png)\n\nnice?");
		Markup.Picture picture = blocks.stream().filter(Markup.Picture.class::isInstance)
			.map(Markup.Picture.class::cast).findFirst().orElseThrow();
		assertEquals("https://cdn.modrinth.com/one.png", picture.url());
		assertEquals("a screenshot", picture.alt());
		// And the marker used to carry it never reaches anything that is drawn.
		assertFalse(Markup.plain(
			"![x](https://cdn.modrinth.com/one.png)").contains("img"));
	}

	@Test
	@DisplayName("HTML is read as the same shapes as Markdown")
	void html() {
		// CurseForge answers with this and nothing else, so it is not a fallback
		// path — it is half of all descriptions.
		List<Markup.Block> blocks = Markup.read(
			"<h2>What it does</h2><p>Shows <b>recipes</b> and <i>uses</i>.</p>"
			+ "<ul><li>Search</li><li>Bookmarks</li></ul>"
			+ "<p>Join our <a href=\"https://discord.gg/x\">discord</a>!</p>");

		Markup.Heading heading = blocks.stream().filter(Markup.Heading.class::isInstance)
			.map(Markup.Heading.class::cast).findFirst().orElseThrow();
		assertEquals("What it does", heading.text());
		assertEquals(2, heading.level());

		String whole = Markup.plain(blocks.isEmpty() ? "" : "");
		assertTrue(blocks.stream().anyMatch(block -> block instanceof Markup.Words words
			&& words.bullet() && Markup.plainOf(words.spans()).equals("Search")), blocks.toString());
		assertTrue(blocks.stream().anyMatch(block -> block instanceof Markup.Words words
			&& words.spans().stream().anyMatch(span ->
				span.bold() && span.text().equals("recipes"))), blocks.toString());
		assertTrue(blocks.stream().anyMatch(block -> block instanceof Markup.Words words
			&& words.spans().stream().anyMatch(span ->
				"https://discord.gg/x".equals(span.link()))), blocks.toString());
		assertEquals("", whole);
	}

	@Test
	@DisplayName("Entities become letters, including the numeric ones")
	void entities() {
		// The numeric ones are how a Cyrillic description survives some editors,
		// and dropping them would silently empty half a page.
		String text = Markup.plain("<p>&laquo;&#1055;&#1088;&#1080;&#1074;&#1077;&#1090;"
			+ "&raquo; &amp; &lt;tags&gt;</p>");
		assertEquals("«Привет» & <tags>", text);
	}

	@Test
	@DisplayName("An unclosed tag costs its own line and not the rest of the page")
	void malformed() {
		// Real descriptions are full of this. A parser that validates would refuse
		// the document; a reducer drops what it cannot read and keeps going.
		String text = Markup.plain("<p>First<p>Second<b>third</p><div>fourth");
		assertTrue(text.contains("First"), text);
		assertTrue(text.contains("fourth"), text);
	}

	@Test
	@DisplayName("A blank line makes a paragraph and a single newline makes a line")
	void spacing() {
		List<Markup.Block> blocks = Markup.read("one\ntwo\n\nthree");
		List<Markup.Words> words = blocks.stream().filter(Markup.Words.class::isInstance)
			.map(Markup.Words.class::cast).toList();
		assertEquals(3, words.size());
		assertFalse(words.get(1).spaced(), "a line inside a paragraph is not a new paragraph");
		assertTrue(words.get(2).spaced(), "a line after a blank one starts a paragraph");
	}

	@Test
	@DisplayName("Nothing at all is nothing, and never a crash")
	void nothing() {
		assertTrue(Markup.read(null).isEmpty());
		assertTrue(Markup.read("").isEmpty());
		assertTrue(Markup.read("   \n\n  ").isEmpty());
		assertEquals("", Markup.plain(null));
	}
}
