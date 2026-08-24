package com.mopicmp.npcstudio.foe;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * One character keeping an eye out: the part that needs a world.
 *
 * <h2>What is here and what is deliberately not</h2>
 *
 * Here: finding somebody to look at, asking the world whether a wall is in the
 * way, and turning the head. Not here: any of the arithmetic — how wide a cone
 * is, how fast noticing happens, when a mood changes. That lives in {@link
 * Sight} and {@link Alarm}, where it is tested without a game, and this class
 * is the thin wiring between those and Minecraft.
 *
 * The split is not tidiness. A guard who notices a little too eagerly does not
 * crash and does not log; the report comes back a week later as "it feels
 * wrong", and finding out why means being able to ask the numbers a question.
 *
 * <h2>Server only</h2>
 *
 * Because two answers to "have I seen you" is worse than none. The client is
 * told the mood and believes it — see the synced mood on the character — which
 * also means a client cannot be lied to about whether it has been spotted by
 * checking what its own copy of the world thinks.
 */
public final class Watch {

	/**
	 * How often the world is asked, in ticks.
	 *
	 * Not every tick, because the expensive question here is the wall — a ray cast
	 * through blocks, once per watching character per player in range. Five times a
	 * second is far finer than noticing needs and a fifth of the cost.
	 *
	 * The alarm still moves every tick. That is the point of separating them: the
	 * meter is smooth even though the looking is not, so nothing downstream can
	 * tell where the samples were.
	 */
	private static final int LOOKS_EVERY = 4;

	private final LivingEntity self;
	private Sight sight = Sight.ORDINARY;

	private float alarm;
	private Alarm.Mood mood = Alarm.Mood.CALM;

	/** What is being watched, and how strongly it was seen when last looked at. */
	private LivingEntity quarry;
	private float strength;

	private int untilLook;

	public Watch(LivingEntity self) {
		this.self = self;
	}

	public Sight sight() {
		return sight;
	}

	public void sight(Sight given) {
		sight = given == null ? Sight.ORDINARY : given;
	}

	public Alarm.Mood mood() {
		return mood;
	}

	public float alarm() {
		return alarm;
	}

	/** Who is being watched, or null. */
	public LivingEntity quarry() {
		return quarry;
	}

	/** Forgets everything: used when something else takes the character over. */
	public void standDown() {
		alarm = 0;
		mood = Alarm.Mood.CALM;
		quarry = null;
		strength = 0;
	}

	/**
	 * One tick of watching.
	 *
	 * The order matters and is the opposite of the obvious one: the world is asked
	 * occasionally and the meter is moved every time. Written the other way round —
	 * meter moved only when the world was asked — the alarm rose in four-tick steps
	 * and a character noticed you in visible jumps.
	 */
	public void tick() {
		if (--untilLook <= 0) {
			untilLook = LOOKS_EVERY;
			look();
		}
		alarm = Alarm.next(alarm, strength);
		mood = Alarm.moodOf(alarm, mood);
		if (mood == Alarm.Mood.CALM) quarry = null;
	}

	/**
	 * Finds the best thing to be looking at, and how well it is seen.
	 *
	 * "Best" is strongest rather than nearest, and the difference is real: somebody
	 * ten blocks away and straight ahead is more of a concern than somebody four
	 * blocks away and behind a shoulder. Nearest-first would have the character
	 * staring past the person it can actually see.
	 */
	private void look() {
		strength = 0;
		LivingEntity best = null;

		for (Player player : self.level().players()) {
			if (!worthWatching(player)) continue;
			float seen = strengthOf(player);
			if (seen > strength) {
				strength = seen;
				best = player;
			}
		}

		// The old quarry is kept while nothing better is in view, so that stepping
		// behind a pillar leaves the character still looking where you went rather
		// than losing interest the instant the ray is blocked.
		if (best != null) quarry = best;
	}

	/**
	 * Whether somebody is a candidate at all, before any geometry.
	 *
	 * Spectators are not there; creative players are, deliberately. Skipping
	 * creative would make this untestable by the only person who is going to test
	 * it, which is a poor trade for a realism nobody asked for.
	 */
	private boolean worthWatching(Player player) {
		return player.isAlive() && !player.isSpectator() && player != self;
	}

	/** How strongly one candidate is seen, walls included. */
	private float strengthOf(LivingEntity target) {
		double distance = self.distanceTo(target);
		if (distance > sight.range()) return 0;

		Vec3 eye = self.getEyePosition();
		double towards = Sight.yawTo(eye.x, eye.z, target.getX(), target.getZ());
		// The head and not the body: a character standing one way and looking
		// another sees where it is looking, which is the whole reason heads turn.
		double off = Sight.turnBetween(self.getYHeadRot(), towards);

		float seen = sight.strength(distance, off);
		if (seen <= 0) return 0;

		// Asked last, because it is the expensive one and most candidates have
		// already been ruled out by an angle or a distance that costs nothing.
		return self.hasLineOfSight(target) ? seen : 0;
	}

	/**
	 * Where the head should be pointing this tick, or null to leave it alone.
	 *
	 * Handed back rather than applied, because who is allowed to turn this
	 * character's head is not this class's business — a scene playing through it
	 * outranks anything here, and the character itself is the only thing that knows
	 * whether one is.
	 */
	public Vec3 lookingAt() {
		return quarry == null || mood == Alarm.Mood.CALM ? null : quarry.getEyePosition();
	}
}
