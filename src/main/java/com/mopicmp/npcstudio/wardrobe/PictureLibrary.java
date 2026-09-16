package com.mopicmp.npcstudio.wardrobe;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.server.MinecraftServer;

/**
 * Every costume the world knows, and where each one is filed.
 *
 * Shared rather than kept per character, because dressing ten guards alike
 * should not mean doing the same thing ten times. It lives with the world for
 * the same reason dialogues do: it is part of what was built, not part of who
 * is looking.
 *
 * <h2>Why a picture and a record are different things</h2>
 *
 * This is the one decision the rest follows from, and it comes from a plain
 * requirement: somebody with a grudge and permissions must not be able to end
 * a project on their way out.
 *
 * A skin is written to disk under a name that is its own fingerprint. Identical
 * pictures are one file, and a file is never rewritten and never removed. The
 * library is a separate thing — a short list of records saying what a costume
 * is called, which category it is in, and which picture it uses.
 *
 * So <b>deleting a costume deletes a record, not a picture</b>. Somebody who
 * wipes the entire wardrobe has destroyed a small text file. Every skin is
 * still there, to the last one.
 *
 * And the list itself is kept in versions: before every change the previous one
 * is written aside, exactly as the dialogue editor keeps drafts, and for the
 * same reason — it costs almost nothing and it is the difference between an
 * afternoon and a project.
 */
public final class SkinLibrary {

	/**
	 * Down to the millisecond, and still checked for collisions.
	 *
	 * Seconds were not enough and the tests said so: several changes inside one
	 * second all wrote the same filename, so each quietly replaced the last and
	 * the history was one entry deep. A safety net with one strand is not one.
	 */
	private static final DateTimeFormatter WHEN =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss-SSS").withZone(ZoneId.systemDefault());

	/** How many past versions of the list to keep. Generous: they are tiny. */
	private static final int VERSIONS = 40;

	/**
	 * One costume as the library records it.
	 *
	 * The build travels with the costume because it belongs to it: "blacksmith"
	 * is a skin and a figure at once, and dressing ten guards alike should be one
	 * action rather than ten. It is copied onto a character when worn rather than
	 * looked up while drawing — a client that is only watching has no wardrobe to
	 * look anything up in.
	 */
	public record Entry(String id, String label, String category, String group,
			String fingerprint, com.mopicmp.npcstudio.entity.BodyShape shape,
			com.mopicmp.npcstudio.entity.FaceMask face) {

		public Entry(String id, String label, String category, String group, String fingerprint) {
			this(id, label, category, group, fingerprint,
				com.mopicmp.npcstudio.entity.BodyShape.DEFAULT,
				com.mopicmp.npcstudio.entity.FaceMask.NONE);
		}

		public Entry(String id, String label, String category, String group,
				String fingerprint, com.mopicmp.npcstudio.entity.BodyShape shape) {
			this(id, label, category, group, fingerprint, shape,
				com.mopicmp.npcstudio.entity.FaceMask.NONE);
		}

		/**
		 * The same face in eighths, which is what a character actually wears.
		 *
		 * The mask is kept at whatever size it was marked at, and everything that
		 * blinks still speaks eight by eight — so this is where the two meet. It is
		 * a method rather than a field so that the fine answer stays the record and
		 * the coarse one stays a view of it, which is the right way round: a view
		 * can be improved later without the file having to be rewritten.
		 */
		public com.mopicmp.npcstudio.entity.EyeMap eyes() {
			return face.reduce();
		}
	}

	private final Path root;
	private final List<Entry> entries = new ArrayList<>();

	public SkinLibrary(Path root) {
		this.root = root;
		read();
	}

	public static SkinLibrary of(MinecraftServer server) {
		return new SkinLibrary(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
			.resolve("npcstudio"));
	}

	private Path pictures() {
		return root.resolve("skins");
	}

	private Path list() {
		return root.resolve("wardrobe.json");
	}

	private Path versions() {
		return root.resolve("wardrobe");
	}

	public List<Entry> entries() {
		return List.copyOf(entries);
	}

	public Optional<Entry> find(String id) {
		return entries.stream().filter(entry -> entry.id().equals(id)).findFirst();
	}

	/**
	 * Files a picture and records it.
	 *
	 * The picture is written first and the record second, which is the safe order
	 * of the two: a picture nothing points at is wasted space, while a record
	 * pointing at nothing is a costume that cannot be worn.
	 */
	public Entry add(String label, String category, String group, byte[] png) {
		String fingerprint = fingerprint(png);
		try {
			Files.createDirectories(pictures());
			Path file = pictures().resolve(fingerprint + ".png");
			// Never rewritten. The name is the content, so a file that is already
			// there is already correct, and writing over it could only make it wrong.
			if (!Files.exists(file)) Files.write(file, png);
		} catch (Exception failed) {
			NpcStudio.LOGGER.warn("Could not store a skin: {}", failed.toString());
			return null;
		}

		Entry entry = new Entry(nextId(), label, category, group, fingerprint);
		entries.add(entry);
		write();
		return entry;
	}

