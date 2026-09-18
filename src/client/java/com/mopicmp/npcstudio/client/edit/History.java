package com.mopicmp.npcstudio.client.edit;

import java.util.ArrayDeque;
import java.util.Deque;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * What has been done in the world, so that it can be taken back.
 *
 * <h2>Why this exists at all</h2>
 *
 * Because the workspace is being moved from panels to direct action, and those
 * two have opposite manners. A panel forgives: there is a button, and a field
 * that can be typed into again. A gizmo does not — a drag that went wrong has
 * already happened, and "delete" in a menu is one flick.
 *
 * Before this, the whole client had exactly one undo anywhere: a private stack
 * inside the eye-marking screen, for painting. {@code Ctrl+Z} was not handled at
 * all. Handing somebody direct manipulation in that state is handing them a tool
 * that is easier to spoil something with than to build with.
 *
 * <h2>One entry per gesture, not per frame</h2>
 *
 * A drag reports continuously — hundreds of positions between grabbing a handle
 * and letting go. Recording those would make undo mean "step back one pixel" and
 * fill the stack with a single drag. So an entry is made when the gesture
 * <em>finishes</em>, holding where it began and where it ended, which is also
 * exactly what {@code MoveHandles} already had in hand and was throwing away.
 *
 * <h2>What it does not try to be</h2>
 *
 * Shared. The stack belongs to this client and knows only what this client did.
 * On a map being built by two people, undoing a move puts the character where
 * <em>you</em> had it, which is why an entry checks first whether the thing is
 * still where it was left and refuses out loud when it is not. That is the
 * honest half of the answer; the other half — a history everyone agrees on —
 * needs the server to keep it, and is not what was asked for.
 */
public final class History {

	private History() { }

	/**
	 * How far back it goes.
	 *
	 * Entries are small — a couple of positions and an id — so this is not about
	 * memory. It is about a stack that has stopped being useful: undoing past a
	 * hundred gestures is not a correction any more, it is archaeology, and the
	 * bottom of it describes a world that has moved on.
	 */
	private static final int REMEMBERED = 100;

	private static final Deque<Doing> done = new ArrayDeque<>();
	private static final Deque<Doing> undone = new ArrayDeque<>();

	/**
	 * Records something that has just happened.
	 *
	 * Clears the redo pile, which is the ordinary rule and worth stating: once you
	 * have undone three things and then done a fourth, the three no longer follow
	 * from anything. Keeping them would offer to redo a future that is not this
	 * one's.
	 */
	public static void did(Doing doing) {
		done.push(doing);
		while (done.size() > REMEMBERED) done.removeLast();
		undone.clear();
	}

	public static boolean anything() {
		return !done.isEmpty();
	}

	public static void undo() {
		Doing doing = done.peek();
		if (doing == null) {
			say(Component.translatable("npc_studio.history.nothing"));
			return;
		}
		Component refused = doing.undo();
		if (refused != null) {
			// Left on the stack on purpose. The reason it refused is usually
			// temporary — somebody else was moving it, the entity had not loaded —
			// and dropping the entry would mean the correction is gone for good.
			say(refused);
			return;
		}
		done.pop();
		undone.push(doing);
		say(Component.translatable("npc_studio.history.undone", doing.what()));
	}

	public static void redo() {
		Doing doing = undone.peek();
		if (doing == null) {
			say(Component.translatable("npc_studio.history.nothing_ahead"));
			return;
		}
		Component refused = doing.redo();
		if (refused != null) {
			say(refused);
			return;
		}
		undone.pop();
		done.push(doing);
		say(Component.translatable("npc_studio.history.redone", doing.what()));
	}

	/**
	 * Thrown away when the world goes.
	 *
	 * Entity ids are the world's, and they are handed out again in the next one. An
	 * entry kept across a disconnect would eventually name a different character
	 * with a straight face and teleport it somewhere it has never been.
	 */
	public static void forget() {
		done.clear();
		undone.clear();
	}

	private static void say(Component what) {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.player == null) return;
		client.player.sendOverlayMessage(what);
	}
}
