package com.mopicmp.npcstudio.net;

import java.util.List;

import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Talking about the world's costumes.
 *
 * The shape here follows one rule: <b>the list travels, the pictures do not</b>.
 * A library of a thousand costumes is a few megabytes of PNG and a few tens of
 * kilobytes of names — and the names are what a screen needs to draw itself.
 * Pictures are asked for one at a time, when something is actually about to be
 * looked at.
 *
 * Sending the lot would work perfectly on the machine it was written on and
 * badly everywhere else, which is the definition of a mistake worth avoiding
 * before it is made rather than after.
 */
public final class WardrobePayloads {

	private WardrobePayloads() { }

	/** Client asks for the library. */
	public record Please() implements CustomPacketPayload {
		public static final Type<Please> TYPE = new Type<>(NpcStudio.id("wardrobe_please"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Please> CODEC =
			StreamCodec.unit(new Please());

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * One costume as a screen needs it: everything except the picture.
	 *
	 * @param eyes whether a blink will work on it — read from the skin when it was
	 *             stored, so the answer is there before anything is downloaded
	 */
	public record Costume(String id, String label, String category, String group,
			String fingerprint, int width, boolean eyes) { }

	/** Server sends the whole list. Small, so it goes in one piece. */
	public record Library(List<Costume> costumes) implements CustomPacketPayload {
		public static final Type<Library> TYPE = new Type<>(NpcStudio.id("wardrobe_library"));

		private static final StreamCodec<io.netty.buffer.ByteBuf, Costume> ONE =
			StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, Costume::id,
				ByteBufCodecs.STRING_UTF8, Costume::label,
				ByteBufCodecs.STRING_UTF8, Costume::category,
				ByteBufCodecs.STRING_UTF8, Costume::group,
				ByteBufCodecs.STRING_UTF8, Costume::fingerprint,
				ByteBufCodecs.VAR_INT, Costume::width,
				ByteBufCodecs.BOOL, Costume::eyes,
				Costume::new);

		public static final StreamCodec<io.netty.buffer.ByteBuf, Library> CODEC =
			ONE.apply(ByteBufCodecs.list(4096)).map(Library::new, Library::costumes);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Client asks for one costume's picture, because it is about to draw it. */
	public record PicturePlease(String fingerprint) implements CustomPacketPayload {
		public static final Type<PicturePlease> TYPE = new Type<>(NpcStudio.id("wardrobe_picture_please"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, PicturePlease> CODEC =
			ByteBufCodecs.STRING_UTF8.map(PicturePlease::new, PicturePlease::fingerprint);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * Server hands over one piece of one picture.
	 *
	 * In pieces for the same reason skins go up in pieces, one direction along: the
	 * clientbound cap is roomier than the serverbound one but it is still a cap, and
	 * a two-megabyte skin is over it. Every picture is sent this way whatever its
	 * size, because one path is one path to get right — a small skin is simply a
	 * transfer of one piece.
	 */
	public record Picture(String fingerprint, int index, int count, byte[] part)
			implements CustomPacketPayload {
		public static final Type<Picture> TYPE = new Type<>(NpcStudio.id("wardrobe_picture"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Picture> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, Picture::fingerprint,
				ByteBufCodecs.VAR_INT, Picture::index,
				ByteBufCodecs.VAR_INT, Picture::count,
				ByteBufCodecs.byteArray(PART), Picture::part,
				Picture::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * Changing the library.
	 *
	 * One packet with a verb rather than six packets, because they all go from
	 * the same screen to the same place and differ only in what they mean. The
	 * whole list comes back afterwards, so a client is told the result rather
	 * than left to work it out.
	 */
	public record Edit(Verb verb, List<String> ids, String label, String category,
			String group, byte[] pixels) implements CustomPacketPayload {

		public enum Verb { ADD, REMOVE, REFILE, RENAME, DROP_CATEGORY, RESTORE, COPY }

		/**
		 * A number off the wire is not an ordinal until it has been checked.
		 *
		 * {@code Verb.values()[i]} reads perfectly and throws on any number that is
		 * not one of ours, in the middle of decoding, before a single one of our own
		 * checks has run. Nothing catastrophic follows — the connection is dropped —
		 * but a stranger's byte should not be choosing between an exception and a
		 * verb. Anything unrecognised means the harmless one.
		 */
		private static Verb verbOf(int id) {
			return id >= 0 && id < Verb.values().length ? Verb.values()[id] : Verb.REMOVE;
		}

		public static final Type<Edit> TYPE = new Type<>(NpcStudio.id("wardrobe_edit"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Edit> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.idMapper(Edit::verbOf, Verb::ordinal), Edit::verb,
				ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(512)), Edit::ids,
				ByteBufCodecs.STRING_UTF8, Edit::label,
				ByteBufCodecs.STRING_UTF8, Edit::category,
				ByteBufCodecs.STRING_UTF8, Edit::group,
				ByteBufCodecs.byteArray(384 * 1024), Edit::pixels,
				Edit::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * Client says where a costume's face keeps its eyes.
	 *
	 * Two longs, a bit per pixel of the face, and whether a person drew them. The
	 * last one is not decoration: a guess may be replaced by a better guess when
	 * the reading improves, and an answer somebody gave by hand may not.
	 */
	/**
	 * A piece of a skin too large to travel in one packet.
	 *
	 * <h2>Why this has to exist</h2>
	 *
	 * Minecraft caps a payload going <em>from</em> a client at 32767 bytes and
	 * disconnects the player over anything larger. That is not our number and not
	 * negotiable. Measured against fifty real HD skins, <b>twenty-two fit and
	 * twenty-eight do not</b> — the median is a hundred and ten kilobytes and the
	 * largest is two megabytes. So without this, more than half of the skins
	 * detailed enough to be worth marking cannot be put into a wardrobe at all.
	 *
	 * The pieces are numbered and counted, so the server knows when it has them
	 * all and never has to guess. It is deliberately not a stream: an upload that
	 * stops half way is a few kilobytes on a heap with a name on it, dropped when
	 * the player leaves or when the next upload starts.
	 *
	 * @param upload a name the client chose for this transfer, so two at once do
	 *               not mix; a stranger may choose any name and can only spoil
	 *               their own upload by it
	 * @param index  which piece this is, from nought
	 * @param count  how many pieces there are altogether
	 */
	public record AddPart(String upload, int index, int count, String label,
			String category, String group, byte[] part) implements CustomPacketPayload {

		public static final Type<AddPart> TYPE = new Type<>(NpcStudio.id("wardrobe_part"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, AddPart> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, AddPart::upload,
				ByteBufCodecs.VAR_INT, AddPart::index,
				ByteBufCodecs.VAR_INT, AddPart::count,
				ByteBufCodecs.STRING_UTF8, AddPart::label,
				ByteBufCodecs.STRING_UTF8, AddPart::category,
				ByteBufCodecs.STRING_UTF8, AddPart::group,
				ByteBufCodecs.byteArray(PART), AddPart::part,
				AddPart::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * How much of a skin goes in one piece.
	 *
	 * Well under the 32767 the game allows, because the name, the shelf and the
	 * transfer's own name ride along with every piece and a payload one byte over
	 * the line does not fail politely — it drops the player out of the world.
	 */
	public static final int PART = 24 * 1024;

	/**
	 * Where a costume's face keeps its eyes, as one line of text.
	 *
	 * It used to be three longs, which was the whole mask when a face was eight
	 * pixels square. A face is now as fine as the skin was drawn — up to two
	 * hundred and fifty-six across — so the mask travels run-length encoded
	 * instead; see {@link com.mopicmp.npcstudio.entity.FaceMask#encode}. That is a
	 * few hundred bytes for a face of any size, because what a person marks is a
	 * handful of solid blobs however large the grid beneath them.
	 */
	public record MarkEyes(String costumeId, String mask, boolean byHand)
			implements CustomPacketPayload {
		public static final Type<MarkEyes> TYPE = new Type<>(NpcStudio.id("wardrobe_eyes"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, MarkEyes> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, MarkEyes::costumeId,
				ByteBufCodecs.stringUtf8(MASK_LIMIT), MarkEyes::mask,
				ByteBufCodecs.BOOL, MarkEyes::byHand,
				MarkEyes::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * How long an encoded mask may be.
	 *
	 * Generous against what a hand-marked face actually comes to and firm against
	 * what a hostile client could send. A mask marked by a person is a few hundred
	 * bytes; the worst case a person could reach by painting a checkerboard over
	 * the largest face is far below this, and anything beyond it is not a face.
	 */
	public static final int MASK_LIMIT = 64 * 1024;

	/** Client dresses a character in something from the library. */
	public record Wear(int entityId, String costumeId) implements CustomPacketPayload {
		public static final Type<Wear> TYPE = new Type<>(NpcStudio.id("wardrobe_wear"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Wear> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Wear::entityId,
				ByteBufCodecs.STRING_UTF8, Wear::costumeId,
				Wear::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** The saved versions of the list, for putting one back. */
	public record Versions(List<String> names) implements CustomPacketPayload {
		public static final Type<Versions> TYPE = new Type<>(NpcStudio.id("wardrobe_versions"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Versions> CODEC =
			ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(256))
				.map(Versions::new, Versions::names);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}
}
