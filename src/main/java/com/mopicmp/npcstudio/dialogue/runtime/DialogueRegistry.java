package com.mopicmp.npcstudio.dialogue.runtime;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.dialogue.Condition;
import com.mopicmp.npcstudio.dialogue.Dialogue;
import com.mopicmp.npcstudio.dialogue.DialogueValidator;
import com.mopicmp.npcstudio.dialogue.Effect;
import com.mopicmp.npcstudio.dialogue.Mark;
import com.mopicmp.npcstudio.dialogue.Node;
import com.mopicmp.npcstudio.dialogue.Presentation;
import com.mopicmp.npcstudio.dialogue.Scope;
import com.mopicmp.npcstudio.dialogue.Sense;
import com.mopicmp.npcstudio.dialogue.Value;

import net.minecraft.resources.Identifier;

/**
 * Every dialogue the server knows, by name.
 *
 * An NPC stores the name and looks the conversation up here, so twenty copies
 * of the same guard share one script and fixing a line fixes all of them.
 *
 * Filled from one built-in example for now. Reading dialogues from datapacks is
 * the same job as saving them into a world — both need codecs — so they are
 * being done together rather than half each.
 */
public final class DialogueRegistry {

	private static final Map<String, Dialogue> DIALOGUES = new ConcurrentHashMap<>();

	/**
	 * Dialogues written in-game, held apart from the ones packs provide.
	 *
	 * Two maps rather than one because they are refilled at different moments:
	 * packs on every reload, the world's own when the world opens. Merging them
	 * into a single map would mean a reload quietly deleting everything someone
	 * had built in the editor.
	 */
	private static final Map<String, Dialogue> WORLD = new ConcurrentHashMap<>();

	private DialogueRegistry() { }

	/** The world's own wins: someone editing is looking at the thing they changed. */
	public static Optional<Dialogue> get(String id) {
		Dialogue own = WORLD.get(id);
		return Optional.ofNullable(own != null ? own : DIALOGUES.get(id));
	}

	/**
	 * The copy a world was given, as against the one that ships with the mod.
	 *
	 * <h2>Why anybody needs to tell them apart</h2>
	 *
	 * Because the world's own wins, silently, and that is right while it is a
	 * deliberate edit — somebody working on a graph is looking at the thing they
	 * changed. It is not right when the world's copy is damaged: the built-in one
	 * is sitting there working and the character uses the broken one, and nothing
	 * anywhere says which is in play.
	 *
	 * That happened. The editor used to drop a document's segments on save, so a
	 * brain opened once and saved came back with its nodes and none of its skills,
	 * and from then on every character running it stood still.
	 */
	public static Optional<Dialogue> worldOwn(String id) {
		return Optional.ofNullable(WORLD.get(id));
	}

	public static Optional<Dialogue> shipped(String id) {
		return Optional.ofNullable(DIALOGUES.get(id));
	}

	public static List<String> names() {
		return java.util.stream.Stream.concat(DIALOGUES.keySet().stream(), WORLD.keySet().stream())
			.distinct().sorted().toList();
	}

	/**
	 * The ones somebody standing in a given place can see.
	 *
	 * <h2>What a place means here</h2>
	 *
	 * Empty is the map's own documents, which is every document that existed before
	 * locations did and every document an author writes on their own world. A name is a
	 * location's, and inside one only that location's documents answer.
	 *
	 * <h2>Why this cuts both ways and has to</h2>
	 *
	 * The obvious half is that a guest in a lesson should not see the map's documents.
	 * The half that is easy to forget is the other one: the map's rules must not fire on
	 * somebody standing in a lesson. A document of rules watches every player on the
	 * server, so without this a guest walking into a lesson would drag the whole map's
	 * ground triggers in with them — and what that looks like is a lesson behaving
	 * strangely for reasons that are written down somewhere they will never think to
	 * look.
	 *
	 * <h2>Why names stay unique across all of them</h2>
	 *
	 * Because a character holds the name of her document as a plain word, and a call
	 * reaches across documents by name. Letting two places each have a "greeting" would
	 * make every one of those words mean two things, and the place it was asked from
	 * would have to be carried everywhere a name goes. Visibility is what was asked for;
	 * ambiguity was not.
	 */
	public static List<String> names(String where) {
		String place = where == null ? "" : where;
		return names().stream()
			.filter(name -> get(name).map(graph -> graph.visibleFrom(place)).orElse(false))
			.toList();
	}

