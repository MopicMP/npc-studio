package com.mopicmp.npcstudio.client.map;

import com.mopicmp.npcstudio.client.editor.EditorState;
import com.mopicmp.npcstudio.client.scene.Wireframe;
import com.mopicmp.npcstudio.client.workspace.Aim;
import com.mopicmp.npcstudio.client.workspace.Under;
import com.mopicmp.npcstudio.dialogue.Area;
import com.mopicmp.npcstudio.dialogue.Node;
import com.mopicmp.npcstudio.dialogue.Route;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * Drawing a walking route by pointing at the ground it goes over.
 *
 * <h2>Why this leaves the editor rather than living in it</h2>
 *
 * Because a route is a shape on a piece of ground, and the only way to say which
 * piece of ground is to look at it. Every alternative was tried on paper and each
 * one is the same mistake in a different costume: a list of coordinates typed into
 * boxes, a mini-map to click, a dropdown of named places to assemble in order.
 * All three ask somebody to describe a path instead of drawing one.
 *
 * It is also the ladder the rest of this mod is built on — world first, then block
 * programming, then the edges of the screen. A path has a place, and a thing with
 * a place is pointed at.
 *
 * <h2>Why there is no screen while it is on</h2>
 *
 * Because a route is longer than a room. Placing one means walking or flying along
 * it, and a screen is exactly the thing that stops the movement keys from reaching
 * the player. So the editing window gets out of the way entirely and the mouse
 * buttons are borrowed instead — which is the gesture already in everybody's hands
 * for putting things in a world, and needs no key anybody has to bind first.
 *
 * The borrowing is narrow on purpose: see {@code MinecraftMixin}, which gives the
 * buttons back the instant this is off, and lets escape through on the second
 * press so that no state of this can leave somebody unable to reach the menu.
 *
 * <h2>Why the points are written straight into the node</h2>
 *
 * Rather than being collected here and handed over at the end. The whole editor
 * works that way — a field edits the document as it is typed, and there is no
 * "done" to forget to press — and a route gathered in a side pocket would be the
 * one thing in it that can be lost by walking away.
 */
public final class Routing {

	private Routing() { }

	private static final int LINE = 0xFF9CCC65;
	private static final int HOME = 0xFFFFB74D;
	private static final int FIRST = 0xFF4FC3F7;
	private static final int UNDER_CURSOR = 0xFFFFCA28;
	private static final int LABEL = 0xFFDCEDC8;

	/** The box being drawn, and the other boxes of the same document behind it. */
	private static final int BOX = 0xFFBA68C8;
	private static final int OTHER_BOX = 0xFF5C4767;

	/** How far up a point's post goes. Head height and a little, so it clears a body. */
	private static final double POST = 1.6;

	/** Past this the number beside a point is not drawn: a field of them is noise. */
	private static final double NUMBERED_WITHIN = 48.0;

	/**
	 * How near a click has to be to take a point away rather than miss it.
	 *
	 * Two blocks, measured flat. Generous because removing is aimed at a marker
	 * rather than at a block — the post is what somebody is pointing at, and the
	 * block under it is a guess at which square of floor the post is standing on.
	 */
	private static final double REMOVES_WITHIN = 2.0;

	private static EditorState document;
	private static String nodeId = "";

	/**
	 * Whether the workspace stayed up behind this.
	 *
	 * Two ways in, because they are two different jobs. Sent into the world you walk
	 * the route yourself, which is how anybody knows whether it can be walked at all.
	 * Left in the workspace you fly its camera over the ground, which is how a long
	 * one is laid out without going round it first. Neither replaces the other and
	 * both were asked for.
	 */
	private static boolean inScene;

	/**
	 * What is being put down, of the three things that are put down by pointing.
	 *
	 * <h2>Why one mode and not three</h2>
	 *
	 * Because they are the same act — look at a piece of ground, say "there" — and
	 * everything around that act is shared and awkward to share twice: the window
	 * gets out of the way, the mouse buttons are borrowed from the game, escape has
	 * to mean "done" once and "menu" thereafter, and the same drawing has to appear
	 * over the world and inside the viewport. A second class doing all of that would
	 * be a second thing to keep in step with the first.
	 *
	 * What differs is only how many clicks each wants and what is written at the end:
	 *
	 * <ul>
	 * <li>{@code POINTS} takes as many as you give it, and stops on escape;</li>
	 * <li>{@code HOME} takes one and is done — a mode that went on waiting after the
	 *     only click it wanted would read as one that had not noticed;</li>
	 * <li>{@code BOX} takes exactly two, because that is what two opposite corners
	 *     are.</li>
	 * <li>{@code SHIFT} takes two as well, and they are not corners: they are the
	 *     same block of the build twice, where it was and where it is now.</li>
	 * </ul>
	 *
	 * The class is called Routing and this is no longer only about routes. The name
	 * has outlived its meaning and is left alone on purpose: renaming it would touch
	 * a dozen files to say something this note says in one line.
	 */
	private enum What { POINTS, HOME, BOX, SPOT, SHIFT }

	private static What what = What.POINTS;

	/**
	 * Which box is being drawn, while one is.
	 *
	 * A name rather than the box itself, because the box lives in the document and
	 * the document is what gets saved. Holding a copy here would be a second place
	 * for it to be, and the first corner of a box somebody walked away from would be
	 * a corner nothing owns.
	 */
	private static String areaName = "";

	/**
	 * The first corner, while only one has been clicked.
	 *
	 * Null between boxes, which is also how the drawing knows whether to show a
	 * finished box or one being stretched out under the cursor.
	 */
	private static Route.Point firstCorner;

