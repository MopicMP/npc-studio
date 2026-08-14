package com.mopicmp.npcstudio.skin;

import java.io.File;
import java.util.Arrays;

import javax.imageio.ImageIO;

import com.mopicmp.npcstudio.entity.EyeMap;

/**
 * Prints what the reading makes of every skin in the eye folder.
 *
 * Not a test — a report. Its output is what a person looks at to say "that one
 * is wrong", and those answers become the labels the real test measures against.
 * Run with: {@code ./gradlew runFaceLookReport}
 */
public final class FaceLookReport {

	public static void main(String[] args) throws Exception {
		File folder = new File(args.length > 0 ? args[0] : "../test/Eyes/skins");
		File[] skins = folder.listFiles((d, n) -> n.endsWith(".png"));
		if (skins == null) {
			System.out.println("no skins at " + folder.getAbsolutePath());
			return;
		}
		Arrays.sort(skins);

		int read = 0;
		for (File skin : skins) {
			int[] face = faceOf(skin);
			EyeMap map = FaceLook.read(face);
			if (!map.isNone()) read++;

			System.out.println();
			System.out.println(skin.getName() + (map.isNone() ? "   NOTHING READ" : ""));
			for (int y = 0; y < EyeMap.SIZE; y++) {
				StringBuilder line = new StringBuilder("   ");
				for (int x = 0; x < EyeMap.SIZE; x++) {
					int colour = face[y * EyeMap.SIZE + x];
					char what = map.isEye(x, y) ? 'O'
						: map.isWhite(x, y) ? 'o'
						: map.isBrow(x, y) ? '^' : shade(colour);
					line.append(what).append(what);
				}
				System.out.println(line);
			}
		}
		System.out.println();
		System.out.printf("read %d of %d, said nothing on %d%n", read, skins.length, skins.length - read);
	}

	/** Light to dark, so a face is legible as text. O is an eye, o its white, ^ a brow. */
	private static char shade(int argb) {
		int light = (((argb >> 16) & 0xFF) * 30 + ((argb >> 8) & 0xFF) * 59 + (argb & 0xFF) * 11) / 100;
		return " .:-=+*#%@".charAt(Math.clamp(9 - light * 10 / 256, 0, 9));
	}

	/** The eight-by-eight face, sampled from whatever size the picture is. */
	static int[] faceOf(File png) throws Exception {
		var image = ImageIO.read(png);
		int scale = image.getWidth() / 64;
		int[] face = new int[EyeMap.SIZE * EyeMap.SIZE];
		for (int y = 0; y < EyeMap.SIZE; y++) {
			for (int x = 0; x < EyeMap.SIZE; x++) {
				face[y * EyeMap.SIZE + x] =
					image.getRGB((EyeMap.FACE_LEFT + x) * scale, (EyeMap.FACE_TOP + y) * scale);
			}
		}
		return face;
	}

	private FaceLookReport() { }
}
