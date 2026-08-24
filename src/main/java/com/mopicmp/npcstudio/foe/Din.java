package com.mopicmp.npcstudio.foe;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything that has recently made a noise, and where.
 *
 * <h2>Why the world tells us instead of us guessing</h2>
 *
 * Hearing began by working loudness out from what a player was doing — walking,
 * running, crouching — which covers a player and nothing else. It has nothing to
 * say about a block being broken, an arrow loosed, a door, a piston, a pressure
 * plate, or a stick of dynamite, and those were asked for by name.
 *
 * Guessing at each of them one at a time would be a long list that is never
 * finished, because the next version and the next mod both add to it. But the
 * game already knows: every one of those things plays a sound, through two
 * methods, with a volume and a position already worked out. So the world is
 * asked rather than second-guessed, and dynamite, pistons and everything anybody
 * adds later arrive without a line being written about any of them.
 *
 * <h2>A log rather than an event</h2>
 *
 * Sounds happen in an instant and characters look four times a second, so an
 * event handed straight over would be missed three times out of four. They are
 * written down instead and kept for about a second, which is also what lets one
 * bang be heard by every character in earshot rather than by whoever asked
 * first.
 */
public final class Din {

	/**
	 * One noise: where it was, how loud, how far it reaches, and when.
	 *
	 * @param at       where it happened
	 * @param loudness how loud at the source, nought to one
	 * @param carries  how far it could possibly be heard, in blocks
	 * @param madeAt   the tick it happened on
	 */
	public record Rumour(net.minecraft.world.phys.Vec3 at, float loudness,
			double carries, long madeAt) { }

	/**
	 * How long a noise stays worth knowing about, in ticks.
	 *
	 * A second. Long enough that a character looking four times a second cannot
	 * miss one, short enough that a door closing does not go on drawing attention
	 * after whoever opened it has walked away.
	 */
	public static final int LINGERS = 20;

	/**
	 * How many are kept at once.
	 *
	 * A cap on the cost and not on the truth. A busy redstone contraption can make
	 * hundreds of sounds a second, and a character standing next to one must not be
	 * able to make the server do hundreds of searches — so the newest win and the
	 * rest are simply not heard, which is roughly what happens to anybody standing
	 * next to a busy redstone contraption.
	 */
	public static final int AT_ONCE = 64;

	private static final List<Rumour> heard = new ArrayList<>();

	private Din() { }

	/**
	 * How loud a game sound is, on our own nought-to-one scale.
	 *
	 * The game's volumes are not on that scale — a footstep is about a seventh, a
	 * block being placed is one, and dynamite is four — so they are scaled and
	 * capped. Scaled by less than the whole, so that an ordinary block sound is
	 * loud without being as loud as anything can possibly be; there has to be room
	 * above a door closing for an explosion.
	 */
	public static float loudnessFor(float volume) {
		return Math.clamp(volume * 0.7f, 0f, 1f);
	}

	/**
	 * How far a sound of that volume can be heard at all.
	 *
	 * Sixteen blocks for anything ordinary, which is the game's own figure for the
	 * range of a normal sound, and further in proportion for the loud ones — again
	 * the game's own rule. So dynamite carries across a valley and a pressure plate
	 * does not carry into the next room, without either being written down.
	 */
	public static double carriesFor(float volume) {
		return Math.clamp(16 * Math.max(1, volume), 8, 160);
	}

	/** Writes a noise down. Called from the server's own sound plumbing. */
	public static void made(net.minecraft.world.phys.Vec3 at, float volume, long tick) {
		float loudness = loudnessFor(volume);
		if (loudness <= 0) return;
		synchronized (heard) {
			if (heard.size() >= AT_ONCE) heard.removeFirst();
			heard.add(new Rumour(at, loudness, carriesFor(volume), tick));
		}
	}

	/** What is still worth knowing about, and forgets the rest on the way past. */
	public static List<Rumour> since(long tick) {
		synchronized (heard) {
			heard.removeIf(rumour -> tick - rumour.madeAt() > LINGERS);
			return List.copyOf(heard);
		}
	}

	/** Dropped when a world is unloaded; the next one has its own noises. */
	public static void forget() {
		synchronized (heard) {
			heard.clear();
		}
	}
}
