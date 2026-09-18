package com.mopicmp.npcstudio.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mopicmp.npcstudio.NpcStudio;

/**
 * Reading what is installed on a server without starting it.
 *
 * Everything needed is inside the jars. A Bukkit plugin carries
 * {@code plugin.yml} at its root, a newer Paper one {@code paper-plugin.yml}, a
 * Fabric mod {@code fabric.mod.json}, a NeoForge mod
 * {@code META-INF/neoforge.mods.toml}. So the list on the screen is read from
 * the files, not asked of a server that may not even start — which matters most
 * exactly when it will not, because the thing stopping it is usually in this
 * list.
 *
 * <b>On not parsing YAML.</b> There is no YAML library here and there is not
 * going to be one for four keys. What is read is the top level of a plugin's
 * descriptor: a name, a colon, a value, and nothing nested. That covers every
 * plugin.yml anybody writes for these fields, and where it does not, the file
 * name is used instead — a list with one entry named after its jar is a much
 * smaller failure than a dependency on a parser for a format we barely touch.
 */
public final class Addons {

	private Addons() {
	}

	/** Which folder a core keeps its additions in. */
	public static String folderFor(String core) {
		return switch (core) {
			case "paper", "purpur", "spigot", "bukkit" -> "plugins";
			default -> "mods";
		};
	}

	public static Addon.Kind kindFor(String core) {
		return folderFor(core).equals("plugins") ? Addon.Kind.PLUGIN : Addon.Kind.MOD;
	}

	public static Path folder(ManagedServer server) {
		return server.path().resolve(folderFor(server.core));
	}

	/** Everything in a server's folder, by name, whether it describes itself or not. */
	public static List<Addon> installed(ManagedServer server) {
		Path folder = folder(server);
		Addon.Kind kind = kindFor(server.core);
		List<Addon> found = new ArrayList<>();
		if (!Files.isDirectory(folder)) return found;

		try (DirectoryStream<Path> listing = Files.newDirectoryStream(folder, "*.jar")) {
			for (Path jar : listing) {
				found.add(read(jar, kind));
			}
		} catch (IOException unreadable) {
			NpcStudio.LOGGER.warn("Could not list {}: {}", folder, unreadable.toString());
		}
		found.sort(java.util.Comparator.comparing(addon -> addon.label().toLowerCase(Locale.ROOT)));
		return found;
	}

