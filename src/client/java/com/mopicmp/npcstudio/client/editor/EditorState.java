package com.mopicmp.npcstudio.client.editor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mopicmp.npcstudio.dialogue.Dialogue;
import com.mopicmp.npcstudio.dialogue.DialogueValidator;
import com.mopicmp.npcstudio.dialogue.Node;
import com.mopicmp.npcstudio.dialogue.codec.DialogueCodecs;

/**
 * The dialogue being edited, on the client.
 *
 * Held as a plain mutable list of nodes rather than as a {@link Dialogue},
 * because a dialogue is immutable and correct by construction while something
 * half-edited is neither. Turning it back into one happens on save, which is
 * also where it gets checked.
 *
 * The validator runs on every change, not only on save. Someone building a
 * conversation should see "menu leads to repair, which does not exist" while
 * they are still looking at the node they broke, not after they have moved on
 * and forgotten it.
 */
public final class EditorState {

	private String id;
	private String start;
	private final List<Node> nodes = new ArrayList<>();
	private final Map<String, String> variables = new LinkedHashMap<>();

	/**
	 * The named ways into this document: the skills it holds.
	 *
	 * Carried through the editor rather than only through the file. Left out, a
	 * brain opened here and saved would come back with every skill gone — the
	 * nodes still present and nothing able to reach them — and nobody would find
	 * out until a character stopped fighting.
	 */
	private final Map<String, String> segments = new LinkedHashMap<>();

	/**
	 * The boxes drawn on the map for this document.
	 *
	 * Carried through the editor for exactly the reason the skills above are, and
	 * the note there is the whole argument: a document opened here and saved without
	 * them would come back with the boxes gone, the nodes still naming them, and
	 * nothing broken enough to notice until a trap failed to spring.
	 */
	private final Map<String, com.mopicmp.npcstudio.dialogue.Area> areas = new LinkedHashMap<>();

	private List<String> otherNames = List.of();

	public static EditorState from(String json, List<String> otherNames) {
		Dialogue dialogue = DialogueCodecs.DIALOGUE
			.parse(JsonOps.INSTANCE, JsonParser.parseString(json))
			.getOrThrow(message -> new IllegalStateException("the server sent a dialogue we cannot read: " + message));

		EditorState state = new EditorState();
		state.id = dialogue.id();
		state.start = dialogue.start();
		state.nodes.addAll(dialogue.nodes().values());
		state.variables.putAll(dialogue.variableTypes());
		state.segments.putAll(dialogue.segments());
		state.areas.putAll(dialogue.areas());
		state.manner = dialogue.manner();
		state.places.putAll(dialogue.places());
		state.triggers.addAll(dialogue.triggers());
		state.voices.putAll(dialogue.voices());
		state.kind = dialogue.kind();
		state.location = dialogue.location();
		state.within = dialogue.within();
		state.standing.addAll(dialogue.standing());
		state.gauges.addAll(dialogue.gauges());
		state.otherNames = otherNames;
		return state;
	}

	public String id() { return id; }

	public void id(String value) { id = value; }

	public String start() { return start; }

	/**
	 * Says where a conversation begins — and refuses to, for a document nobody carries.
	 *
	 * <h2>Why the refusal is here and not in the button</h2>
	 *
	 * Because it is an invariant of the document rather than a fact about one control. A
	 * place, a player or a thing runs through its triggers; the validator refuses a start
	 * on one outright, and a refusal at save time is a document that cannot be saved at
	 * all. Which is exactly what happened: the node panel offered "make this the start" on
	 * every node of every document, one press on a location turned it unsaveable, and the
	 * only clue was a red line about a starting node the author had never asked for.
	 *
	 * The panel does not offer it any more either — it offers a way in, which is what those
	 * documents actually need. Both, because a rule enforced in one place is a rule the
	 * next control gets wrong.
	 */
	public void start(String value) {
		if (kind != com.mopicmp.npcstudio.dialogue.Dialogue.Kind.SCENE) return;
		start = value;
	}

	public List<Node> nodes() { return nodes; }

	public Map<String, String> variables() { return variables; }

