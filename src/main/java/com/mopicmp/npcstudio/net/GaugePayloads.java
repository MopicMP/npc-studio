package com.mopicmp.npcstudio.net;

import java.util.List;

import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * What the player is shown of what they are carrying.
 *
 * <h2>Why the number is worked out on the server</h2>
 *
 * Because the client has no idea what a document is, and there is no reason to teach it.
 * Variables, scopes, conditions and kinds are one language on one side; this side is
 * handed a label, a number and a corner, and draws that.
 *
 * Which also means a gauge cannot disagree with the graph. A client that computed the
 * value itself would need the variables, the conditions and the same reading of both —
 * three things to keep in step, and the day they drift the bar says one thing while the
 * branch does another.
 */
public final class GaugePayloads {

	private GaugePayloads() { }

	/** As many as one screen can hold before it stops being a screen. */
	public static final int MOST = 16;

	/**
	 * One gauge, already decided: everything needed to draw it and nothing else.
	 *
	 * @param full 0 for a number, which has no bar to fill
	 */
	public record Shown(String label, double value, double most, String look,
			String corner, int colour, float full) {

		public static final StreamCodec<io.netty.buffer.ByteBuf, Shown> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.stringUtf8(64), Shown::label,
				ByteBufCodecs.DOUBLE, Shown::value,
				ByteBufCodecs.DOUBLE, Shown::most,
				ByteBufCodecs.stringUtf8(16), Shown::look,
				ByteBufCodecs.stringUtf8(16), Shown::corner,
				ByteBufCodecs.INT, Shown::colour,
				ByteBufCodecs.FLOAT, Shown::full,
				Shown::new);
	}

	/**
	 * Everything on screen right now.
	 *
	 * The whole list rather than what changed, for the reason the portrait and the
	 * abilities are sent whole: a client assembling a list out of differences can
	 * disagree with the server about what it is holding, and it disagrees silently.
	 */
	public record Showing(List<Shown> gauges) implements CustomPacketPayload {
		public static final Type<Showing> TYPE = new Type<>(NpcStudio.id("gauges"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Showing> CODEC =
			Shown.CODEC.apply(ByteBufCodecs.list(MOST)).map(Showing::new, Showing::gauges);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}
}