	/**
	 * Which graphs point anything at this mark.
	 *
	 * Asked before a named place is taken away. A place is referred to by name and
	 * by nothing else, so removing one leaves every graph that named it aiming at
	 * nothing — which is deliberately not an error while a scene plays, because a
	 * lead that is not there behaves the same and every graph has to survive that.
	 *
	 * At the moment of removal the silence is the wrong answer. The person taking
	 * the gate away is the only one who can still fix the three graphs that walk to
	 * it, and the only time they can is before they have forgotten which three.
	 *
	 * Both libraries are searched, and the world's copy wins over the shipped one of
	 * the same name — the same rule {@link #get} uses, so the answer is about the
	 * graphs that would actually run rather than about every document on the disk.
	 */
	public static List<String> pointedAt(String mark) {
		List<String> found = new java.util.ArrayList<>();
		for (String name : names()) {
			get(name).filter(graph -> graph.marksUsed().contains(mark))
				.ifPresent(graph -> found.add(name));
		}
		return List.copyOf(found);
	}

	public static void putWorld(Dialogue dialogue) {
		WORLD.put(dialogue.id(), dialogue);
	}

	public static void removeWorld(String id) {
		WORLD.remove(id);
	}

	public static void replaceWorld(Map<String, Dialogue> dialogues) {
		WORLD.clear();
		WORLD.putAll(dialogues);
	}

	/**
	 * Adds a dialogue, refusing one that will not run.
	 *
	 * The validator is not advisory here. A dialogue with a dead end or a loop
	 * that never waits would strand whoever walked into it, and the error belongs
	 * at load time in a server log — not mid-sentence in front of a player.
	 */
	public static boolean register(Dialogue dialogue) {
		DialogueValidator.Report report = DialogueValidator.validate(dialogue);
		if (!report.ok()) {
			NpcStudio.LOGGER.error("Dialogue \"{}\" was refused:\n{}", dialogue.id(), report);
			return false;
		}
		for (DialogueValidator.Problem problem : report.problems()) {
			NpcStudio.LOGGER.warn("Dialogue \"{}\": {}", dialogue.id(), problem);
		}
		DIALOGUES.put(dialogue.id(), dialogue);
		return true;
	}

	public static void registerBuiltIn() {
		register(example());
		register(torchbearer());
		register(doorman());
		register(sentry());
		register(fighting());
		register(duel());
		register(errand());
		register(errandFirst());
		register(errandSecond());
	}

