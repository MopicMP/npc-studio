package com.mopicmp.npcstudio.entity;

/**
 * One look a character can wear.
 *
 * Players keep the same face and change their clothes — a costume for a
 * holiday, another for work, a third for home — and an NPC in a built world is
 * the same. Holding one skin meant re-uploading a file every time a character
 * changed for a scene, which is the kind of small friction that stops people
 * doing it at all.
 *
 * A look is either a player's name or a picture. Both, because they arrive from
 * different places and neither replaces the other: a name is a live reference
 * that follows whoever owns it, and a file is a thing you made and want kept.
 *
 * A look also carries where its face keeps its eyes. That belongs here rather
 * than beside the picture because it is a fact about <em>this</em> look: change
 * the costume and the face changes with it, and a character wearing a helmet has
 * no eyes to blink whatever the skin underneath says. Keeping it with the
 * costume is also what puts it on the wire — everybody then sees the same face
 * blink at the same moment, instead of each client reading the picture and
 * guessing separately.
 *
 * @param label  what a person calls it; the only part they will read
 * @param name   a player name to wear, or empty when this is a file
 * @param pixels the picture, or empty when this is a name
 * @param eyes   which pixels of the face are eyes and brows
 */
public record Outfit(String label, String name, byte[] pixels, FaceMask eyes) {

	public static Outfit named(String label, String playerName) {
		return new Outfit(label, playerName, new byte[0], FaceMask.NONE);
	}

	public static Outfit picture(String label, byte[] png) {
		return new Outfit(label, "", png, FaceMask.NONE);
	}

	/** The same look with its face read, or marked out by hand. */
	public Outfit looking(FaceMask read) {
		return new Outfit(label, name, pixels, read == null ? FaceMask.NONE : read);
	}

	public boolean isPicture() {
		return pixels != null && pixels.length > 0;
	}
}