	/**
	 * Declares a variable, or takes a declaration away.
	 *
	 * <h2>Why the editor could not do this until now</h2>
	 *
	 * It carried the declarations through a save faithfully and offered no way to make
	 * one. So a graph that used a variable was refused by the validator with a red line
	 * naming the fault, and there was no door anywhere leading to the fix — reported as
	 * "why does it say the variable is not declared, I do not understand how to work
	 * with this", which is the only reasonable thing to say about it.
	 */
	public void variable(String name, String type) {
		if (name == null || name.isEmpty()) return;
		if (type == null || type.isEmpty()) {
			variables.remove(name);
			return;
		}
		// Refuse a type the engine has no default for rather than storing it: a
		// declaration the loader will throw on is worse than no declaration, because it
		// takes the whole document down instead of one node.
		try {
			com.mopicmp.npcstudio.dialogue.Value.defaultFor(type);
		} catch (IllegalArgumentException unknown) {
			return;
		}
		variables.put(name, type);
	}

	/** The skills this document holds, name to the node they begin at. */
	public Map<String, String> segments() { return segments; }

	/**
	 * Names a skill at a node, or takes the name off it.
	 *
	 * Taking it off does not delete the nodes. A skill nobody calls is still a
	 * piece of work somebody did, and losing it to a mis-click would be the sort
	 * of thing people stop using an editor over.
	 */
	public void segment(String name, String at) {
		if (at == null) segments.remove(name);
		else segments.put(name, at);
	}

	/** The boxes this document has drawn, in the order they were drawn. */
	public Map<String, com.mopicmp.npcstudio.dialogue.Area> areas() { return areas; }

	/**
	 * How this document asks to be shown.
	 *
	 * A whole record rather than seven fields, because it is changed a field at a
	 * time through its own withers and saved as one thing — and because the editor
	 * has no business knowing how many settings there are.
	 */
	private com.mopicmp.npcstudio.dialogue.Manner manner =
		com.mopicmp.npcstudio.dialogue.Manner.ORDINARY;

	public com.mopicmp.npcstudio.dialogue.Manner manner() { return manner; }

	/**
	 * What this document is for, and therefore who its threads are about.
	 *
	 * Carried through the editor for the reason the skills, boxes and triggers are: a
	 * document opened here and saved without it would come back a conversation, its
	 * rules still written, and nothing broken enough to notice until a character was
	 * offered a set of rules to carry.
	 */
	private com.mopicmp.npcstudio.dialogue.Dialogue.Kind kind =
		com.mopicmp.npcstudio.dialogue.Dialogue.Kind.SCENE;

	public com.mopicmp.npcstudio.dialogue.Dialogue.Kind kind() { return kind; }

	/**
	 * What a document of kind {@code LOCATION} says about its own ground, and where
	 * this document lives.
	 *
	 * Carried for the same reason as everything above it, and this pair would be worse
	 * to lose than most: a location saved without its own settings comes back a place
	 * with no ground, and a document saved without {@code within} comes back visible
	 * from the whole map — which is the one thing the author asked locations not to be.
	 */
	private java.util.Optional<com.mopicmp.npcstudio.dialogue.Location> location =
		java.util.Optional.empty();

	private String within = "";

	public java.util.Optional<com.mopicmp.npcstudio.dialogue.Location> location() {
		return location;
	}

	public String within() { return within; }

	public void within(String now) { within = now == null ? "" : now; }

	/**
	 * Puts the location's settings in place, or takes them away.
	 *
	 * Taking them away is what changing the kind back to a conversation does, because a
	 * document carrying a place's settings while not being a place is refused by the
	 * validator — and a refusal holds the save back, which would leave somebody who
	 * merely changed their mind unable to write the file at all.
	 */
	public void location(com.mopicmp.npcstudio.dialogue.Location now) {
		location = java.util.Optional.ofNullable(now);
	}

	/**
	 * Properties that are simply true while something holds.
	 *
	 * Carried through the editor for the reason everything else here is — and with a
	 * sharper edge than most: lost on a save, an ability does not merely stop being
	 * editable, it goes <em>off</em>, for everybody who had it, because the sweep takes
	 * away every derived property no live rule names.
	 */
	private final List<com.mopicmp.npcstudio.dialogue.Standing> standing = new ArrayList<>();

	public List<com.mopicmp.npcstudio.dialogue.Standing> standing() { return standing; }

	/** The property under a name, or null. */
	public com.mopicmp.npcstudio.dialogue.Standing standingNamed(String name) {
		for (var rule : standing) {
			if (rule.name().equals(name)) return rule;
		}
		return null;
	}

	/** Puts a property in place of whatever had the same name, or takes it away. */
	public void standing(String name, com.mopicmp.npcstudio.dialogue.Standing now) {
		standing.removeIf(rule -> rule.name().equals(name));
		if (now != null) standing.add(now);
	}