	/**
	 * Three documents that are one errand: go and speak to two others, then come back.
	 *
	 * <h2>Why three and not one</h2>
	 *
	 * Because a character holds one dialogue and a conversation always begins at its
	 * {@code start} — there is no per-character way in. Three characters on one document
	 * would all begin at the same node, and there is nothing to tell them apart with:
	 * the conditions are variables, items, boxes and visited nodes, and none of them
	 * asks "who am I". So one document per character is the shape, not a preference.
	 *
	 * <h2>What ties them together</h2>
	 *
	 * A player variable, and that works because a player's variables are kept under the
	 * player and nothing else — see {@code DialogueSaveData.playerVars}. What one
	 * document writes, another reads, with no wiring between them.
	 *
	 * The price is one line of declaration in each of the three, and it has to say the
	 * same type in all three. That is the whole cost of the arrangement.
	 *
	 * <h2>Why not "has he visited that node"</h2>
	 *
	 * Because visited nodes are <b>not</b> kept per document. They are bare node ids
	 * under the player, so two documents that both have a node called {@code end} — as
	 * several of the ones above do — see each other's. A named variable says what it
	 * means and cannot collide by accident.
	 *
	 * <h2>Why two flags rather than a count of two</h2>
	 *
	 * Because "you have been to one of them" is a line anybody writing this will want,
	 * and a counter cannot say which one. Two flags cost nothing and answer both.
	 */
	private static Dialogue errand() {
		return Dialogue.builder("errand")
			// All three declare all three. A document may only use names it declares,
			// so this block is the same in each of them.
			.variable("errand_sent", "flag")
			.variable("errand_first_done", "flag")
			.variable("errand_second_done", "flag")
			.start("where?")
			// The whole of the arrangement, in one node. Every click re-enters here,
			// because a conversation that ended cleared its bookmark — so the errand
			// answers for itself without anything having to remember where it was.
			.add(new Node.Branch("where?", List.of(
				new Node.Arm(new Condition.All(List.of(
					new Condition.Compare("errand_first_done", Scope.PLAYER,
						Condition.Op.EQ, Value.of(true)),
					new Condition.Compare("errand_second_done", Scope.PLAYER,
						Condition.Op.EQ, Value.of(true)))), "done"),
				// One of the two. Above the plain "still waiting" arm, because the
				// first arm that holds wins and this one is the more particular.
				new Node.Arm(new Condition.Any(List.of(
					new Condition.Compare("errand_first_done", Scope.PLAYER,
						Condition.Op.EQ, Value.of(true)),
					new Condition.Compare("errand_second_done", Scope.PLAYER,
						Condition.Op.EQ, Value.of(true)))), "halfway"),
				new Node.Arm(new Condition.Compare("errand_sent", Scope.PLAYER,
					Condition.Op.EQ, Value.of(true)), "waiting")),
				"asking"))

			.add(new Node.Line("asking", "", "Speak to both of them, then come back to me.",
				Presentation.SUBTITLE, null, "sent"))
			// Written down before the conversation can end, so that leaving mid-sentence
			// is not the difference between having been asked and not.
			.add(new Node.Set("sent", "errand_sent", Scope.PLAYER, Value.of(true), "end"))

			.add(new Node.Line("waiting", "", "Neither of them yet? Off you go.",
				Presentation.SUBTITLE, null, "end"))
			.add(new Node.Line("halfway", "", "One down. There is still the other.",
				Presentation.SUBTITLE, null, "end"))
			.add(new Node.Line("done", "", "Both of them. Good — now, where were we.",
				Presentation.SUBTITLE, null, "end"))
			.add(new Node.End("end"))
			.build();
	}

	/** The first of the two to be visited. The second is the same with one name changed. */
	private static Dialogue errandFirst() {
		return errandStop("errand_first", "errand_first_done");
	}

	private static Dialogue errandSecond() {
		return errandStop("errand_second", "errand_second_done");
	}

	/**
	 * One of the two people the errand sends you to.
	 *
	 * Written once for both, because they differ by a name. That is worth doing here
	 * and worth <em>not</em> doing in the editor: two graphs that look alike are two
	 * graphs somebody can edit apart, and this pair is meant to be edited apart — they
	 * are different people saying different things.
	 */
	private static Dialogue errandStop(String id, String flag) {
		return Dialogue.builder(id)
			.variable("errand_sent", "flag")
			.variable("errand_first_done", "flag")
			.variable("errand_second_done", "flag")
			.start("expected?")
			// Nothing to say until the first one has sent you. Without this arm, walking
			// up to these two before the errand exists gives away the scene.
			.add(new Node.Branch("expected?", List.of(
				new Node.Arm(new Condition.Compare(flag, Scope.PLAYER,
					Condition.Op.EQ, Value.of(true)), "again"),
				new Node.Arm(new Condition.Compare("errand_sent", Scope.PLAYER,
					Condition.Op.EQ, Value.of(true)), "expecting")),
				"stranger"))

			.add(new Node.Line("stranger", "", "Mm.", Presentation.SUBTITLE, null, "end"))
			.add(new Node.Line("expecting", "", "Ah — you were sent. Here is what I know.",
				Presentation.SUBTITLE, null, "told"))
			.add(new Node.Set("told", flag, Scope.PLAYER, Value.of(true), "end"))
			.add(new Node.Line("again", "", "I have told you what I know.",
				Presentation.SUBTITLE, null, "end"))
			.add(new Node.End("end"))
			.build();
	}

