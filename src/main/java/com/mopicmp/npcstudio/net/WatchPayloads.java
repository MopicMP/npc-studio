package com.mopicmp.npcstudio.net;

import java.util.List;

import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Watching a conversation decide things, for somebody who cannot work out why it went
 * the way it did.
 *
 * <h2>Why the account is made on the server</h2>
 *
 * Because that is where the deciding happens, and an account made anywhere else is a
 * second evaluation that can disagree with the first. A debugging tool that lies is
 * worse than none: it sends somebody looking in the wrong place, with confidence.
 *
 * <h2>Why it is off by default and per player</h2>
 *
 * Because it is a stream of text about every step of every conversation, and on a
 * server with people playing it would be traffic and noise for everybody so that one
 * person could read one branch. Asked for by one player, sent to that player, and the
 * engine is handed a watcher that does nothing at all for everybody else.
 */
public final class WatchPayloads {

	private WatchPayloads() { }

	/** As many lines as one step may report before it is cut short. */
	public static final int MOST_LINES = 64;

	/** Client asks to watch, or to stop. A switch rather than two packets: it is one fact. */
	public record Watch(boolean on) implements CustomPacketPayload {
		public static final Type<Watch> TYPE = new Type<>(NpcStudio.id("watch"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Watch> CODEC =
			StreamCodec.composite(ByteBufCodecs.BOOL, Watch::on, Watch::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * What one step decided.
	 *
	 * A whole step at a time rather than a line at a time, because the lines only mean
	 * anything together — an arm that did not hold is worth reading beside the one that
	 * did, and beside the order they were asked in.
	 */
	public record Told(List<String> lines) implements CustomPacketPayload {
		public static final Type<Told> TYPE = new Type<>(NpcStudio.id("watch_told"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Told> CODEC =
			ByteBufCodecs.stringUtf8(512).apply(ByteBufCodecs.list(MOST_LINES))
				.map(Told::new, Told::lines);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}
}
