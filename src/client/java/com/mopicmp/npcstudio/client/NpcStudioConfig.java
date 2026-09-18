package com.mopicmp.npcstudio.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mopicmp.npcstudio.NpcStudio;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Client settings, in a file.
 *
 * Only what somebody has actually asked to change lives here. A settings screen
 * for two values would be more interface than the values are worth, and the
 * ones that belong to a conversation's look are heading for the theme system
 * anyway — this is the holding place for the ones that are personal rather than
 * authored.
 *
 * A missing or unreadable file is not an error. It means the defaults, which
 * are the values almost everyone will keep.
 */
public final class NpcStudioConfig {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE =
		FabricLoader.getInstance().getConfigDir().resolve("npc_studio.json");

	/**
	 * How much the world is dimmed behind a full-screen conversation, 0 to 100.
	 *
	 * Zero leaves it untouched. The default is a wash rather than a curtain: it
	 * lifts the text off whatever is behind without hiding where the conversation
	 * is happening, and someone who has built a scene worth looking at can turn
	 * it off entirely.
	 */
	public int dialogueDim = 40;

	/** Characters per second while a line types itself out. */
	public int typingSpeed = 45;

	/**
	 * How the animation picker was left last time.
	 *
	 * Kept because these are answers to "how do I like to look at this", and
	 * asking the same question every time the screen opens is the definition of
	 * an interface that does not remember you. Stored as steps rather than as
	 * sizes and rates, so that changing what the steps are does not leave someone
	 * with a setting that no longer exists.
	 */
	public int pickerIconSize = 1;

	public int pickerSpeed = 2;

	/** Percent of the width given to the grid, the rest going to the preview. */
	public int pickerSplit = 66;

	/** Whether the animation preview shows what the character is holding. */
	public boolean showHeldItems = true;

	/**
	 * Whether a limb folds smoothly, or simply does not fold.
	 *
	 * Off is what Emotecraft shows, and Emotecraft is the reference implementation
	 * of this format: it reads the bend channel, interpolates it, sends it over
	 * the network, and drops it before drawing. Not one class in either of its
	 * jars so much as mentions a cube, a polygon or a vertex. A bent limb there is
	 * a rigid box that has been rotated, and nobody complains. That is the fallback
	 * this switch turns on, and it is a real one — not a broken state.
	 *
	 * On is a mitre joint — see {@link com.mopicmp.npcstudio.client.emote.Folding},
	 * which carries the whole argument. Eight attempts, and the seven that failed
	 * all failed the same way: they treated the hatching as a problem with the
	 * shape of the fold and tried a different shape. It was z-fighting, so the only
	 * thing that mattered was that the two halves of a limb must not occupy one
	 * place, and a mitre guarantees that by construction rather than by tuning.
	 *
	 * {@code FoldingTest} states it as the proof it is: everything one half keeps
	 * lands on one side of the cut and everything the other keeps on the other,
	 * with no gap where they meet and no thickness lost by either. It runs on every
	 * build.
	 *
	 * Two of its earlier measurements once computed a formula the renderer had
	 * stopped using, and so passed while the renderer misbehaved. Every one of them
	 * now calls the real code rather than recomputing it.
	 */
	public boolean smoothBend = true;

	/**
	 * Whether the second skin layer folds with the limb it covers.
	 *
	 * Off leaves every sleeve, jacket, hat and trouser leg to vanilla: they stay
	 * straight while the limb inside them bends, which looks wrong on purpose.
	 *
	 * This is a bisector, not a setting. A stubborn artefact on a bent limb is
	 * either on the limb or on the layer over it, and no amount of reading the code
	 * has separated the two — where measurement can reach, it has already cleared
	 * the fold and the slicing.
	 */
	public boolean foldOuterLayer = true;

	/**
	 * Whether faces we draw ourselves are pulled a hair inside their own surface.
	 *
	 * The other half of the same bisector. See {@code ModelPartMixin.TUCK}: it is
	 * a sixteenth of a pixel, meant to settle depth ties against the body a limb
	 * presses on. Off puts every face exactly where the model says it is.
	 */
	public boolean tuckFaces = true;

	/**
	 * Whether the eyes glance about, and whether a closing lid draws its lash.
	 *
	 * Two switches rather than one, and they are here for the same reason
	 * {@code tuckFaces} is: "the face looks wrong" is one sentence covering two
	 * quite different drawings, and turning one off at a time is the only thing
	 * that has ever separated them quickly. Off leaves the face exactly as the
	 * skin drew it.
	 */
	public boolean eyeGlance = true;
	public boolean eyeLash = true;

	/**
	 * Whether pupils widen in the dark and narrow in the light.
	 *
	 * On, because it is true of the world the character is standing in rather than
	 * something we invented, and because it costs nothing on any skin. Off draws
	 * every pupil the size the artist painted it.
	 */
	public boolean eyePupils = true;

	/**
	 * Whether the lids answer to the light and the hour.
	 *
	 * A character squints in noon sun and gets heavy-lidded in the small hours.
	 * Both are slight — a fifth of the way down at most — because there is very
	 * little distance on an eight-pixel face between a character squinting and a
	 * character who looks unwell. Off keeps the lids fully open except to blink.
	 */
	public boolean eyeDroop = true;