	/**
	 * Two characters with this brain will fight each other.
	 *
	 * <h2>Two layers in one document, and why they are apart</h2>
	 *
	 * The document holds a <b>skill</b> — "how to fight whoever I was told about" —
	 * and a <b>scenario</b> that decides who that is and when to stop. They are
	 * written against different things on purpose: the skill knows only
	 * {@code target}, and the scenario is the only part that has ever heard of
	 * {@code kin}.
	 *
	 * That is what makes the skill worth having. The same nodes, called with a
	 * different target, are a brawl, a guard turning on an intruder, or a bodyguard
	 * defending somebody — and none of those needs the fighting rewritten. Put the
	 * other way round: <b>the brain knows how, and the scenario decides what this
	 * is.</b>
	 *
	 * <h2>They run alongside each other</h2>
	 *
	 * The call does not wait. The scenario keeps its own turn every tick and can
	 * stop the fight whenever it likes, which is what lets it notice that the
	 * other one has gone. A call that waited would mean the scenario was inside
	 * the fight and could not look up until it ended.
	 *
	 * When both order the body in one tick the skill wins, because the skill is
	 * the specialist and the scenario is what chose it.
	 *
	 * <h2>What to expect</h2>
	 *
	 * Empty hands work: they walk up to each other and punch. A bow makes them
	 * shoot from a distance and close when it gets short. A datapack gun reads as
	 * {@code swung}, which cannot be fired yet, so they close and hit with it —
	 * which is visibly the wrong thing and honestly so.
	 */
	private static Dialogue duel() {
		return Dialogue.builder("duel")
			.start("watch")
			.add(new Node.Until("watch",
				new Condition.Compare(Sense.KIN, Scope.SENSE, Condition.Op.EQ, Value.of(true)),
				"engage"))
			// Naming who. Everything the fighting does about "who" comes from here.
			.add(new Node.Do("engage", "fighting:fight", Mark.KIN, "holding"))
			// And the scenario's own job while it runs: notice when there is nobody
			// left to fight. This is the turn a waiting call would have taken away.
			.add(new Node.Until("holding",
				new Condition.Not(
					new Condition.Compare(Sense.KIN, Scope.SENSE, Condition.Op.EQ, Value.of(true))),
				"break"))
			.add(new Node.Stop("break", "fighting:fight", "fight.over"))
			// Standing easy again. A skill that is stopped cannot put its own guard
			// down — stopping it is not something it gets told about — so whoever
			// stopped it says so. That is the same bargain as the walk: an order goes
			// out, and undoing it is the caller's own business.
			.add(new Node.Act("fight.over", new Effect.Guard(false), "watch"))
			.build();
	}

