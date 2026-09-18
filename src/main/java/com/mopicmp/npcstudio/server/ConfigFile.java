package com.mopicmp.npcstudio.server;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;

/**
 * One configuration file, read as settings and written back by patching it.
 *
 * <b>The file is never rewritten.</b> Every setting knows the exact stretch of
 * characters its value occupies, and saving replaces those characters and nothing
 * else. Comments, blank lines, key order, indentation, quoting style, a stray
 * CRLF in the middle of an LF file — all of it survives, and not because anything
 * here was careful with it: because nothing here touched it.
 *
 * <p>That is the whole design, and it was chosen by measurement rather than by
 * preference. Reading a real {@code paper-global.yml} into a map and dumping the
 * map back gives a file fourteen comments shorter; in a plugin config the
 * comments are the documentation, and losing them once makes the file worse than
 * it was before this mod existed. Even the library's own comment-keeping writer,
 * which is honest work, turned that file's fifteen CRLF endings into LF. A
 * substring replacement cannot do either.
 *
 * <p>What is given up: this cannot add a key that is not there, and cannot edit
 * anything that is not a single value — a list stays a list and is shown as one.
 * Both are the right trade. A key absent from a plugin's config is a key that
 * plugin is not reading, and inventing where to put it is guessing at a format we
 * are deliberately not rewriting.
 */
public final class ConfigFile {

	/** What a value looks like, which decides what it is edited with. */
	public enum Sort {
		FLAG,
		NUMBER,
		TEXT,
		/** A list. Shown, not edited here — see the note on this class. */
		LIST,
		/**
		 * A value written across more than one line.
		 *
		 * Found on the first real file this met: Paper wraps its longer messages
		 * over two indented lines, and the reader hands back one folded string.
		 * Writing that string back into the place the two lines occupied would put
		 * the file's own wrapping back as a single long line — a change nobody
		 * asked for, in a file this is supposed to leave alone. So it is shown and
		 * not edited, like a list.
		 */
		BLOCK;

		/** Whether this can be changed here at all. */
		public boolean editable() {
			return this != LIST && this != BLOCK;
		}
	}

	/**
	 * One editable value.
	 *
	 * @param path    the key, dotted: {@code settings.bungeecord}
	 * @param comment what was written above it, which is this format's only
	 *                documentation and so is the label a person reads
	 * @param from    where the value starts in the file's text
	 * @param to      where it ends. {@code [from, to)} is exactly the value and
	 *                nothing around it
	 */
	public record Setting(String path, String value, String comment, Sort sort, int from, int to) {

		/** The last part of the path, which is what a row is named by. */
		public String name() {
			int dot = path.lastIndexOf('.');
			return dot < 0 ? path : path.substring(dot + 1);
		}

		/** The path above it, which groups rows under a heading. */
		public String group() {
			int dot = path.lastIndexOf('.');
			return dot < 0 ? "" : path.substring(0, dot);
		}

		public boolean flag() {
			return sort == Sort.FLAG;
		}

		public boolean on() {
			return value.equalsIgnoreCase("true");
		}
	}

	/** Which reader a file gets, decided by its name and nothing else. */
	public enum Format {
		YAML,
		PROPERTIES,
		/** Read and shown, not taken apart: JSON, TOML, anything else. */
		PLAIN;

		public static Format of(String name) {
			String lower = name.toLowerCase(Locale.ROOT);
			if (lower.endsWith(".yml") || lower.endsWith(".yaml")) return YAML;
			if (lower.endsWith(".properties")) return PROPERTIES;
			return PLAIN;
		}
	}

	/** Past this it is not a configuration file, and reading it is a stall. */
	public static final long MOST = 4L * 1024 * 1024;

	private final Path path;
	private final Format format;
	private final String text;
	private final List<Setting> settings;

	private ConfigFile(Path path, Format format, String text, List<Setting> settings) {
		this.path = path;
		this.format = format;
		this.text = text;
		this.settings = settings;
	}

	public Path path() {
		return path;
	}

	public Format format() {
		return format;
	}

	public String text() {
		return text;
	}

	public List<Setting> settings() {
		return settings;
	}

	public static ConfigFile read(Path file) throws IOException {
		if (Files.size(file) > MOST) {
			throw new IOException("That file is larger than " + MOST / (1024 * 1024)
				+ " MB, which is not a configuration file any more.");
		}
		return parse(file, Files.readString(file, StandardCharsets.UTF_8));
	}

	public static ConfigFile parse(Path file, String text) {
		Format format = Format.of(file.getFileName().toString());
		List<Setting> found = switch (format) {
			case YAML -> yaml(text);
			case PROPERTIES -> properties(text);
			case PLAIN -> List.of();
		};
		return new ConfigFile(file, format, text, found);
	}

