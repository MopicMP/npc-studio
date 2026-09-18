package com.mopicmp.npcstudio.dialogue.runtime;

import java.util.List;
import java.util.Map;

import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.dialogue.Condition;
import com.mopicmp.npcstudio.dialogue.Dialogue;
import com.mopicmp.npcstudio.dialogue.DialogueEngine;
import com.mopicmp.npcstudio.dialogue.DialogueState;
import com.mopicmp.npcstudio.dialogue.Node;
import com.mopicmp.npcstudio.dialogue.Effect;
import com.mopicmp.npcstudio.dialogue.Presentation;
import com.mopicmp.npcstudio.entity.NpcEntity;
import com.mopicmp.npcstudio.net.ShowChoicePayload;
import com.mopicmp.npcstudio.net.ShowLinePayload;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Ties the engine to the world: who is talking to whom, and what happens next.
 *
 * The engine itself knows nothing about players or entities — it takes a
 * bookmark and returns a new one. Everything that has to touch Minecraft lives
 * here, which is why the engine can be tested without a game and this class can
 * stay small.
 *
 * State is read and written through {@link DialogueSaveData}, which keeps the
 * three kinds apart: where the player is, what the player has done, and what
 * the world is like. Only the first is forgotten when a conversation ends.
 */
public final class DialogueRuntime {

	private DialogueRuntime() { }

	// ------------------------------------------------- conversations that wait

	/**
	 * A conversation standing at a node that has not finished happening.
	 *
	 * <h2>Why this had to exist</h2>
	 *
	 * A conversation used to be refused the moment its graph waited, and the reason
	 * given was that nothing would come back and wake it. That was true and it is
	 * not any more: the character is ticked twenty times a second, and the thing she
	 * is waiting for is usually her own legs.
	 *
	 * It stopped being an edge case the day a route became a node. "Say a line, walk
	 * to the gate, say another" is the plainest thing anybody would write with one,
	 * and it produced a red message and a conversation thrown away — while the order
	 * to walk, which had already left, went through. So she walked, and the next
	 * click started the graph from the top and sent her round again.
	 *
	 * @param ticks how long it has stood here, so that a graph which really is stuck
	 *              is still caught rather than leaving somebody watching nothing
	 */
	private record Parked(int npc, int ticks, String zone) { }

	/**
	 * One conversation, named by who is having it.
	 *
	 * <h2>Why the player alone was not a key</h2>
	 *
	 * Because a player can now be in two conversations. A document may be a place as
	 * well as a character — walk into the room and it speaks, click the guard and she
	 * speaks — and the two run beside each other through the same nodes.
	 *
	 * Keyed by player and subject, which is exactly how the bookmarks in the save have
	 * always been keyed. That was written for twenty guards sharing one document; it
	 * turns out to be the same shape a place needs, and the two now agree.
	 */
	private record Thread(java.util.UUID player, java.util.UUID subject) { }

	private static final Map<Thread, Parked> PARKED = new java.util.HashMap<>();

	/*
	 * There was a second map here, holding "this place has a conversation running".
	 *
	 * It is gone, and what it cost is worth writing down, because it is the same
	 * mistake as the one above it: a fact kept in two places, one authoritative and
	 * one a copy somebody has to remember to update.
	 *
	 * The copy was emptied on three paths only — the graph reaching its ending, the
	 * minute of patience running out, and the player disconnecting. A zone
	 * conversation that stopped any other way left an entry behind for ever, and the
	 * entry meant "already running", so the trigger never fired again. Reported
	 * exactly as it behaves: the blocks work once per visit to the world, restarting
	 * the dialogue does not help, and rejoining fixes it — because the disconnect was
	 * the only sweep that ever ran.
	 *
	 * What is authoritative is the bookmark. "Running" is "there is a bookmark": it is
	 * saved, it is cleared by everything that ends a conversation, and it cannot drift
	 * from itself. Ticking is served by {@link Parked}, which already had to be right
	 * for the conversation to be stepped at all.
	 */

	/**
	 * Which once-a-visit triggers this player has already set off.
	 *
	 * <h2>This is the deleted map, and it is not the deleted mistake</h2>
	 *
	 * It looks like what was taken out above and it is worth saying exactly how it
	 * differs, because the resemblance is the whole risk.
	 *
	 * The old map answered "is this conversation running", which the bookmark already
	 * answered — two copies of one fact, and the copy was the one that went stale. This
	 * answers "has this trigger fired since the player joined", which nothing else knows
	 * and nothing else can be derived from. It is not a cache of anything.
	 *
	 * And it fails the other way. A stale entry in the old map silently disabled a
	 * trigger nobody had asked to disable; a stale entry here disables a trigger whose
	 * author wrote down that it should fire once — which is what they asked for. Being
	 * wrong in the direction of the setting is the difference between a bug and a
	 * feature that could be tidier.
	 *
	 * Emptied when the player leaves, along with everything else of theirs. That is the
	 * whole of "rejoin to arm it again", and it is one line in {@link #forgetThreads}
	 * rather than a sweep of its own that could be forgotten.
	 */
	private static final java.util.Set<Thread> FIRED_ONCE = new java.util.HashSet<>();

	/**
	 * What a rule fired about: the block somebody just used.
	 *
	 * <h2>Why it lives here and not on the step</h2>
	 *
	 * Because a rule may say a line, and a line waits. So the block has to still be
	 * findable when the second step comes, which means it belongs to the thread rather
	 * than to the moment — and it goes away when the thread does, with everything else
	 * of that player's.
	 *
	 * This is what {@link com.mopicmp.npcstudio.dialogue.Mark#IT} resolves through, and
	 * it is the piece the whole plan was missing: every part of the mechanism had been
	 * designed and costed, and "if what they clicked is alight" could not be spelled.
	 */
	private static final Map<Thread, net.minecraft.core.BlockPos> ABOUT =
		new java.util.HashMap<>();

	/**
	 * What a conversation last told a character to look at.
	 *
	 * <h2>Why this is remembered at all</h2>
	 *
	 * A gaze is a hold, not an act — told to look at you she goes on looking at you,
	 * which is the whole point of it and is right while the conversation is running.
	 * It was still right after the conversation ended, and that was the fault: she
	 * stood there watching whoever was nearest for the rest of the session.
	 *
	 * It reads as a character frozen, but it is worse than it looks, because the
	 * gaze <em>overrules</em> the watching reflex — {@code gaze} runs after
	 * {@code keepWatch} and wins. So the character does not merely keep staring; she
	 * has lost her own head for good, with nothing anywhere saying why. That is the
	 * same shape as a wall left standing: invisible, permanent, and silent.
	 *
	 * <h2>Why it is remembered rather than simply cleared</h2>
	 *
	 * Because a behaviour graph sets the same field, through the same verb, and a
	 * conversation ending has no business letting go of a stare that a fight put
	 * there. So what is let go of is what this conversation ordered and nothing else
	 * — the same compare-before-clearing {@link Walls} does with its blocks.
	 */
	private record Staring(int npc, String mark) { }

	private static final Map<java.util.UUID, Staring> STARING = new java.util.HashMap<>();

	/**
	 * Who this conversation has asked to stand still.
	 *
	 * The same shape as the stare above and for the same reason: it is a hold, so
	 * somebody has to be responsible for letting go of it. Unlike the stare, letting
	 * go is not merely tidy — a hold left standing is a person who cannot move, which
	 * is the worst thing anything here can leave behind.
	 */
	private static final java.util.Set<java.util.UUID> HELD = new java.util.HashSet<>();

	/**
	 * How long a conversation may stand still before it is called broken.
	 *
	 * A minute. Long enough for any walk anybody would put in front of a player —
	 * a route across a courtyard is ten seconds and a long one is thirty — and short
	 * enough that a graph waiting on something that will never happen is still found
	 * by the person writing it rather than by a player.
	 *
	 * The other half of the bound is not a number: a parked conversation is dropped
	 * the moment the player walks out of range, which is the same distance the line
	 * on screen already uses. Somebody who leaves is not waiting.
	 */
	public static final int PATIENCE = 20 * 60;

	/**
	 * As far as the player may wander while a conversation is mid-wait.
	 *
	 * <h2>Why this is asked only of a character standing still</h2>
	 *
	 * Because it cannot tell who walked. It was written for "the player got bored and
	 * left", which is a fair thing to notice, and it fired on "the character set off
	 * and the player is following her" — which is not a conversation being abandoned,
	 * it is the scene happening.
	 *
	 * What that cost is the whole of a report: a character offers to lead the way,
	 * walks off, and the distance passes twelve blocks. The conversation is taken out
	 * of the list of the ones being ticked, and nothing ever steps it again — so the
	 * arrival is never collected, the node after the walk is never entered, the line
	 * never leaves the screen, and even the minute of patience never runs out, because
	 * the patience is counted inside the very loop the conversation was just removed
	 * from. It does not fail. It stops, silently, for ever.
	 */
	private static final double WATCHING = 12.0;

	/**
	 * Whether this conversation has been left behind, as against merely stretched.
	 *
	 * A character carrying out an order is the answer to "who walked away": she did,
	 * because the graph said to. Distance means nothing then, and the bound that does
	 * the work is the patience below — which does not tick while she is going
	 * somewhere either, for the same reason.
	 */
	private static boolean walkedOut(NpcEntity npc, ServerPlayer player) {
		if (!npc.walkingTo().isEmpty()) return false;
		return npc.distanceToSqr(player) > WATCHING * WATCHING;
	}

