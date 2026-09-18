package com.mopicmp.npcstudio.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Worlds beside the world: a builder's dimension, made by a datapack we write.
 *
 * The thing people write a plugin for. Builders want somewhere to build that is
 * not the world everyone plays in, and a way there and back — and a server can do
 * all of that with no plugin at all, because a dimension is a file and a datapack
 * is a folder of files. So this writes the folder.
 *
 * <p>Three facts decide the whole shape of this class, and each of them is a limit
 * of the game rather than a decision of ours:
 *
 * <ul>
 * <li><b>Dimensions are read when the world loads.</b> A new one appears on the
 * next start of the server and not before. {@code /reload} re-reads functions and
 * does not add dimensions. Nothing here can change that, so everything here says
 * it.</li>
 * <li><b>Going there is a command, and commands have levels.</b> {@code /execute
 * in} needs level 2, which builders do not have and should not be given for this.
 * The one command an ordinary player may use is {@code /trigger}, so that is what
 * they use: a trigger objective, a function on the tick tag reading it, and a
 * teleport that the <em>server</em> performs.</li>
 * <li><b>Who may go is a list, and the list has to survive being offline.</b> A
 * score is set by name and keeps working when the named player is not on, so the
 * list becomes {@code scoreboard players set <name> npcok 1} lines in a function
 * that runs at load. No permissions plugin, no operators.</li>
 * </ul>
 *
 * <p>Everything written here ends up inside command files, so every name and every
 * id is checked against a pattern first. A "player name" holding a newline would
 * be a person adding their own commands to a file the server runs as itself every
 * tick — this is the one place in this mod where somebody else's text becomes
 * code.
 */
public final class Dimensions {

	private Dimensions() {
	}

	/** Everyone in the same place, or each builder on a plot of their own. */
	public enum Sharing {
		SHARED,
		PLOTS
	}

	/** Where the way back puts somebody: where they left, or a fixed point. */
	public enum Return {
		EXIT,
		POINT
	}

	/**
	 * One builder's world.
	 *
	 * @param id       the path of the dimension; the namespace is always ours
	 * @param template a flat template from {@link Flat}, or blank when layers are given
	 * @param layers   layers in the written notation, when there is no template
	 * @param who      the names that may go, which become lines in a function
	 * @param point    where the way back puts somebody, when {@code back} is POINT
	 */
	public record Place(String id, String name, String template, String layers,
			Sharing sharing, Return back, List<String> who, int[] point) {

		public String full() {
			return SPACE + ":" + id;
		}
	}

	/** The line at the top of every file we generate, for whoever opens one. */
	private static final String HEADER =
		"# Written by NPC Studio. Edited by hand it will be overwritten.\n";

	/** Our namespace, and the folder the pack lives in. */
	public static final String SPACE = "npc_studio";

	/** The names of the scoreboard objectives, kept short because they are typed. */
	private static final String GO = "npcgo";
	private static final String BACK = "npcback";
	private static final String OK = "npcok";

	/** How far apart plots are put, which is far enough not to hear each other. */
	private static final int PLOT_STEP = 1024;

	/** Where a builder lands, above the layers of every template we offer. */
	private static final int LANDING = 100;

	public static Path folder(ManagedServer server, String world) {
		return server.path().resolve(world).resolve("datapacks").resolve(SPACE);
	}

	/**
	 * Where the chunks of a made dimension end up once the server has been there.
	 *
	 * Under the world, by namespace and id, which is where every added dimension
	 * goes. Worth knowing because changing what a dimension is made of changes the
	 * file that describes it and nothing that has already been generated: the
	 * ground stays exactly as it was until this folder is gone.
	 */
	public static Path chunksOf(ManagedServer server, String world, String id) {
		return server.path().resolve(world).resolve("dimensions").resolve(SPACE).resolve(id);
	}

	/** Whether a name may be written into a command file at all. */
	public static boolean validPlayer(String name) {
		return name != null && name.matches("[A-Za-z0-9_]{1,16}");
	}

	/** Whether an id may be a dimension's, and a file's. */
	public static boolean validId(String id) {
		return id != null && id.matches("[a-z0-9_-]{1,32}");
	}

