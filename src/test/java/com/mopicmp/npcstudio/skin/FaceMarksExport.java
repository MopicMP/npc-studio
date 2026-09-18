package com.mopicmp.npcstudio.skin;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import com.mopicmp.npcstudio.entity.EyeMap;
import com.mopicmp.npcstudio.wardrobe.PictureLibrary;

/**
 * Takes the faces a person marked in game out of a save and into the repository.
 *
 * <h2>Why the labels are copied rather than read where they lie</h2>
 *
 * They are made in game, so they live in {@code run/saves/<world>/npcstudio}: a
 * folder named after a world that may be renamed or deleted, next to skins kept
 * by fingerprint. A test that reads from there measures nothing on any machine
 * but this one, and stops measuring here the moment the world is thrown away.
 *
 * So the marks and the sixty-four pixels they were made on are written out
 * together, as text, into {@code test/Eyes/faces.txt}. A face is sixty-four
 * numbers; the whole corpus is a few pages. After that the measurement needs no
 * pictures, no save, and no image library — which is what lets it be an ordinary
 * test that runs on every build rather than a thing somebody remembers to do.
 *
 * Run with: {@code ./gradlew exportFaceMarks}
 */
public final class FaceMarksExport {

	/** Where the marking is done, unless told otherwise. */
	private static final String SAVE = "run/saves/Новый мир (1)/npcstudio";

	public static void main(String[] args) throws Exception {
		Path save = Path.of(args.length > 0 ? args[0] : SAVE);
		Path out = Path.of(args.length > 1 ? args[1] : "../test/Eyes/faces.txt");
		if (!Files.isDirectory(save)) {
			System.out.println("no wardrobe at " + save.toAbsolutePath());
			return;
		}

		PictureLibrary library = PictureLibrary.wardrobeShelf(save);
		StringBuilder text = new StringBuilder();
		text.append("""
			# Faces marked by hand, with the pixels they were marked on.
			#
			# One record per costume somebody opened the eye grid on. Masks are hex, bit
			# y*8+x, so the first pixel of the top row is the lowest bit. Pixels are ARGB
			# hex, eight to a line, top row first. Written by FaceMarksExport; read by
			# FaceLookAccuracyTest. Do not edit by hand — mark in game and export again.
			""");

		// The fine masks go to their own file, keyed by the skin's fingerprint. See
		// the note on writeMarks below for why they cannot ride in this one.
		StringBuilder fine = new StringBuilder();
		fine.append("""
			# Faces marked by hand, at the size the skin was drawn.
			#
			# One line per costume: the skin's fingerprint (SHA-256 of the PNG, first
			# thirty-two hex digits) and the mask, run-length encoded — see FaceMask.
			# The pixels are not here: an HD face is a quarter of a million numbers and
			# the skins themselves are already in test/Eyes. Match a line to its picture
			# by hashing the files there.
			""");

		int written = 0;
		int marks = 0;
		for (PictureLibrary.Entry entry : library.entries()) {
			if (entry.face().authored() && !entry.face().isNone()) {
				fine.append("mask ").append(entry.fingerprint()).append(' ')
					.append(entry.face().encode()).append('\n');
				marks++;
			}

			EyeMap marked = entry.eyes();
			if (!marked.authored() || marked.isNone()) continue;

			byte[] png = library.picture(entry.fingerprint());
			if (png == null || png.length == 0) continue;
			int[] face = faceOf(png);
			if (face == null) continue;

			text.append("\nface ").append(entry.fingerprint()).append('\n');
			text.append("eyes ").append(Long.toHexString(marked.eyes())).append('\n');
			text.append("whites ").append(Long.toHexString(marked.whites())).append('\n');
			text.append("brows ").append(Long.toHexString(marked.brows())).append('\n');
			for (int y = 0; y < EyeMap.SIZE; y++) {
				text.append("pixels");
				for (int x = 0; x < EyeMap.SIZE; x++) {
					text.append(' ').append(String.format("%08x", face[y * EyeMap.SIZE + x]));
				}
				// The same row again as a picture, so the file can be read by a person:
				// e is an eye, w a white, b a brow, and the shading is the skin.
				text.append("   # ");
				for (int x = 0; x < EyeMap.SIZE; x++) {
					text.append(marked.isEye(x, y) ? 'e'
						: marked.isWhite(x, y) ? 'w'
						: marked.isBrow(x, y) ? 'b' : '.');
				}
				text.append('\n');
			}
			written++;
		}

		Files.createDirectories(out.toAbsolutePath().getParent());
		Files.writeString(out, text.toString());
		System.out.printf("wrote %d marked faces to %s%n", written, out.toAbsolutePath());

		Path marksFile = out.resolveSibling("marks.txt");
		Files.writeString(marksFile, fine.toString());
		System.out.printf("wrote %d fine masks to %s%n", marks, marksFile.toAbsolutePath());
	}

	/**
	 * The eight-by-eight face, sampled exactly the way the game samples it.
	 *
	 * <h2>Why "exactly" is the whole of this method</h2>
	 *
	 * The corpus is what the reading is measured against, so a corpus sampled by
	 * some other rule measures a reading nobody runs. This used to take the corner
	 * pixel of each block, and the game takes whichever pixel of the block is least
	 * like the face's own colour — a difference worth two faces of twenty-five on
	 * detailed skins, which is precisely where the measurement is supposed to be
	 * sharpest.
	 *
	 * The outer layer is <b>not</b> laid over it, and that matches the game rather
	 * than matching what a player sees. See {@code FacePicture.eighths}: the
	 * composite is the honest picture and this reading is measurably bad at it, so
	 * both sides of the measurement read the near layer until there is a reading
	 * built for the far one.
	 */
	private static int[] faceOf(byte[] png) throws Exception {
		var image = ImageIO.read(new ByteArrayInputStream(png));
		if (image == null || image.getWidth() < 64 || image.getWidth() % 64 != 0) return null;
		int step = image.getWidth() / 64;

		int[] middles = new int[EyeMap.SIZE * EyeMap.SIZE];
		for (int y = 0; y < EyeMap.SIZE; y++) {
			for (int x = 0; x < EyeMap.SIZE; x++) {
				middles[y * EyeMap.SIZE + x] = at(image, step,
					x * step + step / 2, y * step + step / 2);
			}
		}
		if (step == 1) return middles;

		int skin = FaceLook.complexion(middles);
		int[] face = new int[EyeMap.SIZE * EyeMap.SIZE];
		for (int y = 0; y < EyeMap.SIZE; y++) {
			for (int x = 0; x < EyeMap.SIZE; x++) {
				int furthest = middles[y * EyeMap.SIZE + x];
				int worst = -1;
				for (int dy = 0; dy < step; dy++) {
					for (int dx = 0; dx < step; dx++) {
						int colour = at(image, step, x * step + dx, y * step + dy);
						if ((colour >>> 24) < 128) continue;
						int away = FaceLook.apart(colour, skin);
						if (away > worst) {
							worst = away;
							furthest = colour;
						}
					}
				}
				face[y * EyeMap.SIZE + x] = furthest;
			}
		}
		return face;
	}

	/** One pixel of the face itself. */
	private static int at(java.awt.image.BufferedImage image, int step, int x, int y) {
		return image.getRGB(EyeMap.FACE_LEFT * step + x, EyeMap.FACE_TOP * step + y);
	}

	private FaceMarksExport() { }
}
