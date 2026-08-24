package com.mopicmp.npcstudio;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The rules about mixins that only the running game used to enforce.
 *
 * These are checked here because the alternative is checking them by starting
 * Minecraft. A mixin that breaks one of them does not fail to compile and does
 * not fail a test — it fails at class load, which is to say the game refuses to
 * start, with the reason in a log nobody is looking at yet. That happened: an
 * interface mixin was given a constant, which is a field, and interface mixins
 * may not have fields. Everything compiled.
 *
 * Read as text rather than through reflection because the mixin classes live in
 * the client source set, which the tests are not compiled against.
 */
class MixinConfigTest {

	private static final Path CLIENT_CONFIG = Path.of("src/client/resources/npc_studio.client.mixins.json");
	private static final Path COMMON_CONFIG = Path.of("src/main/resources/npc_studio.mixins.json");

	@Test
	@DisplayName("every mixin the config names is a file that exists")
	void everyMixinExists() throws IOException {
		for (Path config : List.of(CLIENT_CONFIG, COMMON_CONFIG)) {
			if (!Files.exists(config)) continue;
			for (Path source : sourcesOf(config)) {
				assertTrue(Files.exists(source),
					config.getFileName() + " names " + source + ", which is not there — "
						+ "the game refuses to start rather than skipping it");
			}
		}
	}

	@Test
	@DisplayName("an interface mixin holds accessors and nothing else")
	void interfaceMixinsHaveNoFields() throws IOException {
		for (Path config : List.of(CLIENT_CONFIG, COMMON_CONFIG)) {
			if (!Files.exists(config)) continue;
			for (Path source : sourcesOf(config)) {
				if (!Files.exists(source)) continue;
				String text = strip(Files.readString(source, StandardCharsets.UTF_8));
				if (!text.contains("interface ")) continue;

				// A field in an interface is implicitly public static final, which
				// Mixin counts as a field and refuses. Methods end in a bracket;
				// anything else ending in a semicolon at member level is a field.
				//
				// One tab exactly. Without the second guard the leading `[^;{]*` ate
				// any further indentation, so every statement in every method body
				// counted as a member — and the first interface mixin with a body in
				// it failed on a line reading `... instanceof Foo bar) ... return x;`,
				// which is a pattern variable and about as far from a field as it gets.
				Matcher member = Pattern.compile("(?m)^\\t(?![\\t/])([^\\n;{]*);\\s*$").matcher(text);
				while (member.find()) {
					String line = member.group(1).trim();
					if (line.endsWith(")")) continue;
					fail(source.getFileName() + " is an interface mixin with a field — "
						+ "\"" + line + "\". Mixin refuses the whole class at load time, "
						+ "so the game will not start. Put the constant somewhere else.");
				}
			}
		}
	}

	/** The java files a config points at, worked out from its package and list. */
	private static List<Path> sourcesOf(Path config) throws IOException {
		JsonObject json = JsonParser.parseString(
			Files.readString(config, StandardCharsets.UTF_8)).getAsJsonObject();
		String pkg = json.get("package").getAsString().replace('.', '/');
		Path root = config.toString().contains("client")
			? Path.of("src/client/java") : Path.of("src/main/java");

		List<Path> found = new ArrayList<>();
		for (String side : List.of("mixins", "client", "server")) {
			if (!json.has(side)) continue;
			JsonArray names = json.getAsJsonArray(side);
			for (int i = 0; i < names.size(); i++) {
				found.add(root.resolve(pkg).resolve(names.get(i).getAsString() + ".java"));
			}
		}
		return found;
	}

	/** Comments out of the way, so a sentence with a semicolon is not read as a field. */
	private static String strip(String text) {
		return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
	}
}
