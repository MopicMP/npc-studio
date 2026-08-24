package com.mopicmp.npcstudio.client.wardrobe;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.platform.NativeImage;
import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.net.WardrobePayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * The world's costumes as this client knows them.
 *
 * The list arrives whole and the pictures do not. A library of a thousand
 * costumes is megabytes of PNG and a few pages of names, and a screen draws
 * itself from the names — so a picture is asked for when something is about to
 * be shown and not before. Scrolling past a costume without looking at it costs
 * nothing.
 *
 * A picture that has arrived is kept for the session under its own fingerprint,
 * which means two costumes that happen to be the same file share one texture as
 * naturally as they share one file on the server.
 */
public final class Costumes {

	private static List<WardrobePayloads.Costume> known = List.of();
	private static List<String> versions = List.of();

	private static final Map<String, Identifier> textures = new HashMap<>();
	private static final Map<String, byte[]> pictures = new HashMap<>();
	private static final Set<String> asked = new HashSet<>();

	private Costumes() { }

	public static List<WardrobePayloads.Costume> all() {
		return known;
	}

	public static List<String> versions() {
		return versions;
	}

	/**
	 * How many times the list has been replaced.
	 *
	 * A screen filters the library once when it opens and then draws from what it
	 * filtered, which is right — refiltering a thousand costumes every frame to
	 * find out that nothing changed is work for nothing. But it left no way to
	 * find out when something <em>did</em>, and a costume added a moment ago
	 * simply was not there until the screen was closed and opened again.
	 *
	 * A number that goes up is the cheapest way to ask: one comparison a frame,
	 * and the answer is right for every screen looking at the library at once
	 * rather than only for whichever one sent the change.
	 */
	private static int generation;

	public static int generation() {
		return generation;
	}

	public static void accept(List<WardrobePayloads.Costume> costumes) {
		known = List.copyOf(costumes);
		generation++;
	}

	public static void acceptVersions(List<String> names) {
		versions = List.copyOf(names);
	}

	/**
	 * Takes back the markings the world already holds.
	 *
	 * Arrives with the library and in batches after it, so what somebody marked
	 * last week opens as what they marked rather than as the reading's guess at it.
	 * Merged rather than replacing, because the batches are several and a costume
	 * marked a moment ago in this session should not blink out while they arrive.
	 */
	public static void acceptMarks(List<com.mopicmp.npcstudio.net.WardrobePayloads.Mark> marks) {
		for (var mark : marks) {
			marked.put(mark.costumeId(),
				com.mopicmp.npcstudio.entity.FaceMask.decode(mark.mask(), mark.byHand()));
		}
		generation++;
	}

	/** Asks the server for the whole list. Cheap, and the screen needs it to open. */
	public static void refresh() {
		ClientPlayNetworking.send(new WardrobePayloads.Please());
	}

	/**
	 * The texture for a costume, or null while it is still on its way.
	 *
	 * Asking is what starts the fetch, so a screen simply draws what it has and
	 * comes back to the rest next frame. The guard on asking matters: this is
	 * called from drawing, and without it a costume in view would be requested
	 * sixty times a second.
	 */
	public static Identifier texture(String fingerprint) {
		Identifier ready = textures.get(fingerprint);
		if (ready != null) return ready;
		if (asked.add(fingerprint)) {
			ClientPlayNetworking.send(new WardrobePayloads.PicturePlease(fingerprint));
		}
		return null;
	}

	/**
	 * How tall each picture is, in sixty-fourths: 64, or 32 for the old layout.
	 *
	 * Kept because a texture on the card cannot be asked. Every blit of a skin has
	 * to be told, and being told wrong is what made seven of the fifty measured HD
	 * skins look like broken pictures rather than like the perfectly good old-layout
	 * skins they are.
	 */
	private static final Map<String, Integer> heights = new HashMap<>();

	public static int tall(String fingerprint) {
		return heights.getOrDefault(fingerprint, 64);
	}

	/** The raw picture, for the code that composites rather than draws. */
	public static byte[] pixels(String fingerprint) {
		return pictures.get(fingerprint);
	}

	/**
	 * One piece of a picture, and the whole of it once the last piece lands.
	 *
	 * Pictures arrive in pieces because the largest of them are past what a packet
	 * carries. Held by fingerprint rather than by arrival order, since two costumes
	 * can be on their way at once and their pieces interleave.
	 */
	private static final Map<String, byte[][]> arriving = new HashMap<>();

	public static void acceptPicture(String fingerprint, int index, int count, byte[] part) {
		if (count < 1 || index < 0 || index >= count) return;

		byte[][] pieces = arriving.get(fingerprint);
		if (pieces == null || pieces.length != count) {
			pieces = new byte[count][];
			arriving.put(fingerprint, pieces);
		}
		pieces[index] = part;

		int size = 0;
		for (byte[] piece : pieces) {
			if (piece == null) return;
			size += piece.length;
		}

		arriving.remove(fingerprint);
		byte[] png = new byte[size];
		int at = 0;
		for (byte[] piece : pieces) {
			System.arraycopy(piece, 0, png, at, piece.length);
			at += piece.length;
		}
		acceptPicture(fingerprint, png);
	}

