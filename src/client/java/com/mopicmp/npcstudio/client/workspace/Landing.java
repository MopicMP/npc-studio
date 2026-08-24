package com.mopicmp.npcstudio.client.workspace;

/**
 * Where an edit comes to rest.
 *
 * <h2>The idea, in one sentence</h2>
 *
 * A panel does not own the value it shows. It is an editor for a value that
 * lives somewhere else, and <b>where</b> that value is written down is a
 * separate question from <b>what</b> the panel is for.
 *
 * <h2>What it is for</h2>
 *
 * Without this there is one panel per place. The sky can be set in a scene, so
 * there is a lighting panel in the scene editor; the sky ought to be settable in
 * the world, so there is a second one; a dialogue ought to be able to make it
 * night, so there is a third. Three copies of one set of sliders, three places
 * to fix one mistake, and they drift apart within a month. Every mod of this
 * shape grows that way and it is what this is here to prevent.
 *
 * With it there is one lighting panel, and moving a slider means three different
 * things depending on where edits are landing at that moment:
 *
 * <ul>
 * <li>{@link #WORLD} — the sky changes now and stays that way.</li>
 * <li>{@link #SCENE} — a key goes down on the cursor.</li>
 * <li>{@link #BLOCK} — the selected node gains an action.</li>
 * </ul>
 *
 * <h2>Chosen, not guessed</h2>
 *
 * It follows the work by default: a scene open means edits go into the scene,
 * because that is what somebody with a scene open is doing. It can be pinned,
 * for the times when it is not — turning the sky orange for the world while a
 * scene happens to be open is an ordinary thing to want.
 *
 * A pin that has become impossible is not obeyed. Pinning {@code SCENE} and then
 * closing the scene leaves nothing to write into, and the honest answer there is
 * the world rather than a silently discarded edit. This is the whole reason
 * {@link #choose} takes its context as arguments instead of reading it: the rule
 * is arithmetic and is tested as arithmetic.
 */
public enum Landing {

	WORLD(Icon.ENVIRONMENT),
	SCENE(Icon.SCENE),
	BLOCK(Icon.GRAPH);

	public final Icon icon;

	Landing(Icon icon) {
		this.icon = icon;
	}

	public net.minecraft.network.chat.Component title() {
		return net.minecraft.network.chat.Component.translatable(
			"npc_studio.landing." + name().toLowerCase(java.util.Locale.ROOT));
	}

	// -------------------------------------------------------------- the choice

	/**
	 * What has been pinned, or null while it follows the work.
	 *
	 * Static and not saved. Which receiver is in use is a fact about the next
	 * minute, not about the project — somebody who pinned the world to fix a sunset
	 * has not asked to still be pinned there tomorrow, and a pin surviving a restart
	 * would be a pin nobody remembers setting.
	 */
	private static Landing pinned;

	public static Landing pinned() {
		return pinned;
	}

	/** Pins a receiver, or hands it back to the work with null. */
	public static void pin(Landing wanted) {
		pinned = wanted;
	}

	/**
	 * The receiver in use at this moment.
	 *
	 * @param pinned      what somebody pinned, or null to follow the work
	 * @param sceneOpen   whether a scene is open to take keys
	 * @param nodeChosen  whether a graph node is selected to take actions
	 */
	public static Landing choose(Landing pinned, boolean sceneOpen, boolean nodeChosen) {
		Landing wanted = pinned != null ? pinned : following(sceneOpen, nodeChosen);
		return switch (wanted) {
			// A pin at something that is not there is not obeyed, and the fallback is
			// always the world: it is the one receiver that is always able to take an
			// edit, because the world is always there.
			case SCENE -> sceneOpen ? SCENE : WORLD;
			case BLOCK -> nodeChosen ? BLOCK : WORLD;
			case WORLD -> WORLD;
		};
	}

	/**
	 * Where edits go when nobody has said, worked out from what is being done.
	 *
	 * The scene wins over the node when both are up, and that ordering is not
	 * arbitrary: the graph and the timeline sit one above the other on purpose, so
	 * both are on screen most of the time, and of the two the scene is the one
	 * being <em>edited</em> — a node is usually selected because somebody looked at
	 * it. Guessing the other way would put keys into dialogue nodes all afternoon.
	 */
	private static Landing following(boolean sceneOpen, boolean nodeChosen) {
		if (sceneOpen) return SCENE;
		if (nodeChosen) return BLOCK;
		return WORLD;
	}