	public byte[] picture(String fingerprint) {
		try {
			Path file = pictures().resolve(fingerprint + ".png");
			return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
		} catch (Exception failed) {
			NpcStudio.LOGGER.warn("Could not read skin {}: {}", fingerprint, failed.toString());
			return null;
		}
	}

	/**
	 * Forgets a costume.
	 *
	 * The record goes and the picture stays. That is not an oversight to tidy up
	 * later — it is the whole safety net. Anything deleted here can be put back
	 * by restoring a version of the list, and the pictures it points at will
	 * still be on disk waiting.
	 */
	public void remove(List<String> ids) {
		if (entries.removeIf(entry -> ids.contains(entry.id()))) write();
	}

	/** Gives a costume a build. Everyone dressed in it afterwards gets it too. */
	/**
	 * Records where a costume's face keeps its eyes.
	 *
	 * Written into the library rather than kept on one character, for the same
	 * reason the build is: a face belongs to the costume, and marking it once
	 * should serve every character who ever wears it. It also means the answer
	 * lands in {@code wardrobe.json}, where it can be read back and measured
	 * against — which is the only way anybody will ever know whether the reading
	 * is getting better.
	 */
	public void relook(String id, com.mopicmp.npcstudio.entity.FaceMask face) {
		for (int i = 0; i < entries.size(); i++) {
			Entry entry = entries.get(i);
			if (!entry.id().equals(id)) continue;
			entries.set(i, new Entry(entry.id(), entry.label(), entry.category(),
				entry.group(), entry.fingerprint(), entry.shape(),
				face == null ? com.mopicmp.npcstudio.entity.FaceMask.NONE : face));
			write();
			return;
		}
	}

	public void reshape(String id, com.mopicmp.npcstudio.entity.BodyShape shape) {
		for (int i = 0; i < entries.size(); i++) {
			Entry entry = entries.get(i);
			if (!entry.id().equals(id)) continue;
			entries.set(i, new Entry(entry.id(), entry.label(), entry.category(),
				entry.group(), entry.fingerprint(), shape, entry.face()));
			write();
			return;
		}
	}

	/** Moves costumes to another shelf. A category is a label, not a box. */
	public void refile(List<String> ids, String category, String group) {
		boolean changed = false;
		for (int i = 0; i < entries.size(); i++) {
			Entry entry = entries.get(i);
			if (!ids.contains(entry.id())) continue;
			// The face comes along, like the build. Leaving it off here is what made
			// a marking somebody had made by hand vanish the moment they filed the
			// costume on another shelf — and it did not vanish visibly, it came back
			// as the reading's own guess after the next reload.
			entries.set(i, new Entry(entry.id(), entry.label(), category, group,
				entry.fingerprint(), entry.shape(), entry.face()));
			changed = true;
		}
		if (changed) write();
	}

	/**
	 * Puts the same costumes on a second shelf.
	 *
	 * Free, and that is the point of storing pictures under their own fingerprint.
	 * A copy is a new record naming a picture that is already on disk, so having
	 * the guard's outfit in both "Guards" and "Favourites" costs one line of text
	 * rather than a second copy of the skin. Ten copies still weigh one skin.
	 *
	 * New identities, though, not shared ones. Two records that shared an id would
	 * be one costume that appears twice, and removing it from one shelf would take
	 * it off the other.
	 */
	public void copy(List<String> ids, String category, String group) {
		// Gathered before anything is added, or the list being walked grows under
		// the walk and the copies start copying themselves.
		List<Entry> wanted = entries.stream().filter(entry -> ids.contains(entry.id())).toList();
		if (wanted.isEmpty()) return;
		for (Entry entry : wanted) {
			entries.add(new Entry(nextId(), entry.label(), category, group,
				entry.fingerprint(), entry.shape(), entry.face()));
		}
		write();
	}

	public void rename(String id, String label) {
		for (int i = 0; i < entries.size(); i++) {
			if (!entries.get(i).id().equals(id)) continue;
			Entry entry = entries.get(i);
			entries.set(i, new Entry(entry.id(), label, entry.category(), entry.group(),
				entry.fingerprint(), entry.shape(), entry.face()));
			write();
			return;
		}
	}

