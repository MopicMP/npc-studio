package com.mopicmp.npcstudio.client.workspace.panel;

import java.util.ArrayList;
import java.util.List;

import com.mopicmp.npcstudio.client.scene.Music;
import com.mopicmp.npcstudio.client.scene.Ogg;
import com.mopicmp.npcstudio.client.scene.Playing;
import com.mopicmp.npcstudio.client.scene.Scenes;
import com.mopicmp.npcstudio.client.workspace.Icon;
import com.mopicmp.npcstudio.client.workspace.IconTextButton;
import com.mopicmp.npcstudio.client.workspace.WorkspacePanel;
import com.mopicmp.npcstudio.scene.Cue;
import com.mopicmp.npcstudio.scene.Scene;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The music in a scene: what plays, from when, and for how long.
 *
 * <h2>What was missing</h2>
 *
 * Everything except the fact of it. A piece of music was a name chosen from a
 * list and a mark one pixel wide on the ruler — no length, no end, no way to see
 * it against the animation, and no way to hear it from where it starts without
 * hunting for the moment by hand. It was reported as there being nothing to set,
 * and there was nothing to set.
 *
 * <h2>Where the length comes from</h2>
 *
 * Out of the file's own headers, not out of playing it. An Ogg page carries how
 * many samples have been decoded by the end of it, so the last page of a file
 * carries the total — two small reads instead of four minutes of decoding. See
 * {@code Ogg}. That is what makes it affordable to show a length beside every
 * name in the list and to draw the piece as a bar on the ruler.
 *
 * <h2>Why there is no separate preview player</h2>
 *
 * Because the scene already is one. Playing the scene plays whichever piece the
 * cursor has passed, from as far into it as the cursor has gone — so hearing a
 * piece against the animation is pressing play, and a second player would be a
 * second thing that could be making a noise. What was missing was getting the
 * cursor onto the piece without aiming at a one-pixel mark, and that is the
 * button: it puts the cursor on the cue and starts the scene there.
 *
 * Nothing plays while the scene is paused. That was the other half of what was
 * reported — the music started the moment a scene was opened and ran on for ever
 * while the timeline stood still, which is not music that will not stop, it is
 * music measuring a different clock. See {@code Playing.follow}.
 */
public class MusicPanel extends WorkspacePanel {

	private static final int PAD = 8;
	private static final int ROW = 16;
	private static final int LABEL = 11;
	private static final int BUTTON = 18;
	private static final int LIST_ROW = 12;

	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;
	private static final int WARN = 0xFFE57373;
	private static final int MUSIC = 0xFF4DB6AC;
	private static final int OFF = 0xFF55606B;
	private static final int PANEL = 0xFF161A20;
	private static final int EDGE = 0xFF2C333D;

	private static final int LIST_SHOWS = 4;

	/** Which cue is being worked on, named by the moment it starts. */
	private int picked = -1;
	private boolean choosingPiece;
	private int builtFor = -2;

	@Override
	public String id() {
		return "music";
	}

	@Override
	public int minimumWidth() {
		return 200;
	}

	@Override
	public int minimumHeight() {
		return PAD + LIST_SHOWS * LIST_ROW + 6 + BUTTON + 6
			+ LABEL + ROW + 4 + LABEL + ROW + 4 + LABEL + ROW * 2 + PAD;
	}

	// -------------------------------------------------------------------- state

	/** The music cues of the open scene, earliest first. */
	private List<Cue> cues() {
		Scene scene = Playing.scene();
		List<Cue> found = new ArrayList<>();
		if (scene == null) return found;
		for (Cue cue : scene.cues()) {
			if (cue.kind() == Cue.Kind.MUSIC) found.add(cue);
		}
		found.sort((a, b) -> Integer.compare(a.at(), b.at()));
		return found;
	}

	private Cue chosen() {
		for (Cue cue : cues()) {
			if (cue.at() == picked) return cue;
		}
		return null;
	}

	@Override
	public void tick() {
		if (builtFor != picked) rebuild();
	}