	/**
	 * How to fight, and nothing about who.
	 *
	 * <h2>A library, not a character's graph</h2>
	 *
	 * It has no beginning, and that is what makes it one. Nobody is given this: a
	 * character is given a dialogue, and a dialogue calls in here by name —
	 * {@code fighting:fight}. Which is what a brain is, said properly. It used to
	 * be a second field on the character, and that was wrong twice over: a brain is
	 * not something you wear, and a character with two documents has two authors.
	 *
	 * <h2>Why splitting it is the whole point</h2>
	 *
	 * The nodes below know only {@code target} — never {@code kin}, never why. Call
	 * them with a different target and the same fighting is a brawl, a guard
	 * turning on an intruder, or a bodyguard defending somebody, and none of those
	 * needs the fighting rewritten.
	 *
	 * <b>The brain knows how; the dialogue decides what this is.</b>
	 *
	 * <h2>What to expect</h2>
	 *
	 * Empty hands work: they walk up to each other and punch. A bow makes them
	 * shoot from a distance and close when it gets short. A datapack gun reads as
	 * {@code swung}, which cannot be fired yet, so they close and hit with it —
	 * which is visibly the wrong thing and honestly so.
	 */
	private static Dialogue fighting() {
		return Dialogue.builder("fighting")
			.library()
			.segment("fight", "fight.on")

			// On guard for as long as this skill runs. It is the other half of the
			// wooden fight: phases in a blow read as nothing while the character
			// between blows stands the way she stands in a queue.
			.add(new Node.Act("fight.on", new Effect.Guard(true), "fight.look"))

			.add(new Node.Branch("fight.look", List.of(
				// A bow, and far enough off that it is the sensible thing. A bow at
				// arm's length is a club held by the wrong end.
				new Node.Arm(new Condition.All(List.of(
					new Condition.Compare(Sense.WEAPON, Scope.SENSE,
						Condition.Op.EQ, Value.of("drawn")),
					new Condition.Compare(Sense.TARGET_DISTANCE, Scope.SENSE,
						Condition.Op.GT, Value.of(5)))), "fight.aim"),
				// Close enough to hit, asked of the body rather than guessed at. The
				// guess was two and eight tenths while the body could reach three and
				// a half, and a blow's own knockback lands squarely in the gap: still
				// able to hit, and told to walk. That is why every exchange was one
				// blow followed by an approach.
				new Node.Arm(new Condition.Compare(Sense.IN_REACH, Scope.SENSE,
					Condition.Op.EQ, Value.of(true)), "fight.stop"),
				// Near, and closing at a walk. Running the last few blocks is what
				// made two of them orbit each other: the walk goes to within one and
				// a half blocks, which is inside the distance at which the game
				// pushes two bodies apart, so each shoved the other and set off
				// again. A fighter closes the last stride, she does not charge it.
				new Node.Arm(new Condition.Compare(Sense.TARGET_DISTANCE, Scope.SENSE,
					Condition.Op.LT, Value.of(7)), "fight.walk")),
				"fight.run"))

			.add(new Node.Act("fight.aim", new Effect.LookAt(Mark.TARGET), "fight.shoot"))
			.add(new Node.Act("fight.shoot", new Effect.Fire(Mark.TARGET), "fight.rest"))

			// Stopping first, or she walks through the person she is hitting and the
			// two of them shuffle across the floor together.
			.add(new Node.Act("fight.stop", new Effect.Halt(), "fight.face"))
			.add(new Node.Act("fight.face", new Effect.LookAt(Mark.TARGET), "fight.hit"))
			.add(new Node.Act("fight.hit", new Effect.Strike(Mark.TARGET), "fight.rest"))

			.add(new Node.Act("fight.walk", new Effect.WalkTo(Mark.TARGET, 0.45f), "fight.soon"))
			.add(new Node.Act("fight.run", new Effect.WalkTo(Mark.TARGET, 1f), "fight.later"))

			// How often she looks up, and why it is not one number.
			//
			// It was ten ticks for everything, and that is half a second — at a run,
			// two blocks. So she decided to close from four blocks away and next
			// looked up from inside her opponent. Every complaint about the fighting
			// was downstream of that: they circled, they rarely struck, and when they
			// did it was from a shove rather than from a step.
			//
			// Near, she looks up every other tick, which costs one comparison and
			// buys the difference between stopping at arm's length and running
			// through somebody. Far, six ticks is plenty: nothing decided at eight
			// blocks changes in a third of a second.
			.add(new Node.Every("fight.soon", 2, "fight.look"))
			.add(new Node.Every("fight.later", 6, "fight.look"))
			// After a blow. Not a rhythm — she is committed for the length of her own
			// swing anyway, and this is only how soon she asks again once it is over.
			// Short, because a fighter still in reach should press rather than
			// wait: the chain is three different blows and it only ever gets to the
			// second if the first is followed up inside a second.
			.add(new Node.Every("fight.rest", 3, "fight.look"))
			.build();
	}

