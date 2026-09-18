package com.mopicmp.npcstudio.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.mopicmp.npcstudio.net.SpotPayloads;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;

/**
 * Places going out and coming back, over the wire and into the save.
 *
 * <h2>Why a round trip is the test worth writing here</h2>
 *
 * Because both halves of a codec are written at once and only one of them is ever
 * exercised by hand. Somebody placing a point sees it appear, and that proves the
 * writing; the reading is not proved until the world is closed and opened again,
 * which is a slow way to find out that a map's places have been quietly emptying
 * themselves. The same for the packet: everything looks right until the list is
 * long enough or the name is in the wrong alphabet.
 *
 * <h2>The full list, exactly</h2>
 *
 * {@link WorldSpots#MOST} is both the number the store allows and the number the
 * packet allows, and if those disagree by one then a map that is full is a map
 * whose places can never be sent — every client would see none of them, with
 * nothing anywhere saying why. That is the sort of off-by-one that survives
 * review, so it is pinned rather than reasoned about.
 */
class SpotWireTest {

	private static BlockPos at(int x, int y, int z) {
		return new BlockPos(x, y, z);
	}

	@Test
	@DisplayName("one place survives being written and read again")
	void oneSpotRoundTrips() {
		Spot spot = new Spot("gate", at(120, 71, -88));
		JsonElement written = Spot.CODEC.encodeStart(JsonOps.INSTANCE, spot)
			.getOrThrow(IllegalStateException::new);
		Spot read = Spot.CODEC.parse(JsonOps.INSTANCE, written)
			.getOrThrow(IllegalStateException::new);
		assertEquals(spot, read);
	}

	@Test
	@DisplayName("a whole map's places survive being saved and loaded")
	void theStoreRoundTrips() {
		WorldSpots spots = new WorldSpots();
		spots.put("gate", at(1, 64, 2));
		// In the alphabet this map is actually written in, which is the case a codec
		// tested with "test" never covers.
		spots.put("ворота", at(-300, 12, 4000));
		spots.put("the third window", at(0, 255, 0));

		JsonElement written = WorldSpots.CODEC.encodeStart(JsonOps.INSTANCE, spots)
			.getOrThrow(IllegalStateException::new);
		WorldSpots read = WorldSpots.CODEC.parse(JsonOps.INSTANCE, written)
			.getOrThrow(IllegalStateException::new);

		assertEquals(3, read.count());
		assertEquals(at(1, 64, 2), read.at("gate"));
		assertEquals(at(-300, 12, 4000), read.at("ворота"));
		assertEquals(at(0, 255, 0), read.at("the third window"));
	}

	@Test
	@DisplayName("a place with the longest allowed name fits in a packet")
	void longestNameFitsOnTheWire() {
		// Both alphabets at the limit. A Russian name is two bytes a letter where an
		// English one is one, and a cap counted in the wrong unit refuses the longer of
		// the two while letting the shorter through — which looks like the field
		// working for some people and not others.
		for (String letter : new String[] { "a", "в" }) {
			String name = letter.repeat(Spot.LONGEST);
			assertEquals(name, Spot.tidy(name), "tidy should allow " + letter);

			ByteBuf buffer = Unpooled.buffer();
			Spot sent = new Spot(name, at(7, 7, 7));
			Spot.STREAM_CODEC.encode(buffer, sent);
			assertEquals(sent, Spot.STREAM_CODEC.decode(buffer));
		}
	}

	@Test
	@DisplayName("a completely full map's places fit in one packet")
	void theFullListFitsOnTheWire() {
		List<Spot> full = new ArrayList<>();
		for (int i = 0; i < WorldSpots.MOST; i++) {
			full.add(new Spot("place " + i, at(i, 64, i)));
		}

		ByteBuf buffer = Unpooled.buffer();
		SpotPayloads.Spots.CODEC.encode(buffer, new SpotPayloads.Spots(full));
		SpotPayloads.Spots back = SpotPayloads.Spots.CODEC.decode(buffer);

		assertEquals(WorldSpots.MOST, back.spots().size());
		assertEquals(full, back.spots());
	}

	@Test
	@DisplayName("a list longer than the map allows is refused rather than trusted")
	void anOverlongListIsRefused() {
		// The cap is on the reading side as well as the writing side, and it has to be:
		// the client believes what it is told, and a list with no ceiling arriving from
		// anywhere is a list that can be made as long as somebody likes.
		List<Spot> tooMany = new ArrayList<>();
		for (int i = 0; i <= WorldSpots.MOST; i++) {
			tooMany.add(new Spot("place " + i, at(i, 64, i)));
		}

		ByteBuf buffer = Unpooled.buffer();
		assertThrows(RuntimeException.class,
			() -> SpotPayloads.Spots.CODEC.encode(buffer, new SpotPayloads.Spots(tooMany)));
	}

	@Test
	@DisplayName("an empty map is an empty list, not a missing one")
	void emptyRoundTrips() {
		ByteBuf buffer = Unpooled.buffer();
		SpotPayloads.Spots.CODEC.encode(buffer, new SpotPayloads.Spots(List.of()));
		assertTrue(SpotPayloads.Spots.CODEC.decode(buffer).spots().isEmpty());

		JsonElement written = WorldSpots.CODEC.encodeStart(JsonOps.INSTANCE, new WorldSpots())
			.getOrThrow(IllegalStateException::new);
		assertEquals(0, WorldSpots.CODEC.parse(JsonOps.INSTANCE, written)
			.getOrThrow(IllegalStateException::new).count());
	}
}
