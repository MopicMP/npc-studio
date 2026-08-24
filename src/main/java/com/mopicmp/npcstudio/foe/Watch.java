package com.mopicmp.npcstudio.foe;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

/**
 * One character keeping an eye out: the part that needs a world.
 *
 * <h2>What is here and what is deliberately not</h2>
 *
 * Here: gathering what the senses bring in, asking the world about walls, and
 * saying where the head should point. Not here: any of the arithmetic — how wide
 * a cone is, how fast noticing happens, how vague a noise is, how fast to turn.
 * That lives in {@link Sight}, {@link Alarm}, {@link Din}, {@link Shots} and
 * {@link Lead}, where it is tested without a game.
 *
 * The split is not tidiness. A guard who notices a little too eagerly does not
 * crash and does not log; the report comes back a week later as "it feels
 * wrong", and finding out why means being able to ask the numbers a question.
 *
 * <h2>One kind of finding</h2>
 *
 * Every sense produces a {@link Lead} and the best one wins. This replaced a
 * branch per sense, each writing into its own fields, with what she was looking
 * at reassembled from those fields afterwards by a second set of rules — and the
 * two sets disagreed. See {@link Lead} for the bugs that came of it; they were
 * reported as one symptom and were really one cause.
 *
 * <h2>Server only</h2>
 *
 * Because two answers to "have I been seen" is worse than none. The client is
 * told the mood and believes it, which also means a client cannot find out
 * whether it has been spotted by asking its own copy of the world.
 */
public final class Watch {

	/**
	 * How often the world is asked, in ticks.
	 *
	 * Not every tick, because the expensive questions here are the walls and the
	 * search through the air. Five times a second is far finer than noticing needs
	 * and a fifth of the cost.
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

	/** What she is attending to, or null. The single answer to "what now". */
	private Lead lead;

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

	public Lead lead() {
		return lead;
	}

	/** Who is being watched, or null when it was only a noise. */
	public LivingEntity quarry() {
		return lead == null ? null : lead.who();
	}

	/** Whether what she is going on is eyes rather than ears. */
	public boolean bySight() {
		return lead != null && lead.seen();
	}

	/** Forgets everything: used when something else takes the character over. */
	public void standDown() {
		alarm = 0;
		mood = Alarm.Mood.CALM;
		lead = null;
		attention = 0;
		attending = 0;
		attendingUntil = 0;
		seenFlying.clear();
	}

	/**
	 * One tick of watching.
	 *
	 * The order matters and is the opposite of the obvious one: the world is asked
	 * occasionally and the meter is moved every time. Written the other way round
	 * the alarm rose in four-tick steps and a character noticed you in jumps.
	 */
	public void tick() {
		if (--untilLook <= 0) {
			untilLook = LOOKS_EVERY;
			look();
		}
		// Sound alone stops short of certainty. Sight has no ceiling, so the two are
		// one call with a different limit rather than two paths that have to be kept
		// saying the same thing.
		float strength = lead == null ? 0 : lead.strength();
		alarm = Alarm.next(alarm, strength, bySight() ? 1f : Noise.CEILING);
		mood = Alarm.moodOf(alarm, mood);
		if (mood == Alarm.Mood.CALM) {
			lead = null;
			attention = 0;
		} else {
			attention++;
		}
	}

	/**
	 * How long this has been paying attention to something, in ticks.
	 *
	 * The difference between a glance and turning to face somebody. It is natural to
	 * turn your head towards a stranger; standing there looking sideways at them is
	 * not, and nobody holds their neck at sixty degrees while they talk.
	 */
	private int attention;

	public int attention() {
		return attention;
	}

	/** Whether she has been looking long enough to turn and face it. */
	public boolean squaresUp() {
		return attention > 20;
	}

	/**
	 * How important whatever has her attention was, and until when.
	 *
	 * Otherwise the loudest thing in the last second always wins, and a character
	 * who has just turned towards an explosion is pulled away by somebody shutting a
	 * door. That is not alertness, it is distractibility, and it looks foolish in
	 * exactly the moment a character most needs not to.
	 *
	 * Held rather than latched: the hold runs out and ordinary things are
	 * interesting again. And a sighting ignores it entirely — nothing outranks
	 * looking straight at somebody.
	 */
	private float attending;
	private long attendingUntil;

	/** How long the most serious possible noise holds attention, in ticks. */
	private static final int HOLDS_FOR = 60;

	// ------------------------------------------------------------- the looking

