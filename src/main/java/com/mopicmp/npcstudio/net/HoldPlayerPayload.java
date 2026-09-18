package com.mopicmp.npcstudio.net;

import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Stand still, or you may go.
 *
 * <h2>Why this is said out loud and not worked out from the line on screen</h2>
 *
 * Because it was worked out from the line on screen, and the report is what that
 * costs: the character said her piece and walked off, the line stayed up while she
 * walked, and the player stood rooted to the spot watching her go. Nothing was
 * broken — the rule was "held while a line is showing" and a line was showing.
 *
 * The trouble is that "a line is on screen" and "this is a moment the player should
 * be still for" are different facts that happen to coincide in the simplest scene.
 * A scene where somebody speaks and then leaves pulls them apart, and there was no
 * way to say so.
 *
 * So it is a thing the graph says and takes back, like a wall or a gaze — and like
 * both of those, what a conversation takes it gives back when it ends, however it
 * ends. See {@code DialogueRuntime.sweep}.
 *
 * @param held true to take the movement keys, false to hand them back
 */
public record HoldPlayerPayload(boolean held) implements CustomPacketPayload {

	public static final Type<HoldPlayerPayload> TYPE = new Type<>(NpcStudio.id("hold_player"));

	public static final StreamCodec<io.netty.buffer.ByteBuf, HoldPlayerPayload> CODEC =
		StreamCodec.composite(
			ByteBufCodecs.BOOL, HoldPlayerPayload::held,
			HoldPlayerPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
