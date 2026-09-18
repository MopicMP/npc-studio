package com.mopicmp.npcstudio.map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A place on the map with a name, which is what a graph can be pointed at.
 *
 * <h2>Why this had to exist</h2>
 *
 * Because every verb that aims — walk to, look at, fire at, strike — takes a
 * mark, and until now every mark was relative: whoever she noticed, the nearest
 * player, another of her kind, where she was standing when asked. Not one of them
 * can say "the gate". A scene where somebody walks to a particular doorway could
 * not be written at all, and the way round it was to put a character there and
 * hope nothing moved her.
 *
 * {@link com.mopicmp.npcstudio.dialogue.Mark} predicted this in the file that
 * defines the relative ones: <em>"a named point placed in a scene — the gate, the
 * third window — is the shape that will be wanted then, and it is still a
 * name."</em> This is that, and it is still a name: a graph refers to
 * {@code at:gate} and knows nothing about where the gate is, so the same graph
 * copied to another map works there as soon as that map has a gate.
 *
 * <h2>Why a block and not a precise point</h2>
 *
 * Because everything that consumes one wants somewhere to stand or somewhere to
 * look, and both are satisfied by the middle of a block. A point stored to six
 * decimals would say the author had chosen those decimals, which they did not —
 * they clicked on a floor.
 */
public record Spot(String name, BlockPos at) {

	/**
	 * The longest a name may be.
	 *
	 * Not a guess at what anybody needs: it is what fits in the places a name is
	 * read. Names are shown in a list down the side of a panel and in a dropdown in
	 * the graph editor, both of which are narrow, and a name too long to read in
	 * either is a name that cannot be chosen.
	 */
	public static final int LONGEST = 24;

	public static final Codec<Spot> CODEC = RecordCodecBuilder.create(each -> each.group(
		Codec.STRING.fieldOf("name").forGetter(Spot::name),
		BlockPos.CODEC.fieldOf("at").forGetter(Spot::at)
	).apply(each, Spot::new));

	public static final StreamCodec<io.netty.buffer.ByteBuf, Spot> STREAM_CODEC =
		StreamCodec.composite(
			ByteBufCodecs.stringUtf8(LONGEST), Spot::name,
			BlockPos.STREAM_CODEC, Spot::at,
			Spot::new);

	/**
	 * The name as it will be stored, or null when it is not a name at all.
	 *
	 * A colon is refused because {@code at:} is how a place is told from a relative
	 * mark, and a point called {@code x:y} would write a mark nothing could read
	 * back. Everything else is allowed, including spaces and any language: this is
	 * a label an author reads, not an identifier.
	 */
	public static String tidy(String wanted) {
		if (wanted == null) return null;
		String name = wanted.trim();
		if (name.isEmpty() || name.length() > LONGEST) return null;
		if (name.indexOf(':') >= 0) return null;
		return name;
	}
}