	/**
	 * Write the whole pack, replacing whatever we wrote before.
	 *
	 * Replacing rather than merging: the pack is ours from top to bottom and is
	 * generated from the list every time, so there is exactly one thing that can
	 * be true of it at a time. Anything a person edited inside it would be lost —
	 * which is why it is a folder with our name on it and not one of theirs.
	 */
	public static void write(ManagedServer server, String world, List<Place> places)
			throws IOException {
		for (Place place : places) {
			if (!validId(place.id())) {
				throw new IOException("That is not a name a dimension can have: " + place.id());
			}
			for (String who : place.who()) {
				if (!validPlayer(who)) {
					throw new IOException("That is not a player's name: " + who);
				}
			}
		}

		Path root = folder(server, world);
		Path data = root.resolve("data").resolve(SPACE);
		delete(root);
		Files.createDirectories(data.resolve("dimension"));
		Files.createDirectories(data.resolve("function"));
		Files.createDirectories(data.resolve("predicate"));
		Files.createDirectories(root.resolve("data/minecraft/tags/function"));

		put(root.resolve("pack.mcmeta"), meta(server));
		put(root.resolve(SPACE + ".json"), ours(places));

		for (Place place : places) {
			put(data.resolve("dimension").resolve(place.id() + ".json"), dimension(place));
		}
		put(data.resolve("function/setup.mcfunction"), setup());
		put(data.resolve("function/load.mcfunction"), load(places));
		put(data.resolve("function/tick.mcfunction"), tick(places));
		put(data.resolve("function/back.mcfunction"), back(places));
		put(data.resolve("function/back_overworld.mcfunction"),
			"$execute in minecraft:overworld run tp @s $(x) $(y) $(z)\n");
		put(data.resolve("function/back_nether.mcfunction"),
			"$execute in minecraft:the_nether run tp @s $(x) $(y) $(z)\n");
		put(data.resolve("function/back_end.mcfunction"),
			"$execute in minecraft:the_end run tp @s $(x) $(y) $(z)\n");
		for (int at = 0; at < places.size(); at++) {
			put(data.resolve("function/go_" + places.get(at).id() + ".mcfunction"),
				go(places.get(at), at + 1));
			put(data.resolve("function/back_" + places.get(at).id() + ".mcfunction"),
				backFrom(places.get(at)));
		}
		put(data.resolve("predicate/in_nether.json"), where("minecraft:the_nether"));
		put(data.resolve("predicate/in_end.json"), where("minecraft:the_end"));

		put(root.resolve("data/minecraft/tags/function/load.json"),
			"{\"values\":[\"" + SPACE + ":load\"]}\n");
		put(root.resolve("data/minecraft/tags/function/tick.json"),
			"{\"values\":[\"" + SPACE + ":tick\"]}\n");
	}

	/** What we wrote last time, so the window can show it and change it. */
	public static List<Place> read(ManagedServer server, String world) {
		Path file = folder(server, world).resolve(SPACE + ".json");
		if (!Files.isRegularFile(file)) return List.of();
		try {
			JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			List<Place> places = new ArrayList<>();
			for (var each : json.getAsJsonArray("places")) {
				JsonObject one = each.getAsJsonObject();
				List<String> who = new ArrayList<>();
				if (one.has("who")) {
					for (var name : one.getAsJsonArray("who")) who.add(name.getAsString());
				}
				int[] point = {0, LANDING, 0};
				if (one.has("point")) {
					JsonArray at = one.getAsJsonArray("point");
					for (int n = 0; n < 3 && n < at.size(); n++) point[n] = at.get(n).getAsInt();
				}
				places.add(new Place(
					one.get("id").getAsString(),
					one.has("name") ? one.get("name").getAsString() : one.get("id").getAsString(),
					one.has("template") ? one.get("template").getAsString() : "the_void",
					one.has("layers") ? one.get("layers").getAsString() : "",
					Sharing.valueOf(one.get("sharing").getAsString()),
					Return.valueOf(one.get("back").getAsString()),
					List.copyOf(who), point));
			}
			return List.copyOf(places);
		} catch (IOException | RuntimeException unreadable) {
			return List.of();
		}
	}

	// ------------------------------------------------------------------ the files

