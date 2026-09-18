package com.mopicmp.npcstudio.client.workspace;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * The way in.
 *
 * A key rather than a menu entry or an item, because the workspace is not a
 * thing in the world and does not belong to anything in it: it is opened to
 * work, the way a program is opened, and there is nothing to walk up to first.
 *
 * <h2>Why they are bound, after being unbound on purpose</h2>
 *
 * They used to arrive with no key at all, and the reason written here was that a mod
 * taking a letter of the keyboard has decided something that is not its to decide.
 *
 * That was polite and it was wrong. The report was that the workspace could only be
 * reached by spawning a character and shift-clicking her — which is not a way in at all,
 * it is what somebody does after failing to find one. A door nobody can find is not
 * courtesy; it is a door nobody can find.
 *
 * So they are bound to three letters the vanilla game does not use, and anybody who wants
 * them elsewhere moves them in the same window they would have had to visit anyway. The
 * difference is that they now go there only if something is in the way, rather than going
 * there to discover that anything exists at all.
 *
 * <h2>Who may open it</h2>
 *
 * Operators. Asked of the client, which is enough for a door and not enough for
 * a lock: the client's permission level is what the server told it, so a client
 * that lies about it opens a workspace whose every action the server then
 * refuses. The real check is on each change, where it already is, and the
 * proper answer — the rights window with its levels from none to strict — is a
 * task of its own.
 */
public final class WorkspaceKey {

	private static KeyMapping key;

	/**
	 * The modelling mode, on a key of its own.
	 *
	 * Its own rather than a tab of the workspace, because it is a different job:
	 * building a mast has nothing to say about a character's walking animation.
	 * Axiom draws the same line, and its editor is a mode you enter rather than a
	 * window you open.
	 */
	private static KeyMapping modelling;

	/**
	 * The ring, without the editing window.
	 *
	 * <h2>Why the acts needed a way in from ordinary play</h2>
	 *
	 * Because they are done while walking about. Putting a named place on a doorway,
	 * fixing where a guard comes back to, starting her graph to watch what she does —
	 * all of those happen standing in the scene, and needing the whole workspace
	 * first meant taking the camera off the player's body to act on the ground in
	 * front of them.
	 *
	 * <h2>A press rather than a hold</h2>
	 *
	 * Held would suit a flick of the wrist better, and this is not that: choosing
	 * "put a place here" is followed by typing a name, which cannot be done with a
	 * key held down. So it opens and stays, and the same key or escape puts it away.
	 */
	private static KeyMapping ring;

	private WorkspaceKey() { }

	public static void register() {
		// N for this mod's name, and free in the vanilla game.
		key = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.npc_studio.workspace", InputConstants.Type.KEYSYM,
			InputConstants.KEY_N, KeyMapping.Category.MISC));

		// M for modelling, likewise free.
		modelling = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.npc_studio.modelling", InputConstants.Type.KEYSYM,
			InputConstants.KEY_M, KeyMapping.Category.MISC));

		// G for the ring, and under the left hand on purpose: it is the one of the three
		// used while walking about, so it has to be reachable without letting go of the
		// keys that do the walking.
		ring = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.npc_studio.ring", InputConstants.Type.KEYSYM,
			InputConstants.KEY_G, KeyMapping.Category.MISC));

		ClientTickEvents.END_CLIENT_TICK.register(WorkspaceKey::pressed);
	}

	private static void pressed(Minecraft client) {
		if (client.player == null) return;

		if (ring != null && ring.consumeClick()) {
			// A toggle, and the closing half has to come first: with the ring up this is
			// the key somebody presses to put it away, and opening a second one over the
			// first would leave two.
			if (client.gui.screen() instanceof WorldRingScreen open) {
				open.onClose();
				return;
			}
			// Only from the world. Pressed with any other window up it would open a ring
			// over it about whatever the crosshair was on before that window opened.
			if (client.gui.screen() == null && allowed(client)) {
				WorldRingScreen.show(client);
			}
			return;
		}

		if (modelling != null && modelling.consumeClick() && allowed(client)) {
			com.mopicmp.npcstudio.client.model.ModellingScreen.show(client);
			return;
		}
		if (key != null && key.consumeClick() && allowed(client)) {
			WorkspaceScreen.show(client);
		}
	}

	/**
	 * Whether this player may edit, said out loud when they may not.
	 *
	 * The same level the mod's own commands and the dialogue editor already
	 * require, asked the same way. A second definition of "may edit" is a second
	 * thing to get wrong. A key that silently does nothing is indistinguishable
	 * from a key that is not bound, which is why the refusal is spoken.
	 */
	private static boolean allowed(Minecraft client) {
		if (client.player == null) return false;
		if (net.minecraft.commands.Commands.LEVEL_GAMEMASTERS.check(client.player.permissions())) {
			return true;
		}
		client.player.sendOverlayMessage(Component.translatable("npc_studio.workspace.denied"));
		return false;
	}
}
