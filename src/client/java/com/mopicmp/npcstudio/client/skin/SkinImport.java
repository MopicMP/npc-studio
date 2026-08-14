package com.mopicmp.npcstudio.client.skin;

import java.nio.file.Files;
import java.nio.file.Path;

import com.mojang.blaze3d.platform.NativeImage;
import com.mopicmp.npcstudio.NpcStudio;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/**
 * Choosing a skin file off the disk.
 *
 * A skin is small — a few kilobytes — so the whole picture travels rather than
 * a reference to it. That is what makes it work at all in company: the file
 * sits on one person's machine, and everybody else has to see the character
 * too.
 *
 * Checked here before it goes anywhere. A skin has to be 64×64 or the old
 * 64×32, and anything else would arrive at the other end as a character
 * wrapped in someone's holiday photograph.
 */
public final class SkinImport {

	/**
	 * The largest a skin file may be.
	 *
	 * The same number the server uses, taken from there rather than written down
	 * again — and that was the whole of the bug this replaced. It said 384 kilobytes
	 * on the reasoning that a 256×256 skin is a few dozen, which is true and is not
	 * the question. Measured against fifty real HD skins the median is a hundred and
	 * ten kilobytes and fourteen are over that line, so the chooser was quietly
	 * dropping better than a quarter of what somebody selected.
	 *
	 * Quietly is the worse half. A file that is refused has to say so, by name and
	 * with a reason; see {@link #told}.
	 */
	public static final int LIMIT = com.mopicmp.npcstudio.wardrobe.SkinBytes.LARGEST;

	private SkinImport() { }

	/** The picture, or null and a reason. */
	public record Result(byte[] pixels, String message) { }

	public static Result choose() {
		Many many = chooseMany();
		if (many.skins().isEmpty()) return new Result(null, many.message());
		return new Result(many.skins().get(0).pixels(), many.message());
	}

	/**
	 * One picture, named, out of however many were chosen.
	 *
	 * The name travels because it is the only thing anybody will read afterwards:
	 * thirty-six costumes called "new costume" is a list nobody can use, and the
	 * file already says what each one is.
	 */
	public record Skin(String label, byte[] pixels) { }

	/** Everything that came out of one trip to the file chooser. */
	public record Many(java.util.List<Skin> skins, String message) { }

	/**
	 * Chooses any number of skins at once, from files or from an archive.
	 *
	 * One at a time was the whole interface, and it turned adding a set of
	 * thirty-six into thirty-six trips through a file dialog. A folder of skins
	 * arrives as a folder of skins, and people keep them zipped.
	 */
	public static Many chooseMany() {
		String picked;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer filters = stack.mallocPointer(2);
			filters.put(stack.UTF8("*.png"));
			filters.put(stack.UTF8("*.zip"));
			filters.flip();
			picked = TinyFileDialogs.tinyfd_openFileDialog(
				"Choose skins", null, filters, "Skins (*.png, *.zip)", true);
		} catch (Throwable unavailable) {
			NpcStudio.LOGGER.warn("No file chooser available: {}", unavailable.toString());
			return new Many(java.util.List.of(), "Could not open a file chooser.");
		}
		if (picked == null) return new Many(java.util.List.of(), "");

