package com.mopicmp.npcstudio.net;

import java.util.List;

import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Editing an NPC itself, as opposed to the words it says.
 *
 * Separate from the dialogue editor because the two are separate things. A
 * dialogue is a document that many NPCs can share; how one of them stands,
 * walks and what it holds belongs to that one character, and travels with its
 * entity id rather than with a name.
 */
public final class NpcPayloads {

	private NpcPayloads() { }

	/** Client asks to edit the NPC it is looking at. */
	public record Open(int entityId) implements CustomPacketPayload {
		public static final Type<Open> TYPE = new Type<>(NpcStudio.id("npc_open"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Open> CODEC =
			ByteBufCodecs.VAR_INT.map(Open::new, Open::entityId);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * Everything about one NPC that the screen can change.
	 *
	 * The four animations travel as a list in the order of {@code Motion} rather
	 * than as four named fields. There is one place that has to know that order —
	 * the entity — and a packet layout that restates it is a second place to keep
	 * in step. What the NPC is holding travels the same way: main hand, then off
	 * hand, as item identifiers.
	 */
	public record Details(int entityId, String skin, String dialogue, List<String> animations,
			List<String> held, float scale) implements CustomPacketPayload {

		public static final Type<Details> TYPE = new Type<>(NpcStudio.id("npc_details"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Details> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Details::entityId,
				ByteBufCodecs.STRING_UTF8, Details::skin,
				ByteBufCodecs.STRING_UTF8, Details::dialogue,
				ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(8)), Details::animations,
				ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(2)), Details::held,
				ByteBufCodecs.FLOAT, Details::scale,
				Details::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * A skin picture, travelling whole.
	 *
	 * The file itself rather than a path to it, because a path only means
	 * something on the machine it came from and everybody else has to see the
	 * character as well. A skin is a few kilobytes, so this is cheaper than it
	 * sounds — and bounded anyway, so a client cannot post a film.
	 */
	public record SkinUpload(int entityId, byte[] pixels) implements CustomPacketPayload {
		public static final Type<SkinUpload> TYPE = new Type<>(NpcStudio.id("npc_skin_upload"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, SkinUpload> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.VAR_INT, SkinUpload::entityId,
				ByteBufCodecs.byteArray(384 * 1024), SkinUpload::pixels,
				SkinUpload::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Server passing a skin on to everyone who can see the character. */
	public record SkinFor(int entityId, byte[] pixels) implements CustomPacketPayload {
		public static final Type<SkinFor> TYPE = new Type<>(NpcStudio.id("npc_skin_for"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, SkinFor> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.VAR_INT, SkinFor::entityId,
				ByteBufCodecs.byteArray(384 * 1024), SkinFor::pixels,
				SkinFor::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Client asking for a skin it has been told about but has not got. */
	public record SkinPlease(int entityId) implements CustomPacketPayload {
		public static final Type<SkinPlease> TYPE = new Type<>(NpcStudio.id("npc_skin_please"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, SkinPlease> CODEC =
			ByteBufCodecs.VAR_INT.map(SkinPlease::new, SkinPlease::entityId);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/*
	 * There were two more packets here — the list of looks a character owned, and
	 * the orders to add, remove and wear one. They went when the wardrobe became
	 * the world's rather than each character's: there was no longer a screen that
	 * sent them, and a serverbound handler nobody sends to is an entry point that
	 * nobody re-reads either. A character now holds what it is wearing, and the
	 * shelf holds the rest.
	 */

	/**
	 * Changing how a character is built.
	 *
	 * Its own packet rather than more fields on the general edit, because a build
	 * is dragged rather than typed: a slider sends this many times a second while
	 * somebody is looking at the result, and resending a character's animations
	 * and held items with every pixel of drag would be absurd.
	 *
	 * @param verb what to do besides taking the numbers: keep them on the costume,
	 *             or throw them away and take the costume's instead
	 */
	public record Shape(int entityId, Verb verb, long sizes, long posture)
			implements CustomPacketPayload {

		public enum Verb { SET, KEEP_ON_COSTUME, TAKE_FROM_COSTUME }

		private static Verb verbOf(int id) {
			return id >= 0 && id < Verb.values().length ? Verb.values()[id] : Verb.SET;
		}

		public static final Type<Shape> TYPE = new Type<>(NpcStudio.id("npc_shape"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Shape> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Shape::entityId,
				ByteBufCodecs.idMapper(Shape::verbOf, Verb::ordinal), Shape::verb,
				ByteBufCodecs.VAR_LONG, Shape::sizes,
				ByteBufCodecs.VAR_LONG, Shape::posture,
				Shape::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Client sends the edited NPC back. Same shape, on purpose. */
	public record Apply(int entityId, String skin, String dialogue, List<String> animations,
			List<String> held, float scale) implements CustomPacketPayload {

		public static final Type<Apply> TYPE = new Type<>(NpcStudio.id("npc_apply"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Apply> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Apply::entityId,
				ByteBufCodecs.STRING_UTF8, Apply::skin,
				ByteBufCodecs.STRING_UTF8, Apply::dialogue,
				ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(8)), Apply::animations,
				ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(2)), Apply::held,
				ByteBufCodecs.FLOAT, Apply::scale,
				Apply::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}
}
