package com.mopicmp.npcstudio.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Copies of a world, taken in the one order that produces a usable copy.
 *
 * <b>This class exists for four lines of it.</b> A running Minecraft server holds
 * chunks in memory and writes them when it feels like it, so copying a world
 * folder while it runs produces an archive that is complete in the sense that
 * every file is there and torn in the sense that they are from different moments.
 * It restores. It even loads. What it does not do is match, and the mismatch
 * shows up as missing chunks and rolled-back inventories on the one day anybody
 * looks — which is the day the original is gone.
 *
 * So: {@code save-off} to stop it writing, {@code save-all flush} to make it
 * finish what it started, copy, {@code save-on}. That is what every backup plugin
 * does and it is not optional.
 *
 * The consequence is the part worth arguing about: <b>if the server is running
 * and its command channel does not answer, no copy is taken at all.</b> Not a
 * copy with a warning — none. An archive somebody believes in is worse than no
 * archive, because it is the reason they did not make a real one.
 */
public final class Backups {

	private Backups() {
	}

	/**
	 * What a copy needs the server to do, so this can be tested without one.
	 *
	 * The interface is here rather than in the caller because the order of these
	 * calls <em>is</em> the subject of this class, and an order can only be tested
	 * against something that records it.
	 */
	public interface Saving {

		/** Whether there is a running server that could be writing. */
		boolean running();

		/**
		 * Stop it writing and make it finish. False means it could not be reached,
		 * which is a refusal to copy and not a warning to press past.
		 */
		boolean pause();

		/** Let it write again. Called however the copy went, including badly. */
		void resume();

		/** For a server that is not running: nothing to ask, nothing to restore. */
		Saving STOPPED = new Saving() {
			@Override
			public boolean running() {
				return false;
			}

			@Override
			public boolean pause() {
				return true;
			}

			@Override
			public void resume() {
			}
		};
	}

	/**
	 * One archive on disk.
	 *
	 * @param label what a person called it, which may be nothing. Everything else
	 *              here is read out of the file name, and so is this — see
	 *              {@link #rename} for why it is not kept in a file beside it.
	 */
	public record Backup(String file, Path path, String world, long size, Instant made,
			String label) {

		/** What to show it as: their words when there are any, the world when not. */
		public String title() {
			return label.isBlank() ? world : label;
		}
	}

	/** Where they live: beside the worlds, so they travel with the server. */
	public static Path folder(ManagedServer server) {
		return server.path().resolve("backups");
	}

