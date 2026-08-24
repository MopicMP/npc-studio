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
			double carries, float urgency, long madeAt) { }

	/**
	 * How much a noise demands to be dealt with, as opposed to merely heard.
	 *
	 * <h2>Why this is not the same as how loud it is</h2>
	 *
	 * It was, and it produced a bug worth keeping the story of. A stick of dynamite
	 * makes one loud bang and then a great many quiet ones as each broken block
	 * reports itself — and those come from the crater, which is at your feet. A
	 * character picking the strongest noise picked a nearby block over a distant
	 * explosion and stood there staring at the floor while a hole appeared in the
	 * world behind her.
	 *
	 * Loudness is how well you can hear something. Urgency is whether it matters.
	 * They usually agree and the times they do not are exactly the interesting ones:
	 * a fight on the other side of a courtyard is quieter than a door beside you and
	 * a great deal more worth turning round for.
	 *
	 * @param hostile whether the sound came from something dangerous rather than
	 *                from the furniture
	 */
	public static float urgencyFor(float volume, boolean hostile) {
		if (volume <= 0) return 0;
		// Anything above the ordinary is a bang: an explosion, a shot, something
		// breaking that was not meant to. The game reserves volumes above one for
		// things it wants heard across a distance, which is the same judgement.
		float bang = Math.clamp((volume - 1f) / 3f, 0f, 1f);
		float threat = hostile ? 0.6f : 0f;
		// The ordinary floor: every noise is worth a glance, nothing is worth
		// nothing. Without it a character would ignore footsteps entirely once she
		// had heard one door.
		return Math.clamp(Math.max(bang, threat) * 0.8f + 0.2f, 0f, 1f);
	}

	/**
	 * How badly a noise is placed, in blocks, from where it was really made.
	 *
	 * <h2>Ears are not eyes</h2>
	 *
	 * They were, and it was reported: she knew exactly where a sound came from,
	 * which is not something anybody can do. Two ears give a direction and a rough
	 * sense of distance, and the further away and the fainter, the rougher.
	 *
	 * That matters beyond being realistic. A character who knows precisely where a
	 * noise came from has, in effect, seen you — and then hearing stops being the
	 * lesser sense it is supposed to be and the whole arrangement of ears telling
	 * you where to look and eyes telling you what is there collapses.
	 */
	public static double vagueness(float loudness, double distance) {
		if (loudness <= 0) return 0;
		// A quarter of the distance, eased by how clearly it was heard. Something
		// loud right beside you is placed to within half a block; a faint noise forty
		// blocks off could be anywhere within ten.
		double byDistance = distance * 0.25;
		double byLoudness = 1.4 - loudness;
		return Math.clamp(byDistance * byLoudness, 0, 12);
	}

	/**
	 * Where a character thinks a noise came from.
	 *
	 * The guess has to be the same every time it is asked, or the head twitches
	 * about a point sixty times a second — so it is worked out from the noise
	 * itself rather than drawn afresh. Two characters hearing one bang guess
	 * differently, which is right: they are standing in different places.
	 */
	public static net.minecraft.world.phys.Vec3 guessAt(Rumour rumour, double vagueness,
			net.minecraft.world.phys.Vec3 from) {
		if (vagueness <= 0) return rumour.at();
		long seed = stirred(rumour.at().x) * 31 + stirred(rumour.at().z) * 17
			+ rumour.madeAt() * 7 + stirred(from.x) * 3 + stirred(from.z);
		java.util.Random guessing = new java.util.Random(seed);
		double angle = guessing.nextDouble() * Math.PI * 2;
		double away = guessing.nextDouble() * vagueness;
		// Sideways only. Being wrong about the height would have a character staring
		// into the ground or up at the sky, which reads as broken rather than as
		// approximate — and up and down is the one direction ears are actually
		// rather good at, because a noise from below sounds like it.
		return rumour.at().add(Math.cos(angle) * away, 0, Math.sin(angle) * away);
	}

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
	public static void made(net.minecraft.world.phys.Vec3 at, float volume,
			boolean hostile, long tick) {
		float loudness = loudnessFor(volume);
		if (loudness <= 0) return;
		synchronized (heard) {
			if (heard.size() >= AT_ONCE) heard.removeFirst();
			heard.add(new Rumour(at, loudness, carriesFor(volume),
				urgencyFor(volume, hostile), tick));
		}
	}

	/** What is still worth knowing about, and forgets the rest on the way past. */
	public static List<Rumour> since(long tick) {
		synchronized (heard) {
			heard.removeIf(rumour -> tick - rumour.madeAt() > LINGERS);
			return List.copyOf(heard);
		}
	}

	/**
	 * A coordinate mixed into something a seed can use.
	 *
	 * <h2>The bug this is the fix for, which a test found first</h2>
	 *
	 * The raw bits of a double will not do, and the reason is easy to miss. A round
	 * number like thirty has all of its information in the high bits — the low
	 * forty-eight are zero — and {@code Random} seeds itself from the low
	 * forty-eight and throws the rest away. So every character standing on a whole
	 * block, hearing a noise made on a whole block, drew exactly the same seed.
	 *
	 * They would all have guessed the same wrong spot, in step, for ever. Which
	 * would have looked less like several people mishearing something and more like
	 * one mind moving several bodies.
	 */
	private static long stirred(double value) {
		long bits = Double.doubleToLongBits(value);
		// The high bits folded down where the seed can reach them, then stirred so
		// that neighbouring blocks do not produce neighbouring answers.
		bits ^= bits >>> 32;
		bits *= 0xff51afd7ed558ccdL;
		bits ^= bits >>> 29;
		return bits;
	}

	/** Dropped when a world is unloaded; the next one has its own noises. */
	public static void forget() {
		synchronized (heard) {
			heard.clear();
		}
		synchronized (familiar) {
			familiar.clear();
		}
	}

	// ------------------------------------------------------- getting used to it

	/**
	 * How many times the same noise has come from the same place lately.
	 *
	 * Keyed on the block and on a rough idea of how loud, so that a door opening and
	 * closing counts as one thing happening repeatedly, while a door and then a
	 * gunshot at the same doorway do not.
	 */
	private static final java.util.Map<Long, int[]> familiar = new java.util.HashMap<>();

	/**
	 * How much a noise still means, having heard it several times already.
	 *
	 * <h2>Why anybody stops looking</h2>
	 *
	 * Because a miller does not jump at his own mill. The fifth time a piston fires
	 * in the same place it is furniture, not an event, and a character who turns her
	 * head to it every time is not vigilant — she is broken in a way that is
	 * tiring to watch and trivially exploitable: hold down a button and she never
	 * looks anywhere else.
	 *
	 * <h2>But an explosion is an explosion the tenth time</h2>
	 *
	 * The forgetting is proportional to how ordinary the thing was. A door fades to
	 * almost nothing; a bang barely fades at all. That asymmetry is the whole point
	 * — habituation that treats everything alike would make somebody who blows a
	 * hole in a wall five times in a row invisible, which is the opposite of what
	 * habituation is for.
	 *
	 * @param heard how many times it has been heard, this one included
	 */
	public static float dulledBy(int heard, float urgency) {
		if (heard <= 1) return 1;
		// How readily this kind of thing becomes background. Nothing ever fades
		// completely: the fifth piston is still worth a glance, just not a turn.
		float fades = 1 - Math.clamp(urgency, 0f, 1f);
		float worn = (float) Math.pow(0.55, heard - 1);
		return Math.clamp(1 - fades * (1 - worn), FLOOR_OF_INTEREST, 1f);
	}

	/** However familiar, a noise never becomes literally nothing. */
	public static final float FLOOR_OF_INTEREST = 0.15f;

	/** How long a place goes on being remembered as noisy, in ticks. */
	private static final int REMEMBERED_FOR = 200;

	/**
	 * Counts this noise against its place, and says how much of it is still news.
	 *
	 * Counted per noise rather than per listener, which is a simplification worth
	 * naming: two guards either side of a wall get used to a piston at the same
	 * rate, even though only one of them can hear it well. The alternative is a
	 * memory per character per place, and the reward is a distinction nobody will
	 * ever see.
	 */
	public static float familiarity(Rumour rumour, long now) {
		long place = keyOf(rumour);
		synchronized (familiar) {
			int[] seen = familiar.get(place);
			if (seen == null || now - seen[1] > REMEMBERED_FOR) {
				familiar.put(place, new int[] { 1, (int) now });
				if (familiar.size() > 256) {
					familiar.entrySet().removeIf(e -> now - e.getValue()[1] > REMEMBERED_FOR);
				}
				return 1;
			}
			seen[0] = Math.min(seen[0] + 1, 12);
			seen[1] = (int) now;
			return dulledBy(seen[0], rumour.urgency());
		}
	}

	private static long keyOf(Rumour rumour) {
		long x = (long) Math.floor(rumour.at().x / 3);
		long y = (long) Math.floor(rumour.at().y / 3);
		long z = (long) Math.floor(rumour.at().z / 3);
		long loud = Math.round(rumour.loudness() * 4);
		return (((x & 0xFFFFF) << 44) | ((y & 0xFFFFF) << 24) | ((z & 0xFFFFF) << 4)
			| (loud & 0xF));
	}
}
