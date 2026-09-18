package com.mopicmp.npcstudio.client.server;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;
import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.server.Backups;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;

/**
 * What a copy looks like from the outside: its world's picture and its version.
 *
 * Both are inside the archive — {@code <world>/icon.png} and
 * {@code <world>/level.dat} — and both are read from there rather than from the
 * world the copy was taken of. That is the whole point: the copies worth looking
 * at are the ones whose world has since been changed beyond recognition or
 * deleted, and a row that borrowed the living world's picture would show the
 * wrong thing exactly when it mattered.
 *
 * <p>The reading is not done here. Opening an archive is disk work and this is
 * asked from the drawing, sixty times a second — so {@link ServerManager} reads
 * the bytes on its worker and hands them over, and this only turns them into a
 * picture and a version, once each, and remembers.
 */
public final class BackupLook {

	/** What was found in one archive. Either may be missing. */
	public record Look(byte[] icon, String version) {
	}

	private static final Map<String, Identifier> pictures = new HashMap<>();
	private static int counter;

	private BackupLook() {
	}

	/**
	 * Read the two small files out of an archive. Worker thread only.
	 *
	 * Never throws: a copy that cannot be opened is a row with no picture and no
	 * version, which is a true thing to show and not a reason to lose the list.
	 */
	public static Look read(Backups.Backup backup) {
		byte[] icon = Backups.entry(backup, backup.world() + "/icon.png");
		return new Look(icon, versionIn(Backups.entry(backup, backup.world() + "/level.dat")));
	}

	private static String versionIn(byte[] level) {
		if (level == null) return "";
		try (var in = new ByteArrayInputStream(level)) {
			CompoundTag tag = NbtIo.readCompressed(in, NbtAccounter.create(MOST_LEVEL_DAT));
			return tag.getCompound("Data")
				.flatMap(data -> data.getCompound("Version"))
				.flatMap(version -> version.getString("Name"))
				.orElse("");
		} catch (Exception unreadable) {
			return "";
		}
	}

	private static final long MOST_LEVEL_DAT = 8L * 1024 * 1024;

	/**
	 * The picture for this copy, registered the first time it is asked for.
	 *
	 * Under the archive's file name, which is unique in the folder and changes
	 * when somebody renames it — so a renamed copy reads its picture again rather
	 * than showing whatever the name used to hold.
	 */
	public static Identifier picture(String file, byte[] png) {
		if (pictures.containsKey(file)) return pictures.get(file);
		if (png == null) {
			pictures.put(file, null);
			return null;
		}
		try {
			NativeImage image = NativeImage.read(new ByteArrayInputStream(png));
			Identifier where = NpcStudio.id("backup-icon/" + (counter++) + ".png");
			Minecraft.getInstance().getTextureManager()
				.register(where, new DynamicTexture(() -> "backup icon", image));
			pictures.put(file, where);
			return where;
		} catch (Exception broken) {
			NpcStudio.LOGGER.warn("Could not read the icon inside '{}': {}", file,
				broken.toString());
			pictures.put(file, null);
			return null;
		}
	}

	/** Look again next time — after the copy has been renamed or replaced. */
	public static void forget(String file) {
		pictures.remove(file);
	}
}
