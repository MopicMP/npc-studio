package com.mopicmp.npcstudio.client.skin;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.blaze3d.platform.NativeImage;
import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.skin.FaceLook;

import net.minecraft.client.Minecraft;

/**
 * The actual pixels of a skin, for the code that has to look at them.
 *
 * Drawing a skin needs no pixels — the graphics card has them and that is
 * enough. Reading one does, and the game offers no way in: a resolved skin
 * comes back as a texture on the card, and the skin cache is a directory whose
 * naming is its own business. So the picture is fetched again here, from the
 * address the profile itself carries.
 *
 * Two callers want this and it is worth saying so, because it decides the
 * shape. {@link FaceReading} needs to know where a face's eyes are before it
 * dares put a lid over them. A skin browser needs the same picture to show it
 * and to say whether it will work. One fetch, one cache, both served.
 *
 * Nothing blocks. A caller asks, gets nothing the first time, and gets an
 * answer some frames later — which is exactly how the game treats skins itself,
 * and why a character wears a default face for a moment when it comes into
 * view.
 */
public final class SkinPixels {

	private static final HttpClient HTTP = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	/** Skins are small; anything this big is not one. */
	private static final int LIMIT = 2 * 1024 * 1024;

	private static final Map<String, FaceReading> read = new HashMap<>();
	private static final Set<String> asking = new HashSet<>();

	private SkinPixels() { }

	/**
	 * What a picture's face has on it, or null while it is still being fetched.
	 *
	 * Keyed by the address rather than by the profile: two characters wearing the
	 * same skin are the same picture and deserve one download between them.
	 */
	public static FaceReading of(GameProfile profile) {
		String url = textureUrl(profile);
		if (url == null) return null;

		FaceReading already = read.get(url);
		if (already != null) return already;
		fetch(url);
		return null;
	}

	/**
	 * The face of a skin that is already in the game's own resources.
	 *
	 * <h2>Why this was missing, and what it cost</h2>
	 *
	 * A skin was only ever looked for at the address in a profile, which is right
	 * for a player and wrong for almost every NPC. A character nobody has given a
	 * skin to wears Steve — and Steve is not at an address. He is a PNG inside the
	 * game, so there was no URL, so there was no face, so there were no eyes, so
	 * <b>no NPC ever blinked</b> unless somebody had uploaded a picture to it.
	 *
	 * The same held for anyone playing offline, and for any server whose profiles
	 * had not been resolved yet. In other words the feature worked in the one case
	 * it was tested in and in no other.
	 *
	 * Reading it back out of the resource pack costs nothing and needs no network:
	 * whatever texture the game has decided to draw this character with, we open
	 * the same file and look at it.
	 */
	public static FaceReading ofResource(net.minecraft.resources.Identifier texture) {
		if (texture == null) return null;
		String key = texture.toString();
		FaceReading already = read.get(key);
		if (already != null) return already;

		try (var open = Minecraft.getInstance().getResourceManager()
				.getResourceOrThrow(texture).open()) {
			FaceReading reading = analyse(open.readAllBytes());
			read.put(key, reading == null ? FaceReading.BLANK : reading);
		} catch (Exception missing) {
			// Remembered as blank rather than retried. This is called from drawing,
			// and a texture that is not in the pack this frame will not be in it on
			// the next one either.
			read.put(key, FaceReading.BLANK);
			NpcStudio.LOGGER.warn("Could not read the skin {}: {}", key, missing.toString());
		}
		return read.get(key);
	}

	/** The same, for a picture we already hold — an uploaded skin needs no fetching. */
	public static FaceReading of(String key, byte[] png) {
		FaceReading already = read.get(key);
		if (already != null) return already;

		FaceReading reading = analyse(png);
		read.put(key, reading == null ? FaceReading.BLANK : reading);
		return read.get(key);
	}

