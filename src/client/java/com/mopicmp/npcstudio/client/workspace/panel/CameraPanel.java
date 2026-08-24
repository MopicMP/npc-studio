package com.mopicmp.npcstudio.client.workspace.panel;

import java.util.List;

import com.mopicmp.npcstudio.client.editor.FlatSlider;
import com.mopicmp.npcstudio.client.scene.Playing;
import com.mopicmp.npcstudio.client.scene.SceneCameras;
import com.mopicmp.npcstudio.client.workspace.Icon;
import com.mopicmp.npcstudio.client.workspace.IconTextButton;
import com.mopicmp.npcstudio.client.workspace.Workspace;
import com.mopicmp.npcstudio.client.workspace.WorkspacePanel;
import com.mopicmp.npcstudio.scene.Channels;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * The shot: where it is taken from, which way it points, and how wide it is.
 *
 * <h2>Why numbers and not only handles</h2>
 *
 * The arrows and the ring put a camera roughly where it belongs, and roughly is
 * the wrong precision for the one object in a scene whose job is exactly this.
 * A shot that has to sit level, or exactly two blocks above the deck, or turned
 * to a round ninety degrees is not something any amount of dragging arrives at —
 * the same argument the modelling panel makes about a mast eight pixels from the
 * middle, and the reason both exist beside their gizmos rather than instead.
 *
 * <h2>Why every box writes a key</h2>
 *
 * Because a camera has no place to put a number that is not a key. Everything
 * else in a scene has a body standing in the world that a scene may or may not
 * have an opinion about; this exists only in the document, so typing into it is
 * keying it at the cursor. The consequence worth knowing is the one the pose
 * panel has: the cursor is part of every edit, so moving the camera at tick 40
 * and again at tick 80 is a shot that travels between them.
 *
 * <h2>Looking through it is not a second picture</h2>
 *
 * The button hands the game's own camera over to this one. That is the whole
 * mechanism and it is why it costs nothing: the world is already being drawn from
 * a place and a direction, so filming from somewhere else is the same picture
 * rather than another one. What it cannot do is show the shot and the scene at
 * the same time, which is what a little window in the corner would be — a second
 * render of the world, and a much larger thing. See {@code docs/deferred.md}.
 */
public class CameraPanel extends WorkspacePanel {

	private static final int PAD = 8;
	private static final int ROW = 16;
	private static final int LABEL = 52;
	private static final int BUTTON = 18;

	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;
	private static final int GOOD = 0xFF66BB6A;
	private static final int WARN = 0xFFE57373;
	private static final int OFF = 0xFF55606B;

	/** The lens, in degrees of the vertical angle. The wheel obeys the same range. */
	private static final float NARROWEST = SceneCameras.NARROWEST;
	private static final float WIDEST = SceneCameras.WIDEST;

	@Override
	public String id() {
		return "camera";
	}

	@Override
	public int minimumWidth() {
		return 190;
	}

	@Override
	public int minimumHeight() {
		return PAD + BUTTON * 2 + 6 + (ROW + 10) * 3 + PAD;
	}

	// ------------------------------------------------------------------ widgets

	/** Where each group of boxes ended up, so its name can be drawn beside it. */
	private int placeTop;
	private int exportTop;
	private int turnTop;
	private int lensTop;

