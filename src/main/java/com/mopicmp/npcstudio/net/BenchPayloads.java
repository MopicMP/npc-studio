package com.mopicmp.npcstudio.net;

import java.util.List;

import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Trying something on a character and being told what happened.
 *
 * <h2>Why this is separate from everything else</h2>
 *
 * So that it can be deleted. Everything the bench does is scaffolding by
 * definition — a way to make a character do something now, in front of you,
 * before there is a graph telling her to. None of it is a feature, and the
 * moment block programming can express the same thing, the panel, these two
 * payloads and their handler should go out together in one commit.
 *
 * That was the fault being fixed when this was written: the same jobs had been
 * done as commands, each one commented "scaffolding, will move to the panel",
 * and nothing that is spread across a command tree ever gets moved. Keeping it
 * in one named place is the only version of "temporary" that has ever worked.
 */
public final class BenchPayloads {

	private BenchPayloads() { }

	/**
	 * Do this to the character that is selected.
	 *
	 * The action is a plain string rather than an enum with a codec, because the
	 * list will change every week for as long as this exists and an unknown name
	 * is already handled — it is answered with "no such thing", which is exactly
	 * what an out-of-date client deserves and no worse than a crash.
	 */
	public record Ask(int entityId, String action) implements CustomPacketPayload {
		public static final Type<Ask> TYPE = new Type<>(NpcStudio.id("bench_ask"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Ask> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Ask::entityId,
				ByteBufCodecs.STRING_UTF8, Ask::action,
				Ask::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * What came of it, as lines to show.
	 *
	 * Lines rather than a structure, because the whole value of a readout is that
	 * it can say anything without a packet being redesigned first. The moment it
	 * needs a structure it has stopped being a readout and become a feature, and
	 * features do not live here.
	 */
	public record Told(List<String> lines) implements CustomPacketPayload {
		public static final Type<Told> TYPE = new Type<>(NpcStudio.id("bench_told"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Told> CODEC =
			ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(64))
				.map(Told::new, Told::lines);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}
}
