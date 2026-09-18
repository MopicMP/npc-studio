package com.mopicmp.npcstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Turning "how much processor" into the two things a system actually understands.
 */
class CpuTest {

	@Test
	@DisplayName("A share of the cores is a mask, and no share at all is not a mask of nothing")
	void masks() {
		assertEquals(0b1L, Cpu.mask(1, 8));
		assertEquals(0b1111L, Cpu.mask(4, 8));
		// Zero means "all of them". A mask with no bits in it is a process that
		// can never be scheduled anywhere — the one answer that must not happen.
		assertEquals(0L, Cpu.mask(0, 8));
		assertEquals(0L, Cpu.mask(8, 8));
		assertEquals(0L, Cpu.mask(99, 8));
	}

	@Test
	@DisplayName("The same share, written the way Linux asks for it")
	void ranges() {
		assertEquals("0", Cpu.range(1, 8));
		assertEquals("0-3", Cpu.range(4, 8));
		assertEquals("", Cpu.range(0, 8));
		assertEquals("", Cpu.range(8, 8));
	}

	@Test
	@DisplayName("A name that means nothing is normal rather than an exception")
	void priorities() {
		assertEquals(Cpu.Priority.LOW, Cpu.Priority.of("low"));
		assertEquals(Cpu.Priority.BELOW, Cpu.Priority.of("BELOW"));
		assertEquals(Cpu.Priority.NORMAL, Cpu.Priority.of(""));
		assertEquals(Cpu.Priority.NORMAL, Cpu.Priority.of("whatever was in the file"));
	}
}