	private void change(Cue was, Cue now) {
		Scene scene = Playing.scene();
		if (scene == null || was == null) return;
		picked = now.at();
		builtFor = picked;
		Scenes.keep(Playing.openName(), scene.without(was).with(now));
	}

	/**
	 * Puts a cue at the cursor, or chooses the one already there.
	 *
	 * One piece at a moment, as the timeline's own picker has always enforced: two
	 * would be two answers to "what is playing here" and there is one mark to say
	 * it with.
	 */
	private void addOrPick() {
		Scene scene = Playing.scene();
		if (scene == null) return;
		int at = Playing.head().tick();
		picked = at;
		for (Cue cue : cues()) {
			if (cue.at() == at) return;
		}
		Scenes.keep(Playing.openName(), scene.with(Cue.music(at, "")));
	}

	private void remove() {
		Scene scene = Playing.scene();
		Cue was = chosen();
		if (scene == null || was == null) return;
		picked = -1;
		Scenes.keep(Playing.openName(), scene.without(was));
	}

	/**
	 * Makes the scene last exactly as long as its music.
	 *
	 * <h2>Why this is a button and not something to work out by hand</h2>
	 *
	 * Because it is the ordinary shape of the thing being made. The animation is a
	 * loop of a few seconds, the film is as long as the piece, and the words carry
	 * on changing over the whole of it — so the scene's length is not a number
	 * anybody chooses, it is a number the music already knows. Working it out meant
	 * reading a length off this panel, dividing by nothing in particular and
	 * dragging an edge until it matched.
	 *
	 * The end of the last piece rather than of the chosen one, because a scene with
	 * two pieces in it ends when the second one does.
	 */
	private void fit() {
		Scene scene = Playing.scene();
		if (scene == null) return;
		int end = 0;
		for (Cue cue : cues()) {
			if (cue.what().isEmpty()) continue;
			int runs = Music.ticks(cue.what());
			if (runs > 0) end = Math.max(end, cue.at() + runs);
		}
		// Nothing measurable to fit to. Silently leaving the length alone is right:
		// guessing at one would be the panel deciding how long the film is.
		if (end <= 0) return;
		Scenes.keep(Playing.openName(), scene.lengthened(end));
	}

	/** Takes the cursor to the piece and starts the scene from there. */
	private void hear() {
		Cue cue = chosen();
		if (cue == null) return;
		Playing.head(Playing.head().scrubbedTo(cue.at(), Playing.length()));
		if (!Playing.playing()) Playing.start();
	}

	// ------------------------------------------------------------------ widgets

	private int whenTop;
	private int pieceTop;
	private int factsTop;