	private void look() {
		long now = self.level().getGameTime();
		if (now > attendingUntil) attending = 0;

		Lead best = null;
		for (Player player : self.level().players()) {
			if (!worthWatching(player)) continue;
			best = better(best, fromEyes(player));
			best = better(best, fromEars(player));
		}
		best = better(best, fromTheWorld(now));
		best = better(best, fromShots(now));

		if (best == null) {
			// Nothing new. Whatever she was attending to stays where it was, so that a
			// character does not turn away the instant a sound stops — the alarm
			// draining is what ends it, and that takes seconds.
			return;
		}
		// A sighting is never held off by anything; every other kind has to be at
		// least as important as what is already occupying her.
		if (!best.seen() && best.urgency() < attending) return;

		lead = best;
		if (!best.seen()) {
			attending = best.urgency();
			attendingUntil = now + (long) (HOLDS_FOR * best.urgency());
		}
	}

	private static Lead better(Lead held, Lead offered) {
		return offered != null && offered.beats(held) ? offered : held;
	}

	/**
	 * Somebody in view.
	 *
	 * <h2>The cone is for noticing, not for keeping hold of</h2>
	 *
	 * Once somebody has your full attention you do not lose them because your head
	 * lagged behind them. Without that rule the angle is checked afresh every look,
	 * so a character who had already seen you could be un-seen simply by being
	 * faster than her neck — which is what "you can jog in circles behind her"
	 * meant. Turning the head faster never fixes it: there is always a radius at
	 * which a running player out-turns any fixed rate.
	 */
	private Lead fromEyes(Player player) {
		double distance = self.distanceTo(player);
		if (distance > sight.range()) return null;

		Vec3 eye = self.getEyePosition();
		double towards = Sight.yawTo(eye.x, eye.z, player.getX(), player.getZ());
		// The head and not the body: a character standing one way and looking another
		// sees where she is looking, which is the whole reason heads turn.
		double off = Sight.turnBetween(self.getYHeadRot(), towards);

		boolean holding = mood == Alarm.Mood.ALERT && player == quarry();
		float seen = sight.strength(distance, holding ? 0 : off);
		if (seen <= 0) return null;

		// Asked last, because it is the expensive one and most candidates have been
		// ruled out by an angle or a distance that costs nothing.
		return self.hasLineOfSight(player) ? Lead.sighting(player, seen) : null;
	}

	/**
	 * Somebody heard but not seen.
	 *
	 * Placed as vaguely as any other noise, and that is a correction rather than a
	 * flourish. Hearing somebody move behind a wall used to hand back their exact
	 * position every tick, which is watching them through the wall by another name;
	 * and when they stopped, the head snapped away as though nothing had happened.
	 * One mistake, reported as two.
	 */
	private Lead fromEars(Player player) {
		float loudness = loudnessOf(player);
		if (loudness <= 0) return null;

		float wall = throughWalls(player);
		float reaching = Noise.heard(self.distanceTo(player), loudness * wall);
		if (wall < 1) {
			double round = around(player);
			if (round >= 0) reaching = Math.max(reaching, Noise.heard(round, loudness));
		}
		if (reaching <= 0) return null;

		double away = self.position().distanceTo(player.position());
		Vec3 guess = Din.guessAt(
			new Din.Rumour(player.position(), loudness, Noise.EARSHOT, FOOTSTEPS, self.tickCount),
			Din.vagueness(reaching, away), self.position());
		// The person is remembered even though the place is a guess: it costs nothing
		// and it is what lets a sighting a moment later be recognised as the same
		// person rather than as a fresh discovery.
		return Lead.noise(guess, player, reaching, FOOTSTEPS);
	}

	/**
	 * How urgent somebody moving about is.
	 *
	 * The plainest thing there is. Footsteps are how you notice that anybody is
	 * there at all, and they must never out-rank a bang — that was the whole of the
	 * crater problem, in the other direction.
	 */
	private static final float FOOTSTEPS = 0.2f;

	/** Whatever the world itself banged, which has no author. */
	private Lead fromTheWorld(long now) {
		Lead best = null;
		for (Din.Rumour rumour : Din.since(now)) {
			double away = self.position().distanceTo(rumour.at());
			float reaching = Noise.heard(away, rumour.loudness(), rumour.carries());
			if (reaching <= 0) continue;
			Vec3 guess = Din.guessAt(rumour, Din.vagueness(reaching, away), self.position());
			best = better(best, Lead.noise(guess, null, reaching, rumour.urgency()));
		}
		return best;
	}

	// ------------------------------------------------------------- the shooting

	/** Projectiles noticed going past, and the tick their meaning becomes clear. */
	private final java.util.Map<Integer, Long> seenFlying = new java.util.HashMap<>();

