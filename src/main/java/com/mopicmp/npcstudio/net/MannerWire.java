package com.mopicmp.npcstudio.net;

import com.mopicmp.npcstudio.dialogue.Manner;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * How a document's lines are shown, on its way to the screen.
 *
 * <h2>Why the whole record travels rather than the parts of it the screen uses</h2>
 *
 * Because which parts the screen uses is not settled and should not have to be. Two
 * of these seven are read by the server and never leave it; the rest are read by the
 * client. Sending only the client's five would mean that moving one setting across
 * that line later is a change to two payloads, a stream codec and both ends —
 * whereas the record already exists, is already small, and is already the thing the
 * conversation means by "how this is shown".
 *
 * Sent with every line rather than once when the conversation opens, for the reason
 * anything else about a line is: the client keeps nothing between lines on purpose,
 * so a player who joins mid-scene or reconnects gets a correct screen from the first
 * packet instead of the defaults until the next one.
 */
public final class MannerWire {

	private MannerWire() { }

	public static final StreamCodec<io.netty.buffer.ByteBuf, Manner> MANNER =
		StreamCodec.composite(
			ByteBufCodecs.FLOAT, Manner::width,
			ByteBufCodecs.VAR_INT, Manner::lines,
			ByteBufCodecs.BOOL, Manner::holdsPlayer,
			ByteBufCodecs.DOUBLE, Manner::range,
			ByteBufCodecs.VAR_INT, Manner::idleTicks,
			ByteBufCodecs.FLOAT, Manner::pace,
			ByteBufCodecs.BOOL, Manner::breathes,
			Manner::new);
}
