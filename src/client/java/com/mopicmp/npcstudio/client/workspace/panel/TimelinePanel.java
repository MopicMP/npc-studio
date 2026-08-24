package com.mopicmp.npcstudio.client.workspace.panel;

import java.util.ArrayList;
import java.util.List;

import com.mopicmp.npcstudio.client.scene.Playing;
import com.mopicmp.npcstudio.client.scene.Scenes;
import com.mopicmp.npcstudio.client.workspace.Icon;
import com.mopicmp.npcstudio.client.workspace.WorkspacePanel;
import com.mopicmp.npcstudio.scene.Channels;
import com.mopicmp.npcstudio.scene.Cue;
import com.mopicmp.npcstudio.scene.Key;
import com.mopicmp.npcstudio.scene.Playhead;
import com.mopicmp.npcstudio.scene.Role;
import com.mopicmp.npcstudio.scene.Scene;
import com.mopicmp.npcstudio.scene.Thinning;
import com.mopicmp.npcstudio.scene.Track;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * When things happen.
 *
 * <h2>What changed here</h2>
 *
 * This used to keep its own float and its own boolean, and that is exactly as
 * far as a strip can get on its own: it could show a cursor moving and it could
 * not answer the question the cursor exists for, which is what did we go past.
 * The clock is {@link Playhead} now, the rows are the scene's own tracks, and
 * this is what it always should have been — a view of a document rather than a
 * document pretending to be a view.
 *
 * <h2>One row per number</h2>
 *
 * A character with a moving arm is three rows, not one. That is what the format
 * says and hiding it here would be a kindness that costs the thing rows are for:
 * seeing that the wobble is in one axis and the other two are dead straight.
 * They are grouped under whoever they belong to, which is the part that makes it
 * readable.
 */
public class TimelinePanel extends WorkspacePanel {

	/** How wide a second of the ruler is at rest, in pixels. Twenty ticks. */
	private static final float SECOND = 40f;

	private static final int RULER = 14;
	private static final int ROW = 11;
	private static final int BUTTON = 14;

	/**
	 * How many buttons the strip in the corner carries.
	 *
	 * <h2>Why this is a constant and the column is worked out from it</h2>
	 *
	 * Because the left column and the button strip share a width and used not to
	 * know it. The column was ninety-six pixels because somebody wrote ninety-six;
	 * the strip was however many buttons there happened to be. At six buttons the
	 * two just fitted, with ten pixels left for the open scene's name — which is
	 * why the name had been a bare arrow for a long time and nobody minded.
	 *
	 * Adding a seventh took the strip to a hundred pixels, past the end of its own
	 * column, and the name went with it: it was drawn off the edge and its hit test
	 * became {@code x >= 100 && x < 96}, which is nothing. The scene picker stopped
	 * working, and it stopped working in the one way that leaves no trace — a
	 * rectangle that cannot contain a point.
	 *
	 * So the column is now whatever the strip needs plus room for a name. Two
	 * constants that had to agree became one that the other is worked out from,
	 * which is the only arrangement that cannot drift.
	 */
	private static final int BUTTONS = 7;

	/** Where the open scene's name begins: after the buttons. */
	private static final int NAME_LEFT = BUTTON * BUTTONS + 2;

	/** How much of the name is readable. Enough for a word, not for a sentence. */
	private static final int NAME_ROOM = 60;

	/** The left column: the buttons and the name above, the track names below. */
	private static final int NAMES = NAME_LEFT + NAME_ROOM;

	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;
	private static final int TRACK = 0xFF1B2028;
	private static final int PANEL = 0xFF161A20;
	private static final int TICK_LINE = 0xFF2C333D;
	private static final int PLAYHEAD = 0xFFFF7043;
	private static final int GOOD = 0xFF66BB6A;
	private static final int PAST_END = 0x40000000;

	/** A key somebody has picked out, and the ring drawn round it. */
	/** Music: its cue on the ruler, and the button while something is playing. */
	private static final int MUSIC = 0xFF4DB6AC;

	/** The same, dimmed: how far the piece actually runs. */
	private static final int MUSIC_BAR = 0x804DB6AC;

	/** A line on the screen: its mark on the ruler, and its button while one is up. */
	private static final int CAPTION = 0xFFFFD54A;

	private static final int PICKED = 0xFFFFD54A;
	private static final int PICKED_EDGE = 0xFF3A2E10;

	private float zoom = 1f;
	private boolean scrubbing;

	/** Whether the scene's own end is being dragged. */
	private boolean draggingEnd;

