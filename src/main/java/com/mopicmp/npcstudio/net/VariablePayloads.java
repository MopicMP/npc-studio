package com.mopicmp.npcstudio.net;

import java.util.List;

import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Every variable name the world knows, for the list the editor offers.
 *
 * <h2>Why this has to come from the server</h2>
 *
 * The editor holds one document. The names are spread across all of them, and a
 * variable in the player's scope is shared by every document that names it — which is
 * the fact that has to be visible while writing, because it is invisible afterwards.
 *
 * Reported as "variables seem to work only inside one dialogue". They do not: the store
 * is one map for the whole world, keyed by the player and not by the document. What is
 * per-document is the <em>declaration</em>, and what is per-row is the <em>scope</em> —
 * so writing a name under "player" in one graph and reading it under "world" in another
 * is two different variables that look like one, and nothing anywhere says so. A list
 * that names the scope alongside the name is where that stops being possible.
 *
 * <h2>Why the type travels with the name</h2>
 *
 * Because the other half of the same trap is a name declared as a flag in one document
 * and as a number in another. The comparison is then false for ever, both documents
 * validate, and the symptom is a branch that never fires. Seeing "flag" beside the name
 * at the moment of choosing it is the only cheap moment to catch that.
 */
public final class VariablePayloads {

	private VariablePayloads() { }

	/** As many rows as one answer carries. Well past any real map; a bound, not a budget. */
	public static final int MOST = 512;

	/** The editor asks. Sent when it opens, so the list is of what exists right now. */
	public record Please() implements CustomPacketPayload {
		public static final Type<Please> TYPE = new Type<>(NpcStudio.id("variables_please"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Please> CODEC =
			StreamCodec.unit(new Please());

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * One name, as one document knows it.
	 *
	 * @param scope the scope it is used in, or empty when the document declares it and
	 *              nothing has read or written it yet — which is a real state and worth
	 *              showing, since a declaration nobody uses is usually a name that was
	 *              typed twice with a difference somebody has not spotted
	 */
	public record Known(String name, String scope, String type, String document) {
		public static final StreamCodec<io.netty.buffer.ByteBuf, Known> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.stringUtf8(128), Known::name,
				ByteBufCodecs.stringUtf8(32), Known::scope,
				ByteBufCodecs.stringUtf8(32), Known::type,
				ByteBufCodecs.stringUtf8(128), Known::document,
				Known::new);
	}

	public record Names(List<Known> known) implements CustomPacketPayload {
		public static final Type<Names> TYPE = new Type<>(NpcStudio.id("variables_known"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Names> CODEC =
			Known.CODEC.apply(ByteBufCodecs.list(MOST)).map(Names::new, Names::known);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}
}