	/** A name no property of this document has taken. */
	/**
	 * What the player is shown of what they carry.
	 *
	 * Carried through the editor for the reason everything else here is: lost on a save,
	 * a stamina bar would go off the screen while the variable behind it went on
	 * deciding things — which is worse than losing both, because then nothing says why.
	 */
	private final List<com.mopicmp.npcstudio.dialogue.Gauge> gauges = new ArrayList<>();

	public List<com.mopicmp.npcstudio.dialogue.Gauge> gauges() { return gauges; }

	public com.mopicmp.npcstudio.dialogue.Gauge gaugeNamed(String label) {
		for (var gauge : gauges) {
			if (gauge.label().equals(label)) return gauge;
		}
		return null;
	}

	/** Puts a gauge in place of whatever had the same label, or takes it away. */
	public void gauge(String label, com.mopicmp.npcstudio.dialogue.Gauge now) {
		gauges.removeIf(gauge -> gauge.label().equals(label));
		if (now != null) gauges.add(now);
	}

	/** A label no gauge of this document has taken. */
	public String freshGauge() {
		for (int i = 1; i < 1000; i++) {
			String tried = "gauge" + i;
			if (gaugeNamed(tried) == null) return tried;
		}
		return "gauge";
	}

	public String freshStanding() {
		for (int i = 1; i < 1000; i++) {
			String tried = "property" + i;
			if (standingNamed(tried) == null) return tried;
		}
		return "property";
	}

	/**
	 * Changes what the document is for.
	 *
	 * The start goes with it in one direction only. A set of rules is carried by
	 * nobody, so a beginning left on one is a beginning nothing can reach — the
	 * validator refuses it, and refusing somebody's save because of a field they did
	 * not touch is worse than clearing the field they just made meaningless. Going the
	 * other way nothing is put back, because there is nothing to put back to.
	 */
	public void kind(com.mopicmp.npcstudio.dialogue.Dialogue.Kind now) {
		kind = now == null ? com.mopicmp.npcstudio.dialogue.Dialogue.Kind.SCENE : now;
		if (kind != com.mopicmp.npcstudio.dialogue.Dialogue.Kind.SCENE) start = "";
		// The settings follow the kind, in both directions, because the validator refuses
		// a document where the two disagree — and a refusal holds the save back. Left to
		// the author, changing this one field would produce a file that cannot be
		// written, over a page they have not been shown yet.
		if (kind == com.mopicmp.npcstudio.dialogue.Dialogue.Kind.LOCATION) {
			if (location.isEmpty()) {
				location = java.util.Optional.of(
					com.mopicmp.npcstudio.dialogue.Location.of(""));
			}
			// A place cannot live inside a place, and saying so here is kinder than
			// saying so afterwards in red.
			within = "";
		} else {
			location = java.util.Optional.empty();
		}
	}

	/**
	 * Where each node sits on the canvas, as the document last had it.
	 *
	 * Held here rather than on the screen because it is part of the document, and the
	 * screen is a thing that opens and closes. It was on the screen, in two maps that
	 * were thrown away with it — which is why a graph dragged into a shape came back
	 * in a straight line.
	 */
	private final Map<String, com.mopicmp.npcstudio.dialogue.Pin> places = new LinkedHashMap<>();

	public Map<String, com.mopicmp.npcstudio.dialogue.Pin> places() { return places; }

	/**
	 * Which box starts which of this document's named ways in.
	 *
	 * Carried through the editor for the reason the skills and the boxes are: a
	 * document opened here and saved without it would come back with its triggers
	 * gone, the boxes still drawn, and nothing broken enough to notice until somebody
	 * walked into a room and nothing happened.
	 */
	private final List<com.mopicmp.npcstudio.dialogue.Trigger> triggers = new ArrayList<>();

	public List<com.mopicmp.npcstudio.dialogue.Trigger> triggers() { return triggers; }

	/** The trigger about a thing, or null — for the pages that edit one at a time. */
	public com.mopicmp.npcstudio.dialogue.Trigger triggerAbout(String what) {
		for (var trigger : triggers) {
			if (trigger.about().equals(what)) return trigger;
		}
		return null;
	}

	/** Puts a trigger in place of whatever was about the same thing, or takes it away. */
	private void putTrigger(String what, com.mopicmp.npcstudio.dialogue.Trigger now) {
		triggers.removeIf(trigger -> trigger.about().equals(what));
		if (now != null) triggers.add(now);
	}

