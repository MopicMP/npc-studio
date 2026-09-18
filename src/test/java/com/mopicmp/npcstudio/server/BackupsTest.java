package com.mopicmp.npcstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Copies of a world, and the order in which they are taken.
 *
 * <b>The order is the subject.</b> A copy taken while a server is writing is
 * complete and wrong — every file present, from different moments — and it
 * restores and loads and only shows its damage on the day the original is gone.
 * So what is tested here is not that files end up in a zip; it is that the server
 * was told to stop writing first, told to finish, and let go afterwards however
 * the copy went.
 */
class BackupsTest {

	/** A server that records what it was asked, in the order it was asked. */
	private static final class Recorder implements Backups.Saving {
		final List<String> said = new ArrayList<>();
		boolean up = true;
		boolean answers = true;

		@Override
		public boolean running() {
			return up;
		}

		@Override
		public boolean pause() {
			said.add(answers ? "pause" : "pause-failed");
			return answers;
		}

		@Override
		public void resume() {
			said.add("resume");
		}
	}

	private static ManagedServer server(Path folder) throws IOException {
		ManagedServer server = new ManagedServer();
		server.id = "test";
		server.core = "fabric";
		server.directory = folder.toString();
		Files.createDirectories(folder);
		return server;
	}

	private static Worlds.World world(Path root, String name, String... extraFolders)
		throws IOException {
		List<Path> folders = new ArrayList<>();
		Path main = root.resolve(name);
		Files.createDirectories(main);
		Files.writeString(main.resolve("level.dat"), "not really nbt");
		Files.createDirectories(main.resolve("region"));
		Files.writeString(main.resolve("region").resolve("r.0.0.mca"), "chunks");
		Files.writeString(main.resolve("session.lock"), "held");
		folders.add(main);
		for (String extra : extraFolders) {
			Path beside = root.resolve(extra);
			Files.createDirectories(beside);
			Files.writeString(beside.resolve("level.dat"), "also nbt");
			folders.add(beside);
		}
		return new Worlds.World(name, main, folders, List.of(), 0, 0);
	}

	@Test
	@DisplayName("A running server is stopped from writing, made to flush, and let go after")
	void theOrder(@TempDir Path folder) throws IOException {
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		Recorder saving = new Recorder();

		Backups.make(server, world(root, "world"), saving, Downloads.Progress.IGNORED);

		// Told to stop before anything was read, and let go after everything was.
		assertEquals(List.of("pause", "resume"), saving.said);
	}

	@Test
	@DisplayName("A running server that will not answer gets no copy at all")
	void refusesRatherThanTears(@TempDir Path folder) throws IOException {
		// The argument this whole class is built on: an archive somebody believes
		// in is worse than no archive, because it is the reason they did not make
		// a real one. So this is a refusal and not a warning to press past.
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		Recorder saving = new Recorder();
		saving.answers = false;
		Worlds.World world = world(root, "world");

		IOException refused = assertThrows(IOException.class,
			() -> Backups.make(server, world, saving, Downloads.Progress.IGNORED));
		assertTrue(refused.getMessage().contains("command channel"), refused.getMessage());
		// Nothing was left behind, not even a part of one.
		assertTrue(Backups.list(server).isEmpty());
		try (var files = Files.list(Backups.folder(server))) {
			assertEquals(0, files.count());
		}
		// And it was never told to stop, so it was never left stopped.
		assertFalse(saving.said.contains("resume"));
	}

	@Test
	@DisplayName("A stopped server is asked nothing")
	void stoppedNeedsNoAsking(@TempDir Path folder) throws IOException {
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		Recorder saving = new Recorder();
		saving.up = false;

		Backups.make(server, world(root, "world"), saving, Downloads.Progress.IGNORED);

		assertTrue(saving.said.isEmpty(), saving.said.toString());
	}

	@Test
	@DisplayName("All three folders of a Paper world go into one copy, and come back beside each other")
	void threeFoldersOneWorld(@TempDir Path folder) throws IOException {
		// A Paper world is three folders next to each other. An archive rooted at
		// the overworld could not hold the other two, and restoring would put the
		// nether inside the overworld — so the names are relative to the server.
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		Worlds.World world = world(root, "world", "world_nether", "world_the_end");

		Backups.Backup made = Backups.make(server, world, Backups.Saving.STOPPED,
			Downloads.Progress.IGNORED);

		Files.writeString(root.resolve("world").resolve("region").resolve("r.0.0.mca"),
			"ruined");
		Backups.restore(server, made, Backups.Saving.STOPPED);

		assertEquals("chunks",
			Files.readString(root.resolve("world").resolve("region").resolve("r.0.0.mca")));
		assertTrue(Files.isRegularFile(root.resolve("world_nether").resolve("level.dat")));
		assertTrue(Files.isRegularFile(root.resolve("world_the_end").resolve("level.dat")));
	}

	@Test
	@DisplayName("The lock a running server holds is not copied and not restored")
	void noSessionLock(@TempDir Path folder) throws IOException {
		// Restoring one gives a server that believes another server already has
		// the world, and refuses to load it.
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		Backups.Backup made = Backups.make(server, world(root, "world"),
			Backups.Saving.STOPPED, Downloads.Progress.IGNORED);

		Files.delete(root.resolve("world").resolve("session.lock"));
		Backups.restore(server, made, Backups.Saving.STOPPED);
		assertFalse(Files.exists(root.resolve("world").resolve("session.lock")));
	}