	/**
	 * One tick of every conversation that is standing still.
	 *
	 * Free when there are none, which is nearly always — the map is empty unless
	 * somebody is mid-conversation with a graph that waits.
	 */
	public static void tick(net.minecraft.server.MinecraftServer server) {
		ground(server);
		if (PARKED.isEmpty()) return;

		for (var entry : List.copyOf(PARKED.entrySet())) {
			Thread who = entry.getKey();
			ServerPlayer player = server.getPlayerList().getPlayer(who.player());
			if (player == null) {
				PARKED.remove(who);
				continue;
			}
			// A conversation a place began has nobody to look at, so the questions
			// below about a character are not asked of it at all.
			String zone = entry.getValue().zone();
			if (!zone.isEmpty()) {
				stepZone(player, zone, new DialogueEngine.Input.Begin());
				continue;
			}
			// Walked out of sight, or the character is gone: the conversation is over
			// in every sense that matters to a block. The bookmark stays — coming back
			// picks it up — but the corridor does not stay sealed while nobody is in it.
			if (!(player.level().getEntity(entry.getValue().npc()) instanceof NpcEntity standing)
					|| !standing.isAlive()
					|| walkedOut(standing, player)) {
				sweep(player);
			}
			// The bookmark is left where it is in every one of these. Walking away
			// from a conversation is not ending it — coming back and clicking picks
			// it up exactly where it was, which is what a bookmark is for.
			if (!(player.level().getEntity(entry.getValue().npc()) instanceof NpcEntity npc)
					|| !npc.isAlive()
					|| walkedOut(npc, player)) {
				PARKED.remove(who);
				continue;
			}

			Dialogue dialogue = DialogueRegistry.get(npc.dialogueId()).orElse(null);
			DialogueSaveData store = DialogueSaveData.of(player.level());
			Cast cast = new Cast.Character(npc);
			DialogueState state = dialogue == null ? null : load(store, player, cast, dialogue);
			if (state == null) {
				PARKED.remove(who);
				continue;
			}
			step(player, cast, dialogue, state, new DialogueEngine.Input.Begin());
		}
	}

	// ------------------------------------------------- conversations a place begins

	/**
	 * The name a running zone conversation goes by: this document, this way in.
	 *
	 * Both, because one document may watch several places and each is its own thread
	 * with its own bookmark. A key of the document alone would make two rooms share a
	 * conversation and read as one of them being ignored.
	 */
	/** The name of the place having this conversation, or empty when it is a person. */
	private static String zoneOf(Cast cast) {
		return cast instanceof Cast.Nobody nobody ? nobody.where() : "";
	}

	private static String zoneName(Dialogue dialogue, String segment) {
		return dialogue.id() + "/" + segment;
	}

	/**
	 * Checked four times a second, which is often enough to catch somebody walking.
	 *
	 * A player crossing a boundary at a sprint covers about two blocks in that time, so
	 * a box of any size anybody would draw cannot be walked through unnoticed. Asked
	 * more often it would be a search of every document for every player every tick,
	 * for a question whose answer changes about once an hour.
	 */
	private static final int GROUND_EVERY = 5;

	/**
	 * Whether anybody is standing anywhere that has something to say.
	 *
	 * <h2>What makes this free when nothing uses it</h2>
	 *
	 * Nearly every document answers no to {@link Dialogue#watchesGround}, which is a
	 * field read, so the loop below usually costs one comparison per document and
	 * stops. A map with no triggers in it never reaches the part that measures boxes.
	 */
	private static void ground(net.minecraft.server.MinecraftServer server) {
		if (server.getTickCount() % GROUND_EVERY != 0) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) settle(player);

