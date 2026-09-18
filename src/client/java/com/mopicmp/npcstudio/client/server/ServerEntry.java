package com.mopicmp.npcstudio.client.server;

import com.mopicmp.npcstudio.client.workspace.Icon;
import com.mopicmp.npcstudio.client.workspace.IconTextButton;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

/**
 * The way in, from the screen where somebody is already thinking about servers.
 *
 * A corner icon and not a seventh button. The multiplayer screen already carries
 * six along the bottom in two rows, laid out for that number; adding to them
 * pushes the rows about and puts ours among five vanilla ones as though it were
 * one of them. It is not — everything else there joins a server that exists, and
 * this makes one.
 */
public final class ServerEntry {

	private ServerEntry() {
	}

	private static final int ACCENT = 0xFF4FC3F7;

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof JoinMultiplayerScreen)) return;
			// getWidgets rather than a list of buttons: this version of the API has
			// one list of everything drawable on the screen, and a widget added to
			// it is drawn and clicked like any of the vanilla ones.
			Screens.getWidgets(screen).add(new IconTextButton(
				width - IconTextButton.FOLDED - 6, 6,
				IconTextButton.FOLDED, IconTextButton.FOLDED,
				Icon.SERVER, Component.translatable("npc_studio.server.entry"), ACCENT,
				() -> ServerListScreen.show(client, screen)));
		});
	}
}
