package com.mopicmp.npcstudio.net;

import java.util.List;

import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Layout sheets across the wire: a figure's slots, their positions, and what fills them.
 *
 * <h2>Why the sheets travel as text</h2>
 *
 * Because there is already one way to write a sheet down, and it is read by people. A
 * second shape for the wire would be a second thing to keep in step with the record —
 * and this codebase has paid for that three times: a box written in two frames, a line
 * rebuilt in eleven places, a running scene remembered in two maps. The file's own
 * writing is reused as-is.
 *
 * <h2>Why one slot at a time going up, and everything at once coming down</h2>
 *
 * A packet from a client is capped at about thirty-two thousand bytes, and that is the
 * hard fact this is shaped around.
 *
 * A whole sheet does not have a bound anybody can promise: a figure may have a hundred
 * slots with a dozen variants each, and somewhere past that a save would silently be too
 * big to send. A <em>slot</em> does have one — it is the thing being edited, it is
 * bounded by {@code Puppet.MOST_PARTS}, and the worst case fits with room to spare.
 *
 * It is also how the editing actually goes: somebody places a slot and moves to the next
 * one. Saving as they go means there is no button at the end that can fail, and nothing
 * is lost if they close the window.
 *
 * Coming down the cap is roomier and the whole shelf is a few kilobytes of names, so it
 * arrives in one piece like every other list here.
 */
public final class PuppetPayloads {

	private PuppetPayloads() { }

	/**
	 * As long as one slot's writing may be.
	 *
	 * Comfortably inside what a client may send, and comfortably past what a slot of a
	 * hundred and twenty-eight variants actually writes. A slot that will not fit is
	 * refused whole rather than cut short: half a slot is a figure with pieces missing
	 * and nothing anywhere saying why.
	 */
	public static final int MOST_SLOT = 24_000;

	/** Client asks for the world's sheets. The picker and the layout screen both need them. */
	public record Please() implements CustomPacketPayload {
		public static final Type<Please> TYPE = new Type<>(NpcStudio.id("puppets_please"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Please> CODEC =
			StreamCodec.unit(new Please());

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Server sends every sheet, each as the same text the world's own file holds. */
	public record Sheets(List<String> written) implements CustomPacketPayload {
		public static final Type<Sheets> TYPE = new Type<>(NpcStudio.id("puppets_sheets"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Sheets> CODEC =
			ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(
					com.mopicmp.npcstudio.puppet.PuppetShelf.MOST))
				.map(Sheets::new, Sheets::written);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * One slot placed, on a sheet that is made if it is not there yet.
	 *
	 * The canvas travels with every slot, and that is deliberate rather than wasteful:
	 * the first slot somebody places is what creates the sheet, and there is no separate
	 * "make a sheet" step to forget. Later slots simply say the same thing again.
	 *
	 * @param slot the slot as JSON, in the same shape the world's file uses
	 */
	public record PutSlot(String sheet, int wide, int high, String slot)
			implements CustomPacketPayload {
		public static final Type<PutSlot> TYPE = new Type<>(NpcStudio.id("puppet_put_slot"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, PutSlot> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.stringUtf8(64), PutSlot::sheet,
				ByteBufCodecs.VAR_INT, PutSlot::wide,
				ByteBufCodecs.VAR_INT, PutSlot::high,
				ByteBufCodecs.stringUtf8(MOST_SLOT), PutSlot::slot,
				PutSlot::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * A slot taken off a sheet, or — with no slot named — the whole sheet.
	 *
	 * One verb for both because they are the same act at two sizes, and because a second
	 * packet type would be a second place to check who is allowed to do it.
	 */
	public record Drop(String sheet, String slot) implements CustomPacketPayload {
		public static final Type<Drop> TYPE = new Type<>(NpcStudio.id("puppet_drop"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Drop> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.stringUtf8(64), Drop::sheet,
				ByteBufCodecs.stringUtf8(64), Drop::slot,
				Drop::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}
}
