package com.mopicmp.npcstudio.map;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The ground somebody has taken a copy of, by the name they gave it.
 *
 * <h2>Why the name here is not the name of the file</h2>
 *
 * The blocks themselves are kept by the game, in its own structure format, under an
 * {@code Identifier} — and an identifier's path may hold only {@code [a-z0-9_.-]}. So
 * «поляна» cannot be a file name, and neither can anything else this project's author
 * would actually type.
 *
 * Refusing those names was the alternative, and it is the wrong one twice over: it makes
 * the tool rude in the language it is used in, and it would have been discovered on the
 * first real capture rather than here. So a region has two names — the one a person
 * reads and the one the disk gets — and this is the only place that knows both.
 *
 * <h2>Why the game's format and not our own</h2>
 *
 * Because it exists, it is the one every other tool understands, and a file written here
 * can be opened by a vanilla structure block. Writing a second block format would have
 * bought nothing but a second block format to maintain.
 *
 * What the game's format does not hold is which documents belong to this ground, and
 * that is what this store is for.
 *
 * <h2>Why per dimension, like the named places</h2>
 *
 * The same reason and the same sentence: a copy of the ground is a fact about the world
 * it was taken in. It also means a region captured in the tutorial dimension is not
 * offered on the overworld's list, which is what somebody looking at that list expects.
 */
public class WorldRegions extends SavedData {

	/**
	 * One copy of the ground: where its blocks are kept, and how big it is.
	 *
	 * The size is held here rather than read back out of the file because it is asked
	 * for to draw a list and to check whether a thing will fit, and loading a megabyte
	 * of blocks to answer "how wide is it" is a poor trade.
	 */
	public record Region(String file, Vec3i size, List<String> documents) {

		public static final Codec<Region> CODEC = RecordCodecBuilder.create(instance ->
			instance.group(
				Codec.STRING.fieldOf("file").forGetter(Region::file),
				Vec3i.CODEC.fieldOf("size").forGetter(Region::size),
				// The documents that travel with this ground. Empty until capture learns
				// to take them, which is the step after this one — written into the
				// format now because a field added to a file later is the expensive kind
				// of change, and an empty list is the cheap kind.
				Codec.STRING.listOf().optionalFieldOf("documents", List.of())
					.forGetter(Region::documents)
			).apply(instance, Region::new));

		public Region {
			file = file == null ? "" : file;
			size = size == null ? Vec3i.ZERO : size;
			documents = List.copyOf(documents == null ? List.of() : documents);
		}

		/** How many blocks it comes to, as a long because a big one overflows an int. */
		public long blocks() {
			return (long) size.getX() * size.getY() * size.getZ();
		}
	}

	public static final Codec<WorldRegions> CODEC =
		Codec.unboundedMap(Codec.STRING, Region.CODEC)
			.xmap(WorldRegions::new, data -> data.regions);

	private static final SavedDataType<WorldRegions> TYPE = new SavedDataType<>(
		NpcStudio.id("map_regions"), WorldRegions::new, CODEC, DataFixTypes.LEVEL);

	/**
	 * As many regions as one world may hold copies of.
	 *
	 * The same number the named places get, and for the same reason: these are read
	 * through a list somebody picks from, and a list of a thousand is not a way of
	 * choosing anything. The author's own estimate for the tutorial is "dozens".
	 */
	public static final int MOST = 256;

	/** As long as a region may be called, in letters a person reads. */
	public static final int LONGEST = 24;

	private final Map<String, Region> regions = new LinkedHashMap<>();

	public WorldRegions() { }

	private WorldRegions(Map<String, Region> loaded) {
		this.regions.putAll(loaded);
	}

	public static WorldRegions of(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(TYPE);
	}

	/** What is kept under a name, or null when this world has no copy by that name. */
	public Region at(String name) {
		return name == null ? null : regions.get(name);
	}

	public List<String> names() {
		return List.copyOf(regions.keySet());
	}

	public List<Map.Entry<String, Region>> all() {
		return new ArrayList<>(regions.entrySet());
	}

	public int count() {
		return regions.size();
	}

	/**
	 * A file name nothing else here has taken.
	 *
	 * Counted rather than derived from the person's name, because deriving it would
	 * mean deciding what «поляна» becomes in letters a path allows — and two different
	 * names can easily become the same one, which is one region silently overwriting
	 * another.
	 */
	public String freshFile() {
		for (int n = 1; ; n++) {
			String tried = "region" + n;
			boolean taken = false;
			for (Region region : regions.values()) {
				if (region.file().equals(tried)) {
					taken = true;
					break;
				}
			}
			if (!taken) return tried;
		}
	}

	/**
	 * Puts a copy down, or replaces one already under that name.
	 *
	 * Replacing rather than refusing, because taking the copy again is what somebody
	 * does after changing the build — and making them delete the old one first would be
	 * a step that exists only to be forgotten, leaving two copies where one was meant.
	 */
	public boolean put(String name, Region region) {
		if (name == null || region == null) return false;
		if (!regions.containsKey(name) && regions.size() >= MOST) return false;
		regions.put(name, region);
		setDirty();
		return true;
	}

	public Region drop(String name) {
		Region gone = regions.remove(name);
		if (gone != null) setDirty();
		return gone;
	}
}