	@Override
	protected void build() {
		builtFor = picked;
		if (Playing.scene() == null) return;
		int across = width - PAD * 2;
		if (across < 80) return;

		int y = PAD + Math.min(LIST_SHOWS, Math.max(1, cues().size())) * LIST_ROW + 6;
		y += IconTextButton.row(PAD, y, across, BUTTON, List.of(
			new IconTextButton.Spec(Icon.ADD,
				Component.translatable("npc_studio.music.add"), ACCENT, this::addOrPick),
			new IconTextButton.Spec(Icon.PLAY,
				Component.translatable("npc_studio.music.hear"), MUSIC, this::hear),
			new IconTextButton.Spec(Icon.REMOVE,
				Component.translatable("npc_studio.music.remove"), WARN, this::remove),
			new IconTextButton.Spec(Icon.SCALE,
				Component.translatable("npc_studio.music.fit"), ACCENT, this::fit),
			// The world's own noise. Here rather than in the settings because it is a
			// thing about listening to a scene, and this is the panel about that.
			new IconTextButton.Spec(
				com.mopicmp.npcstudio.client.scene.Hush.wanted()
					? Icon.PLAYER_OFF : Icon.PLAYER_ON,
				Component.translatable("npc_studio.music.hush"),
				com.mopicmp.npcstudio.client.scene.Hush.wanted() ? MUSIC : OFF,
				() -> {
					com.mopicmp.npcstudio.client.scene.Hush.wanted(
						!com.mopicmp.npcstudio.client.scene.Hush.wanted());
					rebuild();
				})),
			this::add);
		y += 6;

		Cue cue = chosen();
		if (cue == null) {
			lengthOnly(y, across);
			return;
		}

		whenTop = y;
		y += LABEL;
		EditBox when = new EditBox(font, PAD, y, across / 2, ROW - 4, Component.literal(""));
		when.setValue(String.format(java.util.Locale.ROOT, "%.2f", cue.at() / 20f));
		when.setResponder(said -> {
			try {
				int at = Math.max(0, Math.round(Float.parseFloat(said.trim().replace(',', '.')) * 20));
				Cue now = chosen();
				if (now != null && at != now.at() && !taken(at)) change(now, now.moved(at));
			} catch (NumberFormatException halfTyped) {
				// Left alone on purpose, as everywhere else a number is typed here.
			}
		});
		add(when);
		y += ROW + 2;

		pieceTop = y;
		y += LABEL + LIST_ROW + 4;
		factsTop = y;
		y += LABEL * 3 + 14;

		// How long the whole thing runs, typed.
		//
		// The edge of the scene can be dragged on the timeline and that is not
		// enough: the handle only exists while the end is on screen, and at the
		// ordinary two pixels a tick a minute of scene is two thousand pixels away.
		// Everything past the width of the panel was unreachable, which is what "I
		// cannot set it past five seconds" actually was.
		lengthTop = y;
		y += LABEL;
		EditBox howLong = new EditBox(font, PAD, y, across / 2, ROW - 4, Component.literal(""));
		howLong.setValue(String.format(java.util.Locale.ROOT, "%.2f", Playing.length() / 20f));
		howLong.setResponder(said -> {
			try {
				int ticks = Math.round(Float.parseFloat(said.trim().replace(',', '.')) * 20);
				Scene now = Playing.scene();
				if (now != null && ticks >= 1) Scenes.keep(Playing.openName(), now.lengthened(ticks));
			} catch (NumberFormatException halfTyped) {
				// Left alone on purpose, as everywhere else a number is typed here.
			}
		});
		add(howLong);
	}

	private int lengthTop;

	/**
	 * The scene's length on its own, when no cue is chosen.
	 *
	 * How long the film runs is a property of the scene rather than of any one
	 * piece of music, so it must be reachable in a panel with an empty list — which
	 * is exactly the state somebody is in when they have just made a scene and want
	 * it to be longer than five seconds.
	 */
	private void lengthOnly(int y, int across) {
		lengthTop = y;
		EditBox howLong = new EditBox(font, PAD, y + LABEL, across / 2, ROW - 4,
			Component.literal(""));
		howLong.setValue(String.format(java.util.Locale.ROOT, "%.2f", Playing.length() / 20f));
		howLong.setResponder(said -> {
			try {
				int ticks = Math.round(Float.parseFloat(said.trim().replace(',', '.')) * 20);
				Scene now = Playing.scene();
				if (now != null && ticks >= 1) Scenes.keep(Playing.openName(), now.lengthened(ticks));
			} catch (NumberFormatException halfTyped) {
				// Left alone on purpose.
			}
		});
		add(howLong);
	}

	private boolean taken(int at) {
		for (Cue cue : cues()) {
			if (cue.at() == at) return true;
		}
		return false;
	}

	// ------------------------------------------------------------------ drawing

	@Override
	protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (Playing.scene() == null) {
			graphics.text(font, Component.translatable("npc_studio.pose.no_scene"),
				PAD, PAD, TEXT_DIM);
			return;
		}
		drawList(graphics, mouseX, mouseY);

		Cue cue = chosen();
		if (cue == null) {
			graphics.text(font, Component.translatable("npc_studio.music.pick"),
				PAD, lengthTop - LABEL - 2, TEXT_DIM);
			graphics.text(font, Component.translatable("npc_studio.music.sceneLength"),
				PAD, lengthTop + 1, TEXT_DIM);
			return;
		}