	@Override
	protected void build() {
		if (Playing.scene() == null) return;

		int across = width - PAD * 2;
		if (across < 60) return;

		if (SceneCameras.part() == null) {
			IconTextButton.row(PAD, PAD, across, BUTTON, List.of(
				new IconTextButton.Spec(Icon.CAMERA,
					Component.translatable("npc_studio.camera.add"), ACCENT, SceneCameras::add)),
				this::add);
			return;
		}

		int y = PAD;
		y += IconTextButton.row(PAD, y, across, BUTTON, List.of(
			new IconTextButton.Spec(Icon.CAMERA,
				Component.translatable("npc_studio.camera.through"),
				SceneCameras.through() ? GOOD : OFF, this::lookThrough),
			new IconTextButton.Spec(Icon.FOCUS,
				Component.translatable("npc_studio.camera.here"), ACCENT, SceneCameras::putHere),
			new IconTextButton.Spec(Icon.CHECK,
				Component.translatable("npc_studio.camera.key"), GOOD, SceneCameras::keyAll),
			new IconTextButton.Spec(Icon.REMOVE,
				Component.translatable("npc_studio.camera.remove"), WARN, SceneCameras::remove)),
			this::add);
		y += 6;

		// The film, from the panel that is about the film. It was an unlabelled icon
		// at the far end of the timeline's button strip, which is a fine place for a
		// thing you press while editing and a poor one for the thing the whole
		// workspace is for.
		exportTop = y;
		y += IconTextButton.row(PAD, y, across, BUTTON, List.of(
			new IconTextButton.Spec(Icon.IMAGE,
				Component.translatable(com.mopicmp.npcstudio.client.scene.Capture.armed()
					? "npc_studio.camera.exportGo" : "npc_studio.camera.export"),
				com.mopicmp.npcstudio.client.scene.Capture.running() ? WARN
					: com.mopicmp.npcstudio.client.scene.Capture.armed() ? GOOD : ACCENT,
				this::film),
			// Which rectangle is the film. The window is every pixel drawn and hides
			// the panels while it runs; the viewport is what the hole shows and leaves
			// them up. Neither is right for everybody, so it is a switch.
			new IconTextButton.Spec(Icon.BARE,
				Component.translatable(com.mopicmp.npcstudio.client.scene.Framing.toViewport()
					? "npc_studio.camera.frameViewport" : "npc_studio.camera.frameWindow"),
				com.mopicmp.npcstudio.client.scene.Framing.toViewport() ? GOOD : ACCENT,
				() -> {
					com.mopicmp.npcstudio.client.scene.Framing.toViewport(
						!com.mopicmp.npcstudio.client.scene.Framing.toViewport());
					com.mopicmp.npcstudio.client.scene.Capture.disarm();
					rebuild();
				})),
			this::add);
		y += 6;

		int field = width - PAD * 2 - LABEL;
		placeTop = y;
		triple(y, field, Channels.X, Channels.Y, Channels.Z);
		y += ROW + 4;

		// Two rather than three: a camera has no roll. Not because it could not have
		// one, but because it would be a fourth number nobody has asked for and a
		// tilted horizon is the sort of thing you want to have chosen on purpose.
		turnTop = y;
		pair(y, field, Channels.YAW, Channels.PITCH);
		y += ROW + 4;

		lensTop = y;
		add(new FlatSlider(PAD + LABEL, y, field, ROW - 2, "",
			NARROWEST, WIDEST, 1, ACCENT,
			() -> value(Channels.FOV),
			picked -> SceneCameras.put(Channels.FOV, picked.floatValue())));
	}

	private void triple(int y, int field, String first, String second, String third) {
		int each = (field - 8) / 3;
		if (each < 24) return;
		number(PAD + LABEL, y, each, first);
		number(PAD + LABEL + each + 4, y, each, second);
		number(PAD + LABEL + (each + 4) * 2, y, each, third);
	}

	private void pair(int y, int field, String first, String second) {
		int each = (field - 4) / 2;
		if (each < 24) return;
		number(PAD + LABEL, y, each, first);
		number(PAD + LABEL + each + 4, y, each, second);
	}

	/**
	 * One typed number.
	 *
	 * A half-typed number is ignored rather than announced, which is the modelling
	 * panel's rule and it is right for the same reason: "1" on the way to "16" is a
	 * perfectly good number that nobody meant, and "-" on the way to "-4" is not a
	 * number at all. Complaining on either would be complaining about typing.
	 */
	private void number(int x, int y, int across, String channel) {
		EditBox box = new EditBox(font, x, y, across, ROW - 4, Component.literal(""));
		box.setValue(show((float) value(channel)));
		box.setResponder(text -> {
			try {
				SceneCameras.put(channel, Float.parseFloat(text.trim()));
			} catch (NumberFormatException halfTyped) {
				// Left alone on purpose. See the note above.
			}
		});
		add(box);
	}

	private double value(String channel) {
		return SceneCameras.read(channel, SceneCameras.resting(channel));
	}

	private static String show(float value) {
		return String.format(java.util.Locale.ROOT, "%.2f", value);
	}

	// ------------------------------------------------------------------ keeping up

	/** What the boxes were built from, so they can be rebuilt when it moves under them. */
	private float[] built = new float[0];
	private boolean hadCamera;
	private boolean wasThrough;
	private boolean wasFilming;

	/**
	 * Rebuilds the boxes when the document has moved without them.
	 *
	 * It moves constantly: dragging an arrow in the viewport keys the same channels
	 * these boxes show, and so does scrubbing the cursor across a key. Boxes that
	 * did not follow would be a second copy of the numbers disagreeing with the
	 * first, which is the thing every other panel here is written to avoid.
	 *
	 * Never while somebody is typing in one. A rebuild throws the boxes away and
	 * takes the focus with them, so doing it under a hand halfway through a number
	 * would eat the number — and the value being typed is exactly the value that
	 * has just changed, so this would fire on every keystroke.
	 */
	@Override
	public void tick() {
		if (Playing.scene() == null) return;
		boolean has = SceneCameras.part() != null;
		if (has != hadCamera || SceneCameras.through() != wasThrough) {
			hadCamera = has;
			wasThrough = SceneCameras.through();
			rebuild();
			remember();
			return;
		}
		// And the export's state, because the button's word and colour come from it
		// and it changes on its own — a capture that reaches the end of the scene
		// stops without anybody pressing anything.
		boolean filming = com.mopicmp.npcstudio.client.scene.Capture.running()
			|| com.mopicmp.npcstudio.client.scene.Capture.armed();
		if (filming != wasFilming) {
			wasFilming = filming;
			rebuild();
			remember();
			return;
		}
		if (!has || getFocused() != null) return;
		if (moved()) {
			rebuild();
			remember();
		}
	}

