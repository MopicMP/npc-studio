package com.mopicmp.npcstudio.server;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import com.mopicmp.npcstudio.NpcStudio;

/**
 * How much of the machine a server is allowed to take.
 *
 * There is no knob anywhere that means "use forty per cent of the processor".
 * What exists is two things, and between them they do the job honestly:
 *
 * <ul>
 * <li><b>How many cores it may run on.</b> Four of eight is half the machine, and
 * it is a real ceiling rather than a target — the operating system will not give
 * the process the other four whatever it does.</li>
 * <li><b>How politely it queues.</b> A lowered priority does not cap anything: it
 * says who yields when two programs want the same core at the same moment. On a
 * machine that is idle it changes nothing at all, which is exactly what makes it
 * the right tool for "do not make my game stutter".</li>
 * </ul>
 *
 * <p>Neither can be done from inside Java — the language has no idea what a core
 * is. So this asks the system, once, just after the process starts, and treats
 * failure as nothing worse than "the setting did not take": a server that runs on
 * every core is a working server, and refusing to start one because a niceness
 * command was missing would be absurd.
 */
public final class Cpu {

	private Cpu() {
	}

	/** How politely a server queues for the processor. */
	public enum Priority {
		NORMAL,
		BELOW,
		LOW;

		public static Priority of(String name) {
			for (Priority each : values()) {
				if (each.name().equalsIgnoreCase(name)) return each;
			}
			return NORMAL;
		}
	}

	/**
	 * The mask that lets a process run on the first {@code cores} cores.
	 *
	 * Kept as a long and given to the system as a number, which is what both
	 * Windows and Linux want. Zero cores means "no limit" rather than "no cores",
	 * because a mask of nothing is a process that can never run.
	 */
	static long mask(int cores, int have) {
		if (cores <= 0 || cores >= have) return 0;
		return (1L << cores) - 1;
	}

	/** The list Linux wants: {@code 0-3}, or blank when there is no limit. */
	static String range(int cores, int have) {
		if (cores <= 0 || cores >= have) return "";
		return cores == 1 ? "0" : "0-" + (cores - 1);
	}

	/**
	 * Apply both to a running process, saying nothing if it will not take.
	 *
	 * @param cores how many it may use, or zero for all of them
	 */
	public static void apply(long pid, int cores, Priority priority) {
		int have = Machine.cores();
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		try {
			if (os.contains("win")) {
				windows(pid, mask(cores, have), priority);
			} else {
				unix(pid, range(cores, have), priority);
			}
		} catch (IOException | RuntimeException notTaken) {
			// Worth a line in our log and nothing more. The server is running.
			NpcStudio.LOGGER.warn("Could not set the processor share of {}: {}",
				pid, notTaken.toString());
		}
	}

	private static void windows(long pid, long mask, Priority priority) throws IOException {
		StringBuilder script = new StringBuilder("$p = Get-Process -Id " + pid + ";");
		if (mask != 0) script.append(" $p.ProcessorAffinity = [System.IntPtr]").append(mask).append(";");
		script.append(" $p.PriorityClass = '").append(switch (priority) {
			case LOW -> "Idle";
			case BELOW -> "BelowNormal";
			case NORMAL -> "Normal";
		}).append("'");
		run(List.of("powershell", "-NoProfile", "-NonInteractive", "-Command", script.toString()));
	}

	private static void unix(long pid, String range, Priority priority) throws IOException {
		if (!range.isEmpty()) {
			// Only Linux has this; on macOS the command is simply not there and
			// the failure is caught where every other one here is.
			run(List.of("taskset", "-pc", range, String.valueOf(pid)));
		}
		int niceness = switch (priority) {
			case LOW -> 15;
			case BELOW -> 5;
			case NORMAL -> 0;
		};
		if (niceness != 0) {
			run(List.of("renice", "-n", String.valueOf(niceness), "-p", String.valueOf(pid)));
		}
	}

	private static void run(List<String> command) throws IOException {
		Process process = new ProcessBuilder(command)
			.redirectErrorStream(true)
			.redirectOutput(ProcessBuilder.Redirect.DISCARD)
			.start();
		try {
			// A moment, and no more: this is a housekeeping command, and a server
			// must not wait on it.
			if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
				process.destroy();
			}
		} catch (InterruptedException stopped) {
			Thread.currentThread().interrupt();
			process.destroy();
		}
	}
}
