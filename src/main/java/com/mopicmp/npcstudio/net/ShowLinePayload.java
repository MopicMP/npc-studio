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
 * @param text    the line itself, in stretches that may be drawn differently
 * @param mode    how to show it, by {@link com.mopicmp.npcstudio.dialogue.Presentation} ordinal
 * @param showing how wide, how tall, how fast — the document's own answers rather
 *                than the constants the bar used to be built from
 * @param face    whose head to draw, by {@link com.mopicmp.npcstudio.dialogue.Node.Line.Face}
 *                ordinal. An ordinal rather than the enum because that is what
 *                {@code mode} beside it already is, and one packet should not carry
 *                two conventions for the same kind of thing.
 * @param nameColour what colour to draw the name, already worked out. The document's
 *                answer and the line's override are both known on the server and
 *                neither is known on the client, so the argument is settled there and
 *                the answer travels — rather than shipping a table and the rule.
 */
public record ShowLinePayload(int npc, String speaker,
		com.mopicmp.npcstudio.dialogue.text.Words text, int mode,
		com.mopicmp.npcstudio.dialogue.Manner showing, int face, String nameColour)
		implements CustomPacketPayload {

	/** The four-part form, for the callers that have no document to ask. */
	public ShowLinePayload(int npc, String speaker,
			com.mopicmp.npcstudio.dialogue.text.Words text, int mode) {
		this(npc, speaker, text, mode, com.mopicmp.npcstudio.dialogue.Manner.ORDINARY);
	}

	/** The five-part form, from before a line could say whose face is beside it. */
	public ShowLinePayload(int npc, String speaker,
			com.mopicmp.npcstudio.dialogue.text.Words text, int mode,
			com.mopicmp.npcstudio.dialogue.Manner showing) {
		this(npc, speaker, text, mode, showing,
			com.mopicmp.npcstudio.dialogue.Node.Line.Face.SPEAKER.ordinal(), "");
	}

	public static final Type<ShowLinePayload> TYPE = new Type<>(NpcStudio.id("show_line"));

	public static final StreamCodec<io.netty.buffer.ByteBuf, ShowLinePayload> CODEC =
		StreamCodec.composite(
			ByteBufCodecs.VAR_INT, ShowLinePayload::npc,
			ByteBufCodecs.STRING_UTF8, ShowLinePayload::speaker,
			WordsWire.WORDS, ShowLinePayload::text,
			ByteBufCodecs.VAR_INT, ShowLinePayload::mode,
			MannerWire.MANNER, ShowLinePayload::showing,
			ByteBufCodecs.VAR_INT, ShowLinePayload::face,
			ByteBufCodecs.STRING_UTF8, ShowLinePayload::nameColour,
			ShowLinePayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