	/**
	 * Whether the brows move with an expression.
	 *
	 * The boldest thing on the face by a wide margin: everything else moves a
	 * fraction of a pixel and a brow moves a whole one. That is why it is worth
	 * having and why it has its own switch — it is also the only part of this that
	 * depends on the reading having found the brow's columns and not merely its
	 * rows, so it is the first thing to turn off on a skin where something looks
	 * wrong above the eyes.
	 */
	public boolean eyeBrows = true;

	/**
	 * How far an eye travels when it looks aside, in skin pixels.
	 *
	 * A whole pixel is as far as it can go, and on a real face that is the entire
	 * eye: measured over the thirty-five readable faces marked by hand, every
	 * single one draws its eye two pixels wide — a white and an iris. So a full
	 * pixel of travel does not move the eye so much as turn it into its own mirror
	 * image, with whatever the artist put on the outside now on the inside.
	 *
	 * Which is right depends on the drawing, and that is why this is a number
	 * rather than a decision. At one the eye reaches the far side of its socket, as
	 * an animator would draw it. Below one it leans without ever swapping over, and
	 * a highlight stays on the side of the eye it was painted on.
	 */
	public float eyeTravel = 1f;

	/**
	 * Whether a limb thrown a long way from the body is hidden instead of drawn.
	 *
	 * On by default, because the emotes that do this are doing it deliberately —
	 * a head walking on its own has no use for the body it left behind — and a
	 * limb stranded in mid-air is the worse of the two readings.
	 */
	public boolean hideDistantParts = true;

	/** How far a limb may stray, in model pixels, before it counts as gone. */
	public int distantPart = 48;

	/**
	 * Whether an emote may reposition the item in a character's hand.
	 *
	 * Off, because the frame these offsets are measured against has not been
	 * established — see {@code HeldItemMixin}. Turn it on to compare the two.
	 */
	public boolean useItemChannels;

	/**
	 * How the workspace panels were left.
	 *
	 * Kept because arranging them is work, and work that is thrown away every
	 * time the game closes is work nobody does twice. Null means nobody has moved
	 * anything yet and the shipped arrangement stands.
	 */
	public com.mopicmp.npcstudio.client.workspace.DockLayout.SavedDock workspace;

	/**
	 * The arrangement from before the columns went, kept and not read.
	 *
	 * Moved here once, the first time an old one is found. It describes a workspace
	 * that no longer exists — twelve panels sharing a column that could not fit its
	 * own contents — so restoring it would put back the thing this work removed.
	 * Deleting it outright is a different matter: somebody spent an afternoon
	 * dragging those splitters, and quietly binning that is not ours to do.
	 */
	public com.mopicmp.npcstudio.client.workspace.DockLayout.SavedDock workspaceBefore;

	/**
	 * How wide each panel was last pulled to, by id.
	 *
	 * Separate from the layout above because a panel that has been dismissed is not
	 * in the layout at all, and dismissing one is now the ordinary thing to do with
	 * it. See {@code PanelWidths} for why that made the width look as though it were
	 * never saved.
	 */
	public java.util.Map<String, Integer> panelWidth = new java.util.LinkedHashMap<>();

	private static NpcStudioConfig loaded;

	public static NpcStudioConfig get() {
		if (loaded == null) load();
		return loaded;
	}

	private static void load() {
		loaded = new NpcStudioConfig();
		try {
			if (Files.exists(FILE)) {
				NpcStudioConfig read = GSON.fromJson(Files.readString(FILE), NpcStudioConfig.class);
				if (read != null) loaded = read;
			} else {
				writeOut();
			}
		} catch (Exception broken) {
			// Keep the defaults and say why once, rather than refusing to start
			// over a settings file.
			NpcStudio.LOGGER.warn("Could not read {}: {}", FILE, broken.getMessage());
		}
		loaded.dialogueDim = Math.clamp(loaded.dialogueDim, 0, 100);
		loaded.typingSpeed = Math.clamp(loaded.typingSpeed, 1, 400);
		// Clamped rather than trusted: a hand-edited file should not be able to
		// index past the end of an array in the middle of drawing a screen.
		loaded.pickerIconSize = Math.clamp(loaded.pickerIconSize, 0, 3);
		loaded.pickerSpeed = Math.clamp(loaded.pickerSpeed, 0, 4);
		loaded.pickerSplit = Math.clamp(loaded.pickerSplit, 40, 85);
		loaded.distantPart = Math.clamp(loaded.distantPart, 8, 512);
		// A pixel is the whole eye, so more than one is not a bolder glance — it is
		// an eye drawn on a cheek.
		loaded.eyeTravel = Math.clamp(loaded.eyeTravel, 0f, 1f);
	}

	public void save() {
		writeOut();
	}

	private static void writeOut() {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(loaded));
		} catch (IOException failed) {
			NpcStudio.LOGGER.warn("Could not write {}: {}", FILE, failed.getMessage());
		}
	}

	/** The dim as a colour to fill with, or zero when it is switched off. */
	public int dimColour() {
		if (dialogueDim <= 0) return 0;
		int alpha = (int) (dialogueDim / 100f * 255);
		return alpha << 24;
	}
}
