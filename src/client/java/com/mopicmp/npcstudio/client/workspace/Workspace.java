package com.mopicmp.npcstudio.client.workspace;

import java.util.ArrayList;
import java.util.List;

import com.mopicmp.npcstudio.entity.NpcEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * What the whole workspace agrees on: who is selected.
 *
 * One value, and it is worth a class of its own because everything to the right
 * of the viewport now answers to it. The screens this replaced each carried
 * their own entity id, passed in at the door, and that is exactly what made a
 * scene with two characters impossible: to change the second one you left the
 * first one's editor and came back through another door.
 *
 * Static, because it outlives the screen. Closing the workspace to look at
 * something and opening it again should not lose the selection any more than
 * it should lose the layout.
 */
public final class Workspace {

	private static int selected = -1;

	/**
	 * The shared flag that means "glowing", as {@code Entity.FLAG_GLOWING} has it.
	 *
	 * Written out here because the constant cannot live where it is used from —
	 * see {@code EntityAccessor}, which may not hold a field at all.
	 */
	private static final int GLOWING = 6;

	/**
	 * A panel asking another panel for an answer.
	 *
	 * The character's four motion slots are chosen from the animation library,
	 * and the library is a panel of its own now rather than a screen opened on
	 * top. So the request has to be left somewhere both can see: what is set at
	 * the moment, and what to do with the choice.
	 */
	public record Pick(int about, String current,
			java.util.function.Consumer<String> chose) {

		/**
		 * Who the question was asked about.
		 *
		 * <h2>Why a request has to name a character at all</h2>
		 *
		 * Because the answer arrives later, and by then the selection may have moved.
		 * It was reported as an animation landing on the wrong NPC, and that is exactly
		 * what it was: the request was "which animation", with no record of whose, so
		 * the panel wrote the answer into whatever character it happened to be showing
		 * when the answer came back.
		 *
		 * Nothing about the picker changed to fix it. What changed is that the question
		 * carries its own subject, so an answer can be checked against it — and the
		 * check belongs to whoever asked, since they are the ones who know what the
		 * answer is for.
		 */
	}

	private static Pick animationPick;

	private Workspace() { }

	public static void askAnimation(Pick request) {
		animationPick = request;
		// Asking without showing where the answer is given would be a button that
		// appears to do nothing, which is how the old screens taught the lesson.
		WorkspaceScreen.reveal("animation");
	}

	public static Pick animationPick() {
		return animationPick;
	}

	public static void answered() {
		animationPick = null;
	}

	/**
	 * Whether the player's own body is drawn while the workspace is open.
	 *
	 * Off to begin with. The camera has left the body and the body is standing
	 * wherever it was left — usually in the middle of the scene being built,
	 * looking at nothing, in the shot. It is not part of the scene and it should
	 * not be in the picture unless somebody says so.
	 */
	private static boolean showPlayer;

	public static boolean showPlayer() {
		return showPlayer;
	}

	public static void showPlayer(boolean shown) {
		showPlayer = shown;
	}

	/**
	 * Selects something, and lights it up.
	 *
	 * The outline is the game's own glow — the one a spectral arrow leaves — set
	 * on the client's copy only. Nobody else sees it, which is right: this is not
	 * a property of the character, it is a note about which one is being worked
	 * on. Written here rather than left to each panel because the previous one has
	 * to be turned off, and the only place that knows what the previous one was is
	 * the place holding the selection.
	 */
	public static void select(int entityId) {
		glow(selection(), false);
		selected = entityId;
		glow(selection(), true);
	}

	/** Puts the outline back on after the world has been away, and takes it off. */
	public static void lit(boolean on) {
		glow(selection(), on);
	}

	/**
	 * Lights an entity up on this client only.
	 *
	 * Through the shared flag rather than through {@code setGlowingTag}, which
	 * does nothing at all on a client — see {@code EntityAccessor} for why. That
	 * was the whole of "the highlight does not work".
	 */
	private static void glow(Entity entity, boolean on) {
		if (entity == null) return;
		((com.mopicmp.npcstudio.client.mixin.EntityAccessor) entity)
			.npcStudio$setSharedFlag(GLOWING, on);
	}

	/**
	 * Lets go of the world, and of whoever was chosen in it.
	 *
	 * <h2>Why a number has to be forgotten</h2>
	 *
	 * Because it is an entity id, and ids are handed out per world and handed out
	 * again in the next. Carried across, the selection does not point at nobody —
	 * which would be harmless — it points at whatever happens to hold that number
	 * where you have arrived. Every panel here answers to the selection, so the
	 * costume panel would be dressing a stranger and the handles would be standing
	 * on one.
	 *
	 * This is the third statement of the same hazard in this codebase: the undo
	 * history says it, the remembered skins say it, and the scene's cast says it.
	 * The selection is where all three of those get their subject from, and it was
	 * the one that never let go.
	 *
	 * The glow is not put out first, deliberately. The entity it was on belongs to a
	 * level that is going away with it, and reaching into a discarded world to tidy
	 * a flag nobody will read is work with a null waiting in it.
	 */
	public static void forget() {
		selected = -1;
		animationPick = null;
		showPlayer = false;
	}

	public static int selected() {
		return selected;
	}

	/** The selected thing, or null when nothing is selected or it has gone. */
	public static Entity selection() {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null || selected < 0) return null;
		return client.level.getEntity(selected);
	}

	public static NpcEntity selectedNpc() {
		return selection() instanceof NpcEntity npc ? npc : null;
	}

	/**
	 * The characters in the scene: every NPC the client has loaded.
	 *
	 * Not a list somebody curates. A scene is built out of what is standing
	 * there, and asking people to also register their characters in a list is a
	 * second thing to keep in step with the first.
	 */
	public static List<NpcEntity> cast() {
		Minecraft client = Minecraft.getInstance();
		List<NpcEntity> found = new ArrayList<>();
		if (client.level == null) return found;
		for (Entity entity : client.level.entitiesForRendering()) {
			if (entity instanceof NpcEntity npc) found.add(npc);
		}
		found.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
		return found;
	}

	/**
	 * The things in the scene that are not people: models somebody has placed.
	 *
	 * Kept apart from the characters rather than mixed in, because the two answer
	 * different panels — a wardrobe is about a character and a bone of a ship is
	 * not — and because a list where the ship's wheel sits between two sailors
	 * reads as a list of sailors with a mistake in it.
	 *
	 * They belong in the scene all the same, and that is the whole reason this
	 * exists: a wheel that has to turn while somebody's hands are on it is a
	 * participant in exactly the sense a person is.
	 */
	public static List<com.mopicmp.npcstudio.entity.ModelObject> things() {
		Minecraft client = Minecraft.getInstance();
		List<com.mopicmp.npcstudio.entity.ModelObject> found = new ArrayList<>();
		if (client.level == null) return found;
		for (Entity entity : client.level.entitiesForRendering()) {
			if (entity instanceof com.mopicmp.npcstudio.entity.ModelObject object) found.add(object);
		}
		found.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
		return found;
	}
}