	/** How near the end mark a click must land to take hold of it, in pixels. */
	private static final int GRAB_END = 4;
	private int scroll;
	private boolean choosing;

	@Override
	public String id() {
		return "timeline";
	}

	@Override
	public int minimumHeight() {
		return 64;
	}

	@Override
	public int minimumWidth() {
		// The column plus enough ruler to be a ruler. Worked out rather than written
		// down for the same reason the column is: a squeezed panel where the buttons
		// have eaten the whole width is a timeline with no time in it.
		return NAMES + 120;
	}

	// ------------------------------------------------------------ the geometry

	private float pixelsPerTick() {
		return SECOND * zoom / 20f;
	}

	private int xOf(double ticks) {
		return NAMES + (int) Math.round(ticks * pixelsPerTick());
	}

	private double ticksAt(double px) {
		return Math.max(0, (px - NAMES) / pixelsPerTick());
	}

	/** Where the scene's name starts: after the buttons, in one place so they agree. */
	private static int nameLeft() {
		return NAME_LEFT;
	}

	/**
	 * Thins every recording in the scene down to keys somebody can edit.
	 *
	 * <h2>Why this is a button rather than something recording does on its own</h2>
	 *
	 * Because the dense record is worth keeping until somebody has looked at it. A
	 * thinning is a judgement about what mattered in a movement, and the one thing
	 * you cannot do afterwards is get back what it decided did not — so it happens
	 * when a person says so, having watched the take, and not at the moment the
	 * button was released and they have not seen it yet.
	 *
	 * There is no separate record button, and that is not an omission. Turning a
	 * ring while the scene is playing already writes a key at every tick it passes,
	 * which is what recording <em>is</em>; a mode that had to be armed first would
	 * be a second way to do the thing the handles already do.
	 */
	private void tidy() {
		Scene scene = Playing.scene();
		if (scene == null) return;
		Scenes.keep(Playing.openName(), Thinning.tidied(scene));
	}

	/**
	 * The rows, in the order they are drawn: a heading per part, then its numbers.
	 *
	 * Worked out fresh each frame rather than kept, because the scene is replaced
	 * on every edit and a cached list of rows would be a second thing to keep in
	 * step with it — the exact mistake the wardrobe's stale grid was.
	 */
	private record Row(String role, Track track) { }

	/**
	 * One key somebody has picked out, named by what it belongs to rather than by
	 * where it is in a list.
	 *
	 * By name because the list underneath moves. Thinning a scene, or keying
	 * anything at all, rebuilds every track it touches — so an index into one is a
	 * pointer at whatever has since taken that place, and deleting by it is how a
	 * timeline comes to remove the wrong key.
	 */
	private record Picked(String role, String channel, int tick) { }

	private final java.util.Set<Picked> picked = new java.util.LinkedHashSet<>();

	/** How near, in pixels, the mouse has to come to a key to mean it. */
	private static final int NEAR = 4;

	/**
	 * The key under a point, or null.
	 *
	 * Walked in drawing order and answered with the last hit rather than the first,
	 * so the one on top is the one taken — which matters where two keys a tick
	 * apart are drawn overlapping at a low zoom.
	 */
	private Picked keyAt(Scene scene, double mouseX, double mouseY) {
		if (mouseX < NAMES || mouseY < RULER) return null;
		List<Row> rows = rows(scene);
		int index = (int) ((mouseY + scroll - RULER) / ROW);
		if (index < 0 || index >= rows.size()) return null;
		Row row = rows.get(index);
		if (row.track() == null) return null;

		Picked found = null;
		for (Key key : row.track().keys()) {
			if (Math.abs(xOf(key.at()) - mouseX) <= NEAR) {
				found = new Picked(row.role(), row.track().channel(), key.at());
			}
		}
		return found;
	}

	/** Takes the picked keys out of the scene. */
	private void removePicked() {
		Scene scene = Playing.scene();
		if (scene == null || picked.isEmpty()) return;
		Scene changed = scene;
		for (Picked one : picked) {
			changed = changed.unkeyed(one.role(), one.channel(), one.tick());
		}
		picked.clear();
		Scenes.keep(Playing.openName(), changed);
	}

	private List<Row> rows(Scene scene) {
		List<Row> rows = new ArrayList<>();
		for (Role role : scene.cast()) {
			rows.add(new Row(role.name(), null));
			for (Track track : scene.tracksOf(role.name())) rows.add(new Row(role.name(), track));
		}
		// Anything belonging to a part that is no longer in the cast. It should not
		// happen — removing a part takes its tracks — but a file somebody edited by
		// hand can say it, and rows nobody can see are rows nobody can delete.
		for (Track track : scene.tracks()) {
			if (scene.role(track.subject()) == null) rows.add(new Row(track.subject(), track));
		}
		return rows;
	}