	@Test
	@DisplayName("A restore onto a running server is refused")
	void notWhileRunning(@TempDir Path folder) throws IOException {
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		Backups.Backup made = Backups.make(server, world(root, "world"),
			Backups.Saving.STOPPED, Downloads.Progress.IGNORED);

		Recorder saving = new Recorder();
		IOException refused = assertThrows(IOException.class,
			() -> Backups.restore(server, made, saving));
		assertTrue(refused.getMessage().contains("Stop the server"), refused.getMessage());
	}

	@Test
	@DisplayName("An archive naming a file outside the server folder is refused before anything is deleted")
	void doesNotClimb(@TempDir Path folder) throws IOException {
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		world(root, "world");
		Files.createDirectories(Backups.folder(server));
		Path bad = Backups.folder(server).resolve("world-2026-01-01_00-00-00.zip");
		try (var out = Files.newOutputStream(bad);
			 var zip = new java.util.zip.ZipOutputStream(out)) {
			zip.putNextEntry(new java.util.zip.ZipEntry("../../escaped.txt"));
			zip.write("no".getBytes(java.nio.charset.StandardCharsets.UTF_8));
			zip.closeEntry();
		}

		var backup = Backups.list(server).getFirst();
		assertThrows(IOException.class,
			() -> Backups.restore(server, backup, Backups.Saving.STOPPED));
		assertFalse(Files.exists(folder.resolve("escaped.txt")));
		// And the world that was there is still there: everything is checked
		// before anything is removed, or a refused restore would have destroyed a
		// world for nothing.
		assertTrue(Files.isRegularFile(root.resolve("world").resolve("level.dat")));
	}

	@Test
	@DisplayName("Which world a copy is of is read back out of its name")
	void namesCarryTheWorld() {
		String name = Backups.nameFor("my-world_2", LocalDateTime.of(2026, 9, 1, 23, 5, 7));
		assertEquals("my-world_2-2026-09-01_23-05-07.zip", name);
		// A dash in the world's own name must not be mistaken for the one before
		// the stamp — which is why the stamp's shape is checked rather than the
		// last dash taken.
		assertEquals("my-world_2", Backups.worldOf(name));
		assertEquals("world", Backups.worldOf("world-2026-09-01_23-05-07.zip"));
	}

	/**
	 * A name somebody typed must not cost the copy its world.
	 *
	 * Which world an archive is of is read out of its name and nowhere else, so a
	 * rename that put words where the world is would leave a copy nobody could
	 * place. The words go after the stamp, and the world survives whatever is in
	 * them — dashes included, which is the shape that breaks a lazy reading.
	 */
	@Test
	@DisplayName("A name of one's own is added after the stamp, and the world is still read")
	void renamingKeepsTheWorld(@TempDir Path folder) throws IOException {
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		Files.createDirectories(Backups.folder(server));
		Path file = Backups.folder(server).resolve("my-world-2026-09-01_23-05-07.zip");
		Files.writeString(file, "not really a zip");

		Backups.Backup was = Backups.list(server).getFirst();
		assertEquals("my-world", was.world());
		assertEquals("", was.label());
		assertEquals("my-world", was.title());

		Backups.Backup now = Backups.rename(was, "перед шахтой-2");
		assertEquals("my-world-2026-09-01_23-05-07 перед шахтой-2.zip", now.file());
		assertFalse(Files.exists(file));
		assertTrue(Files.isRegularFile(now.path()));

		// And read back off the disk, which is the only reading that matters: the
		// list is built from file names on the next look, not from what rename
		// happened to return.
		Backups.Backup read = Backups.list(server).getFirst();
		assertEquals("my-world", read.world());
		assertEquals("перед шахтой-2", read.label());
		assertEquals("перед шахтой-2", read.title());

		// Renamed again, the words are replaced rather than piled up.
		Backups.Backup twice = Backups.rename(read, "после");
		assertEquals("my-world-2026-09-01_23-05-07 после.zip", twice.file());
		// And an empty name takes them off again.
		assertEquals("my-world-2026-09-01_23-05-07.zip", Backups.rename(twice, "  ").file());
	}

	@Test
	@DisplayName("A name that a file cannot hold is refused rather than mangled")
	void refusesNamesAFileCannotHold(@TempDir Path folder) throws IOException {
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		Files.createDirectories(Backups.folder(server));
		Files.writeString(Backups.folder(server).resolve("world-2026-09-01_23-05-07.zip"), "x");
		Backups.Backup backup = Backups.list(server).getFirst();

		assertThrows(IOException.class, () -> Backups.rename(backup, "../escape"));
		assertThrows(IOException.class, () -> Backups.rename(backup, "a:b"));
		assertThrows(IOException.class, () -> Backups.rename(backup, "x".repeat(200)));
		// Nothing moved: a refusal that had already renamed the file would be the
		// worst of both.
		assertEquals("world-2026-09-01_23-05-07.zip", Backups.list(server).getFirst().file());
	}

	@Test
	@DisplayName("A small file can be read out of a copy without unpacking it")
	void readsOneFileOutOfACopy(@TempDir Path folder) throws IOException {
		Path root = folder.resolve("server");
		ManagedServer server = server(root);
		Worlds.World world = world(root, "world");
		Files.writeString(root.resolve("world").resolve("icon.png"), "pretend picture");

		Backups.Backup made = Backups.make(server, world,
			Backups.Saving.STOPPED, Downloads.Progress.IGNORED);
		assertEquals("pretend picture", new String(Backups.entry(made, "world/icon.png"),
			java.nio.charset.StandardCharsets.UTF_8));
		assertNull(Backups.entry(made, "world/nothing-like-that"));
	}
}
