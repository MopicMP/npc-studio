package com.mopicmp.npcstudio.net;

import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * I am stepping away from this conversation.
 *
 * <h2>Why pressing escape had to say anything at all</h2>
 *
 * It did not, and that was the whole of a bug report: escape closed the full-screen
 * card and the bar immediately picked the same question up, because the server still
 * had it on this player's screen and the bar draws whatever is there. Two dialogues,
 * one after the other, from one press — reported in exactly those words.
 *
 * Closing a screen is a client-side act and the server cannot see it happen. So the
 * client says so, the server takes the line off screen the ordinary way, and the same
 * closing packet comes back that would have come back on a timeout. One path, and
 * nothing left showing behind.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * End the conversation. The bookmark stays exactly where it was: stepping away from
 * somebody mid-sentence and coming back to them is a thing people do, and clicking
 * again picks the same moment back up. This is about a screen, not about a scene.
 */
public record PutDownPayload() implements CustomPacketPayload {

	public static final Type<PutDownPayload> TYPE = new Type<>(NpcStudio.id("put_down"));

	public static final StreamCodec<io.netty.buffer.ByteBuf, PutDownPayload> CODEC =
		StreamCodec.unit(new PutDownPayload());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