	/**
	 * An arrow going past, and where it must have come from.
	 *
	 * Watched first and understood a moment later. A character who snaps to the
	 * firing position on the frame the arrow passes has not deduced anything — she
	 * has read its owner — and it looks like it, because no thought is that fast.
	 */
	private Lead fromShots(long now) {
		var near = self.getBoundingBox().inflate(Shots.NOTICED_WITHIN);
		Lead best = null;

		for (Projectile flying : self.level().getEntitiesOfClass(Projectile.class, near)) {
			Vec3 course = flying.getDeltaMovement();
			if (course.lengthSqr() < 0.01) continue;
			// Its own doing, which is not news to her.
			if (flying.getOwner() == self) continue;
			if (!self.hasLineOfSight(flying)) continue;

			float startle = Shots.startle(self.position().distanceTo(flying.position()));
			if (startle <= 0) continue;

			Long clearAt = seenFlying.get(flying.getId());
			if (clearAt == null) {
				seenFlying.put(flying.getId(), now + Shots.WORKING_IT_OUT);
				continue;
			}
			if (now < clearAt) continue;

			best = better(best, Lead.noise(backAlong(flying), null, startle, Shots.URGENCY));
		}

		// Arrows land and are removed, so their entries would otherwise pile up for
		// as long as the world stands.
		seenFlying.entrySet().removeIf(entry -> now - entry.getValue() > 100);
		return best;
	}

	/**
	 * Back along the flight until something is in the way.
	 *
	 * The wall it came round is a better guess at where the shooter is than a fixed
	 * distance would be — an arrow through a doorway says the archer is at the
	 * doorway, not sixteen blocks beyond it through solid rock.
	 */
	private Vec3 backAlong(Projectile flying) {
		Vec3 at = flying.position();
		Vec3 course = flying.getDeltaMovement();
		Vec3 far = Shots.firedFrom(at, course, Shots.BACK_ALONG);

		var blocked = self.level().clip(new net.minecraft.world.level.ClipContext(at, far,
			net.minecraft.world.level.ClipContext.Block.COLLIDER,
			net.minecraft.world.level.ClipContext.Fluid.NONE, flying));
		if (blocked.getType() == net.minecraft.world.phys.HitResult.Type.MISS) return far;
		return Shots.firedFrom(at, course, at.distanceTo(blocked.getLocation()));
	}

	// ------------------------------------------------------------- the plumbing

	/**
	 * Whether somebody is a candidate at all, before any geometry.
	 *
	 * Spectators are not there; creative players are, deliberately. Skipping
	 * creative would make this untestable by the only person going to test it.
	 */
	private boolean worthWatching(Player player) {
		return player.isAlive() && !player.isSpectator() && player != self;
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
	 * sprinting. Every player was perfectly silent, and had been since hearing was
	 * written.
	 *
	 * {@code getKnownMovement} is the right call and this was checked rather than
	 * assumed: {@code ServerGamePacketListenerImpl} sets it from the movement packet.
	 */
	public float loudnessOf(Player player) {
		double pace = player.getKnownMovement().horizontalDistance();
		return Noise.loudness(pace, player.isShiftKeyDown(),
			player.isSprinting(), player.isInWater());
	}

	/** What is actually reaching these ears from somebody, for a readout to show. */
	public float hearing(Player player) {
		Lead heard = fromEars(player);
		return heard == null ? 0 : heard.strength();
	}

	/**
	 * How much of a sound survives the walls between here and there.
	 *
	 * <h2>Walked rather than clipped</h2>
	 *
	 * The game's own ray stops at the first thing it hits, which answers "is there a
	 * wall" and not "how much wall" — and the difference is the feature. One block
	 * of stone between you and a guard should be tense; four should be silence.
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
			if (state.isAir() || state.getCollisionShape(self.level(), where).isEmpty()) continue;
			absorbed += Muffle.factorOf(state.getSoundType());
		}
		return Muffle.carried(absorbed);
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
	 * Where the head should be pointing this tick, or null to leave it alone.
	 *
	 * Handed back rather than applied, because who is allowed to turn this
	 * character's head is not this class's business — a scene playing through her
	 * outranks anything here.
	 *
	 * One rule, and it is the whole of what used to go wrong: a sighting is followed
	 * live, and everything else is a fixed place. A guess does not become a track
	 * however long it goes on.
	 */
	public Vec3 lookingAt() {
		if (mood == Alarm.Mood.CALM || lead == null) return null;
		return lead.seen() && lead.who() != null ? lead.who().getEyePosition() : lead.at();
	}
}
