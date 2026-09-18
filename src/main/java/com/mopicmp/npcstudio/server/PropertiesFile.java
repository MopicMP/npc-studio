package com.mopicmp.npcstudio.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code server.properties}, edited without being rewritten.
 *
 * The obvious way to change one setting is to load the file into
 * {@link java.util.Properties}, put the value, and store it back. That loses
 * every comment, reorders every key, and stamps a date at the top. On the file
 * the whole feature exists to edit — the one file every server owner has opened
 * by hand, with their own notes in it — that is not a saved setting, it is a
 * file they now have to read again from scratch.
 *
 * So the file is held as its own lines. Lines nobody touched are written back
 * byte for byte, including their line endings and whether the last one had a
 * newline; a changed key is rewritten in place, keeping its position. The test
 * for this is a round trip with no edit at all, compared byte for byte, because
 * that is the property that has to hold before any editing is worth doing.
 *
 * The same reasoning applies with more force to plugin configuration, which is
 * where the documentation lives entirely in comments — that is a later stage and
 * a different format, but this is the same promise in miniature.
 */
public final class PropertiesFile {

	/** A logical entry: the lines it occupies, and what it means. */
	private record Entry(int from, int to, String key) {
	}

	private final List<String> lines;
	private final String separator;
	private final boolean trailingNewline;
	private final List<Entry> entries = new ArrayList<>();

	private PropertiesFile(List<String> lines, String separator, boolean trailingNewline) {
		this.lines = lines;
		this.separator = separator;
		this.trailingNewline = trailingNewline;
		index();
	}

	public static PropertiesFile read(Path file) throws IOException {
		return parse(Files.readString(file, StandardCharsets.UTF_8));
	}

	public static PropertiesFile parse(String text) {
		// Split keeping nothing: the separator and the final newline are recorded
		// separately, so that writing puts back exactly what was there.
		String separator = text.contains("\r\n") ? "\r\n" : "\n";
		boolean trailing = text.endsWith("\n");
		String body = trailing
			? text.substring(0, text.length() - separator.length())
			: text;
		List<String> lines = new ArrayList<>(List.of(body.split("\r\n|\n", -1)));
		if (text.isEmpty()) {
			lines.clear();
		}
		return new PropertiesFile(lines, separator, trailing);
	}

	/** An empty file, for a server whose properties are being written from nothing. */
	public static PropertiesFile empty() {
		return parse("");
	}

