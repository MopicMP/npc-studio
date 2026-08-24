package com.mopicmp.npcstudio.client.skin;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import com.mopicmp.npcstudio.entity.FaceMask;

/**
 * Draws the face the way the game will, into a picture somebody can look at.
 *
 * <h2>Why this exists</h2>
 *
 * Because every argument about the eyes so far has been settled by looking, and
 * because the last two rounds of fixing them were checked by reasoning instead.
 * The quads {@link EyePaint} hands out are a pure function of the marking and of
 * what the eye is doing — no Minecraft anywhere in it — so the same quads can be
 * rasterised against the real skin here, at the same size, with the same
 * nearest-neighbour sampling the game uses. What comes out is not an
 * approximation of the result: it is the result, minus the lighting.
 *
 * Run with: {@code ./gradlew lookAtEyes}
 */
public final class EyeLookExport {

	/** The costume every complaint about the eyes was made against. */
	private static final String SKIN = "../test/Eyes/hd-skins/07a55dd89fa807ca.png";

	/** Its marking, copied out of the world that holds it. */
	private static final String MASK =
		"20:208.4.8.4.10.4.8.4.10.4.8.4.1a8//204.4.10.4.8.4.10.4.a.2.10.2.1a6"
			+ "//1a7.3.c.3.b.9.6.9.7.a.6.a.203///";

	/** How much of the head to draw round the eyes, in cells of the face. */
	private static final int FROM = 8;
	private static final int TO = 16;

	/** How large to blow each cell up, so a cell is visible as a cell. */
	private static final int ZOOM = 16;

	public static void main(String[] args) throws Exception {
		Path skin = Path.of(args.length > 0 ? args[0] : SKIN);
		Path out = Path.of(args.length > 1 ? args[1] : "../test/Eyes/looks");
		if (!Files.isRegularFile(skin)) {
			System.out.println("no skin at " + skin.toAbsolutePath());
			return;
		}
		Files.createDirectories(out);

		BufferedImage picture = ImageIO.read(skin.toFile());
		FaceMask mask = FaceMask.decode(MASK, true);

		// Twice: once from the marking as it was made, and once from the same
		// marking squeezed to eight cells — which is the copy a character dressed
		// before masks kept their own size is still wearing, and therefore what the
		// game draws while the pictures come out right.
		List<String> made = new ArrayList<>();
		for (boolean coarse : new boolean[] { false, true }) {
			FaceReading face = FaceReading.of(coarse ? FaceMask.of(mask.reduce()) : mask);
			for (Look look : looks()) {
				BufferedImage drawn = draw(picture, face, look);
				Path file = out.resolve((coarse ? "coarse-" : "") + look.name() + ".png");
				ImageIO.write(drawn, "png", file.toFile());
				made.add(file.getFileName().toString());
			}
		}
		System.out.println("wrote " + made.size() + " into " + out.toAbsolutePath());
		for (String name : made) System.out.println("  " + name);
	}

	/** One thing an eye can be doing. */
	private record Look(String name, float aside, float pupil, float shut) { }

	private static List<Look> looks() {
		return List.of(
			new Look("00-at-rest", 0f, 0f, 0f),
			new Look("01-looking-right", 1f, 0f, 0f),
			new Look("02-looking-right-half", 0.5f, 0f, 0f),
			new Look("03-looking-left", -1f, 0f, 0f),
			// The two the light alone can reach, which is what "the eyes are enormous
			// at night" was about: a dark room, and a dark room with somebody standing
			// in it. Anything wider than these has to be asked for by an expression.
			new Look("04-dark-room", 0f, 0.22f, 0f),
			new Look("04b-dark-room-noticed", 0f, 0.32f, 0f),
			new Look("05-pupils-widest", 0f, 1f, 0f),
			new Look("06-pupils-narrow", 0f, -1f, 0f),
			new Look("07-looking-right-wide", 1f, 0.6f, 0f),
			new Look("08-blink-third", 0f, 0f, 0.34f),
			new Look("09-blink-two-thirds", 0f, 0f, 0.67f),
			new Look("10-blink-shut", 0f, 0f, 1f));
	}