		// Everything, because this beat belongs to the server and not to anybody in
		// particular. Which documents answer for which person is decided below, where
		// there is a person to decide it against.
		for (String name : DialogueRegistry.names()) {
			Dialogue dialogue = DialogueRegistry.get(name).orElse(null);
			if (dialogue == null || !dialogue.watchesGround()) continue;
			for (var trigger : dialogue.triggers()) {
				switch (trigger.cause()) {
					// A box is measured against each player, because standing in one is
					// a fact about a person.
					case com.mopicmp.npcstudio.dialogue.Trigger.Cause.Inside _ -> {
						for (ServerPlayer player : server.getPlayerList().getPlayers()) {
							// And only for the people this document answers for. Without
							// it, a guest walking into a lesson would drag the whole
							// map's boxes in with them — which looks like the lesson
							// behaving strangely for reasons written down somewhere they
							// would never think to look.
							if (!dialogue.visibleFrom(com.mopicmp.npcstudio.map.Whereabouts.of(player))) continue;
							maybeBegin(player, dialogue, trigger);
						}
					}
					// A block is one thing in the world, so it is looked at once — not
					// once per player. Only then is it asked who it counted for.
					// A block is one thing and has no asker, so there is nobody to
					// decide a place against. A document living in a location names
					// places that do not resolve yet — they are the map's coordinates,
					// and a copy is somewhere else — so it is left alone rather than
					// quietly watching the wrong ground. See docs/locations.md.
					case com.mopicmp.npcstudio.dialogue.Trigger.Cause.Became(
							String place, String block) -> {
						if (dialogue.within().isEmpty()) {
							blockChanged(server, dialogue, trigger, place, block);
						}
					}
					// Answering a click is not polled at all; the game says when. Nor is
					// an ability: the client says when, because that is where a jump
					// happens.
					case com.mopicmp.npcstudio.dialogue.Trigger.Cause.Used _,
							com.mopicmp.npcstudio.dialogue.Trigger.Cause.UsedAny _,
							com.mopicmp.npcstudio.dialogue.Trigger.Cause.Knack _,
							com.mopicmp.npcstudio.dialogue.Trigger.Cause.UsedItem _ -> { }
				}
			}
		}
	}

	/**
	 * What a block at a marked place was the last time anybody looked.
	 *
	 * <h2>Why this is remembered at all</h2>
	 *
	 * Because "light the fire three times" counts changes, not ticks. Watched as a
	 * state, a lit fire answers yes four times a second for as long as it burns; the
	 * only way to mean what the words mean is to notice the moment the answer turned.
	 *
	 * <h2>Why it does not outlive the server</h2>
	 *
	 * Nothing is written to the save, so a fire already alight when the world opens is
	 * not counted as having just been lit — the first look establishes what is, and
	 * only what happens afterwards is a change. That is right as well as cheap: a latch
	 * that survived a restart would need a way to clear it, and the reason it wanted
	 * clearing would be invisible.
	 */
	private static final java.util.Map<String, Boolean> BLOCK_WAS =
		new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * One block trigger: has it just become what the graph is watching for, and if so,
	 * for whom.
	 *
	 * <h2>Why a condition decides who</h2>
	 *
	 * Because the block does not know. Minecraft keeps no record of who last touched
	 * one, so a change in the world is an event with nobody's name on it — and taking
	 * whoever stands nearest would be a rule the author never wrote, invisible in the
	 * graph, and wrong exactly when two people are in the room together.
	 *
	 * So it is asked in the language that already exists. "Whoever is standing in the
	 * box round the fire" is one word of it. Nobody answers, nobody is counted, and
	 * that is a scene that behaves the same whether one person is playing or six.
	 */
	private static void blockChanged(net.minecraft.server.MinecraftServer server,
			Dialogue dialogue, com.mopicmp.npcstudio.dialogue.Trigger trigger,
			String place, String block) {
		if (place.isEmpty()) return;

		net.minecraft.server.level.ServerLevel level = server.overworld();
		var at = com.mopicmp.npcstudio.map.WorldSpots.of(level).at(place);
		if (at == null) return;

		boolean now = BlockLook.matches(level, at, block);
		// Named by the way in as well as by the place, because a place may now carry
		// two rules — which is the whole reason the map became a list — and one entry
		// for both would have each of them cancelling the other's memory.
		String seen = dialogue.id() + "/" + place + "/" + trigger.wayIn();
		Boolean before = BLOCK_WAS.put(seen, now);
		// Unchanged, or looked at for the first time. The first look is not a change:
		// the world was already like that when we arrived.
		if (before == null || before == now || !now) return;

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			DialogueSaveData store = DialogueSaveData.of(player.level());
			Cast cast = new Cast.Nobody(zoneName(dialogue, trigger.wayIn()),
				store.zoneVars(zoneName(dialogue, trigger.wayIn())));
			DialogueState asking = fresh(store, player, cast, dialogue);
			if (!trigger.who().test(asking, worldFor(player, cast, dialogue))) continue;
			maybeBegin(player, dialogue, trigger);
		}
	}

	/**
	 * One box, one player: begin the thread it names, or step the one already running.
	 *
	 * <h2>Why leaving the box does not end it</h2>
	 *
	 * Because a scene that starts at a threshold is not a scene that has to be watched
	 * from the threshold. "Are you finished with the tutorial?" is answered where the
	 * player is standing when they answer it, which may well be a step back out of the
	 * doorway. What ends the thread is the graph ending it.
	 */
	/**
	 * Somebody pressed something a graph has shown, and whichever graph was waiting moves on.
	 *
	 * <h2>Why the press goes to the graphs rather than starting one</h2>
	 *
	 * Because a menu is a graph that is already running and standing still on purpose. The
	 * six labels went up because something ran; the press is that same something being told
	 * what was chosen. Started afresh instead, a press would be a document per button, each
	 * of them having to work out for itself where the menu had got to.
	 *
	 * <h2>Why every one of the presser's threads is asked</h2>
	 *
	 * Because a person may have two of them running — a lesson laid out around them and a
	 * conversation in the middle of it — and which of the two hung the thing they pressed is
	 * not something the click knows. The ones that are not waiting for that name are not
	 * disturbed at all: this asks first and steps only what answers.
	 *
	 * <h2>Why a press nothing is waiting for is not swallowed</h2>
	 *
	 * A hologram is often only a label. Answering "yes, that was mine" to a click nobody was
	 * waiting for would stop the block behind it being used, which is exactly the complaint
	 * that got interaction boxes thrown out — see {@link com.mopicmp.npcstudio.show.Aim}.
	 *
	 * @param reach how far the press carries, which the caller knows better than this does:
	 *              as far as the block being used, or as far as an empty hand goes
	 * @return true when a graph took it, so the click can be counted as answered
	 */
	public static boolean pressed(ServerPlayer player, double reach) {
		if (!(player.level() instanceof net.minecraft.server.level.ServerLevel level)) return false;
		if (PARKED.isEmpty()) return false;

		String place = com.mopicmp.npcstudio.map.Whereabouts.of(player);
		var eye = player.getEyePosition();
		var look = player.getLookAngle();
		var near = com.mopicmp.npcstudio.show.Showing.near(level, place, eye, reach);
		if (near.isEmpty()) return false;
		String name = com.mopicmp.npcstudio.show.Aim.at(near, eye.x, eye.y, eye.z,
			look.x, look.y, look.z, reach);
		if (name.isEmpty()) return false;

		boolean answered = false;
		for (var entry : List.copyOf(PARKED.entrySet())) {
			Thread thread = entry.getKey();
			if (!thread.player().equals(player.getUUID())) continue;
			String zone = entry.getValue().zone();
			// A place's thread and a character's are stepped by different doors, and both
			// may be standing on a wait. Which door is the same question the beat asks.
			if (!zone.isEmpty()) {
				if (!waitingFor(player, zone, name)) continue;
				stepZone(player, zone, new DialogueEngine.Input.Pressed(name));
				answered = true;
				continue;
			}
			if (!(player.level().getEntity(entry.getValue().npc()) instanceof NpcEntity npc)) continue;
			Dialogue dialogue = DialogueRegistry.get(npc.dialogueId()).orElse(null);
			if (dialogue == null) continue;
			Cast cast = new Cast.Character(npc);
			DialogueState state = load(DialogueSaveData.of(player.level()), player, cast, dialogue);
			if (state == null || !waits(dialogue, state, name)) continue;
			step(player, cast, dialogue, state, new DialogueEngine.Input.Pressed(name));
			answered = true;
		}
		return answered;
	}

	/** Whether a place's thread is standing on a wait for this name. */
	private static boolean waitingFor(ServerPlayer player, String zone, String name) {
		int slash = zone.lastIndexOf('/');
		if (slash < 0) return false;
		Dialogue dialogue = DialogueRegistry.get(zone.substring(0, slash)).orElse(null);
		if (dialogue == null) return false;
		DialogueSaveData store = DialogueSaveData.of(player.level());
		Cast cast = new Cast.Nobody(zone, store.zoneVars(zone));
		DialogueState state = load(store, player, cast, dialogue);
		return state != null && waits(dialogue, state, name);
	}

	/** Whether the node a thread is standing on is waiting for this name in particular. */
	private static boolean waits(Dialogue dialogue, DialogueState state, String name) {
		return dialogue.node(state.currentNode()) instanceof Node.Pressed waiting
			&& waiting.next(name) != null;
	}

	private static void maybeBegin(ServerPlayer player, Dialogue dialogue,
			com.mopicmp.npcstudio.dialogue.Trigger trigger) {
		maybeBegin(player, dialogue, trigger, null);
	}

	private static void maybeBegin(ServerPlayer player, Dialogue dialogue,
			com.mopicmp.npcstudio.dialogue.Trigger trigger, Cast subject) {
		String segment = trigger.wayIn();
		String zone = zoneName(dialogue, segment);
		Thread thread = new Thread(player.getUUID(), Cast.Nobody.named(zone));

		String at = dialogue.segments().get(segment);
		if (at == null) return;
		// A block trigger has already asked its own two questions — did it change, and
		// who did it count for — before ever getting here. Where the player is standing
		// is not one of them: they may well be watching the fire from across the room.
		if (trigger.cause() instanceof com.mopicmp.npcstudio.dialogue.Trigger.Cause.Inside(
				String area)) {
			com.mopicmp.npcstudio.dialogue.Area box = dialogue.area(area);
			if (box == null) return;
			// The origin, because a zone has nobody to hang steps off. See Cast.Nobody.
			if (!box.holds(player.getX(), player.getY(), player.getZ(), 0, 0, 0,
					com.mopicmp.npcstudio.dialogue.Route.Facing.SOUTH)) {
				return;
			}
		}
		// Waiting rather than interrupting. A trigger that cut across a character
		// mid-sentence would make every zone a hazard to write near, and the thread is
		// not lost by waiting — the player is standing in the box and will be asked
		// again in a quarter of a second.
		//
		// A rule is the exception, and it has to be: somebody who clicked a fire has
		// clicked the fire, and a rule that silently did nothing because a conversation
		// happened to be on screen would be a rule that works except when it matters.
		if (!trigger.isRule() && DialogueDisplay.isShowing(player)) return;

		DialogueSaveData store = DialogueSaveData.of(player.level());
		// Whoever the caller said, or nobody in particular. A rule about a thing hands
		// its own, because the stack is what its memory belongs to.
		Cast cast = subject != null ? subject : new Cast.Nobody(zone, store.zoneVars(zone));
		// Already under way? The bookmark says so, and nothing else is asked. One that
		// finished, or was reset, or whose document was edited under it has no bookmark
		// and begins afresh.
		//
		// That is the whole of the trigger fix; see the note where the second map used
		// to be. The question used to be answered from a copy that three paths ever
		// cleared, so a scene stopping any other way latched the trigger shut until the
		// player rejoined the world.
		// A rule keeps no bookmark, so there is never one to resume: it runs now and it
		// is over. Left to the ordinary path, the second click would have resumed the
		// first click rather than answering.
		if (trigger.isRule()) {
			store.clearBookmark(player.getUUID(), cast.id());
			step(player, cast, dialogue, fresh(store, player, cast, dialogue).at(at),
				new DialogueEngine.Input.Begin());
			return;
		}

		DialogueState was = load(store, player, cast, dialogue);
		if (was == null) {
			// A fresh start, and the only thing the once-a-visit latch counts. Resuming
			// below is not the trigger firing again — it is the same scene carrying on —
			// so a scene somebody pressed escape during is not lost until they rejoin.
			if (trigger.onceOnly() && !FIRED_ONCE.add(thread)) return;
			step(player, cast, dialogue, fresh(store, player, cast, dialogue).at(at),
				new DialogueEngine.Input.Begin());
			return;
		}
		// Under way and being ticked: leave it to its own thread.
		if (PARKED.containsKey(thread)) return;
		// Under way, nothing on the screen — the check above this one has already said
		// so — and nothing ticking it. That is a scene somebody stepped away from, and
		// walking back into the room is the plainest way there is of saying "go on".
		//
		// Without this, escape during a scene a doorway began would be the end of it for
		// good: the bookmark blocks the trigger, and a question is not parked, because
		// it is waiting for a person rather than for the world. Nothing anywhere would
		// have brought it back.
		step(player, cast, dialogue, was, new DialogueEngine.Input.Begin());
	}

	/**
	 * The player pressed use with nobody in front of them, and a place is talking.
	 *
	 * <h2>Why this exists at all</h2>
	 *
	 * A line is moved on by pointing at whoever said it. A place cannot be pointed at,
	 * so a conversation a doorway began had a line on the screen and no gesture
	 * anywhere that would take it further: it stood there until the line timed out and
	 * the thread never moved again. Asked in exactly those words — what do you do when
	 * there is a line and no character.
	 *
	 * <h2>Why the screen decides which conversation this is</h2>
	 *
	 * Because the click carries no target. "Carry on" means "carry on with the thing
	 * being said to me", and the only thing that knows what that is, is what is on the
	 * screen — see {@link DialogueDisplay#zoneOn}. A guess from the parked list would
	 * be wrong in the one case that matters: a line is not parked, precisely because
	 * it waits for a person rather than for the world.
	 */
	public static void speakOn(ServerPlayer player) {
		String zone = DialogueDisplay.zoneOn(player);
		if (zone.isEmpty()) return;
		stepZone(player, zone, new DialogueEngine.Input.Advance());
	}

	/**
	 * The player answered a question nobody asked them.
	 *
	 * <h2>Why this was missing and how it looked</h2>
	 *
	 * The same hole as {@link #speakOn}, one node along, and it was left behind when
	 * that one was filled. An answer travels with the character who asked, because an
	 * answer arriving late must not land on a different conversation — sound
	 * reasoning, and it has no answer at all for a question a doorway asked. The
	 * packet carried minus one, the server looked for an entity with that id, found
	 * none, and dropped it.
	 *
	 * From the outside: the question appears, the screen dims, clicking an answer does
	 * nothing whatever, and eventually the screen goes away by itself. Reported in
	 * those words.
	 *
	 * The late-arrival worry is answered the same way here as it is for carrying a
	 * line on: what may be answered is whatever the server has on this player's
	 * screen, and if that has moved on the input is refused by the engine — which is
	 * a stronger guarantee than an id, not a weaker one.
	 */
	public static void answerHere(ServerPlayer player, int option) {
		String zone = DialogueDisplay.zoneOn(player);
		if (zone.isEmpty()) return;
		stepZone(player, zone, new DialogueEngine.Input.Pick(option));
	}

	/** One step of a conversation a place began: a tick of it, or a person moving it on. */
	private static void stepZone(ServerPlayer player, String zone, DialogueEngine.Input input) {
		String id = zone.substring(0, zone.lastIndexOf('/'));
		Dialogue dialogue = DialogueRegistry.get(id).orElse(null);
		Thread thread = new Thread(player.getUUID(), Cast.Nobody.named(zone));
		if (dialogue == null) {
			PARKED.remove(thread);
			return;
		}
		DialogueSaveData store = DialogueSaveData.of(player.level());
		Cast cast = new Cast.Nobody(zone, store.zoneVars(zone));
		DialogueState state = load(store, player, cast, dialogue);
		if (state == null) {
			PARKED.remove(thread);
			return;
		}
		// An input that does not match what the scene is standing on is not worth
		// throwing over: a press landing a tick after the scene moved on is an ordinary
		// thing, and the engine refuses it loudly — a red message and a conversation
		// thrown away. Asked to begin instead, which is what a click on a character
		// does in the same situation and which re-shows whatever is really there.
		Node standing = dialogue.node(state.currentNode());
		boolean fits = switch (input) {
			case DialogueEngine.Input.Advance _ -> standing instanceof Node.Line;
			case DialogueEngine.Input.Pick _ -> standing instanceof Node.Choice;
			// Asked again here although the press has already been aimed at this thread,
			// because a tick may have passed between the two and the scene may have moved.
			case DialogueEngine.Input.Pressed _ -> standing instanceof Node.Pressed;
			case DialogueEngine.Input.Begin _ -> true;
		};
		if (!fits) input = new DialogueEngine.Input.Begin();
		step(player, cast, dialogue, state, input);
	}

	/** A player who left takes every thread of theirs with them. */
	private static void forgetThreads(java.util.UUID player) {
		PARKED.keySet().removeIf(thread -> thread.player().equals(player));
		// And the once-a-visit latches, which is what makes the visit a visit.
		FIRED_ONCE.removeIf(thread -> thread.player().equals(player));
		ABOUT.keySet().removeIf(thread -> thread.player().equals(player));
		KNACKS.remove(player);
		GAUGES.remove(player);
		// Nothing is sent: they have gone. The record goes so that the next player to
		// hold this id is not thought to have somebody else's picture up.
		SHOWING_PORTRAIT.remove(player);
	}

	/**
	 * A player who left takes their parked conversation with them — and its walls.
	 *
	 * The walls first, while the player still has a level to look them up in. After
	 * a disconnect there is no way back to the blocks except the sweep at the next
	 * start-up, which is a corridor sealed until somebody restarts the server.
	 */
	public static void forget(ServerPlayer player) {
		sweep(player);
		forgetThreads(player.getUUID());
	}

	/**
	 * A player who has actually gone, as opposed to one starting a scene over.
	 *
	 * The difference is one line, and it is worth the second method. Watching branches
	 * decide things is something a person switched on and is still expecting; clearing
	 * it inside the general forgetting meant that pressing "play it again" turned the
	 * watching off while the editor went on showing the switch as lit — a debugging
	 * tool silently stopping is the one behaviour a debugging tool must not have.
	 */
	public static void left(ServerPlayer player) {
		forget(player);
		LOOKING_ON.remove(player.getUUID());
	}

	/**
	 * A click on an NPC: start the conversation, or take one more step through it.
	 *
	 * There is no separate "begin" and "continue" from the outside, which is the
	 * point. The bookmark decides: no bookmark means start, a bookmark on a line
	 * means move on, a bookmark on a choice means show that choice again — which
	 * is what makes Esc a pause rather than a cancel.
	 */
	public static void talkTo(ServerPlayer player, NpcEntity npc) {
		Dialogue dialogue = DialogueRegistry.get(npc.dialogueId()).orElse(null);
		if (dialogue == null) {
			player.sendSystemMessage(Component.literal("This NPC has no dialogue called \""
				+ npc.dialogueId() + "\".").withStyle(ChatFormatting.RED));
			return;
		}
		// A document of rules is carried by nobody. Given to a character it would have
		// no beginning to run, so clicking her would do nothing at all — and "nothing
		// happens" is the symptom this mod works hardest to never produce silently.
		if (dialogue.isRules()) {
			player.sendSystemMessage(Component.literal("\"" + npc.dialogueId()
				+ "\" is a set of rules, not a conversation; it runs through its triggers "
				+ "and cannot be given to a character.").withStyle(ChatFormatting.RED));
			return;
		}

		DialogueSaveData store = DialogueSaveData.of(player.level());
		Cast cast = new Cast.Character(npc);
		DialogueState state = load(store, player, cast, dialogue);

		DialogueEngine.Input input;
		if (state == null) {
			state = fresh(store, player, cast, dialogue);
			input = new DialogueEngine.Input.Begin();
		} else if (dialogue.node(state.currentNode()) instanceof Node.Line
				&& DialogueDisplay.isShowing(player, npc)) {
			// Only move on if the player can actually see the line they are moving
			// on from. If the bar timed out or they walked away, the click brings
			// the same line back instead — otherwise the conversation would advance
			// past something they never read.
			input = new DialogueEngine.Input.Advance();
		} else {
			// Sitting on a choice, or picking a dropped thread back up.
			input = new DialogueEngine.Input.Begin();
		}

		step(player, cast, dialogue, state, input);
	}

	/** The player answered a question. */
	public static void choose(ServerPlayer player, NpcEntity npc, int option) {
		Dialogue dialogue = DialogueRegistry.get(npc.dialogueId()).orElse(null);
		if (dialogue == null) return;

		Cast cast = new Cast.Character(npc);
		DialogueState state = load(DialogueSaveData.of(player.level()), player, cast, dialogue);
		if (state == null) return;

		step(player, cast, dialogue, state, new DialogueEngine.Input.Pick(option));
	}

	/**
	 * Rebuilds where the player was, or reports there is nowhere.
	 *
	 * The three parts come from three places, which is the point: the position
	 * from the bookmark, the progress from the player, the world from the world.
	 * Only the position disappears when a conversation ends.
	 *
	 * Declared types come from the dialogue as it is now rather than from
	 * anything saved. A writer may have changed a variable's type since, and
	 * honouring the old one would leave the state disagreeing with its own script.
	 */
	private static DialogueState load(DialogueSaveData store, ServerPlayer player,
			Cast cast, Dialogue dialogue) {
		String node = store.bookmark(player.getUUID(), cast.id());
		if (node == null) return null;
		return new DialogueState(node,
			store.varsOf(player.getUUID()),
			store.worldVars(),
			cast.memory(),
			store.visitedBy(player.getUUID()),
			dialogue.variableTypes());
	}

	/** A fresh conversation still starts with everything the player already knows. */
	private static DialogueState fresh(DialogueSaveData store, ServerPlayer player,
			Cast cast, Dialogue dialogue) {
		return new DialogueState(dialogue.start(),
			store.varsOf(player.getUUID()),
			store.worldVars(),
			cast.memory(),
			store.visitedBy(player.getUUID()),
			dialogue.variableTypes());
	}

	/**
	 * Who has asked to be told what the conversations they walk into are deciding.
	 *
	 * Per player and off by default: it is a stream of text about every step, and on a
	 * server with people playing it would be traffic and noise for everybody so that one
	 * person could read one branch. Emptied when they leave, with everything else of
	 * theirs.
	 */
	private static final java.util.Set<java.util.UUID> LOOKING_ON = new java.util.HashSet<>();

	public static void watch(ServerPlayer player, boolean on) {
		if (on) LOOKING_ON.add(player.getUUID());
		else LOOKING_ON.remove(player.getUUID());
	}

	/**
	 * Puts every standing property where its condition says it should be, for one player.
	 *
	 * <h2>Why this is asked on a beat rather than only when something happens</h2>
	 *
	 * Because the answer can change with nothing happening. A condition may read a
	 * variable — which does go through the engine — but it may equally read a box the
	 * player is standing in or what is in their hand, and nothing tells us about those.
	 * So it is asked on the same beat the ground triggers use.
	 *
	 * The beat is a number nobody has measured. Five ticks is what the ground uses and
	 * it is here because it is already there, not because it is known to be right: a
	 * quarter of a second between picking up the feather and being able to jump is
	 * noticeable. See docs/player-and-things.md, where the standing complaint is that
	 * every cost in this plan was guessed at.
	 *
	 * <h2>Why it is also asked straight after a step</h2>
	 *
	 * Because the case that is not on a beat is the one people watch: a conversation
	 * hands you the feather, and the ability should be there before the line has faded,
	 * not up to a beat later.
	 */
	public static void settle(ServerPlayer player) {
		java.util.List<Traits.Wanted> wanted = new java.util.ArrayList<>();
		java.util.List<com.mopicmp.npcstudio.net.GaugePayloads.Shown> showing =
			new java.util.ArrayList<>();
		for (String name : DialogueRegistry.names(com.mopicmp.npcstudio.map.Whereabouts.of(player))) {
			Dialogue dialogue = DialogueRegistry.get(name).orElse(null);
			if (dialogue == null) continue;
			if (dialogue.standing().isEmpty() && dialogue.gauges().isEmpty()) continue;

			DialogueSaveData store = DialogueSaveData.of(player.level());
			// Nobody in particular, because a standing rule is about the player and about
			// the world, and there is no character in the room to measure anything from.
			Cast cast = new Cast.Nobody(dialogue.id(), store.zoneVars(dialogue.id()));
			DialogueState asking = fresh(store, player, cast, dialogue);
			var world = worldFor(player, cast, dialogue);
			for (var rule : dialogue.standing()) {
				wanted.add(new Traits.Wanted(dialogue.id(), rule,
					rule.when().test(asking, world)));
			}
			// And what the player is shown of it. Worked out here rather than on the
			// client because the client has no idea what a document is, and there is no
			// reason to teach it — see GaugePayloads.
			for (var gauge : dialogue.gauges()) {
				if (showing.size() >= com.mopicmp.npcstudio.net.GaugePayloads.MOST) break;
				if (!gauge.when().test(asking, world)) continue;
				showing.add(drawn(gauge, asking));
			}
		}
		// Always, even when nothing wants anything: the sweep is how a rule deleted from
		// a document stops leaving its property on everybody who ever had it.
		java.util.List<String> knacks = Traits.settle(player, wanted);

		// Only when it changed. This runs on a beat for every player, and a packet every
		// beat to everybody saying the same thing as last time is the kind of traffic
		// nobody notices until a server has thirty people on it.
		if (!knacks.equals(KNACKS.get(player.getUUID()))) {
			KNACKS.put(player.getUUID(), knacks);
			ServerPlayNetworking.send(player,
				new com.mopicmp.npcstudio.net.KnackPayloads.Have(knacks));
		}
		// The same rule for the same reason: this runs on a beat for every player, and a
		// packet every beat saying what the last one said is traffic nobody notices until
		// a server has thirty people on it.
		if (!showing.equals(GAUGES.get(player.getUUID()))) {
			GAUGES.put(player.getUUID(), showing);
			ServerPlayNetworking.send(player,
				new com.mopicmp.npcstudio.net.GaugePayloads.Showing(showing));
		}
	}

	/**
	 * One gauge, turned into the six things needed to draw it.
	 *
	 * The share is worked out here rather than sent as two numbers to divide, because a
	 * maximum of nought is a bar nobody can draw — and deciding that once, where the
	 * validator has already refused it, is better than deciding it again in a renderer
	 * where the answer would be a division by zero.
	 */
	private static com.mopicmp.npcstudio.net.GaugePayloads.Shown drawn(
			com.mopicmp.npcstudio.dialogue.Gauge gauge, DialogueState state) {
		double value = state.get(gauge.variable(), gauge.scope())
				instanceof com.mopicmp.npcstudio.dialogue.Value.Num(double number)
			? number : 0;
		float full = gauge.most() > 0
			? (float) Math.max(0, Math.min(1, value / gauge.most())) : 0;
		return new com.mopicmp.npcstudio.net.GaugePayloads.Shown(gauge.label(), value,
			gauge.most(), gauge.look().name().toLowerCase(),
			gauge.corner().name().toLowerCase(), colourOf(gauge.colour()), full);
	}

	/**
	 * A colour written as a word or as digits, or the ordinary one.
	 *
	 * The same words a speaker's name takes, because it is the same question asked
	 * about a different thing and two vocabularies for one idea is one to get wrong.
	 */
	private static int colourOf(String written) {
		if (written == null || written.isEmpty()) return 0xFFECEFF1;
		// The one vocabulary this mod has for colours, shared with the bar, the answers
		// and every picker that offers one. A second reading of the same words is how a
		// colour offered in the editor comes to mean nothing where it is drawn.
		return com.mopicmp.npcstudio.dialogue.text.Tint.of(written);
	}

	/** What each player was last shown, so the beat above can stay quiet when nothing moved. */
	private static final Map<java.util.UUID,
			java.util.List<com.mopicmp.npcstudio.net.GaugePayloads.Shown>> GAUGES =
		new java.util.HashMap<>();

	/**
	 * What each player was last told they could do.
	 *
	 * Held so that the beat above can stay quiet when nothing changed. Dropped when they
	 * leave, so the next login is told afresh rather than being trusted to remember
	 * across a disconnect — which it would not, since the client forgets with the world.
	 */
	private static final Map<java.util.UUID, java.util.List<String>> KNACKS =
		new java.util.HashMap<>();

	/**
	 * The player used one of this mod's own abilities.
	 *
	 * <h2>Why this is trusted, and what that is worth</h2>
	 *
	 * The client says it jumped twice. It could be lying, and the worst a lie buys is
	 * spending its own energy on a jump it did not make: which abilities are on is
	 * decided here, from the document's conditions, and the cost lives in the graph
	 * which is also here. Checking would cost every honest player a delay to catch
	 * nobody — somebody editing their own client can already move how they like.
	 *
	 * It is refused when the ability is not on, which is not a security measure either.
	 * It is so that a stale packet — one in flight while the last of the energy went —
	 * does not charge for a jump the client was already told it could not make.
	 */
	/**
	 * The player used an item: run whatever rule answers for that kind.
	 *
	 * <h2>Why the subject is the stack and not the player</h2>
	 *
	 * Because a document about things is about the thing. That is what makes
	 * {@code Scope.CHARACTER} mean the stack's own memory — a sword that counts what it
	 * has killed keeps the count on itself, travels with it, and takes it into a chest.
	 *
	 * A document about the player that answers an item is perfectly ordinary too, and
	 * there the subject is nobody in particular: the item was the occasion, not the
	 * subject. Which of the two it is comes from the document's kind, which is exactly
	 * what the kind is for.
	 *
	 * @return true when a rule answered and the game's own behaviour should not also run
	 */
	public static boolean usedItem(ServerPlayer player,
			net.minecraft.world.InteractionHand hand) {
		net.minecraft.world.item.ItemStack held = player.getItemInHand(hand);
		if (held.isEmpty()) return false;
		String id = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
		boolean answered = false;

		for (String name : DialogueRegistry.names(com.mopicmp.npcstudio.map.Whereabouts.of(player))) {
			Dialogue dialogue = DialogueRegistry.get(name).orElse(null);
			if (dialogue == null) continue;
			for (var trigger : dialogue.triggers()) {
				if (!(trigger.cause()
						instanceof com.mopicmp.npcstudio.dialogue.Trigger.Cause.UsedItem(
							String wanted))) {
					continue;
				}
				if (!id.equals(wanted)) continue;

				DialogueSaveData store = DialogueSaveData.of(player.level());
				Cast cast = dialogue.kind() == Dialogue.Kind.THING
					? new Cast.Held(player, hand)
					: new Cast.Nobody(zoneName(dialogue, trigger.wayIn()),
						store.zoneVars(zoneName(dialogue, trigger.wayIn())));
				DialogueState asking = fresh(store, player, cast, dialogue);
				if (!trigger.who().test(asking, worldFor(player, cast, dialogue))) continue;

				maybeBegin(player, dialogue, trigger, cast);
				answered |= trigger.instead();
			}
		}
		return answered;
	}

	public static void usedKnack(ServerPlayer player, String knack) {
		if (!KNACKS.getOrDefault(player.getUUID(), java.util.List.of()).contains(knack)) return;

		for (String name : DialogueRegistry.names(com.mopicmp.npcstudio.map.Whereabouts.of(player))) {
			Dialogue dialogue = DialogueRegistry.get(name).orElse(null);
			if (dialogue == null) continue;
			for (var trigger : dialogue.triggers()) {
				if (!(trigger.cause()
						instanceof com.mopicmp.npcstudio.dialogue.Trigger.Cause.Knack(
							String wanted))) {
					continue;
				}
				if (!wanted.equals(knack)) continue;

				DialogueSaveData store = DialogueSaveData.of(player.level());
				String zone = zoneName(dialogue, trigger.wayIn());
				Cast cast = new Cast.Nobody(zone, store.zoneVars(zone));
				DialogueState asking = fresh(store, player, cast, dialogue);
				if (!trigger.who().test(asking, worldFor(player, cast, dialogue))) continue;
				maybeBegin(player, dialogue, trigger);
			}
		}
	}

	/**
	 * The place in the world a mark names, for the verbs and tests that are about blocks.
	 *
	 * Only the two that can name a block: a marked point, and whatever this rule fired
	 * about. The rest of the vocabulary points at creatures, and a creature is not
	 * somewhere a block can be put.
	 */
	private static net.minecraft.core.BlockPos where(ServerPlayer player, Cast cast, String mark) {
		if (mark == null || mark.isEmpty()) return null;
		if (com.mopicmp.npcstudio.dialogue.Mark.IT.equals(mark)) {
			return ABOUT.get(new Thread(player.getUUID(), cast.id()));
		}
		String place = com.mopicmp.npcstudio.dialogue.Mark.place(mark);
		if (place == null) return null;
		if (!(player.level() instanceof net.minecraft.server.level.ServerLevel level)) return null;
		return com.mopicmp.npcstudio.map.WorldSpots.of(level).at(place);
	}

	/**
	 * Somebody used a block: run whatever rule answers for it.
	 *
	 * <h2>Why the cheap questions come first</h2>
	 *
	 * A right click happens every second for every player. So this is asked in the order
	 * that throws the most away soonest: does any document watch the ground at all, then
	 * is this trigger about using something, then is this the place, and only then the
	 * state of the block and the condition about who. A rule that measured the player
	 * before checking whether the place matched would cost every document something on
	 * every click — and it would be found on a real map with forty of them rather than
	 * on a test map with one.
	 *
	 * @return true when a rule answered and the game's own behaviour should not also run
	 */
	public static boolean used(ServerPlayer player, net.minecraft.core.BlockPos at) {
		if (!(player.level() instanceof net.minecraft.server.level.ServerLevel level)) return false;
		var spots = com.mopicmp.npcstudio.map.WorldSpots.of(level);
		boolean answered = false;

		for (String name : DialogueRegistry.names(com.mopicmp.npcstudio.map.Whereabouts.of(player))) {
			Dialogue dialogue = DialogueRegistry.get(name).orElse(null);
			if (dialogue == null || !dialogue.watchesGround()) continue;
			for (var trigger : dialogue.triggers()) {
				// The cheapest question first, and it throws away nearly everything: is
				// this trigger even about a click, and is it about this block. Only then
				// anything that costs.
				String block = switch (trigger.cause()) {
					case com.mopicmp.npcstudio.dialogue.Trigger.Cause.Used(
							String place, String wanted) ->
						at.equals(spots.at(place)) ? wanted : null;
					// A kind rather than a place: every campfire on the map, including
					// the ones built after the graph was written. This is what makes a
					// rule a rule about the world instead of about one spot.
					case com.mopicmp.npcstudio.dialogue.Trigger.Cause.UsedAny(String wanted) ->
						wanted;
					case com.mopicmp.npcstudio.dialogue.Trigger.Cause.Inside _,
							com.mopicmp.npcstudio.dialogue.Trigger.Cause.Became _,
							com.mopicmp.npcstudio.dialogue.Trigger.Cause.Knack _,
							com.mopicmp.npcstudio.dialogue.Trigger.Cause.UsedItem _ -> null;
				};
				if (block == null) continue;
				// The state is a filter here rather than something waited for, which is
				// how "if it is alight, put it out" and "if it is out, light it" become
				// two rules instead of one rule with a branch in it.
				if (!block.isBlank() && !BlockLook.matches(level, at, block)) continue;

				DialogueSaveData store = DialogueSaveData.of(level);
				String zone = zoneName(dialogue, trigger.wayIn());
				Cast cast = new Cast.Nobody(zone, store.zoneVars(zone));
				DialogueState asking = fresh(store, player, cast, dialogue);
				// Written down before the condition is asked, because the condition may
				// perfectly well be about the block itself — "if what they clicked is
				// alight" is the commonest thing anybody writes here.
				ABOUT.put(new Thread(player.getUUID(), cast.id()), at);
				if (!trigger.who().test(asking, worldFor(player, cast, dialogue))) {
					ABOUT.remove(new Thread(player.getUUID(), cast.id()));
					continue;
				}

				maybeBegin(player, dialogue, trigger);
				answered |= trigger.instead();
			}
		}
		return answered;
	}

	private static void step(ServerPlayer player, Cast cast, Dialogue dialogue,
			DialogueState state, DialogueEngine.Input input) {
		DialogueSaveData store = DialogueSaveData.of(player.level());
		DialogueEngine.Step step;
		boolean watched = LOOKING_ON.contains(player.getUUID());
		var lines = new java.util.ArrayList<String>();
		try {
			step = watched
				? DialogueEngine.step(dialogue, state, input, worldFor(player, cast, dialogue),
					line -> {
						// Cut short rather than allowed to grow: a graph that walks through
						// forty nodes in one step is a real graph, and the packet has a
						// bound. Losing the tail of an account is better than losing the
						// account.
						if (lines.size() < com.mopicmp.npcstudio.net.WatchPayloads.MOST_LINES) {
							lines.add(line);
						}
					})
				: DialogueEngine.step(dialogue, state, input, worldFor(player, cast, dialogue));
		} catch (DialogueEngine.DialogueFault fault) {
			// A broken dialogue must not take the player down with it. The bookmark
			// is dropped so a second click starts cleanly instead of hitting the
			// same wall, and the reason goes to the log where it can be fixed.
			store.clearBookmark(player.getUUID(), cast.id());
			NpcStudio.LOGGER.error("Dialogue \"{}\" failed: {}", dialogue.id(), fault.getMessage());
			player.sendSystemMessage(Component.literal("That conversation is broken; it has been reset.")
				.withStyle(ChatFormatting.RED));
			return;
		}

		if (watched) {
			// The variables after the step rather than before it. What somebody wants to
			// know is what the graph believes now — the values it decided on are already
			// spelled out in the branch lines above them.
			for (String said : com.mopicmp.npcstudio.dialogue.Explain
					.variables(dialogue, step.state())) {
				if (lines.size() < com.mopicmp.npcstudio.net.WatchPayloads.MOST_LINES) {
					lines.add(said);
				}
			}
			ServerPlayNetworking.send(player,
				new com.mopicmp.npcstudio.net.WatchPayloads.Told(List.copyOf(lines)));
		}

		for (Effect effect : step.effects()) {
			apply(effect, player, cast, dialogue);
		}

		// And the standing properties, because a step may have moved a variable one of
		// them reads. Straight away rather than on the next beat: a conversation that
		// hands you the feather should leave you able to jump before the line has faded.
		settle(player);

		// Progress and world state are written back whatever the screen turns out
		// to be — including when the conversation just ended. That is exactly the
		// case where it matters: what a conversation taught must outlive it.
		store.remember(player.getUUID(), step.state().playerVars(),
			step.state().visited(), step.state().worldVars());
		// And what the conversation taught the character about herself, which lives
		// on her rather than in the world's book — twenty guards sharing one script
		// each remember their own half of it.
		cast.rememberOnly(step.state().characterVars());
		// A place has no body to hold its own memory in, so the world's book holds it —
		// see DialogueSaveData.zoneVars. Written here rather than inside the cast so
		// that the marking-dirty stays where every other write to the save is.
		if (cast instanceof Cast.Nobody nobody) {
			store.rememberZone(nobody.where(), step.state().characterVars());
		}

		// Whatever came out, this conversation is no longer standing still. Cleared
		// before the switch rather than in each arm, because a new arm added later
		// would be a conversation that stays parked while it is plainly doing
		// something — and the parked list is what drives the ticking.
		Thread thread = new Thread(player.getUUID(), cast.id());
		if (!(step.screen() instanceof DialogueEngine.Screen.Waiting)) {
			PARKED.remove(thread);
		}

		switch (step.screen()) {
			case DialogueEngine.Screen.Line line -> {
				store.putBookmark(player.getUUID(), cast.id(), step.state().currentNode());
				ServerPlayNetworking.send(player, new ShowLinePayload(
					cast.shownAs(), line.speaker(), line.text(), line.mode().ordinal(),
					dialogue.manner(), line.face().ordinal(),
					// Settled here, where both answers are known. The line's own colour
					// outranks the document's, which is this editor's rule everywhere:
					// somebody who said it on the spot was looking at the spot.
					line.nameColour().isEmpty()
						? dialogue.voiceOf(line.speaker()) : line.nameColour()));
				DialogueDisplay.showing(player, cast.shownAs(),
					line.mode() == Presentation.FULLSCREEN, dialogue.manner(), line.lasts(),
					zoneOf(cast));
			}
			case DialogueEngine.Screen.Choice choice -> {
				store.putBookmark(player.getUUID(), cast.id(), step.state().currentNode());
				ServerPlayNetworking.send(player, new ShowChoicePayload(
					cast.shownAs(), choice.speaker(), choice.prompt(), choice.mode().ordinal(),
					choice.options().stream()
						.map(o -> new ShowChoicePayload.Option(o.index(), o.label(),
							o.colour() == null ? "" : o.colour()))
						.toList(),
					dialogue.manner()));
				DialogueDisplay.showing(player, cast.shownAs(),
					choice.mode() == Presentation.FULLSCREEN, dialogue.manner(), zoneOf(cast));
			}
			case DialogueEngine.Screen.Finished _ -> {
				// The conversation is over, so the bookmark goes: the next click
				// should start again rather than resume something that ended. The
				// variables it set stay — that is the difference between finishing a
				// conversation and never having had it.
				store.clearBookmark(player.getUUID(), cast.id());
				DialogueDisplay.hide(player);
				// And down comes anything it left standing. Here rather than left to the
				// author: a graph that raised two walls and reached its ending down a
				// branch that drops one is not a broken graph, it is one written by
				// somebody thinking about the scene instead of about the blocks.
				sweep(player);
			}
			// A graph standing still, which a conversation may now do.
			//
			// It used to be refused outright, on the grounds that nothing would come
			// back and wake it. Something does: see {@link #tick}. What is kept from
			// the old behaviour is the ceiling — a graph that waits for a minute in
			// front of a player is a mistake somebody should be told about, and the
			// message is the same one, said only when it is true.
			//
			// Nothing is taken off the screen. The line she said before setting off
			// stays up while she walks, which is what anybody writing "say this, then
			// go there" meant — and its own idle timer takes it down in its own time.
			case DialogueEngine.Screen.Waiting _ -> {
				store.putBookmark(player.getUUID(), cast.id(), step.state().currentNode());
				Parked was = PARKED.get(thread);
				// Patience is about nothing happening, and a character walking is
				// something happening. Counted against a walk, a minute is shorter than
				// half the routes anybody would put in a scene — so a long walk reported
				// itself as a broken graph and reset a conversation that was working.
				// And a graph waiting to be pressed is not standing still by mistake
				// either — it is a menu, and a menu that nobody has touched for a minute
				// is a menu, not a fault. Without this, a hub would throw its own doors
				// away a minute after laying them out, with a red message blaming the
				// author for a graph that was working exactly as written.
				boolean listening = dialogue.node(step.state().currentNode()) instanceof Node.Pressed;
				int stood = was == null || listening || !cast.walkingTo().isEmpty()
					? 0 : was.ticks();
				if (stood >= PATIENCE) {
					PARKED.remove(thread);
					store.clearBookmark(player.getUUID(), cast.id());
					NpcStudio.LOGGER.error(
						"Dialogue \"{}\" has waited at \"{}\" for {} ticks; it has been reset.",
						dialogue.id(), step.state().currentNode(), stood);
					player.sendSystemMessage(Component.literal(
						"That conversation has been waiting for a minute; it has been reset.")
						.withStyle(ChatFormatting.RED));
					DialogueDisplay.hide(player);
					// A conversation that gave up mid-scene is exactly the one that has
					// walls up: it was waiting for somebody to cross something.
					sweep(player);
					// And the character is put back, which the sweep does not do and must
					// not do for an ordinary ending.
					//
					// The difference is worth stating, because it is the whole rule. A
					// conversation that *finished* leaves the world as it left it: it
					// ended where its author meant it to, and a character standing
					// somewhere new is part of what was written. A conversation that was
					// *given up on* did not finish, and leaving the character half way
					// through a scene whose bookmark has just been thrown away means the
					// next run starts with her in the wrong place — reported as exactly
					// that: the dialogue broke and she had to be fetched by hand.
					abandon(cast);
					return;
				}
				PARKED.put(thread, new Parked(cast.shownAs(), stood + 1, zoneOf(cast)));
			}
		}
	}


	/**
	 * The questions a condition may ask during a conversation.
	 *
	 * {@code hasItem} is about the player's pockets, because a conversation is
	 * about the player — the same question means her own hands when a behaviour
	 * graph asks it, and both readings are right for whoever is asking.
	 *
	 * The senses are the NPC's, and come free: the machinery was built for
	 * behaviour, and a conversation that can ask whether the guard is already
	 * alert is worth having for nothing.
	 */
	private static Condition.World worldFor(ServerPlayer player, Cast cast,
			Dialogue dialogue) {
		return new Condition.World() {
			/**
			 * Whether the player is standing in one of this document's boxes.
			 *
			 * The box is looked up in the graph being run, not anywhere global: boxes
			 * belong to the document that drew them, and two graphs are free to have a
			 * "hall" each without meaning the same piece of ground.
			 *
			 * Measured against the character this conversation is with, which is what
			 * makes a box written as steps land where the author drew it. The editor
			 * asks the same question of the same anchor while it is being drawn — see
			 * {@code Routing.world} — and a box that answered here against a different
			 * frame would be right on the screen and wrong on the ground.
			 */
			@Override
			public boolean inside(String area) {
				com.mopicmp.npcstudio.dialogue.Area box = dialogue.area(area);
				if (box == null) return false;
				var home = cast.anchorAt();
				return box.holds(player.getX(), player.getY(), player.getZ(),
					net.minecraft.util.Mth.floor(home.x),
					net.minecraft.util.Mth.floor(home.y),
					net.minecraft.util.Mth.floor(home.z),
					cast.facing());
			}

			/**
			 * Whether the block at a marked place is what the graph says.
			 *
			 * The place is looked up in the world's own book rather than in the
			 * document, because a point is a fact about a map and several documents may
			 * name the same one — which is the point of them. A mark naming nothing
			 * answers no, the same as a mark naming a place in a chunk nobody is near.
			 */
			@Override
			public boolean blockAt(String mark, String block) {
				if (!(player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
					return false;
				}
				return BlockLook.matches(level, where(player, cast, mark), block);
			}

			@Override
			public boolean holding(String item) {
				// Both hands, because "with a flint and steel in hand" is true of
				// somebody holding one in either, and the game itself tries the main
				// hand and then the off hand when anything is used.
				net.minecraft.resources.Identifier id = Identifier.tryParse(
					item.contains(":") ? item : "minecraft:" + item);
				if (id == null) return false;
				Item wanted = BuiltInRegistries.ITEM.getValue(id);
				return player.getMainHandItem().is(wanted) || player.getOffhandItem().is(wanted);
			}

			@Override
			public boolean hasItem(String itemId, int count) {
				Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
				int found = 0;
				for (ItemStack stack : player.getInventory()) {
					if (stack.is(item)) found += stack.getCount();
				}
				return found >= count;
			}

			@Override
			public com.mopicmp.npcstudio.dialogue.Value sense(String name) {
				// A place has no senses. Nought rather than an error, the same answer a
				// mark that names nothing gives: a scene must not stop dead because it
				// asked a question its own subject cannot have.
				return cast.body() == null
					? com.mopicmp.npcstudio.dialogue.Value.of(false)
					: com.mopicmp.npcstudio.brain.Senses.of(cast.body(), name);
			}

			// The level's own throw rather than a generator of ours. It is the one the
			// rest of the game uses, it is seeded with the world, and a second source of
			// chance would be a second thing to seed, to save and to explain.
			@Override public int roll(int sides) {
				return sides <= 1 ? 0 : player.level().getRandom().nextInt(sides);
			}
		};
	}

	private static void apply(Effect effect, ServerPlayer player, Cast cast,
			Dialogue dialogue) {
		switch (effect) {
			case Effect.Wall(String area, boolean up) -> wall(player, cast, dialogue, area, up);
			// One block, put there on purpose, and meant to stay. No sweep and no memory
			// of what was there before — a verb that quietly put the world back when a
			// conversation ended could not put a fire out at all.
			case Effect.PutBlock(String mark, String block) -> {
				var at = where(player, cast, mark);
				var state = BlockLook.state(block);
				if (at != null && state != null
						&& player.level() instanceof net.minecraft.server.level.ServerLevel level
						&& level.isLoaded(at)) {
					level.setBlockAndUpdate(at, state);
				}
			}
			// A thing stood in the air, or the one already standing there changed. Where it
			// goes is measured the same way a teleport is — steps from the anchor, or
			// coordinates — so a menu written in one copy of a room is the same menu in the
			// next one along.
			case Effect.Show(String name, com.mopicmp.npcstudio.dialogue.Shown what,
					com.mopicmp.npcstudio.dialogue.Route.Point where) -> {
				if (player.level() instanceof net.minecraft.server.level.ServerLevel level) {
					// The values are the player's own, which is the whole of what the
					// placeholders were put in for. One hologram for everybody today, and
					// whose values fill it is the line that changes when that stops being
					// true — see Shown.filled.
					com.mopicmp.npcstudio.show.Showing.put(level,
						com.mopicmp.npcstudio.map.Whereabouts.of(player), name, what,
						what.filled(readable(player, cast, dialogue, what.names())),
						middleOf(cast, where));
				}
			}
			case Effect.Unshow(String name) -> {
				if (player.level() instanceof net.minecraft.server.level.ServerLevel level) {
					com.mopicmp.npcstudio.show.Showing.take(level,
						com.mopicmp.npcstudio.map.Whereabouts.of(player), name);
				}
			}
			// A property given to somebody, or taken back. Which body it lands on is the
			// verb's own field rather than a guess: "you are faster now" and "she is
			// faster now" are both ordinary things to write, and getting the wrong one
			// is a scene where the boon went to the wrong person with nothing to say so.
			case Effect.Trait trait -> {
				if (trait.whose() == Effect.Trait.Whose.CHARACTER) {
					if (cast.body() != null) Traits.put(cast.body(), trait);
					else NpcStudio.LOGGER.warn(
						"A place asked to give a character \"{}\"; a place has no body.",
						trait.name());
				} else {
					Traits.put(player, trait);
				}
			}
			case Effect.Send(com.mopicmp.npcstudio.dialogue.Route.Point where) ->
				send(player, cast, where);
			// The whole state rather than an adjustment to it, so a client cannot end up
			// disagreeing about what is on its own screen. See ShowPortraitPayload.
			case Effect.Portrait showing -> {
				SHOWING_PORTRAIT.put(player.getUUID(), showing.showing());
				ServerPlayNetworking.send(player, standing(showing));
			}
			case Effect.Hold(boolean up) -> {
				if (up) HELD.add(player.getUUID());
				else HELD.remove(player.getUUID());
				ServerPlayNetworking.send(player,
					new com.mopicmp.npcstudio.net.HoldPlayerPayload(up));
			}
			// A stare is done by the character, like every other verb about her — but
			// it is written down as well, because it is one of the few that does not
			// finish by itself and so has to be let go of when the conversation does.
			case Effect.LookAt(String mark) -> {
				if (cast.body() == null) break;
				com.mopicmp.npcstudio.brain.Deeds.doTo(effect, cast.body());
				// Told to look at nothing, there is nothing left to let go of, and
				// leaving a note here would make the next ending clear a stare somebody
				// else had set in between.
				if (mark == null || mark.isEmpty()
						|| com.mopicmp.npcstudio.dialogue.Mark.NOTHING.equals(mark)) {
					STARING.remove(player.getUUID());
				} else {
					STARING.put(player.getUUID(), new Staring(cast.shownAs(), mark));
				}
			}
			case Effect.GiveItem(String id, int count) -> {
				Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
				ItemStack stack = new ItemStack(item, count);
				// Drop what will not fit rather than deleting it: an unnoticed loss
				// of a quest reward is far worse than an item on the floor.
				if (!player.getInventory().add(stack)) {
					player.drop(stack, false);
				}
			}
			case Effect.TakeItem(String id, int count) -> {
				Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
				int left = count;
				for (ItemStack stack : player.getInventory()) {
					if (left <= 0) break;
					if (!stack.is(item)) continue;
					int taken = Math.min(left, stack.getCount());
					stack.shrink(taken);
					left -= taken;
				}
			}
			// Run as the server rather than as the player: a dialogue may need to do
			// things the player has no right to do, and the alternative — handing
			// the player elevated rights for a moment — is far easier to abuse.
			case Effect.RunCommand(String command) ->
				player.level().getServer().getCommands().performPrefixedCommand(
					player.createCommandSourceStack()
						.withMaximumPermission(PermissionSet.ALL_PERMISSIONS),
					command);
			// Everything that is about the character rather than about the player
			// goes to the one place that knows how to do it. A conversation that
			// ends with the guard turning and walking away is an ordinary thing to
			// write, and there is no reason for it to mean something different here
			// from what it means in a behaviour graph.
			default -> {
				// Everything left is about a body, and a conversation a place began has
				// none. Said out loud in the log rather than ignored: a verb that does
				// nothing is exactly the thing an author needs told about, and it is the
				// same answer a behaviour graph already gives to the verbs about a player.
				if (cast.body() == null) {
					NpcStudio.LOGGER.warn(
						"Dialogue \"{}\" asked for {}, which is about a character, and this"
							+ " conversation was begun by a place and has none.",
						dialogue.id(), effect.getClass().getSimpleName());
					break;
				}
				com.mopicmp.npcstudio.brain.Deeds.doTo(effect, cast.body());
			}
		}
	}

	/**
	 * The middle of the block a point names, which is where a shown thing hangs.
	 *
	 * The middle rather than the corner, because a hologram is not a block: an author who
	 * writes "two up and one left" means the space, and a thing at the corner of it reads
	 * as half a block out from everything built around it.
	 */
	private static net.minecraft.world.phys.Vec3 middleOf(Cast cast,
			com.mopicmp.npcstudio.dialogue.Route.Point where) {
		var anchor = cast.anchorAt();
		int[] to = com.mopicmp.npcstudio.dialogue.Route.world(where,
			net.minecraft.util.Mth.floor(anchor.x),
			net.minecraft.util.Mth.floor(anchor.y),
			net.minecraft.util.Mth.floor(anchor.z),
			cast.facing());
		return net.minecraft.world.phys.Vec3.atCenterOf(
			new net.minecraft.core.BlockPos(to[0], to[1], to[2]));
	}

	/**
	 * What the variables named in a hologram's text currently read, as words.
	 *
	 * Only the names it actually asks for, so a document with forty variables in it costs
	 * a shown thing nothing unless it mentions them.
	 *
	 * The three places are asked in the order a reader would expect: the player's own
	 * first, then the character's or the place's, then the world's. A name that is in none
	 * of them is left out here and left standing in the text by {@link
	 * com.mopicmp.npcstudio.dialogue.Shown#filled} — visible on the wall, pointing at
	 * itself, which is what a misspelt one should do.
	 */
	private static Map<String, String> readable(ServerPlayer player, Cast cast,
			Dialogue dialogue, java.util.List<String> names) {
		if (names.isEmpty()) return Map.of();
		DialogueSaveData store = DialogueSaveData.of(player.level());
		DialogueState state = fresh(store, player, cast, dialogue);
		Map<String, String> values = new java.util.HashMap<>();
		for (String name : names) {
			com.mopicmp.npcstudio.dialogue.Value had = state.playerVars().get(name);
			if (had == null) had = state.characterVars().get(name);
			if (had == null) had = state.worldVars().get(name);
			if (had != null) values.put(name, said(had));
		}
		return values;
	}

	/** A value as it is written on a wall: a whole number without its nought decimals. */
	private static String said(com.mopicmp.npcstudio.dialogue.Value value) {
		return switch (value) {
			case com.mopicmp.npcstudio.dialogue.Value.Text(String text) -> text;
			case com.mopicmp.npcstudio.dialogue.Value.Flag(boolean flag) -> String.valueOf(flag);
			case com.mopicmp.npcstudio.dialogue.Value.Num(double number) ->
				number == Math.rint(number) ? String.valueOf((long) number) : String.valueOf(number);
		};
	}

	/**
	 * Puts the player somewhere else, at once.
	 *
	 * Measured against the character, like every other step in this mod, so a scene
	 * written as "three blocks behind her" survives being built off to one side of the
	 * map and pasted in. A point written as coordinates ignores her entirely and is
	 * what a scene with nobody in it uses.
	 *
	 * The game's own teleport rather than setting the position by hand: it loads the
	 * chunk, tells the client it was a move rather than a stride, and does not leave
	 * the camera interpolating across the ground in between.
	 */
	private static void send(ServerPlayer player, Cast cast,
			com.mopicmp.npcstudio.dialogue.Route.Point where) {
		if (where == null) return;
		var anchor = cast.anchorAt();
		int[] to = com.mopicmp.npcstudio.dialogue.Route.world(where,
			net.minecraft.util.Mth.floor(anchor.x),
			net.minecraft.util.Mth.floor(anchor.y),
			net.minecraft.util.Mth.floor(anchor.z),
			cast.facing());
		var at = net.minecraft.world.phys.Vec3.atBottomCenterOf(
			new net.minecraft.core.BlockPos(to[0], to[1], to[2]));
		player.teleportTo(at.x, at.y, at.z);
	}

	/**
	 * Seals a box, or gives the passage back.
	 *
	 * <h2>Where the blocks are worked out, and why it is here rather than there</h2>
	 *
	 * A box written as steps hangs off the character, and she walks. The cells are
	 * therefore worked out once, at the moment the wall goes up, and the list of them
	 * is what comes down again — see {@link Walls}. Asking the box a second time when
	 * the wall is dropped would ask it about wherever she is standing then, clear a
	 * set of blocks nobody filled, and leave the real wall in the corridor for ever.
	 *
	 * <h2>What is said out loud, and to whom</h2>
	 *
	 * A box that does not exist is not an error at run time — the same choice a named
	 * place that has gone makes, for the same reason: a conversation stopping dead in
	 * front of a player is worse than a trap that does not spring. It goes to the log,
	 * where the person building the map can find it, and the editor is where it is
	 * said properly.
	 */
	private static void wall(ServerPlayer player, Cast cast, Dialogue dialogue,
			String area, boolean up) {
		if (!(player.level() instanceof net.minecraft.server.level.ServerLevel level)) return;
		Walls walls = Walls.of(level);
		if (!up) {
			walls.drop(Walls.in(level), player.getUUID(), cast.id(), area);
			return;
		}

		com.mopicmp.npcstudio.dialogue.Area box = dialogue.area(area);
		if (box == null) {
			NpcStudio.LOGGER.warn(
				"Dialogue \"{}\" seals a box called \"{}\", which it does not have.",
				dialogue.id(), area);
			return;
		}
		var home = cast.anchorAt();
		int[][] corners = box.world(
			net.minecraft.util.Mth.floor(home.x),
			net.minecraft.util.Mth.floor(home.y),
			net.minecraft.util.Mth.floor(home.z),
			cast.facing());
		int placed = walls.raise(Walls.in(level), player.getUUID(), cast.id(), area,
			corners[0], corners[1]);
		if (placed < 0) {
			NpcStudio.LOGGER.warn(
				"Dialogue \"{}\" asked to seal \"{}\", which is over {} blocks; it was refused.",
				dialogue.id(), area, Walls.MOST);
		}
	}

	/**
	 * Everything this conversation was holding is let go of.
	 *
	 * Called from every way a conversation stops, and that list is the whole of the
	 * risk these verbs carry. A wall left up is invisible, unbreakable and silent —
	 * the author walks into thin air for the rest of the afternoon with nothing
	 * anywhere to tell them why. A stare left on is the same fault wearing a face:
	 * it looks like one character behaving oddly, and it is actually a character who
	 * will never look at anything by herself again.
	 *
	 * So both are here, in one place, and anything else that holds rather than
	 * happens belongs here too. Most verbs do not: an animation and an expression
	 * both end on a clock of their own, and giving an item cannot be undone by an
	 * ending because it was not a hold in the first place.
	 */
	private static void sweep(ServerPlayer player) {
		if (player.level() instanceof net.minecraft.server.level.ServerLevel level) {
			Walls.of(level).dropAll(Walls.in(level), player.getUUID());
		}
		unstare(player);
		unhold(player);
		unshow(player);
	}

	/**
	 * Which players have a picture standing on their screen.
	 *
	 * The same shape as the hold and the stare beside it, and for the same reason:
	 * a portrait is raised by a verb and outlives the line that raised it, so
	 * something has to be responsible for taking it down. A picture left over a
	 * player's screen is the same class of fault as a wall left in their corridor —
	 * silent, permanent, and nothing on screen saying which graph did it.
	 */
	private static final Map<java.util.UUID, Boolean> SHOWING_PORTRAIT =
		new java.util.HashMap<>();

	/**
	 * What the act asked for, turned into pictures and corners.
	 *
	 * <h2>Why the sheet is looked up here rather than sent</h2>
	 *
	 * Because the client should not need it. A player who has never opened the layout
	 * window — which is every player — has to draw the figure correctly, and a sheet
	 * edited while somebody is mid-scene must not leave two clients disagreeing about
	 * where a character's eyes are. The server holds the sheet, so the server answers.
	 *
	 * <h2>What a figure nobody has a sheet for does</h2>
	 *
	 * Nothing, quietly. A world whose sheets have not loaded, or an act naming a figure
	 * somebody has since thrown away, sends an empty stack — which reads as "no portrait"
	 * and is the state every scene already knows how to be in. Refusing to step the
	 * conversation would be a scene stuck on a missing picture.
	 */
	private static com.mopicmp.npcstudio.net.ShowPortraitPayload standing(
			Effect.Portrait showing) {
		if (!showing.assembled()) {
			return showing.picture().isEmpty()
				? com.mopicmp.npcstudio.net.ShowPortraitPayload.none()
				: com.mopicmp.npcstudio.net.ShowPortraitPayload.one(
					showing.picture(), showing.side().ordinal(), showing.mirrored());
		}
		var shelf = com.mopicmp.npcstudio.puppet.Puppets.shelf();
		var sheet = shelf == null ? null : shelf.named(showing.figure());
		if (sheet == null) return com.mopicmp.npcstudio.net.ShowPortraitPayload.none();

		var layers = new java.util.ArrayList<com.mopicmp.npcstudio.net.ShowPortraitPayload.Layer>();
		for (var placed : sheet.worn(showing.choice())) {
			if (layers.size() >= com.mopicmp.npcstudio.net.ShowPortraitPayload.MOST_LAYERS) break;
			layers.add(new com.mopicmp.npcstudio.net.ShowPortraitPayload.Layer(
				placed.picture(), placed.x(), placed.y()));
		}
		return new com.mopicmp.npcstudio.net.ShowPortraitPayload(layers,
			sheet.wide(), sheet.high(), showing.side().ordinal(), showing.mirrored());
	}

	private static void unshow(ServerPlayer player) {
		if (SHOWING_PORTRAIT.remove(player.getUUID()) == null) return;
		ServerPlayNetworking.send(player,
			com.mopicmp.npcstudio.net.ShowPortraitPayload.none());
	}

	/**
	 * The player gets their feet back.
	 *
	 * Unconditional, unlike the stare: there is no "somebody else may have set this"
	 * case worth being careful about, because nothing else sets it — and if there
	 * ever is, the right answer is still to let go. Being wrongly free for a moment
	 * is a scene with a flaw in it; being wrongly held is somebody who has to close
	 * the game.
	 */
	/**
	 * A scene nobody finished is wound back, as far as it can be.
	 *
	 * Only the two things this mod put in motion: the walking it ordered, and where it
	 * left her. Not her memory, not the world's variables, not anything the player
	 * gained — those are things that happened, and undoing them would be a reset
	 * quietly rewriting somebody's save.
	 *
	 * Home rather than a guess at where the scene began, because home is the one place
	 * the document already knows and the one every route is measured from. She goes
	 * there without walking: the journey back is a minute nobody asked to watch, and a
	 * character walking home across a courtyard after an error message reads as a
	 * second thing going wrong.
	 */
	private static void abandon(Cast cast) {
		// Nothing to wind back when there was nobody walking. A place cannot be left
		// half way through a scene, because a place does not move.
		if (cast.body() == null) return;
		cast.body().halt();
		cast.body().goHome(false);
	}

	private static void unhold(ServerPlayer player) {
		if (!HELD.remove(player.getUUID())) return;
		ServerPlayNetworking.send(player, new com.mopicmp.npcstudio.net.HoldPlayerPayload(false));
	}

	/**
	 * The character stops looking where this conversation pointed her.
	 *
	 * Only if she is still looking exactly there. Anything may have happened between
	 * the order and the ending — she may have been dropped into a fight, and a fight
	 * points her at what it is fighting — and an ending that cleared that would be a
	 * conversation reaching out of its grave to blind somebody.
	 *
	 * Cleared to nothing rather than to {@link com.mopicmp.npcstudio.dialogue.Mark}'s
	 * word for it: they behave the same in the gaze, but the empty one is "no graph
	 * is holding this head", which is what has just become true, and it is what lets
	 * the watching reflex have its head back.
	 */
	private static void unstare(ServerPlayer player) {
		Staring was = STARING.remove(player.getUUID());
		if (was == null) return;
		if (player.level().getEntity(was.npc()) instanceof NpcEntity npc
				&& was.mark().equals(npc.gazingAt())) {
			npc.lookAt("");
		}
	}
}
