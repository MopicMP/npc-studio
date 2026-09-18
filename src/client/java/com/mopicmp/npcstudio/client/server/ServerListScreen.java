package com.mopicmp.npcstudio.client.server;

import java.util.List;

import com.mopicmp.npcstudio.client.workspace.Icon;
import com.mopicmp.npcstudio.client.workspace.IconTextButton;
import com.mopicmp.npcstudio.server.ManagedServer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Which server, before anything else.
 *
 * The first door, and it disappears when there is nothing behind it: with no
 * servers at all this goes straight to making one. A list of nothing, with one
 * button on it, is a screen that exists only to be passed through.
 */
public class ServerListScreen extends Screen {

	private static final int CANVAS = 0xFF101318;
	private static final int BAR = 0xFF12161C;
	private static final int EDGE = 0xFF2C333D;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int GOOD = 0xFF66BB6A;

	private static final int TOP = 22;
	private static final int ROW = 34;
	private static final int WIDTH = 300;

	private final Screen back;
	private final ServerManager manager = ServerManager.get();
	private List<ManagedServer> listed = List.of();
	private int scroll;
	private boolean straightToCreate = true;

	public ServerListScreen(Screen back) {
		super(Component.translatable("npc_studio.server.title"));
		this.back = back;
	}

	/**
	 * Opens over a screen, remembering it as the way back.
	 *
	 * The screen has to be handed in rather than read off the client: this
	 * version keeps no public handle on the one that is showing, and the caller —
	 * a button on that very screen — always has it.
	 */
	public static void show(Minecraft client, Screen from) {
		client.setScreenAndShow(new ServerListScreen(from));
	}

	@Override
	protected void init() {
		manager.reload();
		listed = manager.servers();

		if (listed.isEmpty() && straightToCreate) {
			// Once only: coming back from a cancelled creation should land here,
			// not bounce into the same form again.
			straightToCreate = false;
			minecraft.setScreenAndShow(new CreateServerScreen(this));
			return;
		}

		addRenderableWidget(new IconTextButton(4, 3, 16, 16, Icon.CLOSED,
			Component.translatable("npc_studio.server.back"), TEXT_DIM, this::onClose));

		int left = width / 2 - WIDTH / 2;
		int fits = Math.max(1, (height - TOP - 44) / ROW);
		scroll = Math.clamp(scroll, 0, Math.max(0, listed.size() - fits));

		for (int i = 0; i < fits && scroll + i < listed.size(); i++) {
			ManagedServer server = listed.get(scroll + i);
			addRenderableWidget(new Row(left, TOP + i * ROW, WIDTH, ROW - 4, server,
				() -> minecraft.setScreenAndShow(new ServerManagerScreen(this, server.id))));
		}

		addRenderableWidget(new IconTextButton(left, height - 30, WIDTH, 20, Icon.ADD,
			Component.translatable("npc_studio.server.create"), GOOD,
			() -> minecraft.setScreenAndShow(new CreateServerScreen(this))));
	}

	/** One server in the list: its own picture, its name, and what it is. */
	private final class Row extends AbstractWidget {

		private final ManagedServer server;
		private final Runnable onPress;

		Row(int x, int y, int width, int height, ManagedServer server, Runnable onPress) {
			super(x, y, width, height, Component.literal(server.name));
			this.server = server;
			this.onPress = onPress;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor graphics,
				int mouseX, int mouseY, float delta) {
			boolean lit = isHovered();
			graphics.fill(getX(), getY(), getX() + width, getY() + height, EDGE);
			graphics.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1,
				lit ? 0xFF232A34 : 0xFF161A20);

			int size = height - 8;
			Identifier icon = ServerIcon.of(server);
			if (icon != null) {
				graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, icon,
					getX() + 4, getY() + 4, 0, 0, size, size, 64, 64, 64, 64);
			} else {
				Icon.SERVER.draw(graphics, getX() + 4 + (size - Icon.SIZE) / 2,
					getY() + 4 + (size - Icon.SIZE) / 2, TEXT_DIM);
			}

			int textLeft = getX() + size + 10;
			graphics.text(minecraft.font, server.name.isBlank() ? server.id : server.name,
				textLeft, getY() + 6, lit ? TEXT : 0xFFCFD8DC);
			graphics.text(minecraft.font, server.core + " " + server.gameVersion,
				textLeft, getY() + 17, TEXT_DIM);

			boolean up = manager.up(server);
			graphics.fill(getX() + width - 10, getY() + height / 2 - 2,
				getX() + width - 6, getY() + height / 2 + 2, up ? GOOD : 0xFF3A424D);
		}

		@Override
		public void onClick(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
			onPress.run();
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}

	@Override
	public void tick() {
		// Kept ticking so the dots in the list say what is up without opening one.
		manager.tick();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, CANVAS);
		graphics.fill(0, 0, width, TOP - 4, BAR);
		graphics.fill(0, TOP - 5, width, TOP - 4, EDGE);
		graphics.text(font, title, width / 2 - font.width(title) / 2, 5, TEXT);
		super.extractRenderState(graphics, mouseX, mouseY, delta);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
		if (listed.size() > 1) {
			scroll = Math.max(0, scroll - (int) Math.signum(dy));
			clearWidgets();
			init();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, dx, dy);
	}

	@Override
	public void onClose() {
		minecraft.setScreenAndShow(back);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
