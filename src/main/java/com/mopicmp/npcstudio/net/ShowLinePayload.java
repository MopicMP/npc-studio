package com.mopicmp.npcstudio.net;

import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * One line of dialogue, on its way to the screen.
 *
 * The NPC travels as an entity id rather than as a name or a skin. The client
 * already has the entity — it is standing in front of the player — so sending
 * anything more would be shipping a copy of something already there, and it
 * would go stale the moment the NPC changed its skin mid-scene.
 *
 * @param npc     entity id of whoever is speaking, or -1 for a line with no speaker
 * @param speaker the name to show; may be empty, and may differ from the NPC's
 *                own name because a character can be "???" until they say who
 *                they are
 * @param text    the line itself
 * @param mode    how to show it, by {@link com.mopicmp.npcstudio.dialogue.Presentation} ordinal
 */
public record ShowLinePayload(int npc, String speaker, String text, int mode)
		implements CustomPacketPayload {

	public static final Type<ShowLinePayload> TYPE = new Type<>(NpcStudio.id("show_line"));

	public static final StreamCodec<io.netty.buffer.ByteBuf, ShowLinePayload> CODEC =
		StreamCodec.composite(
			ByteBufCodecs.VAR_INT, ShowLinePayload::npc,
			ByteBufCodecs.STRING_UTF8, ShowLinePayload::speaker,
			ByteBufCodecs.STRING_UTF8, ShowLinePayload::text,
			ByteBufCodecs.VAR_INT, ShowLinePayload::mode,
			ShowLinePayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