	/**
	 * Where a skin may be fetched from.
	 *
	 * The address comes out of a profile, and a profile comes from the server —
	 * so an unfriendly server could name any address it liked and this client
	 * would dutifully connect to it. That is worth something to somebody: it
	 * reveals the player's address to a third party, and it lets a server probe
	 * machines on the player's own network from inside it.
	 *
	 * Minecraft's own downloader restricts this, and so does this. Skins live at
	 * Mojang; anywhere else is not a skin.
	 */
	private static final java.util.List<String> ALLOWED =
		java.util.List.of("textures.minecraft.net", "assets.mojang.com", "api.mojang.com");

	private static boolean permitted(String url) {
		try {
			URI where = URI.create(url);
			if (!"https".equalsIgnoreCase(where.getScheme())) return false;
			String host = where.getHost();
			if (host == null) return false;
			host = host.toLowerCase(java.util.Locale.ROOT);
			for (String allowed : ALLOWED) {
				if (host.equals(allowed) || host.endsWith("." + allowed)) return true;
			}
			return false;
		} catch (RuntimeException malformed) {
			return false;
		}
	}

	private static void fetch(String url) {
		if (!permitted(url)) {
			// Remembered as nothing, so a server that keeps offering a bad address
			// is asked about once rather than on every frame.
			read.put(url, FaceReading.BLANK);
			NpcStudio.LOGGER.warn("Refused to fetch a skin from {}", url);
			return;
		}
		if (!asking.add(url)) return;

		HTTP.sendAsync(HttpRequest.newBuilder(URI.create(url)).GET().build(),
				HttpResponse.BodyHandlers.ofByteArray())
			.thenAccept(response -> {
				byte[] body = response.body();
				FaceReading reading = response.statusCode() == 200 && body.length <= LIMIT
					? analyse(body) : null;
				// Back to the game's own thread before touching the maps, because
				// everything that reads them is drawing when it does.
				Minecraft.getInstance().execute(() -> {
					read.put(url, reading == null ? FaceReading.BLANK : reading);
					asking.remove(url);
				});
			})
			.exceptionally(failed -> {
				Minecraft.getInstance().execute(() -> {
					// Remembered as "nothing there" rather than retried: a skin that
					// will not download will not download on the next frame either,
					// and a face is redrawn sixty times a second.
					read.put(url, FaceReading.BLANK);
					asking.remove(url);
				});
				NpcStudio.LOGGER.warn("Could not fetch a skin: {}", failed.toString());
				return null;
			});
	}

	/**
	 * Reads a face out of a PNG.
	 *
	 * The picture is closed again straight away. Only a dozen numbers are kept —
	 * where the eyes are and what colour the brows were — so holding on to a
	 * megabyte of pixels for every character that has ever been looked at would
	 * be keeping the wrapper and throwing away the sweet.
	 */
	private static FaceReading analyse(byte[] png) {
		try (NativeImage image = NativeImage.read(new ByteArrayInputStream(png))) {
			// The face alone, without the outer layer over it — and that is a debt
			// rather than a decision. See FacePicture: what a player looks at is the
			// two composited, and reading only the near one means reading a picture
			// nobody sees.
			//
			// It stays this way because the alternative was measured and is worse.
			// Against eighty-one faces marked by hand, compositing takes the rows a
			// blink lands on from six of twenty-five to nine on HD skins — and from
			// forty-one of fifty-six down to twenty on ordinary ones. Not because it
			// hides the eyes: on all twenty-one faces it broke, the marked eyes are
			// completely in the clear. It is that a fringe over the forehead changes
			// what the rest of the face looks like, and this rule picks its band of
			// rows by comparing them. Re-tuning the four numbers it has recovers
			// thirty of eighty-one, still well short.
			//
			// So the composite needs a reading built for it rather than one talked
			// into it, and until there is one, this reads what it was measured on.
			FacePicture face = FacePicture.read(image);
			if (face == null) return null;
			return FaceReading.of(FaceLook.read(face.eighths(false)));
		} catch (Exception broken) {
			NpcStudio.LOGGER.warn("Could not read a skin: {}", broken.toString());
			return null;
		}
	}