	/**
	 * The text this file would have with one value changed.
	 *
	 * Returns the whole text rather than writing, so that several changes can be
	 * applied to one file and the result looked at before anything is saved.
	 * Applying them from the end backwards keeps every earlier span valid; applied
	 * forwards, the second edit would land at an offset the first had moved.
	 */
	public String with(List<Setting> changed, List<String> values) {
		if (changed.size() != values.size()) {
			throw new IllegalArgumentException("A value for each setting, no more and no fewer");
		}
		record Edit(int from, int to, String value) {
		}
		List<Edit> edits = new ArrayList<>();
		for (int at = 0; at < changed.size(); at++) {
			Setting setting = changed.get(at);
			if (setting.from() < 0 || setting.to() > text.length() || setting.from() > setting.to()) {
				throw new IllegalArgumentException("That setting does not belong to this file");
			}
			edits.add(new Edit(setting.from(), setting.to(), spell(setting, values.get(at))));
		}
		edits.sort(java.util.Comparator.comparingInt(Edit::from).reversed());
		StringBuilder out = new StringBuilder(text);
		int last = Integer.MAX_VALUE;
		for (Edit edit : edits) {
			if (edit.to() > last) {
				throw new IllegalArgumentException("Two settings overlapping in the file");
			}
			last = edit.from();
			out.replace(edit.from(), edit.to(), edit.value());
		}
		return out.toString();
	}

	/**
	 * How a new value is spelled, given how the old one was.
	 *
	 * The span a value occupies includes its quotes, and the value read out of it
	 * does not — so writing back what was read, unspelled, would take the quotes
	 * off {@code restart-script: './start.sh'} and leave a file that means
	 * something else. So the old spelling is kept: quoted stays quoted, in the same
	 * style, escaped the way that style escapes; plain stays plain unless the new
	 * text needs quoting to stay one value.
	 */
	private String spell(Setting setting, String value) {
		String written = value.replace("\r", "").replace("\n", " ");
		if (format != Format.YAML) return written;
		String was = text.substring(setting.from(), setting.to());
		if (was.length() >= 2 && was.startsWith("'") && was.endsWith("'")) {
			return "'" + written.replace("'", "''") + "'";
		}
		if (was.length() >= 2 && was.startsWith("\"") && was.endsWith("\"")) {
			return "\"" + written.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
		}
		return needsQuoting(written) ? "'" + written.replace("'", "''") + "'" : written;
	}

	/**
	 * Whether a plain value would be read back as something other than itself.
	 *
	 * Not a full check of the format's rules, and it does not need to be: quoting
	 * something that did not need it is harmless, and the list here covers what
	 * turns a value into a comment, a map, a list or nothing at all.
	 */
	private static boolean needsQuoting(String value) {
		if (value.isEmpty()) return true;
		if (!value.strip().equals(value)) return true;
		if (value.contains(": ") || value.contains(" #") || value.endsWith(":")) return true;
		char first = value.charAt(0);
		return "#&*!|>%@`,[]{}'\"".indexOf(first) >= 0 || first == '-' && value.length() == 1;
	}

	/**
	 * Write text to the file, beside it first and then into place.
	 *
	 * A configuration file half written is a server that will not start, and the
	 * moment it is half written is the moment somebody is watching a progress bar
	 * and reaching for the power switch.
	 */
	public static void write(Path file, String text) throws IOException {
		Path part = file.resolveSibling(file.getFileName() + ".npc_part");
		Files.writeString(part, text, StandardCharsets.UTF_8);
		Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
	}

	// ---------------------------------------------------------------- YAML

	private static List<Setting> yaml(String text) {
		LoaderOptions loading = new LoaderOptions();
		loading.setProcessComments(true);
		loading.setCodePointLimit((int) MOST);
		List<Setting> found = new ArrayList<>();
		try {
			Node root = new Yaml(loading).compose(new StringReader(text));
			walk(root, "", found, text);
		} catch (RuntimeException broken) {
			// A file that does not parse is shown as text rather than as nothing:
			// somebody's half-finished edit is exactly when they want to see it.
			return List.of();
		}
		return List.copyOf(found);
	}

	private static void walk(Node node, String path, List<Setting> found, String text) {
		if (!(node instanceof MappingNode mapping)) return;
		for (NodeTuple tuple : mapping.getValue()) {
			if (!(tuple.getKeyNode() instanceof ScalarNode key)) continue;
			String here = path.isEmpty() ? key.getValue() : path + "." + key.getValue();
			Node value = tuple.getValueNode();
			if (value instanceof MappingNode) {
				walk(value, here, found, text);
				continue;
			}
			String comment = commentOf(key);
			if (value instanceof ScalarNode scalar) {
				int from = scalar.getStartMark().getIndex();
				int to = scalar.getEndMark().getIndex();
				boolean wrapped = text.lastIndexOf('\n', to - 1) >= from;
				found.add(new Setting(here, scalar.getValue(), comment,
					wrapped ? Sort.BLOCK : sortOf(scalar.getValue()), from, to));
			} else {
				// A sequence. Shown, so that a person can see it is there and go to
				// the file, and not editable here — see the note on this class.
				found.add(new Setting(here, "", comment, Sort.LIST,
					value.getStartMark().getIndex(), value.getEndMark().getIndex()));
			}
		}
	}

