package com.mopicmp.npcstudio.map;

import com.mojang.serialization.Codec;
import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The map's start settings, kept in the world save.
 *
 * Same bargain as the world's dialogues: what somebody builds with the in-game
 * panel belongs to the world they built it in, and travels with it when the map
 * is handed to somebody else as a folder. That is the whole distribution story
 * for this map — no server, no download, no pack to host — so the world save is
 * not merely a convenient place to put this, it is the only place that arrives
 * with the map.
 */
public class WorldStart extends SavedData {

	public static final Codec<WorldStart> CODEC =
		MapStart.CODEC.xmap(WorldStart::new, data -> data.start);

	private static final SavedDataType<WorldStart> TYPE = new SavedDataType<>(
		NpcStudio.id("map_start"), WorldStart::new, CODEC, DataFixTypes.LEVEL);

	private MapStart start = MapStart.NOTHING;

	public WorldStart() { }

	private WorldStart(MapStart loaded) {
		this.start = loaded;
	}

	/**
	 * Held on the overworld whichever level asks.
	 *
	 * A map's start is one thing for the whole map. Per-dimension start settings
	 * would mean the nether could disagree with the overworld about how bright the
	 * map is, which is not a feature anybody asked for and is a bug the first time
	 * it happens by accident.
	 */
	public static WorldStart of(ServerLevel level) {
		return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	public MapStart start() {
		return start;
	}

	public void set(MapStart next) {
		this.start = next;
		setDirty();
	}
}
