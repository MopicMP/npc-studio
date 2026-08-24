package com.mopicmp.npcstudio.foe;

import net.minecraft.world.level.block.SoundType;

/**
 * How much a wall takes out of a sound.
 *
 * <h2>Borrowed, and from the right place</h2>
 *
 * Hearing started with no walls in it at all — an honest simplification, and it
 * made hiding behind one pointless, which was reported. Sound Physics Remastered
 * was named as the mod that does this properly and it was unpacked rather than
 * guessed at.
 *
 * The idea worth taking from it is not its numbers, it is <b>what it keys them
 * to</b>. It does not carry a list of blocks. It keys absorption to the block's
 * {@link SoundType} — the thing every block in the game already declares so that
 * walking on it makes the right noise. Which means one small table covers
 * stone, wood, wool, glass, leaves, every block added in every future version,
 * and every block of every other mod installed, for nothing. A list of blocks
 * would have been out of date by the next snapshot.
 *
 * Their figures, read out of the jar: wool absorbs half again as much as an
 * ordinary block, moss three quarters, honey a half, glass and snow a tenth,
 * and growing things essentially nothing. Those are somebody's carefully tuned
 * numbers for an audio mod and there is no reason to invent worse ones.
 *
 * <h2>What is deliberately not taken</h2>
 *
 * Everything else it does — reflection, reverberation, the sound of a room. That
 * is for making audio convincing to a player, and it has nothing to say about
 * whether a guard heard your footsteps. This is one number, absorbed per block
 * of wall.
 */
public final class Muffle {

	/**
	 * What an ordinary solid block takes out, before the material is considered.
	 *
	 * One, so that the material figures below read as multiples of "a normal wall"
	 * — which is how they are written in the mod they came from, and how anybody
	 * changing them would expect them to read.
	 */
	public static final float ORDINARY = 1f;

	private Muffle() { }

	/**
	 * The kinds of material there are, as far as sound is concerned.
	 *
	 * <h2>Why the table is not simply keyed on SoundType</h2>
	 *
	 * Because {@code SoundType}'s static initialiser needs the game's registries,
	 * so merely mentioning one of its constants outside a running game throws. A
	 * table written directly against them cannot be asked a single question without
	 * starting Minecraft — which is exactly backwards for the part most likely to be
	 * quietly wrong.
	 *
	 * So the policy lives here, in plain numbers that any test can read, and {@link
	 * #factorOf} is the one line that binds a game type to one of them. That line is
	 * checked against the jar by eye rather than by test, and it is the sort of line
	 * where that is enough: it names constants and returns constants, and if a name
	 * were wrong it would not compile.
	 */
	public enum Absorbs {

		/** Wool. Better than stone, genuinely, and worth a padded room meaning something. */
		PADDING(1.5f),

		/** Moss, and the moss carpet. */
		SOFT(0.75f),

		/** Honey. */
		STICKY(0.5f),

		/** Glass, snow, bamboo, wet grass: things that stop sight and not sound. */
		THIN(0.1f),

		/** Vines, crops, ladders, chains. A curtain is not a wall. */
		NOTHING(0f),

		/** Stone, wood, metal, and everything nobody has had an opinion about. */
		ORDINARY(1f);

		public final float factor;

		Absorbs(float factor) {
			this.factor = factor;
		}
	}

	/** Which kind of material this is. The one line that needs a running game. */
	public static Absorbs classOf(SoundType type) {
		if (type == SoundType.WOOL) return Absorbs.PADDING;
		if (type == SoundType.MOSS || type == SoundType.MOSS_CARPET) return Absorbs.SOFT;
		if (type == SoundType.HONEY_BLOCK) return Absorbs.STICKY;
		if (type == SoundType.GLASS || type == SoundType.SNOW
			|| type == SoundType.POWDER_SNOW || type == SoundType.BAMBOO
			|| type == SoundType.WET_GRASS) return Absorbs.THIN;
		if (type == SoundType.VINE || type == SoundType.GRASS || type == SoundType.CROP
			|| type == SoundType.LADDER || type == SoundType.CHAIN
			|| type == SoundType.SWEET_BERRY_BUSH || type == SoundType.ROOTS) {
			return Absorbs.NOTHING;
		}
		return Absorbs.ORDINARY;
	}

	/** How much this material absorbs, as a multiple of an ordinary block. */
	public static float factorOf(SoundType type) {
		return classOf(type).factor;
	}

	/**
	 * How much of a sound survives after passing through that much wall.
	 *
	 * Each block's worth of absorption takes a fixed fraction of what is left, so
	 * two walls are not twice as good as one, they are one squared — which is how
	 * absorption actually works and is why a second layer of stone is worth so much
	 * less than the first.
	 *
	 * @param through the absorption summed along the line, in ordinary blocks
	 */
	public static float carried(float through) {
		if (through <= 0) return 1;
		return (float) Math.pow(SURVIVES, through);
	}

	/**
	 * What fraction of a sound gets through one ordinary block.
	 *
	 * <h2>Chosen against the thing that has to stay true</h2>
	 *
	 * A single wall must not make a running player inaudible, because then hiding
	 * is solved by standing behind anything and there is no game in it. And three
	 * or four blocks of stone must be near enough silence, or walls mean nothing.
	 *
	 * At a third: one wall leaves a sprinter at about a third — still plainly
	 * audible, and that is the tense case. Two leaves a ninth, about the level of
	 * walking in the open. Four leaves under a hundredth, which is below the
	 * threshold of hearing and therefore genuinely nothing.
	 */
	private static final double SURVIVES = 0.34;
}
