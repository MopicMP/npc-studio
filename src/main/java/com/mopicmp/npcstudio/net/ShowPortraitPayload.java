package com.mopicmp.npcstudio.net;

import java.util.List;

import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * What is standing beside this player's scene: a stack of pictures and where each sits.
 *
 * <h2>Why the fingerprints and not the pictures</h2>
 *
 * The same bargain a character's skin already strikes, and for a stronger reason here: a
 * portrait is a drawing rather than a sixty-four-pixel square, and sending one down the
 * wire every time a scene changed expression would be shipping the same few hundred
 * kilobytes over and over. The client asks once, by fingerprint, and never again — the
 * picture is on a shelf of the world and every player may fetch it.
 *
 * With a figure assembled from parts the saving is the whole point rather than a
 * nicety: changing an expression sends one small picture's name and the eight kilobytes
 * of the picture itself, where a finished portrait would be the figure all over again.
 *
 * <h2>Why the stack is worked out on the server</h2>
 *
 * Because it is a fact about the world, and the client should not need the layout sheet
 * to draw a scene. The server has the sheet, looks up what the act asked to be worn, and
 * sends the answer: these pictures, at these corners. A player who has never opened the
 * layout window — which is every player — draws the figure correctly, and a sheet edited
 * mid-session cannot leave two clients disagreeing about where somebody's eyes are.
 *
 * <h2>Why the whole state and not "add" and "remove"</h2>
 *
 * Because a packet that says what is showing cannot leave a client disagreeing with the
 * server. Two packets that adjust a state can: one lost, one arriving twice, one arriving
 * in the wrong order, and a portrait stands there for the rest of the session with
 * nothing left that knows how to take it down. This one names what is up, and an empty
 * list is nothing up.
 *
 * @param layers   the pictures, back to front, with their corners on the figure's canvas
 * @param wide     the figure's canvas, or 0 for a single picture standing on its own
 * @param side     which edge it stands against, by ordinal
 * @param mirrored whether it is flipped
 */
public record ShowPortraitPayload(List<Layer> layers, int wide, int high,
		int side, boolean mirrored) implements CustomPacketPayload {

	/**
	 * As many pictures as one figure may be made of.
	 *
	 * A bound on the packet rather than on the sheet: a sheet may hold more slots than
	 * this, and a scene showing more than sixty-four of them at once is not a portrait,
	 * it is a mistake somebody would rather hear about than see drawn.
	 */
	public static final int MOST_LAYERS = 64;

	/** One picture and where its corner goes on the figure's canvas. */
	public record Layer(String picture, int x, int y) {
		public static final StreamCodec<io.netty.buffer.ByteBuf, Layer> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, Layer::picture,
				ByteBufCodecs.VAR_INT, Layer::x,
				ByteBufCodecs.VAR_INT, Layer::y,
				Layer::new);
	}

	public static final Type<ShowPortraitPayload> TYPE = new Type<>(NpcStudio.id("show_portrait"));

	public static final StreamCodec<io.netty.buffer.ByteBuf, ShowPortraitPayload> CODEC =
		StreamCodec.composite(
			Layer.CODEC.apply(ByteBufCodecs.list(MOST_LAYERS)), ShowPortraitPayload::layers,
			ByteBufCodecs.VAR_INT, ShowPortraitPayload::wide,
			ByteBufCodecs.VAR_INT, ShowPortraitPayload::high,
			ByteBufCodecs.VAR_INT, ShowPortraitPayload::side,
			ByteBufCodecs.BOOL, ShowPortraitPayload::mirrored,
			ShowPortraitPayload::new);

	/**
	 * One whole picture standing on its own, which is what this packet used to be.
	 *
	 * A canvas of nothing rather than the picture's own size, because the server does not
	 * know that size — the picture is a file on a shelf, and reading every header to fill
	 * in a number the client already has would be work for nothing. Nought means "the
	 * one picture is the canvas", and the drawing does exactly what it always did.
	 */
	public static ShowPortraitPayload one(String picture, int side, boolean mirrored) {
		return new ShowPortraitPayload(List.of(new Layer(picture, 0, 0)), 0, 0, side, mirrored);
	}

	/** Nothing showing, which is what the end of every conversation sends. */
	public static ShowPortraitPayload none() {
		return new ShowPortraitPayload(List.of(), 0, 0, 0, false);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
