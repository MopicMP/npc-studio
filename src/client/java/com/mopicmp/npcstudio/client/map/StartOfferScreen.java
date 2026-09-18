package com.mopicmp.npcstudio.client.map;

import java.util.List;

import com.mopicmp.npcstudio.client.editor.FlatButton;
import com.mopicmp.npcstudio.client.scene.Shaders;
import com.mopicmp.npcstudio.map.Firmness;
import com.mopicmp.npcstudio.map.MapStart;
import com.mopicmp.npcstudio.map.Setting;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * What the map would like set, and a yes or a no.
 *
 * <h2>Why there is a window at all</h2>
 *
 * Because a suggestion that applies itself is not a suggestion. The other two
 * firmnesses take the settings and give them back; this one takes nothing and
 * therefore has to ask, and asking has to say <em>what</em> — a window offering
 * to "apply the map's recommended settings" without naming them is a window whose
 * only safe answer is no.
 *
 * So every line names one setting, what it is now, and what the map would make
 * it. Three columns is more than a message box and it is the entire content of
 * the decision.
 */
public class StartOfferScreen extends Screen {

	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;
	private static final int PANEL = 0xFF1B2028;
	private static final int EDGE = 0xFF3A424D;
	private static final int SCRIM = 0xB0000000;

	private static final int WIDTH = 260;
	private static final int ROW = 13;
	private static final int PAD = 12;

	private final MapStart start;
	private final List<MapStart.Entry> offered;

	private int left;
	private int top;
	private int tall;

	public StartOfferScreen(MapStart start) {
		super(Component.translatable("npc_studio.start.offer.title"));
		this.start = start;
		this.offered = start.settings().stream()
			.filter(it -> it.firmness() == Firmness.SUGGESTED).toList();
	}

	/** Whether there is a shader worth naming: recommended, and installed here. */
	private boolean shaderOffered() {
		return !start.shaderPack().isEmpty() && Shaders.installed()
			&& !start.shaderPack().equals(Shaders.current());
	}

	@Override
	protected void init() {
		int shader = shaderOffered() ? ROW + 20 + 6 : 0;
		tall = PAD + 12 + 10 + offered.size() * ROW + shader + 10 + 20 + PAD;
		left = (width - WIDTH) / 2;
		top = (height - tall) / 2;

		if (shaderOffered()) {
			// Its own button, not folded into "apply". A shader is the one thing here
			// that can take a playable map down to four frames a second, and agreeing
			// to a brightness is not agreeing to that.
			addRenderableWidget(new FlatButton(left + PAD,
				top + PAD + 22 + offered.size() * ROW + ROW, WIDTH - PAD * 2, 20,
				Component.translatable("npc_studio.start.offer.shader_on", start.shaderPack()),
				EDGE, () -> Shaders.use(start.shaderPack())));
		}

		int buttons = top + tall - PAD - 20;
		int half = (WIDTH - PAD * 2 - 6) / 2;

		addRenderableWidget(new FlatButton(left + PAD, buttons, half, 20,
			Component.translatable("npc_studio.start.offer.yes"), ACCENT, () -> {
				StartOptions.accept(start);
				onClose();
			}));
		addRenderableWidget(new FlatButton(left + PAD + half + 6, buttons, half, 20,
			Component.translatable("npc_studio.start.offer.no"), EDGE, this::onClose));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, SCRIM);
		graphics.fill(left, top, left + WIDTH, top + tall, PANEL);
		graphics.fill(left, top, left + WIDTH, top + 1, EDGE);
		graphics.fill(left, top + tall - 1, left + WIDTH, top + tall, EDGE);
		graphics.fill(left, top, left + 1, top + tall, EDGE);
		graphics.fill(left + WIDTH - 1, top, left + WIDTH, top + tall, EDGE);

		graphics.text(font, title, left + PAD, top + PAD, TEXT);

		int y = top + PAD + 22;
		for (MapStart.Entry entry : offered) {
			Setting what = entry.what();
			graphics.text(font, Component.translatable(what.key()), left + PAD, y, TEXT_DIM);

			// Now, then what it would become. Both, because a number on its own says
			// nothing about whether saying yes changes anything.
			String now = number(what, StartOptions.read(what));
			String next = number(what, entry.bound().applied(StartOptions.read(what), entry.value()));
			int at = left + WIDTH - PAD - font.width(next);
			graphics.text(font, Component.literal(next), at, y, ACCENT);
			graphics.text(font, Component.literal(now + "  →"),
				at - 6 - font.width(now + "  →"), y, TEXT_DIM);
			y += ROW;
		}

		if (shaderOffered()) {
			graphics.text(font, Component.translatable("npc_studio.start.offer.shader"),
				left + PAD, y, TEXT_DIM);
		}

		super.extractRenderState(graphics, mouseX, mouseY, delta);
	}

	private String number(Setting what, double value) {
		if (what.decimals() == 0) return String.valueOf(Math.round(value));
		return String.format(java.util.Locale.ROOT, "%." + what.decimals() + "f", value);
	}

	/**
	 * Not closed by the escape key alone doing nothing else.
	 *
	 * Closing is the "no", and it is a perfectly good answer — the map goes on
	 * being played, with the player's own settings. Nothing here is a gate.
	 */
	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
