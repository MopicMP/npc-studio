package com.mopicmp.npcstudio.server;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * One server this mod knows about.
 *
 * Plain fields rather than a record, because Gson writes it to disk and because
 * two of these values — the process id and the moment it started — are findings
 * about the world rather than settings, and they change under a running
 * manager.
 *
 * Everything here describes a server we can reach. Where the files are, how the
 * thing is launched, and how to talk to it. What kind of core it is and which
 * version belongs to a later stage; the fields are here so that a server created
 * now is still readable when that stage arrives, and unknown values are simply
 * blank.
 */
public final class ManagedServer {

	/** Stable key. Also the folder name under the servers directory for local ones. */
	public String id = "";

	/** What the owner calls it. Local to this installation; not the message of the day. */
	public String name = "";

	/** Absolute path to the server folder. */
	public String directory = "";

	/** {@code vanilla}, {@code fabric}, {@code paper}... Blank until a core is chosen. */
	public String core = "";

	/** The Minecraft version this server runs. Blank until a core is installed. */
	public String gameVersion = "";

	/** The jar to launch, relative to {@link #directory}. */
	public String jar = "";

	/** Extra arguments to the JVM, before {@code -jar}. */
	public List<String> jvmArgs = new ArrayList<>();

	/**
	 * Arguments to the server itself.
	 *
	 * {@code nogui} by default, and it matters more than it looks: without it the
	 * vanilla server opens a Swing window, which on a machine already running the
	 * game is a second thing competing for the screen and a window whose close
	 * button kills the server without saving.
	 */
	public List<String> gameArgs = new ArrayList<>(List.of("nogui"));

	/** Heap for the server process, in megabytes. */
	public int memoryMb = 1024;

	/** The port players connect to. */
	/**
	 * How many processor cores this server may run on, or zero for all of them.
	 *
	 * The honest form of "how much processor does it get": four of eight is half
	 * the machine and the operating system enforces it. Kept here rather than in
	 * {@code server.properties} because it is nothing to do with the server — it
	 * is about the process, and the server has no opinion on it.
	 */
	public int cores;

	/** How politely it queues for the processor: {@code NORMAL}, {@code BELOW}, {@code LOW}. */
	public String priority = "NORMAL";

	public int port = 25565;

	/** The command channel. Chosen by us when the server is first started. */
	public int rconPort;

	/**
	 * The RCON password.
	 *
	 * Generated, never typed, and stored beside the rest — which is worth saying
	 * out loud: it sits in a plain file in the config folder. That is acceptable
	 * for a password to a channel that only listens on this machine, and it is
	 * the reason the plan keeps other people's credentials, which are not like
	 * this at all, in a separate place with a separate decision behind it.
	 */
	public String rconPassword = "";

	/** A specific java binary, or blank for the one running the game. */
	public String javaPath = "";

	/** The process we last started, or zero. */
	public long pid;

	/**
	 * When that process started, in epoch milliseconds.
	 *
	 * Without it the process id alone is a trap: ids are reused, and a stale one
	 * eventually points at somebody else's program — which we would then report
	 * as a running server and offer to stop.
	 */
	public long pidStarted;

	public Path path() {
		return Path.of(directory);
	}

	public Path jarPath() {
		return path().resolve(jar);
	}

	public Path propertiesPath() {
		return path().resolve("server.properties");
	}

	/**
	 * Where our own record of the console goes.
	 *
	 * Not the server's {@code logs/latest.log}: this one is opened before the
	 * server starts and catches what it says before its own logging exists —
	 * which is exactly where "it will not start and there is nothing in the log"
	 * lives. A wrong java version prints there and nowhere else.
	 */
	public Path consolePath() {
		return path().resolve("npc-studio-console.log");
	}

	/** A password worth having: generated, long, and never shown unless asked for. */
	public static String newSecret() {
		String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
		SecureRandom random = new SecureRandom();
		StringBuilder secret = new StringBuilder(24);
		for (int at = 0; at < 24; at++) {
			secret.append(alphabet.charAt(random.nextInt(alphabet.length())));
		}
		return secret.toString();
	}

	@Override
	public String toString() {
		return "ManagedServer[" + id + " at " + directory + "]";
	}
}
