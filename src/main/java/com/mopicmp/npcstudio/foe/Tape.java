package com.mopicmp.npcstudio.foe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.mopicmp.npcstudio.NpcStudio;

/**
 * A fight, written down one tick at a time.
 *
 * <h2>Why this exists, and why it exists now rather than later</h2>
 *
 * Because five rounds of fixing the fighting went the same way: a report of what
 * it looked like, a guess at which of a dozen numbers caused that, a change, and
 * a different complaint. Every one of the guesses turned out to be about
 * something real — the walk arriving inside the target, the decision running at
 * a fifth of the speed of the legs, a reach the graph could not know, a chain
 * that forgot itself on every interruption — and every one of them was found by
 * reasoning backwards from a sentence rather than by looking.
 *
 * That is not a method, it is luck with extra steps. A fight is twenty ticks a
 * second across two bodies, and the only honest way to know what happened in it
 * is to have it written down.
 *
 * <h2>What is written</h2>
 *
 * One line per tick per character, and nothing summarised: where she is, what
 * she is playing, how far into it, which phase of which blow, what her graph is
 * standing on, and every event that fired. Summaries are where the answer hides
 * — "she flinched" and "she flinched for six ticks, four of them doubled over"
 * are the same summary and different bugs.
 *
 * <h2>Where it goes</h2>
 *
 * A file beside the world, because chat cannot hold it and because a file can be
 * read by somebody who was not there. That is the point: the person watching the
 * fight and the person who has to explain it are not the same person, and a
 * description passed between them loses exactly the detail that matters.
 */
public final class Tape {

	private Tape() { }

	/** How many ticks one recording may run before it stops itself. */
	public static final int LONGEST = 400;

	private static final List<String> LINES = new ArrayList<>();
	private static volatile boolean rolling;
	private static int began = -1;
	private static Path where;

	public static boolean rolling() {
		return rolling;
	}

	/** How much has been recorded, for a readout that can say so. */
	public static int length() {
		return LINES.size();
	}

	public static Path file() {
		return where;
	}

	public static void start(Path gameDir, int tick) {
		LINES.clear();
		where = gameDir.resolve("npc-fight-tape.txt");
		began = tick;
		rolling = true;
		LINES.add(String.format("%5s %-16s %-5s %-8s %-4s %-4s %-34s %-6s %-20s %-5s %-5s %s",
			"tick", "who", "guard", "phase", "link", "into", "playing", "age", "graph at",
			"dist", "hp", "event"));
		LINES.add("-".repeat(140));
	}

	/**
	 * One tick of one character.
	 *
	 * Every column is filled even when it is empty, because a column that
	 * disappears when there is nothing to say makes two lines impossible to read
	 * against each other — and reading two lines against each other is the whole
	 * reason there are two characters in the file.
	 */
	public static void note(int tick, String who, boolean guard, String phase, int link,
			int into, String playing, int age, int gestureFor, String graphAt, double dist,
			float hp, String event) {
		if (!rolling) return;
		if (tick - began > LONGEST) {
			stop();
			return;
		}
		LINES.add(String.format("%5d %-16s %-5s %-8s %-4d %-4d %-34s %-6s %-20s %-5s %-5s %s",
			tick - began,
			cut(who, 16),
			guard ? "up" : "",
			cut(phase, 8),
			link,
			into,
			cut(playing, 34),
			playing.isEmpty() ? "" : (gestureFor > 0 ? age + "/" + gestureFor : String.valueOf(age)),
			cut(graphAt, 20),
			dist < 0 ? "" : String.format("%.1f", dist),
			String.format("%.1f", hp),
			event));
	}

	private static String cut(String text, int at) {
		if (text == null) return "";
		return text.length() <= at ? text : text.substring(0, at - 1) + "…";
	}

	/**
	 * Stops, and writes.
	 *
	 * Written on stopping rather than as it goes, because a fight is four hundred
	 * ticks and a file opened twenty times a second is a fight measured through a
	 * thing that changes it.
	 */
	public static String stop() {
		rolling = false;
		if (where == null) return "nothing was recorded";
		try {
			Files.write(where, String.join(System.lineSeparator(), LINES)
				.getBytes(StandardCharsets.UTF_8));
			return LINES.size() - 2 + " lines written to " + where.getFileName();
		} catch (IOException cannotWrite) {
			NpcStudio.LOGGER.error("Could not write the fight tape to {}", where, cannotWrite);
			return "could not write it: " + cannotWrite.getMessage();
		}
	}
}
