package com.mopicmp.npcstudio.net;

import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Carry on with whatever is being said to me.
 *
 * <h2>Why it names nothing</h2>
 *
 * Because there is nothing to name. Every other way of moving a conversation on
 * points at the character saying it, and this one exists precisely for the case
 * where there is no character: a scene a doorway began, spoken by the room. The
 * server knows what is on this player's screen — see {@code DialogueDisplay} — and
 * that is a better answer than anything a client could send, because it is the same
 * fact the line was drawn from.
 *
 * <h2>Why an empty packet is safe to accept from anybody</h2>
 *
 * It carries no target, no index and no name, so there is nothing in it to be
 * dishonest about. The worst a client can do by sending it constantly is advance
 * its own conversation, which is what pressing the button does anyway — and past
 * the last line there is nothing left to advance.
 */
public record SpeakOnPayload() implements CustomPacketPayload {

	public static final Type<SpeakOnPayload> TYPE = new Type<>(NpcStudio.id("speak_on"));

	public static final StreamCodec<io.netty.buffer.ByteBuf, SpeakOnPayload> CODEC =
		StreamCodec.unit(new SpeakOnPayload());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
