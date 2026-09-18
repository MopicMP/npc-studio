package com.mopicmp.npcstudio.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The console of a server we are not attached to.
 *
 * The local server is started detached, so its output cannot come to us down a
 * pipe: the game is allowed to close and come back while the server keeps
 * running, and a pipe does not survive that. It goes to a file instead, and this
 * reads whatever has appeared since the last look.
 *
 * Three things it has to get right, all of which happen in ordinary use:
 *
 * <ul>
 * <li>a line that is still being written arrives cut in half, and must not be
 * shown until it is whole — otherwise the console shows a fragment and then the
 * same line again;</li>
 * <li>a UTF-8 character can be split across two writes, so decoding has to
 * happen on complete lines rather than on each chunk of bytes;</li>
 * <li>the file can shrink, when a server restarts into a fresh log. Reading on
 * from the old position would then produce nonsense from the middle of a line,
 * so a shrink means starting over.</li>
 * </ul>
 */
public final class LogTail {

	/**
	 * How far back to start when picking up an existing log.
	 *
	 * Reopening the manager should show the recent console, not replay a session
	 * from three days ago into a scrolling panel.
	 */
	private static final long BACKLOG = 64 * 1024;

	/** A line this long without a newline is treated as a line anyway. */
	private static final int MAX_LINE = 64 * 1024;

	private final Path file;
	private long position;
	private final ByteArrayOutputStream partial = new ByteArrayOutputStream();

	private LogTail(Path file, long position) {
		this.file = file;
		this.position = position;
	}

	/** Everything the file already holds, from the beginning. */
	public static LogTail whole(Path file) {
		return new LogTail(file, 0);
	}

	/**
	 * The tail of what is already there, then whatever follows.
	 *
	 * The first partial line after the starting point is dropped rather than
	 * shown: starting a fixed number of bytes back almost always lands in the
	 * middle of one.
	 */
	public static LogTail recent(Path file) {
		long size;
		try {
			size = Files.exists(file) ? Files.size(file) : 0;
		} catch (IOException unreadable) {
			size = 0;
		}
		if (size <= BACKLOG) return new LogTail(file, 0);
		LogTail tail = new LogTail(file, size - BACKLOG);
		tail.dropFirstLine = true;
		return tail;
	}

	private boolean dropFirstLine;

	/** New whole lines since the last call. Never null; empty is the usual answer. */
	public List<String> poll() throws IOException {
		List<String> found = new ArrayList<>();
		if (!Files.exists(file)) return found;

		long size = Files.size(file);
		if (size < position) {
			// The file was replaced or truncated. Anything held back belongs to a
			// log that no longer exists.
			position = 0;
			partial.reset();
		}
		if (size == position) return found;

		try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
			channel.position(position);
			ByteBuffer buffer = ByteBuffer.allocate(16 * 1024);
			while (channel.read(buffer) > 0) {
				buffer.flip();
				while (buffer.hasRemaining()) {
					byte b = buffer.get();
					position++;
					if (b == '\n') {
						found.add(decode(partial.toByteArray()));
						partial.reset();
					} else if (b != '\r') {
						partial.write(b);
						if (partial.size() >= MAX_LINE) {
							found.add(decode(partial.toByteArray()));
							partial.reset();
						}
					}
				}
				buffer.clear();
			}
		}
		if (dropFirstLine && !found.isEmpty()) {
			found.removeFirst();
			dropFirstLine = false;
		}
		return found;
	}

	/** How far into the file this has read. */
	public long position() {
		return position;
	}

	/**
	 * Bytes to text, never throwing.
	 *
	 * A console panel that stops updating because one line was not valid UTF-8 is
	 * worse than a console panel with one damaged line in it — and the lines most
	 * likely to be damaged are the ones from a crash, which are the ones somebody
	 * is reading the console for.
	 */
	private static String decode(byte[] bytes) {
		try {
			return StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPLACE)
				.onUnmappableCharacter(CodingErrorAction.REPLACE)
				.decode(ByteBuffer.wrap(bytes))
				.toString();
		} catch (CharacterCodingException impossible) {
			// Both actions are REPLACE, so this cannot happen; if it ever does,
			// the bytes are still worth more than an exception.
			return new String(bytes, StandardCharsets.ISO_8859_1);
		}
	}
}