	/**
	 * The eight-by-eight face, sampled out of a skin of whatever size.
	 *
	 * A skin at twice or four times the size is the same layout drawn larger, so
	 * the reading always works in sixty-fourths and this is where the scaling
	 * happens. Twice, because which pixel of a block to take depends on knowing
	 * the face's colour, and knowing the face's colour depends on having sampled
	 * it: once plainly to find the complexion, then again taking from each block
	 * whichever pixel is least like it.
	 *
	 * Taking a single corner is what stopped detailed skins from ever blinking. An
	 * eye drawn as a one-pixel line on a 128-wide skin lives in three quarters of
	 * a block, and the corner is the other quarter — so three faces in four came
	 * back as bare cheek.
	 */
	private static int[] faceOf(NativeImage image, int step) {
		int size = com.mopicmp.npcstudio.entity.EyeMap.SIZE;
		int left = com.mopicmp.npcstudio.entity.EyeMap.FACE_LEFT;
		int top = com.mopicmp.npcstudio.entity.EyeMap.FACE_TOP;

		int[] face = new int[size * size];
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				face[y * size + x] = image.getPixel((left + x) * step, (top + y) * step);
			}
		}
		if (step == 1) return face;

		int skin = FaceLook.complexion(face);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				int furthest = face[y * size + x];
				int worst = -1;
				for (int dy = 0; dy < step; dy++) {
					for (int dx = 0; dx < step; dx++) {
						int colour = image.getPixel((left + x) * step + dx, (top + y) * step + dy);
						if ((colour >>> 24) < 128) continue;
						int away = FaceLook.apart(colour, skin);
						if (away > worst) {
							worst = away;
							furthest = colour;
						}
					}
				}
				face[y * size + x] = furthest;
			}
		}
		return face;
	}

	/**
	 * Digs the skin's address out of a profile.
	 *
	 * It travels as base64 of a small piece of JSON, which is Mojang's shape and
	 * not ours to argue with. Absent until the profile has been resolved, which
	 * is normal rather than a failure.
	 */
	private static String textureUrl(GameProfile profile) {
		for (Property property : profile.properties().get("textures")) {
			try {
				String decoded = new String(Base64.getDecoder().decode(property.value()),
					java.nio.charset.StandardCharsets.UTF_8);
				JsonObject textures = JsonParser.parseString(decoded).getAsJsonObject()
					.getAsJsonObject("textures");
				if (textures == null) continue;
				JsonObject skin = textures.getAsJsonObject("SKIN");
				if (skin != null && skin.has("url")) return skin.get("url").getAsString();
			} catch (Exception unreadable) {
				NpcStudio.LOGGER.warn("Could not read a profile's textures: {}", unreadable.toString());
			}
		}
		return null;
	}

	/**
	 * The face of whatever this character is actually wearing.
	 *
	 * An uploaded skin is already in hand and is read at once; a skin named by
	 * profile has to be fetched, so the first few frames come back with nothing
	 * and the character simply does not blink until it arrives. That is the same
	 * pause the game itself takes to put a face on anybody.
	 */
	public static FaceReading forNpc(com.mopicmp.npcstudio.client.entity.ClientNpcEntity npc) {
		String mark = npc.skinMark();
		if (!mark.isEmpty()) {
			byte[] uploaded = npc.customSkin();
			// Only the client that uploaded it holds the bytes; everyone else was
			// sent them and keeps them under the same fingerprint.
			if (uploaded != null && uploaded.length > 0) return of(mark, uploaded);
			byte[] shared = CustomSkins.pixelsFor(mark);
			if (shared != null) return of(mark, shared);
			return null;
		}

		// A profile that carries an address is fetched from it, once, and shared
		// between every character wearing the same skin.
		FaceReading remote = of(npc.getProfile().partialProfile());
		if (remote != null) return remote;

		// Otherwise: whatever the game has actually decided to draw. That covers
		// Steve and Alex, everybody playing offline, and every profile that has not
		// been resolved yet — which between them is most characters in most worlds.
		//
		// Asked of the entity rather than worked out from a name, so this stays
		// right if the character is wearing one of the newer default skins, or if a
		// resource pack has replaced them.
		return ofResource(npc.getSkin().body().texturePath());
	}

	/** Dropped on leaving a world, so a long session does not accumulate faces. */
	public static void forget() {
		read.clear();
		asking.clear();
	}
}
