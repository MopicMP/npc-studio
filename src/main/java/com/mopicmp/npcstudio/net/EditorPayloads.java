package com.mopicmp.npcstudio.net;

import java.util.List;

import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Talking to the dialogue editor.
 *
 * A dialogue travels as its own JSON rather than as a bespoke packet layout.
 * The codec already exists, it is already tested both ways, and a second
 * description of the same thing would be a second thing to keep in step — the
 * kind of duplication that stays correct right up until someone adds a field.
 */
public final class EditorPayloads {

	private EditorPayloads() { }

	/** Client asks for the list of dialogues to pick from. */
	public record Browse() implements CustomPacketPayload {
		public static final Type<Browse> TYPE = new Type<>(NpcStudio.id("editor_browse"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Browse> CODEC =
			StreamCodec.unit(new Browse());

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Server answers with the names on offer. */
	public record Listing(List<String> names) implements CustomPacketPayload {
		public static final Type<Listing> TYPE = new Type<>(NpcStudio.id("editor_listing"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Listing> CODEC =
			ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(512))
				.map(Listing::new, Listing::names);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Client asks to edit something, by name. Empty name means "start a new one". */
	public record Open(String id) implements CustomPacketPayload {
		public static final Type<Open> TYPE = new Type<>(NpcStudio.id("editor_open"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Open> CODEC =
			ByteBufCodecs.STRING_UTF8.map(Open::new, Open::id);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * Server sends the dialogue over, with the names of the others alongside.
	 *
	 * The list of names comes too because the editor needs it for the "go to"
	 * fields: a transition has to point at a node, and offering a free-text box
	 * for something that must match exactly is a way of manufacturing typos.
	 */
	public record Editing(String json, List<String> names) implements CustomPacketPayload {
		public static final Type<Editing> TYPE = new Type<>(NpcStudio.id("editor_editing"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Editing> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, Editing::json,
				ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(512)), Editing::names,
				Editing::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Client sends the edited dialogue back. */
	public record Save(String json) implements CustomPacketPayload {
		public static final Type<Save> TYPE = new Type<>(NpcStudio.id("editor_save"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Save> CODEC =
			ByteBufCodecs.STRING_UTF8.map(Save::new, Save::json);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * How the save went.
	 *
	 * Failure carries the validator's own words. "Could not save" would leave
	 * someone guessing at a graph they have been staring at for an hour; "menu
	 * leads to repair, which does not exist" tells them where to click.
	 */
	public record Saved(boolean ok, String message) implements CustomPacketPayload {
		public static final Type<Saved> TYPE = new Type<>(NpcStudio.id("editor_saved"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Saved> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.BOOL, Saved::ok,
				ByteBufCodecs.STRING_UTF8, Saved::message,
				Saved::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}
}
