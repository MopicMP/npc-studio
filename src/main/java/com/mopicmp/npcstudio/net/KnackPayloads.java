package com.mopicmp.npcstudio.net;

import java.util.List;

import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Which of this mod's own abilities a player has, and when one is used.
 *
 * <h2>Why the client has to be told at all</h2>
 *
 * Because a jump happens on the client. The key is pressed there, the movement is
 * predicted there, and the server only ever sees where somebody ended up — so an ability
 * that had to ask the server whether it was allowed would answer a fifth of a second
 * after the key, which for a jump is not late, it is broken.
 *
 * So the answer is sent ahead of the question: the client is told what it may do, and
 * tells the server when it did it.
 *
 * <h2>Why that is not a hole</h2>
 *
 * Nothing here is authority over anything. The client is told which abilities are on,
 * and that list is decided entirely on the server from the document's own conditions. A
 * client saying "I used the double jump" can, at worst, spend its own energy on a jump
 * it did not make — and the graph, which is where a cost lives, is on the server.
 *
 * The one thing this deliberately does not try to be is anti-cheat. Somebody editing
 * their own client can already move how they like in a way no packet of ours would
 * notice, and a check here would cost every honest player a delay to catch nobody.
 */
public final class KnackPayloads {

	private KnackPayloads() { }

	/** As many as one answer carries. Well past the handful this mod writes. */
	public static final int MOST = 32;

	/**
	 * The abilities this player has right now.
	 *
	 * The whole list rather than what changed, for the reason the portrait is sent
	 * whole: a client that assembles a list out of differences is a client that can
	 * disagree with the server about what it is holding, and it disagrees silently.
	 */
	public record Have(List<String> knacks) implements CustomPacketPayload {
		public static final Type<Have> TYPE = new Type<>(NpcStudio.id("knacks"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Have> CODEC =
			ByteBufCodecs.stringUtf8(64).apply(ByteBufCodecs.list(MOST))
				.map(Have::new, Have::knacks);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * The player just used one.
	 *
	 * Sent so that a graph can charge for it. Without this an ability could be switched
	 * on and off but never cost anything — and an ability with no cost is not an
	 * ability, it is a setting.
	 */
	public record Used(String knack) implements CustomPacketPayload {
		public static final Type<Used> TYPE = new Type<>(NpcStudio.id("knack_used"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Used> CODEC =
			ByteBufCodecs.stringUtf8(64).map(Used::new, Used::knack);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}
}
