package com.mopicmp.npcstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading a config, and changing one value in it without touching the rest.
 *
 * <b>What is being defended here is the file, not the feature.</b> In a plugin
 * config the comments are the entire documentation — the plugin author wrote
 * them, the server owner has read them, and half of them have that owner's own
 * notes underneath. A settings screen that loses them once has made the file
 * worse than it was before this mod existed, and no amount of convenience buys
 * that back.
 *
 * <p>So the tests are about what does <em>not</em> change: the text either side
 * of the value, the blank lines, the odd CRLF in an otherwise LF file, the
 * quoting. The file used for that last one is written the way Paper writes its
 * own — because that is the file this will meet.
 */
class ConfigFileTest {

	/** A shape taken from a real paper-global.yml, CRLF comments and all. */
	private static final String PAPER = """
		# This is the global configuration file for Paper.\r
		# As you can see, there's a lot to configure.\r
		#\r
		# Interested in reporting a bug? https://github.com/PaperMC/Paper/issues\r
		_version: 31
		chunk-loading-basic:
		  player-max-chunk-generate-rate: -1.0
		  player-max-chunk-load-rate: 100.0
		collisions:
		  # Whether the server should let entities push each other around.
		  enable-player-collisions: true
		  fix-climbing-bypassing-cramming-rule: false
		messages:
		  no-permission: '<red>I''m sorry, but you do not have permission.'
		  kick:
		    authentication-servers-down: ''
		    flying-player: <red>Flying is not enabled on this server
		misc:
		  max-joins-per-tick: 5
		  long-message: This is a message that Paper wrapped over two lines
		    because it was longer than the width it wraps at
		""";

	private static ConfigFile paper() {
		return ConfigFile.parse(Path.of("config", "paper-global.yml"), PAPER);
	}

	@Test
	@DisplayName("Reading finds the values, their paths and the comment above them")
	void readsPathsAndComments() {
		List<ConfigFile.Setting> found = paper().settings();
		ConfigFile.Setting collisions = one(found, "collisions.enable-player-collisions");
		assertEquals("true", collisions.value());
		assertEquals(ConfigFile.Sort.FLAG, collisions.sort());
		assertTrue(collisions.on());
		// The comment is the label. This format has no other documentation, and a
		// row called "enable-player-collisions" and nothing else is a row nobody
		// can act on.
		assertEquals("Whether the server should let entities push each other around.",
			collisions.comment());
		// And a key with nothing written above it does not borrow the comment of
		// the key before it.
		assertEquals("", one(found, "collisions.fix-climbing-bypassing-cramming-rule").comment());

		assertEquals(ConfigFile.Sort.NUMBER, one(found, "misc.max-joins-per-tick").sort());
		assertEquals(ConfigFile.Sort.NUMBER,
			one(found, "chunk-loading-basic.player-max-chunk-load-rate").sort());
		assertEquals("31", one(found, "_version").value());
	}

	@Test
	@DisplayName("Changing one value changes one line, and nothing else in the file")
	void oneLineChanges() {
		ConfigFile file = paper();
		ConfigFile.Setting collisions = one(file.settings(), "collisions.enable-player-collisions");
		String after = file.with(List.of(collisions), List.of("false"));

		List<String> was = PAPER.lines().toList();
		List<String> now = after.lines().toList();
		assertEquals(was.size(), now.size());
		int changed = 0;
		for (int at = 0; at < was.size(); at++) {
			if (!was.get(at).equals(now.get(at))) {
				changed++;
				assertEquals("  enable-player-collisions: true", was.get(at));
				assertEquals("  enable-player-collisions: false", now.get(at));
			}
		}
		assertEquals(1, changed);
		// The four carriage returns in the header are still there. A library that
		// rewrites the file normalises those away without saying so; this cannot,
		// because it never wrote those bytes.
		assertEquals(count(PAPER, '\r'), count(after, '\r'));
		// One character longer, because "false" is one longer than "true" — the
		// edit and nothing besides.
		assertEquals(PAPER.length() + 1, after.length());
	}

