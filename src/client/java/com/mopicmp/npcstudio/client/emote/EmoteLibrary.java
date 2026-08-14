package com.mopicmp.npcstudio.client.emote;

import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Every emote the game can find, and none of them loaded until needed.
 *
 * The pack is seven hundred emotes and a hundred and seventy megabytes, and
 * almost all of that is keyframes. Reading it at startup would buy a several
 * second pause for data that is thrown away unused — a player who never opens
 * the picker needs none of it, and one who does needs one at a time.
 *
 * So there are two halves. The index is a single small file listing what exists;
 * it is read once and kept. The keyframes live in a file per emote and are
 * parsed the first time something asks to play one, then cached.
 *
 * Icons need no help at all: they are ordinary textures under an ordinary
 * identifier, so the game loads them when they are first drawn and manages them
 * afterwards. That is most of the reason the pack lives in resources rather than
 * in a folder of its own.
 */
public final class EmoteLibrary {

	private static final String FOLDER = "emotes";
	private static final Identifier INDEX = NpcStudio.id(FOLDER + "/index.json");

	/** What the picker needs to draw a row: everything except the movement. */
	public record Entry(String id, String name, String author, String description,
			Identifier icon, List<String> tags) {

		/**
		 * The name with its colour codes stripped, for matching against.
		 *
		 * These names are written with section signs in them, so a search for
		 * "меч" would otherwise miss a name that happens to have a colour change
		 * in the middle of the word.
		 */
		public String searchable() {
			return strip(name + " " + author + " " + description).toLowerCase(Locale.ROOT);
		}
	}

	private static List<Entry> index = List.of();
	private static final Map<String, Entry> byId = new HashMap<>();
	private static final Map<String, Emote> loaded = new HashMap<>();

	/**
	 * Emotes the player has put there themselves.
	 *
	 * Kept apart from the ones in the mod's resources because they are read
	 * differently — straight off the disk rather than through a resource pack —
	 * and because they can appear while the game is running, which a resource
	 * cannot. Everything downstream sees one list either way.
	 */
	private static final Map<String, java.nio.file.Path> imported = new HashMap<>();

	public static java.nio.file.Path folder() {
		return net.fabricmc.loader.api.FabricLoader.getInstance()
			.getConfigDir().resolve("npc_studio").resolve("emotes");
	}

	private EmoteLibrary() { }

	/**
	 * Reads the index.
	 *
	 * Called on resource reload as well as at startup, because a resource pack
	 * can add emotes and the list has to notice. The parsed keyframes are dropped
	 * at the same time — a pack could have replaced them.
	 */
	public static void reload(ResourceManager manager) {
		loaded.clear();
		byId.clear();
		List<Entry> found = new ArrayList<>();
		List<Category> groups = new ArrayList<>();

		// Every index across every pack, not just the winning one, so a pack that
		// adds emotes does not have to restate the ones already there.
		for (Resource resource : manager.getResourceStack(INDEX)) {
			try (Reader reader = new InputStreamReader(resource.open(), java.nio.charset.StandardCharsets.UTF_8)) {
				JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

				JsonArray emotes = root.getAsJsonArray("emotes");
				if (emotes != null) {
					for (JsonElement element : emotes) {
						Entry entry = entry(element.getAsJsonObject());
						if (entry != null && !byId.containsKey(entry.id())) {
							byId.put(entry.id(), entry);
							found.add(entry);
						}
					}
				}

				JsonObject tree = root.getAsJsonObject("categories");
				if (tree != null) {
					for (Map.Entry<String, JsonElement> group : tree.entrySet()) {
						List<String> children = new ArrayList<>();
						for (JsonElement child : group.getValue().getAsJsonArray()) {
							children.add(child.getAsString());
						}
						groups.add(new Category(group.getKey(), List.copyOf(children)));
					}
				}
			} catch (Exception broken) {
				NpcStudio.LOGGER.warn("Could not read an emote index: {}", broken.toString());
			}
		}

		imported.clear();
		found.addAll(scanFolder());

		found.sort((a, b) -> strip(a.name()).compareToIgnoreCase(strip(b.name())));
		index = List.copyOf(found);
		categories = List.copyOf(groups);
		NpcStudio.LOGGER.info("{} emotes available in {} categories, {} of them imported",
			index.size(), categories.size(), imported.size());
	}

	/**
	 * Everything the player has dropped into their own emote folder.
	 *
	 * Read here rather than indexed ahead of time, because there is no build step
	 * for a file somebody saved a minute ago. Only the labels are parsed — the
	 * keyframes wait until something plays them, the same as the built-in ones.
	 */
	private static List<Entry> scanFolder() {
		java.nio.file.Path folder = folder();
		List<Entry> found = new ArrayList<>();
		if (!java.nio.file.Files.isDirectory(folder)) return found;

		try (var files = java.nio.file.Files.list(folder)) {
			for (java.nio.file.Path file : files.toList()) {
				String name = file.getFileName().toString();
				if (!name.endsWith(".json")) continue;
				String id = "imported/" + name.substring(0, name.length() - 5).toLowerCase(Locale.ROOT)
					.replaceAll("[^a-z0-9_.-]+", "_");
				if (byId.containsKey(id)) continue;

				try (Reader reader = java.nio.file.Files.newBufferedReader(
						file, java.nio.charset.StandardCharsets.UTF_8)) {
					JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
					Entry entry = new Entry(id,
						EmoteParser.text(root.get("name"), name),
						root.has("author") ? root.get("author").getAsString() : "",
						EmoteParser.text(root.get("description"), ""),
						ImportedIcons.load(id, file), List.of());
					imported.put(id, file);
					byId.put(id, entry);
					found.add(entry);
				} catch (Exception broken) {
					// Named, because a file somebody just added and cannot see is a
					// question they will otherwise ask the log about anyway.
					NpcStudio.LOGGER.warn("Could not read imported emote {}: {}", name, broken.toString());
				}
			}
		} catch (Exception failed) {
			NpcStudio.LOGGER.warn("Could not read {}: {}", folder, failed.toString());
		}
		return found;
	}

