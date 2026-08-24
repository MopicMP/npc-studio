package com.mopicmp.npcstudio.client.scene;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The names this mod reaches into Iris by, checked against an actual Iris.
 *
 * <h2>What happened</h2>
 *
 * The shader tab listed every pack on disk and none of them would load. The
 * reason was one line in the log —
 * {@code NoSuchMethodException: IrisApiV0ConfigImpl.setShaderPackName} — and the
 * reason for <em>that</em> was that nobody had ever looked: the method name was
 * the obvious one, so it was written down and shipped. Iris's published
 * configuration has two methods and neither has ever been called that.
 *
 * <h2>Why a test and not a comment</h2>
 *
 * Because reflection cannot be compiled against anything. A wrong class name or a
 * wrong method name is a string that is correct until it runs, and the failure it
 * produces is a caught exception and a feature that quietly does nothing — which
 * is the hardest kind of broken to notice and took a round trip through somebody
 * else's game to find.
 *
 * So the jar is read. Not loaded — loading Iris means loading half of Minecraft —
 * but opened, and each class file searched for the names we intend to ask it for.
 * A method's own name is in its class's constant pool, so its absence is real
 * evidence rather than a hint. If Iris renames one of these, this fails here
 * instead of in a log nobody has opened yet.
 *
 * <h2>Half of what is checked is deliberately not public API</h2>
 *
 * {@code Iris} and {@code IrisConfig} are internal, and that is not an oversight
 * to be tidied away later. The published API can turn shaders on and off and
 * cannot name a pack, so choosing one means going where Iris's own selection
 * screen goes. What this test buys is knowing the day that stops working.
 */
class IrisReachTest {

	/** Where a copy of Iris is kept for exactly this. */
	private static final Path REFERENCE = Path.of("..", "test", "video");

	private static Optional<Path> irisJar() {
		if (!Files.isDirectory(REFERENCE)) return Optional.empty();
		try (var listing = Files.list(REFERENCE)) {
			return listing.filter(path -> {
				String name = path.getFileName().toString();
				return name.startsWith("iris-") && name.endsWith(".jar");
			}).findFirst();
		} catch (IOException unreadable) {
			return Optional.empty();
		}
	}

	/** Whether a class in the jar mentions a name at all. */
	private static boolean mentions(ZipFile jar, String className, String name)
			throws IOException {
		ZipEntry entry = jar.getEntry(className.replace('.', '/') + ".class");
		assertTrue(entry != null, "Iris has no class called " + className
			+ " — the mod asks for it by name and would find nothing");
		byte[] bytes = jar.getInputStream(entry).readAllBytes();
		// Latin-1 rather than UTF-8: the constant pool is modified UTF-8 and the
		// names being looked for are plain ASCII, so a byte-for-char reading finds
		// them and cannot throw on the parts that are not text.
		return new String(bytes, StandardCharsets.ISO_8859_1).contains(name);
	}

	private record Reach(String className, List<String> names) { }

	/** Everything {@code Shaders} reaches for, in the class it reaches for it in. */
	private static final List<Reach> WANTED = List.of(
		// The published API: getting hold of it, and the one call that applies a
		// change. This last one is the whole mechanism — it saves the config and
		// reloads unconditionally, so it works even when only the pack name moved.
		new Reach("net.irisshaders.iris.api.v0.IrisApi",
			List.of("getInstance", "getConfig")),
		new Reach("net.irisshaders.iris.api.v0.IrisApiConfig",
			List.of("areShadersEnabled", "setShadersEnabledAndApply")),

		// The internal parts, which is where the pack name actually lives.
		new Reach("net.irisshaders.iris.Iris",
			List.of("getIrisConfig", "getCurrentPackName", "clearShaderPackOptionQueue",
				"getShaderpacksDirectory", "getStoredError", "isFallback")),
		new Reach("net.irisshaders.iris.config.IrisConfig",
			List.of("setShaderPackName", "getShaderPackName")));

	@Test
	@DisplayName("every name the mod reaches into Iris by is in the jar")
	void everyNameIsThere() throws IOException {
		Optional<Path> jar = irisJar();
		Assumptions.assumeTrue(jar.isPresent(),
			"no Iris in test/video to check against — put one there and this test means something");

		try (ZipFile iris = new ZipFile(jar.get().toFile())) {
			for (Reach reach : WANTED) {
				for (String name : reach.names()) {
					assertTrue(mentions(iris, reach.className(), name),
						reach.className() + " has nothing called " + name
							+ " — Shaders asks for it by reflection, so the shader tab "
							+ "would list packs that refuse to load, which is what happened");
				}
			}
		}
	}

	@Test
	@DisplayName("the method that never existed is still not there")
	void theOldGuessIsStillWrong() throws IOException {
		Optional<Path> jar = irisJar();
		Assumptions.assumeTrue(jar.isPresent());

		// Pinned as a fact rather than as history. If a future Iris does add this to
		// its published config, then reaching into the internal one stops being
		// necessary and this test is the thing that says so.
		try (ZipFile iris = new ZipFile(jar.get().toFile())) {
			assertTrue(!mentions(iris, "net.irisshaders.iris.api.v0.IrisApiConfig",
					"setShaderPackName"),
				"the published config now names a pack — use it and stop reaching "
					+ "into net.irisshaders.iris.Iris");
		}
	}
}
