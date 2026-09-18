package com.mopicmp.npcstudio.net;

import java.util.List;

import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.map.Spot;
import com.mopicmp.npcstudio.map.WorldSpots;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** The map's named places on the wire. */
public final class SpotPayloads {

	private SpotPayloads() { }

	/** Client asking what this world's places are, on arrival and after a change. */
	public record Please() implements CustomPacketPayload {
		public static final Type<Please> TYPE = new Type<>(NpcStudio.id("spots_please"));

		public static final StreamCodec<io.netty.buffer.ByteBuf, Please> CODEC =
			StreamCodec.unit(new Please());

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * Every place in the world the player is standing in.
	 *
	 * The whole list rather than what changed, because it is short by construction —
	 * see {@link WorldSpots#MOST} — and because the client draws all of them at once
	 * anyway. A protocol for adding one and removing one is two more things to get
	 * out of step for no saving worth measuring.
	 */
	public record Spots(List<Spot> spots) implements CustomPacketPayload {
		public static final Type<Spots> TYPE = new Type<>(NpcStudio.id("spots"));

		public static final StreamCodec<io.netty.buffer.ByteBuf, Spots> CODEC =
			StreamCodec.composite(
				Spot.STREAM_CODEC.apply(ByteBufCodecs.list(WorldSpots.MOST)), Spots::spots,
				Spots::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Somebody putting a place down, or moving one that already has this name. */
	public record Put(String name, BlockPos at) implements CustomPacketPayload {
		public static final Type<Put> TYPE = new Type<>(NpcStudio.id("spot_put"));

		public static final StreamCodec<io.netty.buffer.ByteBuf, Put> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.stringUtf8(Spot.LONGEST), Put::name,
				BlockPos.STREAM_CODEC, Put::at,
				Put::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Taking one away. Graphs naming it go on naming it and find nothing. */
	public record Drop(String name) implements CustomPacketPayload {
		public static final Type<Drop> TYPE = new Type<>(NpcStudio.id("spot_drop"));

		public static final StreamCodec<io.netty.buffer.ByteBuf, Drop> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.stringUtf8(Spot.LONGEST), Drop::name,
				Drop::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}
}
