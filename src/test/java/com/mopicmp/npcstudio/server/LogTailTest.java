package com.mopicmp.npcstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading the console of a server that is writing it as we read.
 *
 * All four cases here happen in ordinary use rather than in theory: a line
 * caught half-written, a character split between two writes, a log replaced by
 * a restart, and a file that does not exist yet because the server has not
 * started. Each of them, got wrong, shows up as a console panel that duplicates
 * a line, prints a question mark, repeats an old session or stops updating —
 * and all four look like the same complaint from the outside.
 */
class LogTailTest {

	@TempDir
	Path folder;

	private static void append(Path file, String text) throws IOException {
		Files.writeString(file, text, StandardCharsets.UTF_8,
			StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	@Test
	@DisplayName("Only whole lines are given out, and only once")
	void wholeLines() throws IOException {
		Path log = folder.resolve("console.log");
		LogTail tail = LogTail.whole(log);

		append(log, "[12:00:00] Starting server\n[12:00:01] Loading world\n");
		assertEquals(List.of("[12:00:00] Starting server", "[12:00:01] Loading world"),
			tail.poll());

		// Nothing new is nothing, not the last line again.
		assertEquals(List.of(), tail.poll());

		// A line still being written is held back until its newline arrives.
		append(log, "[12:00:02] Done (5.1");
		assertEquals(List.of(), tail.poll());
		append(log, "23s)! For help, type \"help\"\n");
		assertEquals(List.of("[12:00:02] Done (5.123s)! For help, type \"help\""), tail.poll());
	}

	@Test
	@DisplayName("A character split between two writes is not two broken ones")
	void splitCharacter() throws IOException {
		Path log = folder.resolve("console.log");
		LogTail tail = LogTail.whole(log);

		byte[] bytes = "Привет\n".getBytes(StandardCharsets.UTF_8);
		// Cut in the middle of the first letter, which is two bytes in UTF-8.
		Files.write(log, java.util.Arrays.copyOfRange(bytes, 0, 1),
			StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		assertEquals(List.of(), tail.poll());

		Files.write(log, java.util.Arrays.copyOfRange(bytes, 1, bytes.length),
			StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		assertEquals(List.of("Привет"), tail.poll());
	}

	@Test
	@DisplayName("Windows line endings do not leave a carriage return on every line")
	void endings() throws IOException {
		Path log = folder.resolve("console.log");
		LogTail tail = LogTail.whole(log);
		append(log, "one\r\ntwo\r\n");
		assertEquals(List.of("one", "two"), tail.poll());
	}

	@Test
	@DisplayName("A log replaced by a restart is read from its beginning")
	void restarted() throws IOException {
		Path log = folder.resolve("console.log");
		LogTail tail = LogTail.whole(log);
		append(log, "old session line one\nold session line two\n");
		assertEquals(2, tail.poll().size());

		// A shorter file at the same path is a different file. Carrying on from
		// the old position would start in the middle of a line.
		Files.writeString(log, "new\n", StandardCharsets.UTF_8);
		assertEquals(List.of("new"), tail.poll());
	}

	@Test
	@DisplayName("A file that is not there yet is not an error")
	void missing() throws IOException {
		LogTail tail = LogTail.whole(folder.resolve("not-started-yet.log"));
		assertEquals(List.of(), tail.poll());
		assertEquals(0, tail.position());
	}

	@Test
	@DisplayName("Picking up a long log shows its tail, not the whole history")
	void recent() throws IOException {
		Path log = folder.resolve("console.log");
		StringBuilder history = new StringBuilder();
		for (int line = 0; line < 20_000; line++) {
			history.append("[12:00:00] line ").append(line).append('\n');
		}
		Files.writeString(log, history.toString(), StandardCharsets.UTF_8);

		LogTail tail = LogTail.recent(log);
		List<String> shown = tail.poll();
		assertTrue(shown.size() < 20_000, "the whole history was replayed");
		assertTrue(shown.size() > 100, "almost nothing was shown: " + shown.size());

		// The last line written is the last line shown, and no line is a fragment.
		assertEquals("[12:00:00] line 19999", shown.getLast());
		assertTrue(shown.getFirst().startsWith("[12:00:00] line "),
			"first line looks cut: " + shown.getFirst());

		// And it carries on from there.
		append(log, "[12:00:01] after\n");
		assertEquals(List.of("[12:00:01] after"), tail.poll());
	}
}