	/**
	 * Where the build was, while only the first of the two clicks has landed.
	 *
	 * A block rather than a point, because neither of the two is a place anything is
	 * written to: they are only the two ends of a measurement, and what is kept is
	 * the difference between them.
	 */
	private static net.minecraft.core.BlockPos shiftFrom;

	/**
	 * Whether points are being placed, and there is still somewhere to put them.
	 *
	 * The second half is not a formality. The node can be deleted or renamed from
	 * the panel that opened this, and a mode left running against a node that is no
	 * longer there would go on taking the mouse buttons and quietly dropping every
	 * point — which is the worst way for it to fail, because it looks exactly like
	 * clicking on the wrong sort of ground.
	 */
	public static boolean active() {
		if (document == null || nodeId.isEmpty()) return false;
		// Only the placing of points needs a route node to put them in — which is the
		// check that stops a mode running against a node somebody deleted from the
		// panel that opened it. The other two are not about a node at all: the home is
		// offered from the ending as well, and a box belongs to the document.
		return what != What.POINTS || walkNode() != null;
	}

	/** True while the world is the whole of the display and the mouse is ours. */
	public static boolean inWorld() {
		return active() && !inScene;
	}

	public static boolean inScene() {
		return active() && inScene;
	}

	/** Whether the click being taken puts the home rather than a point of the route. */
	public static boolean movingHome() {
		return active() && what == What.HOME;
	}

	/** Whether the click being taken is a corner of a box. */
	public static boolean drawingBox() {
		return active() && what == What.BOX;
	}

	public static String node() {
		return nodeId;
	}

	/**
	 * Begins placing points for one route node.
	 *
	 * @param scene whether to stay in the workspace and use its camera, or leave for
	 *              the world and use the player's own eyes
	 */
	public static void start(EditorState state, String node, boolean scene) {
		begin(state, node, scene, What.POINTS, "");
	}

	/** Begins moving where the character belongs, which is one click and done. */
	public static void startHome(EditorState state, String node, boolean scene) {
		begin(state, node, scene, What.HOME, "");
	}

	/**
	 * Begins choosing the one place an {@code appear} puts her, which is one click.
	 *
	 * The same gesture as moving her home and for the same reason: a single place is
	 * a single click, and a mode that went on waiting afterwards would read as one
	 * that had not noticed.
	 */
	public static void startSpot(EditorState state, String node, boolean scene) {
		begin(state, node, scene, What.SPOT, "");
	}

	/**
	 * Begins drawing a box, corner and opposite corner.
	 *
	 * The box need not exist yet. A name with nothing under it is exactly what "draw
	 * a new one" means, and the document learns about it when the second corner
	 * lands — so walking away halfway leaves the document as it was rather than
	 * holding a box with one corner, which is not a box.
	 */
	public static void startBox(EditorState state, String area, boolean scene) {
		if (area == null || area.isEmpty()) return;
		begin(state, area, scene, What.BOX, area);
	}

	/**
	 * Begins carrying every box of this document to where the build went.
	 *
	 * <h2>What this is for</h2>
	 *
	 * Builders build off to the side and paste the finished thing in, and every
	 * coordinate in it changes. The boxes are written in coordinates precisely so
	 * that nothing moves them by accident — see {@link #formOfBox} for what happened
	 * when something did — so the deliberate move has to exist, and this is it.
	 *
	 * <h2>Why two clicks and not a typed offset</h2>
	 *
	 * Because nobody knows the offset. What somebody knows is which block of the
	 * build they are looking at: the corner of the doorway was there, and it is here
	 * now. Two clicks say that, and the arithmetic is ours rather than theirs — and
	 * it is the same gesture as everything else here, which is to look at a piece of
	 * ground and say "there".
	 */
	public static void startShift(EditorState state, boolean scene) {
		if (state == null || state.areas().isEmpty()) return;
		shiftFrom = null;
		// Named after the document, because this belongs to no node. The name is only
		// what keeps the mode alive — see active(), which asks for a node it can put
		// things in and is satisfied by anything for the modes that are not POINTS.
		begin(state, state.id(), scene, What.SHIFT, "");
	}

	private static void begin(EditorState state, String node, boolean scene,
			What placing, String area) {
		if (state == null || node == null || node.isEmpty()) return;
		document = state;
		nodeId = node;
		inScene = scene;
		what = placing;
		areaName = area;
		firstCorner = null;
		shiftFrom = null;

		Minecraft client = Minecraft.getInstance();
		if (client == null) return;
		if (!scene) {
			// Out of the way entirely. Not hidden, not made transparent: a screen that
			// is still there is a screen the movement keys do not get past, and walking
			// the route is most of the point of leaving.
			client.setScreenAndShow(null);
			return;
		}
		// And the other way in has to bring the viewport out, or the button opens a
		// mode whose whole content is drawn in a panel that is not on the screen.
		com.mopicmp.npcstudio.client.workspace.WorkspaceScreen.summon("viewport");
	}

	/**
	 * Puts the points back into the node and brings the editor back.
	 *
	 * Bringing it back rather than leaving the world showing, because this was
	 * entered from a button in a panel and the way out of a mode should land where
	 * the way in was pressed.
	 */
	public static void finish() {
		EditorState state = document;
		boolean wasInScene = inScene;
		forget();
		if (state == null) return;

		Minecraft client = Minecraft.getInstance();
		if (client == null) return;
		if (!wasInScene) {
			com.mopicmp.npcstudio.client.workspace.WorkspaceScreen.show(client);
		}
		// Through the panel's own way in, so that the graph is revealed and handed the
		// document exactly as it is when one arrives from the server. A second route
		// back into the editor would be a second thing to keep in step.
		com.mopicmp.npcstudio.client.workspace.panel.GraphPanel.accept(state);
	}