	@Test
	@DisplayName("A quoted value stays quoted, in the style it was quoted in")
	void keepsQuoting() {
		ConfigFile file = paper();
		ConfigFile.Setting message = one(file.settings(), "messages.no-permission");
		// Read without its quotes and without the doubling that quoting needed.
		assertEquals("<red>I'm sorry, but you do not have permission.", message.value());

		String after = file.with(List.of(message), List.of("<red>Nope, it's not allowed"));
		assertTrue(after.contains("no-permission: '<red>Nope, it''s not allowed'"), after);
		// Written back unchanged, the file is the file again — which is the whole
		// promise, and the one thing worth checking byte for byte.
		assertEquals(PAPER, file.with(List.of(message), List.of(message.value())));
	}

	@Test
	@DisplayName("A plain value that would stop being one value is quoted")
	void quotesWhatWouldBreak() {
		ConfigFile file = paper();
		ConfigFile.Setting flying = one(file.settings(), "messages.kick.flying-player");
		// This one is unquoted in the file, so it stays unquoted while it can.
		assertTrue(file.with(List.of(flying), List.of("No flying here"))
			.contains("flying-player: No flying here"));
		// And is quoted the moment it could not be read back as itself: a colon and
		// a space start a mapping, and "# " starts a comment.
		assertTrue(file.with(List.of(flying), List.of("stop: right there"))
			.contains("flying-player: 'stop: right there'"));
		assertTrue(file.with(List.of(flying), List.of("no # really"))
			.contains("flying-player: 'no # really'"));
		// An empty value is quoted rather than left as nothing at all, which would
		// read as null and not as an empty message.
		assertTrue(file.with(List.of(flying), List.of("")).contains("flying-player: ''"));
	}

	@Test
	@DisplayName("Two changes at once both land, and neither moves the other's place")
	void twoAtOnce() {
		ConfigFile file = paper();
		ConfigFile.Setting version = one(file.settings(), "_version");
		ConfigFile.Setting joins = one(file.settings(), "misc.max-joins-per-tick");
		// The first is near the top and the second near the bottom, and the first
		// gets much longer: applied front to back, the second would land at an
		// offset that is no longer where it was read from.
		String after = file.with(List.of(version, joins), List.of("1000000", "9"));
		assertTrue(after.contains("_version: 1000000"), after);
		assertTrue(after.contains("max-joins-per-tick: 9"), after);
	}

	@Test
	@DisplayName("A .properties config reads the same way, comment and all")
	void properties() {
		// FerriteCore's, which is the shape every Fabric mod's properties file has:
		// a line of documentation, then the key.
		String text = "# Replace the blockstate neighbor table\n"
			+ "replaceNeighborLookup = true\n"
			+ "# Do not store the properties of a state explicitly\n"
			+ "# Requires replaceNeighborLookup to be enabled\n"
			+ "replacePropertyMap = false\n"
			+ "\n"
			+ "cacheSize = 2048\n";
		ConfigFile file = ConfigFile.parse(Path.of("config", "ferritecore.mixin.properties"), text);
		assertEquals(ConfigFile.Format.PROPERTIES, file.format());

		ConfigFile.Setting first = one(file.settings(), "replaceNeighborLookup");
		assertEquals("true", first.value());
		assertEquals("Replace the blockstate neighbor table", first.comment());
		// Two comment lines are one sentence, because that is what they are: prose
		// wrapped at eighty columns, not two separate notes.
		assertEquals("Do not store the properties of a state explicitly"
			+ " Requires replaceNeighborLookup to be enabled",
			one(file.settings(), "replacePropertyMap").comment());
		// A blank line ends a comment: this key has none, rather than the one from
		// two keys above it.
		assertEquals("", one(file.settings(), "cacheSize").comment());
		assertEquals(ConfigFile.Sort.NUMBER, one(file.settings(), "cacheSize").sort());

		String after = file.with(List.of(first), List.of("false"));
		assertTrue(after.contains("replaceNeighborLookup = false"), after);
		assertTrue(after.startsWith("# Replace the blockstate neighbor table\n"), after);
		assertEquals(text.length() + 1, after.length());
	}

