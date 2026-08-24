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

	/** Whether the last thing that raised the alarm was eyes rather than ears. */
	private boolean bySight;

	/**
	 * Where the quarry was when it was last actually noticed.
	 *
	 * <h2>The bug this is the fix for</h2>
	 *
	 * Without it a character went on tracking you <em>through the wall</em> you had
	 * just stepped behind — her head following you along it for the two or three
	 * seconds the alarm takes to drain, and then abruptly giving up. It was
	 * reported exactly that way: she keeps watching until you stop, and then
	 * suddenly realises she cannot see through walls.
	 *
	 * Nothing was wrong with the noticing. The mistake was that "look at the
	 * quarry" asked the quarry where it was <em>now</em>, and while forgetting
	 * somebody you do not know where they are — that is what forgetting is. So the
	 * position is remembered at the moment of seeing, and the head goes there.
	 *
	 * Which is also the behaviour worth having on its own account: a guard staring
	 * at the doorway you vanished through is what being hunted looks like, and it
	 * is the hook every later step hangs on. Walking over to look is the next one.
	 */
	private Vec3 lastKnown;

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
		bySight = false;
		lastKnown = null;
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
		// Sound alone stops short of certainty. Sight has no ceiling, so the two are
		// the same call with a different limit rather than two paths that have to be
		// kept saying the same thing.
		alarm = Alarm.next(alarm, strength, bySight ? 1f : Noise.CEILING);
		mood = Alarm.moodOf(alarm, mood);
		if (mood == Alarm.Mood.CALM) {
			quarry = null;
			lastKnown = null;
			attention = 0;
		} else {
			attention++;
		}
	}

	/**
	 * How long this has been paying attention to something, in ticks.
	 *
	 * <h2>What it is for</h2>
	 *
	 * The difference between a glance and turning to face somebody. Reported like
	 * this: it is natural to turn your head towards a stranger, but standing there
	 * looking sideways at them is not — not while you talk to them, and not for
	 * minutes on end. Nobody holds their neck at sixty degrees; after a moment they
	 * bring their shoulders round.
	 *
	 * A counter rather than a state, because the whole question is <em>how long</em>,
	 * and because it costs one int and turns "glance, then commit" into arithmetic
	 * the character can be asked about.
	 */
	private int attention;

	public int attention() {
		return attention;
	}

	/**
	 * Whether this has been looking long enough to turn and face what it sees.
	 *
	 * About a second. Short enough that it never looks like indecision, long enough
	 * that a character following somebody past a doorway does it with her eyes, the
	 * way a person would.
	 */
	public boolean squaresUp() {
		return attention > 20;
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
		bySight = false;
		LivingEntity best = null;

		for (Player player : self.level().players()) {
			if (!worthWatching(player)) continue;

			float seen = strengthOf(player);
			float noise = heardFrom(player);
			// Eyes win ties, because when both are available the eyes are the better
			// evidence and the ceiling should not apply.
			float evidence = Math.max(seen, noise);
			if (evidence <= strength) continue;

			strength = evidence;
			bySight = seen >= noise;
			best = player;
		}

		if (best == null) return;
		quarry = best;
		// Remembered here and only here: this is the moment of noticing, and where
		// somebody was when they were noticed is all a character can honestly know
		// about where they are.
		lastKnown = bySight ? best.getEyePosition() : best.position();
	}

	/**
	 * How loudly one candidate is heard, walls not included.
	 *
	 * Not included on purpose. A wall stops sight outright and muffles sound, and
	 * modelling that properly means knowing what the wall is made of and how thick
	 * it is — a large piece of work whose reward is that a guard hears you slightly
	 * less through stone. Hearing straight through is the honest simplification,
	 * and it is the one that makes hiding behind a wall tense rather than safe.
	 */
	/**
	 * How loudly one candidate is heard, by whichever route the sound gets here.
	 *
	 * <h2>Two routes, and the louder wins</h2>
	 *
	 * Sound arrives both ways at once: some of it through the wall, muffled, and
	 * some of it round through the doorway, undiminished but having gone further.
	 * You hear the louder. Taking the better of the two is not a shortcut, it is
	 * what actually happens.
	 *
	 * The way round is only looked for when the direct line is blocked, because in
	 * the open the direct line already is the way round and the search would be an
	 * expensive way of finding that out.
	 */
	private float heardFrom(Player player) {
		float loudness = loudnessOf(player);
		if (loudness <= 0) return 0;

		float wall = throughWalls(player);
		float direct = Noise.heard(self.distanceTo(player), loudness * wall);
		if (wall >= 1) return direct;

		double round = around(player);
		if (round < 0) return direct;
		return Math.max(direct, Noise.heard(round, loudness));
	}

	/**
	 * How much noise somebody is making.
	 *
	 * <h2>The call that made hearing do nothing whatever</h2>
	 *
	 * This asked {@code getDeltaMovement}, which is the obvious method and is empty
	 * for the one kind of entity that matters. A player on the server does not move
	 * by having a velocity applied — the client says where it has got to and the
	 * server puts it there — so the field reads nought however hard somebody is
	 * sprinting. Every player was permanently, perfectly silent, and had been since
	 * hearing was written.
	 *
	 * It was reported as a wall one block high behind which nothing could be heard
	 * whatever was done there, and then, rightly, as the hearing distance being
	 * fixed and very small. Both are the same nought. The wall had nothing to do
	 * with it: one block does not even interrupt the line between two people's eyes.
	 *
	 * {@code getKnownMovement} is the right call and this was checked rather than
	 * assumed — {@code ServerGamePacketListenerImpl} sets it from the movement
	 * packet, which is precisely the number wanted here.
	 */
	public float loudnessOf(Player player) {
		double pace = player.getKnownMovement().horizontalDistance();
		return Noise.loudness(pace, player.isShiftKeyDown(),
			player.isSprinting(), player.isInWater());
	}

	/** What is actually reaching these ears from somebody, for a readout to show. */
	public float hearing(Player player) {
		return heardFrom(player);
	}

	/** How far the sound has to travel through open air, or -1 if it cannot. */
	private double around(Player player) {
		var level = self.level();
		var from = self.blockPosition().above();
		var to = player.blockPosition().above();
		return Airways.reach(
			(x, y, z) -> {
				var where = new net.minecraft.core.BlockPos(x, y, z);
				var state = level.getBlockState(where);
				return state.isAir() || state.getCollisionShape(level, where).isEmpty();
			},
			from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ());
	}

	/**
	 * How much of a sound survives the walls between here and there.
	 *
	 * <h2>Walked rather than clipped</h2>
	 *
	 * The game's own ray stops at the first thing it hits, which answers "is there
	 * a wall" and not "how much wall" — and the difference is the whole feature. One
	 * block of stone between you and a guard should be tense; four should be
	 * silence. So the line is stepped along and every block it passes through is
	 * counted, once each.
	 *
	 * At half a block a step this can miss a corner cut exactly diagonally. That is
	 * a real inaccuracy and it is left in: the cost of doing it exactly is a proper
	 * voxel traversal on every watcher every fifth of a second, and the reward is
	 * being right about a sound that was already going to be borderline.
	 */
	private float throughWalls(Player player) {
		Vec3 from = self.getEyePosition();
		Vec3 to = player.getEyePosition();
		double span = from.distanceTo(to);
		if (span < 0.5) return 1;

		int steps = (int) Math.ceil(span * 2);
		float absorbed = 0;
		net.minecraft.core.BlockPos last = null;
		for (int i = 1; i < steps; i++) {
			Vec3 at = from.lerp(to, i / (double) steps);
			var where = net.minecraft.core.BlockPos.containing(at);
			if (where.equals(last)) continue;
			last = where;

			var state = self.level().getBlockState(where);
			// Anything you can walk through is not a wall, whatever its material says.
			if (state.isAir() || state.getCollisionShape(self.level(), where).isEmpty()) continue;
			absorbed += Muffle.factorOf(state.getSoundType());
		}
		return Muffle.carried(absorbed);
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

	/**
	 * How strongly one candidate is seen, walls included.
	 *
	 * <h2>The cone is for noticing, not for keeping hold of</h2>
	 *
	 * Once somebody has your full attention you do not lose them because your head
	 * lagged half a second behind them. You keep them in sight by turning, and if
	 * they run round you, you turn with them.
	 *
	 * Without that rule the angle is checked afresh every look, so a character who
	 * had already seen you could be un-seen simply by being faster than her neck —
	 * and that is precisely what was reported, twice: you can jog round behind her
	 * while she turns, indefinitely, and nothing ever comes of it. Turning the head
	 * faster does not fix it, because there is always a radius at which a running
	 * player out-turns any fixed rate; it is the rule that is wrong, not the number.
	 *
	 * So while she is alert and it is you she is alert to, the angle stops
	 * mattering and only the wall does. Getting away needs cover or distance, which
	 * is what getting away ought to need.
	 */
	private float strengthOf(LivingEntity target) {
		double distance = self.distanceTo(target);
		if (distance > sight.range()) return 0;

		Vec3 eye = self.getEyePosition();
		double towards = Sight.yawTo(eye.x, eye.z, target.getX(), target.getZ());
		// The head and not the body: a character standing one way and looking
		// another sees where it is looking, which is the whole reason heads turn.
		double off = Sight.turnBetween(self.getYHeadRot(), towards);

		boolean holding = mood == Alarm.Mood.ALERT && target == quarry;
		float seen = sight.strength(distance, holding ? 0 : off);
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
		if (mood == Alarm.Mood.CALM) return null;
		// While the quarry is being noticed right now — by either sense — its live
		// position; the moment it is not, wherever it was last noticed. That switch
		// is the fix for tracking somebody through a wall, see lastKnown.
		//
		// Either sense, and not only sight, which was a mistake worth naming: gating
		// it on sight meant a character who could hear you was always turning towards
		// where you had been rather than where you were, and so could never catch up
		// with somebody circling her. Hearing does not tell you what something is. It
		// tells you where it is, continuously, which is exactly what it is for.
		if (strength > 0 && quarry != null) {
			return bySight ? quarry.getEyePosition() : quarry.position();
		}
		return lastKnown;
	}

	/** Whether what the character is going on is eyes rather than ears. */
	public boolean bySight() {
		return bySight && strength > 0;
	}
}