	/**
	 * What one jar says about itself.
	 *
	 * Never throws. A jar that cannot be opened, or has no descriptor we know, is
	 * still installed and still has to appear — being unable to read it is not a
	 * reason to hide it from the person looking for what broke their server.
	 */
	public static Addon read(Path jar, Addon.Kind kind) {
		String file = jar.getFileName().toString();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			Addon fabric = fabric(zip, file);
			if (fabric != null) return fabric;
			Addon bukkit = bukkit(zip, file, "paper-plugin.yml");
			if (bukkit != null) return bukkit;
			bukkit = bukkit(zip, file, "plugin.yml");
			if (bukkit != null) return bukkit;
			Addon forge = forge(zip, file);
			if (forge != null) return forge;
		} catch (IOException unreadable) {
			NpcStudio.LOGGER.debug("Could not read {}: {}", file, unreadable.toString());
		}
		return new Addon(file, file, stripped(file), "", kind, Addon.Side.UNSAID, List.of(), false);
	}

	/** A file name with the version and the extension taken off, as a last resort. */
	private static String stripped(String file) {
		String name = file.endsWith(".jar") ? file.substring(0, file.length() - 4) : file;
		int dash = name.indexOf('-');
		return dash > 0 ? name.substring(0, dash) : name;
	}

	private static String text(ZipFile zip, String path) throws IOException {
		ZipEntry entry = zip.getEntry(path);
		if (entry == null) return null;
		try (InputStream in = zip.getInputStream(entry)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	// ------------------------------------------------------------------ fabric

	static Addon fabricFrom(String json, String file) {
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		String id = string(root, "id", file);
		String name = string(root, "name", id);
		String version = string(root, "version", "");

		// The one field this whole class exists for.
		Addon.Side side = switch (string(root, "environment", "*")) {
			case "client" -> Addon.Side.CLIENT_ONLY;
			case "server" -> Addon.Side.SERVER_ONLY;
			default -> Addon.Side.BOTH;
		};

		List<String> needs = new ArrayList<>();
		JsonElement depends = root.get("depends");
		if (depends != null && depends.isJsonObject()) {
			for (String each : depends.getAsJsonObject().keySet()) {
				// Everything depends on these two; naming them is noise.
				if (!each.equals("minecraft") && !each.equals("java")
					&& !each.equals("fabricloader")) {
					needs.add(each);
				}
			}
		}
		return new Addon(file, id, name, version, Addon.Kind.MOD, side, needs, true);
	}

	private static Addon fabric(ZipFile zip, String file) throws IOException {
		String json = text(zip, "fabric.mod.json");
		if (json == null) return null;
		try {
			return fabricFrom(json, file);
		} catch (RuntimeException broken) {
			NpcStudio.LOGGER.debug("Odd fabric.mod.json in {}: {}", file, broken.toString());
			return null;
		}
	}

	private static String string(JsonObject object, String key, String fallback) {
		JsonElement value = object.get(key);
		if (value == null || !value.isJsonPrimitive()) return fallback;
		return value.getAsString();
	}

	// ------------------------------------------------------------------ bukkit

	/**
	 * The top level of a plugin descriptor.
	 *
	 * Lines that begin with a space belong to something nested and are skipped;
	 * so are comments and the document markers. Quotes and a trailing comment are
	 * taken off the value, which is the whole of what these files do with them.
	 */
	static java.util.Map<String, String> topLevel(String yaml) {
		java.util.Map<String, String> read = new java.util.LinkedHashMap<>();
		for (String raw : yaml.split("\r\n|\n|\r")) {
			if (raw.isBlank() || raw.startsWith("#") || raw.startsWith("---")) continue;
			if (Character.isWhitespace(raw.charAt(0))) continue;
			int colon = raw.indexOf(':');
			if (colon <= 0) continue;
			String key = raw.substring(0, colon).trim();
			String value = raw.substring(colon + 1).trim();
			if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
				value = value.substring(1, value.length() - 1);
			} else if (value.startsWith("'") && value.endsWith("'") && value.length() > 1) {
				value = value.substring(1, value.length() - 1);
			}
			read.put(key, value);
		}
		return read;
	}

	private static Addon bukkit(ZipFile zip, String file, String path) throws IOException {
		String yaml = text(zip, path);
		if (yaml == null) return null;
		var read = topLevel(yaml);
		String name = read.getOrDefault("name", stripped(file));
		List<String> needs = new ArrayList<>();
		String depend = read.get("depend");
		if (depend != null) {
			for (String each : depend.replace("[", "").replace("]", "").split(",")) {
				if (!each.isBlank()) needs.add(each.trim());
			}
		}
		// A plugin is a server thing by definition; there is no other side for it
		// to be on, and no plugin descriptor has ever said so.
		return new Addon(file, name, name, read.getOrDefault("version", ""),
			Addon.Kind.PLUGIN, Addon.Side.SERVER_ONLY, needs, true);
	}

	// ------------------------------------------------------------------ forge

	private static Addon forge(ZipFile zip, String file) throws IOException {
		String toml = text(zip, "META-INF/neoforge.mods.toml");
		if (toml == null) toml = text(zip, "META-INF/mods.toml");
		if (toml == null) return null;

		String id = "";
		String name = "";
		String version = "";
		for (String raw : toml.split("\r\n|\n|\r")) {
			String line = raw.trim();
			int equals = line.indexOf('=');
			if (equals <= 0) continue;
			String key = line.substring(0, equals).trim();
			String value = line.substring(equals + 1).trim().replace("\"", "");
			switch (key) {
				case "modId" -> {
					if (id.isEmpty()) id = value;
				}
				case "displayName" -> {
					if (name.isEmpty()) name = value;
				}
				case "version" -> {
					if (version.isEmpty()) version = value;
				}
				default -> {
					// Everything else in the file is about loading, not identity.
				}
			}
		}
		if (id.isEmpty() && name.isEmpty()) return null;
		return new Addon(file, id.isEmpty() ? name : id,
			name.isEmpty() ? id : name, version, Addon.Kind.MOD, Addon.Side.BOTH, List.of(), true);
	}

	/**
	 * The picture a mod carries inside itself, if it carries one.
	 *
	 * Fabric mods name theirs in {@code fabric.mod.json}, and it is a PNG inside
	 * the same jar — so the installed list can show real icons without asking
	 * anybody's server for them. Plugins have no such convention and get a letter
	 * instead.
	 */
	public static byte[] icon(Path jar) {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			String json = text(zip, "fabric.mod.json");
			if (json == null) return null;
			JsonObject root = JsonParser.parseString(json).getAsJsonObject();
			JsonElement icon = root.get("icon");
			if (icon == null) return null;

			String path;
			if (icon.isJsonPrimitive()) {
				path = icon.getAsString();
			} else if (icon.isJsonObject()) {
				// Some declare a set of sizes; the largest that is there will do.
				JsonObject sizes = icon.getAsJsonObject();
				path = null;
				for (String key : sizes.keySet()) path = sizes.get(key).getAsString();
			} else {
				return null;
			}
			if (path == null || path.isBlank()) return null;

			ZipEntry entry = zip.getEntry(path);
			if (entry == null || entry.getSize() > 512 * 1024) return null;
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		} catch (IOException | RuntimeException unreadable) {
			return null;
		}
	}

	// ------------------------------------------------------------------ putting one in

	/**
	 * Copy a jar into the server's folder, having looked at it first.
	 *
	 * @return what to tell somebody, or empty when it went in
	 */
	public static String install(ManagedServer server, Path jar) {
		if (!Files.isRegularFile(jar)
			|| !jar.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
			return "that is not a jar";
		}
		Addon.Kind kind = kindFor(server.core);
		Addon addon = read(jar, kind);
		if (addon.wrongSide()) {
			return "that one is for the client — it will not run on a server";
		}
		if (kind == Addon.Kind.PLUGIN && addon.described() && addon.kind() == Addon.Kind.MOD) {
			return "that is a mod, and this core takes plugins";
		}
		if (kind == Addon.Kind.MOD && addon.described() && addon.kind() == Addon.Kind.PLUGIN) {
			return "that is a plugin, and this core takes mods";
		}
		try {
			Path folder = folder(server);
			Files.createDirectories(folder);
			Files.copy(jar, folder.resolve(jar.getFileName().toString()),
				java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			return "";
		} catch (IOException failed) {
			return failed.getMessage() == null ? failed.toString() : failed.getMessage();
		}
	}

	/**
	 * Take one out.
	 *
	 * The name comes from a list we made from the folder, and it still cannot
	 * become a path without being checked — see the rule in
	 * {@code docs/security.md}. A delete is not the place to make an exception
	 * for a name that is probably fine.
	 */
	public static String remove(ManagedServer server, String file) {
		Path folder = folder(server).toAbsolutePath().normalize();
		Path target = folder.resolve(file).normalize();
		if (!target.startsWith(folder) || !target.getFileName().toString().equals(file)) {
			return "that name does not belong to this folder";
		}
		try {
			Files.deleteIfExists(target);
			return "";
		} catch (IOException failed) {
			return failed.getMessage() == null ? failed.toString() : failed.getMessage();
		}
	}
}