	// ------------------------------------------------------------- the drawing

	@Override
	protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		Scene scene = Playing.scene();
		drawTransport(graphics, mouseX, mouseY, scene);
		if (scene == null) {
			graphics.text(font, Component.translatable("npc_studio.timeline.none"),
				NAMES + 6, RULER + 6, TEXT_DIM);
			if (choosing) drawChoices(graphics, mouseX, mouseY);
			return;
		}

		drawRuler(graphics, scene);
		drawRows(graphics, scene, mouseX, mouseY);
		drawCues(graphics, scene);
		drawPlayhead(graphics);
		if (choosing) drawChoices(graphics, mouseX, mouseY);
	}

	/**
	 * The buttons, in the corner the ruler does not use.
	 *
	 * Icons rather than words because there are four of them and they are pressed
	 * constantly; a row of labels would take the width the names need.
	 */
	private void drawTransport(GuiGraphicsExtractor graphics, int mouseX, int mouseY, Scene scene) {
		graphics.fill(0, 0, NAMES, RULER, PANEL);

		boolean going = Playing.playing();
		icon(graphics, Icon.REWIND, 0, mouseX, mouseY, TEXT_DIM);
		icon(graphics, going ? Icon.PAUSE : Icon.PLAY, 1, mouseX, mouseY,
			scene == null ? 0xFF55606B : going ? GOOD : ACCENT);
		icon(graphics, Icon.RESET, 2, mouseX, mouseY,
			Playing.head().loops() ? ACCENT : TEXT_DIM);
		// Thinning. Lit only when there is anything to thin, because a button that
		// is always available and usually does nothing teaches people to ignore it.
		icon(graphics, Icon.COLLAPSE, 3, mouseX, mouseY,
			scene != null && Thinning.keyCount(scene) > scene.tracks().size() * 2
				? ACCENT : 0xFF55606B);
		// Lit while something is playing, so that "why is there music" and "why is
		// there none" are both answered from the same three pixels.
		icon(graphics, Icon.MUSIC, 5, mouseX, mouseY,
			scene == null ? 0xFF55606B
				: com.mopicmp.npcstudio.client.scene.Music.nowPlaying().isEmpty()
					? TEXT_DIM : MUSIC);
		// A line on the screen. Lit while one is up, on the same reasoning: the
		// question somebody has at that moment is whether the words they can see are
		// the ones this button put there.
		icon(graphics, Icon.DIALOGUE, 6, mouseX, mouseY,
			scene == null ? 0xFF55606B
				: !scene.showingAt(Playing.head().tick()).isEmpty() ? CAPTION : TEXT_DIM);
		// Filming. Red while it runs, because this is the one control here that
		// writes to somebody's disk and it should not be possible to leave it going
		// without noticing.
		icon(graphics, Icon.IMAGE, 4, mouseX, mouseY,
			com.mopicmp.npcstudio.client.scene.Capture.running() ? 0xFFE57373
				: com.mopicmp.npcstudio.client.scene.Capture.armed() ? 0xFFFFC46B
				: scene == null ? 0xFF55606B : TEXT_DIM);

		String named = scene == null ? Component.translatable("npc_studio.timeline.pick").getString()
			: Playing.openName();
		int left = nameLeft();
		boolean over = mouseY >= 0 && mouseY < RULER && mouseX >= left && mouseX < NAMES;
		graphics.text(font, Component.literal("▾ " + trim(named, NAMES - left - 6)),
			left + 2, 3, over || choosing ? ACCENT : TEXT);
	}

	private void icon(GuiGraphicsExtractor graphics, Icon which, int slot,
			int mouseX, int mouseY, int colour) {
		int x = slot * BUTTON;
		boolean over = mouseY >= 0 && mouseY < RULER && mouseX >= x && mouseX < x + BUTTON;
		if (over) graphics.fill(x, 0, x + BUTTON, RULER, 0xFF232A34);
		// The sheet is sixteen and the strip is fourteen, so it sits a pixel proud
		// of each edge rather than being scaled to something blurry.
		which.draw(graphics, x - 1, -1, over ? TEXT : colour);
	}

	private void drawRuler(GuiGraphicsExtractor graphics, Scene scene) {
		graphics.fill(NAMES, 0, width, RULER, TRACK);
		graphics.fill(0, RULER - 1, width, RULER, TICK_LINE);

		// Past the end of the scene is shaded rather than left blank: a key out
		// there is still a key, and the shading is what says why it is not reached.
		//
		// And the edge is a handle. Until now the length of a scene was five seconds
		// because that is what an empty one is made with, and there was nothing
		// anywhere that could change it — no field, no button, nothing. Dragging the
		// edge of the shading is the shortest way from seeing the limit to moving it.
		int end = xOf(scene.length());
		if (end < width) graphics.fill(end, RULER, width, height, PAST_END);
		if (end >= NAMES && end <= width) {
			graphics.fill(end - 1, RULER, end + 1, height, draggingEnd ? PLAYHEAD : TICK_LINE);
			String howLong = String.format(java.util.Locale.ROOT, "%.1fs",
				scene.length() / 20f);
			int at = Math.min(end + 3, width - font.width(howLong) - 2);
			graphics.text(font, Component.literal(howLong), at, height - 10,
				draggingEnd ? PLAYHEAD : TEXT_DIM);
		}

		float step = pixelsPerTick();
		for (int second = 0; ; second++) {
			int x = xOf(second * 20.0);
			if (x > width) break;
			graphics.fill(x, 2, x + 1, RULER - 1, TICK_LINE);
			graphics.text(font, Component.literal(second + "s"), x + 3, 3, TEXT_DIM);
			if (step * 5 < 3) continue;
			for (int quarter = 1; quarter < 4; quarter++) {
				int minor = xOf(second * 20.0 + quarter * 5);
				if (minor > width) break;
				graphics.fill(minor, RULER - 5, minor + 1, RULER - 1, TICK_LINE);
			}
		}
	}

	private void drawRows(GuiGraphicsExtractor graphics, Scene scene, int mouseX, int mouseY) {
		List<Row> rows = rows(scene);
		if (rows.isEmpty()) {
			graphics.text(font, Component.translatable("npc_studio.timeline.nobody"),
				6, RULER + 6, TEXT_DIM);
			return;
		}
		scroll = Mth.clamp(scroll, 0, Math.max(0, rows.size() * ROW - (height - RULER)));

		for (int i = 0; i < rows.size(); i++) {
			int top = RULER + i * ROW - scroll;
			if (top + ROW < RULER || top > height) continue;
			Row row = rows.get(i);

			if (row.track() == null) {
				graphics.fill(0, top, width, top + ROW, PANEL);
				Role role = scene.role(row.role());
				// A part with nobody in it is drawn dim rather than hidden. It is an
				// ordinary state — the scene plays and that part does not move — and
				// the only way to notice it is to be able to see it.
				boolean here = role != null && role.cast();
				graphics.text(font, Component.literal(trim(row.role(), NAMES - 44)),
					4, top + 2, here ? TEXT : TEXT_DIM);
				drawRepeat(graphics, role, top);
				continue;
			}

			if (i % 2 == 1) graphics.fill(0, top, width, top + ROW, TRACK);
			graphics.text(font, Component.literal(trim(labelOf(row.track()), NAMES - 16)),
				12, top + 2, TEXT_DIM);
			drawKeys(graphics, row.role(), row.track(), top);
		}
	}

	/**
	 * How often this part starts over, and where the wrap falls.
	 *
	 * <h2>Why a part loops and not the scene</h2>
	 *
	 * Because they are different lengths on purpose, and that is the ordinary case
	 * rather than an exotic one. A sailor swaying at the wheel is five seconds of
	 * movement with no sixth second to author — the sixth second is the first one
	 * again. The shot around him runs as long as the music, a minute of it, and the
	 * words on the screen keep changing the whole way through.
	 *
	 * Looping the scene would make all three the same length, which is no answer:
	 * the music would restart every five seconds and the first subtitle would come
	 * back twelve times. So the loop belongs to the participant.
	 *
	 * <h2>Why it is set with the cursor and not typed</h2>
	 *
	 * Because the number wanted is a moment, and there is already a tool for
	 * choosing a moment. Put the playhead where the movement should start over and
	 * press this; shift takes it off again. Typing "5.0" would be asking somebody
	 * to convert what they can see into a number and back.
	 */
	private void drawRepeat(GuiGraphicsExtractor graphics, Role role, int top) {
		if (role == null) return;
		boolean on = role.repeat() > 0;
		String said = on
			? "↺ " + String.format(java.util.Locale.ROOT, "%.1fs", role.repeat() / 20f)
			: "↺";
		graphics.text(font, Component.literal(said),
			NAMES - 6 - font.width(said), top + 2, on ? MUSIC : 0xFF3A424C);

		// And where the wrap falls, on the part's own heading and nowhere else. Drawn
		// on every heading at once it would be a field of vertical lines nobody could
		// read against the keys underneath.
		if (!on) return;
		for (int lap = 1; ; lap++) {
			int x = xOf((double) role.repeat() * lap);
			if (x > width) break;
			if (x >= NAMES) graphics.fill(x, top + 1, x + 1, top + ROW - 1, MUSIC_BAR);
		}
	}

	/**
	 * Makes a part start over at the cursor, or stop starting over.
	 *
	 * The cursor is the number, which is the whole point: put the playhead where the
	 * movement should wrap and press the mark. Nought is refused rather than clamped
	 * — a loop of no length is not a loop, and taking it off is what shift is for.
	 */
	private void repeat(Scene scene, String part, boolean off) {
		Role role = scene.role(part);
		if (role == null) return;
		int at = off ? 0 : Playing.head().tick();
		if (!off && at <= 0) return;
		Scenes.keep(Playing.openName(), scene.with(role.repeating(at)));
	}

	/** Where the mark that sets a part's loop sits, in the names column. */
	private boolean overRepeat(double x, double y, int top) {
		return y >= top && y < top + ROW && x >= NAMES - 46 && x < NAMES;
	}

	/** What a channel is called in the left-hand column: the bone, then the number. */
	private String labelOf(Track track) {
		String channel = track.channel();
		if (!Channels.isBone(channel)) return channel;
		return Channels.boneOf(channel) + " " + Channels.fieldOf(channel);
	}

	/**
	 * The keys, as diamonds.
	 *
	 * A diamond rather than a square because a square at this size is a pixel
	 * block indistinguishable from the row behind it, and because every other
	 * timeline anybody has used draws a key this way — which is worth more here
	 * than any argument from first principles.
	 */
	private void drawKeys(GuiGraphicsExtractor graphics, String role, Track track, int top) {
		int middle = top + ROW / 2;
		for (Key key : track.keys()) {
			int x = xOf(key.at());
			if (x < NAMES - 4 || x > width + 4) continue;
			boolean chosen = picked.contains(new Picked(role, track.channel(), key.at()));
			int colour = chosen ? PICKED
				: key.ease() == Key.Ease.HOLD ? TEXT_DIM
				: key.ease() == Key.Ease.LINEAR ? ACCENT : GOOD;
			// A ring round the chosen ones as well as a colour. Colour alone is what
			// a timeline uses to say which easing a key has, and saying two things
			// with one channel is how a person comes to delete the wrong key.
			if (chosen) {
				for (int row = -4; row <= 4; row++) {
					int half = 4 - Math.abs(row);
					graphics.fill(x - half, middle + row, x + half + 1, middle + row + 1, PICKED_EDGE);
				}
			}
			for (int row = -3; row <= 3; row++) {
				int half = 3 - Math.abs(row);
				graphics.fill(x - half, middle + row, x + half + 1, middle + row + 1, colour);
			}
		}
	}

	/** Sounds and lines, on the ruler itself, since they belong to nobody's row. */
	private void drawCues(GuiGraphicsExtractor graphics, Scene scene) {
		for (Cue cue : scene.cues()) {
			int x = xOf(cue.at());
			int colour = switch (cue.kind()) {
				case SOUND -> 0xFFBA8CFF;
				case MUSIC -> MUSIC;
				case TEXT -> 0xFFFFC46B;
			};

			// How long a piece of music actually runs, drawn. It used to be a mark one
			// pixel wide and nothing else — no length, no end, nothing to compare with
			// the animation underneath — which is most of what "there is nothing to
			// set" meant. The length is the file's own, read out of its headers rather
			// than by playing it; see {@code Ogg}.
			//
			// Drawn before the marks so a mark sitting on top of a bar is still legible,
			// and drawn even when the start is off to the left, because a bar that
			// begins before the visible ruler is exactly the one worth seeing.
			if (cue.kind() == Cue.Kind.MUSIC && !cue.what().isEmpty()) {
				int runs = com.mopicmp.npcstudio.client.scene.Music.ticks(cue.what());
				if (runs > 0) {
					int to = xOf(Math.min(cue.at() + runs, stops(scene, cue)));
					int from = Math.max(NAMES, x);
					if (to > from) {
						graphics.fill(from, RULER - 8, Math.min(width, to), RULER - 6, MUSIC_BAR);
						// A cap where the piece runs out, which is the moment somebody is
						// looking for: it is the one place the scene goes quiet by itself.
						if (to <= width && to == xOf(cue.at() + runs)) {
							graphics.fill(to - 1, RULER - 9, to + 1, RULER - 5, MUSIC);
						}
					}
				}
			}

			if (x < NAMES || x > width) continue;
			// Music reaches further down the ruler, because unlike the other two it
			// is not a thing that happened at that moment — it is where a thing began
			// and goes on until the next one says otherwise.
			graphics.fill(x, RULER - (cue.kind() == Cue.Kind.MUSIC ? 8 : 4), x + 1, RULER, colour);
			if (cue.lasts() > 0) {
				graphics.fill(x, RULER - 2, Math.min(width, xOf(cue.ends())), RULER - 1, colour);
			}
		}
	}

	/**
	 * Where a piece of music is cut off by the next one, or the end of the scene.
	 *
	 * A piece plays until something says otherwise, and two things do: another cue,
	 * and running out of scene. Drawing the whole file regardless would show a bar
	 * running past a piece of music that will never be heard.
	 */
	private static int stops(Scene scene, Cue piece) {
		int end = scene.length();
		for (Cue other : scene.cues()) {
			if (other.kind() != Cue.Kind.MUSIC || other.at() <= piece.at()) continue;
			end = Math.min(end, other.at());
		}
		return end;
	}

	/**
	 * What the last capture wrote, along the very bottom.
	 *
	 * Said once and left there. A folder of three hundred numbered pictures is no
	 * use to somebody who does not know which folder, and the moment they would
	 * have looked is the moment it finished.
	 */
	private void drawNotice(GuiGraphicsExtractor graphics) {
		// Nothing is shown while a capture runs, because nothing is shown at all —
		// the panels stand aside so as not to be in the shot. The count arrives with
		// the rest of the interface when it stops, and it carries the one diagnostic
		// this feature cannot have any other way: a capture that says nought frames
		// is a capture whose per-frame hook never fired, which is the only part of
		// it that cannot be checked without playing the game.
		String written = com.mopicmp.npcstudio.client.scene.Capture.said();
		if (written.isEmpty()) return;
		graphics.text(font, Component.literal(written), NAMES + 4, height - 10, TEXT_DIM);
	}

	private void drawPlayhead(GuiGraphicsExtractor graphics) {
		int x = xOf(Playing.head().at());
		if (x < NAMES || x > width) return;
		graphics.fill(x, 0, x + 1, height, PLAYHEAD);
		graphics.fill(x - 3, 0, x + 4, 4, PLAYHEAD);
	}

	/**
	 * Which scene is open, and the way to start a new one.
	 *
	 * A list that hangs over the rest rather than a row of buttons: the number of
	 * scenes in a world is not known in advance, and a control that grows sideways
	 * until it runs off the panel is the thing the icon buttons were introduced to
	 * stop.
	 */
	/** Whether the list of pieces is open. */
	// ---------------------------------------------------------------- captions

	/**
	 * A line on the screen: chosen here, written elsewhere.
	 *
	 * <h2>What used to be here</h2>
	 *
	 * Two boxes wedged into this ruler strip — fourteen pixels tall, beside seven
	 * buttons, holding a line of text and a number. It was reported as inconvenient
	 * and crooked and it was both: a caption has a typeface, a size, a colour and a
	 * place on the frame, and none of them had anywhere to be said.
	 *
	 * The shape was the mistake rather than the size. A timeline holds <em>when</em>
	 * — that is the whole of what a timeline is — and everything else about a thing
	 * belongs in a panel, exactly as a bone's angles do. So the button now puts a
	 * line at the cursor, chooses it and opens the panel that edits it; the ruler
	 * keeps the mark, and clicking the mark chooses that line.
	 */
	/**
	 * The moment of the caption whose mark is under a point, or minus one.
	 *
	 * A few pixels of slack, because the mark is one pixel wide. Aiming at a
	 * one-pixel target is not a thing anybody should be asked to do, and the
	 * nearest of two marks a few ticks apart is still unambiguous.
	 */
	private int captionNear(double x) {
		Scene scene = Playing.scene();
		if (scene == null) return -1;
		int best = -1;
		double nearest = 4;
		for (Cue cue : scene.cues()) {
			if (cue.kind() != Cue.Kind.TEXT) continue;
			double away = Math.abs(xOf(cue.at()) - x);
			if (away < nearest) {
				nearest = away;
				best = cue.at();
			}
		}
		return best;
	}

	private void caption() {
		if (Playing.scene() == null) return;
		com.mopicmp.npcstudio.client.scene.Captions.addOrPick();
		com.mopicmp.npcstudio.client.workspace.WorkspaceScreen.reveal("captions");
	}

	private void drawChoices(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		List<String> names = Scenes.names();
		int rows = names.size() + 1;
		int left = nameLeft();
		int top = RULER;
		int wide = Math.max(120, NAMES - left);
		graphics.fill(left - 1, top - 1, left + wide + 1, top + rows * 13 + 1, TICK_LINE);
		graphics.fill(left, top, left + wide, top + rows * 13, PANEL);

		for (int i = 0; i < rows; i++) {
			int y = top + i * 13;
			boolean over = mouseX >= left && mouseX < left + wide && mouseY >= y && mouseY < y + 13;
			boolean on = i < names.size() && names.get(i).equals(Playing.openName());
			if (over || on) graphics.fill(left + 1, y, left + wide - 1, y + 13, on ? 0xFF27313E : 0xFF232A34);
			String said = i < names.size() ? names.get(i)
				: Component.translatable("npc_studio.timeline.new").getString();
			graphics.text(font, Component.literal(trim(said, wide - 8)), left + 5, y + 3,
				i < names.size() ? (on ? ACCENT : TEXT) : GOOD);
		}
	}

	private String trim(String said, int room) {
		return font.width(said) <= room ? said
			: font.plainSubstrByWidth(said, room - font.width("…")) + "…";
	}

	// --------------------------------------------------------------- the input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (!inside(event.x(), event.y())) return false;

		if (choosing) {
			chose(event.x(), event.y());
			choosing = false;
			return true;
		}

		if (event.y() < RULER && event.x() < NAMES) {
			int slot = (int) (event.x() / BUTTON);
			// Pressing anything else takes the arming back. An armed button that stays
			// armed across a scrub, a scene change and a cup of tea is a button that
			// goes off by surprise, which is the whole of what went wrong.
			if (slot != 4) com.mopicmp.npcstudio.client.scene.Capture.disarm();
			switch (slot) {
				case 0 -> Playing.head(Playing.head().scrubbedTo(0, Playing.length()).playing(false));
				case 1 -> {
					if (Playing.playing()) Playing.stop();
					else Playing.start();
				}
				case 2 -> Playing.head(Playing.head().looping(!Playing.head().loops()));
				case 3 -> tidy();
				case 4 -> {
					// Arm, then start. Two hundred megabytes is not a thing one press
					// on an unlabelled icon may cause, which is what it was.
					if (com.mopicmp.npcstudio.client.scene.Capture.running()) {
						com.mopicmp.npcstudio.client.scene.Capture.stop();
					} else if ((event.modifiers() & GLFW_SHIFT) != 0) {
						// Which format, cycled where the count is shown. A list would be
						// a menu over a panel for a choice of five, and the count beside
						// it is what somebody is actually reading at that moment anyway.
						var all = com.mopicmp.npcstudio.client.scene.Video.Preset.values();
						var now = com.mopicmp.npcstudio.client.scene.Capture.preset();
						com.mopicmp.npcstudio.client.scene.Capture.preset(
							all[(now.ordinal() + 1) % all.length]);
						com.mopicmp.npcstudio.client.scene.Capture.arm();
					} else if (com.mopicmp.npcstudio.client.scene.Capture.armed()) {
						com.mopicmp.npcstudio.client.scene.Capture.start();
					} else {
						com.mopicmp.npcstudio.client.scene.Capture.arm();
					}
				}
				case 5 -> {
					// The panel rather than a bare list of names. Choosing a piece was
					// never the hard part — seeing how long it runs against the animation
					// was, and a list of names cannot say that.
					if (Playing.scene() != null) {
						com.mopicmp.npcstudio.client.workspace.WorkspaceScreen.reveal("music");
					}
				}
				case 6 -> caption();
				default -> choosing = true;
			}
			return true;
		}

		// The names column: the only thing clickable there is the mark that sets how
		// often a part starts over. Everything else in it is a label.
		if (event.x() < NAMES) {
			Scene named = Playing.scene();
			if (named == null || event.y() < RULER) return false;
			List<Row> rows = rows(named);
			int index = (int) ((event.y() + scroll - RULER) / ROW);
			if (index < 0 || index >= rows.size()) return false;
			Row row = rows.get(index);
			if (row.track() != null) return false;
			int top = RULER + index * ROW - scroll;
			if (!overRepeat(event.x(), event.y(), top)) return false;
			repeat(named, row.role(), (event.modifiers() & GLFW_SHIFT) != 0);
			return true;
		}
		com.mopicmp.npcstudio.client.scene.Capture.disarm();

		// The end of the scene, which is a handle now. Before the keys and before
		// scrubbing, because it sits on top of both and a click on it means it.
		Scene here = Playing.scene();
		if (here != null && Math.abs(xOf(here.length()) - event.x()) <= GRAB_END) {
			draggingEnd = true;
			return true;
		}

		// A caption's mark on the ruler is the caption, not the moment under it. The
		// same argument as a key below: the mark is a thing drawn on the screen, and
		// the only reason to aim at one is to say "that line". Without this the panel
		// would be the only way in, and the mark would be a picture of something you
		// cannot touch.
		if (event.y() < RULER) {
			int line = captionNear(event.x());
			if (line >= 0) {
				com.mopicmp.npcstudio.client.scene.Captions.pick(line);
				com.mopicmp.npcstudio.client.workspace.WorkspaceScreen.reveal("captions");
				Playing.head(Playing.head().playing(false)
					.scrubbedTo(line, Playing.length()));
				return true;
			}
		}

		// A key under the mouse is the thing being aimed at, not the moment it sits
		// at. Without this there was no way to say "that one" at all — every click in
		// the track area scrubbed — which is why the keys could be seen and never
		// removed.
		Scene scene = Playing.scene();
		if (scene != null) {
			Picked hit = keyAt(scene, event.x(), event.y());
			if (hit != null) {
				boolean adding = (event.modifiers() & (GLFW_SHIFT | GLFW_CONTROL)) != 0;
				if (!adding) {
					boolean only = picked.size() == 1 && picked.contains(hit);
					picked.clear();
					if (only) return true;
				} else if (picked.remove(hit)) {
					return true;
				}
				picked.add(hit);
				// And the cursor goes to it, because what a key is for is the moment it
				// names — picking one and then having to find that moment by hand is
				// two actions for one intention.
				Playing.head(Playing.head().playing(false)
					.scrubbedTo(hit.tick(), Playing.length()));
				return true;
			}
			picked.clear();
		}

		// Scrubbing stops playback, because the two are fighting over one number and
		// the hand on the mouse is the one that meant it.
		scrubbing = true;
		Playing.head(Playing.head().playing(false).scrubbedTo(ticksAt(event.x()), Playing.length()));
		return true;
	}

	private static final int GLFW_SHIFT = 0x0001;
	private static final int GLFW_CONTROL = 0x0002;

	private void chose(double mouseX, double mouseY) {
		List<String> names = Scenes.names();
		int left = nameLeft();
		int wide = Math.max(120, NAMES - left);
		if (mouseX < left || mouseX > left + wide) return;
		int row = (int) ((mouseY - RULER) / 13);
		if (row < 0 || row > names.size()) return;

		if (row < names.size()) {
			Playing.open(names.get(row));
			return;
		}
		// A new scene is written down straight away rather than when something is
		// put in it. An empty scene that only exists until you look away is worse
		// than an empty scene.
		String name = fresh(names);
		Scenes.keep(name, Scene.empty(name));
		Playing.open(name);
	}

	private static String fresh(List<String> taken) {
		String wanted = "scene";
		if (!taken.contains(wanted)) return wanted;
		for (int n = 2; ; n++) {
			if (!taken.contains(wanted + n)) return wanted + n;
		}
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (draggingEnd) {
			Scene scene = Playing.scene();
			// At least a tick, because a scene of no length is one nothing can be put
			// in — the record floors it there anyway, and letting the drag go below
			// would make the handle stick at a place the number never reaches.
			if (scene != null) {
				Scenes.keep(Playing.openName(),
					scene.lengthened(Math.max(1, (int) Math.round(ticksAt(event.x())))));
			}
			return true;
		}
		if (!scrubbing) return false;
		Playing.head(Playing.head().scrubbedTo(ticksAt(event.x()), Playing.length()));
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		draggingEnd = false;
		boolean was = scrubbing;
		scrubbing = false;
		return was;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amountX, double amountY) {
		if (!inside(mouseX, mouseY)) return false;
		// Over the names it scrolls the rows; over the time it zooms. Two meanings
		// for one gesture, told apart by which half of the panel is under it.
		if (mouseX < NAMES) {
			scroll -= (int) (amountY * ROW);
			return true;
		}
		zoom = Mth.clamp(zoom * (float) Math.exp(amountY * 0.16), 0.15f, 8f);
		return true;
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		// Delete and backspace both, because which one removes a thing is a matter
		// of which keyboard somebody grew up with.
		if (event.key() == 261 || event.key() == 259) {
			if (picked.isEmpty()) return false;
			removePicked();
			return true;
		}
		// Space, because it is what every player of anything expects and because
		// both hands are usually elsewhere.
		if (event.key() != 32) return false;
		if (Playing.playing()) Playing.stop();
		else Playing.start();
		return true;
	}
}
