package com.mopicmp.npcstudio.net;

import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Scenes, on the wire.
 *
 * The same shape as the models: the document is the world's, the client edits a
 * copy, and the whole thing goes back as text. Text rather than a field-by-field
 * codec because the format is going to keep changing while it is being built,
 * and a codec is a second description of the same document that has to be edited
 * in step with the first — which is exactly the kind of pair that drifts.
 */
public final class ScenePayloads {

	private ScenePayloads() { }

	/**
	 * The largest scene there is, in bytes of its file.
	 *
	 * Ours, and generous: four megabytes of this text is hundreds of thousands of
	 * keys, which is more than a person will author and more than a recording of
	 * any sane length produces. It exists so that a client cannot ask a server to
	 * hold something unbounded, not to tell anybody how long a scene may be.
	 */
	public static final int MOST = 4 * 1024 * 1024;

	/**
	 * How much of a scene goes in one piece, on its way to the server.
	 *
	 * Well under the 32767 vanilla allows a serverbound payload, because the name
	 * and the transfer's own name ride along with every piece and a payload one
	 * byte over does not fail politely — it disconnects the player, which is a
	 * spectacular way to lose an afternoon's work.
	 *
	 * The same number the wardrobe settled on for skins, and reused rather than
	 * chosen afresh: it is a fact about the wire, not about what is being sent.
	 */
	public static final int PART = 24 * 1024;

	/** Client asks for everything this world has. */
	public record Please() implements CustomPacketPayload {
		public static final Type<Please> TYPE = new Type<>(NpcStudio.id("scene_please"));

		public static final StreamCodec<io.netty.buffer.ByteBuf, Please> CODEC =
			StreamCodec.unit(new Please());

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * A piece of a scene on its way to the server.
	 *
	 * Every scene goes this way, not only the large ones. One path is one path to
	 * get right — the wardrobe's lesson, learned when skins had two — and a small
	 * scene is a transfer of a single piece.
	 *
	 * Bytes rather than text, and that is not a detail. A codec that caps a string
	 * counts characters while the wire counts bytes, and this document is full of
	 * names somebody typed in Russian: at two bytes a letter, a piece sized to be
	 * safe in characters is twice the size that disconnects them.
	 */
	public record Part(String upload, int index, int count, String name, byte[] part)
			implements CustomPacketPayload {

		public static final Type<Part> TYPE = new Type<>(NpcStudio.id("scene_part"));

		public static final StreamCodec<io.netty.buffer.ByteBuf, Part> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, Part::upload,
				ByteBufCodecs.VAR_INT, Part::index,
				ByteBufCodecs.VAR_INT, Part::count,
				ByteBufCodecs.stringUtf8(64), Part::name,
				ByteBufCodecs.byteArray(PART), Part::part,
				Part::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * One whole scene, on its way out to the clients.
	 *
	 * Only that way now. The ceiling in this direction is far higher, and the
	 * server is the one place that already knows the document is sound.
	 */
	public record Document(String name, String json) implements CustomPacketPayload {
		public static final Type<Document> TYPE = new Type<>(NpcStudio.id("scene_document"));

		public static final StreamCodec<io.netty.buffer.ByteBuf, Document> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.stringUtf8(64), Document::name,
				ByteBufCodecs.stringUtf8(MOST), Document::json,
				Document::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** A scene thrown away, in either direction. */
	public record Gone(String name) implements CustomPacketPayload {
		public static final Type<Gone> TYPE = new Type<>(NpcStudio.id("scene_gone"));

		public static final StreamCodec<io.netty.buffer.ByteBuf, Gone> CODEC =
			StreamCodec.composite(ByteBufCodecs.stringUtf8(64), Gone::name, Gone::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}
}
