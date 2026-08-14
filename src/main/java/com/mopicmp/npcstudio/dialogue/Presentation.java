package com.mopicmp.npcstudio.dialogue;

/**
 * How a line is put in front of the player.
 *
 * Set per line rather than per dialogue, so one scene can open as a subtitle,
 * take the camera away for a beat, and come back — without the writer having to
 * split it into three dialogues.
 *
 * The engine only carries this value; what it looks like is entirely the
 * presentation layer's business. What the engine does care about is the third
 * column, because it decides whether the player can walk off mid-sentence.
 *
 * <table>
 *   <tr><th>mode</th><th>control</th><th>advanced by</th></tr>
 *   <tr><td>SUBTITLE</td><td>player keeps it</td><td>timer or right-click</td></tr>
 *   <tr><td>CUTSCENE</td><td>taken away</td><td>right-click; Esc ends the scene</td></tr>
 *   <tr><td>FULLSCREEN</td><td>taken away</td><td>picking an option; Esc suspends</td></tr>
 * </table>
 */
public enum Presentation {
	SUBTITLE,
	CUTSCENE,
	FULLSCREEN;

	/** Whether the player can still move, and so can walk out of range. */
	public boolean playerKeepsControl() {
		return this == SUBTITLE;
	}
}
