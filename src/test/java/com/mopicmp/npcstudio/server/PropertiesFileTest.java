package com.mopicmp.npcstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import java.io.StringReader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That editing one setting does not rewrite somebody's file.
 *
 * The first test is the one the rest depend on: a file read and written with no
 * change at all must come back byte for byte. It sounds like a formality and it
 * is the entire promise — the ordinary way to do this loses every comment,
 * reorders every key and stamps a date at the top, and the file it does that to
 * is the one the owner has their own notes in.
 *
 * The same promise, on a much harder format, is what the configuration editor
 * later stands on. This is where it is cheap to establish.
 */
class PropertiesFileTest {

	/** A file with the things a real one has: comments, blanks, and a stamp. */
	private static final String SAMPLE = """
		#Minecraft server properties
		#Sat Aug 30 12:00:00 MSK 2026
		enable-jmx-monitoring=false
		rcon.port=25575
		level-seed=
		gamemode=survival
		# our own note about why this is off
		enable-command-block=false

		motd=A Minecraft Server
		""";

	@Test
	@DisplayName("Read and written back unchanged, it is the same file")
	void roundTrip() {
		assertEquals(SAMPLE, PropertiesFile.parse(SAMPLE).text());
	}

	@Test
	@DisplayName("Windows line endings survive, and so does a missing final newline")
	void endings() {
		String crlf = "a=1\r\n#note\r\nb=2";
		assertEquals(crlf, PropertiesFile.parse(crlf).text());

		String noNewline = "a=1\nb=2";
		assertEquals(noNewline, PropertiesFile.parse(noNewline).text());
	}

	@Test
	@DisplayName("A changed value stays where it was, and the comments stay with it")
	void inPlace() {
		PropertiesFile file = PropertiesFile.parse(SAMPLE);
		file.set("gamemode", "creative");

		String written = file.text();
		assertTrue(written.contains("gamemode=creative"));
		assertFalse(written.contains("gamemode=survival"));
		assertTrue(written.contains("# our own note about why this is off"));
		assertTrue(written.contains("#Minecraft server properties"));

		// Position, not merely presence: the line that followed it still does.
		int gamemode = written.indexOf("gamemode=creative");
		int seed = written.indexOf("level-seed=");
		int note = written.indexOf("# our own note");
		assertTrue(seed < gamemode && gamemode < note);
	}

	@Test
	@DisplayName("A key that was not there is added, and nothing else moves")
	void added() {
		PropertiesFile file = PropertiesFile.parse(SAMPLE);
		file.set("enable-rcon", "true");
		String written = file.text();
		assertTrue(written.startsWith(SAMPLE.substring(0, SAMPLE.indexOf("motd"))));
		assertEquals("true", PropertiesFile.parse(written).get("enable-rcon"));
	}

	@Test
	@DisplayName("A value that is not ASCII is escaped, so both readings of the file agree")
	void unicode() throws Exception {
		PropertiesFile file = PropertiesFile.parse(SAMPLE);
		file.set("motd", "Привет, мир");
		String written = file.text();

		// Written as escapes, so the bytes are ASCII whichever encoding the game
		// reads the file with.
		assertTrue(written.contains("motd=\\u041f"), "expected escapes, got: " + written);
		for (char c : written.toCharArray()) {
			assertTrue(c < 128, "the file should be plain ASCII, found: " + c);
		}

		// And it is the same text again, both to us and to the loader the game uses.
		assertEquals("Привет, мир", PropertiesFile.parse(written).get("motd"));
		Properties byJava = new Properties();
		byJava.load(new StringReader(written));
		assertEquals("Привет, мир", byJava.getProperty("motd"));
	}

	@Test
	@DisplayName("The separators a hand-edited file may use are all read")
	void separators() {
		PropertiesFile file = PropertiesFile.parse("""
			a=1
			b = 2
			c:3
			d 4
			""");
		assertEquals("1", file.get("a"));
		assertEquals("2", file.get("b"));
		assertEquals("3", file.get("c"));
		assertEquals("4", file.get("d"));
	}

	@Test
	@DisplayName("A value continued across lines is one entry, not two keys")
	void continued() {
		PropertiesFile file = PropertiesFile.parse("""
			motd=first \\
			second
			gamemode=survival
			""");
		assertEquals("first second", file.get("motd"));
		assertEquals("survival", file.get("gamemode"));
		assertFalse(file.has("second"));
	}

	@Test
	@DisplayName("Missing, blank and malformed values do not become exceptions")
	void forgiving() {
		PropertiesFile file = PropertiesFile.parse("""
			level-seed=
			max-players=twenty
			pvp=yes
			""");
		assertEquals("", file.get("level-seed"));
		assertEquals(null, file.get("nothing-like-this"));
		assertEquals(20, file.getInt("max-players", 20));
		assertEquals(25565, file.getInt("nothing-like-this", 25565));
		// "yes" is not how this format writes true, and guessing that it is would
		// be the kind of helpfulness that turns a typo into a changed setting.
		assertTrue(file.getBoolean("pvp", true));
		assertFalse(file.getBoolean("pvp", false));
	}

	@Test
	@DisplayName("Removing takes the entry and leaves the file otherwise alone")
	void removed() {
		PropertiesFile file = PropertiesFile.parse(SAMPLE);
		file.remove("gamemode");
		String written = file.text();
		assertFalse(written.contains("gamemode"));
		assertTrue(written.contains("# our own note about why this is off"));
		assertTrue(written.contains("level-seed="));
	}

	@Test
	@DisplayName("An empty file can be written into and is still a properties file")
	void fromNothing() {
		PropertiesFile file = PropertiesFile.empty();
		file.set("server-port", "25565");
		file.set("motd", "hello");
		assertEquals("server-port=25565\nmotd=hello", file.text());
		assertEquals("25565", PropertiesFile.parse(file.text()).get("server-port"));
	}
}
