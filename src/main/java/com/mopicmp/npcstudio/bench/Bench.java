package com.mopicmp.npcstudio.bench;

import java.util.ArrayList;
import java.util.List;

import com.mopicmp.npcstudio.brain.Senses;
import com.mopicmp.npcstudio.dialogue.Sense;
import com.mopicmp.npcstudio.dialogue.Value;
import com.mopicmp.npcstudio.entity.NpcEntity;
import com.mopicmp.npcstudio.foe.Din;
import com.mopicmp.npcstudio.foe.Draw;
import com.mopicmp.npcstudio.foe.Noise;

import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Making a character do something now, so that it can be watched.
 *
 * <h2>What belongs here and what does not</h2>
 *
 * Two things belong: an <em>action</em> that a graph will eventually order and
 * cannot yet, and a <em>readout</em> of something that is invisible from
 * outside. Nothing else. A setting does not belong here — a setting is a fact
 * about a character and lives on the character panel, which is where the ones
 * that were briefly commands have gone.
 *
 * The distinction is worth keeping sharp because this file is meant to shrink.
 * Every action here is a verb block programming does not have yet, so each one
 * should disappear as step C adds it. The readouts will outlive the actions and
 * are the cheaper half anyway.
 *
 * <h2>Why the readouts exist at all</h2>
 *
 * Because a character standing still may be waiting on a condition that is
 * false, or on one that is false because a reading behind it is not what
 * anybody thought — and from outside those are the same character standing
 * still. Every round trip during the perception work was spent telling those
 * apart by watching a body.
 */
public final class Bench {

	private Bench() { }

	/** As far away as a character can be and still be the one meant. */
	private static final double RANGE = 64.0;

	public static void asked(ServerPlayer player, int entityId, String action) {
		// The same gate the mod's own commands require, asked in the one place that
		// matters. A client that lies about its permission level gets here and is
		// refused here.
		if (!Commands.LEVEL_GAMEMASTERS.check(player.permissions())) {
			tell(player, List.of("You are not allowed to do that."));
			return;
		}
		if (!(player.level().getEntity(entityId) instanceof NpcEntity npc)
				|| npc.distanceToSqr(player) > RANGE * RANGE) {
			tell(player, List.of("No character selected, or too far away."));
			return;
		}

		tell(player, switch (action) {
			case "senses" -> senses(npc);
			case "brain" -> brain(npc);
			case "watch" -> watching(npc, player);
			case "fire" -> fire(npc, player);
			case "forget" -> forget(npc);
			default -> List.of("The bench has nothing called \"" + action + "\".");
		});
	}

