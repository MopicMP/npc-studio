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
 * Unbound by default — a mod that takes a letter of the keyboard on the way in
 * has decided something that is not its to decide.
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

	private WorkspaceKey() { }

	public static void register() {
		key = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.npc_studio.workspace", InputConstants.Type.KEYSYM,
			InputConstants.UNKNOWN.getValue(), KeyMapping.Category.MISC));

		modelling = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.npc_studio.modelling", InputConstants.Type.KEYSYM,
			InputConstants.UNKNOWN.getValue(), KeyMapping.Category.MISC));

		ClientTickEvents.END_CLIENT_TICK.register(WorkspaceKey::pressed);
	}

	private static void pressed(Minecraft client) {
		if (client.player == null) return;

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