	@Test
	@DisplayName("Written back with no change at all, the file is byte for byte the same")
	void roundTrip() {
		// The property everything else rests on, and the one the whole design was
		// chosen for. Every value is written back as it was read, and the result
		// has to be the same file — not nearly, not apart from the line endings.
		ConfigFile file = paper();
		List<ConfigFile.Setting> settings = file.settings().stream()
			.filter(each -> each.sort().editable()).toList();
		List<String> values = settings.stream().map(ConfigFile.Setting::value).toList();
		assertEquals(PAPER, file.with(settings, values));
	}

	/**
	 * The one thing the made-up file in this test did not have, and the real one did.
	 *
	 * Paper wraps a long message over two indented lines. The reader hands that
	 * back as one folded string, and writing it into the place the two lines
	 * occupied would rewrap the file — a change nobody asked for, in the file this
	 * is meant to leave alone. It was found by running this reader over the actual
	 * paper-global.yml on this machine, which is the only way it could have been.
	 */
	@Test
	@DisplayName("A value written over two lines is shown and not edited")
	void wrappedValuesAreNotEdited() {
		ConfigFile.Setting wrapped = one(paper().settings(), "misc.long-message");
		assertEquals(ConfigFile.Sort.BLOCK, wrapped.sort());
		assertFalse(wrapped.sort().editable());
		// The value itself is still read, so the row can show what is in there.
		assertTrue(wrapped.value().startsWith("This is a message that Paper wrapped"));
		assertTrue(wrapped.value().contains("because it was longer"));
	}

	@Test
	@DisplayName("A properties value carried onto the next line is left alone too")
	void carriedPropertiesValues() {
		ConfigFile file = ConfigFile.parse(Path.of("config", "x.properties"),
			"# a note\nmessage = one long value that \\\n  carries on\nplain = 2\n");
		assertEquals(ConfigFile.Sort.BLOCK, one(file.settings(), "message").sort());
		assertEquals(ConfigFile.Sort.NUMBER, one(file.settings(), "plain").sort());
	}

	@Test
	@DisplayName("A file that will not parse gives no settings rather than a wrong one")
	void brokenFile() {
		ConfigFile file = ConfigFile.parse(Path.of("plugins", "X", "config.yml"),
			"settings:\n  broken: [1, 2\n  more: yes\n");
		assertTrue(file.settings().isEmpty());
		// And the text is still there, so it can be shown to whoever has to fix it.
		assertFalse(file.text().isBlank());
	}

	@Test
	@DisplayName("A setting from another file is refused rather than applied at that offset")
	void refusesForeignSettings() {
		ConfigFile file = ConfigFile.parse(Path.of("a.yml"), "a: 1\n");
		ConfigFile.Setting elsewhere =
			new ConfigFile.Setting("a", "1", "", ConfigFile.Sort.NUMBER, 900, 902);
		assertThrows(IllegalArgumentException.class,
			() -> file.with(List.of(elsewhere), List.of("2")));
	}

	private static ConfigFile.Setting one(List<ConfigFile.Setting> found, String path) {
		for (ConfigFile.Setting each : found) {
			if (each.path().equals(path)) return each;
		}
		assertNotNull(null, "no setting called " + path + " in " + found.size() + " read");
		return null;
	}

	private static int count(String text, char ch) {
		int found = 0;
		for (int at = 0; at < text.length(); at++) {
			if (text.charAt(at) == ch) found++;
		}
		return found;
	}
}