	/**
	 * A behaviour graph that shoots at you, written entirely as nodes.
	 *
	 * <h2>What it is proving</h2>
	 *
	 * That the brain has verbs. Every decision in it — whether that is worth
	 * shooting at, how long to wait between shots, when to stop — is in the graph
	 * and none of it is in java. The java below it is routes, necks and
	 * bowstrings, which are physics and which no block was ever going to bend.
	 *
	 * She watches whatever she notices. When she has seen — not merely heard —
	 * something within twenty blocks, she shoots at it, waits a second and a half,
	 * and looks again. Losing sight of it sends her back to watching.
	 *
	 * <h2>Seen, not heard, and why that is the whole test</h2>
	 *
	 * A character who fires at noises shoots through walls at a pig. The
	 * distinction was built into perception long before there was a graph to use
	 * it, and this is the first time anything has actually asked.
	 *
	 * Give her a bow and either arrows in the off hand or endless ammunition, and
	 * turn watchfulness on — all three on the character panel.
	 *
	 * The weapon is checked rather than assumed. A graph that fires whatever is in
	 * the hand is a graph that draws a sword back and lets go of it, and from
	 * outside that is a character standing still for no stated reason.
	 */
	private static Dialogue sentry() {
		return Dialogue.builder("sentry")
			.start("watch")
			// Nothing is worth doing until she has actually seen something. Standing
			// here costs one question a tick, which is what watching costs.
			.add(new Node.Until("watch",
				new Condition.All(List.of(
					// A bow, or anything that behaves like one. Without this she draws
					// a sword back and lets go of it, which is not a thing that
					// happens - and from outside it is a character standing still
					// with no reason given.
					new Condition.Compare(Sense.WEAPON, Scope.SENSE,
						Condition.Op.EQ, Value.of("drawn")),
					new Condition.Compare(Sense.LEAD_SEEN, Scope.SENSE,
						Condition.Op.EQ, Value.of(true)),
					new Condition.Compare(Sense.LEAD_DISTANCE, Scope.SENSE,
						Condition.Op.LT, Value.of(20)))),
				"face"))
			.add(new Node.Act("face", new Effect.LookAt(Mark.LEAD), "shoot"))
			.add(new Node.Act("shoot", new Effect.Fire(Mark.LEAD), "between"))
			// Long enough to draw, loose and lower the bow before the next one. A
			// shorter gap does not fire faster — the weapon has its own mind about
			// that — it only makes her order shots she cannot take yet.
			.add(new Node.Every("between", 30, "still?"))
			.add(new Node.Branch("still?", List.of(new Node.Arm(
				new Condition.Compare(Sense.LEAD_SEEN, Scope.SENSE,
					Condition.Op.EQ, Value.of(true)), "shoot")), "lower"))
			.add(new Node.Act("lower", new Effect.LookAt(Mark.NOTHING), "watch"))
			.build();
	}

	/**
	 * A behaviour graph that perceives and remembers.
	 *
	 * <h2>What it demonstrates, and why in this order</h2>
	 *
	 * She waits until somebody is within four blocks, then greets them — once.
	 * After that she nods instead, for as long as that person stays; when they
	 * walk away past eight blocks she forgets, and the next arrival is a stranger
	 * again.
	 *
	 * Every piece of the step is in that sentence. {@code player.distance} is a
	 * reading she cannot write. {@code greeted} is a variable of her own that
	 * outlives a restart. And the forgetting is the part worth watching: without
	 * it she would greet the first person who ever came near and nod at everybody
	 * afterwards, for ever, which is exactly what a character with memory and no
	 * way to let go of it does.
	 *
	 * The hysteresis is deliberate too — greet at four, forget at eight. Standing
	 * on the line at a single threshold makes her greet, forget, greet, forget as
	 * you shift your weight.
	 */
	private static Dialogue doorman() {
		return Dialogue.builder("doorman")
			.variable("greeted", "flag")
			.start("waiting")
			.add(new Node.Until("waiting",
				new Condition.Compare(Sense.PLAYER_DISTANCE, Scope.SENSE,
					Condition.Op.LT, Value.of(4)), "known?"))
			.add(new Node.Branch("known?", List.of(new Node.Arm(
				new Condition.Compare("greeted", Scope.CHARACTER, Condition.Op.EQ, Value.of(true)),
				"nod")), "greet"))
			.add(new Node.Act("greet", new Effect.PlayAnimation("wave", 40), "learn"))
			.add(new Node.Set("learn", "greeted", Scope.CHARACTER, Value.of(true), "settle"))
			.add(new Node.Act("nod", new Effect.PlayAnimation("nod", 30), "settle"))
			// Long enough that she is not waving continuously at somebody standing
			// in front of her, short enough that she notices them leaving.
			.add(new Node.Every("settle", 40, "gone?"))
			.add(new Node.Until("gone?",
				new Condition.Compare(Sense.PLAYER_DISTANCE, Scope.SENSE,
					Condition.Op.GT, Value.of(8)), "forget"))
			.add(new Node.Set("forget", "greeted", Scope.CHARACTER, Value.of(false), "waiting"))
			.build();
	}

