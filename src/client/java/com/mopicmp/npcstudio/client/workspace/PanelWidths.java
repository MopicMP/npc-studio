package com.mopicmp.npcstudio.client.workspace;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How wide each panel was last left, kept past the panel itself.
 *
 * <h2>Why this is not in the saved layout</h2>
 *
 * Because the saved layout only knows about panels that are open, and the whole
 * arrangement now is that a panel is summoned and dismissed. Pull the costume
 * panel out to a width that suits you, press its button to put it away, press it
 * again — under the layout alone that is a brand new panel with no history, and
 * it came back at whatever width the code chose. That was reported as the width
 * not being saved, and it was exactly right: it was saved while the panel was
 * open, which is the one time it does not need to be.
 *
 * So the width is remembered against the id rather than against the panel, and
 * outlives every one of them.
 *
 * <h2>Why the number is not clamped here</h2>
 *
 * Because what it has to fit inside is a window, and a window is a different size
 * on the next machine and after the next drag of a corner. Kept as asked for,
 * fitted where it is used.
 */
public final class PanelWidths {

	private PanelWidths() { }

	private static final Map<String, Integer> KEPT = new LinkedHashMap<>();

	/**
	 * Whether what was written down has been read yet.
	 *
	 * <h2>Why this loads itself instead of being handed its contents</h2>
	 *
	 * Because there are two windows with docks in them — the workspace and the
	 * modelling mode — and both remember widths and both write them out on the way
	 * closed. Loading from one of them means the other, opened first, starts with an
	 * empty map and saves that empty map over everything anybody had set. A setting
	 * that survives some evenings and not others is worse than one that never saves,
	 * because nobody can tell which evening they are having.
	 *
	 * So the rule is not "whoever opens first must remember to load". It is that this
	 * cannot be read before it has read itself.
	 */
	private static boolean loaded;

	private static void ensureLoaded() {
		if (loaded) return;
		loaded = true;
		Map<String, Integer> from = com.mopicmp.npcstudio.client.NpcStudioConfig.get().panelWidth;
		if (from == null) return;
		for (Map.Entry<String, Integer> one : from.entrySet()) {
			// Anything nonsensical in the file is passed over rather than trusted. It is
			// a file somebody can edit, and a width of nought or of minus four is a panel
			// that cannot be drawn.
			if (one.getKey() == null || one.getValue() == null || one.getValue() <= 0) continue;
			KEPT.put(one.getKey(), one.getValue());
		}
	}

	/** What this panel was last pulled to, or nought when it never has been. */
	public static int of(String id) {
		ensureLoaded();
		Integer wide = KEPT.get(id);
		return wide == null ? 0 : wide;
	}

	public static void remember(String id, int wide) {
		if (id == null || wide <= 0) return;
		ensureLoaded();
		KEPT.put(id, wide);
	}

	/** Everything remembered, for whichever window is on its way closed. */
	public static Map<String, Integer> all() {
		ensureLoaded();
		return new LinkedHashMap<>(KEPT);
	}
}