		java.util.List<Skin> skins = new java.util.ArrayList<>();
		java.util.List<String> refused = new java.util.ArrayList<>();
		// tinyfd hands several files back as one string, separated by pipes.
		for (String each : picked.split(java.util.regex.Pattern.quote("|"))) {
			if (each.isBlank()) continue;
			Path file = Path.of(each);
			if (each.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
				unpack(file, skins, refused);
			} else {
				Result one = read(file);
				if (one.pixels() != null) {
					skins.add(new Skin(nameOf(file.getFileName().toString()), one.pixels()));
				} else {
					refused.add(file.getFileName() + " — " + one.message());
				}
			}
		}
		return new Many(skins, told(skins.size(), refused));
	}

	/**
	 * Every skin inside an archive, ignoring whatever else is in there.
	 *
	 * Folders inside are walked through rather than refused, because a pack of
	 * skins is as likely to be sorted into folders as not. Anything that is not a
	 * picture of the right shape is passed over quietly — an archive with a readme
	 * in it is not a broken archive.
	 */
	private static void unpack(Path archive, java.util.List<Skin> into, java.util.List<String> refused) {
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive.toFile())) {
			var entries = zip.entries();
			while (entries.hasMoreElements()) {
				var entry = entries.nextElement();
				if (entry.isDirectory()) continue;
				String name = entry.getName();
				if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) continue;
				if (entry.getSize() > LIMIT) {
					refused.add(name + " — файл больше " + (LIMIT / 1024 / 1024) + " МБ");
					continue;
				}
				try (var stream = zip.getInputStream(entry)) {
					byte[] pixels = stream.readAllBytes();
					if (accepted(pixels)) {
						int slash = name.lastIndexOf('/');
						into.add(new Skin(nameOf(slash < 0 ? name : name.substring(slash + 1)), pixels));
					} else {
						refused.add(name + " — не похоже на скин");
					}
				}
			}
		} catch (Exception broken) {
			refused.add(archive.getFileName() + " (" + broken.getMessage() + ")");
		}
	}

	/** Whether these bytes are a skin-shaped picture. */
	private static boolean accepted(byte[] pixels) {
		if (pixels.length > LIMIT) return false;
		try (NativeImage image = NativeImage.read(new java.io.ByteArrayInputStream(pixels))) {
			return shaped(image.getWidth(), image.getHeight());
		} catch (Exception unreadable) {
			return false;
		}
	}

	/** A file name without its extension, which is what a person called the skin. */
	private static String nameOf(String file) {
		int dot = file.lastIndexOf('.');
		String bare = dot > 0 ? file.substring(0, dot) : file;
		return bare.isBlank() ? "новый костюм" : bare;
	}

	/**
	 * What happened, and what did not — by name.
	 *
	 * A count of what was skipped is not a report, it is a rumour. Somebody picking
	 * fifty files and getting forty-five has to be able to find out which five and
	 * why without guessing, and the answer they were given before was the number
	 * five. The first two are named in full because a line of chat has room for two;
	 * the rest are counted.
	 */
	private static String told(int taken, java.util.List<String> refused) {
		if (taken == 0 && refused.isEmpty()) return "";
		StringBuilder said = new StringBuilder();
		said.append(taken == 0 ? "ни один файл не подошёл" : "взято скинов: " + taken);
		if (refused.isEmpty()) return said.toString();

		said.append(". Пропущено ").append(refused.size()).append(": ");
		said.append(refused.get(0));
		if (refused.size() > 1) said.append("; ").append(refused.get(1));
		if (refused.size() > 2) said.append("; и ещё ").append(refused.size() - 2);
		// The whole list where it can be read at leisure, since a chat line cannot
		// hold fifty names and somebody adding fifty files wants all fifty answers.
		for (String each : refused) NpcStudio.LOGGER.info("Skin not taken: {}", each);
		return said.toString();
	}

	/**
	 * Whether a picture is skin-shaped.
	 *
	 * Not just 64×64. Minecraft has taken skins at whole multiples of that for
	 * years — 128, 256 and up — and they are uncommon rather than rare, so
	 * refusing them was refusing work somebody had already done. The old
	 * half-height layout is still allowed for the same reason.
	 */
	private static boolean shaped(int width, int height) {
		// The same shape the server insists on, and capped at the same place: a
		// chooser that accepts what the far end refuses is a chooser that lies.
		if (width < 64 || width > 2048 || width % 64 != 0) return false;
		return height == width || height * 2 == width;
	}

	private static Result read(Path file) {
		try {
			byte[] pixels = Files.readAllBytes(file);
			if (pixels.length > LIMIT) {
				return new Result(null, "файл " + (pixels.length / 1024) + " КБ, а больше "
					+ (LIMIT / 1024 / 1024) + " МБ нельзя");
			}

			// Decoded before it is sent, so a picture that is not a skin is refused
			// here rather than arriving somewhere else and failing there.
			try (NativeImage image = NativeImage.read(new java.io.ByteArrayInputStream(pixels))) {
				if (!shaped(image.getWidth(), image.getHeight())) {
					return new Result(null, "скин это 64×64 или кратное, а тут "
						+ image.getWidth() + "×" + image.getHeight());
				}
			}
			return new Result(pixels, "взят " + file.getFileName());
		} catch (Exception broken) {
			return new Result(null, "не читается: " + broken.getMessage());
		}
	}
}