	private static final DateTimeFormatter STAMP =
		DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);

	/** A world's copies and everything else's, newest first. */
	public static List<Backup> list(ManagedServer server) {
		Path folder = folder(server);
		if (!Files.isDirectory(folder)) return List.of();
		List<Backup> found = new ArrayList<>();
		try (var files = Files.list(folder)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				String name = file.getFileName().toString();
				if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) continue;
				long size;
				Instant made;
				try {
					size = Files.size(file);
					made = Files.getLastModifiedTime(file).toInstant();
				} catch (IOException gone) {
					continue;
				}
				found.add(new Backup(name, file, worldOf(name), size, made, labelOf(name)));
			}
		} catch (IOException unreadable) {
			return List.of();
		}
		found.sort(java.util.Comparator.comparing(Backup::made).reversed());
		return List.copyOf(found);
	}

	/**
	 * Which world an archive is of, read back out of its name.
	 *
	 * The name is {@code <world>-<when>.zip} and the stamp has a known shape, so
	 * the world is everything before the last dash that is followed by one. Read
	 * from the name rather than from a note beside it because a note is a second
	 * thing that can be lost, and a copy whose world is unknown is a copy nobody
	 * dares restore.
	 */
	static String worldOf(String file) {
		String name = bare(file);
		int dash = stampAt(name);
		// Whichever way it was named, the whole of it is better than nothing.
		return dash < 0 ? name : name.substring(0, dash);
	}

	/**
	 * The words a person put on this copy, or nothing.
	 *
	 * They live after the stamp, behind a space: {@code world-<stamp> before the
	 * mine}. Behind the stamp rather than in front of it so that the world and the
	 * moment stay exactly where {@link #worldOf} looks for them — a name somebody
	 * typed cannot push them out of place, however many dashes it has in it.
	 */
	static String labelOf(String file) {
		String name = bare(file);
		int dash = stampAt(name);
		if (dash < 0) return "";
		int space = name.indexOf(' ', dash + 1);
		return space < 0 ? "" : name.substring(space + 1).trim();
	}

	private static String bare(String file) {
		return file.endsWith(".zip") ? file.substring(0, file.length() - 4) : file;
	}

	/** Where the stamp starts, as the index of the dash before it, or -1. */
	private static int stampAt(String name) {
		int dash = name.lastIndexOf('-');
		while (dash > 0) {
			String tail = name.substring(dash + 1);
			if (tail.matches("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}( .*)?")) return dash;
			dash = name.lastIndexOf('-', dash - 1);
		}
		return -1;
	}

	static String nameFor(String world, LocalDateTime when) {
		return world + "-" + STAMP.format(when) + ".zip";
	}

	/** Longer than this is not a name any more, it is a note. */
	private static final int MOST_LABEL = 64;

	/** What a name may not hold: what a file name may not hold, and quotes. */
	private static final java.util.regex.Pattern BAD_IN_NAME =
		java.util.regex.Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");

	/**
	 * Put a person's own name on a copy, keeping the world and the moment.
	 *
	 * The name goes into the file name and nowhere else. A note in a file beside
	 * the archive would be a second thing to lose, and losing it would leave a copy
	 * nobody dares restore — the same reason the world is read out of the name
	 * rather than recorded. So the name is renamed: what is added is added after
	 * the stamp, where nothing else looks.
	 *
	 * An empty name takes the words off again rather than being refused.
	 */
	public static Backup rename(Backup backup, String label) throws IOException {
		String wanted = label == null ? "" : label.trim();
		if (wanted.length() > MOST_LABEL) {
			throw new IOException("That name is longer than " + MOST_LABEL + " characters.");
		}
		if (BAD_IN_NAME.matcher(wanted).find()) {
			throw new IOException("A name cannot hold any of \\ / : * ? \" < > |");
		}
		String stamped = bare(backup.file());
		int dash = stampAt(stamped);
		if (dash < 0) {
			throw new IOException("That copy is not named the way this makes them, so renaming"
				+ " it would lose which world it is of.");
		}
		int space = stamped.indexOf(' ', dash + 1);
		String head = space < 0 ? stamped : stamped.substring(0, space);
		String name = wanted.isEmpty() ? head + ".zip" : head + " " + wanted + ".zip";
		if (name.equals(backup.file())) return backup;

		Path target = backup.path().resolveSibling(name);
		if (Files.exists(target)) {
			throw new IOException("There is already a copy called that.");
		}
		Files.move(backup.path(), target);
		return new Backup(name, target, backup.world(), backup.size(), backup.made(), wanted);
	}

	/**
	 * One file out of the archive, or nothing when it is not in there.
	 *
	 * For the two small files that say what a copy is — the world's picture and its
	 * {@code level.dat}. Read from the archive rather than from the world it came
	 * from, because the interesting copies are the ones whose world is gone.
	 *
	 * @param inside the path inside the archive, which is relative to the server
	 *               folder: {@code <world>/icon.png}
	 */
	public static byte[] entry(Backup backup, String inside) {
		try (ZipFile zip = new ZipFile(backup.path().toFile())) {
			ZipEntry entry = zip.getEntry(inside);
			if (entry == null || entry.getSize() > MOST_ENTRY) return null;
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readNBytes(MOST_ENTRY);
			}
		} catch (IOException | RuntimeException unreadable) {
			return null;
		}
	}

	/** A picture and a level.dat are both small; this is room for a strange one. */
	private static final int MOST_ENTRY = 8 * 1024 * 1024;

	/** Past this a world is not being copied, something has gone wrong. */
	private static final long MOST = 8L * 1024 * 1024 * 1024;

	/**
	 * Take a copy, or refuse and say why.
	 *
	 * @throws IOException when the server is running and will not stop writing —
	 *                     see the note on this class about why that is a refusal
	 */
	public static Backup make(ManagedServer server, Worlds.World world, Saving saving,
			Downloads.Progress progress) throws IOException {
		Path folder = folder(server);
		Files.createDirectories(folder);
		Path target = folder.resolve(nameFor(world.name(), LocalDateTime.now()));

		boolean paused = false;
		try {
			if (saving.running()) {
				if (!saving.pause()) {
					throw new IOException("The server is running and its command channel does"
						+ " not answer. A copy taken now would be stitched together from"
						+ " different moments — it would restore, and it would be wrong."
						+ " Stop the server, or fix the command channel, and try again.");
				}
				paused = true;
			}
			write(server.path(), world.folders(), target, progress);
		} catch (IOException | RuntimeException failed) {
			Files.deleteIfExists(target);
			throw failed instanceof IOException broken ? broken
				: new IOException(failed.getMessage(), failed);
		} finally {
			if (paused) saving.resume();
		}
		return new Backup(target.getFileName().toString(), target, world.name(),
			Files.size(target), Files.getLastModifiedTime(target).toInstant(), "");
	}

	/**
	 * The archive itself: every folder of the world, named relative to the server.
	 *
	 * Relative to the server folder rather than to the world folder, because a
	 * Paper world is three folders beside each other — an archive rooted at one of
	 * them could not hold the other two, and restoring would put the nether inside
	 * the overworld.
	 */
	private static void write(Path root, List<Path> folders, Path target,
			Downloads.Progress progress) throws IOException {
		long[] done = {0};
		Path partial = target.resolveSibling(target.getFileName() + ".part");
		try (OutputStream out = Files.newOutputStream(partial);
			 ZipOutputStream zip = new ZipOutputStream(out)) {
			for (Path folder : folders) {
				Files.walkFileTree(folder, new SimpleFileVisitor<Path>() {
					@Override
					public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
						throws IOException {
						// The lock a running server holds. Copying it is harmless and
						// restoring it is not: the server treats a stale one as
						// another server already having the world.
						if (file.getFileName().toString().equals("session.lock")) {
							return FileVisitResult.CONTINUE;
						}
						String name = root.relativize(file).toString().replace('\\', '/');
						zip.putNextEntry(new ZipEntry(name));
						done[0] += Files.copy(file, zip);
						zip.closeEntry();
						if (done[0] > MOST) {
							throw new IOException("That world is larger than "
								+ MOST / (1024 * 1024 * 1024) + " GB");
						}
						progress.at(done[0], -1);
						return FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult visitFileFailed(Path file, IOException problem) {
						// A file the server deleted mid-walk. It was not there when
						// the copy was taken, which is a true statement about the
						// copy.
						return FileVisitResult.CONTINUE;
					}
				});
			}
		} catch (IOException | RuntimeException failed) {
			Files.deleteIfExists(partial);
			throw failed instanceof IOException broken ? broken
				: new IOException(failed.getMessage(), failed);
		}
		Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
	}

	/**
	 * Put a copy back, over whatever is there now.
	 *
	 * Refuses while the server runs, and not out of caution: replacing the files
	 * under a loaded world gives a server holding chunks that no longer exist, and
	 * it will write them back over what was just restored.
	 *
	 * <b>The caller is expected to have taken a copy of what is there first.</b>
	 * Restoring destroys the present, and a person restoring last week's world
	 * because of a mistake today is one press away from wanting today back.
	 */
	public static void restore(ManagedServer server, Backup backup, Saving saving)
		throws IOException {
		if (saving.running()) {
			throw new IOException("Stop the server first. Putting files back under a world"
				+ " it has loaded gives it chunks that no longer exist, and it writes them"
				+ " out again over what was just restored.");
		}
		Path root = server.path().toAbsolutePath().normalize();
		java.util.Set<Path> replacing = new java.util.LinkedHashSet<>();

		try (ZipFile zip = new ZipFile(backup.path().toFile())) {
			// Everything is checked before anything is deleted: a restore that
			// removes the world and then refuses the archive is a restore that
			// destroyed a world for nothing.
			var entries = zip.entries();
			int count = 0;
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (++count > 200_000) throw new IOException("That archive holds too many files");
				if (entry.isDirectory()) continue;
				Path target = root.resolve(entry.getName()).normalize();
				if (!target.startsWith(root)) {
					throw new IOException("That archive names a file outside the server folder: "
						+ entry.getName());
				}
				Path top = root.relativize(target);
				if (top.getNameCount() > 0) replacing.add(root.resolve(top.getName(0)));
			}
			for (Path folder : replacing) {
				if (Files.isDirectory(folder)) delete(folder);
			}

			entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (entry.isDirectory()) continue;
				Path target = root.resolve(entry.getName()).normalize();
				if (!target.startsWith(root)) continue;
				Files.createDirectories(target.getParent());
				try (InputStream in = zip.getInputStream(entry)) {
					Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		} catch (java.util.zip.ZipException notAZip) {
			throw new IOException("That copy cannot be read: " + notAZip.getMessage(), notAZip);
		}
	}

	public static void drop(Backup backup) throws IOException {
		Files.deleteIfExists(backup.path());
	}

	private static void delete(Path folder) throws IOException {
		Files.walkFileTree(folder, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
				throws IOException {
				Files.deleteIfExists(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path directory, IOException problem)
				throws IOException {
				Files.deleteIfExists(directory);
				return FileVisitResult.CONTINUE;
			}
		});
	}
}
