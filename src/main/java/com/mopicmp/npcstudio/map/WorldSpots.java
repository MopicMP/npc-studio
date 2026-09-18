package com.mopicmp.npcstudio.map;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The map's named places, kept in the world save.
 *
 * Same bargain as the start settings and the world's dialogues: what somebody
 * places with the mod belongs to the world they placed it in and travels with it
 * when the map is handed over as a folder.
 *
 * <h2>Why these are per dimension rather than one list for the map</h2>
 *
 * The opposite of the choice made for the start, and for the opposite reason. A
 * map has one start — a second one in the nether would be two answers to a
 * question with one — whereas a place is somewhere you can stand, and the nether
 * has its own somewheres. Two doorways in two dimensions may both reasonably be
 * called "the gate", and a single list would make one of them silently replace
 * the other.
 *
 * It also matches how a mark is read: a character asking for {@code at:gate} is
 * asking in the world she is standing in, which is the only world she can walk to
 * anything in.
 */
public class WorldSpots extends SavedData {

	public static final Codec<WorldSpots> CODEC =
		Codec.unboundedMap(Codec.STRING, BlockPos.CODEC).xmap(WorldSpots::new, data -> data.spots);

	private static final SavedDataType<WorldSpots> TYPE = new SavedDataType<>(
		NpcStudio.id("map_spots"), WorldSpots::new, CODEC, DataFixTypes.LEVEL);

	/**
	 * As many places as one map may name.
	 *
	 * A ceiling because this is written by a packet and an unbounded list arriving
	 * from a client is a file that grows until the save does not open, however
	 * trusted the client. The number is what the list is read through rather than a
	 * guess at what an author needs: these are chosen from a dropdown in the graph
	 * editor, and a dropdown of a thousand names is not a way of choosing anything.
	 */
	public static final int MOST = 256;

	private final Map<String, BlockPos> spots = new LinkedHashMap<>();

	public WorldSpots() { }

	private WorldSpots(Map<String, BlockPos> loaded) {
		this.spots.putAll(loaded);
	}

	public static WorldSpots of(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(TYPE);
	}

	/** Where a named place is, or null when this world has no such name. */
	public BlockPos at(String name) {
		return name == null ? null : spots.get(name);
	}

	public List<Spot> all() {
		List<Spot> found = new ArrayList<>();
		for (Map.Entry<String, BlockPos> one : spots.entrySet()) {
			found.add(new Spot(one.getKey(), one.getValue()));
		}
		return found;
	}

	public int count() {
		return spots.size();
	}

	/**
	 * Puts a place down, or moves one that is already named.
	 *
	 * Moving rather than refusing, because "the gate" being in the wrong spot is
	 * corrected by pointing at the right one, and a graph that says {@code at:gate}
	 * should follow the correction without being edited. That is the whole reason
	 * these are names.
	 */
	public boolean put(String name, BlockPos at) {
		if (name == null || at == null) return false;
		if (!spots.containsKey(name) && spots.size() >= MOST) return false;
		spots.put(name, at);
		setDirty();
		return true;
	}

	public boolean drop(String name) {
		if (spots.remove(name) == null) return false;
		setDirty();
		return true;
	}
}
