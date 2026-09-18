package com.mopicmp.npcstudio.client.workspace;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The ring, in ordinary play, with no workspace behind it.
 *
 * <h2>Why the acts had to leave the workspace</h2>
 *
 * Because most of what they are for is done while walking about. Putting a named
 * place on a doorway, fixing where a guard comes back to, starting her graph again
 * to watch what she does — every one of those is a thing you do standing in the
 * scene, and requiring the whole editing window first meant taking the camera off
 * the player's body to do something to the ground in front of them.
 *
 * <h2>Why this is a screen and not an overlay</h2>
 *
 * Because choosing needs a cursor, and a cursor needs the mouse ungrabbed, and
 * only a screen ungrabs it. It draws nothing but the ring: no background, no dim,
 * no panels. The world carries on behind it exactly as it was.
 *
 * <h2>Why the subject is fixed when it opens</h2>
 *
 * The crosshair was on something at the moment the key was pressed, and that is
 * what was meant. Following the mouse afterwards would mean the ring is about
 * whatever the cursor drifted over while reading it — and the cursor starts in the
 * middle of the ring, which is over nothing.
 */
public class WorldRingScreen extends Screen {

	private static final int TEXT_DIM = 0xFF8A99A6;

	private final Ring ring = new Ring();
	private final Under subject;

	private WorldRingScreen(Under subject) {
		super(Component.translatable("npc_studio.world.ring"));
		this.subject = subject;
	}

	/**
	 * Opens it on whatever the crosshair is on, or says why it will not.
	 *
	 * Refusing out loud rather than opening an empty ring: a ring with nothing in it
	 * teaches that the key sometimes does nothing, which is the lesson that stops
	 * people pressing it.
	 */
	public static void show(Minecraft client) {
		Under under = Aim.under();
		if (under.nothing()) {
			say(client, Component.translatable("npc_studio.world.nothing"));
			return;
		}
		List<Menu.Entry> entries = WorldActions.forSubject(under);
		if (entries.isEmpty()) {
			say(client, Component.translatable("npc_studio.world.nothing"));
			return;
		}
		client.setScreenAndShow(new WorldRingScreen(under));
	}

	private static void say(Minecraft client, Component what) {
		if (client.player != null) client.player.sendOverlayMessage(what);
	}

	@Override
	protected void init() {
		// Not while a name is being typed. This runs again on every resize of the
		// window, and putting the ring back over the box would cover the thing being
		// typed into with the menu that opened it.
		if (com.mopicmp.npcstudio.client.map.Naming.isOpen()) return;

		List<Menu.Entry> entries = WorldActions.forSubject(subject);
		// In the middle of the window, because that is where the crosshair was and
		// therefore where the eye is. The viewport's ring opens at the cursor for the
		// same reason — it is opened by pointing, so the point is where it belongs.
		ring.open(width / 2, height / 2, entries, 0, 0, width, height);
	}

	/** Nothing behind it. The world is the picture and this is a mark on it. */
	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) { }

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		ring.draw(graphics, font, mouseX, mouseY);
		com.mopicmp.npcstudio.client.map.Naming.draw(graphics, font, width, height);
		if (com.mopicmp.npcstudio.client.map.Naming.isOpen()) return;

		Component hint = Component.translatable("npc_studio.world.ring_hint");
		graphics.text(font, hint, (width - font.width(hint)) / 2, height - 24, TEXT_DIM);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (com.mopicmp.npcstudio.client.map.Naming.isOpen()) return true;
		ring.click(event.x(), event.y());
		// Whatever was chosen may have asked for a name, and that needs this screen to
		// stay: it is the only thing on the display that draws the box or takes a key.
		if (!com.mopicmp.npcstudio.client.map.Naming.isOpen()) onClose();
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (com.mopicmp.npcstudio.client.map.Naming.keyPressed(event.key())) {
			// Naming is over — either placed or dropped — and there is nothing else here
			// to do, so the screen goes with it rather than leaving a ring nobody asked
			// to see again.
			if (!com.mopicmp.npcstudio.client.map.Naming.isOpen()) onClose();
			return true;
		}
		if (com.mopicmp.npcstudio.client.map.Naming.isOpen()) return true;
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (com.mopicmp.npcstudio.client.map.Naming.charTyped(event.codepoint())) return true;
		return super.charTyped(event);
	}

	@Override
	public void onClose() {
		com.mopicmp.npcstudio.client.map.Naming.cancel();
		super.onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
