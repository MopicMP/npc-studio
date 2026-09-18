package com.mopicmp.npcstudio.dialogue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A whole conversation: nodes, where it starts, and the variables it uses.
 *
 * Variables are declared rather than sprung into being on first write. It costs
 * the writer one line and buys the validator the ability to say "you compared
 * `gold` against a piece of text" instead of shrugging. Undeclared names are an
 * error, not a new variable — a typo in a name would otherwise create a second
 * variable that is always zero, and the dialogue would just quietly take the
 * wrong branch forever.
 *
 * `formatVersion` is here from the first commit on purpose. Dialogues live in
 * other people's world saves, the format will change, and a file that arrives
 * without a version number cannot be migrated — only guessed at.
 */
public record Dialogue(
		String id,
		int formatVersion,
		String start,
		Map<String, Node> nodes,
		Map<String, String> variableTypes,
		Map<String, String> segments,
		Map<String, Area> areas,
		Manner manner,
		Map<String, Pin> places,
		java.util.List<Trigger> triggers,
		Map<String, String> voices,
		Kind kind,
		java.util.List<Standing> standing,
		java.util.List<Gauge> gauges,
		java.util.Optional<Location> location,
		String within) {

	/**
	 * What this document is about, and therefore who its threads are.
	 *
	 * <h2>Why the document says it and not the thread</h2>
	 *
	 * A conversation says who it is about by being carried: a character holds the name
	 * of her dialogue, and a place holds the name of the box that starts one. A
	 * document about the player is carried by nobody — there is no NPC to hang it on
	 * and no box to walk into — so if the document does not say what it is, nothing
	 * does. It ends up filed under a conversation's name, where nobody will find it.
	 *
	 * <h2>What it does not do</h2>
	 *
	 * It is not a second language. The nodes, conditions, verbs, variables, editor and
	 * validator are all one and stay one; this answers what the document is for and
	 * when it is live, and nothing else. The day it starts meaning "and here the
	 * conditions are different" is the day this was a mistake.
	 */
	public enum Kind {
		/**
		 * A conversation, a place, or both: everything written before this field.
		 *
		 * Both on purpose. A document may hold what a character says when you click her
		 * <em>and</em> what happens when you walk into her room, as separate threads
		 * through the same nodes — so splitting those into two kinds would split
		 * something that is deliberately one thing.
		 */
		SCENE,

		/**
		 * Rules about the player, standing for as long as the map is loaded.
		 *
		 * Nobody carries one. It has no beginning, because there is nothing to click:
		 * it does what it does through its triggers, and a document of this kind with
		 * no triggers does nothing at all.
		 */
		PLAYER,

		/**
		 * Rules about a kind of thing rather than about a person.
		 *
		 * Its own kind rather than a corner of {@link #PLAYER}, because memory on a
		 * stack has to be a capability of something, and this is the something. That
		 * capability is not built yet — see docs/player-and-things.md — so today this
		 * differs from {@code PLAYER} only in how it is filed. The name is here now
		 * because a name in a file format is the expensive thing to change later.
		 */
		THING,

		/**
		 * A place you go to: ground that is laid out on the way in and thrown away on
		 * the way out.
		 *
		 * Its own kind for the same reason {@code PLAYER} is: nobody carries it. There
		 * is no character to hang it on and no box to walk into, because the box has not
		 * been built yet when the document is read — the document is what causes the
		 * ground to exist.
		 *
		 * What it is about lives in {@link Dialogue#location()}. Everything else it
		 * needs, it already had: what it grants is {@link Dialogue#standing()}, what
		 * happens on entry is a trigger, and whether a guest may change anything is a
		 * condition.
		 */
		LOCATION
	}

	/**
	 * A graph may have more than one way in.
	 *
	 * <h2>What a segment is for</h2>
	 *
	 * {@code start} is where the graph begins when it is somebody's own. A
	 * segment is a second, named beginning, meant to be called from elsewhere:
	 * "how to fight", "how to take cover", "how to look busy". The nodes and the
	 * declared variables are shared, so a document is a set of related skills
	 * rather than a pile of unrelated files.
	 *
	 * <h2>Why this and not a graph per skill</h2>
	 *
	 * Because skills come in families and want to share. Fighting and retreating
	 * read the same variables and lead into one another; kept as separate
	 * documents they would need those variables declared twice, in step, for ever.
	 *
	 * A document with no segments is an ordinary graph and always was — which is
	 * why every one written before this goes on working without a line changed.
	 */

	public static final int CURRENT_FORMAT = 1;

	/**
	 * As long as a dialogue may be called.
	 *
	 * The same allowance a named place gets. A name is typed into a small box in a
	 * corner and read back off a list, and both of those stop working at about the
	 * same length — so one number for both, rather than two that drift.
	 */
	public static final int LONGEST_NAME = 32;

	public Dialogue {
		// Not Map.copyOf: that returns an unordered map and throws away the order
		// the nodes were written in. It matters twice over — a writer opening their
		// own file wants their own order back, and without it saving a dialogue
		// twice produces two different files for no reason. The round-trip test
		// caught this; comparing maps for equality does not, since equality ignores
		// order.
		nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
		variableTypes = Collections.unmodifiableMap(new LinkedHashMap<>(variableTypes));
		segments = Collections.unmodifiableMap(new LinkedHashMap<>(segments));
		// And the boxes, in the order they were drawn, for the same reason as the
		// nodes: a list that reshuffles itself between openings is a list nobody can
		// find anything in twice.
		areas = Collections.unmodifiableMap(new LinkedHashMap<>(areas));
		// A document that says nothing about how it is shown is shown the way every
		// document was shown before any of this could be said. That is what makes this
		// field safe to add to a format other people already have maps in.
		manner = manner == null ? Manner.ORDINARY : manner;
		// And where the boxes were dragged to. Empty is not a document with its nodes
		// at the origin — it is a document nobody has arranged, which the editor lays
		// out for itself. That distinction is the whole of the field's meaning.
		places = Collections.unmodifiableMap(new LinkedHashMap<>(places == null ? Map.of() : places));
		triggers = java.util.List.copyOf(triggers == null ? java.util.List.of() : triggers);
		voices = Collections.unmodifiableMap(
			new LinkedHashMap<>(voices == null ? Map.of() : voices));
		// A document that does not say what it is, is a conversation — which is what
		// every document written before this field was, and has to go on being.
		kind = kind == null ? Kind.SCENE : kind;
		standing = java.util.List.copyOf(standing == null ? java.util.List.of() : standing);
		gauges = java.util.List.copyOf(gauges == null ? java.util.List.of() : gauges);
		// Only a LOCATION has one, and the validator is what says so. Empty here means
		// "this document is not a place", which is true of every document ever written
		// before this field and has to go on being true without anybody editing them.
		location = location == null ? java.util.Optional.empty() : location;
		// Where this document lives, and therefore who can see it.
		//
		// Empty is the map's own — which is every document that exists today, and the
		// answer that must never need writing down. A name is a location's, and then
		// this document is invisible from everywhere except inside it.
		//
		// The document says it, rather than the location keeping a list, for the reason
		// the kind is on the document too: a list can hold the same name twice and a
		// field cannot, so "which location is this in" has one answer by construction
		// instead of by a check somebody has to remember to run.
		within = within == null ? "" : within;
	}

	/** The fourteen-part form, from before a document could be somewhere. */
	public Dialogue(String id, int formatVersion, String start, Map<String, Node> nodes,
			Map<String, String> variableTypes, Map<String, String> segments,
			Map<String, Area> areas, Manner manner, Map<String, Pin> places,
			java.util.List<Trigger> triggers, Map<String, String> voices, Kind kind,
			java.util.List<Standing> standing, java.util.List<Gauge> gauges) {
		this(id, formatVersion, start, nodes, variableTypes, segments, areas, manner,
			places, triggers, voices, kind, standing, gauges,
			java.util.Optional.empty(), "");
	}

	/** Whether this document is a place rather than something that happens in one. */
	public boolean isLocation() {
		return kind == Kind.LOCATION;
	}

	/**
	 * Whether this document is visible to somebody standing where {@code where} says.
	 *
	 * Empty means the map's own documents, which is where an author works and what
	 * every document written before locations existed belongs to.
	 */
	public boolean visibleFrom(String where) {
		return within.equals(where == null ? "" : where);
	}

	/** The thirteen-part form, from before a document could show anything on screen. */
	public Dialogue(String id, int formatVersion, String start, Map<String, Node> nodes,
			Map<String, String> variableTypes, Map<String, String> segments,
			Map<String, Area> areas, Manner manner, Map<String, Pin> places,
			java.util.List<Trigger> triggers, Map<String, String> voices, Kind kind,
			java.util.List<Standing> standing) {
		this(id, formatVersion, start, nodes, variableTypes, segments, areas, manner,
			places, triggers, voices, kind, standing, java.util.List.of());
	}

	/** The eleven-part form, from before a document said what it was for. */
	public Dialogue(String id, int formatVersion, String start, Map<String, Node> nodes,
			Map<String, String> variableTypes, Map<String, String> segments,
			Map<String, Area> areas, Manner manner, Map<String, Pin> places,
			java.util.List<Trigger> triggers, Map<String, String> voices) {
		this(id, formatVersion, start, nodes, variableTypes, segments, areas, manner,
			places, triggers, voices, Kind.SCENE, java.util.List.of(), java.util.List.of());
	}

	/** The twelve-part form, from before a document could hold a standing property. */
	public Dialogue(String id, int formatVersion, String start, Map<String, Node> nodes,
			Map<String, String> variableTypes, Map<String, String> segments,
			Map<String, Area> areas, Manner manner, Map<String, Pin> places,
			java.util.List<Trigger> triggers, Map<String, String> voices, Kind kind) {
		this(id, formatVersion, start, nodes, variableTypes, segments, areas, manner,
			places, triggers, voices, kind, java.util.List.of(), java.util.List.of());
	}

	/** Whether nobody carries this: it stands on its own and works through its triggers. */
	public boolean isRules() {
		return kind != Kind.SCENE;
	}

	/**
	 * What colour each speaker's name is drawn in.
	 *
	 * <h2>Why the colour belongs to the name and not to the line</h2>
	 *
	 * Because it is a fact about a character, said once. Put on the line — which is
	 * where the text's own colour lives, and where it was asked for — it would have
	 * to be chosen again on every line she has, and the fiftieth would be a different
	 * yellow from the first. That is not a hypothetical: it is what happens to every
	 * per-line setting that describes somebody rather than something.
	 *
	 * A line may still overrule it; see {@link Node.Line#nameColour}. The rule is the
	 * one this whole editor keeps to — the document says what is usually true, and an
	 * author who says otherwise in one place outranks it, because they are looking at
	 * that place and meant it.
	 *
	 * Keyed by the name as typed, so a name nobody has coloured is simply absent and
	 * drawn the way names were drawn before any of this existed.
	 */
	public String voiceOf(String speaker) {
		String colour = voices.get(speaker);
		return colour == null ? "" : colour;
	}

	/** The nine-part form, for everything written before a document could be a place. */
	public Dialogue(String id, int formatVersion, String start,
			Map<String, Node> nodes, Map<String, String> variableTypes,
			Map<String, String> segments, Map<String, Area> areas, Manner manner,
			Map<String, Pin> places) {
		this(id, formatVersion, start, nodes, variableTypes, segments, areas, manner,
			places, java.util.List.of(), Map.of());
	}

	/** The ten-part form, from before a document knew what colour anybody's name is. */
	public Dialogue(String id, int formatVersion, String start,
			Map<String, Node> nodes, Map<String, String> variableTypes,
			Map<String, String> segments, Map<String, Area> areas, Manner manner,
			Map<String, Pin> places, java.util.List<Trigger> triggers) {
		this(id, formatVersion, start, nodes, variableTypes, segments, areas, manner,
			places, triggers, Map.of());
	}

	/**
	 * Whether anything in this document happens without somebody clicking a character.
	 *
	 * Asked of every loaded document, for every player, several times a second, so it
	 * is a field read rather than a search. Nearly every document answers no, and the
	 * ones that do are how the whole watch stays free.
	 */
	public boolean watchesGround() {
		return !triggers.isEmpty();
	}

	/**
	 * Which box starts which of this document's named ways in.
	 *
	 * <h2>What this makes a document</h2>
	 *
	 * A place as well as a conversation. One graph can hold what a character says when
	 * you click her <em>and</em> what happens when you walk into the room — as separate
	 * threads through the same nodes, sharing the same variables, saved in one file.
	 *
	 * <h2>Why it points at a segment rather than at a node</h2>
	 *
	 * Because a named way in is what a segment already is, and the alternative would be
	 * a second kind of entry point meaning the same thing. It also reads correctly on
	 * the canvas: the thing the box starts has a name, and that name is written beside
	 * the nodes it leads to.
	 *
	 * <h2>What it deliberately does not hold</h2>
	 *
	 * Whether it has fired <em>before</em>, in the sense the map cares about. "Once
	 * ever", "once a day", "once until she tells you otherwise" are the same question
	 * asked differently and would each need building in separately; written as a
	 * variable in the graph they are all one mechanism, which is where they belong.
	 *
	 * {@link Trigger.Again#ONCE_A_VISIT} is not that question and is not a small
	 * version of it. It is remembered for as long as the player is on the server and
	 * nowhere else — nothing reaches the save, nothing has to be migrated, and nothing
	 * has to be cleared by hand while a scene is being written and tried again. That
	 * is what makes it cheap enough to be a setting rather than a feature.
	 */

	/**
	 * The eight-part form, for everything written before the layout was kept.
	 *
	 * @deprecated only in the sense that nothing new should use it; every caller that
	 *             does is one written before there was a layout to pass, and passing
	 *             nothing is exactly what they mean.
	 */
	public Dialogue(String id, int formatVersion, String start,
			Map<String, Node> nodes, Map<String, String> variableTypes,
			Map<String, String> segments, Map<String, Area> areas, Manner manner) {
		this(id, formatVersion, start, nodes, variableTypes, segments, areas, manner,
			Map.of(), java.util.List.of());
	}

	/** Where a node sits on the canvas, or null for one nobody has placed. */
	public Pin place(String node) {
		return places.get(node);
	}

	/**
	 * The seven-part form, for everything written before the showing could be set.
	 *
	 * The same bargain the six-part one struck. There are four of these now, and that
	 * is not four ways of doing one thing: each marks a moment when the record grew,
	 * and each keeps every caller written before that moment working unchanged.
	 */
	public Dialogue(String id, int formatVersion, String start,
			Map<String, Node> nodes, Map<String, String> variableTypes,
			Map<String, String> segments, Map<String, Area> areas) {
		this(id, formatVersion, start, nodes, variableTypes, segments, areas,
			Manner.ORDINARY, Map.of(), java.util.List.of());
	}

	/**
	 * The six-part form, for everything written before boxes existed.
	 *
	 * The same bargain the five-part one struck and for the same reason: a graph with
	 * nothing drawn on the map is the ordinary case and will stay the ordinary case.
	 */
	public Dialogue(String id, int formatVersion, String start,
			Map<String, Node> nodes, Map<String, String> variableTypes,
			Map<String, String> segments) {
		this(id, formatVersion, start, nodes, variableTypes, segments, Map.of(),
			Manner.ORDINARY, Map.of(), java.util.List.of());
	}

	/**
	 * The five-part form, for everything written before segments existed.
	 *
	 * Not a convenience. A graph without segments is the ordinary case and will
	 * stay the ordinary case, and making every caller pass an empty map would be
	 * asking them all to say the same nothing.
	 */
	public Dialogue(String id, int formatVersion, String start,
			Map<String, Node> nodes, Map<String, String> variableTypes) {
		this(id, formatVersion, start, nodes, variableTypes, Map.of());
	}

	/** A box by name, or null when this document has no such box. */
	public Area area(String name) {
		return areas.get(name);
	}

	/**
	 * Every box anything in this graph names.
	 *
	 * The same question {@link #marksUsed} answers about places, asked for the same
	 * moment: the author deleting a box is the one person who can still fix the two
	 * nodes that name it, and the only time they can is before they have forgotten
	 * which two.
	 *
	 * Worse here than for places, in fact. A named place that is gone leaves a
	 * character walking nowhere, which is visible. A wall whose box is gone leaves a
	 * passage that never seals — a trap that silently does not happen, which is the
	 * hardest kind of broken to notice.
	 */
	public java.util.Set<String> areasUsed() {
		return areasUsed(nodes.values());
	}

	/**
	 * The same, over loose nodes rather than a finished document.
	 *
	 * Because the editor asks it too, and the editor has no document — it has a list
	 * of nodes it is still changing. Building one to ask a question about it would
	 * copy three maps a frame, and worse, it would be a second walk to keep in step
	 * with this one. A box counted differently in the panel from the file is a box
	 * somebody deletes believing nothing uses it.
	 */
	public static java.util.Set<String> areasUsed(Iterable<Node> nodes) {
		java.util.Set<String> found = new java.util.LinkedHashSet<>();
		for (Node node : nodes) {
			switch (node) {
				case Node.Act act -> {
					if (act.effect() instanceof Effect.Wall wall) found.add(wall.area());
				}
				case Node.Until until -> collectAreas(until.condition(), found);
				case Node.Branch branch -> {
					for (Node.Arm arm : branch.arms()) collectAreas(arm.condition(), found);
				}
				case Node.Choice choice -> {
					for (Node.Option option : choice.options()) {
						collectAreas(option.condition(), found);
					}
				}
				default -> { }
			}
		}
		return found;
	}

	private static void collectAreas(Condition condition, java.util.Set<String> into) {
		switch (condition) {
			case Condition.Inside(String area) -> into.add(area);
			case Condition.Not(Condition inner) -> collectAreas(inner, into);
			case Condition.All(java.util.List<Condition> parts) ->
				parts.forEach(part -> collectAreas(part, into));
			case Condition.Any(java.util.List<Condition> parts) ->
				parts.forEach(part -> collectAreas(part, into));
			default -> { }
		}
	}

	/** Where a named way in begins, or null if there is no such way in. */
	public String segment(String name) {
		return segments.get(name);
	}

	/**
	 * Whether anybody says anything in it.
	 *
	 * <h2>Why a graph has a sort at all</h2>
	 *
	 * One language, two runtimes, and each can serve only part of it: a
	 * conversation cannot stand and wait, and a brain has nobody to speak to.
	 * Both refuse when handed the wrong thing — but a refusal that arrives when a
	 * player clicks an NPC is a refusal nobody sees.
	 *
	 * Asked here, it can be used at the moment somebody is choosing, which is the
	 * moment they are looking. That was reported the hard way, twice: a behaviour
	 * graph put in the conversation field, two characters standing still, and
	 * nothing anywhere saying why.
	 */
	public boolean speaks() {
		return nodes().values().stream()
			.anyMatch(node -> node instanceof Node.Line || node instanceof Node.Choice);
	}

	/** Whether it ever stands still on its own account: a timer or a question. */
	public boolean waits() {
		return nodes().values().stream()
			.anyMatch(node -> node instanceof Node.Every || node instanceof Node.Until);
	}

	public Node node(String id) {
		return nodes.get(id);
	}

	/**
	 * Every mark anything in this graph is pointed at.
	 *
	 * <h2>What this is for</h2>
	 *
	 * Answering "who is using this?" about a named place before it is taken away.
	 * A place is referred to by name and by nothing else, so removing one leaves
	 * every graph that named it pointing at nothing — and pointing at nothing is
	 * deliberately not an error at run time, because a lead that is not there
	 * behaves the same way and every graph has to survive that.
	 *
	 * Which is right while a scene is playing and useless while a map is being
	 * built. The author taking the gate away is the one person who can still fix
	 * the three graphs that walk to it, and the only moment they can is before they
	 * have forgotten which three.
	 *
	 * Relative marks come back too. They cost nothing to include and leaving them
	 * out would make this a method about places, which is a narrower thing than the
	 * question it answers.
	 */
	public java.util.Set<String> marksUsed() {
		java.util.Set<String> found = new java.util.LinkedHashSet<>();
		for (Node node : nodes.values()) {
			// A call names the thing the skill is to act on, which is a mark like any
			// other and is the one people forget when they go looking by hand.
			if (node instanceof Node.Do call) found.add(call.target());
			if (!(node instanceof Node.Act act)) continue;
			switch (act.effect()) {
				case Effect.WalkTo(String at, float ignored) -> found.add(at);
				case Effect.LookAt(String at) -> found.add(at);
				case Effect.Fire(String at) -> found.add(at);
				case Effect.Strike(String at) -> found.add(at);
				// Where a block goes is a place like any other, and one that can be
				// spelled wrong like any other.
				case Effect.PutBlock(String at, String ignored) -> found.add(at);
				default -> { }
			}
		}
		// And the places conditions look at. A block test names a place exactly the way
		// a walk does, and leaving it out here meant the one warning that catches a
		// mistyped place name — see DialogueEditing.missingPlaces — stayed silent about
		// the whole half of the language that reads the world instead of walking into it.
		for (Node node : nodes.values()) {
			java.util.List<Condition> asked = switch (node) {
				case Node.Branch branch -> branch.arms().stream().map(Node.Arm::condition).toList();
				case Node.Choice choice -> choice.options().stream().map(Node.Option::condition).toList();
				case Node.Until until -> java.util.List.of(until.condition());
				default -> java.util.List.of();
			};
			for (Condition condition : asked) marksIn(condition, found);
		}
		// A trigger about a block at a place names that place, and a place name can be
		// spelled wrong like any other. A rule about a whole kind names none, which is
		// the point of it and is not a typo waiting to happen.
		for (Trigger trigger : triggers) {
			if (!trigger.place().isEmpty()) found.add(Mark.at(trigger.place()));
		}
		return found;
	}

	/** Every place a condition looks at, however deeply it is nested. */
	private static void marksIn(Condition condition, java.util.Set<String> found) {
		switch (condition) {
			case Condition.Block(String mark, String ignored) -> found.add(mark);
			case Condition.Not(Condition inner) -> marksIn(inner, found);
			case Condition.All(java.util.List<Condition> parts) ->
				parts.forEach(part -> marksIn(part, found));
			case Condition.Any(java.util.List<Condition> parts) ->
				parts.forEach(part -> marksIn(part, found));
			default -> { }
		}
	}

	/**
	 * Whether this document is one a character can be given.
	 *
	 * <h2>Why this is a method and not a comparison against null</h2>
	 *
	 * Because "no start" arrives written two ways — {@code null} from a document
	 * built in code, an empty string from one that has been through a file — and
	 * every place that checks only one of them is wrong for the other half of the
	 * documents in the world. That is not hypothetical: the first library the
	 * validator saw was refused with the message "no path from here ever reaches an
	 * end" pointing at a node whose name was the empty string, because one check
	 * asked {@code == null} and the empty start walked straight past it.
	 */
	public boolean hasStart() {
		return start != null && !start.isEmpty();
	}

	/**
	 * A document with no beginning: a set of named skills for others to call.
	 *
	 * This is what a brain is. Nobody carries one — a character carries a dialogue
	 * — and that dialogue calls in here by name.
	 */
	public boolean isLibrary() {
		return !hasStart();
	}

	public static Builder builder(String id) {
		return new Builder(id);
	}

	/** Assembling a dialogue in code, for tests and for the editor. */
	public static final class Builder {
		private final String id;
		private String start;
		private boolean library;
		private final Map<String, Node> nodes = new LinkedHashMap<>();
		private final Map<String, String> variables = new LinkedHashMap<>();
		private final Map<String, String> segments = new LinkedHashMap<>();
		private final Map<String, Area> areas = new LinkedHashMap<>();

		private Builder(String id) { this.id = id; }

		/** Names a box of the map, for a condition or a wall to point at. */
		public Builder area(String name, Area box) {
			areas.put(name, box);
			return this;
		}

		/** Names a way in, at the node the next {@link #add} will put there. */
		public Builder segment(String name, String at) {
			segments.put(name, at);
			return this;
		}

		public Builder start(String node) { this.start = node; return this; }

		/**
		 * Says this document has no beginning, because it is a set of skills.
		 *
		 * Said rather than inferred from "no start was given", because {@link #add}
		 * takes the first node as the start when nobody has said otherwise — which
		 * is right for everything somebody is writing as a graph, and would quietly
		 * turn a library into a graph whose first skill runs by itself.
		 */
		public Builder library() { this.library = true; return this; }

		public Builder variable(String name, String type) {
			// Fail here rather than at validation: a type that does not exist is a
			// mistake in the caller, not in the dialogue being described.
			Value.defaultFor(type);
			variables.put(name, type);
			return this;
		}

		public Builder add(Node node) {
			nodes.put(node.id(), node);
			if (start == null && !library) start = node.id();
			return this;
		}

		public Dialogue build() {
			return new Dialogue(id, CURRENT_FORMAT, library ? "" : start,
				nodes, variables, segments, areas);
		}
	}
}