	/**
	 * Empties a category without emptying its contents.
	 *
	 * What was in it becomes uncategorised rather than deleted. A category is a
	 * label somebody wrote on a shelf, and throwing away the shelf is no reason
	 * to throw away what was on it.
	 */
	public void dropCategory(String category, String group) {
		boolean changed = false;
		for (int i = 0; i < entries.size(); i++) {
			Entry entry = entries.get(i);
			boolean hit = entry.category().equals(category)
				&& (group.isEmpty() || entry.group().equals(group));
			if (!hit) continue;
			entries.set(i, new Entry(entry.id(), entry.label(),
				group.isEmpty() ? "" : category, "", entry.fingerprint(),
				entry.shape(), entry.face()));
			changed = true;
		}
		if (changed) write();
	}

	// ------------------------------------------------------------ versions

	/** The saved versions of the list, newest first. */
	public List<String> history() {
		if (!Files.isDirectory(versions())) return List.of();
		try (var files = Files.list(versions())) {
			return files.map(path -> path.getFileName().toString())
				.filter(name -> name.endsWith(".json"))
				.map(name -> name.substring(0, name.length() - 5))
				.sorted(Comparator.reverseOrder())
				.toList();
		} catch (Exception failed) {
			NpcStudio.LOGGER.warn("Could not list wardrobe versions: {}", failed.toString());
			return List.of();
		}
	}

	/** Puts a past version back. The pictures it names are still on disk. */
	public boolean restore(String version) {
		// The name of a version arrives from whoever clicked, which means it
		// arrives over the network, which means it is not to be trusted with a
		// filesystem. A name like "../../../server" resolved happily before this
		// check existed. Two locks: the shape of the name, and the fact that the
		// resolved path still sits inside the folder it was supposed to.
		if (!version.matches("[0-9 :-]{1,40}")) return false;
		Path file = versions().resolve(version + ".json").normalize();
		if (!file.startsWith(versions().normalize())) return false;
		if (!Files.isRegularFile(file)) return false;
		try {
			// Read before anything is written. Keeping the current state aside first
			// was the obvious order and the wrong one: the copy could land on the
			// very file being restored, and the restore then put back what it had
			// just overwritten. The safety net cut its own rope.
			List<Entry> wanted = parse(Files.readString(file, StandardCharsets.UTF_8));

			// Now the current state is kept, so that restoring is itself undoable —
			// putting back the wrong version should not be the end of the matter.
			keepVersion();
			entries.clear();
			entries.addAll(wanted);
			writeList();
			return true;
		} catch (Exception failed) {
			NpcStudio.LOGGER.warn("Could not restore wardrobe {}: {}", version, failed.toString());
			return false;
		}
	}

	// -------------------------------------------------------------- on disk

	private void write() {
		keepVersion();
		writeList();
	}

	private void keepVersion() {
		try {
			if (!Files.isRegularFile(list())) return;
			Files.createDirectories(versions());
			// Never over an existing version, whatever the clock says. A name that
			// collides is a version silently lost.
			String stamp = WHEN.format(Instant.now());
			Path target = versions().resolve(stamp + ".json");
			for (int attempt = 1; Files.exists(target); attempt++) {
				target = versions().resolve(stamp + "-" + attempt + ".json");
			}
			Files.copy(list(), target);
			prune();
		} catch (Exception failed) {
			NpcStudio.LOGGER.warn("Could not keep a wardrobe version: {}", failed.toString());
		}
	}

	private void prune() {
		List<String> kept = history();
		for (int i = VERSIONS; i < kept.size(); i++) {
			try {
				Files.deleteIfExists(versions().resolve(kept.get(i) + ".json"));
			} catch (Exception ignored) {
				// An old version that will not delete is nobody's problem.
			}
		}
	}