	/**
	 * A behaviour graph, to prove there is now such a thing.
	 *
	 * <h2>What it is for</h2>
	 *
	 * The same job as {@link #example()}: something to reach for to check the
	 * thing works at all, without writing a datapack first. It uses both of the
	 * new occasions and nothing else, so what it demonstrates is exactly what was
	 * built.
	 *
	 * She stands doing nothing until she is holding a torch, waves when she is,
	 * then waits three seconds and looks again. Put a torch in her hand with
	 * {@code /npc arm} and she starts; take it away and she stops. The waiting is
	 * the point: the first is a question asked every tick, the second is a timer,
	 * and between them they are every occasion a character needs.
	 */
	private static Dialogue torchbearer() {
		return Dialogue.builder("torchbearer")
			.start("dark")
			.add(new Node.Until("dark", new Condition.HasItem("minecraft:torch", 1), "wave"))
			.add(new Node.Act("wave", new Effect.PlayAnimation("wave", 40), "again"))
			.add(new Node.Every("again", 60, "dark"))
			.build();
	}

	/**
	 * Takes the dialogues a datapack reload produced.
	 *
	 * Everything from packs is thrown away first, so a dialogue deleted from a
	 * datapack really disappears instead of lingering until the server restarts —
	 * a writer testing a rename would otherwise be talking to a ghost.
	 *
	 * The built-in example survives, because it is not from a pack and is what
	 * someone reaches for to check the mod works at all.
	 */
	public static void replaceLoaded(Map<Identifier, Dialogue> loaded) {
		DIALOGUES.clear();
		registerBuiltIn();

		int accepted = 0;
		for (Map.Entry<Identifier, Dialogue> entry : loaded.entrySet()) {
			String id = entry.getKey().toString();
			Dialogue dialogue = entry.getValue();
			// The file's own path is the name. Trusting the id inside the file would
			// let two dialogues claim one name, and the loser would vanish silently.
			Dialogue named = new Dialogue(id, dialogue.formatVersion(), dialogue.start(),
				dialogue.nodes(), dialogue.variableTypes());
			if (register(named)) accepted++;
		}
		DialogueLoader.report(accepted);
	}

	/**
	 * One conversation that exercises the parts worth seeing work.
	 *
	 * It remembers whether you have been here, offers an option only to someone
	 * carrying emeralds, and ends differently depending on the answer — so a
	 * single walk through it shows variables, conditions and branching at once.
	 */
	private static Dialogue example() {
		return Dialogue.builder("example")
			.variable("met", "flag")
			.start("check")
			.add(new Node.Branch("check", List.of(new Node.Arm(
				new Condition.Compare("met", Scope.PLAYER, Condition.Op.EQ, Value.of(true)), "again")),
				"first"))
			.add(new Node.Line("first", "Villager", "You are new here.", Presentation.SUBTITLE, null, "remember"))
			.add(new Node.Set("remember", "met", Scope.PLAYER, Value.of(true), "menu"))
			.add(new Node.Line("again", "Villager", "Back so soon?", Presentation.SUBTITLE, null, "menu"))
			.add(new Node.Choice("menu", "What do you want?", List.of(
				new Node.Option("Trade an emerald.", "green",
					new Condition.HasItem("minecraft:emerald", 1), "trade"),
				new Node.Option("Nothing, sorry.", null, new Condition.Always(), "bye"))))
			// Taking the emerald is a step of its own. `has_item` on the option is
			// only a condition — it decides whether the offer is shown, and does not
			// touch the inventory. Leaving it at that gave away bread for nothing,
			// which is the sort of mistake a map maker will make constantly: the
			// condition reads like it does the trade.
			.add(new Node.Act("trade", new Effect.TakeItem("minecraft:emerald", 1), "paid"))
			.add(new Node.Act("paid", new Effect.GiveItem("minecraft:bread", 3), "traded"))
			.add(new Node.Line("traded", "Villager", "Bread for an emerald. Fair enough.",
				Presentation.SUBTITLE, null, "end"))
			.add(new Node.Line("bye", "Villager", "Then stop blocking the road.",
				Presentation.SUBTITLE, null, "end"))
			.add(new Node.End("end"))
			.build();
	}
}