		graphics.text(font, Component.translatable("npc_studio.music.when"),
			PAD, whenTop + 1, TEXT_DIM);
		graphics.text(font, Component.translatable("npc_studio.music.piece"),
			PAD, pieceTop + 1, TEXT_DIM);
		graphics.text(font, Component.translatable("npc_studio.music.sceneLength"),
			PAD, lengthTop + 1, TEXT_DIM);

		String named = cue.what().isEmpty()
			? Component.translatable("npc_studio.timeline.silence").getString() : cue.what();
		boolean over = mouseY >= pieceTop + LABEL && mouseY < pieceTop + LABEL + LIST_ROW;
		graphics.text(font, Component.literal("▾ " + trim(named, width - PAD * 2 - 12)),
			PAD, pieceTop + LABEL + 2, over || choosingPiece ? ACCENT : TEXT);

		drawFacts(graphics, cue);
	}

	/**
	 * What this piece actually does to the scene, in three lines.
	 *
	 * The length of the file, where it would end, and whether the scene is long
	 * enough to hold it. That last one is the whole reason to show any of this: a
	 * fifty-six second piece under a five second scene is not a mistake anybody
	 * makes on purpose, and until now there was nothing anywhere that would have
	 * said so.
	 */
	private void drawFacts(GuiGraphicsExtractor graphics, Cue cue) {
		int y = factsTop;
		if (cue.what().isEmpty()) {
			graphics.text(font, Component.translatable("npc_studio.music.silence_here"),
				PAD, y, TEXT_DIM);
			return;
		}
		double seconds = Music.seconds(cue.what());
		int ends = cue.at() + Music.ticks(cue.what());
		int length = Playing.length();

		line(graphics, y, "npc_studio.music.length", Ogg.said(seconds), TEXT);
		y += LABEL;
		line(graphics, y, "npc_studio.music.ends",
			seconds > 0 ? Ogg.said(ends / 20.0) : "?",
			seconds > 0 && ends > length ? WARN : TEXT);
		y += LABEL;
		line(graphics, y, "npc_studio.music.scene", Ogg.said(length / 20.0), TEXT_DIM);
		y += LABEL;

		if (seconds > 0 && ends > length) {
			graphics.text(font, Component.translatable("npc_studio.music.overruns"),
				PAD, y + 2, WARN);
		} else if (seconds > 0) {
			graphics.text(font, Component.translatable("npc_studio.music.room",
				Ogg.said((length - ends) / 20.0)), PAD, y + 2, TEXT_DIM);
		}
	}

	private void line(GuiGraphicsExtractor graphics, int y, String key, String said, int ink) {
		graphics.text(font, Component.translatable(key), PAD, y, TEXT_DIM);
		graphics.text(font, Component.literal(said), width - PAD - font.width(said), y, ink);
	}

	private void drawList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		List<Cue> cues = cues();
		if (cues.isEmpty()) {
			graphics.text(font, Component.translatable("npc_studio.music.empty"),
				PAD, PAD + 2, TEXT_DIM);
			return;
		}
		int rows = Math.min(LIST_SHOWS, cues.size());
		graphics.fill(PAD - 1, PAD - 1, width - PAD + 1, PAD + rows * LIST_ROW + 1, EDGE);
		graphics.fill(PAD, PAD, width - PAD, PAD + rows * LIST_ROW, PANEL);

		for (int i = 0; i < rows; i++) {
			Cue cue = cues.get(i + scroll());
			int y = PAD + i * LIST_ROW;
			boolean on = cue.at() == picked;
			boolean over = mouseX >= PAD && mouseX < width - PAD && mouseY >= y
				&& mouseY < y + LIST_ROW;
			if (on || over) {
				graphics.fill(PAD + 1, y, width - PAD - 1, y + LIST_ROW,
					on ? 0xFF27313E : 0xFF232A34);
			}
			String when = Ogg.said(cue.at() / 20.0);
			graphics.text(font, Component.literal(when), PAD + 4, y + 2, on ? ACCENT : TEXT_DIM);
			String named = cue.what().isEmpty()
				? Component.translatable("npc_studio.timeline.silence").getString() : cue.what();
			graphics.text(font, Component.literal(trim(named, width - PAD * 2 - 44)),
				PAD + 40, y + 2, on ? TEXT : TEXT_DIM);
		}
	}

	private int scroll() {
		List<Cue> cues = cues();
		int at = 0;
		for (int i = 0; i < cues.size(); i++) {
			if (cues.get(i).at() == picked) at = i;
		}
		return Math.clamp(at - LIST_SHOWS + 1, 0, Math.max(0, cues.size() - LIST_SHOWS));
	}

	private String trim(String said, int room) {
		if (font.width(said) <= room) return said;
		return font.plainSubstrByWidth(said, Math.max(0, room - font.width("…"))) + "…";
	}

	@Override
	protected void over(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (choosingPiece) drawPieces(graphics, mouseX, mouseY);
	}

	/** Every file in the folder, with how long it runs. Silence first. */
	private List<String> pieces() {
		List<String> found = new ArrayList<>();
		found.add("");
		found.addAll(Music.names());
		return found;
	}

	private void drawPieces(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		List<String> named = pieces();
		int wide = width - PAD * 2;
		int top = pieceTop + LABEL + LIST_ROW;
		graphics.fill(PAD - 1, top - 1, PAD + wide + 1, top + named.size() * LIST_ROW + 1, EDGE);
		graphics.fill(PAD, top, PAD + wide, top + named.size() * LIST_ROW, PANEL);
		for (int i = 0; i < named.size(); i++) {
			int y = top + i * LIST_ROW;
			boolean over = mouseX >= PAD && mouseX < PAD + wide && mouseY >= y
				&& mouseY < y + LIST_ROW;
			if (over) graphics.fill(PAD + 1, y, PAD + wide - 1, y + LIST_ROW, 0xFF232A34);
			String said = named.get(i).isEmpty()
				? Component.translatable("npc_studio.timeline.silence").getString() : named.get(i);
			// The length beside the name, because choosing a piece for a shot is
			// choosing a length as much as a tune.
			String howLong = named.get(i).isEmpty() ? "" : Ogg.said(Music.seconds(named.get(i)));
			graphics.text(font, Component.literal(
				trim(said, wide - 12 - font.width(howLong))), PAD + 5, y + 2,
				named.get(i).isEmpty() ? TEXT_DIM : TEXT);
			graphics.text(font, Component.literal(howLong),
				PAD + wide - 5 - font.width(howLong), y + 2, MUSIC);
		}
	}

	// -------------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (!inside(event.x(), event.y())) return false;

		if (choosingPiece) {
			List<String> named = pieces();
			int top = pieceTop + LABEL + LIST_ROW;
			int row = (int) ((event.y() - top) / LIST_ROW);
			if (event.x() >= PAD && event.x() < width - PAD && row >= 0 && row < named.size()) {
				Cue now = chosen();
				if (now != null) change(now, Cue.music(now.at(), named.get(row)));
			}
			choosingPiece = false;
			return true;
		}

		List<Cue> cues = cues();
		int rows = Math.min(LIST_SHOWS, cues.size());
		if (event.y() >= PAD && event.y() < PAD + rows * LIST_ROW && event.x() >= PAD) {
			int row = (int) ((event.y() - PAD) / LIST_ROW) + scroll();
			if (row >= 0 && row < cues.size()) {
				picked = cues.get(row).at();
				Playing.head(Playing.head().scrubbedTo(picked, Playing.length()));
				rebuild();
			}
			return true;
		}

		if (chosen() != null && event.y() >= pieceTop + LABEL
			&& event.y() < pieceTop + LABEL + LIST_ROW) {
			// Asked again, in case a file was dropped into the folder while the game
			// was running — which is the whole workflow this folder exists for.
			Music.forgetLengths();
			choosingPiece = true;
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}
}