	/**
	 * What colour each speaker's name is drawn in.
	 *
	 * Carried through the editor for the reason the skills, the boxes and the
	 * triggers are: a document opened here and saved without it would come back with
	 * every colour gone and nothing broken enough to notice until somebody read a
	 * scene and found everyone speaking in the same blue.
	 */
	private final Map<String, String> voices = new LinkedHashMap<>();

	public Map<String, String> voices() { return voices; }

	/**
	 * Colours a name, or puts it back to the usual.
	 *
	 * An empty colour removes the entry rather than storing an empty string, so that
	 * "nobody has said" and "somebody said the default" are the same state. Two ways
	 * of writing one fact is how a saved file and a fresh one stop matching.
	 */
	public void voice(String speaker, String colour) {
		if (speaker == null || speaker.isEmpty()) return;
		if (colour == null || colour.isEmpty()) voices.remove(speaker);
		else voices.put(speaker, colour);
	}

	/**
	 * Binds a box to a way in, or unbinds it when the segment is empty.
	 *
	 * Rebinding keeps how often it fires. Somebody changing which scene a doorway
	 * starts has not said anything about whether it starts twice, and quietly putting
	 * that back to the default is the kind of loss nobody sees until the room greets
	 * them again on the way out.
	 */
	public void trigger(String area, String segment) {
		if (area == null || area.isEmpty()) return;
		if (segment == null || segment.isEmpty()) {
			putTrigger(area, null);
			return;
		}
		var was = triggerAbout(area);
		putTrigger(area, com.mopicmp.npcstudio.dialogue.Trigger.of(area, segment)
			.asking(was == null
				? com.mopicmp.npcstudio.dialogue.Trigger.Again.EVERY_TIME : was.again()));
	}

	/**
	 * Sets a marked place watching a block, or answering a click on one.
	 *
	 * Both are standing rules that start a way in without anybody clicking a character.
	 * The only difference is what sets them off — the block becoming something, or
	 * somebody using it — and the second is what lets a graph give the player a rule the
	 * game has not got.
	 */
	public void watch(String place, com.mopicmp.npcstudio.dialogue.Trigger.Cause cause,
			String segment) {
		if (place == null || place.isEmpty()) return;
		if (segment == null || segment.isEmpty()) {
			putTrigger(place, null);
			return;
		}
		var was = triggerAbout(place);
		boolean rule = cause instanceof com.mopicmp.npcstudio.dialogue.Trigger.Cause.Used
			|| cause instanceof com.mopicmp.npcstudio.dialogue.Trigger.Cause.UsedAny;
		putTrigger(place, new com.mopicmp.npcstudio.dialogue.Trigger(cause,
			// Who it counts for is kept across a rebinding, the same way "how often" is:
			// changing which scene a fire starts says nothing about whose fire it is.
			was == null ? new com.mopicmp.npcstudio.dialogue.Condition.Always() : was.who(),
			segment,
			was == null ? com.mopicmp.npcstudio.dialogue.Trigger.Again.EVERY_TIME : was.again(),
			// A rule by default when it answers a click, a scene when it waits. Either
			// can be turned round; neither should have to be, to write the ordinary thing.
			was != null && was.onUse() == rule ? was.manner()
				: rule ? com.mopicmp.npcstudio.dialogue.Trigger.Manner.RULE
					: com.mopicmp.npcstudio.dialogue.Trigger.Manner.SCENE,
			was == null || was.onUse() != rule || was.instead()));
	}

	/**
	 * A block name no rule of this document has taken yet.
	 *
	 * A campfire, because it is the example everything about these rules was built for
	 * and because a working line to edit teaches more than an empty field does.
	 */
	public String freshKind() {
		for (int i = 1; i < 1000; i++) {
			String tried = i == 1 ? "minecraft:campfire" : "minecraft:campfire" + i;
			if (triggerAbout(tried) == null) return tried;
		}
		return "minecraft:campfire";
	}

	/** Whether a rule answers instead of the game, or beside it. */
	public void watchInstead(String place, boolean instead) {
		var was = triggerAbout(place);
		if (was == null) return;
		putTrigger(place, was.inManner(was.manner(), instead));
	}

	/** How often a trigger fires, for a thing that starts something. */
	public void triggerAgain(String what, com.mopicmp.npcstudio.dialogue.Trigger.Again again) {
		var was = triggerAbout(what);
		if (was == null) return;
		putTrigger(what, was.asking(again));
	}

