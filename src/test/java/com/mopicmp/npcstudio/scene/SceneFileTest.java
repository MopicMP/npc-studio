package com.mopicmp.npcstudio.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A scene written down and read back is the scene that was written down.
 *
 * <h2>Why this exists</h2>
 *
 * Because the reader had a two-way choice in it — "the word SOUND, or else a
 * line" — which was correct while there were two kinds of cue and went silently
 * wrong the moment there were three. A piece of music saved and reopened came
 * back as a caption: no error, no warning, and the scene simply had no music in
 * it any more.
 *
 * The failure is worth describing because of its shape rather than its size.
 * Nothing threw, nothing was logged, and the only way to notice was to reopen a
 * scene and find something missing — by which time it is somebody's afternoon
 * rather than a bug. A reader that enumerates what there is cannot fail this way
 * again, and this test is what says so when a fourth kind arrives.
 */
class SceneFileTest {

	@Test
	@DisplayName("every kind of cue survives being saved and read back")
	void everyCueKindSurvives() {
		Scene scene = Scene.empty("корабль");
		for (Cue.Kind kind : Cue.Kind.values()) {
			scene = scene.with(new Cue(10 + kind.ordinal(), kind, "какой-то " + kind, 5, null));
		}

		Scene back = SceneIO.read(SceneIO.write(scene));

		assertEquals(Cue.Kind.values().length, back.cues().size(), "a cue went missing");
		for (Cue cue : back.cues()) {
			Cue was = scene.cues().stream()
				.filter(one -> one.at() == cue.at()).findFirst().orElseThrow();
			assertEquals(was.kind(), cue.kind(), "the cue at " + cue.at() + " changed kind");
			assertEquals(was.what(), cue.what());
			assertEquals(was.lasts(), cue.lasts());
		}
	}

	@Test
	@DisplayName("every kind of part survives too, including the one nobody plays")
	void everyRoleKindSurvives() {
		// The sky is a part of kind WORLD with nobody cast in it. A reader that fell
		// back to CHARACTER for anything it did not know would turn it into an actor
		// with no body, and the weather would stop being found.
		Scene scene = Scene.empty("корабль");
		for (Role.Kind kind : Role.Kind.values()) {
			scene = scene.with(Role.of(kind.name().toLowerCase(), kind));
		}

		Scene back = SceneIO.read(SceneIO.write(scene));

		assertEquals(Role.Kind.values().length, back.cast().size());
		for (Role role : back.cast()) {
			assertEquals(role.name().toUpperCase(), role.kind().name(),
				"the part called " + role.name() + " came back as " + role.kind());
		}
	}

	@Test
	@DisplayName("a file written before a kind existed still reads")
	void olderFilesStillRead() {
		// The point of matching by name is that anything unrecognised has to land
		// somewhere harmless rather than throw. A caption shows and can be deleted;
		// a sound would fire.
		Scene scene = SceneIO.read(com.google.gson.JsonParser.parseString(
			"{\"name\":\"корабль\",\"length\":40,"
				+ "\"cues\":[{\"at\":5,\"kind\":\"WHISTLING\",\"what\":\"?\"}]}")
			.getAsJsonObject());

		assertEquals(1, scene.cues().size());
		assertEquals(Cue.Kind.TEXT, scene.cues().get(0).kind());
	}
}
