package com.mopicmp.npcstudio.net;

import java.util.List;

import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The world's shelf of portraits, as the editor needs to see it.
 *
 * <h2>Why the pictures are not here</h2>
 *
 * Only the list is: a name, a shelf, and the fingerprint of the picture. The picture
 * itself is fetched one at a time by fingerprint, through the pair the wardrobe
 * already has — {@code WardrobePayloads.PicturePlease} and {@code Picture} — because
 * that pair is not about costumes at all. It says "send me the picture with this
 * fingerprint", the server checks the fingerprint is one it knows, and a portrait is
 * exactly as much a picture with a fingerprint as a costume is.
 *
 * <h2>Why the rows are the wardrobe's own row type</h2>
 *
 * Because they are the same row: an id, a label, the two names it is filed under, and
 * a fingerprint. A second record with the same six fields would be a second thing to
 * keep in step for no gain — and the two fields at the end that mean nothing to a
 * portrait cost a byte each.
 */
public final class PortraitPayloads {

	private PortraitPayloads() { }

	/** Client asks for the whole list. Cheap, and the picker needs it to open. */
	public record Please() implements CustomPacketPayload {
		public static final Type<Please> TYPE = new Type<>(NpcStudio.id("portraits_please"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Please> CODEC =
			StreamCodec.unit(new Please());

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Server sends the whole list. Small, so it goes in one piece. */
	public record Shelf(List<WardrobePayloads.Costume> pictures) implements CustomPacketPayload {
		public static final Type<Shelf> TYPE = new Type<>(NpcStudio.id("portraits_shelf"));

		private static final StreamCodec<io.netty.buffer.ByteBuf, WardrobePayloads.Costume> ONE =
			StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, WardrobePayloads.Costume::id,
				ByteBufCodecs.STRING_UTF8, WardrobePayloads.Costume::label,
				ByteBufCodecs.STRING_UTF8, WardrobePayloads.Costume::category,
				ByteBufCodecs.STRING_UTF8, WardrobePayloads.Costume::group,
				ByteBufCodecs.STRING_UTF8, WardrobePayloads.Costume::fingerprint,
				ByteBufCodecs.VAR_INT, WardrobePayloads.Costume::width,
				ByteBufCodecs.BOOL, WardrobePayloads.Costume::eyes,
				WardrobePayloads.Costume::new);

		public static final StreamCodec<io.netty.buffer.ByteBuf, Shelf> CODEC =
			ONE.apply(ByteBufCodecs.list()).map(Shelf::new, Shelf::pictures);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}
}
