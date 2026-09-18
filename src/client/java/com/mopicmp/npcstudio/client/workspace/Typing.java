package com.mopicmp.npcstudio.client.workspace;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;

/**
 * Whether the keyboard belongs to letters right now.
 *
 * <h2>The bug this exists for</h2>
 *
 * Somebody was typing a line of dialogue, wrote an H, and every panel in the
 * workspace vanished. H is the key that stands the panels aside, and it fired
 * while the caret was in a text box.
 *
 * It is not that the shortcut was checked too early — it is checked after the
 * panels have had the key, which is exactly where the comment beside it says it
 * should be. The mistake is in what "the panels have had it" was taken to mean.
 * A {@link EditBox} returns <em>false</em> from {@code keyPressed} for an
 * ordinary letter, because a letter does not arrive as a key press at all: it
 * arrives afterwards, on its own, as {@code charTyped}. So the box declines the
 * key, the workspace concludes nobody wanted it, hides everything — and then the
 * letter turns up and is dutifully typed into the box behind the empty screen.
 *
 * Every bare-letter shortcut has this hole, and it cannot be closed by asking
 * the widget, because the widget has already truthfully said no. It can only be
 * closed by asking a different question: not "did anyone take this key" but "is
 * anyone expecting letters".
 *
 * <h2>Why a walk and not a flag</h2>
 *
 * A flag would have to be set and cleared by every field that gains and loses
 * the caret, in every panel, for ever — which is the shape of a rule that is
 * obeyed five times and forgotten the sixth. The focus chain already knows the
 * answer; this only reads it.
 */
public final class Typing {

	private Typing() { }

	/**
	 * Far enough down a chain of containers to reach anything real, and short
	 * enough that a listener which somehow holds itself cannot spin here.
	 */
	private static final int DEEP = 16;

	/**
	 * Something that takes letters without holding a widget that does.
	 *
	 * The walk below can only find text fields, and a screen that draws its own
	 * caret has none — the dialogue editor's text window is exactly that. Left to
	 * the walk, the workspace would decide nobody was typing while somebody was
	 * typing a sentence, and the first bare letter they used would hide every panel.
	 *
	 * It answers about its <em>own</em> state only. Delegating back here would be a
	 * question that asks itself.
	 */
	public interface Aware {
		boolean takesLetters();
	}

	/** Whether the caret sits in a text field somewhere below this listener. */
	public static boolean into(GuiEventListener listener) {
		GuiEventListener at = listener;
		for (int step = 0; step < DEEP && at != null; step++) {
			if (at instanceof Aware aware && aware.takesLetters()) return true;
			// Named one by one on purpose. A test cannot catch a text widget left off
			// this list — it would have to build one, and building one needs a font and
			// a window — so the guard is that there is exactly one place to add it, and
			// it is the place anybody adding a text widget to this workspace will be
			// sent to by the very symptom above.
			if (at instanceof EditBox) return true;
			// And ours, which is the whole reason the note above says there is exactly
			// one place to add it. A field missing from this list is a field whose
			// letters double as workspace shortcuts — H stands the panels aside in the
			// middle of somebody typing a name.
			if (at instanceof com.mopicmp.npcstudio.client.editor.FlatField) return true;
			if (!(at instanceof ContainerEventHandler container)) return false;
			GuiEventListener below = container.getFocused();
			if (below == at) return false;
			at = below;
		}
		return false;
	}
}
