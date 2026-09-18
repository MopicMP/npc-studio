package com.mopicmp.npcstudio.net;

import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.map.MapStart;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/** The start of a map — where a player appears and what they see — on the wire. */
public final class MapPayloads {

	private MapPayloads() { }

	/**
	 * Client asks for both: what the map wants set, and where the spawn is.
	 *
	 * One payload rather than two because they are always wanted together — a
	 * player arriving needs the settings and the panel opening needs the point,
	 * and a round trip saved is a frame of the panel that is not empty.
	 */
	public record Please() implements CustomPacketPayload {
		public static final Type<Please> TYPE = new Type<>(NpcStudio.id("map_please"));

		public static final StreamCodec<io.netty.buffer.ByteBuf, Please> CODEC =
			StreamCodec.unit(new Please());

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * The whole of the map's start, in either direction.
	 *
	 * Out to every player who joins, and back from the one editing it. The same
	 * record both ways because it is small and complete: there is no partial edit
	 * of this worth sending, and a whole document cannot half-apply.
	 */
	public record Start(MapStart start) implements CustomPacketPayload {
		public static final Type<Start> TYPE = new Type<>(NpcStudio.id("map_start"));

		public static final StreamCodec<io.netty.buffer.ByteBuf, Start> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.fromCodec(MapStart.CODEC), Start::start,
				Start::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Somebody putting the spawn where they are standing, or where they typed. */
	public record SetSpawn(BlockPos pos, float yaw, float pitch) implements CustomPacketPayload {
		public static final Type<SetSpawn> TYPE = new Type<>(NpcStudio.id("map_set_spawn"));

		public static final StreamCodec<io.netty.buffer.ByteBuf, SetSpawn> CODEC =
			StreamCodec.composite(
				BlockPos.STREAM_CODEC, SetSpawn::pos,
				ByteBufCodecs.FLOAT, SetSpawn::yaw,
				ByteBufCodecs.FLOAT, SetSpawn::pitch,
				SetSpawn::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * Where the spawn is, and — the part that is worth a packet — where a player
	 * would actually end up.
	 *
	 * {@code landing} does not come from our idea of the rules. It comes from
	 * {@code PlayerSpawnFinder.findSpawn}, which is the method the game calls to
	 * place a real player, so the answer is the game's rather than ours. The two
	 * numbers beside it are the reasons it might differ: the respawn radius, and
	 * whether the world is in adventure mode — where the search is skipped
	 * altogether and the point is honoured with only a height fixup.
	 */
	public record Spawn(BlockPos pos, float yaw, float pitch, Vec3 landing,
			int radius, boolean adventure) implements CustomPacketPayload {

		public static final Type<Spawn> TYPE = new Type<>(NpcStudio.id("map_spawn"));

		public static final StreamCodec<io.netty.buffer.ByteBuf, Spawn> CODEC =
			StreamCodec.composite(
				BlockPos.STREAM_CODEC, Spawn::pos,
				ByteBufCodecs.FLOAT, Spawn::yaw,
				ByteBufCodecs.FLOAT, Spawn::pitch,
				Vec3.STREAM_CODEC, Spawn::landing,
				ByteBufCodecs.VAR_INT, Spawn::radius,
				ByteBufCodecs.BOOL, Spawn::adventure,
				Spawn::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}
}
