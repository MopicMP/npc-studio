package com.mopicmp.npcstudio.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mopicmp.npcstudio.NpcStudio;

/**
 * Keys that belong to the person using the mod, kept the way such things should
 * be kept — and an honest account of what that does and does not mean.
 *
 * <b>What is actually done.</b> The file lives beside the game's configuration
 * and nowhere near a server folder, so it cannot be swept into a world archive or
 * a screenshot of a server directory. It is written with permissions that name
 * only its owner — a POSIX mode of 600, or on Windows an access list holding one
 * entry, the owner's. It is never written to the log; the log lines below say a
 * name and never a value. It is never shown whole: {@link #masked} is the only
 * way this class hands one back to anything that draws. It goes over the network
 * to exactly one host, in a header, on a request that {@link Downloads} refuses
 * to follow a redirect from, so no address but the intended one can ever be
 * handed it.
 *
 * <b>What cannot be done, and should not be claimed.</b> The key has to be
 * readable by this program in order to be usable, so anything running as this
 * user can read it. Encrypting it would move the question rather than answer it:
 * the mod would then have to hold the decryption key, in the same account, on the
 * same disk. That is obfuscation, and calling obfuscation security is how people
 * end up trusting it with something they should not. The same is true of the key
 * saved in a browser or in any desktop application — this is the same protection
 * those have, described accurately.
 *
 * The Base64 is not part of that argument. It is there so a key holding a newline
 * or an equals sign cannot break the file, and for no other reason.
 */
public final class ApiKeys {

	private static final String FILE = "npc_studio_keys.properties";

	private final Path file;
	private Map<String, String> keys;

	public ApiKeys(Path file) {
		this.file = file;
	}

	public static ApiKeys forGame() {
		return new ApiKeys(net.fabricmc.loader.api.FabricLoader.getInstance()
			.getConfigDir().resolve(FILE));
	}

	public Path path() {
		return file;
	}

	private synchronized Map<String, String> read() {
		if (keys != null) return keys;
		Map<String, String> found = new LinkedHashMap<>();
		try {
			if (Files.isRegularFile(file)) {
				for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
					String trimmed = line.trim();
					if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
					int equals = trimmed.indexOf('=');
					if (equals <= 0) continue;
					String id = trimmed.substring(0, equals).trim();
					String value = trimmed.substring(equals + 1).trim();
					try {
						found.put(id, new String(Base64.getDecoder().decode(value),
							StandardCharsets.UTF_8));
					} catch (IllegalArgumentException notBase64) {
						// A line somebody wrote by hand. Taken as it stands rather
						// than dropped: the alternative is a key that silently is
						// not there.
						found.put(id, value);
					}
				}
			}
		} catch (IOException unreadable) {
			// The name of the file, never a line of it.
			NpcStudio.LOGGER.warn("Could not read {}: {}", FILE, unreadable.getMessage());
		}
		keys = found;
		return keys;
	}

	/** The key for a source, or an empty string. Never logged, never drawn. */
	public synchronized String get(String id) {
		return read().getOrDefault(id, "");
	}

	public synchronized boolean has(String id) {
		return !get(id).isBlank();
	}

	/** Which sources have one, for an interface that must not ask for the values. */
	public synchronized java.util.Set<String> present() {
		java.util.Set<String> found = new java.util.LinkedHashSet<>();
		read().forEach((id, value) -> {
			if (!value.isBlank()) found.add(id);
		});
		return found;
	}

	public synchronized void put(String id, String key) throws IOException {
		String trimmed = key == null ? "" : key.trim();
		if (trimmed.isEmpty()) {
			drop(id);
			return;
		}
		read().put(id, trimmed);
		write();
	}

	public synchronized void drop(String id) throws IOException {
		read().remove(id);
		write();
	}

	private void write() throws IOException {
		StringBuilder text = new StringBuilder("""
			# Keys for catalogues that need one of their own.
			#
			# This file is meant to be readable only by you, and the mod sets it that
			# way whenever it writes. It is not encrypted, and it is not pretending to
			# be: the mod has to be able to read it to use it. Do not put it in a
			# repository, a backup that others can read, or a screenshot.
			""");
		read().forEach((id, value) -> text.append(id).append('=')
			.append(Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)))
			.append(System.lineSeparator()));

		Path parent = file.getParent();
		if (parent != null) Files.createDirectories(parent);
		// Made empty and locked down before anything is in it, so there is no
		// moment where the file holds a key and is readable by everybody.
		if (!Files.exists(file)) Files.createFile(file);
		onlyOwner(file);
		Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
		onlyOwner(file);
	}

	/**
	 * Permissions naming one person: whoever owns it.
	 *
	 * Both kinds of filesystem, because the two do not share a vocabulary — a
	 * POSIX mode and a Windows access list are different enough that a single
	 * call cannot express this. Failure is warned about and not thrown: a key
	 * that is saved on a filesystem which cannot express permissions at all, a
	 * memory stick formatted as FAT, is still better than a key the person has
	 * to type in every time — but they should be told.
	 */
	private static void onlyOwner(Path file) {
		try {
			PosixFileAttributeView posix =
				Files.getFileAttributeView(file, PosixFileAttributeView.class);
			if (posix != null) {
				posix.setPermissions(EnumSet.of(
					PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
				return;
			}
			AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
			if (acl != null) {
				AclEntry owner = AclEntry.newBuilder()
					.setType(AclEntryType.ALLOW)
					.setPrincipal(acl.getOwner())
					.setPermissions(EnumSet.allOf(AclEntryPermission.class))
					.build();
				acl.setAcl(List.of(owner));
				return;
			}
			NpcStudio.LOGGER.warn("{} sits on a filesystem that cannot restrict who reads it",
				FILE);
		} catch (IOException | RuntimeException refused) {
			NpcStudio.LOGGER.warn("Could not restrict who may read {}: {}",
				FILE, refused.getMessage());
		}
	}

	/**
	 * A key as it is allowed to appear on a screen.
	 *
	 * The last four characters and nothing else, because the only question an
	 * interface has to answer about a key is "is this the one I pasted" — and
	 * four characters answer it. The length is not shown either: a mask as long
	 * as the key states its length, and the length of a key is a fact about the
	 * key.
	 */
	public static String masked(String key) {
		if (key == null || key.isBlank()) return "";
		String trimmed = key.trim();
		if (trimmed.length() <= 4) return "*".repeat(trimmed.length());
		return "*".repeat(12) + trimmed.substring(trimmed.length() - 4);
	}
}