	/**
	 * Writes down where the boxes are now.
	 *
	 * Given the whole arrangement at once rather than a node at a time, because that
	 * is how the canvas holds it and because a partial answer would leave the document
	 * describing a layout that half exists.
	 */
	public void places(Map<String, com.mopicmp.npcstudio.dialogue.Pin> now) {
		places.clear();
		if (now != null) places.putAll(now);
	}

	public void manner(com.mopicmp.npcstudio.dialogue.Manner now) {
		// Nothing is announced. The graph is saved by noticing that what it writes out
		// has changed, so anything that changes the document is noticed by the same
		// one mechanism — and a second way of saying "this changed" would be a second
		// thing to remember to call.
		manner = now == null ? com.mopicmp.npcstudio.dialogue.Manner.ORDINARY : now;
	}

	/**
	 * Draws a box under a name, or rubs one out.
	 *
	 * Rubbing out leaves the nodes that name it alone. They stop meaning anything
	 * until a box of that name exists again, which is a thing the editor can say out
	 * loud — and it is far better than the alternative, which is silently rewriting
	 * somebody's graph because they mis-clicked a cross.
	 */
	public void area(String name, com.mopicmp.npcstudio.dialogue.Area box) {
		if (name == null || name.isEmpty()) return;
		if (box == null) areas.remove(name);
		else areas.put(name, box);
	}

	/** A box name nothing else in this document is using. */
	public String freshArea() {
		for (int n = 1; ; n++) {
			String candidate = "area" + n;
			if (!areas.containsKey(candidate)) return candidate;
		}
	}

	public List<String> otherNames() { return otherNames; }

	/** Every node id in this dialogue, for the "goes to" pickers. */
	public List<String> nodeIds() {
		return nodes.stream().map(Node::id).toList();
	}

	public void replace(int index, Node node) {
		nodes.set(index, node);
	}

	public void add(Node node) {
		nodes.add(node);
	}

	/**
	 * Takes a node out, and everything that pointed at it by name.
	 *
	 * <h2>Why the pointers go too</h2>
	 *
	 * Because they cannot survive it and they are not the author's to keep. A start naming
	 * a node that is gone, or a way in naming one, is not a decision anybody made — it is
	 * the wreckage of a deletion, and the validator refuses to save while it is there.
	 *
	 * Seen exactly that way: a node was deleted and the editor went on reporting that the
	 * document's starting node "until1" could not be reached, about a node that no longer
	 * existed anywhere. Nothing in the window could clear it.
	 *
	 * A trigger whose way in has just gone goes with it. It is not the author's work
	 * either — a trigger pointing at nothing does nothing, and leaving it would only move
	 * the refusal one line down.
	 *
	 * Wires from other nodes are <em>not</em> touched, and that is the difference: a wire
	 * is a decision somebody made about where the graph goes, and the validator names the
	 * ones left dangling so they can be pointed somewhere on purpose.
	 */
	public void remove(int index) {
		if (index < 0 || index >= nodes.size()) return;
		String gone = nodes.get(index).id();
		nodes.remove(index);

		if (start.equals(gone)) start = "";
		List<String> orphaned = segments.entrySet().stream()
			.filter(way -> way.getValue().equals(gone))
			.map(Map.Entry::getKey)
			.toList();
		for (String way : orphaned) {
			segments.remove(way);
			triggers.removeIf(trigger -> trigger.wayIn().equals(way));
		}
	}

	/**
	 * A node id nothing else is using.
	 *
	 * Numbered by type, so a graph reads as "line3 goes to choice1" rather than
	 * as a list of anonymous numbers. Names can be changed afterwards; this only
	 * has to be unique and not meaningless.
	 */
	public String freshId(String prefix) {
		List<String> taken = nodeIds();
		for (int n = 1; ; n++) {
			String candidate = prefix + n;
			if (!taken.contains(candidate)) return candidate;
		}
	}

	public Dialogue build() {
		Map<String, Node> byId = new LinkedHashMap<>();
		for (Node node : nodes) byId.put(node.id(), node);
		return new Dialogue(id, Dialogue.CURRENT_FORMAT, start, byId, variables, segments,
			areas, manner, places, triggers, voices, kind, standing, gauges, location, within);
	}

	public String toJson() {
		return DialogueCodecs.DIALOGUE.encodeStart(JsonOps.INSTANCE, build())
			.getOrThrow(message -> new IllegalStateException(message))
			.toString();
	}

	/** What is wrong with it right now, in the order the validator found it. */
	public List<DialogueValidator.Problem> problems() {
		if (nodes.isEmpty()) return List.of();
		return DialogueValidator.validate(build()).problems();
	}
}
