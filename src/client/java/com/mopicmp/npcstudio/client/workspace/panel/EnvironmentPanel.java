package com.mopicmp.npcstudio.client.workspace.panel;

import java.util.List;

import com.mopicmp.npcstudio.client.editor.FlatSlider;
import com.mopicmp.npcstudio.client.scene.Playing;
import com.mopicmp.npcstudio.client.scene.Weather;
import com.mopicmp.npcstudio.client.workspace.Icon;
import com.mopicmp.npcstudio.client.workspace.IconTextButton;
import com.mopicmp.npcstudio.client.workspace.WorkspacePanel;
import com.mopicmp.npcstudio.scene.Channels;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * The sky a scene is shot under.
 *
 * <h2>What changed, and why it had to</h2>
 *
 * This panel used to send {@code /time set} and {@code /weather}, which is the
 * obvious thing and the wrong one. Both are commands to the <em>world</em>: they
 * change the sky for everybody, they are saved, and — the part that actually
 * broke the work — they start something that then runs. Rain begun by a command
 * ends a few minutes later, on its own, in the middle of a take. The sun begun at
 * noon is somewhere else by the third attempt at the same shot.
 *
 * So none of it is a command any more. The sky is a part of the scene, with
 * tracks and keys like any other, and it is <b>held</b>: every frame, for as long
 * as the scene is open, the sun is put where the scene says and the rain is set
 * to what the scene says. Nothing is counting down, so nothing can run out; the
 * world underneath keeps its own weather, so closing the scene puts everything
 * back with nothing to undo.
 *
 * <h2>Degrees rather than a time of day</h2>
 *
 * The strip is a whole turn of the sky, because that is the question being asked.
 * "Twenty past six in the evening" is an answer to a different one, and it cannot
 * say "just under the horizon, behind the mast" — which is the sort of thing a
 * shot is actually about. The four named moments are still here as places to
 * start from.
 */
public class EnvironmentPanel extends WorkspacePanel {

	private static final int PAD = 8;
	private static final int LABEL = 10;
	private static final int ROW = 18;
	private static final int GAP = 6;

	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;
	private static final int OFF = 0xFF55606B;
	private static final int WARN = 0xFFE57373;

	/**
	 * Where the sun stands at the four moments anybody names, in degrees.
	 *
	 * The game's own clock turned into angles, so that pressing "sunset" puts the
	 * sun where sunset puts it rather than near it: a day is twenty-four thousand
	 * ticks to a whole turn, and nought is noon.
	 */
	private static final int[] MOMENTS = { 1000, 6000, 13000, 18000 };

	@Override
	public String id() {
		return "environment";
	}

	@Override
	public int minimumWidth() {
		return 150;
	}

	/**
	 * As tall as the panel actually lays out, walked by the same code that lays it.
	 *
	 * <h2>Why it is measured and not written down</h2>
	 *
	 * Because it was written down, twice, and the two drifted the moment a row was
	 * added. The layout grew a second sun strip and a darkening row; the number
	 * underneath was updated by hand and came out forty pixels short — so at the
	 * smallest size the dock would allow, the last control was simply below the
	 * bottom edge and clipped away.
	 *
	 * The same fault as the timeline's left column, in the same shape: two numbers
	 * obliged to agree, computed in two places. So {@link #lay} walks the rows once
	 * and either builds them or only measures, and there is one arrangement rather
	 * than two descriptions of one.
	 */
	@Override
	public int minimumHeight() {
		return lay(false) + PAD;
	}

	/** Where each heading goes, worked out while the rows are laid and drawn after. */
	private int weatherTop;
	private int skyTop;

	private static float degreesOf(int ticks) {
		return ticks / 24000f * 360f;
	}

	@Override
	protected void build() {
		if (Playing.scene() == null) return;
		lay(true);
	}

