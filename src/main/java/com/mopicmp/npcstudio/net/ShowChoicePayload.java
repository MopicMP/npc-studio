package com.mopicmp.npcstudio.net;

import java.util.List;

import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * A question and the answers the player may give.
 *
 * These used to go to chat, which was scaffolding and behaved like it: the bar
 * covered them, they scrolled away, and they sat among unrelated messages. A
 * conversation belongs in the conversation's own space.
 *
 * Each option carries the number it had in the dialogue, not its position in
 * this list. Conditions hide options, and renumbering what survives would make
 * the player's answer land on a different branch than the one they read.
 */
public record ShowChoicePayload(int npc, String speaker,
		com.mopicmp.npcstudio.dialogue.text.Words prompt, int mode, List<Option> options,
		com.mopicmp.npcstudio.dialogue.Manner showing)
		implements CustomPacketPayload {

	/** The five-part form, for the callers that have no document to ask. */
	public ShowChoicePayload(int npc, String speaker,
			com.mopicmp.npcstudio.dialogue.text.Words prompt, int mode, List<Option> options) {
		this(npc, speaker, prompt, mode, options, com.mopicmp.npcstudio.dialogue.Manner.ORDINARY);
	}

	public record Option(int index, com.mopicmp.npcstudio.dialogue.text.Words label, String colour) {
		public static final StreamCodec<io.netty.buffer.ByteBuf, Option> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Option::index,
				WordsWire.WORDS, Option::label,
				ByteBufCodecs.STRING_UTF8, Option::colour,
				Option::new);
	}

	public static final Type<ShowChoicePayload> TYPE = new Type<>(NpcStudio.id("show_choice"));

	public static final StreamCodec<io.netty.buffer.ByteBuf, ShowChoicePayload> CODEC =
		StreamCodec.composite(
			ByteBufCodecs.VAR_INT, ShowChoicePayload::npc,
			ByteBufCodecs.STRING_UTF8, ShowChoicePayload::speaker,
			WordsWire.WORDS, ShowChoicePayload::prompt,
			ByteBufCodecs.VAR_INT, ShowChoicePayload::mode,
			Option.CODEC.apply(ByteBufCodecs.list(16)), ShowChoicePayload::options,
			MannerWire.MANNER, ShowChoicePayload::showing,
			ShowChoicePayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