	/**
	 * The pack's own description, and which game it is for.
	 *
	 * The format number is read out of the server's own jar — {@code version.json}
	 * at its root says what data pack version that exact build speaks. Guessing it
	 * from the version of the game this window is running in would be guessing
	 * about a different program: people manage servers older than their client all
	 * the time, and a pack whose number is wrong is not loaded, quietly, with one
	 * line in a log nobody reads.
	 */
	static String meta(ManagedServer server) {
		int[] format = format(server);
		StringBuilder out = new StringBuilder();
		out.append("{\n  \"pack\": {\n");
		out.append("    \"description\": \"NPC Studio — dimensions made from the game\",\n");
		out.append("    \"pack_format\": ").append(format[0]).append(",\n");
		out.append("    \"min_format\": [").append(format[0]).append(", ")
			.append(format[1]).append("],\n");
		out.append("    \"max_format\": ").append(format[0]).append("\n");
		out.append("  }\n}\n");
		return out.toString();
	}

	/**
	 * The data pack version of this server's own core, as major and minor.
	 *
	 * Falls back to the newest we know of when the jar will not say — a launcher
	 * jar that patches a copy of the game at run time (which is what Paper's is)
	 * may not carry the file at all.
	 */
	static int[] format(ManagedServer server) {
		Path jar = server.path().resolve(server.jar == null || server.jar.isBlank()
			? "server.jar" : server.jar);
		if (Files.isRegularFile(jar)) {
			try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
				var entry = zip.getEntry("version.json");
				if (entry != null) {
					try (InputStream stream = zip.getInputStream(entry)) {
						String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
						JsonObject json = JsonParser.parseString(text).getAsJsonObject();
						JsonObject pack = json.getAsJsonObject("pack_version");
						if (pack != null) {
							if (pack.has("data_major")) {
								return new int[] {pack.get("data_major").getAsInt(),
									pack.has("data_minor") ? pack.get("data_minor").getAsInt() : 0};
							}
							if (pack.has("data")) {
								return new int[] {pack.get("data").getAsInt(), 0};
							}
						}
					}
				}
			} catch (IOException | RuntimeException unreadable) {
				// Said below, in the only way that matters: with a number that is
				// at least not made up on the spot.
			}
		}
		return new int[] {NEWEST_FORMAT, 0};
	}

	/** What 26.2 speaks, read from its own version.json when this was written. */
	private static final int NEWEST_FORMAT = 107;

	private static String dimension(Place place) {
		String settings = place.template().isBlank()
			? Flat.fromWritten(place.layers(), "minecraft:the_void")
			: Flat.settings(Flat.preset(place.template()));
		return "{\n  \"type\": \"minecraft:overworld\",\n"
			+ "  \"generator\": {\n"
			+ "    \"type\": \"minecraft:flat\",\n"
			+ "    \"settings\": " + settings + "\n"
			+ "  }\n}\n";
	}

	/** A predicate is how a function asks which dimension it is standing in. */
	private static String where(String dimension) {
		return "{\n  \"condition\": \"minecraft:location_check\",\n"
			+ "  \"predicate\": { \"dimension\": \"" + dimension + "\" }\n}\n";
	}

	/**
	 * The objectives, made once and never again.
	 *
	 * Guarded by a value in storage rather than run every load, because
	 * {@code scoreboard objectives add} on one that exists is a red error line in
	 * the console at every start — and a console that cries wolf about our doing
	 * is a console people stop reading.
	 */
	private static String setup() {
		return line("scoreboard objectives add " + GO + " trigger")
			+ line("scoreboard objectives add " + BACK + " trigger")
			+ line("scoreboard objectives add " + OK + " dummy")
			+ line("scoreboard objectives add npcx dummy")
			+ line("scoreboard objectives add npcy dummy")
			+ line("scoreboard objectives add npcz dummy")
			+ line("scoreboard objectives add npcd dummy")
			+ line("scoreboard objectives add npcin dummy");
	}

	private static String load(List<Place> places) {
		StringBuilder out = new StringBuilder();
		out.append("# Written by NPC Studio. Edited by hand it will be overwritten.\n");
		out.append(line("execute unless data storage " + SPACE + ":state {ready:1b} run function "
			+ SPACE + ":setup"));
		out.append(line("data modify storage " + SPACE + ":state ready set value 1b"));
		// The list of who may go, rewritten from nothing every time so that a name
		// taken off the list in the window is a name taken off the server.
		out.append(line("scoreboard players reset * " + OK));
		java.util.Set<String> already = new java.util.LinkedHashSet<>();
		for (Place place : places) {
			for (String who : place.who()) {
				if (already.add(who)) {
					out.append(line("scoreboard players set " + who + " " + OK + " 1"));
				}
			}
		}
		return out.toString();
	}

	/**
	 * Every tick: let the allowed use the trigger, act on what they asked, reset.
	 *
	 * The order matters. Enabling comes first so a new player can use it on their
	 * first tick; the reset comes last so that one press is one journey.
	 */
	/** Whether nobody has been named anywhere, which is taken to mean everybody. */
	public static boolean everyone(List<Place> places) {
		for (Place place : places) {
			if (!place.who().isEmpty()) return false;
		}
		return true;
	}

	private static String tick(List<Place> places) {
		StringBuilder out = new StringBuilder();
		out.append("# Written by NPC Studio. Edited by hand it will be overwritten.\n");
		// An empty list means everybody. It meant nobody at first, which is the
		// stricter reading and the wrong one: somebody who makes a builders' world
		// and writes no names has not said "no one may go", they have not got to
		// that part yet — and what they get is a dimension nobody can reach and a
		// command that answers "you cannot trigger this yet".
		String who = everyone(places) ? "@a" : "@a[scores={" + OK + "=1..}]";
		// Every line here is an "execute as ... run", including the ones that would
		// read more plainly without it. A bare command whose selector matches
		// nobody counts as a failure — and this file runs twenty times a second on
		// a server that is empty most of the night.
		out.append(line("execute as " + who + " run scoreboard players enable @s " + GO));
		out.append(line("execute as " + who + " run scoreboard players enable @s " + BACK));
		out.append(line("execute as @a[scores={" + BACK + "=1..}] at @s run function "
			+ SPACE + ":back"));
		// Only those who used it, and this is not tidiness. A trigger is enabled by
		// unlocking a score, and resetting the score throws the unlocking away with
		// it. The tick functions run before the server reads what people typed, so
		// resetting this one for everybody every tick meant it was locked again by
		// the time anybody could press it: "you cannot trigger this yet", for ever.
		// The way in never had the fault because its reset was already conditional.
		out.append(line("execute as @a[scores={" + BACK + "=1..}] run scoreboard players reset @s "
			+ BACK));
		for (int at = 0; at < places.size(); at++) {
			out.append(line("execute as @a[scores={" + GO + "=" + (at + 1) + "}] at @s run function "
				+ SPACE + ":go_" + places.get(at).id()));
		}
		out.append(line("execute as @a[scores={" + GO + "=1..}] run scoreboard players reset @s "
			+ GO));
		return out.toString();
	}

	/**
	 * Going in: remember where they stood, then put them there.
	 *
	 * The position is kept in scores rather than in storage because scores are
	 * already per player and keyed by name, which is exactly what is needed and
	 * costs nothing. Blocks, not fractions of one — somebody comes back standing
	 * where they were, not standing in the same footprint.
	 */
	private static String go(Place place, int which) {
		StringBuilder out = new StringBuilder();
		out.append("# Written by NPC Studio. Edited by hand it will be overwritten.\n");
		out.append(line("execute store result score @s npcx run data get entity @s Pos[0] 1"));
		out.append(line("execute store result score @s npcy run data get entity @s Pos[1] 1"));
		out.append(line("execute store result score @s npcz run data get entity @s Pos[2] 1"));
		out.append(line("scoreboard players set @s npcd 0"));
		out.append(line("execute if predicate " + SPACE + ":in_nether run scoreboard players set"
			+ " @s npcd 1"));
		out.append(line("execute if predicate " + SPACE + ":in_end run scoreboard players set"
			+ " @s npcd 2"));
		// Which place they are in, so that the way out is that place's own.
		out.append(line("scoreboard players set @s npcin " + which));
		if (place.sharing() == Sharing.SHARED) {
			out.append(line("execute in " + place.full() + " run tp @s 0 " + LANDING + " 0"));
		} else if (place.who().isEmpty()) {
			// Plots are cut from the list of names, so with no names there are no
			// plots — everybody lands in the same spot rather than nowhere.
			out.append(line("execute in " + place.full() + " run tp @s 0 " + LANDING + " 0"));
		} else {
			// A plot each, a thousand blocks apart, in the order the list is
			// written. The order is the window's; changing it moves people, which
			// is why the window says so.
			for (int at = 0; at < place.who().size(); at++) {
				out.append(line("execute if entity @s[name=" + place.who().get(at) + "] in "
					+ place.full() + " run tp @s " + (at * PLOT_STEP) + " " + LANDING + " 0"));
			}
		}
		return out.toString();
	}

	/**
	 * Coming out: each place its own way, because each place was told its own way.
	 *
	 * This was one function for all of them at first, and reading the generated
	 * file showed what that meant: with two places, one returning to where people
	 * left and one to a fixed point, the file did both in turn and the second one
	 * won. Everybody came out of the builders' world standing in the arena. So the
	 * way out is per place, chosen by the place they are in.
	 */
	private static String back(List<Place> places) {
		StringBuilder out = new StringBuilder();
		out.append(HEADER);
		for (int at = 0; at < places.size(); at++) {
			out.append(line("execute if score @s npcin matches " + (at + 1) + " run function "
				+ SPACE + ":back_" + places.get(at).id()));
		}
		out.append(line("scoreboard players set @s npcin 0"));
		return out.toString();
	}

	/** One place's way out, which is either a memory or a decision. */
	private static String backFrom(Place place) {
		StringBuilder out = new StringBuilder();
		out.append(HEADER);
		if (place.back() == Return.POINT) {
			int[] point = place.point();
			out.append(line("execute in minecraft:overworld run tp @s "
				+ point[0] + " " + point[1] + " " + point[2]));
			return out.toString();
		}
		out.append(line("execute store result storage " + SPACE + ":at x int 1 run"
			+ " scoreboard players get @s npcx"));
		out.append(line("execute store result storage " + SPACE + ":at y int 1 run"
			+ " scoreboard players get @s npcy"));
		out.append(line("execute store result storage " + SPACE + ":at z int 1 run"
			+ " scoreboard players get @s npcz"));
		out.append(line("execute if score @s npcd matches 0 run function " + SPACE
			+ ":back_overworld with storage " + SPACE + ":at"));
		out.append(line("execute if score @s npcd matches 1 run function " + SPACE
			+ ":back_nether with storage " + SPACE + ":at"));
		out.append(line("execute if score @s npcd matches 2 run function " + SPACE
			+ ":back_end with storage " + SPACE + ":at"));
		return out.toString();
	}

	private static String ours(List<Place> places) {
		StringBuilder out = new StringBuilder("{\n  \"places\": [\n");
		for (int at = 0; at < places.size(); at++) {
			Place place = places.get(at);
			out.append("    {");
			out.append("\"id\": \"").append(place.id()).append("\", ");
			out.append("\"name\": \"").append(escape(place.name())).append("\", ");
			out.append("\"template\": \"").append(place.template()).append("\", ");
			out.append("\"layers\": \"").append(escape(place.layers())).append("\", ");
			out.append("\"sharing\": \"").append(place.sharing()).append("\", ");
			out.append("\"back\": \"").append(place.back()).append("\", ");
			out.append("\"point\": [").append(place.point()[0]).append(", ")
				.append(place.point()[1]).append(", ").append(place.point()[2]).append("], ");
			out.append("\"who\": [");
			for (int who = 0; who < place.who().size(); who++) {
				if (who > 0) out.append(", ");
				out.append('"').append(place.who().get(who)).append('"');
			}
			out.append("]}");
			if (at < places.size() - 1) out.append(',');
			out.append('\n');
		}
		return out.append("  ]\n}\n").toString();
	}

	private static String escape(String text) {
		return text.replace("\\", "\\\\").replace("\"", "\\\"")
			.replace("\n", " ").replace("\r", " ");
	}

	/**
	 * One command, and nothing that could be two.
	 *
	 * Every id and every name has been checked already; this is the second wall,
	 * for the day somebody adds a field and forgets the first one.
	 */
	private static String line(String command) {
		if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
			throw new IllegalArgumentException("A command may not hold a line break");
		}
		return command + "\n";
	}

	private static void put(Path file, String text) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, text, StandardCharsets.UTF_8);
	}

	private static void delete(Path folder) throws IOException {
		if (!Files.exists(folder)) return;
		try (var walk = Files.walk(folder)) {
			for (Path each : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(each);
			}
		}
	}

	/** What somebody types to go there, for a window that has to tell them. */
	public static String command(List<Place> places, Place place) {
		return "/trigger " + GO + " set " + (places.indexOf(place) + 1);
	}

	public static String backCommand() {
		return "/trigger " + BACK;
	}

	/** A name for a place from a person who typed one, or its id. */
	public static String nameOf(Place place) {
		return place.name() == null || place.name().isBlank()
			? place.id() : place.name().trim();
	}

	static String space(String text) {
		return text.toLowerCase(Locale.ROOT);
	}
}