	/**
	 * The panel's rows, in order, measured or made.
	 *
	 * @param make whether to add the widgets, or only work out how tall this comes to
	 * @return where the last row ends
	 */
	private int lay(boolean make) {
		int across = Math.max(60, width - PAD * 2);
		int y = PAD + LABEL;

		// Two turns, because a sun has two. The first moves it along its arc — up out
		// of the east, over, down into the west — and cannot say "low, behind the mast"
		// when the mast is to the north. The second turns the arc itself about the
		// vertical, which is the direction the sun rises from.
		if (make) {
			add(new FlatSlider(PAD, y, across, ROW,
				Component.translatable("npc_studio.environment.sunArc").getString(),
				0, 360, 1, ACCENT,
				() -> Weather.read(Channels.SUN, degreesOf(6000)),
				picked -> Weather.put(Channels.SUN, picked.floatValue())));
		}
		y += ROW + 2;

		if (make) {
			add(new FlatSlider(PAD, y, across, ROW,
				Component.translatable("npc_studio.environment.sunTurn").getString(),
				0, 360, 1, ACCENT,
				() -> Weather.read(Channels.SUN_TURN, 0),
				picked -> Weather.put(Channels.SUN_TURN, picked.floatValue())));
		}
		y += ROW + GAP;

		y += row(make, y, across, List.of(
			moment(Icon.MORNING, 0), moment(Icon.NOON, 1),
			moment(Icon.SUNSET, 2), moment(Icon.NIGHT, 3)));
		y += GAP + LABEL;

		// Switches rather than buttons: a button says "start raining" and a switch
		// says "it is raining", and only the second one is a thing a scene can hold.
		// Which is which is said by the colour, since a row of icons has nowhere to
		// put the word "on".
		weatherTop = y - LABEL;
		y += row(make, y, across, List.of(
			switched(Icon.CLEAR, "rain", Channels.RAIN, ACCENT),
			switched(Icon.RAIN, "storm", Channels.STORM, WARN)));
		y += GAP;

		skyTop = y;
		y = colour(make, y, across, SKY, SKY_REST) + GAP;
		sunTop = y;
		y = colour(make, y, across, SUN, WHITE) + GAP;
		rainTop = y;
		y = colour(make, y, across, RAIN, WHITE) + GAP;

		// The dark over everything, which is not weather and lives here anyway: it is
		// the fourth thing the world part carries, and a panel per channel would be a
		// worse answer than a heading. Nought is clear and one is black, and the whole
		// use of it is the middle — two keys apart on the timeline is a fade.
		fadeTop = y;
		if (make) {
			add(new FlatSlider(PAD, y + LABEL, across, ROW, "", 0, 1, 0.01, DARK,
				() -> Weather.read(Channels.FADE, 0),
				picked -> Weather.put(Channels.FADE, picked.floatValue())));
		}
		return y + LABEL + ROW;
	}

	/** A row of buttons, or the room one would take. */
	private int row(boolean make, int y, int across, List<IconTextButton.Spec> specs) {
		if (make) return IconTextButton.row(PAD, y, across, ROW, specs, this::add);
		// Measured without making anything, which needs the same folding rule the row
		// itself uses — so it is asked rather than guessed at, with the widgets thrown
		// away. A handful of objects once per resize is cheaper than a second copy of
		// that rule going quietly out of step.
		int[] tallest = { 0 };
		int rows = IconTextButton.row(PAD, y, across, ROW, specs,
			button -> tallest[0] = Math.max(tallest[0], button.getY() + button.getHeight() - y));
		return Math.max(rows, tallest[0]);
	}

	private int fadeTop;

	/** The colour of the one slider that is not a colour. */
	private static final int DARK = 0xFF7E57C2;

	/** The three channels of one colour, in the order anybody reads them. */
	private static final String[] SKY = { Channels.SKY_R, Channels.SKY_G, Channels.SKY_B };
	private static final String[] SUN = { Channels.SUN_R, Channels.SUN_G, Channels.SUN_B };
	private static final String[] RAIN = { Channels.RAIN_R, Channels.RAIN_G, Channels.RAIN_B };

	/** What each reads as before anybody has said: the game's own answer. */
	private static final int SKY_REST = 0x78A7FF;
	private static final int WHITE = 0xFFFFFF;

	private int sunTop;
	private int rainTop;

	/**
	 * One colour, as three sliders in the colours they mix.
	 *
	 * Red, green and blue drawn red, green and blue. It is the smallest thing that
	 * can be done and it is most of what makes three identical boxes readable —
	 * the alternative is three sliders labelled R, G and B, which is a label
	 * telling you what a colour is.
	 */
	private int colour(boolean make, int top, int across, String[] parts, int rest) {
		int[] shown = { 0xFFFF5555, 0xFF55DD66, 0xFF5599FF };
		if (make) {
			for (int i = 0; i < parts.length; i++) {
				String channel = parts[i];
				int part = i;
				add(new FlatSlider(PAD, top + LABEL + i * ROW, across - 26, ROW - 2, "",
					0, 255, 1, shown[i],
					() -> Weather.read(channel, (rest >> (16 - 8 * part)) & 0xFF),
					picked -> Weather.put(channel, picked.floatValue())));
			}
		}
		return top + LABEL + parts.length * ROW;
	}

