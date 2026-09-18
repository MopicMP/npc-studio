package com.mopicmp.npcstudio.puppet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A figure assembled out of placed pictures: the layout sheet, written down once.
 *
 * <h2>Why a portrait stopped being a picture</h2>
 *
 * Because the sets people actually have are not portraits. Measured on a real one:
 * 4594 files, of which 4208 are <em>parts</em> — a body, a head, eleven pairs of eyes,
 * dozens of outfits — each trimmed to its own edges. Every combination as a finished
 * picture would be six outfits times eight faces times however many poses, and each of
 * those a full-sized drawing in the world's save.
 *
 * As parts, one expression costs one small picture. The median part in that set is eight
 * kilobytes. Changing a face mid-scene is eight kilobytes on the wire; as whole pictures
 * it would be the figure again.
 *
 * <h2>Why the placement lives here and not on the node that shows it</h2>
 *
 * Because where the eyes go is a fact about the figure, said once. A node saying "put
 * this layer at 412, 88" would repeat those numbers in every scene the character appears
 * in, and the fiftieth would disagree with the first. It is the same rule that put a
 * speaker's colour on the document rather than on each line, and the reason is the same:
 * a fact about somebody is written where they are described.
 *
 * So a scene says <em>Lona, cloak, sad</em>. Three names. Nothing in a graph ever holds
 * a coordinate.
 *
 * <h2>What a slot is</h2>
 *
 * A place on the figure and the pictures that may fill it. The eyes are a slot; the
 * eleven pairs are its parts. A slot may also be filled by nothing, which is how a hat
 * that is sometimes not worn works — so the parts are what <em>may</em> go there, never
 * what must.
 *
 * Slots are held in drawing order, back to front. That is the one thing beyond position
 * that cannot be worked out: hair goes behind a face and in front of a shoulder, and no
 * arithmetic on the pictures will ever say which.
 */
public record Puppet(String name, int wide, int high, List<Slot> slots) {

	/** As many slots as one figure may have. Far past anything anybody assembles by hand. */
	public static final int MOST_SLOTS = 128;

	/** As many pictures as may fill one slot. */
	public static final int MOST_PARTS = 256;

	/**
	 * One place on the figure, and what may go in it.
	 *
	 * @param x    where its pictures' left edge sits on the figure's own canvas
	 * @param y    and their top edge
	 */
	public record Slot(String name, int x, int y, List<Part> parts) {

		public Slot {
			name = name == null ? "" : name;
			parts = List.copyOf(parts == null ? List.of() : parts);
		}

		public Slot moved(int toX, int toY) {
			return new Slot(name, toX, toY, parts);
		}

		/** The part called this, or null. Named rather than numbered; see {@link Puppet#worn}. */
		public Part part(String label) {
			for (Part each : parts) {
				if (each.label().equals(label)) return each;
			}
			return null;
		}
	}

	/**
	 * One picture that may fill a slot, and how far it is nudged from the slot's own corner.
	 *
	 * <h2>Why a part has an offset at all when the slot already has one</h2>
	 *
	 * Because the variants of one slot are not the same size. They were trimmed to their
	 * own edges, so two pairs of eyes drawn in the same place come out as pictures a few
	 * pixels different — measured on a real set, 30 slots in 88 have variants whose
	 * canvases disagree. The slot says where the eyes go; this says how much this
	 * particular pair has to shift to land there.
	 *
	 * It is nearly always zero and is worked out rather than typed — see {@link Fitting}.
	 * Kept per part rather than folded into the slot because folding it in would mean the
	 * slot's position changed every time somebody looked at a different variant, and then
	 * nobody could say where the eyes actually are.
	 */
	public record Part(String label, String picture, int nudgeX, int nudgeY) {

		public Part(String label, String picture) {
			this(label, picture, 0, 0);
		}

		public Part {
			label = label == null ? "" : label;
			picture = picture == null ? "" : picture;
		}

		public Part nudged(int byX, int byY) {
			return new Part(label, picture, byX, byY);
		}
	}

	public Puppet {
		name = name == null ? "" : name;
		slots = List.copyOf(slots == null ? List.of() : slots);
		// A canvas of nothing would divide by zero everywhere it is used to work out how
		// tall the figure is drawn. One pixel is a figure nobody can see, which is honest
		// about a sheet nobody has finished.
		wide = Math.max(1, wide);
		high = Math.max(1, high);
	}

	public Slot slot(String named) {
		for (Slot each : slots) {
			if (each.name().equals(named)) return each;
		}
		return null;
	}

	public Puppet with(Slot changed) {
		List<Slot> now = new ArrayList<>(slots);
		for (int i = 0; i < now.size(); i++) {
			if (now.get(i).name().equals(changed.name())) {
				now.set(i, changed);
				return new Puppet(name, wide, high, now);
			}
		}
		now.add(changed);
		return new Puppet(name, wide, high, now);
	}

	/**
	 * What to draw, for a figure wearing this choice: pictures with their corners, back
	 * to front.
	 *
	 * <h2>Why a slot nobody named shows nothing</h2>
	 *
	 * Because the alternative is a default, and a default here would mean a figure
	 * quietly growing a hat when somebody adds a hat slot to the sheet. Scenes written
	 * last week must not change because the sheet gained a part this week — so absent
	 * means absent, and the picker fills in what it wants when the choice is first made.
	 *
	 * A name that no longer exists is skipped in the same silence. A part deleted from a
	 * sheet leaves scenes that named it, and the choice between drawing nothing there and
	 * refusing to draw the figure at all is not close.
	 */
	public List<Placed> worn(Map<String, String> choice) {
		List<Placed> shown = new ArrayList<>();
		if (choice == null) return shown;
		for (Slot each : slots) {
			String wanted = choice.get(each.name());
			if (wanted == null || wanted.isEmpty()) continue;
			Part part = each.part(wanted);
			if (part == null || part.picture().isEmpty()) continue;
			shown.add(new Placed(part.picture(),
				each.x() + part.nudgeX(), each.y() + part.nudgeY()));
		}
		return shown;
	}

	/** One picture and where its corner goes on the figure's canvas. */
	public record Placed(String picture, int x, int y) { }

	/**
	 * Every slot filled by its first part: what a fresh choice starts as.
	 *
	 * The first rather than none, because a figure that begins as an empty rectangle
	 * teaches nobody anything about what they are choosing. Whoever is picking then takes
	 * off what should not be there, which is the shorter journey — a sheet is mostly body
	 * and clothes, and mostly they are all wanted.
	 */
	public Map<String, String> everything() {
		Map<String, String> choice = new LinkedHashMap<>();
		for (Slot each : slots) {
			if (!each.parts().isEmpty()) choice.put(each.name(), each.parts().get(0).label());
		}
		return Collections.unmodifiableMap(choice);
	}
}
