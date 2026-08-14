package com.mopicmp.npcstudio.net;

import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * "Go on" — a line moved past without clicking the NPC.
 *
 * Every other way of advancing goes through interacting with the entity, which
 * stops working the moment a cutscene takes the camera away: the NPC may not be
 * in front of the player any more, and the player cannot click anything at all.
 *
 * It names the NPC rather than relying on the server remembering who was last
 * spoken to, for the same reason answers do: a packet can arrive late, and one
 * that advanced the wrong conversation would be very hard to see.
 */
public record AdvancePayload(int npc) implements CustomPacketPayload {

	public static final Type<AdvancePayload> TYPE = new Type<>(NpcStudio.id("advance"));

	public static final StreamCodec<io.netty.buffer.ByteBuf, AdvancePayload> CODEC =
		ByteBufCodecs.VAR_INT.map(AdvancePayload::new, AdvancePayload::npc);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