	/** The colour those three come to, for the swatch beside them. */
	private int mixed(String[] parts, int rest) {
		int out = 0;
		for (int i = 0; i < parts.length; i++) {
			int part = (int) Math.clamp(
				Math.round(Weather.read(parts[i], (rest >> (16 - 8 * i)) & 0xFF)), 0, 255);
			out |= part << (16 - 8 * i);
		}
		return out;
	}

	private void swatch(GuiGraphicsExtractor graphics, int top, int colour) {
		int left = width - PAD - 20;
		graphics.fill(left - 1, top + LABEL - 1, left + 21, top + LABEL + ROW * 3 - 3, 0xFF2C333D);
		graphics.fill(left, top + LABEL, left + 20, top + LABEL + ROW * 3 - 4, 0xFF000000 | colour);
	}

	private IconTextButton.Spec moment(Icon icon, int which) {
		return new IconTextButton.Spec(icon,
			Component.translatable("npc_studio.environment.hour" + which), ACCENT,
			() -> Weather.put(Channels.SUN, degreesOf(MOMENTS[which])));
	}

	/**
	 * One weather switch.
	 *
	 * Three states rather than two, and the third is the one that matters: a scene
	 * that says nothing about the rain leaves the world's own weather alone. So
	 * pressing it turns it on, pressing it again turns it off — and holding shift
	 * takes the channel out altogether, which hands the sky back.
	 */
	private IconTextButton.Spec switched(Icon icon, String name, String channel, int lit) {
		boolean on = Weather.says(channel) && Weather.read(channel, 0) > 0.5f;
		return new IconTextButton.Spec(icon,
			Component.translatable("npc_studio.environment." + name), on ? lit : OFF,
			() -> {
				if (hasShiftDown()) {
					Weather.clear(channel);
				} else {
					Weather.put(channel, on ? 0f : 1f);
				}
				rebuild();
			});
	}

	private static boolean hasShiftDown() {
		var window = net.minecraft.client.Minecraft.getInstance().getWindow();
		return com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, 340)
			|| com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, 344);
	}

	@Override
	public void tick() {
		// The switches show what the document says, so they are rebuilt when it
		// changes under them — scrubbing the cursor across a key that turns the rain
		// on has to light the switch, or the panel and the sky disagree.
		if (Playing.scene() == null) return;
		boolean rain = Weather.says(Channels.RAIN) && Weather.read(Channels.RAIN, 0) > 0.5f;
		boolean storm = Weather.says(Channels.STORM) && Weather.read(Channels.STORM, 0) > 0.5f;
		if (rain != wasRain || storm != wasStorm) {
			wasRain = rain;
			wasStorm = storm;
			rebuild();
		}
	}

	private boolean wasRain;
	private boolean wasStorm;

	@Override
	protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (Playing.scene() == null) {
			graphics.text(font, Component.translatable("npc_studio.pose.no_scene"),
				PAD, PAD, TEXT_DIM);
			return;
		}
		graphics.text(font, Component.translatable("npc_studio.environment.sun"),
			PAD, PAD, TEXT_DIM);
		graphics.text(font, Component.translatable("npc_studio.environment.weather"),
			PAD, weatherTop, TEXT_DIM);
		// The colour itself, beside the three numbers that make it. Three sliders are
		// three numbers; a person choosing a sky is choosing a colour, and the only
		// honest way to show one is to show it.
		graphics.text(font, Component.translatable("npc_studio.environment.sky"),
			PAD, skyTop, TEXT_DIM);
		swatch(graphics, skyTop, mixed(SKY, SKY_REST));
		graphics.text(font, Component.translatable("npc_studio.environment.sunColour"),
			PAD, sunTop, TEXT_DIM);
		swatch(graphics, sunTop, mixed(SUN, WHITE));
		graphics.text(font, Component.translatable("npc_studio.environment.rainColour"),
			PAD, rainTop, TEXT_DIM);
		swatch(graphics, rainTop, mixed(RAIN, WHITE));
		graphics.text(font, Component.translatable("npc_studio.environment.fade"),
			PAD, fadeTop, TEXT_DIM);
	}
}