	/** Puts it down without going anywhere, for a disconnect or a change of world. */
	public static void forget() {
		document = null;
		nodeId = "";
		inScene = false;
		what = What.POINTS;
		areaName = "";
		firstCorner = null;
		shiftFrom = null;
		// The remembered anchor goes with it. A client entity kept past a disconnect
		// is a handle on a world that no longer exists, and the next one hands out the
		// same ids to different characters.
		anchorWas = null;
		anchorAt = Long.MIN_VALUE;
		anchorFor = null;
		anchorKnown = false;
	}

	// ------------------------------------------------------------ the points

	/** The route as it stands, or an empty one when nothing is being placed. */
	public static Route route() {
		Node.Walk walk = walkNode();
		return walk == null ? Route.NOWHERE : walk.route();
	}

	private static Node.Walk walkNode() {
		if (document == null) return null;
		for (Node node : document.nodes()) {
			if (node instanceof Node.Walk walk && walk.id().equals(nodeId)) return walk;
		}
		// The node was deleted or renamed while this was open. Nothing to place into,
		// so the mode is over — better than going on collecting points for a node
		// that is not there and reporting nothing when they vanish.
		return null;
	}

	/**
	 * Writes the route back into the node, and tells the panel showing it.
	 *
	 * <h2>Why the telling is needed at all</h2>
	 *
	 * Because the document and the panel are not the same thing, and only one of
	 * them is read every frame. The list of points in the node's panel is built once,
	 * when the node is selected — so a point taken away out in the world went from
	 * the document, went from the lines drawn over the ground, and stayed in the list
	 * of numbers. Reported as exactly that: deleted in the world, still in the node.
	 *
	 * It is worst in the scene, where the panel and the ground are on the screen at
	 * the same moment, showing the same route, disagreeing.
	 */
	private static void putRoute(Route now) {
		Node.Walk walk = walkNode();
		if (walk == null) return;
		int index = document.nodeIds().indexOf(walk.id());
		if (index < 0) return;
		document.replace(index, new Node.Walk(walk.id(), now, walk.next()));
		panelChanged();
	}

	/**
	 * Rebuilds the node's fields, wherever the editor is being shown.
	 *
	 * Through the screen's own static rather than through the panel, because there
	 * are two places it can be — a panel of the workspace, or a screen of its own —
	 * and the screen is the thing both of those are made of.
	 */
	private static void panelChanged() {
		var editor = com.mopicmp.npcstudio.client.editor.GraphEditorScreen.current();
		if (editor != null) editor.refreshPanel();
	}

	// ------------------------------------------------------------- the anchor

	/**
	 * The character a route of steps is measured from.
	 *
	 * <h2>Why the editor has to find one at all</h2>
	 *
	 * Because a step is a step from somewhere. The anchor at run time is the
	 * character's own home, and she knows it; the editor is turning clicks into
	 * steps before any of that has happened, so it has to be looking at the same
	 * character the route will belong to.
	 *
	 * Whoever is selected first, because pointing at somebody is how you say which
	 * one you mean. Failing that, the nearest character carrying this very graph —
	 * which is the ordinary case: you walk up to the guard whose round you are
	 * drawing, and there she is.
	 *
	 * Null is a real answer and not a failure. A route can be drawn with nobody
	 * about; it is simply drawn in coordinates, and says so.
	 */
	public static com.mopicmp.npcstudio.entity.NpcEntity anchor() {
		return anchorFor(document);
	}

	/**
	 * The same, for a document that is merely open rather than being drawn on.
	 *
	 * <h2>Why the placing mode cannot be the one who knows</h2>
	 *
	 * It was, and that made the panel lie. This class holds a document only while
	 * somebody is out in the world putting points down; the panel is read at every
	 * other moment as well, and asking through the mode meant asking a class that
	 * had been emptied. So a route node with a character standing in front of it
	 * said "nobody here to ask" and showed its steps measured from the origin, and
	 * the one number on the panel that everything else hangs off was the one that
	 * could not be trusted.
	 */
	public static com.mopicmp.npcstudio.entity.NpcEntity anchorFor(EditorState state) {
		if (state == null) return null;
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.level == null || client.player == null) return null;

