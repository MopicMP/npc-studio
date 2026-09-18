package com.mopicmp.npcstudio.server;

import java.lang.management.ManagementFactory;

/**
 * What this computer can actually spare for a server.
 *
 * Written after a server made by this mod became unplayable for the one person
 * on it. Nothing was broken: the client had two gigabytes, the server took two
 * more, and both were sharing six gigabytes and two cores with Windows. The
 * machine swapped, the server missed its ticks, and the client timed out — which
 * arrives as "connection lost" and looks like a network fault.
 *
 * So the numbers a server is created with are not defaults from a wiki. They are
 * worked out from the machine it will run on, and when the machine is too small
 * the interface says so instead of finding out later.
 */
public final class Machine {

	private Machine() {
	}

	/** Left for the operating system and everything else that is open. */
	private static final long RESERVED_MB = 2000;

	/** Below this a server is not worth starting; above it, it is a real one. */
	private static final long FLOOR_MB = 768;

	private static final long CEILING_MB = 6144;

	/** Total physical memory in megabytes, or zero when the JVM will not say. */
	public static long totalMemoryMb() {
		try {
			var bean = ManagementFactory.getOperatingSystemMXBean();
			if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
				return sun.getTotalMemorySize() / (1024 * 1024);
			}
		} catch (Throwable unavailable) {
			// Some runtimes do not carry the extended bean. Nothing here is worth
			// a failure; the caller gets zero and asks nobody.
		}
		return 0;
	}

	/** What the game itself has been allowed, in megabytes. */
	public static long clientHeapMb() {
		return Runtime.getRuntime().maxMemory() / (1024 * 1024);
	}

	public static int cores() {
		return Runtime.getRuntime().availableProcessors();
	}

	/**
	 * How much to give a server on this machine.
	 *
	 * The game's own heap is subtracted, not ignored: the two are running at the
	 * same time on the same machine, and a sum that fits neither is how a laptop
	 * ends up paging one of them to disk. Rounded down to whole quarters of a
	 * gigabyte, because a number like 1873 invites somebody to wonder where it
	 * came from.
	 */
	public static int recommendedHeapMb() {
		long total = totalMemoryMb();
		if (total <= 0) return 1024;
		long free = total - clientHeapMb() - RESERVED_MB;
		long rounded = Math.clamp(free, FLOOR_MB, CEILING_MB) / 256 * 256;
		return (int) Math.max(FLOOR_MB, rounded);
	}

	/** Whether asking this machine to run both is going to hurt. */
	public static boolean tight() {
		return cores() <= 2 || totalMemoryMb() > 0 && totalMemoryMb() < 8192;
	}

	/**
	 * How far a locally hosted server should draw, in chunks.
	 *
	 * The vanilla default is ten, and ten is chosen for a server that is the only
	 * thing on its machine. Here the same processor is drawing the game, and view
	 * distance is the single heaviest thing a small server does — the work grows
	 * with the square of it.
	 */
	public static int viewDistance() {
		return cores() <= 2 ? 6 : cores() <= 4 ? 8 : 10;
	}

	/** How far it should keep the world ticking. Cheaper than drawing, but not free. */
	public static int simulationDistance() {
		return cores() <= 2 ? 4 : cores() <= 4 ? 6 : 10;
	}

	/**
	 * Flags for the server's own JVM: none, and that is the finding.
	 *
	 * <b>This method used to return five, and they made the server unplayable.</b>
	 * A server started by hand from its folder — plain {@code -Xmx}, nothing else
	 * — carried three people on this machine. The same server started from here,
	 * with the same memory, threw its only player off.
	 *
	 * What they did, and why each was wrong:
	 *
	 * <ul>
	 * <li>{@code MinHeapFreeRatio} and {@code MaxHeapFreeRatio} ask the collector
	 * to keep the heap only slightly larger than what is in use — which means
	 * <em>resizing it constantly</em>, and on G1 a shrink is a full collection.
	 * Full collections of a multi-gigabyte heap on a slow machine are seconds
	 * long, and seconds are what a client waits before deciding the server is
	 * gone;</li>
	 * <li>{@code G1PeriodicGCInterval} adds a collection every thirty seconds
	 * whether anything needs collecting or not;</li>
	 * <li>{@code MaxGCPauseMillis=50} sounds like the tick and is not a promise
	 * of one: it makes the collector work in much smaller pieces, far more
	 * often, for more total overhead.</li>
	 * </ul>
	 *
	 * Where the reasoning came from is worth writing down, because it is a way of
	 * being wrong that repeats: it was lifted from the note in
	 * {@code gradle.properties} about the Gradle daemon. That note is about a
	 * process that has <em>finished working</em> and should hand memory back. A
	 * server is the opposite — it is working continuously and must never stop to
	 * rearrange itself. A conclusion proved in one place, applied to another
	 * where it is false.
	 *
	 * So: whatever the person chose for memory, and nothing else. If tuning
	 * returns it will be because it was measured on a running server, not because
	 * it reads well.
	 */
	public static java.util.List<String> jvmFlags() {
		return java.util.List.of();
	}
}