	public static void acceptPicture(String fingerprint, byte[] png) {
		asked.remove(fingerprint);
		if (textures.containsKey(fingerprint)) return;
		try {
			NativeImage image = NativeImage.read(new ByteArrayInputStream(png));
			heights.put(fingerprint, image.getHeight() * 2 <= image.getWidth() ? 32 : 64);
			Identifier where = NpcStudio.id("costumes/" + fingerprint);
			Minecraft.getInstance().getTextureManager()
				.register(where, new DynamicTexture(() -> fingerprint, image));
			textures.put(fingerprint, where);
			pictures.put(fingerprint, png);
		} catch (Exception broken) {
			// Remembered as nothing rather than retried: a picture that will not
			// open this frame will not open on the next one either.
			NpcStudio.LOGGER.warn("Could not read costume {}: {}", fingerprint, broken.toString());
		}
	}

	/** Dropped on leaving a world; the next one has its own wardrobe. */
	public static void forget() {
		known = List.of();
		versions = List.of();
		generation++;
		asked.clear();
		// The textures themselves are left registered: the texture manager owns
		// them now, and a fingerprint always names the same picture, so a costume
		// that comes back is the one already loaded.
		pictures.clear();
	}

	/**
	 * What Minecraft itself will carry from a client to a server, in bytes.
	 *
	 * Not our limit and not negotiable: vanilla caps a serverbound custom payload
	 * at this and throws on anything larger, which disconnects the player rather
	 * than failing politely. Our own codec claims to allow 384 KB, which was
	 * simply wrong — that much room exists in the clientbound direction, and skins
	 * travel both ways.
	 *
	 * Worth a check rather than a comment, because the skins this will bite are
	 * exactly the large, detailed ones somebody has spent time on.
	 */
	public static final int MOST_A_CLIENT_MAY_SEND = 32767;

	/**
	 * Whether a picture can reach the server at all.
	 *
	 * Now only a question about the file rather than about the wire. A skin larger
	 * than one packet used to be refused here, which turned out to mean refusing
	 * more than half of the HD skins anybody would want to mark — so they go in
	 * pieces instead, and the only limit left is how large a skin can be at all.
	 */
	public static boolean tooBig(byte[] pixels) {
		return pixels != null && pixels.length > com.mopicmp.npcstudio.wardrobe.SkinBytes.LARGEST;
	}

	/**
	 * Sends one skin, in as many pieces as it takes.
	 *
	 * Every skin goes this way, not only the large ones: one path is one path to
	 * get right, and a small skin is a transfer of a single piece.
	 */
	public static void add(String label, String category, String group, byte[] png) {
		String upload = java.util.UUID.randomUUID().toString();
		int part = com.mopicmp.npcstudio.net.WardrobePayloads.PART;
		int count = Math.max(1, (png.length + part - 1) / part);
		for (int i = 0; i < count; i++) {
			int from = i * part;
			int to = Math.min(png.length, from + part);
			ClientPlayNetworking.send(new WardrobePayloads.AddPart(upload, i, count,
				label, category, group, java.util.Arrays.copyOfRange(png, from, to)));
		}
	}

	public static void edit(WardrobePayloads.Edit.Verb verb, List<String> ids,
			String label, String category, String group, byte[] pixels) {
		ClientPlayNetworking.send(new WardrobePayloads.Edit(verb, ids, label, category, group, pixels));
	}

	/**
	 * Where each costume's eyes are, as far as this client knows.
	 *
	 * Filled from two directions: what somebody has marked in this session, and
	 * what the world already held, which arrives with the library.
	 *
	 * The second half is new and was the whole of a complaint. This map used to be
	 * the session's only, because the library list carries no mask — so a marking
	 * made last week was on disk, was being blinked with correctly, and was
	 * nevertheless invisible to the editor, which opened on the reading's guess
	 * instead. From the outside that is indistinguishable from the mod having
	 * thrown the marking away and made its own.
	 */
	private static final Map<String, com.mopicmp.npcstudio.entity.FaceMask> marked = new HashMap<>();

	public static com.mopicmp.npcstudio.entity.FaceMask markedEyes(String costumeId) {
		return marked.get(costumeId);
	}

	public static void markEyes(String costumeId, com.mopicmp.npcstudio.entity.FaceMask face) {
		marked.put(costumeId, face);
		ClientPlayNetworking.send(new WardrobePayloads.MarkEyes(
			costumeId, face.encode(), face.authored()));
	}

	/**
	 * A costume's face at the size it was drawn, or null if its picture has not
	 * arrived yet.
	 *
	 * Not sampled down to eight by eight any more, and both layers of the head
	 * rather than the first — see {@link com.mopicmp.npcstudio.client.skin.FacePicture}
	 * for the measurements that settle both.
	 */
	public static com.mopicmp.npcstudio.client.skin.FacePicture facePicture(String fingerprint) {
		byte[] png = pixels(fingerprint);
		if (png == null) return null;
		try (com.mojang.blaze3d.platform.NativeImage image =
				com.mojang.blaze3d.platform.NativeImage.read(new java.io.ByteArrayInputStream(png))) {
			return com.mopicmp.npcstudio.client.skin.FacePicture.read(image);
		} catch (java.io.IOException unreadable) {
			return null;
		}
	}

	/** The same face in eighths, which is what the detector still reads. */
	public static int[] face(String fingerprint) {
		var picture = facePicture(fingerprint);
		return picture == null ? null : picture.eighths();
	}

	public static void wear(int entityId, String costumeId) {
		ClientPlayNetworking.send(new WardrobePayloads.Wear(entityId, costumeId));
	}
}
