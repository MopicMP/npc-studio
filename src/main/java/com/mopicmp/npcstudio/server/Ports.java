package com.mopicmp.npcstudio.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * Whether a port can be had.
 *
 * Used for two different questions. The game port is one somebody chose, and a
 * clash with it has to be reported rather than worked around — moving it
 * silently would change the address their friends were given. The command port
 * is ours, nobody types it, and it may be any free one.
 *
 * A free port is not a promise. Between the check and the server binding, the
 * port can be taken by something else; this narrows the window, it does not
 * close it. The report that matters is still the server's own failure to bind,
 * which is why the console file is opened before the process starts.
 */
public final class Ports {

	private Ports() {
	}

	/** Where our own command port is looked for, above the range programs usually take. */
	private static final int RCON_FROM = 25575;

	private static final int RCON_TO = 25675;

	public static boolean free(int port) {
		if (port < 1 || port > 65535) return false;
		try (ServerSocket socket = new ServerSocket()) {
			// Without this, a port left in TIME_WAIT by a server that has just
			// stopped reads as free on some systems and as taken on others.
			socket.setReuseAddress(false);
			socket.bind(new InetSocketAddress((InetAddress) null, port), 1);
			return true;
		} catch (IOException taken) {
			return false;
		}
	}

	/** A free port for the command channel, or zero if the whole range is busy. */
	public static int pickRcon() {
		for (int port = RCON_FROM; port <= RCON_TO; port++) {
			if (free(port)) return port;
		}
		return 0;
	}
}