	private boolean moved() {
		if (built.length != SceneCameras.SHOT.length) return true;
		for (int i = 0; i < SceneCameras.SHOT.length; i++) {
			// A hundredth, because that is what the boxes show. Any finer and a
			// channel easing through a curve would rebuild the panel every frame.
			if (Math.abs(built[i] - value(SceneCameras.SHOT[i])) > 0.005) return true;
		}
		return false;
	}

	private void remember() {
		built = new float[SceneCameras.SHOT.length];
		for (int i = 0; i < built.length; i++) built[i] = (float) value(SceneCameras.SHOT[i]);
	}

	/**
	 * Arms the export, then runs it.
	 *
	 * Two presses, as the timeline's icon has always been: the first says how many
	 * frames and where they are going, the second does it. That was put in after an
	 * unlabelled icon wrote two hundred megabytes to somebody's disk, and a labelled
	 * button is not a reason to take it out — sixteen hundred frames is still a
	 * quarter of an hour nobody asked for by accident.
	 */
	private void film() {
		if (com.mopicmp.npcstudio.client.scene.Capture.running()) {
			com.mopicmp.npcstudio.client.scene.Capture.stop();
		} else if (com.mopicmp.npcstudio.client.scene.Capture.armed()) {
			com.mopicmp.npcstudio.client.scene.Capture.start();
		} else {
			com.mopicmp.npcstudio.client.scene.Capture.arm();
		}
		rebuild();
	}

	/**
	 * Hands the view to the camera, or takes it back.
	 *
	 * Selecting it on the way in, because the way back out is this same button and
	 * the panel showing it has to be the one in front of somebody. Nothing else
	 * changes about the selection: it is a camera either way.
	 */
	private void lookThrough() {
		boolean on = !SceneCameras.through();
		SceneCameras.through(on);
		if (on && SceneCameras.camera() != null) Workspace.select(SceneCameras.camera().getId());
		rebuild();
	}

	private String trim(String said, int room) {
		if (font.width(said) <= room) return said;
		return font.plainSubstrByWidth(said, Math.max(0, room - font.width("…"))) + "…";
	}

	// ------------------------------------------------------------------ drawing

	@Override
	protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (Playing.scene() == null) {
			graphics.text(font, Component.translatable("npc_studio.pose.no_scene"),
				PAD, PAD, TEXT_DIM);
			return;
		}
		if (SceneCameras.part() == null) {
			graphics.text(font, Component.translatable("npc_studio.camera.none"),
				PAD, PAD + BUTTON + 4, TEXT_DIM);
			return;
		}

		graphics.text(font, Component.translatable("npc_studio.camera.at"),
			PAD, placeTop + 3, TEXT_DIM);
		graphics.text(font, Component.translatable("npc_studio.camera.turn"),
			PAD, turnTop + 3, TEXT_DIM);
		graphics.text(font, Component.translatable("npc_studio.camera.lens"),
			PAD, lensTop + 3, TEXT_DIM);

		// What the export is about to do, or is doing. The bar itself goes in the
		// window's title while it runs — a bar drawn here would be a bar in the film —
		// but the count and the outcome belong beside the button that caused them.
		String about = com.mopicmp.npcstudio.client.scene.Capture.said();
		if (!about.isEmpty()) {
			graphics.text(font, Component.literal(trim(about, width - PAD * 2)),
				PAD, exportTop + BUTTON + 2,
				com.mopicmp.npcstudio.client.scene.Capture.armed() ? GOOD : TEXT_DIM);
		}
		if (SceneCameras.through()) {
			// And how to change the lens without leaving the shot, which is the one
			// thing about a camera that cannot be judged from outside it.
			graphics.text(font, Component.translatable("npc_studio.camera.looking"),
				PAD, lensTop + ROW + 4, GOOD);
			graphics.text(font, Component.translatable("npc_studio.camera.wheel"),
				PAD, lensTop + ROW + 15, TEXT_DIM);
		}
	}
}
