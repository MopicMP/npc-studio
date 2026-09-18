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

	/**
	 * As far away as a character can be and still be the one meant.
	 *
	 * <h2>Why this is no longer a number of its own</h2>
	 *
	 * It was sixty-four, and it was written when the bench was a debug panel you
	 * opened while standing next to somebody. Next door, {@link
	 * com.mopicmp.npcstudio.net.NpcEditing#REACH_SQUARED} had already had this exact
	 * argument and written the answer down: arm's length was right when editing meant
	 * walking up to a character, and wrong once the workspace grew a camera that flies
	 * and panels that follow whoever is chosen. It settled on the far end of a scene
	 * rather than the far end of a world.
	 *
	 * Everything that reasoning covers is true here and more so, because the ring
	 * points at whoever is on the screen. Two numbers meant a band between them where
	 * a menu opened and every entry in it was refused — and refused with a line of
	 * English from this file, written for a panel, arriving over the ordinary game.
	 *
	 * So there is one bound, it lives where the argument is, and it is not smaller
	 * for these acts than for editing the same character's costume.
	 */
	public static final double RANGE_SQUARED =
		com.mopicmp.npcstudio.net.NpcEditing.REACH_SQUARED;

	public static void asked(ServerPlayer player, int entityId, String action) {
		// The same gate the mod's own commands require, asked in the one place that
		// matters. A client that lies about its permission level gets here and is
		// refused here.
		if (!Commands.LEVEL_GAMEMASTERS.check(player.permissions())) {
			tell(player, List.of("You are not allowed to do that."));
			return;
		}
		if (!(player.level().getEntity(entityId) instanceof NpcEntity npc)
				|| npc.distanceToSqr(player) > RANGE_SQUARED) {
			tell(player, List.of("No character selected, or too far away."));
			return;
		}

		tell(player, switch (action) {
			case "senses" -> senses(npc);
			case "brain" -> brain(npc);
			case "shipped" -> shipped(npc);
			case "watch" -> watching(npc, player);
			case "fire" -> fire(npc, player);
			case "forget" -> forget(npc);
			case "tape" -> tape(npc);
			case "restart" -> restart(npc);
			case "post" -> post(npc);
			default -> List.of("The bench has nothing called \"" + action + "\".");
		});
	}

	/**
	 * Puts her back at the top of her graph.
	 *
	 * <h2>Why this is the act a scene is built with</h2>
	 *
	 * Because a graph is judged by watching it run, and running it is the one thing
	 * that had no button anywhere. Every change to a scenario was tried by reloading
	 * the world or by waiting for whatever state she was in to end on its own — that
	 * is, by luck and patience rather than by asking.
	 *
	 * There is no "stop" beside it and there should not be. She has one document and
	 * it is always running; stopping it would be a state a finished map can never be
	 * in, so a button for it would be a button that only exists while building. What
	 * "stop" means in practice is either taking her graph off her, which is a fact
	 * about the character and belongs on her panel, or a {@code Halt} in the graph
	 * itself, which is the graph's own word for it.
	 */
	private static List<String> restart(NpcEntity npc) {
		if (npc.graphId().isEmpty()) return List.of("She has no graph to run.");
		npc.forgetWhereSheWas();
		return List.of("Started \"" + npc.graphId() + "\" again from the top.");
	}

	/**
	 * Fixes where she comes back to at where she is standing.
	 *
	 * The one relative mark an author sets by hand rather than the world setting it.
	 * A graph that says "walk to the lead, then back to post" is a patrol, and until
	 * now post was wherever she happened to be the first time the graph asked for it
	 * — which is where she was dropped, not where she belongs.
	 */
	private static List<String> post(NpcEntity npc) {
		npc.markPost();
		var at = npc.position();
		return List.of(String.format("Her post is here: %.1f, %.1f, %.1f", at.x, at.y, at.z));
	}

	/**
	 * The answer, as plain English, and that has stopped being harmless.
	 *
	 * <h2>What changed under it</h2>
	 *
	 * These lines were written for a debug panel: readouts of sense values and
	 * variable names, read by whoever was building the thing, and English was the
	 * right register for a list of {@code kin.distance = 4.2}.
	 *
	 * Two of these acts are now on the ring in the world, where they are ordinary
	 * authoring — "run her graph again", "her post is here" — and the answer arrives
	 * as an overlay message over the game. So a Russian-speaking author gets an
	 * English sentence at the one moment they are told whether anything happened.
	 *
	 * The honest fix is a translation key and arguments rather than a finished
	 * sentence, which means changing {@link com.mopicmp.npcstudio.net.BenchPayloads}
	 * to carry components. That is worth doing and is not worth doing quietly, so it
	 * is written down here rather than half-done: the readouts should probably stay
	 * as they are, and only the acts should be translated.
	 */
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
		// Said first, and only to somebody who had one. A character carried a brain
		// as a second field until this version; it has been folded into the one
		// document she has, and saying nothing about that would be the third silent
		// loss in a row.
		if (!npc.movedBrain().isEmpty()) {
			lines.add("she used to carry a separate brain, \"" + npc.movedBrain() + "\"");
			lines.add("  a character has one graph now, and it is the dialogue below");
		}
		if (npc.graphId().isEmpty()) {
			lines.add("No graph. Put one in the dialogue field on the character panel.");
			return lines;
		}
		var graph = com.mopicmp.npcstudio.dialogue.runtime.DialogueRegistry.get(npc.graphId())
			.orElse(null);
		if (graph == null) {
			lines.add("Her graph is \"" + npc.graphId() + "\", which no longer exists.");
			return lines;
		}

		// Which copy. The world's own wins silently over the one that ships with
		// the mod, and that is right for a deliberate edit and wrong for a damaged
		// one — and the two are indistinguishable without being told.
		boolean ownCopy = com.mopicmp.npcstudio.dialogue.runtime.DialogueRegistry
			.worldOwn(npc.graphId()).isPresent();
		lines.add("graph \"" + npc.graphId() + "\""
			+ (ownCopy ? " (this world's own copy)" : " (built in)"));

		var mind = npc.mind(graph);
		// Her own bookmark, the one that runs whether or not anybody is here. A
		// conversation is not this: that one is kept per player and per character,
		// so two people can be mid-sentence with her and neither of them is the
		// thread below.
		lines.add("  her own thread at: " + mind.currentNode());
		lines.add(npc.doingNow().isEmpty()
			? "  no skill running"
			: "  doing \"" + npc.doingNow() + "\" at " + (npc.doingState() == null
				? "?" : npc.doingState().currentNode()) + ", about " + npc.doingAt());
		// Said even when empty. Leaving the line out when there are none is the
		// same silence this whole readout exists to end: a graph with no skills at
		// all looked exactly like one whose skills were fine.
		lines.add("  skills: " + (graph.segments().isEmpty()
			? "NONE" : String.join(", ", graph.segments().keySet())));
		if (!npc.lastBlow().isEmpty()) {
			lines.add("  last blow: " + npc.lastBlow());
		}
		// Where in the swing she is, because a blow is an interval now and "she is
		// doing nothing" and "she is four ticks into a wind-up" are the same
		// character standing there from outside.
		if (npc.swinging()) {
			lines.add("  mid-swing, " + npc.blow().into() + " ticks in — she cannot be "
				+ "ordered anywhere until it is over");
		}
		if (!npc.brainTrouble().isEmpty()) {
			lines.add("  REFUSED: " + npc.brainTrouble());
		}

		if (ownCopy) {
			var built = com.mopicmp.npcstudio.dialogue.runtime.DialogueRegistry
				.shipped(npc.graphId()).orElse(null);
			if (built != null && !built.segments().isEmpty() && graph.segments().isEmpty()) {
				lines.add("  the built-in one has skills and this copy has none —");
				lines.add("  press \"forget this world's copy\" below.");
			}
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
			String why = other.graphId().isEmpty() ? "no graph"
				: !other.graphId().equals(npc.graphId()) ? "graph \"" + other.graphId() + "\""
				: !npc.hasLineOfSight(other) ? "same graph, but nothing in sight of her"
				: "SAME GRAPH, in sight — this one is kin";
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
		if (!npc.walkingTo().isEmpty()) lines.add("on route \"" + npc.walkingTo() + "\"");
		// The last climb she jumped for. Here because jumping has been reported three
		// times in words and answered wrongly twice: a climb of about a block against a
		// step of six tenths means the route is drawn a block too high, and nothing
		// here at all while she is visibly hopping means the body never left the ground
		// and what is wrong is the animation.
		double[] jumped = npc.lastJump();
		if (jumped != null) {
			lines.add(String.format(java.util.Locale.ROOT,
				"last jump — to y %.0f from %.2f, step %.2f",
				jumped[0], jumped[1], jumped[2]));
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
	 * Throws away this world's own copy of her brain.
	 *
	 * <h2>Why this exists at all</h2>
	 *
	 * Because saving a graph in the editor writes a copy into the world, that copy
	 * wins from then on, and until now nothing anywhere could remove it. So a
	 * single bad save was permanent — and there was one: the editor used to drop a
	 * document's skills on save, which left a brain that looked complete, ran, and
	 * did nothing.
	 *
	 * It is not only a repair. Going back to the version that ships with the mod,
	 * or the one a datapack provides, is an ordinary thing to want after an
	 * experiment.
	 */
	private static List<String> shipped(NpcEntity npc) {
		if (npc.graphId().isEmpty()) return List.of("She has no graph to reset.");
		String id = npc.graphId();
		if (com.mopicmp.npcstudio.dialogue.runtime.DialogueRegistry.worldOwn(id).isEmpty()) {
			return List.of("\"" + id + "\" is already the built-in one.");
		}
		if (com.mopicmp.npcstudio.dialogue.runtime.DialogueRegistry.shipped(id).isEmpty()) {
			return List.of("\"" + id + "\" exists only as this world's copy —",
				"forgetting it would leave nothing behind.");
		}
		if (!(npc.level() instanceof net.minecraft.server.level.ServerLevel level)) {
			return List.of("That can only be done on the server.");
		}
		com.mopicmp.npcstudio.dialogue.runtime.WorldDialogues.of(level).remove(id);
		// Her train of thought was built on the copy that has just gone.
		// Same document, fresh start: forgetting the world's copy leaves her holding
		// a bookmark into a graph that has just been replaced under her.
		npc.forgetWhereSheWas();
		return List.of("Forgot this world's copy of \"" + id + "\".",
			"She is on the built-in one now.");
	}

	/**
	 * Starts or stops writing the fight down.
	 *
	 * <h2>Why a file and not this readout</h2>
	 *
	 * Because the answer is never in one tick. Five rounds of fixing the fighting
	 * were five guesses at which of a dozen numbers matched a sentence about how it
	 * looked, and every one of them happened to be about something real — which is
	 * luck, not a method. A fight is twenty ticks a second across two bodies; it
	 * has to be written down before it can be understood.
	 *
	 * It goes beside the world rather than into chat, because chat cannot hold four
	 * hundred lines and because a file can be read by somebody who was not there.
	 */
	private static List<String> tape(NpcEntity npc) {
		if (com.mopicmp.npcstudio.foe.Tape.rolling()) {
			return List.of(com.mopicmp.npcstudio.foe.Tape.stop());
		}
		com.mopicmp.npcstudio.foe.Tape.start(
			net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir(), npc.tickCount);
		return List.of("recording — every character writes a line a tick",
			"press again to stop, or it stops itself after "
				+ com.mopicmp.npcstudio.foe.Tape.LONGEST + " ticks");
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