	/**
	 * The face with one look on it.
	 *
	 * The skin is copied first and the quads are stamped over it in the order the
	 * game draws them — the eye, then the lid, then the lash — each sampling the
	 * skin as it was rather than as it is becoming, which is what a texture lookup
	 * does.
	 */
	private static BufferedImage draw(BufferedImage picture, FaceReading face, Look look) {
		int scale = picture.getWidth() / 64;
		int size = (TO - FROM) * scale;
		BufferedImage out = new BufferedImage(size * ZOOM, size * ZOOM, BufferedImage.TYPE_INT_RGB);

		int[][] cells = new int[TO - FROM][TO - FROM];
		for (int y = 0; y < TO - FROM; y++) {
			for (int x = 0; x < TO - FROM; x++) {
				cells[y][x] = 0;
			}
		}

		// The face as it comes off the skin, in cells of the marking.
		int span = (TO - FROM) * scale;
		int[][] pixels = new int[span][span];
		for (int y = 0; y < span; y++) {
			for (int x = 0; x < span; x++) {
				pixels[y][x] = picture.getRGB(FROM * scale + x, FROM * scale + y);
			}
		}
		int[][] before = new int[span][span];
		for (int y = 0; y < span; y++) System.arraycopy(pixels[y], 0, before[y], 0, span);

		for (boolean right : new boolean[] { true, false }) {
			EyeRows rows = right ? face.rows() : face.rows().mirrored();
			Glance glance = Glance.of(face, look.aside(), 0f, look.pupil(), right);
			if (glance != null) stamp(pixels, before, EyePaint.eye(rows, glance), scale);
			if (look.shut() > 0) {
				stamp(pixels, before,
					EyePaint.lid(rows, face.eyeTop(), face.eyeBottom(), look.shut()), scale);
				stamp(pixels, before,
					EyePaint.lash(rows, face.eyeTop(), face.eyeBottom(), look.shut()), scale);
			}
		}

		for (int y = 0; y < size * ZOOM; y++) {
			for (int x = 0; x < size * ZOOM; x++) {
				out.setRGB(x, y, pixels[y / ZOOM][x / ZOOM]);
			}
		}
		return out;
	}

	/**
	 * One list of quads, stamped onto the face.
	 *
	 * Sampled from the untouched copy, because a texture does not change under a
	 * draw call; written into the working one, because that is the framebuffer.
	 * Sixty-fourths are turned into pixels of the picture by the same arithmetic
	 * the shader does: the face begins at 8 and there are {@code scale} pixels to
	 * a sixty-fourth.
	 */
	private static void stamp(int[][] into, int[][] from, List<EyePaint.Patch> patches, int scale) {
		for (EyePaint.Patch patch : patches) {
			if (patch.redundant()) continue;
			int x0 = Math.round((patch.u0() - FROM) * scale);
			int x1 = Math.round((patch.u1() - FROM) * scale);
			int y0 = Math.round((patch.v0() - FROM) * scale);
			int y1 = Math.round((patch.v1() - FROM) * scale);
			if (x1 <= x0 || y1 <= y0) continue;

			for (int y = y0; y < y1; y++) {
				for (int x = x0; x < x1; x++) {
					// Where in the source rectangle this pixel's middle falls.
					float acrossPart = (x - x0 + 0.5f) / (x1 - x0);
					float downPart = (y - y0 + 0.5f) / (y1 - y0);
					float u = patch.su0() + (patch.su1() - patch.su0()) * acrossPart;
					float v = patch.sv0() + (patch.sv1() - patch.sv0()) * downPart;
					int sx = (int) Math.floor((u - FROM) * scale);
					int sy = (int) Math.floor((v - FROM) * scale);
					if (sx < 0 || sy < 0 || sx >= from.length || sy >= from.length) continue;
					if (x < 0 || y < 0 || x >= into.length || y >= into.length) continue;
					into[y][x] = from[sy][sx];
				}
			}
		}
	}

	private EyeLookExport() { }
}
