package com.mopicmp.npcstudio.client.scene;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * How long a piece of music is, without listening to the whole of it.
 *
 * <h2>Why this is not "decode it and count"</h2>
 *
 * Because that is the length of the track in work as well as in seconds. A four
 * minute piece takes four minutes of decoding to measure that way, on the thread
 * that asked, and the answer is wanted the moment a list is opened — for every
 * file in the folder at once.
 *
 * An Ogg file already knows. Every page in it carries a granule position, which
 * for Vorbis is the number of samples decoded by the end of that page, and the
 * last page of the stream therefore carries the total. Divide by the sample rate,
 * which is in the identification header at the very front, and that is the length.
 * Two small reads: the first few hundred bytes and the last few thousand.
 *
 * <h2>What it does when the file is not what it says</h2>
 *
 * Answers nothing. A length is a nicety — it goes next to a name in a list and
 * draws a bar on a ruler — and a file that will not parse is still a file the
 * decoder may play perfectly well. Refusing to show it because we could not
 * measure it would be the measurement deciding what exists.
 */
public final class Ogg {

	/** Every Ogg page begins with these four bytes. */
	private static final byte[] CAPTURE = { 'O', 'g', 'g', 'S' };

	/** How much of the end to look through for the last page. */
	private static final int TAIL = 64 * 1024;

	/** How much of the front to look through for the identification header. */
	private static final int HEAD = 8 * 1024;

	private Ogg() { }

	/**
	 * The length in seconds, or nought when it cannot be worked out.
	 *
	 * @param file an ogg vorbis file
	 */
	public static double seconds(Path file) {
		try (SeekableByteChannel channel = Files.newByteChannel(file)) {
			long size = channel.size();
			if (size < 64) return 0;

			int rate = sampleRate(read(channel, 0, (int) Math.min(HEAD, size)));
			if (rate <= 0) return 0;

			long from = Math.max(0, size - TAIL);
			long granule = lastGranule(read(channel, from, (int) (size - from)));
			if (granule <= 0) return 0;

			return granule / (double) rate;
		} catch (IOException | RuntimeException unreadable) {
			return 0;
		}
	}

	private static ByteBuffer read(SeekableByteChannel channel, long from, int many)
			throws IOException {
		ByteBuffer bytes = ByteBuffer.allocate(many).order(ByteOrder.LITTLE_ENDIAN);
		channel.position(from);
		while (bytes.hasRemaining() && channel.read(bytes) > 0) {
			// Read until it is full or the file ends, because a channel is allowed to
			// hand over less than was asked for and usually does on the first call.
		}
		bytes.flip();
		return bytes;
	}

	/**
	 * The sample rate, out of the Vorbis identification header.
	 *
	 * That header is the first packet of the stream and begins with the byte one
	 * followed by the word "vorbis". After it come the version as four bytes and the
	 * channel count as one, and then the rate as four — little endian, as everything
	 * in Vorbis is.
	 *
	 * Searched for rather than read at a fixed offset, because the page header in
	 * front of it is a variable length: it carries one byte per segment and how many
	 * segments there are is itself a byte in the header.
	 */
	static int sampleRate(ByteBuffer bytes) {
		byte[] mark = { 1, 'v', 'o', 'r', 'b', 'i', 's' };
		int at = indexOf(bytes, mark, 0);
		if (at < 0 || at + 7 + 9 > bytes.limit()) return 0;
		return bytes.getInt(at + 7 + 4 + 1);
	}

	/**
	 * The granule position of the last page in the buffer.
	 *
	 * Walked forwards from the first page found rather than searched backwards,
	 * because "OggS" is four ordinary bytes and appears inside compressed audio by
	 * chance often enough to matter. Every page is taken and the last one wins, so
	 * a stray match earlier costs a wrong reading that is then overwritten by the
	 * real last page — and the real last page is the one nearest the end.
	 */
	static long lastGranule(ByteBuffer bytes) {
		long last = 0;
		int at = 0;
		while (at >= 0 && at + 27 <= bytes.limit()) {
			at = indexOf(bytes, CAPTURE, at);
			if (at < 0 || at + 27 > bytes.limit()) break;
			long granule = bytes.getLong(at + 6);
			// Minus one is what a page carrying no completed packet reports, and it
			// must not be read as an enormous length.
			if (granule > 0 && granule != -1L) last = granule;
			at += 4;
		}
		return last;
	}

	private static int indexOf(ByteBuffer bytes, byte[] mark, int from) {
		outer:
		for (int at = Math.max(0, from); at + mark.length <= bytes.limit(); at++) {
			for (int i = 0; i < mark.length; i++) {
				if (bytes.get(at + i) != mark[i]) continue outer;
			}
			return at;
		}
		return -1;
	}

	/** A length as a person reads one: minutes and seconds. */
	public static String said(double seconds) {
		if (!(seconds > 0)) return "?";
		int whole = (int) Math.round(seconds);
		return String.format("%d:%02d", whole / 60, whole % 60);
	}
}