	private void index() {
		entries.clear();
		int at = 0;
		while (at < lines.size()) {
			String line = lines.get(at);
			String trimmed = line.stripLeading();
			if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
				at++;
				continue;
			}
			// A value may be continued on the next line by ending this one in an
			// odd number of backslashes. Minecraft never writes that, but a person
			// editing by hand can, and an entry read as two would put the second
			// half in the map as a key of its own.
			int last = at;
			while (continued(lines.get(last)) && last + 1 < lines.size()) last++;
			entries.add(new Entry(at, last, keyOf(joined(at, last))));
			at = last + 1;
		}
	}

	private static boolean continued(String line) {
		int slashes = 0;
		for (int at = line.length() - 1; at >= 0 && line.charAt(at) == '\\'; at--) slashes++;
		return slashes % 2 == 1;
	}

	private String joined(int from, int to) {
		if (from == to) return lines.get(from);
		StringBuilder whole = new StringBuilder();
		for (int at = from; at <= to; at++) {
			String line = lines.get(at);
			if (at < to) line = line.substring(0, line.length() - 1);
			whole.append(at == from ? line : line.stripLeading());
		}
		return whole.toString();
	}

	/**
	 * Where the key ends: the first unescaped {@code =}, {@code :} or space.
	 *
	 * All three are separators in this format even though Minecraft only ever
	 * writes the first. Reading the other two costs four lines and stops a
	 * hand-edited file from quietly having no such key.
	 */
	private static int separatorAt(String line) {
		boolean escaped = false;
		for (int at = 0; at < line.length(); at++) {
			char c = line.charAt(at);
			if (escaped) {
				escaped = false;
			} else if (c == '\\') {
				escaped = true;
			} else if (c == '=' || c == ':' || c == ' ' || c == '\t' || c == '\f') {
				return at;
			}
		}
		return -1;
	}

	private static String keyOf(String line) {
		String text = line.stripLeading();
		int at = separatorAt(text);
		return unescape(at < 0 ? text : text.substring(0, at));
	}

	private static String valueOf(String line) {
		String text = line.stripLeading();
		int at = separatorAt(text);
		if (at < 0) return "";
		String rest = text.substring(at);
		// Skip the run of whitespace, then at most one real separator, then more
		// whitespace: "key = value", "key=value" and "key value" all mean the same.
		int cut = 0;
		while (cut < rest.length() && isBlank(rest.charAt(cut))) cut++;
		if (cut < rest.length() && (rest.charAt(cut) == '=' || rest.charAt(cut) == ':')) cut++;
		while (cut < rest.length() && isBlank(rest.charAt(cut))) cut++;
		return unescape(rest.substring(cut));
	}

	private static boolean isBlank(char c) {
		return c == ' ' || c == '\t' || c == '\f';
	}

	public boolean has(String key) {
		return find(key) != null;
	}

	public String get(String key) {
		Entry entry = find(key);
		return entry == null ? null : valueOf(joined(entry.from(), entry.to()));
	}

	public String getOr(String key, String fallback) {
		String value = get(key);
		return value == null ? fallback : value;
	}

	public int getInt(String key, int fallback) {
		try {
			String value = get(key);
			return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
		} catch (NumberFormatException notANumber) {
			return fallback;
		}
	}

	public boolean getBoolean(String key, boolean fallback) {
		String value = get(key);
		if (value == null) return fallback;
		return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
			case "true" -> true;
			case "false" -> false;
			default -> fallback;
		};
	}

	/** Every key and value, in the order the file has them. */
	public Map<String, String> all() {
		Map<String, String> read = new LinkedHashMap<>();
		for (Entry entry : entries) {
			read.put(entry.key(), valueOf(joined(entry.from(), entry.to())));
		}
		return read;
	}

	/**
	 * Change a value in place, or add the key at the end if it is not there.
	 *
	 * A continued entry collapses onto one line, because the alternative is
	 * deciding where to break a value the person did not write.
	 */
	public void set(String key, String value) {
		String written = escapeKey(key) + "=" + escapeValue(value);
		Entry entry = find(key);
		if (entry == null) {
			lines.add(written);
		} else {
			for (int at = entry.to(); at > entry.from(); at--) lines.remove(at);
			lines.set(entry.from(), written);
		}
		index();
	}

	public void remove(String key) {
		Entry entry = find(key);
		if (entry == null) return;
		for (int at = entry.to(); at >= entry.from(); at--) lines.remove(at);
		index();
	}

	private Entry find(String key) {
		for (Entry entry : entries) {
			if (entry.key().equals(key)) return entry;
		}
		return null;
	}

	public String text() {
		if (lines.isEmpty()) return "";
		String body = String.join(separator, lines);
		return trailingNewline ? body + separator : body;
	}

	public void write(Path file) throws IOException {
		Path parent = file.getParent();
		if (parent != null) Files.createDirectories(parent);
		Files.writeString(file, text(), StandardCharsets.UTF_8);
	}

	private static String escapeKey(String key) {
		StringBuilder out = new StringBuilder();
		for (char c : key.toCharArray()) {
			if (c == '=' || c == ':' || c == ' ' || c == '\t' || c == '\\' || c == '#' || c == '!') {
				out.append('\\');
			}
			out.append(escapedChar(c));
		}
		return out.toString();
	}

	/**
	 * Values are written in plain ASCII, anything else as {@code \\uXXXX}.
	 *
	 * Not decoration: this file has been read as Latin-1 by some versions of the
	 * game and as UTF-8 by others, and a Russian message of the day written as
	 * raw bytes comes out as mojibake under the wrong one. The escape is
	 * understood by the loader in both, so it is the one spelling that is right
	 * whichever version ends up reading the file.
	 */
	private static String escapeValue(String value) {
		StringBuilder out = new StringBuilder();
		for (int at = 0; at < value.length(); at++) {
			char c = value.charAt(at);
			if (c == '\\') {
				out.append("\\\\");
			} else if (c == '\n') {
				out.append("\\n");
			} else if (c == '\r') {
				out.append("\\r");
			} else if (c == '\t') {
				out.append("\\t");
			} else if (c == ' ' && at == 0) {
				out.append("\\ ");
			} else if (c < 0x20 || c > 0x7e) {
				out.append(String.format("\\u%04x", (int) c));
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}

	private static String escapedChar(char c) {
		return switch (c) {
			case '\n' -> "\\n";
			case '\r' -> "\\r";
			case '\t' -> "\\t";
			default -> c < 0x20 || c > 0x7e ? String.format("\\u%04x", (int) c) : String.valueOf(c);
		};
	}

	private static String unescape(String text) {
		StringBuilder out = new StringBuilder(text.length());
		for (int at = 0; at < text.length(); at++) {
			char c = text.charAt(at);
			if (c != '\\' || at + 1 >= text.length()) {
				out.append(c);
				continue;
			}
			char next = text.charAt(++at);
			switch (next) {
				case 'n' -> out.append('\n');
				case 'r' -> out.append('\r');
				case 't' -> out.append('\t');
				case 'f' -> out.append('\f');
				case 'u' -> {
					if (at + 4 < text.length()) {
						try {
							out.append((char) Integer.parseInt(text.substring(at + 1, at + 5), 16));
							at += 4;
						} catch (NumberFormatException notHex) {
							out.append('u');
						}
					} else {
						out.append('u');
					}
				}
				default -> out.append(next);
			}
		}
		return out.toString();
	}
}