		// Remembered for the tick it was worked out in. Every point asks this — to be
		// drawn, and again to be picked out from under the cursor — so a route of a
		// dozen points was sweeping every entity in view two dozen times a frame for
		// an answer that cannot change until somebody has moved. The same trick, and
		// the same reason, as remembering kin for a tick.
		//
		// Which document it was worked out for is part of the memory now, because two
		// can ask in one tick: the mode drawing a route out in the world and a panel
		// showing a different graph would otherwise share one answer.
		// "Nobody" is remembered too, and that is the half that was missing. A null
		// answer failed the check below and sent the search round again, so the one
		// case where the sweep costs most — nobody to find, therefore every entity
		// looked at — was the one case it ran on every single call. It went unnoticed
		// while only the drawing asked; the panel asks every frame now.
		long now = client.level.getGameTime();
		if (anchorAt == now && anchorFor == state && anchorKnown
				&& (anchorWas == null || anchorWas.isAlive())) {
			return anchorWas;
		}
		anchorAt = now;
		anchorFor = state;
		anchorKnown = true;
		anchorWas = lookForAnchor(client, state);
		return anchorWas;
	}

	private static com.mopicmp.npcstudio.entity.NpcEntity anchorWas;
	private static long anchorAt = Long.MIN_VALUE;
	private static EditorState anchorFor;

	/** Whether the tick's answer has been worked out, which "nobody" also counts as. */
	private static boolean anchorKnown;

	private static com.mopicmp.npcstudio.entity.NpcEntity lookForAnchor(Minecraft client,
			EditorState document) {
		// Whoever is selected, but only if she is carrying this graph. Selection is how
		// you say which one you mean, and it is also a thing left over from ten minutes
		// ago — so a stale one must not quietly become the frame a whole route is
		// measured in. Somebody running a different graph is not a candidate at all.
		var chosen = com.mopicmp.npcstudio.client.workspace.Workspace.selection();
		if (chosen instanceof com.mopicmp.npcstudio.entity.NpcEntity npc
				&& npc.graphId().equals(document.id())) {
			return npc;
		}

		com.mopicmp.npcstudio.entity.NpcEntity nearest = null;
		double closest = Double.MAX_VALUE;
		for (var entity : client.level.entitiesForRendering()) {
			if (!(entity instanceof com.mopicmp.npcstudio.entity.NpcEntity npc)) continue;
			if (!npc.graphId().equals(document.id())) continue;
			double away = npc.distanceToSqr(client.player);
			if (away >= closest) continue;
			closest = away;
			nearest = npc;
		}
		if (nearest != null) return nearest;

		// And failing all of that, whoever is selected after all. A character nobody
		// has assigned this graph to yet is still a place to measure from, and while a
		// graph is being written that is the ordinary state of affairs.
		return chosen instanceof com.mopicmp.npcstudio.entity.NpcEntity npc ? npc : null;
	}

	/** Whether steps can be written at all, which is whether there is anybody to measure from. */
	public static boolean anchored() {
		return anchor() != null;
	}

	/**
	 * Where a point is, in blocks of this world.
	 *
	 * The one place the editor turns the two forms of point into one, and it has to
	 * agree with what the legs do — see {@code NpcEntity.walkTheRoute}, which asks
	 * {@link Route#world} the same way against the same home. A route drawn in one
	 * frame and walked in another would be a route that is right on the screen and
	 * wrong on the ground, which is the worst of the two.
	 */
	public static int[] world(Route.Point point) {
		return world(point, anchor());
	}

	/** The same, told which character to measure from, so a list of points asks once. */
	public static int[] world(Route.Point point, com.mopicmp.npcstudio.entity.NpcEntity npc) {
		if (npc == null) {
			// No anchor: a step has nowhere to start, so it is drawn from the origin
			// rather than not drawn. Honest and useless in equal measure, and the panel
			// says why rather than leaving a route that has silently moved to the corner
			// of the map.
			return Route.world(point, 0, 0, 0, Route.Facing.SOUTH);
		}
		var home = npc.home();
		return Route.world(point,
			net.minecraft.util.Mth.floor(home.x),
			net.minecraft.util.Mth.floor(home.y),
			net.minecraft.util.Mth.floor(home.z),
			npc.homeFacing());
	}

	// ------------------------------------------------------------- the points

	/**
	 * Moves where the character belongs to the block that was pointed at.
	 *
	 * Nothing is written here and nothing is drawn from a guess: the post lives on
	 * the character, on the server, and the client learns it back the same way it
	 * learns everything else about her. A client that painted its own answer first
	 * would show a home nobody else has the moment the server refused — and refuse it
	 * will, for somebody without the right to edit or standing too far away.
	 *
	 * The facing is the player's own, which is the gesture the map's start already
	 * uses: which way she stands is said by standing that way.
	 */
	private static void putHome(Vec3 at) {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.player == null) return;
		putHome(at, client.player.getYRot());
	}

	/** The same, told which way she should stand there. */
	public static void putHome(Vec3 at, float facing) {
		var npc = anchor();
		if (at == null || npc == null) {
			say(Component.translatable("npc_studio.route.no_anchor"));
			return;
		}
		// The block above the surface, which is where a body stands — the same reading
		// every other point in this file takes of the ground it was put on.
		BlockPos to = BlockPos.containing(at.x, at.y + 0.5, at.z);
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
			new com.mopicmp.npcstudio.net.NpcPayloads.Post(npc.getId(), to, facing));
		// Done in one. A home is one place, and a mode that went on waiting after the
		// only click it wanted would read as one that had not noticed.
		finish();
	}

	/**
	 * The place an {@code appear} puts her, written the way a route point would be.
	 *
	 * Steps from where she was placed when there is somebody to measure against, and
	 * coordinates when there is not — the same fallback, said out loud the same way,
	 * because a mode that silently did nothing when there was no anchor would look
	 * like a mode that had stopped working.
	 */
	public static void putSpot(Vec3 at) {
		if (at == null || document == null) return;
		int index = document.nodeIds().indexOf(nodeId);
		if (index < 0 || !(document.nodes().get(index) instanceof Node.Act act)) {
			finish();
			return;
		}
		// The block a body would stand on above what was clicked, which is the reading
		// every other point in this file takes of the ground it was put on.
		BlockPos to = BlockPos.containing(at.x, at.y + 0.5, at.z);
		Route.Point where = writtenSpot(to);
		// Written back as whichever of the two this node is. They ask the same question
		// about different people, so they share the pointing — and the first version of
		// this wrote one kind whatever it found, which on the other kind meant a button
		// that silently did nothing.
		com.mopicmp.npcstudio.dialogue.Effect now = switch (act.effect()) {
			case com.mopicmp.npcstudio.dialogue.Effect.Appear _ ->
				new com.mopicmp.npcstudio.dialogue.Effect.Appear(where);
			case com.mopicmp.npcstudio.dialogue.Effect.Send _ ->
				new com.mopicmp.npcstudio.dialogue.Effect.Send(where);
			// A thing hanging in the air is put where somebody pointed, like the other
			// two — and it keeps its name and its look, because the pointing is only
			// about the place.
			case com.mopicmp.npcstudio.dialogue.Effect.Show(String name,
					com.mopicmp.npcstudio.dialogue.Shown what,
					com.mopicmp.npcstudio.dialogue.Route.Point ignored) ->
				new com.mopicmp.npcstudio.dialogue.Effect.Show(name, what, where);
			default -> null;
		};
		if (now == null) {
			finish();
			return;
		}
		document.replace(index, new Node.Act(act.id(), now, act.next()));
		panelChanged();
		finish();
	}

	/** A clicked block, as steps from the character if there is one to measure from. */
	private static Route.Point writtenSpot(BlockPos to) {
		var npc = anchor();
		if (npc == null) {
			say(Component.translatable("npc_studio.route.no_anchor"));
			return new Route.Point.At(to.getX(), to.getY(), to.getZ());
		}
		var home = npc.home();
		return Route.step(to.getX(), to.getY(), to.getZ(),
			net.minecraft.util.Mth.floor(home.x),
			net.minecraft.util.Mth.floor(home.y),
			net.minecraft.util.Mth.floor(home.z),
			npc.homeFacing());
	}

	/**
	 * One corner of the box being drawn; the second one finishes it.
	 *
	 * Nothing is written on the first click. A box with one corner is not a box, and
	 * a document holding one would be a document with a shape in it that no test can
	 * answer about — so the half-drawn state lives here, where walking away loses it
	 * and loses nothing else.
	 */
	public static void putCorner(Vec3 at) {
		if (at == null || document == null || areaName.isEmpty()) return;
		// The block a body would stand on, the same reading every other point here
		// takes of the ground it was put on. A corner inside the floor would make a
		// box a block shallower than the one somebody drew.
		BlockPos to = BlockPos.containing(at.x, at.y + 0.5, at.z);
		Route.Point corner = writtenCorner(to);

		if (firstCorner == null) {
			firstCorner = corner;
			say(Component.translatable("npc_studio.area.second_corner"));
			return;
		}
		document.area(areaName, new Area(firstCorner, corner, formOfBox()));
		firstCorner = null;
		panelChanged();
		finish();
	}

	/**
	 * What a fresh box writes its corners as, taken from the box being redrawn.
	 *
	 * <h2>Why a box nobody has drawn yet is written in coordinates</h2>
	 *
	 * It used to be written as steps from the character, which is what a route does,
	 * and the reasoning was that a box is a piece of a scene and a scene travels.
	 * That reasoning was wrong twice.
	 *
	 * Once because a place has no character. A box bound to a way in is asked about
	 * by a conversation a doorway began, and there is nobody standing in it — so its
	 * steps were measured from the origin, and the trigger sat in a hole under the
	 * world spawn and could not fire. That was reported as drawing the box, binding
	 * it, walking into it, and nothing whatever happening.
	 *
	 * And once because the frame was invisible. A box written as steps hangs off the
	 * character's home <em>and the quarter she faces</em>, so moving her or turning
	 * her carried every box in the document with her and turned them ninety degrees —
	 * which from outside is a wall that works about half the time.
	 *
	 * A box being redrawn keeps whatever it was written as, because that is a
	 * decision somebody made about that box and redrawing it is not the moment to
	 * overrule them. Moving a build is served by {@link #startShift} instead.
	 */
	private static Route.From formOfBox() {
		Area was = document == null ? null : document.areas().get(areaName);
		return was == null ? Route.From.WORLD : was.from();
	}

	/**
	 * One end of the move; the second one carries the boxes.
	 *
	 * <h2>Why the ground under the click and not the block clicked</h2>
	 *
	 * The same reading every point here takes, and it has to be the same one: the
	 * corners were put down as the block a body would stand on, so a move measured
	 * from the block itself would be a block out on the vertical for every box in
	 * the document. Both ends take the same reading, so the difference is right even
	 * if the reading is odd.
	 *
	 * <h2>Why boxes written as steps are counted but not moved</h2>
	 *
	 * They travelled with the character already. Saying how many were left alone is
	 * the difference between "it did nothing" and "it did nothing to those, on
	 * purpose" — and the first of those is a bug report.
	 */
	public static void putShift(Vec3 at) {
		if (at == null || document == null) return;
		BlockPos to = BlockPos.containing(at.x, at.y + 0.5, at.z);
		if (shiftFrom == null) {
			shiftFrom = to;
			say(Component.translatable("npc_studio.area.shift_to"));
			return;
		}
		int byX = to.getX() - shiftFrom.getX();
		int byY = to.getY() - shiftFrom.getY();
		int byZ = to.getZ() - shiftFrom.getZ();
		shiftFrom = null;

		int moved = 0;
		int stayed = 0;
		for (var entry : java.util.Map.copyOf(document.areas()).entrySet()) {
			Area was = entry.getValue();
			Area now = was.shifted(byX, byY, byZ);
			if (now.equals(was)) {
				stayed++;
			} else {
				document.area(entry.getKey(), now);
				moved++;
			}
		}
		say(Component.translatable("npc_studio.area.shifted", moved, stayed));
		panelChanged();
		finish();
	}

	/**
	 * A clicked block, written the way the box being drawn writes its corners.
	 *
	 * The same fallback the route points take, and it has to be the same: a box drawn
	 * with nobody about is written in coordinates and says so once, rather than the
	 * mode taking the mouse and silently doing nothing.
	 */
	private static Route.Point writtenCorner(BlockPos to) {
		var npc = formOfBox() == Route.From.CHARACTER ? anchor() : null;
		if (npc == null) {
			if (formOfBox() == Route.From.CHARACTER) {
				say(Component.translatable("npc_studio.route.no_anchor"));
			}
			return new Route.Point.At(to.getX(), to.getY(), to.getZ());
		}
		var home = npc.home();
		return Route.step(to.getX(), to.getY(), to.getZ(),
			net.minecraft.util.Mth.floor(home.x),
			net.minecraft.util.Mth.floor(home.y),
			net.minecraft.util.Mth.floor(home.z),
			npc.homeFacing());
	}

	/** Adds a point at the block a body would stand on above what was clicked. */
	public static boolean put(Vec3 at) {
		Node.Walk walk = walkNode();
		if (walk == null || at == null) return false;
		// The block above the surface, which is where a body stands — the same reading
		// a named place and the map's start both take of the ground they were put on.
		// A point inside the floor is a point nobody can walk to.
		BlockPos to = BlockPos.containing(at.x, at.y + 0.5, at.z);
		Route was = walk.route();
		Route now = was.and(written(was, to));
		if (now.size() == was.size()) {
			// Full. Said out loud, because a click that silently does nothing is a
			// click people make four more times before reporting it.
			say(Component.translatable("npc_studio.route.full", Route.MOST));
			return false;
		}
		putRoute(now);
		return true;
	}

	/**
	 * A clicked block, written the way this route writes points.
	 *
	 * Falls back to coordinates when there is nobody to measure from, and says so
	 * once. Refusing the click instead would be worse: somebody drawing a path with
	 * no character nearby would get a mode that takes the mouse and does nothing.
	 */
	private static Route.Point written(Route was, BlockPos to) {
		var npc = was.from() == Route.From.CHARACTER ? anchor() : null;
		if (npc == null) {
			if (was.from() == Route.From.CHARACTER) {
				say(Component.translatable("npc_studio.route.no_anchor"));
			}
			return new Route.Point.At(to.getX(), to.getY(), to.getZ());
		}
		var home = npc.home();
		return Route.step(to.getX(), to.getY(), to.getZ(),
			net.minecraft.util.Mth.floor(home.x),
			net.minecraft.util.Mth.floor(home.y),
			net.minecraft.util.Mth.floor(home.z),
			npc.homeFacing());
	}

	/** Takes away the point nearest what is being pointed at, if one is near enough. */
	public static boolean remove(Vec3 at) {
		Node.Walk walk = walkNode();
		if (walk == null || at == null) return false;
		int nearest = nearestTo(walk.route(), at);
		if (nearest < 0) return false;
		putRoute(walk.route().without(nearest));
		return true;
	}

	/**
	 * Which point is being pointed at, or minus one.
	 *
	 * Flat, ignoring height. Somebody aiming at a post from across a courtyard is
	 * aiming at the whole upright, and measuring to the block at its foot would make
	 * the pick depend on how far up the post the crosshair happened to land.
	 */
	public static int nearestTo(Route route, Vec3 at) {
		int nearest = -1;
		double closest = REMOVES_WITHIN * REMOVES_WITHIN;
		var npc = anchor();
		for (int i = 0; i < route.size(); i++) {
			int[] where = world(route.at(i), npc);
			double dx = where[0] + 0.5 - at.x;
			double dz = where[2] + 0.5 - at.z;
			double away = dx * dx + dz * dz;
			if (away <= closest) {
				closest = away;
				nearest = i;
			}
		}
		return nearest;
	}

	// ------------------------------------------------------------- the mouse

	/**
	 * A press of the attack button while this is on: another point, or one fewer.
	 *
	 * <h2>Why removing is on the same button held down rather than on the other one</h2>
	 *
	 * Because the other button is not free in both places. Out here the use button
	 * is going spare, but in the workspace the right button flies the camera, and a
	 * route longer than the view is drawn by flying along it. Putting "remove" on it
	 * would have meant one gesture in the world and a different one in the scene for
	 * the same act — which is worse than a modifier key, because it is a thing to
	 * remember rather than a thing to hold.
	 *
	 * @return whether it was taken, so the game does not also swing at the world
	 */
	/**
	 * Whether shift is down, asked of the keyboard rather than of an event.
	 *
	 * There is no screen out here, so there is no event carrying modifiers — the
	 * click arrives as a bare press of the attack button. Both physical shift keys,
	 * because a person who uses the right one is not holding a different key.
	 */
	public static boolean removing() {
		var window = Minecraft.getInstance().getWindow();
		return com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, 340)
			|| com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, 344);
	}

	public static boolean clicked(boolean removing) {
		if (!inWorld()) return false;
		// The ground and nothing else. Ordinary pointing lets a creature win, which
		// is right for a menu about her and exactly wrong here: the first point
		// anybody wants is the floor she is standing on, and she was the one thing
		// standing between the crosshair and it.
		Under under = Aim.ground();
		if (under.at() == null) {
			say(Component.translatable("npc_studio.route.no_ground"));
			return true;
		}
		if (what == What.HOME) {
			putHome(under.at());
			return true;
		}
		if (what == What.BOX) {
			putCorner(under.at());
			return true;
		}
		if (what == What.SPOT) {
			putSpot(under.at());
			return true;
		}
		if (what == What.SHIFT) {
			putShift(under.at());
			return true;
		}
		if (!removing) {
			put(under.at());
		} else if (!remove(under.at())) {
			say(Component.translatable("npc_studio.route.nothing_there"));
		}
		return true;
	}

	/**
	 * The use button, while this is on: nothing at all.
	 *
	 * Swallowed rather than left alone. It is the button that places blocks and eats
	 * food, and the whole of this mode is spent aiming at floors with whatever
	 * happened to be in hand — so leaving it live would mean a route drawn across a
	 * courtyard with a trail of somebody's building blocks along it.
	 */
	public static boolean rightClicked() {
		return inWorld();
	}

	/**
	 * Escape, while this is on: done, and back to the editor.
	 *
	 * @return whether it was taken, so the pause menu does not also open
	 */
	public static boolean escaped() {
		if (!inWorld()) return false;
		finish();
		return true;
	}

	/**
	 * A line over the hotbar.
	 *
	 * Public because the panel says the same things about the same route, and two
	 * ways of reporting one refusal would eventually word it two ways.
	 */
	public static void say(Component what) {
		Minecraft client = Minecraft.getInstance();
		if (client != null && client.player != null) client.player.sendOverlayMessage(what);
	}

	// ------------------------------------------------------------ the drawing

	/**
	 * The route where it is, over the ordinary game.
	 *
	 * Drawn whenever points are being placed and never otherwise. A route belongs to
	 * one node of one graph, so a world showing every route in every graph at once
	 * would be a world with lines through it that nobody can trace back to anything.
	 */
	public static void overWorld(GuiGraphicsExtractor graphics,
			net.minecraft.client.DeltaTracker delta) {
		if (!inWorld()) return;
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.level == null) return;
		draw(graphics, client.font, 0, 0, Aim.under().at());
		hint(graphics, client.font, 0, client.getWindow().getGuiScaledWidth());
	}

	/** The same, into a viewport that has been given an origin of its own. */
	public static void draw(GuiGraphicsExtractor graphics, Font font,
			int originX, int originY, Vec3 pointedAt) {
		Minecraft client = Minecraft.getInstance();
		var npc = anchor();

		// The home first, and before the route is asked whether it has anything in it.
		// Moving the home is offered from the ending as well, which has no route at
		// all — so an early return on an empty one would leave the anchor invisible in
		// exactly the case where somebody is looking for it.
		drawHome(graphics, font, originX, originY, npc);
		drawBoxes(graphics, font, originX, originY, npc, pointedAt);

		Route route = route();
		if (route.isEmpty()) return;

		Vec3 eye = client.gameRenderer.mainCamera().position();
		int under = pointedAt == null ? -1 : nearestTo(route, pointedAt);

		Vec3 previous = null;
		for (int i = 0; i < route.size(); i++) {
			int[] place = world(route.at(i), npc);
			Vec3 foot = new Vec3(place[0] + 0.5, place[1], place[2] + 0.5);
			int colour = i == under ? UNDER_CURSOR : i == 0 ? FIRST : LINE;

			// An upright, so a point is visible across a courtyard rather than only
			// from above — the same shape the map's named places are drawn as, because
			// they are the same sort of thing standing on the same sort of ground.
			Wireframe.line(graphics, originX, originY, foot, foot.add(0, POST, 0), colour);
			for (double[] arm : new double[][] { { 0.4, 0 }, { -0.4, 0 }, { 0, 0.4 }, { 0, -0.4 } }) {
				Wireframe.line(graphics, originX, originY, foot,
					foot.add(arm[0], 0, arm[1]), colour);
			}

			// And the leg of the journey that got here. Drawn at ankle height rather
			// than on the floor: a line lying exactly on the ground is hidden by it
			// from every angle anybody looks at a route from.
			if (previous != null) {
				Wireframe.line(graphics, originX, originY,
					previous.add(0, 0.2, 0), foot.add(0, 0.2, 0), LINE);
			}
			previous = foot;

			Vec3 top = foot.add(0, POST, 0);
			if (eye.distanceToSqr(top) > NUMBERED_WITHIN * NUMBERED_WITHIN) continue;
			double[] on = Wireframe.at(top, originX, originY);
			if (on == null) continue;
			// The number, because the order is the whole of what a route is. Without
			// it a route and the same points in another order look identical.
			Component said = Component.literal(String.valueOf(i + 1));
			graphics.text(font, said,
				(int) on[0] - font.width(said) / 2, (int) on[1] - 10,
				i == under ? UNDER_CURSOR : LABEL);
		}
	}

	/**
	 * Where the steps are measured from, and which way "forward" points.
	 *
	 * <h2>Why this has to be on the screen</h2>
	 *
	 * Because a route written as steps is meaningless without it, and it is the one
	 * part of the route that is invisible. An author looking at four posts and a
	 * chain between them can check the shape; they cannot check that the shape hangs
	 * off the doorway they meant rather than off wherever the character happened to
	 * be standing when the world was last opened.
	 *
	 * That is not a hypothetical. A character placed before her home was a thing kept
	 * has it settled on her first tick, which is wherever her graph had already walked
	 * her — and a guard whose home is the end of her own round never comes back from
	 * it, with nothing anywhere saying why. It was reported as "she does not find the
	 * point where I put her". The ring's "post here" is the correction; this is what
	 * makes it visible that one is needed.
	 *
	 * The arrow is half of it. Forward is what every step is measured along, so an
	 * anchor facing the wrong quarter turns the whole round without moving it.
	 */
	private static void drawHome(GuiGraphicsExtractor graphics, Font font,
			int originX, int originY, com.mopicmp.npcstudio.entity.NpcEntity npc) {
		if (npc == null) return;
		Vec3 foot = npc.home();
		Vec3 top = foot.add(0, POST + 0.6, 0);
		Wireframe.line(graphics, originX, originY, foot, top, HOME);

		// A cross on the ground, the same mark a named place wears, so the two read as
		// the same sort of thing — because they are.
		for (double[] arm : new double[][] { { 0.4, 0 }, { -0.4, 0 }, { 0, 0.4 }, { 0, -0.4 } }) {
			Wireframe.line(graphics, originX, originY, foot,
				foot.add(arm[0], 0, arm[1]), HOME);
		}

		// And which way forward is, drawn as an arrow two blocks long along it.
		var facing = npc.homeFacing();
		Vec3 ahead = foot.add(facing.forwardX() * 2.0, 0.1, facing.forwardZ() * 2.0);
		Wireframe.line(graphics, originX, originY, foot.add(0, 0.1, 0), ahead, HOME);
		for (int side = -1; side <= 1; side += 2) {
			Vec3 barb = ahead.add(
				(-facing.forwardX() + facing.leftX() * side) * 0.5, 0,
				(-facing.forwardZ() + facing.leftZ() * side) * 0.5);
			Wireframe.line(graphics, originX, originY, ahead, barb, HOME);
		}

		double[] on = Wireframe.at(top, originX, originY);
		if (on == null) return;
		Component said = Component.translatable("npc_studio.route.home");
		graphics.text(font, said, (int) on[0] - font.width(said) / 2, (int) on[1] - 10, HOME);
	}

	/**
	 * Every box this document has drawn, with the one in hand picked out.
	 *
	 * <h2>Why the others are drawn at all</h2>
	 *
	 * Because a wall is placed <em>against</em> something. The stretch that springs
	 * the trap and the wall that seals behind it are two boxes with a relationship,
	 * and drawing the second one blind — with the first invisible — is guessing at
	 * exactly the thing that has to line up. Dim, because they are the reference and
	 * not the work.
	 *
	 * <h2>The one being stretched out under the cursor</h2>
	 *
	 * After the first corner there is no box yet, and the useful picture is the box
	 * there would be if the second corner landed where you are pointing. Without it
	 * the first click has no visible effect at all — which reads as the click having
	 * missed, and the answer to that is another click somewhere else.
	 */
	private static void drawBoxes(GuiGraphicsExtractor graphics, Font font,
			int originX, int originY, com.mopicmp.npcstudio.entity.NpcEntity npc,
			Vec3 pointedAt) {
		if (document == null) return;
		int ax = 0;
		int ay = 0;
		int az = 0;
		Route.Facing facing = Route.Facing.SOUTH;
		if (npc != null) {
			var home = npc.home();
			ax = net.minecraft.util.Mth.floor(home.x);
			ay = net.minecraft.util.Mth.floor(home.y);
			az = net.minecraft.util.Mth.floor(home.z);
			facing = npc.homeFacing();
		}

		for (var each : document.areas().entrySet()) {
			boolean itsOwn = what == What.BOX && each.getKey().equals(areaName);
			// The one in hand is drawn from the corners in play rather than from the
			// document, so a box being redrawn does not show its old shape under its new
			// one. Skipped here and drawn below with the live corner.
			if (itsOwn && firstCorner != null) continue;
			int[][] box = each.getValue().world(ax, ay, az, facing);
			Wireframe.box(graphics, originX, originY, box[0], box[1],
				itsOwn ? BOX : OTHER_BOX);
			nameBox(graphics, font, originX, originY, box, each.getKey(),
				itsOwn ? BOX : OTHER_BOX);
		}

		if (what != What.BOX || firstCorner == null || pointedAt == null) return;
		// Where the second corner would land, read the same way the click reads it, so
		// what is drawn is what would be written.
		BlockPos to = BlockPos.containing(pointedAt.x, pointedAt.y + 0.5, pointedAt.z);
		int[][] box = new Area(firstCorner,
			new Route.Point.At(to.getX(), to.getY(), to.getZ()), Route.From.WORLD)
			.world(ax, ay, az, facing);
		Wireframe.box(graphics, originX, originY, box[0], box[1], UNDER_CURSOR);
		nameBox(graphics, font, originX, originY, box, areaName, UNDER_CURSOR);
	}

	/**
	 * A box name, over the middle of its top face.
	 *
	 * The middle rather than a corner, because a corner of a box is shared with the
	 * three boxes that might be next to it and a label there names any of them.
	 */
	private static void nameBox(GuiGraphicsExtractor graphics, Font font,
			int originX, int originY, int[][] box, String name, int colour) {
		Minecraft client = Minecraft.getInstance();
		if (client == null) return;
		Vec3 top = new Vec3(
			(box[0][0] + box[1][0] + 1) / 2.0,
			box[1][1] + 1.0,
			(box[0][2] + box[1][2] + 1) / 2.0);
		Vec3 eye = client.gameRenderer.mainCamera().position();
		if (eye.distanceToSqr(top) > NUMBERED_WITHIN * NUMBERED_WITHIN) return;
		double[] on = Wireframe.at(top, originX, originY);
		if (on == null) return;
		Component said = Component.literal(name);
		graphics.text(font, said, (int) on[0] - font.width(said) / 2, (int) on[1] - 10, colour);
	}

	/**
	 * The line along the top saying what the buttons do.
	 *
	 * At the top rather than by the crosshair, which is where the pointing happens
	 * and is the one part of the screen that must stay clear.
	 */
	public static void hint(GuiGraphicsExtractor graphics, Font font, int left, int width) {
		if (!active()) return;
		Component said = switch (what) {
			case HOME -> Component.translatable("npc_studio.route.placing_home");
			case SPOT -> Component.translatable("npc_studio.route.placing_spot");
			// Two lines rather than one, because "click a corner" and "click the
			// opposite corner" are different instructions and a mode that says the same
			// thing at both moments cannot be followed by somebody who looked away.
			case BOX -> Component.translatable(firstCorner == null
				? "npc_studio.area.first_corner" : "npc_studio.area.second_corner_hint",
				areaName);
			// And the same two-line rule for the move, where the pair of clicks are not
			// even the same kind of thing: one names a block of the build as it stood,
			// the other names where that block is now.
			case SHIFT -> Component.translatable(shiftFrom == null
				? "npc_studio.area.shift_from" : "npc_studio.area.shift_to_hint");
			case POINTS -> Component.translatable("npc_studio.route.placing", route().size());
		};
		int wide = font.width(said);
		int x = left + (width - wide) / 2;
		graphics.fill(x - 6, 4, x + wide + 6, 20, 0xCC12161C);
		graphics.text(font, said, x, 8, LABEL);
	}
}
