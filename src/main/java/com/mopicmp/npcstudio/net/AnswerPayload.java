package com.mopicmp.npcstudio.net;

import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The player's answer, on its way back to the server.
 *
 * The NPC travels with it rather than being remembered on the server as "who
 * this player was last talking to". A packet can arrive late — after the player
 * has walked off and started talking to someone else — and an answer that
 * landed on the wrong conversation would be a very hard bug to see.
 *
 * The number is the option's index in the dialogue, not its position on screen.
 * The server checks it is one the player was actually offered; a client that
 * sends anything else is refused rather than trusted.
 */
public record AnswerPayload(int npc, int option) implements CustomPacketPayload {

	public static final Type<AnswerPayload> TYPE = new Type<>(NpcStudio.id("answer"));

	public static final StreamCodec<io.netty.buffer.ByteBuf, AnswerPayload> CODEC =
		StreamCodec.composite(
			ByteBufCodecs.VAR_INT, AnswerPayload::npc,
			ByteBufCodecs.VAR_INT, AnswerPayload::option,
			AnswerPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
