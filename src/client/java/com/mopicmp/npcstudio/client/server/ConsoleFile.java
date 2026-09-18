package com.mopicmp.npcstudio.client.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.server.ManagedServer;

/**
 * Taking the console away with you.
 *
 * The reason this exists is that a console is read by somebody else: the person
 * with the problem is rarely the person who knows what the exception means, and
 * "select it all and paste it into a message" is how that conversation starts.
 * Copying works for a screenful; a file works for a start-up that went wrong
 * four hundred lines ago.
 */
public final class ConsoleFile {

	private ConsoleFile() {
	}

	private static final DateTimeFormatter WHEN =
		DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm");

	/** Asks where to put it and writes it. Returns what to say, or empty. */
	public static String save(ManagedServer server, List<String> console) {
		String suggested = server.id + "-" + LocalDateTime.now().format(WHEN) + ".log";
		String picked;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer filters = stack.mallocPointer(1);
			filters.put(stack.UTF8("*.log"));
			filters.flip();
			picked = TinyFileDialogs.tinyfd_saveFileDialog(
				"Save the console", suggested, filters, "Log files (*.log)");
		} catch (Throwable unavailable) {
			NpcStudio.LOGGER.warn("No file chooser available: {}", unavailable.toString());
			return "no file chooser";
		}
		if (picked == null || picked.isBlank()) return "";

		try {
			// What is on the screen, and then the file the server itself keeps if
			// there is one: the second is longer than we keep in memory, and the
			// interesting line is often past that.
			StringBuilder out = new StringBuilder();
			out.append("--- npc studio console for ").append(server.id).append('\n');
			for (String line : console) out.append(line).append('\n');

			Path own = server.consolePath();
			if (Files.isRegularFile(own)) {
				out.append("\n--- ").append(own.getFileName()).append('\n');
				long size = Files.size(own);
				// Only the tail of it: a month of restarts is not what anybody
				// meant by "the console".
				byte[] bytes = Files.readAllBytes(own);
				int from = (int) Math.max(0, size - 512 * 1024);
				out.append(new String(bytes, from, bytes.length - from, StandardCharsets.UTF_8));
			}
			Files.writeString(Path.of(picked), out.toString(), StandardCharsets.UTF_8);
			return "";
		} catch (IOException broken) {
			NpcStudio.LOGGER.warn("Could not write the console: {}", broken.toString());
			return broken.getMessage() == null ? broken.toString() : broken.getMessage();
		}
	}
}