	private static Entry entry(JsonObject object) {
		JsonElement id = object.get("id");
		if (id == null) return null;

		List<String> tags = new ArrayList<>();
		JsonElement listed = object.get("tags");
		if (listed != null && listed.isJsonArray()) {
			for (JsonElement tag : listed.getAsJsonArray()) tags.add(tag.getAsString());
		}

		return new Entry(id.getAsString(),
			EmoteParser.text(object.get("name"), id.getAsString()),
			object.has("author") ? object.get("author").getAsString() : "",
			EmoteParser.text(object.get("description"), ""),
			object.has("icon") && object.get("icon").getAsBoolean()
				? NpcStudio.id(FOLDER + "/" + id.getAsString() + ".png") : null,
			List.copyOf(tags));
	}

	/**
	 * A category and the ones filed under it.
	 *
	 * Which label belongs under which is worked out from the pack when it is
	 * unpacked, by containment: every "JoJo" emote is also "Мульти вселенная",
	 * so JoJo sits inside it. Nothing here is typed out by hand, so a pack that
	 * arrives with categories nobody has heard of still sorts itself.
	 */
	public record Category(String name, List<String> children) { }

	private static List<Category> categories = List.of();

	/** The categories, commonest first. */
	public static List<Category> categories() {
		return categories;
	}

	/** Whether an emote falls under a category, counting anything filed beneath it. */
	public static boolean within(Entry entry, String category) {
		if (category == null) return true;
		if (entry.tags().contains(category)) return true;
		for (Category group : categories) {
			if (!group.name().equals(category)) continue;
			for (String child : group.children()) {
				if (entry.tags().contains(child)) return true;
			}
		}
		return false;
	}

	/** The emotes carrying no label at all, which is a quarter of the pack. */
	public static boolean unlabelled(Entry entry) {
		return entry.tags().isEmpty();
	}

	public static List<Entry> all() {
		return index;
	}

	public static Entry find(String id) {
		return byId.get(id);
	}

	public static boolean known(String id) {
		return byId.containsKey(id);
	}

	/**
	 * The movement, parsed on first use and kept afterwards.
	 *
	 * Empty rather than throwing when an emote will not load: the caller is a
	 * renderer, and a character standing still is a better answer than a frame
	 * that fails.
	 */
	public static Optional<Emote> emote(String id) {
		if (id == null || id.isEmpty()) return Optional.empty();
		Emote cached = loaded.get(id);
		if (cached != null) return Optional.of(cached);
		if (loaded.containsKey(id)) return Optional.empty();

		Optional<Emote> parsed = read(id);
		// Recorded either way, so a broken emote is attempted once rather than
		// once a frame for as long as it is selected.
		loaded.put(id, parsed.orElse(null));
		return parsed;
	}

	private static Optional<Emote> read(String id) {
		// An imported one first, since it is the only place it could be. The two
		// live in different worlds — one behind the resource manager, one on the
		// disk — and this is the single spot that has to know the difference.
		java.nio.file.Path own = imported.get(id);
		if (own != null) {
			try (Reader reader = java.nio.file.Files.newBufferedReader(
					own, java.nio.charset.StandardCharsets.UTF_8)) {
				return Optional.of(EmoteParser.parse(id, JsonParser.parseReader(reader).getAsJsonObject()));
			} catch (Exception broken) {
				NpcStudio.LOGGER.warn("Could not read imported emote {}: {}", id, broken.toString());
				return Optional.empty();
			}
		}

		ResourceManager manager = Minecraft.getInstance().getResourceManager();
		Identifier path = NpcStudio.id(FOLDER + "/" + id + ".json");
		Optional<Resource> resource = manager.getResource(path);
		if (resource.isEmpty()) {
			NpcStudio.LOGGER.warn("No such emote: {}", id);
			return Optional.empty();
		}
		try (Reader reader = new InputStreamReader(resource.get().open(), java.nio.charset.StandardCharsets.UTF_8)) {
			return Optional.of(EmoteParser.parse(id, JsonParser.parseReader(reader).getAsJsonObject()));
		} catch (Exception broken) {
			NpcStudio.LOGGER.warn("Could not read emote {}: {}", id, broken.toString());
			return Optional.empty();
		}
	}

	/** Everything matching what has been typed, across name, author and description. */
	public static List<Entry> search(String query) {
		String needle = strip(query).trim().toLowerCase(Locale.ROOT);
		if (needle.isEmpty()) return all();
		// Split on spaces so several words narrow rather than having to appear
		// together in that order — "меч удар" should find "удар тяжелым мечом".
		String[] words = needle.split("\\s+");
		return all().stream().filter(entry -> {
			String haystack = entry.searchable();
			for (String word : words) {
				if (!haystack.contains(word)) return false;
			}
			return true;
		}).toList();
	}

	/** Drops the section-sign colour codes a name is written with. */
	public static String strip(String text) {
		if (text == null || text.indexOf('§') < 0) return text == null ? "" : text;
		StringBuilder clean = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			if (text.charAt(i) == '§') i++;
			else clean.append(text.charAt(i));
		}
		return clean.toString();
	}
}
