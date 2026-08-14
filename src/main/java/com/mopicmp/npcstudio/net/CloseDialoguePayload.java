package com.mopicmp.npcstudio.net;

import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The conversation is over; take the bar away.
 *
 * Sent explicitly rather than letting the bar time out. A line that lingers
 * after the NPC has finished reads as the game having forgotten about it, and a
 * timeout long enough to read a sentence is long enough to be wrong.
 */
public record CloseDialoguePayload() implements CustomPacketPayload {

	public static final Type<CloseDialoguePayload> TYPE = new Type<>(NpcStudio.id("close_dialogue"));

	public static final StreamCodec<io.netty.buffer.ByteBuf, CloseDialoguePayload> CODEC =
		StreamCodec.unit(new CloseDialoguePayload());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
