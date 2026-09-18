package com.mopicmp.npcstudio.net;

import java.util.List;

import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.font.FontFiles;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Getting a typeface from the server to everybody reading a line written in it.
 *
 * <h2>Why this exists at all</h2>
 *
 * Because a style naming a font the reader has not got draws nothing recognisable
 * — and the author, who does have it, cannot see that. It is the shape of fault
 * this mod has spent the longest on: something that works perfectly for the one
 * person who can check it.
 *
 * <h2>Why not a resource pack</h2>
 *
 * That was the obvious answer and it was measured against this one. A pack means
 * building a zip, hosting it somewhere, a hash in the server settings and a
 * download prompt at the door — for a file the mod is already able to install by
 * itself in a few milliseconds. Sending it down a channel the mod already has is
 * smaller in every direction.
 *
 * <h2>The shape of it</h2>
 *
 * Three messages, and the middle one is the reason it is three rather than one.
 *
 * <ol>
 *   <li>The server says what it has, by name and by digest.
 *   <li>The client asks only for what it has not got.
 *   <li>The server sends that, in pieces.
 * </ol>
 *
 * The asking is what keeps a second visit free: fonts are cached by digest, so a
 * player who has been here before receives nothing at all. Pushing without asking
 * would send every typeface to every player on every join, which for a map with
 * six of them is a megabyte a door.
 */
public final class FontPayloads {

	private FontPayloads() { }

	/** How long a font's name may be on the wire. */
	private static final int NAMED = 128;

	/** Hex of a SHA-256: sixty-four characters, and there is no reason to allow more. */
	private static final int DIGEST = 64;

	/**
	 * What the server has, sent when a player arrives.
	 *
	 * Names as well as digests, because the client needs the name to install under
	 * — a font is chosen in the editor by name, and the same typeface has to be the
	 * same name for the person writing the line and the person reading it.
	 */
	public record Catalogue(List<Entry> fonts) implements CustomPacketPayload {

		public record Entry(String name, String digest, int bytes) {
			public static final StreamCodec<io.netty.buffer.ByteBuf, Entry> CODEC =
				StreamCodec.composite(
					ByteBufCodecs.stringUtf8(NAMED), Entry::name,
					ByteBufCodecs.stringUtf8(DIGEST), Entry::digest,
					ByteBufCodecs.VAR_INT, Entry::bytes,
					Entry::new);
		}

		public static final Type<Catalogue> TYPE = new Type<>(NpcStudio.id("fonts_here"));

		public static final StreamCodec<io.netty.buffer.ByteBuf, Catalogue> CODEC =
			StreamCodec.composite(
				Entry.CODEC.apply(ByteBufCodecs.list(FontFiles.MOST)), Catalogue::fonts,
				Catalogue::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** One typeface the client has not got, asked for by contents rather than by name. */
	public record Want(String digest) implements CustomPacketPayload {

		public static final Type<Want> TYPE = new Type<>(NpcStudio.id("font_wanted"));

		public static final StreamCodec<io.netty.buffer.ByteBuf, Want> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.stringUtf8(DIGEST), Want::digest,
				Want::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * One piece of one typeface.
	 *
	 * The number of pieces travels with every piece rather than being announced
	 * once. It is four bytes against a few hundred kilobytes, and it means the far
	 * end can put the file together without having had to receive any particular
	 * message first — including the first one.
	 */
	public record Part(String digest, int index, int parts, byte[] bytes)
			implements CustomPacketPayload {

		public static final Type<Part> TYPE = new Type<>(NpcStudio.id("font_part"));

		public static final StreamCodec<io.netty.buffer.ByteBuf, Part> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.stringUtf8(DIGEST), Part::digest,
				ByteBufCodecs.VAR_INT, Part::index,
				ByteBufCodecs.VAR_INT, Part::parts,
				ByteBufCodecs.byteArray(FontFiles.PIECE + 64), Part::bytes,
				Part::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
