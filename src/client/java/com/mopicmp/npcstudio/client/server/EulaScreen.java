package com.mopicmp.npcstudio.client.server;

import com.mopicmp.npcstudio.client.editor.FlatButton;
import com.mopicmp.npcstudio.client.workspace.Icon;
import com.mopicmp.npcstudio.client.workspace.IconTextButton;
import com.mopicmp.npcstudio.server.Eula;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The agreement, asked for once and never assumed.
 *
 * Standing between the creation form and the creation itself, because that is
 * exactly where it belongs: managing a server on somebody else's hosting runs no
 * core on this machine and needs no agreement to anything, and only creating one
 * does.
 *
 * The terms themselves are not copied here. They are Mojang's, they change, and
 * a stale copy shown as the thing being agreed to would be worse than a link to
 * the real one — so this says what agreeing means, and opens theirs.
 */
public class EulaScreen extends Screen {

	private static final int CANVAS = 0xFF101318;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int GOOD = 0xFF66BB6A;
	private static final int ACCENT = 0xFF4FC3F7;

	private static final int WIDTH = 320;

	private final Screen back;
	private final Runnable agreed;

	public EulaScreen(Screen back, Runnable agreed) {
		super(Component.translatable("npc_studio.server.eula.title"));
		this.back = back;
		this.agreed = agreed;
	}

	@Override
	protected void init() {
		int left = width / 2 - WIDTH / 2;
		int y = height / 2 + 30;

		addRenderableWidget(new FlatButton(left, y, WIDTH, 18,
			Component.literal(Eula.URL), ACCENT,
			() -> net.minecraft.util.Util.getPlatform().openUri(Eula.URL)));

		addRenderableWidget(new IconTextButton(left, y + 26, WIDTH / 2 - 4, 20,
			Icon.CHECK, Component.translatable("npc_studio.server.eula.accept"), GOOD,
			() -> {
				// Nothing is written here. The press is carried to the creation,
				// which is the only place that knows which folder the agreement
				// belongs in — one server, one file, one act of agreeing.
				minecraft.setScreenAndShow(back);
				agreed.run();
			}));

		addRenderableWidget(new IconTextButton(left + WIDTH / 2 + 4, y + 26, WIDTH / 2 - 4, 20,
			Icon.CANCEL, Component.translatable("npc_studio.server.eula.decline"), TEXT_DIM,
			() -> minecraft.setScreenAndShow(back)));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, CANVAS);

		int left = width / 2 - WIDTH / 2;
		graphics.text(font, title, left, height / 2 - 60, TEXT);
		graphics.textWithWordWrap(font, Component.translatable("npc_studio.server.eula.body"),
			left, height / 2 - 42, WIDTH, TEXT_DIM);

		super.extractRenderState(graphics, mouseX, mouseY, delta);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