	/**
	 * The comment written above a key, which in this format is its documentation.
	 *
	 * Several lines are joined into one sentence: they are wrapped prose, and
	 * putting them back together is what makes them a label rather than a column
	 * of fragments.
	 */
	private static String commentOf(Node key) {
		if (key.getBlockComments() == null || key.getBlockComments().isEmpty()) return "";
		StringBuilder said = new StringBuilder();
		for (var line : key.getBlockComments()) {
			String part = line.getValue().strip();
			if (part.isEmpty()) {
				// A blank line inside a comment block separates one note from the
				// next, and the one nearest the key is the one about the key.
				said.setLength(0);
				continue;
			}
			if (said.length() > 0) said.append(' ');
			said.append(part);
		}
		return said.toString();
	}

	// ---------------------------------------------------------- properties

	/**
	 * A {@code .properties} file, which Fabric mods use and which reads the same way.
	 *
	 * Read here rather than through {@link PropertiesFile} because what is wanted
	 * is different: not the values, which that class gives, but where each value
	 * <em>is</em>, and the comment above it. A mod config like FerriteCore's is a
	 * comment and a key, over and over — the same shape as YAML, in another spelling.
	 */
	private static List<Setting> properties(String text) {
		List<Setting> found = new ArrayList<>();
		int at = 0;
		StringBuilder comment = new StringBuilder();
		while (at < text.length()) {
			int end = text.indexOf('\n', at);
			if (end < 0) end = text.length();
			String line = text.substring(at, end);
			String bare = line.strip();
			if (bare.startsWith("#") || bare.startsWith("!")) {
				String part = bare.substring(1).strip();
				if (!part.isEmpty()) {
					if (comment.length() > 0) comment.append(' ');
					comment.append(part);
				}
			} else if (bare.isEmpty()) {
				comment.setLength(0);
			} else {
				int mark = separatorIn(line);
				// A value continued onto the next line with a backslash. Rare in a
				// mod config and not worth editing badly: patching this line alone
				// would leave the continuation of a value that no longer exists.
				boolean carried = continued(line);
				if (mark >= 0) {
					int from = mark + 1;
					while (from < line.length() && (line.charAt(from) == ' '
						|| line.charAt(from) == '\t')) {
						from++;
					}
					int to = line.length();
					while (to > from && (line.charAt(to - 1) == ' ' || line.charAt(to - 1) == '\t'
						|| line.charAt(to - 1) == '\r')) {
						to--;
					}
					String key = line.substring(0, mark).strip();
					String value = line.substring(from, to);
					found.add(new Setting(key, value, comment.toString(),
						carried ? Sort.BLOCK : sortOf(value), at + from, at + to));
				}
				comment.setLength(0);
			}
			at = end + 1;
		}
		return List.copyOf(found);
	}

	/** Whether this line ends in an odd number of backslashes, and so carries on. */
	private static boolean continued(String line) {
		String bare = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
		int slashes = 0;
		for (int at = bare.length() - 1; at >= 0 && bare.charAt(at) == '\\'; at--) slashes++;
		return slashes % 2 == 1;
	}

	private static int separatorIn(String line) {
		for (int at = 0; at < line.length(); at++) {
			char c = line.charAt(at);
			if (c == '\\') {
				at++;
			} else if (c == '=' || c == ':') {
				return at;
			}
		}
		return -1;
	}

	// ------------------------------------------------------------- shared

	/**
	 * What sort of value this is, from the value itself.
	 *
	 * This is the plan's "the comment is the schema" in its cheapest form: nobody
	 * writes a schema for a plugin nobody has heard of, but everybody writes
	 * {@code true}, and a switch is better than a text field for it. Guessed from
	 * what is in the file rather than from a type declared anywhere, because
	 * neither format declares one.
	 */
	private static Sort sortOf(String value) {
		String bare = value.strip();
		if (bare.equalsIgnoreCase("true") || bare.equalsIgnoreCase("false")) return Sort.FLAG;
		if (bare.isEmpty()) return Sort.TEXT;
		if (bare.startsWith("[") || bare.startsWith("{")) return Sort.LIST;
		try {
			Double.parseDouble(bare);
			return Sort.NUMBER;
		} catch (NumberFormatException notANumber) {
			return Sort.TEXT;
		}
	}
}