	/**
	 * Whether a graph node is selected, asked of whoever knows.
	 *
	 * A hook rather than a call, because the answer lives inside the dialogue
	 * editor and the editor is a screen inside a panel inside a dock — reaching
	 * through all of that from here would tie the receiver to one particular way
	 * of showing a graph. The graph panel sets this when it has a selection.
	 *
	 * Answers false until something sets it, which is right in two ways at once:
	 * with no graph open there is no node, and until the node actions of
	 * {@code docs/studio-architecture.md} exist there is nothing for {@link #BLOCK}
	 * to write into. Both of those are the same false.
	 */
	private static java.util.function.BooleanSupplier nodeChosen = () -> false;

	public static void nodeChosen(java.util.function.BooleanSupplier asking) {
		nodeChosen = asking == null ? () -> false : asking;
	}

	/** The same, reading the live state of the game. */
	public static Landing now() {
		return choose(pinned,
			com.mopicmp.npcstudio.client.scene.Playing.scene() != null,
			nodeChosen.getAsBoolean());
	}

	/** The receivers worth offering here and now; a receiver with no home is not one. */
	public static java.util.List<Landing> offered() {
		java.util.List<Landing> open = new java.util.ArrayList<>();
		open.add(WORLD);
		if (com.mopicmp.npcstudio.client.scene.Playing.scene() != null) open.add(SCENE);
		if (nodeChosen.getAsBoolean()) open.add(BLOCK);
		return open;
	}

	// ------------------------------------------------------------- the routing

	/**
	 * Where a change to this kind of thing will <em>actually</em> land.
	 *
	 * Different from {@link #choose} because a receiver can be in use and still be
	 * the wrong sort of home for a particular value. A costume is not a keyframe
	 * and never becomes one, so dressing a character with a scene open dresses the
	 * character — it does not put a costume key on the timeline.
	 *
	 * Panels show what this returns rather than what is selected. That difference
	 * is the point: the promise is that nobody has to work out where a slider is
	 * writing to, and a receiver that is displayed but overruled would break the
	 * promise in exactly the confusing direction.
	 */
	public static Landing of(Property property, Landing chosen) {
		return switch (property) {
			// Always the world. See Property.CHARACTER for why this can never change.
			case CHARACTER -> WORLD;
			// Only a scene has a clock. Elsewhere the change applies and is looked at,
			// which is worth having — that is how a pose is set up before recording it.
			case CURVE -> chosen == SCENE ? SCENE : WORLD;
			case WORLD, MOMENT -> chosen;
		};
	}

	public static Landing of(Property property) {
		return of(property, now());
	}

	// ------------------------------------------------------------- the telling

	private static final int RECORDS = 0xFF4FC3F7;
	private static final int LIVE_ONLY = 0xFF8A99A6;

	/**
	 * The one line that says where this panel's next edit will go.
	 *
	 * Drawn by the panel rather than by the dock, and that is deliberate: it has to
	 * sit next to the controls it is about. A mark up in the chrome would be read
	 * as being about the window, and the whole promise here is that moving
	 * <em>this</em> slider does <em>that</em>.
	 *
	 * Two colours and no more. Lit means the edit is written down somewhere; dim
	 * means it applies and is not saved, which is the one state somebody can lose
	 * an afternoon to and therefore the one that has to look different.
	 *
	 * @return how wide the mark came out, for a caller placing something after it
	 */
	public static int mark(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
			net.minecraft.client.gui.Font font, int x, int y, Property property) {
		Landing where = of(property);
		boolean kept = records(property, now());
		int ink = kept ? RECORDS : LIVE_ONLY;
		where.icon.draw(graphics, x, y - 4, ink);
		net.minecraft.network.chat.Component said = kept ? where.title()
			: net.minecraft.network.chat.Component.translatable("npc_studio.landing.live");
		graphics.text(font, said, x + Icon.SIZE + 3, y, ink);
		return Icon.SIZE + 3 + font.width(said);
	}

	/**
	 * Whether this receiver records a change of that kind, or only shows it.
	 *
	 * "Only shows it" is the state a panel has to be able to say out loud. A curve
	 * moved with no scene open is not ignored and is not saved either, and somebody
	 * who is not told that will find out by losing an afternoon's work.
	 */
	public static boolean records(Property property, Landing chosen) {
		return switch (property) {
			case CHARACTER, WORLD -> true;
			case CURVE -> of(property, chosen) == SCENE;
			case MOMENT -> true;
		};
	}
}
