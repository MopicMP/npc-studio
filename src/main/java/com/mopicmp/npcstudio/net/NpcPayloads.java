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

	/**
	 * Client asks for a model to be put where it stands.
	 *
	 * A packet rather than the client doing it, because only the server may add
	 * an entity to a world. The name is not checked against anything: the server
	 * has no models — a model is a document on the client that drew it — so
	 * refusing names the server cannot verify would mean refusing all of them.
	 */
	public record PlaceModel(String name) implements CustomPacketPayload {
		public static final Type<PlaceModel> TYPE = new Type<>(NpcStudio.id("place_model"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, PlaceModel> CODEC =
			ByteBufCodecs.STRING_UTF8.map(PlaceModel::new, PlaceModel::name);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * Client asks for a placed object to be taken back out of the world.
	 *
	 * By entity id, because that is what a click in the world produces and it is
	 * the only name a placed object has: two copies of one model are two objects
	 * with the same model name, and deleting by name would take both.
	 */
	public record RemoveModel(int entityId) implements CustomPacketPayload {
		public static final Type<RemoveModel> TYPE = new Type<>(NpcStudio.id("remove_model"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, RemoveModel> CODEC =
			ByteBufCodecs.VAR_INT.map(RemoveModel::new, RemoveModel::entityId);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * Client tells the server how big a placed object is, and whether it is solid.
	 *
	 * The server has never seen a box: geometry is a document on the client. So the
	 * one thing it cannot work out for itself — how much room the thing takes up —
	 * has to be said, and this is the saying of it.
	 */
	/**
	 * A model itself, travelling.
	 *
	 * <h2>The document, not a summary of it</h2>
	 *
	 * What used to go this way was a list of collision boxes: the client measured
	 * the object and told the server the numbers. That is a summary, and a summary
	 * has to be kept in step with the thing it summarises — which it was not, twice,
	 * and each time it showed up as a wall in mid-air or a player pushed back where
	 * they came from.
	 *
	 * The document is not a summary. Both sides read the same one and work out the
	 * same shape with the same code, so there is nothing left to fall out of step.
	 * It also happens to be what makes an object visible to somebody who did not
	 * draw it, which was going to be its own piece of work.
	 *
	 * <h2>As JSON</h2>
	 *
	 * The same text the {@code .bbmodel} file holds, so what travels, what is on the
	 * client's disk and what ends up in the world folder are one thing. A tighter
	 * encoding would save a few kilobytes on a packet that goes once per edit and
	 * would be a second format to keep correct.
	 */
	public record ModelDocument(String name, String json) implements CustomPacketPayload {
		public static final Type<ModelDocument> TYPE = new Type<>(NpcStudio.id("model_document"));

		/** Room for a model far larger than anybody has drawn by hand. */
		public static final int MOST = 4 * 1024 * 1024;

		public static final StreamCodec<io.netty.buffer.ByteBuf, ModelDocument> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.stringUtf8(256), ModelDocument::name,
				ByteBufCodecs.stringUtf8(MOST), ModelDocument::json,
				ModelDocument::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * Whether a placed object is something to walk into.
	 *
	 * Its own packet, because it belongs to the object standing there rather than
	 * to the model: two copies of one ship can reasonably be a deck and a backdrop.
	 */
	public record SolidModel(int entityId, boolean solid) implements CustomPacketPayload {
		public static final Type<SolidModel> TYPE = new Type<>(NpcStudio.id("solid_model"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, SolidModel> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.VAR_INT, SolidModel::entityId,
				ByteBufCodecs.BOOL, SolidModel::solid,
				SolidModel::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * Where a character stands, and which way it faces.
	 *
	 * <h2>Why this is not a scene channel</h2>
	 *
	 * Because a scene animates and this places. Putting a character on the right
	 * step of a gangway is something you do once, before there is any animation to
	 * speak of, and having the only way to do it be "open a scene, put the cursor
	 * somewhere and lay down a key" is the difference between a tool and a puzzle.
	 * The two live side by side: this moves the character, and the key button in
	 * the pose panel writes wherever it has ended up into the scene when somebody
	 * actually wants it animated.
	 */
	public record Place(int entityId, double x, double y, double z, float yaw)
			implements CustomPacketPayload {
		public static final Type<Place> TYPE = new Type<>(NpcStudio.id("npc_place"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Place> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Place::entityId,
				ByteBufCodecs.DOUBLE, Place::x,
				ByteBufCodecs.DOUBLE, Place::y,
				ByteBufCodecs.DOUBLE, Place::z,
				ByteBufCodecs.FLOAT, Place::yaw,
				Place::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

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
			List<String> held, float scale, String brain, boolean watchful, boolean endless)
			implements CustomPacketPayload {

		public static final Type<Details> TYPE = new Type<>(NpcStudio.id("npc_details"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Details> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Details::entityId,
				ByteBufCodecs.STRING_UTF8, Details::skin,
				ByteBufCodecs.STRING_UTF8, Details::dialogue,
				ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(8)), Details::animations,
				ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(2)), Details::held,
				ByteBufCodecs.FLOAT, Details::scale,
				// What she does when nobody is talking to her. These three arrived as
				// commands, which was a mistake: they are settings of one character,
				// the same as which skin she wears, and a command is not where a
				// character's settings live.
				ByteBufCodecs.STRING_UTF8, Details::brain,
				ByteBufCodecs.BOOL, Details::watchful,
				ByteBufCodecs.BOOL, Details::endless,
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
			List<String> held, float scale, String brain, boolean watchful, boolean endless)
			implements CustomPacketPayload {

		public static final Type<Apply> TYPE = new Type<>(NpcStudio.id("npc_apply"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Apply> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Apply::entityId,
				ByteBufCodecs.STRING_UTF8, Apply::skin,
				ByteBufCodecs.STRING_UTF8, Apply::dialogue,
				ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(8)), Apply::animations,
				ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(2)), Apply::held,
				ByteBufCodecs.FLOAT, Apply::scale,
				ByteBufCodecs.STRING_UTF8, Apply::brain,
				ByteBufCodecs.BOOL, Apply::watchful,
				ByteBufCodecs.BOOL, Apply::endless,
				Apply::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}
}