	private static void tell(ServerPlayer player, List<String> lines) {
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
			new com.mopicmp.npcstudio.net.BenchPayloads.Told(List.copyOf(lines)));
	}

	// ---------------------------------------------------------------- readouts

	/** Every reading a graph could ask for, and what she remembers of herself. */
	private static List<String> senses(NpcEntity npc) {
		List<String> lines = new ArrayList<>();
		for (String name : Sense.KNOWN) {
			lines.add("  " + name + " = " + shown(Senses.of(npc, name)));
		}
		lines.add(npc.memory().isEmpty()
			? "remembers nothing about herself"
			: "remembers " + new java.util.TreeMap<>(npc.memory()));
		return lines;
	}

	/**
	 * Why her brain is doing what it is doing, or nothing.
	 *
	 * <h2>Why this is the readout that was missing</h2>
	 *
	 * A character with a brain that does nothing looks exactly like a character
	 * with no brain at all, and there are five separate reasons she might be
	 * standing there: no graph named, a name that is not registered, parked on a
	 * condition that is false, a skill running that is itself waiting, or — the
	 * one that cost a whole test session — a scenario looking for one of her own
	 * kind when there is nobody else who has been given the same brain.
	 *
	 * The last is worth the census below on its own. From outside, "nobody else
	 * has this brain" and "the fighting is broken" are the same two characters
	 * standing still, and only one of them is a bug.
	 */
	private static List<String> brain(NpcEntity npc) {
		List<String> lines = new ArrayList<>();
		if (npc.brainId().isEmpty()) {
			return List.of("No brain. Put a graph in the brain field on the character panel.");
		}
		var graph = com.mopicmp.npcstudio.dialogue.runtime.DialogueRegistry.get(npc.brainId())
			.orElse(null);
		if (graph == null) {
			return List.of("Her brain is \"" + npc.brainId() + "\", which no longer exists.");
		}

		lines.add("brain \"" + npc.brainId() + "\"");
		var mind = npc.mind(graph);
		lines.add("  scenario at: " + mind.currentNode());
		lines.add(npc.doingNow().isEmpty()
			? "  no skill running"
			: "  doing \"" + npc.doingNow() + "\" at " + (npc.doingState() == null
				? "?" : npc.doingState().currentNode()) + ", about " + npc.doingAt());
		if (!graph.segments().isEmpty()) {
			lines.add("  skills: " + String.join(", ", graph.segments().keySet()));
		}

		// The census. Everybody else near enough to matter, and what they are
		// running — because "kin" means somebody with the same brain, and the
		// commonest way for a fight not to start is that nobody else has one.
		var near = npc.getBoundingBox().inflate(com.mopicmp.npcstudio.brain.Marks.WITHIN);
		var others = npc.level().getEntitiesOfClass(NpcEntity.class, near).stream()
			.filter(other -> other != npc && other.isAlive()).toList();
		if (others.isEmpty()) {
			lines.add("nobody else within " + (int) com.mopicmp.npcstudio.brain.Marks.WITHIN
				+ " blocks");
			return lines;
		}
		for (NpcEntity other : others) {
			String why = other.brainId().isEmpty() ? "no brain"
				: !other.brainId().equals(npc.brainId()) ? "brain \"" + other.brainId() + "\""
				: !npc.hasLineOfSight(other) ? "same brain, but nothing in sight of her"
				: "SAME BRAIN, in sight — this one is kin";
			lines.add("  " + Math.round(npc.distanceTo(other)) + " blocks: " + why);
		}
		return lines;
	}

	private static String shown(Value value) {
		return switch (value) {
			// Rounded, because this is read at a glance while walking backwards and
			// "0.6183" is not legible where "0.62" is.
			case Value.Num(double d) -> String.format(java.util.Locale.ROOT, "%.2f", d);
			case Value.Text(String t) -> t;
			case Value.Flag(boolean f) -> f ? "yes" : "no";
		};
	}

	/**
	 * What she has noticed, and what the asker sounds like from where she stands.
	 *
	 * The second half is the line that would have saved a round trip once already:
	 * hearing had never worked at all, and from outside that is indistinguishable
	 * from hearing that does not reach far enough.
	 */
	private static List<String> watching(NpcEntity npc, Player asking) {
		if (!npc.watchful()) {
			return List.of("She is not watching — turn it on in the character panel.");
		}
		List<String> lines = new ArrayList<>();
		var watch = npc.watch();

		lines.add(watch.mood() + ", alarm " + Math.round(watch.alarm() * 100) + "%");

		float loud = watch.loudnessOf(asking);
		float reaching = watch.hearing(asking);
		lines.add("you: " + Math.round(loud * 100) + "% loud, reaching her at "
			+ Math.round(reaching * 100) + "%"
			+ (reaching <= 0 && loud > 0 ? " (inaudible from there)" : ""));

		var lead = watch.lead();
		var quarry = watch.quarry();
		if (lead == null) {
			lines.add("nothing has her attention");
		} else {
			lines.add((lead.seen()
					? "watching " + (quarry == null ? "somebody" : quarry.getName().getString())
					: "a noise at " + Math.round(lead.at().x) + " " + Math.round(lead.at().y)
						+ " " + Math.round(lead.at().z))
				+ " — urgency " + Math.round(lead.urgency() * 100) + "%"
				+ ", strength " + Math.round(lead.strength() * 100) + "%"
				+ ", turning " + Math.round(lead.turningAt()) + "°/tick");
		}

		// Whether the world's own racket is reaching her, which is otherwise
		// indistinguishable from nothing having happened.
		long now = npc.level().getGameTime();
		int noises = 0;
		for (var rumour : Din.since(now)) {
			if (Noise.heard(npc.position().distanceTo(rumour.at()),
					rumour.loudness(), rumour.carries()) > 0) {
				noises++;
			}
		}
		if (noises > 0) lines.add(noises + " noise" + (noises == 1 ? "" : "s") + " in earshot");

		var walk = npc.walking();
		if (walk.walking()) {
			lines.add("walking — waypoint " + walk.reached() + " of " + walk.waypoints()
				+ (walk.stuck() ? ", STUCK" : ""));
		}
		return lines;
	}

	// ----------------------------------------------------------------- actions

	/**
	 * One shot at whoever asked.
	 *
	 * <h2>Why this one is still here</h2>
	 *
	 * Because firing is a verb and block programming has no verbs yet — that is
	 * step C. When it has one, this goes.
	 *
	 * She is pointed at the asker once, at the moment of the order. Aiming is a
	 * later step, and an arrow that leaves the hand correctly but flies off at a
	 * wall proves nothing. Note that it is the <em>body</em> being turned: the
	 * projectile takes the shooter's own rotation, not the head's, which is worth
	 * seeing before the aiming step is designed.
	 */
	private static List<String> fire(NpcEntity npc, ServerPlayer at) {
		var weapon = npc.getMainHandItem();
		if (weapon.isEmpty()) {
			return List.of("Her hand is empty — give her something on the character panel.");
		}
		var kind = com.mopicmp.npcstudio.foe.Arms.of(weapon);
		if (kind != com.mopicmp.npcstudio.foe.Arms.Kind.DRAWN) {
			// Named rather than refused outright: the bench is for finding out what
			// happens, and "she is holding something that is not drawn and released"
			// is exactly the sort of thing that is invisible from the outside.
			return List.of(weapon.getHoverName().getString() + " reads as "
				+ kind.name().toLowerCase(java.util.Locale.ROOT) + ",",
				"and only a drawn weapon can be fired this way yet.");
		}
		if (weapon.getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem ranged
				&& com.mopicmp.npcstudio.foe.Firing.ammoFor(npc, ranged).isEmpty()) {
			return List.of("Nothing to shoot with: put ammunition in her off hand,",
				"or turn on endless ammo.");
		}

		float yaw = (float) com.mopicmp.npcstudio.foe.Sight.yawTo(
			npc.getX(), npc.getZ(), at.getX(), at.getZ());
		var eye = npc.getEyePosition();
		var theirs = at.getEyePosition();
		npc.setYRot(yaw);
		npc.yBodyRot = yaw;
		npc.setYHeadRot(yaw);
		npc.setXRot(com.mopicmp.npcstudio.foe.Neck.pitchTo(
			eye.x, eye.y, eye.z, theirs.x, theirs.y, theirs.z));

		int drawFor = Draw.longEnoughFor(0.95f);
		npc.fire(drawFor);
		return List.of("Firing " + weapon.getHoverName().getString(),
			"drawing " + drawFor + " ticks, "
				+ Math.round(Draw.powerOf(drawFor) * 100) + "% power");
	}

	/**
	 * Wipes what she has learnt about herself.
	 *
	 * An action rather than a readout, and one that will not go away with step C:
	 * a character's memory outlives a restart on purpose, so without this there is
	 * no way to see a graph's first run twice.
	 */
	private static List<String> forget(NpcEntity npc) {
		npc.rememberOnly(java.util.Map.of());
		return List.of("She has forgotten everything about herself.");
	}
}