	private void writeList() {
		try {
			Files.createDirectories(root);
			JsonArray array = new JsonArray();
			for (Entry entry : entries) {
				JsonObject object = new JsonObject();
				object.addProperty("id", entry.id());
				object.addProperty("label", entry.label());
				object.addProperty("category", entry.category());
				object.addProperty("group", entry.group());
				object.addProperty("skin", entry.fingerprint());
				// Left out entirely when there is nothing to say, so a wardrobe of
				// ordinary costumes reads as it always did.
				if (!entry.shape().isDefault()) object.add("shape", entry.shape().toJson());
				// Only when there is something to say, so a file of costumes nobody has
				// looked at stays as short as it was. As hex rather than as numbers: a
				// mask sets the top bit as readily as any other, and a sixty-four bit
				// number that has gone through a JSON reader as a decimal is a thing to
				// check rather than to assume.
				if (!entry.face().isNone()) {
					JsonObject face = new JsonObject();
					// The mask as it was actually marked, at whatever size that was.
					// Run-length encoded, because a face marked on a 2048-wide skin is
					// sixty-five thousand bits and a handful of blobs at the same time.
					face.addProperty("mask", entry.face().encode());
					// And the same answer in eighths, which is what an older build of
					// the mod knows how to read. Written as well rather than instead, so
					// that a world opened by yesterday's jar still blinks — the fine
					// answer is simply ignored there.
					face.addProperty("eyes", Long.toHexString(entry.eyes().eyes()));
					face.addProperty("whites", Long.toHexString(entry.eyes().whites()));
					face.addProperty("brows", Long.toHexString(entry.eyes().brows()));
					face.addProperty("byHand", entry.face().authored());
					object.add("face", face);
				}
				array.add(object);
			}
			JsonObject root = new JsonObject();
			root.add("outfits", array);
			Files.writeString(list(),
				new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
		} catch (Exception failed) {
			NpcStudio.LOGGER.warn("Could not write the wardrobe: {}", failed.toString());
		}
	}

	/**
	 * The mask an entry carries, or none for a costume nobody has looked at.
	 *
	 * The fine mask wins where there is one and the three old hex fields are read
	 * where there is not — which is every wardrobe written before faces could be
	 * marked at the size they were drawn. Somebody who marked a hundred costumes
	 * under the old build keeps all hundred, at the resolution they made them.
	 */
	private static com.mopicmp.npcstudio.entity.FaceMask faceOf(JsonObject object) {
		if (!object.has("face") || !object.get("face").isJsonObject()) {
			return com.mopicmp.npcstudio.entity.FaceMask.NONE;
		}
		JsonObject face = object.getAsJsonObject("face");
		boolean byHand = face.has("byHand") && face.get("byHand").getAsBoolean();
		if (face.has("mask") && face.get("mask").isJsonPrimitive()) {
			var fine = com.mopicmp.npcstudio.entity.FaceMask.decode(
				face.get("mask").getAsString(), byHand);
			if (!fine.isNone()) return fine;
		}
		return com.mopicmp.npcstudio.entity.FaceMask.of(new com.mopicmp.npcstudio.entity.EyeMap(
			mask(face, "eyes"), mask(face, "whites"), mask(face, "brows"), byHand));
	}

	/**
	 * One mask off the file.
	 *
	 * Unreadable means nought rather than an exception: one costume whose face was
	 * hand-edited into nonsense should cost that costume its blink, not the whole
	 * world its wardrobe.
	 */
	private static long mask(JsonObject face, String name) {
		try {
			return face.has(name) ? Long.parseUnsignedLong(face.get(name).getAsString(), 16) : 0L;
		} catch (RuntimeException unreadable) {
			return 0L;
		}
	}

	private void read() {
		try {
			if (!Files.isRegularFile(list())) return;
			entries.addAll(parse(Files.readString(list(), StandardCharsets.UTF_8)));
		} catch (Exception failed) {
			NpcStudio.LOGGER.warn("Could not read the wardrobe: {}", failed.toString());
		}
	}

	private static List<Entry> parse(String json) {
		List<Entry> found = new ArrayList<>();
		JsonArray array = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("outfits");
		if (array == null) return found;
		for (var element : array) {
			JsonObject object = element.getAsJsonObject();
			found.add(new Entry(
				text(object, "id"), text(object, "label"),
				text(object, "category"), text(object, "group"), text(object, "skin"),
				com.mopicmp.npcstudio.entity.BodyShape.fromJson(
					object.has("shape") && object.get("shape").isJsonObject()
						? object.getAsJsonObject("shape") : null),
				faceOf(object)));
		}
		return found;
	}

	private static String text(JsonObject object, String key) {
		return object.has(key) ? object.get(key).getAsString() : "";
	}

	/**
	 * A name no costume has had before, or will have again.
	 *
	 * The first version was the time plus the length of the list, which looks
	 * unique and is not: the length goes back down when something is removed, so
	 * a costume added in the same millisecond as a deletion could inherit the
	 * dead one's name — and every NPC pointing at the old costume would silently
	 * be wearing the new one. A test caught it only by the clock happening to
	 * tick, which is not catching it.
	 *
	 * So the tail is random rather than counted. Nothing that has been handed out
	 * can come back.
	 */
	private String nextId() {
		String id;
		do {
			id = Long.toHexString(System.currentTimeMillis())
				+ "-" + Integer.toHexString(RANDOM.nextInt(0x1000000));
		} while (find(id).isPresent());
		return id;
	}

	private static final java.util.random.RandomGenerator RANDOM =
		new java.util.Random();

	/**
	 * A picture's name is what is in it.
	 *
	 * So the same skin added twice is one file, and a file already on disk needs
	 * no writing. It also means a record can never quietly come to point at a
	 * different picture than the one it was made for.
	 */
	private static String fingerprint(byte[] png) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png))
				.substring(0, 32);
		} catch (Exception impossible) {
			throw new IllegalStateException("SHA-256 is not optional", impossible);
		}
	}
}
