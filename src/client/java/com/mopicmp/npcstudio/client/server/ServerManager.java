package com.mopicmp.npcstudio.client.server;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.server.CoreCatalog;
import com.mopicmp.npcstudio.server.LogTail;
import com.mopicmp.npcstudio.server.ManagedServer;
import com.mopicmp.npcstudio.server.Rcon;
import com.mopicmp.npcstudio.server.ServerCreation;
import com.mopicmp.npcstudio.server.ServerProcess;
import com.mopicmp.npcstudio.server.ServerStore;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Everything the screen needs, done off the thread that draws.
 *
 * The rule this class exists to keep: <b>no file, no socket and no process from
 * the render thread.</b> Every one of them can block — a command channel that
 * waits ten seconds for an answer, a version list from the other side of the
 * world, a folder on a sleeping disk — and blocking there does not slow the
 * screen down, it stops the game.
 *
 * So the screen only ever reads fields and calls methods that return at once,
 * and the answers arrive later on the client thread through
 * {@link Minecraft#execute}. One worker thread rather than a pool: these are all
 * about the same handful of files and one server, and doing two of them at once
 * buys nothing and costs a race.
 *
 * The command channel is opened per command rather than kept. A kept connection
 * has to survive the server stopping, restarting, and the machine sleeping,
 * which is three states to get right in exchange for a few milliseconds on a
 * loopback socket.
 */
public final class ServerManager {

	/** How much console to keep. Enough to scroll back through a start-up. */
	private static final int CONSOLE_LINES = 2000;

	/** Ticks between looks at the console and the process. Twice a second. */
	private static final int EVERY = 10;

	private static ServerManager instance;

	public static ServerManager get() {
		if (instance == null) instance = new ServerManager();
		return instance;
	}

	private final ExecutorService worker = daemon("npc-studio-server");

	/**
	 * A thread of its own for permissions, and not for tidiness.
	 *
	 * Everything else this class does on a background thread is short: read a
	 * folder, parse a file, send one command. Asking LuckPerms to export is not —
	 * it is a command, then a wait on somebody else's plugin writing a file, and
	 * the wait is capped at ten seconds. On the one worker that would stop the
	 * worlds, the addons, the players and the configs from being read for as long
	 * as it took, and the rest of the window would simply go quiet.
	 *
	 * <p>Still a single thread, because two exports at once is a thing LuckPerms
	 * refuses anyway: it wants one at a time and says so.
	 */
	private final ExecutorService rightsWorker = daemon("npc-studio-rights");

	/**
	 * Looking at the console and the process, on a thread of its own.
	 *
	 * Separate from the worker because of what happened when it was not: pressing
	 * stop put a job on the queue that waited a minute for the world to save, and
	 * everything behind it waited too — the console stopped moving and the
	 * settings said "reading" until the shutdown finished. One slow thing must
	 * not be able to stop the window from saying what is happening; watching is
	 * exactly the thing that has to keep working while something slow is going on.
	 */
	private final ExecutorService poller = daemon("npc-studio-watch");

	/**
	 * Pictures, on threads of their own and several at a time.
	 *
	 * They used to queue behind everything else on the worker, and a screenful of
	 * twenty icons is twenty requests across the world — so reaching the bottom
	 * of the list meant waiting for all of them before the next page could even
	 * be asked for. They are also the one thing here that is worth doing in
	 * parallel: each is a small independent fetch, and three at a time is polite
	 * to somebody else's CDN while being three times faster than one.
	 */
	private final ExecutorService icons = Executors.newFixedThreadPool(3, runnable -> {
		Thread thread = new Thread(runnable, "npc-studio-icons");
		thread.setDaemon(true);
		// Below everything that a person is waiting on: a missing picture is a
		// letter in a tile, a missing search result is an empty screen.
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});

	private static ExecutorService daemon(String name) {
		return Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, name);
			// A daemon, so a wedged download cannot keep the game from closing.
			thread.setDaemon(true);
			return thread;
		});
	}

	private ServerStore store;
	private CoreCatalog catalog;

	/**
	 * What a server is doing, which is four states and not a switch.
	 *
	 * "Running" is the wrong question and asking it produced a real fault: a
	 * process exists some twenty seconds before the server will accept anybody,
	 * and a join button that appears the moment the process does sends people
	 * into a connection that is refused. The difference between {@link #STARTING}
	 * and {@link #UP} is the whole point of this enum.
	 */
	public enum State {
		/** No process of ours. */
		DOWN,
		/** A process, but it has not said it is ready. */
		STARTING,
		/** Ready: it has finished loading and the command channel answers. */
		UP,
		/** It has been asked to stop and is saving. */
		STOPPING
	}

	/** What the server says when it has finished starting, in every version. */
	private static final String READY = "For help, type";

	/**
	 * How far along the making of a world is, taken from the core's own words.
	 *
	 * The server counts the spawn area out loud — "Preparing spawn area: 42%" —
	 * and that is the long part of making a world. Reading it is the difference
	 * between a bar that means something and a bar that spins to look busy; when
	 * the line is not there the number stays at minus one and the strip says only
	 * that something is happening, which is all it knows.
	 */
	private static final java.util.regex.Pattern SPAWN =
		java.util.regex.Pattern.compile("Preparing spawn area:\\s*(\\d{1,3})%");

	private volatile int madePercent = -1;
	private volatile String madeStep = "";

	public int madePercent() {
		return madePercent;
	}

	/** What the making is doing right now, in words, for whoever is looking. */
	public String makingStep() {
		return madeStep;
	}

	private void noticeProgress(String line) {
		if (!making) return;
		var found = SPAWN.matcher(line);
		if (found.find()) madePercent = Math.clamp(Integer.parseInt(found.group(1)), 0, 100);
	}

	private ManagedServer watched;
	private LogTail tail;
	private final Deque<String> console = new ArrayDeque<>();
	private volatile State state = State.DOWN;
	private volatile boolean probed;

	/** Whether the next look is the backlog of an old log rather than news. */
	private volatile boolean firstLook = true;
	private volatile java.util.Set<String> up = java.util.Set.of();
	private volatile String status = "";

	/**
	 * A failure worth stopping somebody for, waiting to be shown once.
	 *
	 * Taken rather than read, because it is shown in a window: leaving it set
	 * would put the window back the moment it was closed.
	 */
	private volatile String failure;
	private final AtomicBoolean busy = new AtomicBoolean();
	private int ticks;

	private ServerManager() {
		// Read once, off the drawing thread, so that the browser can ask "is there
		// a key for this source" sixty times a second without touching a disk.
		worker.execute(this::rememberKeys);
	}

	// ------------------------------------------------------------------ keys

	private com.mopicmp.npcstudio.server.ApiKeys keys;

	/**
	 * Which sources have a key, and never which key.
	 *
	 * The screen is told the answer to a yes-or-no question and to nothing else:
	 * a value that is never handed to the drawing code cannot end up in a tooltip,
	 * a log line or a screenshot by accident.
	 */
	private volatile java.util.Set<String> keysPresent = java.util.Set.of();

	private synchronized com.mopicmp.npcstudio.server.ApiKeys keys() {
		if (keys == null) keys = com.mopicmp.npcstudio.server.ApiKeys.forGame();
		return keys;
	}

	private void rememberKeys() {
		java.util.Set<String> present = keys().present();
		Minecraft.getInstance().execute(() -> keysPresent = present);
	}

	public boolean hasKey(String source) {
		return keysPresent.contains(source);
	}

	/** The last four characters of a key, which is all an interface may know. */
	public void maskedKey(String source, Consumer<String> got) {
		worker.execute(() -> {
			String masked = com.mopicmp.npcstudio.server.ApiKeys.masked(keys().get(source));
			Minecraft.getInstance().execute(() -> got.accept(masked));
		});
	}

	/** Keep one, or forget it when what was typed is empty. */
	public void putKey(String source, String key, Runnable then, Consumer<String> failed) {
		worker.execute(() -> {
			try {
				keys().put(source, key);
				rememberKeys();
				Minecraft.getInstance().execute(then);
			} catch (IOException | RuntimeException unwritable) {
				String said = unwritable.getMessage() == null
					? unwritable.toString() : unwritable.getMessage();
				// The message names the file and never what was being written to it.
				Minecraft.getInstance().execute(() -> failed.accept(said));
			}
		});
	}

	public void dropKey(String source, Runnable then, Consumer<String> failed) {
		putKey(source, "", then, failed);
	}

	/**
	 * The catalogue behind a source id, or a refusal that says which is missing.
	 *
	 * Called on the worker, because building one reads the key from a file.
	 */
	private com.mopicmp.npcstudio.server.Catalogue catalogueFor(String source) throws IOException {
		var which = catalog().source(source)
			.orElseThrow(() -> new IOException("no such place to look as " + source));
		return com.mopicmp.npcstudio.server.Catalogue
			.of(which, catalog().downloads(), keys().get(which.id()))
			.orElseThrow(() -> new IOException(which.needsKey() && !keys().has(which.id())
				? which.name() + " needs a key of your own"
				: which.name() + " cannot be used yet"));
	}

	/**
	 * Whether a jar we installed came from this project of this catalogue.
	 *
	 * Two catalogues number their projects independently, so an id on its own no
	 * longer identifies anything — the note beside the jars carries the source in
	 * front of it. One written before there was a second source has no colon in
	 * it and means Modrinth, which is what it meant when it was written.
	 */
	public static boolean sameProject(String recorded, String source, String id) {
		if (recorded == null || recorded.isBlank()) return false;
		int colon = recorded.indexOf(':');
		String was = colon < 0 ? "modrinth" : recorded.substring(0, colon);
		String which = colon < 0 ? recorded : recorded.substring(colon + 1);
		return was.equals(source) && which.equals(id);
	}

	// ------------------------------------------------------------------ state

	/** Synchronised because the worker reads the list while the screen may be making it. */
	public synchronized ServerStore store() {
		if (store == null) store = ServerStore.forGame();
		return store;
	}

	public CoreCatalog catalog() {
		if (catalog == null) {
			catalog = CoreCatalog.load(
				FabricLoader.getInstance().getConfigDir().resolve("npc_studio_cores.json"));
		}
		return catalog;
	}

	public Path baseDirectory() {
		return ServerStore.defaultDirectory();
	}

	public List<ManagedServer> servers() {
		return store().all();
	}

	/** Read the list again, after something outside this session may have changed it. */
	public synchronized void reload() {
		store = null;
	}

	/**
	 * Whether that server is up, for the list rather than for the one being watched.
	 *
	 * Answered from a set refreshed on the same half-second look, because the
	 * honest answer costs a system call and the list is drawn sixty times a
	 * second. A process id on its own is not the answer: it only says we started
	 * something once — see the note in {@code ServerProcess} about ids being
	 * reused.
	 */
	public boolean up(ManagedServer server) {
		return up.contains(server.id);
	}

	public ManagedServer watched() {
		return watched;
	}

	public State state() {
		return state;
	}

	/** Whether there is a process at all — not whether anybody can join. */
	public boolean running() {
		return state == State.STARTING || state == State.UP || state == State.STOPPING;
	}

	/** Whether it has finished starting. The only state in which joining works. */
	public boolean ready() {
		return state == State.UP;
	}

	/** The last thing that happened, for the line under the card. */
	public String status() {
		return status;
	}

	public void say(String what) {
		status = what;
	}

	/** The last failure worth a window, and it is only handed over once. */
	public String takeFailure() {
		String what = failure;
		failure = null;
		return what;
	}

	/**
	 * Follow this server's console and state, and forget the previous one.
	 *
	 * The console starts from the tail of the file rather than its beginning: a
	 * server that has been up for a day has a log nobody wants replayed into a
	 * scrolling panel.
	 */
	public void watch(ManagedServer server) {
		if (watched == server) return;
		watched = server;
		synchronized (console) {
			console.clear();
			lastShape = "";
			repeated = 0;
		}
		tail = server == null ? null : LogTail.recent(server.consolePath());
		state = State.DOWN;
		probed = false;
		firstLook = true;
		status = "";
		ticks = EVERY;
	}

	/** A copy of the console, safe to draw from. */
	public List<String> console() {
		synchronized (console) {
			return new ArrayList<>(console);
		}
	}

	/**
	 * Forget what is on the screen, and keep following the file.
	 *
	 * Only what is held here goes: the server's own log is its own, and a button
	 * in this window that deleted it would be a button that throws away the
	 * evidence somebody is about to be asked for.
	 */
	public void clearConsole() {
		synchronized (console) {
			console.clear();
			lastShape = "";
			repeated = 0;
		}
	}

	/**
	 * Add lines to the console, folding a line that keeps saying the same thing.
	 *
	 * A server shutting down slowly writes "Waited 26 seconds attempting force
	 * stop!", then 27, then 28, once a second until it is done — forty lines that
	 * are one fact. Folded, they are one line with a count on it, and the line
	 * before them is still on the screen where somebody can read it.
	 *
	 * <p>Only the view is folded. The server's own log file has every line, and the
	 * button that opens it is right there — this window is not the record.
	 */
	private void add(List<String> lines) {
		synchronized (console) {
			for (String line : lines) {
				String shape = shapeOf(line);
				if (!console.isEmpty() && shape.equals(lastShape) && !shape.isBlank()) {
					repeated++;
					console.removeLast();
					console.addLast(line + "   ×" + (repeated + 1));
				} else {
					lastShape = shape;
					repeated = 0;
					console.addLast(line);
				}
				while (console.size() > CONSOLE_LINES) console.removeFirst();
			}
		}
	}

	private String lastShape = "";
	private int repeated;

	/**
	 * What a line looks like with its numbers taken out.
	 *
	 * The numbers are what change in a repeating line — the clock at the front, the
	 * count of seconds, the number of the thread — and what stays is what the line
	 * is about. Two lines with the same shape are the same line said twice.
	 */
	private static String shapeOf(String line) {
		return line.replaceAll("\\d+", "#");
	}

	private void note(String line) {
		add(List.of(line));
	}

	// ------------------------------------------------------------------ ticking

	/**
	 * Called every client tick; schedules a look twice a second.
	 *
	 * Guarded, because a look that takes longer than the gap between looks would
	 * otherwise queue up behind itself for ever — which is exactly what happens
	 * when the disk the server lives on goes to sleep.
	 */
	public void tick() {
		if (++ticks < EVERY) return;
		ticks = 0;
		if (!busy.compareAndSet(false, true)) return;

		// With nothing being watched there is still a question worth asking: which
		// of the servers on the list are up. It used to be answered only as a
		// by-product of watching one, so the list said every server was off until
		// somebody opened one of them — which after the game had been closed and
		// opened again was every time, on servers that had never stopped running.
		if (watched == null) {
			poller.execute(() -> {
				try {
					java.util.Set<String> found = whichAreUp();
					Minecraft.getInstance().execute(() -> up = found);
				} finally {
					busy.set(false);
				}
			});
			return;
		}

		ManagedServer server = watched;
		LogTail following = tail;
		State was = state;
		boolean history = firstLook;
		firstLook = false;
		poller.execute(() -> {
			try {
				// Ours, or anybody's. The second half is what stops the window
				// saying "off" about a server the multiplayer list is showing as up
				// — which is what happened whenever the process behind it could no
				// longer be tied to the note this mod kept about it.
				boolean ours = ServerProcess.running(server);
				java.util.Set<String> found = whichAreUp();
				// The same answer the list gets, rather than a second round of
				// questions about the same server twice a second.
				boolean alive = ours || found.contains(server.id);
				List<String> lines = following == null ? List.of() : following.poll();

				// A server that was already up when this screen opened has said its
				// ready line long ago and will not say it again, so it is asked
				// once instead — and only once, because every connection is a pair
				// of lines in its log.
				boolean answers = false;
				if (alive && was == State.DOWN && !probed) {
					probed = true;
					answers = answersRcon(server);
				}
				boolean ready = answers;
				for (String line : lines) {
					if (line.contains(READY)) ready = true;
					noticeProgress(line);
				}
				boolean sawReady = ready;

				Minecraft.getInstance().execute(() -> {
					up = java.util.Set.copyOf(found);
					if (watched != server) return;
					add(lines);
					// A failure this specific is not left in the console to be
					// found. The JVM says it in a page of native text with a DOS
					// error number in it, in the middle of everything else, and
					// the server is already gone by the time anybody scrolls
					// back — so it is caught here and put in front of them.
					//
					// Except on the first look, which is the tail of a log file
					// that may be weeks old. Reading history as news is how the
					// window about memory appeared every time the screen opened,
					// for a server that had run out of it once, long ago.
					for (String line : history ? List.<String>of() : lines) {
						if (line.contains("insufficient memory for the Java Runtime")
							|| line.contains("Could not reserve enough space")
							|| line.contains("commit_memory")) {
							failure = "memory";
						}
					}
					if (!alive) {
						if (server.pid > 0) {
							ServerProcess.forget(server);
							worker.execute(() -> store().save());
						}
						state = State.DOWN;
					} else if (!ours && server.pid > 0) {
						// It answers, but the process we were told about is gone: the
						// identifier belongs to something that has stopped, and
						// keeping it would offer to stop the wrong thing.
						ServerProcess.forget(server);
						worker.execute(() -> store().save());
						state = State.UP;
					} else if (state == State.STOPPING) {
						// Leaving takes as long as it takes; nothing in the log
						// turns it back into a running server.
						state = State.STOPPING;
					} else if (sawReady) {
						state = State.UP;
					} else if (state == State.DOWN) {
						state = State.STARTING;
					}
				});
			} catch (IOException unreadable) {
				NpcStudio.LOGGER.debug("Could not read the console of {}: {}",
					server.id, unreadable.toString());
			} finally {
				busy.set(false);
			}
		});
	}

	/**
	 * Which of the stored servers have a process of their own still running.
	 *
	 * Off the drawing thread: it asks the operating system about one process per
	 * server. The identifier alone is not enough — see
	 * {@link ServerProcess#attach} — so a server whose id has since been given to
	 * something else counts as down, which is the right answer.
	 */
	/**
	 * Which servers are up: the process where there is one, the network otherwise.
	 *
	 * <b>The network half is kept on a leash.</b> Asking a port costs a connection,
	 * and telling two servers that share a port apart costs a login on the command
	 * channel — which the server writes two lines about, every time. Done on every
	 * look, that is six lines a second in somebody's console for a question nobody
	 * asked, and it is what this window was doing. So: our own processes are free
	 * and are checked every time; anything that needs the network is asked at most
	 * once every few seconds and the answer is remembered in between.
	 */
	private volatile java.util.Set<String> upByNetwork = java.util.Set.of();
	private volatile long networkLookedAt;

	/** Rare enough not to fill a log, often enough to notice a server going down. */
	private static final long NETWORK_EVERY_MS = 5_000;

	/** How often one server may be asked to prove itself over the command channel. */
	private static final long RCON_EVERY_MS = 60_000;

	private final java.util.Map<String, Long> loggedInAt = new java.util.concurrent.ConcurrentHashMap<>();

	private java.util.Set<String> whichAreUp() {
		java.util.Set<String> found = new java.util.HashSet<>();
		List<ManagedServer> unknown = new ArrayList<>();
		java.util.Set<Integer> taken = new java.util.HashSet<>();
		for (ManagedServer other : store().all()) {
			if (ServerProcess.running(other)) {
				found.add(other.id);
				// That port has an owner and we can see it. Everything else on the
				// same port is answered: not this one. Without this the two stopped
				// servers sharing 25565 with a running one were asked over and over,
				// for ever, and each asking put two lines in somebody's console —
				// which is what "it still floods, only slower" was.
				taken.add(other.port);
			} else {
				unknown.add(other);
			}
		}
		unknown.removeIf(server -> taken.contains(server.port));
		if (unknown.isEmpty()) {
			upByNetwork = java.util.Set.of();
			return java.util.Set.copyOf(found);
		}
		long now = System.currentTimeMillis();
		// Not while one is shutting down. A server on its way out is closing its
		// command channel and waiting for the threads that use it — Paper says so
		// once a second, "Waited N seconds attempting force stop!" — and a login
		// arriving in the middle of that is a new thread for it to wait for.
		if (now - networkLookedAt >= NETWORK_EVERY_MS && state != State.STOPPING) {
			networkLookedAt = now;
			upByNetwork = askTheNetwork(unknown);
		}
		found.addAll(upByNetwork);
		return java.util.Set.copyOf(found);
	}

	/**
	 * Which of these are answering, without asking the same port twice.
	 *
	 * A port that answers and belongs to one server is that server. A port shared
	 * by several — which is the ordinary case here, since every server made in this
	 * window is offered 25565 and only one of them can be up at a time — proves only
	 * that one of them is up, and which one is settled by the command channel, whose
	 * password belongs to a single server. That is the expensive question, so it
	 * stops at the first server that answers it.
	 */
	private java.util.Set<String> askTheNetwork(List<ManagedServer> unknown) {
		java.util.Set<String> found = new java.util.HashSet<>();
		java.util.Map<Integer, com.mopicmp.npcstudio.server.Ping.Status> asked =
			new java.util.HashMap<>();
		java.util.Set<Integer> settled = new java.util.HashSet<>();
		for (ManagedServer server : unknown) {
			var status = asked.computeIfAbsent(server.port,
				port -> com.mopicmp.npcstudio.server.Ping.status("127.0.0.1", port));
			if (status == null) continue;
			if (!sharesPort(server)) {
				found.add(server.id);
				continue;
			}
			// Somebody has already been shown to own this port in this pass.
			if (settled.contains(server.port)) continue;
			// A server whose own message of the day is not the one being said is not
			// the one saying it. This is a "no" without asking anything of anybody,
			// and it is the common case: two servers rarely carry the same greeting.
			String mine = plain(motdOf(server));
			if (!mine.isBlank() && !mine.equals(plain(status.motd()))) continue;

			// The answer itself may say which one it is. A status carries the
			// message of the day and how many may join, and those come out of each
			// server's own properties — so when they tell these servers apart, the
			// question is already answered and nothing else need be asked.
			String owner = whoseStatus(status, server.port);
			if (owner != null) {
				found.add(owner);
				settled.add(server.port);
				continue;
			}
			// Otherwise the command channel, whose password belongs to one server.
			// This is the expensive one — the server writes two lines about every
			// login — which is why it is the last thing tried and not the first.
			if (server.rconPassword == null || server.rconPassword.isBlank()) continue;
			// And not oftener than once a minute per server. A login that fails is
			// two lines in a log and nothing learned; asking again five seconds
			// later learns nothing again.
			long now = System.currentTimeMillis();
			Long lastLogin = loggedInAt.get(server.id);
			if (lastLogin != null && now - lastLogin < RCON_EVERY_MS) continue;
			loggedInAt.put(server.id, now);
			if (answersRcon(server)) {
				found.add(server.id);
				settled.add(server.port);
				loggedInAt.remove(server.id);
			}
		}
		return java.util.Set.copyOf(found);
	}

	/**
	 * Which server this answer belongs to, when the answer itself makes that plain.
	 *
	 * Only when exactly one of the servers on that port could have said it. Two
	 * servers both left at "A Minecraft Server" say nothing about which is up, and
	 * answering anyway would put a green light on the wrong row.
	 */
	private String whoseStatus(com.mopicmp.npcstudio.server.Ping.Status status, int port) {
		String said = plain(status.motd());
		if (said.isBlank()) return null;
		String only = null;
		for (ManagedServer server : store().all()) {
			if (server.port != port) continue;
			String mine = plain(motdOf(server));
			if (mine.isBlank() || !mine.equals(said)) continue;
			if (only != null) return null;
			only = server.id;
		}
		return only;
	}

	/** Colour codes and edges off, since one side writes them and the other may not. */
	private static String plain(String text) {
		return text.replaceAll("§.", "").strip();
	}

	/**
	 * What a server's properties say its message of the day is.
	 *
	 * Kept between looks with the moment the file was last written, because this is
	 * asked on a timer and the file changes about once a month.
	 */
	private final java.util.Map<String, String[]> motds = new java.util.concurrent.ConcurrentHashMap<>();

	private String motdOf(ManagedServer server) {
		try {
			java.nio.file.Path file = server.propertiesPath();
			if (!java.nio.file.Files.isRegularFile(file)) return "";
			String when = String.valueOf(java.nio.file.Files.getLastModifiedTime(file).toMillis());
			String[] remembered = motds.get(server.id);
			if (remembered != null && remembered[0].equals(when)) return remembered[1];
			String said = com.mopicmp.npcstudio.server.PropertiesFile.read(file).getOr("motd", "");
			motds.put(server.id, new String[] {when, said});
			return said;
		} catch (IOException | RuntimeException unreadable) {
			return "";
		}
	}

	private boolean sharesPort(ManagedServer server) {
		for (ManagedServer other : store().all()) {
			if (!other.id.equals(server.id) && other.port == server.port) return true;
		}
		return false;
	}

	/** Whether the command channel answers, which is what "ready" means to us. */
	private boolean answersRcon(ManagedServer server) {
		try (Rcon rcon = Rcon.connect("127.0.0.1", server.rconPort, server.rconPassword)) {
			rcon.command("list");
			return true;
		} catch (IOException notYet) {
			return false;
		}
	}

	// ------------------------------------------------------------------ doing

	/**
	 * Start it, and say what happened either way.
	 *
	 * The memory is checked before the process rather than after: with the floor
	 * equal to the ceiling the JVM asks the operating system for the whole amount
	 * at once, and if it is not there the failure is a page of native error text
	 * about {@code os::commit_memory} and a DOS error number. That is a true
	 * description of what happened and no use to anybody.
	 */
	public void start(ManagedServer server) {
		if (state != State.DOWN) return;
		state = State.STARTING;
		probed = true;
		status = "";
		worker.execute(() -> {
			try {
				ServerProcess.start(server);
				store().save();
				done(server, "");
			} catch (IOException | RuntimeException failed) {
				Minecraft.getInstance().execute(() -> state = State.DOWN);
				done(server, failed.getMessage());
				NpcStudio.LOGGER.warn("Could not start '{}': {}", server.id, failed.toString());
			}
		});
	}

	/**
	 * Ask it to stop and wait for the save.
	 *
	 * A minute of patience, because a server with a large world spends it writing
	 * chunks, and the alternative to waiting is losing them.
	 */
	/**
	 * Ask it to stop, and do not wait for it here.
	 *
	 * The waiting used to happen on the worker thread, for up to a minute, and
	 * everything else queued behind it — which is how pressing stop made the
	 * console freeze and the settings read for ever. The command goes out, the
	 * state says what is happening, and the ordinary half-second look notices
	 * when the process is gone. Nothing has to sit and watch it die.
	 */
	public void stop(ManagedServer server) {
		if (state == State.STOPPING) return;
		state = State.STOPPING;
		status = "";
		worker.execute(() -> {
			try (Rcon rcon = Rcon.connect("127.0.0.1", server.rconPort, server.rconPassword)) {
				rcon.command("stop");
			} catch (IOException unreachable) {
				NpcStudio.LOGGER.warn("Could not tell '{}' to stop: {}",
					server.id, unreachable.getMessage());
				Minecraft.getInstance().execute(() -> {
					if (watched == server && state == State.STOPPING) state = State.UP;
				});
				done(server, "could not reach the server to stop it");
			}
		});
	}

	/** Run one command and put both it and the answer in the console. */
	public void send(ManagedServer server, String command) {
		if (command.isBlank()) return;
		note("> " + command);
		worker.execute(() -> {
			try (Rcon rcon = Rcon.connect("127.0.0.1", server.rconPort, server.rconPassword)) {
				String answer = rcon.command(command);
				Minecraft.getInstance().execute(() -> {
					if (watched != server) return;
					// The server writes its own copy of most answers into the log,
					// so only what came back down the socket is added — otherwise
					// every command appears twice.
					for (String line : answer.split("\n")) {
						if (!line.isBlank()) note(line);
					}
				});
			} catch (IOException unreachable) {
				done(server, "no answer: " + unreachable.getMessage());
			}
		});
	}

	/**
	 * Create one, reporting progress as it downloads.
	 *
	 * The agreement has already been given by the time this is called — see
	 * {@code Eula} — and this does not check it again because
	 * {@link ServerCreation} does, which is the place it cannot be walked around.
	 */
	public void create(ServerCreation.Request request, Consumer<String> progress,
			Consumer<ManagedServer> made, Consumer<String> failed) {
		worker.execute(() -> {
			try {
				ManagedServer server = ServerCreation.create(store(), baseDirectory(), catalog(),
					request, (done, total) -> {
						String said = total > 0
							? "downloading — " + (done * 100 / total) + "%"
							: "downloading — " + done / 1024 + " KB";
						Minecraft.getInstance().execute(() -> progress.accept(said));
					});
				Minecraft.getInstance().execute(() -> made.accept(server));
			} catch (Exception broken) {
				String said = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				NpcStudio.LOGGER.warn("Could not create a server: {}", broken.toString());
				Minecraft.getInstance().execute(() -> failed.accept(said));
			}
		});
	}

	/**
	 * Put a different core or a different version into an existing server.
	 *
	 * Not a setting, whatever the interface makes it look like. The world folder
	 * stays where it is, and a world opened by a newer version is written in the
	 * newer format and will not open in the older one again — so the caller has
	 * to have said that out loud before this is reached, and this refuses to run
	 * while the server is up because replacing a jar under a running process
	 * produces a failure nobody can read.
	 */
	public void reinstall(ManagedServer server, com.mopicmp.npcstudio.server.CoreCatalog.Core core,
			String version, Consumer<String> progress, Runnable done, Consumer<String> failed) {
		if (state != State.DOWN) {
			failed.accept("stop the server first");
			return;
		}
		String wasJar = server.jar;
		worker.execute(() -> {
			try {
				// A copy first, and this is the reason the whole backup half exists.
				// A world opened by a newer version is written in the newer format
				// and the older one will not read it again — so this is the one
				// action in the window that cannot be undone by repeating it
				// backwards, and it is exactly where nobody thinks to make a copy.
				String wanted = com.mopicmp.npcstudio.server.Worlds.currentName(server);
				for (var world : com.mopicmp.npcstudio.server.Worlds.found(server)) {
					if (!world.is(wanted)) continue;
					Minecraft.getInstance().execute(() -> progress.accept(
						net.minecraft.network.chat.Component
							.translatable("npc_studio.server.worlds.saving_first").getString()));
					com.mopicmp.npcstudio.server.Backups.make(server, world, savingOf(server),
						com.mopicmp.npcstudio.server.Downloads.Progress.IGNORED);
					break;
				}
				String jar = com.mopicmp.npcstudio.server.CoreProvider
					.of(core, catalog().downloads())
					.orElseThrow(() -> new IOException("nothing can install " + core.name()))
					.install(server.path(), version, (at, total) -> {
						String said = total > 0 ? (at * 100 / total) + "%" : at / 1024 + " KB";
						Minecraft.getInstance().execute(() -> progress.accept(said));
					});
				if (!jar.equals(wasJar) && !wasJar.isBlank()) {
					// The old one is left only if it is somehow outside the folder,
					// which it cannot be — but deleting by a stored name is exactly
					// the shape the security notes warn about, so it is checked.
					java.nio.file.Path old = server.path().resolve(wasJar).normalize();
					if (old.startsWith(server.path().toAbsolutePath().normalize())
						|| old.startsWith(server.path().normalize())) {
						java.nio.file.Files.deleteIfExists(old);
					}
				}
				server.core = core.id();
				server.gameVersion = version;
				server.jar = jar;
				store().save();
				Minecraft.getInstance().execute(done);
			} catch (Exception broken) {
				String said = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				NpcStudio.LOGGER.warn("Could not re-install '{}': {}", server.id, broken.toString());
				Minecraft.getInstance().execute(() -> failed.accept(said));
			}
		});
	}

	/** What is installed on a server, read from the jars off the drawing thread. */
	public void addons(ManagedServer server, Consumer<List<com.mopicmp.npcstudio.server.Addon>> got) {
		worker.execute(() -> {
			List<com.mopicmp.npcstudio.server.Addon> found =
				com.mopicmp.npcstudio.server.Addons.installed(server);
			Minecraft.getInstance().execute(() -> got.accept(found));
		});
	}

	/**
	 * Put a jar in, or say why not.
	 *
	 * The checking happens where the copying happens rather than in the screen —
	 * a client mod refused by the interface is a client mod that goes in the next
	 * time anything else calls this.
	 */
	public void installAddon(ManagedServer server, java.nio.file.Path jar, Consumer<String> said) {
		worker.execute(() -> {
			String answer = com.mopicmp.npcstudio.server.Addons.install(server, jar);
			Minecraft.getInstance().execute(() -> said.accept(answer));
		});
	}

	/**
	 * Put in whatever a file holds: one jar, or an archive of them.
	 *
	 * One method for both because from the outside they are one action — somebody
	 * chose files and wants them on the server — and which of the two a given file
	 * is is a question about the file, not about what was meant.
	 */
	public void installFile(ManagedServer server, java.nio.file.Path file,
			Consumer<String> progress, Consumer<com.mopicmp.npcstudio.server.Packs.Result> done,
			Consumer<String> failed) {
		worker.execute(() -> {
			try {
				if (!com.mopicmp.npcstudio.server.Packs.isArchive(file)) {
					String answer = com.mopicmp.npcstudio.server.Addons.install(server, file);
					var result = answer.isEmpty()
						? new com.mopicmp.npcstudio.server.Packs.Result(
							List.of(file.getFileName().toString()), List.of())
						: new com.mopicmp.npcstudio.server.Packs.Result(
							List.of(), List.of(file.getFileName() + " — " + answer));
					Minecraft.getInstance().execute(() -> done.accept(result));
					return;
				}
				var result = com.mopicmp.npcstudio.server.Packs.install(server, file,
					catalog().downloads(), (at, total) -> {
						String said = at / 1024 + " KB";
						Minecraft.getInstance().execute(() -> progress.accept(said));
					});
				Minecraft.getInstance().execute(() -> done.accept(result));
			} catch (IOException | RuntimeException broken) {
				String said = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				NpcStudio.LOGGER.warn("Could not unpack {}: {}", file, broken.toString());
				Minecraft.getInstance().execute(() -> failed.accept(said));
			}
		});
	}

	public void removeAddon(ManagedServer server, String file, Consumer<String> said) {
		worker.execute(() -> {
			String answer = com.mopicmp.npcstudio.server.Addons.remove(server, file);
			if (answer.isEmpty()) {
				com.mopicmp.npcstudio.server.InstalledIndex.drop(server, file);
			}
			Minecraft.getInstance().execute(() -> said.accept(answer));
		});
	}

	/**
	 * Which project a jar on disk came from, asked by its checksum.
	 *
	 * For the ones nobody wrote a note about: a mod the person installed by hand,
	 * or one of this client's own mods. The file is the question, so the answer is
	 * exact — matching by name would be guessing, and the names of a project, its
	 * jar and the mod inside it are routinely three different strings.
	 *
	 * Answers an empty string for anything the catalogue has never seen, which is
	 * an ordinary outcome and not a fault.
	 */
	public void projectOf(java.nio.file.Path jar, String source, Consumer<String> got) {
		worker.execute(() -> {
			String found = "";
			try {
				String sha1 = sha1(jar);
				if (!sha1.isEmpty()) found = catalogueFor(source).projectByHash(sha1);
			} catch (IOException | RuntimeException unknown) {
				NpcStudio.LOGGER.debug("No catalogue entry for {}: {}", jar, unknown.toString());
			}
			String answer = found;
			Minecraft.getInstance().execute(() -> got.accept(answer));
		});
	}

	private static String sha1(java.nio.file.Path file) throws IOException {
		try {
			java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
			try (var in = java.nio.file.Files.newInputStream(file)) {
				byte[] chunk = new byte[64 * 1024];
				int read;
				while ((read = in.read(chunk)) > 0) digest.update(chunk, 0, read);
			}
			return java.util.HexFormat.of().formatHex(digest.digest());
		} catch (java.security.NoSuchAlgorithmException impossible) {
			return "";
		}
	}

	/** Which jars came from which catalogue projects, for this server. */
	public void index(ManagedServer server, Consumer<java.util.Map<String, String>> got) {
		worker.execute(() -> {
			var index = com.mopicmp.npcstudio.server.InstalledIndex.read(server);
			Minecraft.getInstance().execute(() -> got.accept(index));
		});
	}

	/**
	 * Search the catalogue for something that fits this server.
	 *
	 * Narrowed by the server's own version and loader before it is asked, so what
	 * comes back is what can be installed rather than everything that exists.
	 */
	public void browse(ManagedServer server, String query, int offset, String order, String loader,
			String source, Consumer<com.mopicmp.npcstudio.server.Catalogue.Page> got,
			Consumer<String> failed) {
		worker.execute(() -> {
			try {
				var page = catalogueFor(source)
					.search(query, loader, server.gameVersion, offset, order);
				Minecraft.getInstance().execute(() -> got.accept(page));
			} catch (IOException | RuntimeException broken) {
				String said = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				Minecraft.getInstance().execute(() -> failed.accept(said));
			}
		});
	}

	/**
	 * Fetch a picture for a list row, on the thread that is allowed to wait.
	 *
	 * Icons are fetched one at a time behind everything else on the worker, which
	 * is the right priority: a list without pictures is readable and a list that
	 * arrives late is not.
	 */
	public void fetchIcon(String key, String url) {
		fetch(key, url, 1024 * 1024);
	}

	/**
	 * The same, with room for a screenshot rather than an icon.
	 *
	 * A row icon is a few kilobytes and the bound on it is generous. A gallery
	 * picture is a full frame of somebody's game, and the same bound would cut it
	 * in half — which does not fail, it decodes to nothing, and the page shows a
	 * grey box for a reason nobody could find.
	 */
	public void fetchPicture(String key, String url) {
		fetch(key, url, 8 * 1024 * 1024);
	}

	/**
	 * Whether a picture may be fetched from where it lives, and the host if not.
	 *
	 * Asked before drawing so that an address this mod will not reach is shown as
	 * a line naming the site rather than as a box that waits forever. Answering
	 * the host rather than a yes or no is the point: "a picture at github.com"
	 * tells somebody what to do next; "picture unavailable" does not.
	 */
	public String unreachablePicture(String url) {
		try {
			java.net.URI uri = java.net.URI.create(url);
			if (pictures == null) pictures = catalog().forPictures();
			return pictures.permitted(uri) ? null
				: (uri.getHost() == null ? url : uri.getHost());
		} catch (RuntimeException notAnAddress) {
			return url;
		}
	}

	private com.mopicmp.npcstudio.server.Downloads pictures;

	private void fetch(String key, String url, int most) {
		if (!ContentIcons.wanted(key)) return;
		ContentIcons.asked(key);
		icons.execute(() -> {
			byte[] bytes = null;
			try {
				// Pictures go through a longer list of hosts than jars do — see the
				// note in CoreCatalog about why the two lists are different sizes.
				if (pictures == null) pictures = catalog().forPictures();
				bytes = pictures.bytes(java.net.URI.create(url), most);
			} catch (IOException | RuntimeException unavailable) {
				NpcStudio.LOGGER.debug("No icon from {}: {}", url, unavailable.toString());
			}
			byte[] got = bytes;
			Minecraft.getInstance().execute(() -> ContentIcons.offer(key, got));
		});
	}

	/** The picture a mod carries inside its own jar, wherever that jar is. */
	public void fetchJarIcon(String key, java.nio.file.Path jar) {
		fetchAddonIcon(key, jar, null);
	}

	/**
	 * The picture for something installed: from its jar, or from the catalogue.
	 *
	 * A Fabric mod carries one inside itself. A plugin never does — there is no
	 * such convention on that side — so the only picture that exists for it is
	 * the one on the project page it came from, and the note kept beside the jars
	 * is what says which project that was.
	 */
	public void fetchAddonIcon(String key, java.nio.file.Path jar, String project) {
		if (!ContentIcons.wanted(key)) return;
		ContentIcons.asked(key);
		icons.execute(() -> {
			byte[] bytes = com.mopicmp.npcstudio.server.Addons.icon(jar);
			if (bytes == null && project != null && !project.isBlank()) {
				try {
					// The note beside the jars says which catalogue as well as which
					// project, because two catalogues number their projects
					// independently and an id alone points at two different things.
					int colon = project.indexOf(':');
					String source = colon < 0 ? "modrinth" : project.substring(0, colon);
					String which = colon < 0 ? project : project.substring(colon + 1);
					var catalogue = catalogueFor(source);
					String url = catalogue.iconUrl(which);
					if (!url.isBlank()) bytes = catalogue.icon(url);
				} catch (IOException | RuntimeException unavailable) {
					NpcStudio.LOGGER.debug("No catalogue icon for {}: {}",
						project, unavailable.toString());
				}
			}
			byte[] got = bytes;
			Minecraft.getInstance().execute(() -> ContentIcons.offer(key, got));
		});
	}

	/**
	 * Everything about one project, for the page that opens when a row is pressed.
	 *
	 * Fetched on the worker rather than the icon threads even though it is mostly
	 * reading: somebody is waiting in front of an empty panel for this, which is
	 * exactly the thing the icon threads were given a low priority to stay out of
	 * the way of.
	 */
	public void details(ManagedServer server, com.mopicmp.npcstudio.server.Catalogue.Found what,
			String source, String loader,
			Consumer<com.mopicmp.npcstudio.server.Catalogue.Details> got,
			Consumer<String> failed) {
		worker.execute(() -> {
			try {
				var page = catalogueFor(source).details(what.id(), loader, server.gameVersion);
				Minecraft.getInstance().execute(() -> got.accept(page));
			} catch (IOException | RuntimeException broken) {
				String said = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				NpcStudio.LOGGER.warn("Could not read the page of {}: {}",
					what.title(), broken.toString());
				Minecraft.getInstance().execute(() -> failed.accept(said));
			}
		});
	}

	/**
	 * Install one particular published file rather than whichever is newest.
	 *
	 * The difference matters on a server that other people are on: "the newest"
	 * is a decision made for you at the moment you press, and somebody choosing
	 * from a list of versions has a reason — the newest one broke something, or
	 * the rest of their group is on an older one.
	 */
	public void installRelease(ManagedServer server,
			com.mopicmp.npcstudio.server.Catalogue.Found what,
			com.mopicmp.npcstudio.server.Catalogue.Release release, String source,
			Consumer<String> progress, Consumer<String> done, Consumer<String> failed) {
		installRelease(server, what, release, source, null, progress, done, failed);
	}

	/** The same, into a folder of the caller's choosing — a world's, for a datapack. */
	public void installRelease(ManagedServer server,
			com.mopicmp.npcstudio.server.Catalogue.Found what,
			com.mopicmp.npcstudio.server.Catalogue.Release release, String source, Path into,
			Consumer<String> progress, Consumer<String> done, Consumer<String> failed) {
		worker.execute(() -> {
			try {
				String name = catalogueFor(source).install(server, release, into, (at, total) -> {
					String said = total > 0 ? (at * 100 / total) + "%" : at / 1024 + " KB";
					Minecraft.getInstance().execute(() -> progress.accept(said));
				});
				com.mopicmp.npcstudio.server.InstalledIndex.put(server, name,
					source + ":" + what.id());
				Minecraft.getInstance().execute(() -> done.accept(name));
			} catch (IOException | RuntimeException broken) {
				String said = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				NpcStudio.LOGGER.warn("Could not install {}: {}", what.title(), broken.toString());
				Minecraft.getInstance().execute(() -> failed.accept(said));
			}
		});
	}

	/** Pick the right published file for this server and download it into place. */
	public void install(ManagedServer server, com.mopicmp.npcstudio.server.Catalogue.Found what,
			String source, Consumer<String> progress, Consumer<String> done,
			Consumer<String> failed) {
		install(server, what, source, null, null, progress, done, failed);
	}

	/**
	 * The same, told which shelf it is and where it lands.
	 *
	 * A datapack is asked for by a different word and put in a different folder,
	 * and both of those belong to the caller: this class knows how to download,
	 * not which world somebody is looking at.
	 */
	public void install(ManagedServer server, com.mopicmp.npcstudio.server.Catalogue.Found what,
			String source, String askAs, Path into, Consumer<String> progress,
			Consumer<String> done, Consumer<String> failed) {
		worker.execute(() -> {
			try {
				var catalogue = catalogueFor(source);
				String loader = askAs != null ? askAs
					: com.mopicmp.npcstudio.server.Catalogue.loaderFor(server.core);
				var releases = catalogue.releases(what.id(), loader, server.gameVersion);
				var release = com.mopicmp.npcstudio.server.Catalogue.best(releases);
				String name = catalogue.install(server, release, into, (at, total) -> {
					String said = total > 0 ? (at * 100 / total) + "%" : at / 1024 + " KB";
					Minecraft.getInstance().execute(() -> progress.accept(said));
				});
				com.mopicmp.npcstudio.server.InstalledIndex.put(server, name,
					source + ":" + what.id());
				Minecraft.getInstance().execute(() -> done.accept(name));
			} catch (IOException | RuntimeException broken) {
				String said = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				NpcStudio.LOGGER.warn("Could not install {}: {}", what.title(), broken.toString());
				Minecraft.getInstance().execute(() -> failed.accept(said));
			}
		});
	}

	/** The versions one core offers, fetched in the background. */
	public void versions(CoreCatalog.Core core, Consumer<List<String>> got, Consumer<String> failed) {
		worker.execute(() -> {
			try {
				List<String> versions = com.mopicmp.npcstudio.server.CoreProvider
					.of(core, catalog().downloads())
					.orElseThrow(() -> new IOException("nothing can install " + core.name()))
					.versions();
				Minecraft.getInstance().execute(() -> got.accept(versions));
			} catch (IOException unreachable) {
				Minecraft.getInstance().execute(() -> failed.accept(unreachable.getMessage()));
			}
		});
	}

	// ------------------------------------------------------------------ worlds

	public void worlds(ManagedServer server,
			Consumer<List<com.mopicmp.npcstudio.server.Worlds.World>> got) {
		worker.execute(() -> {
			var found = com.mopicmp.npcstudio.server.Worlds.found(server);
			String current = com.mopicmp.npcstudio.server.Worlds.currentName(server);
			Minecraft.getInstance().execute(() -> {
				currentWorld = current;
				got.accept(found);
			});
		});
	}

	/** The name in {@code level-name}, kept so the screen can mark a row. */
	private volatile String currentWorld = "world";

	public String currentWorld() {
		return currentWorld;
	}

	public void backups(ManagedServer server,
			Consumer<List<com.mopicmp.npcstudio.server.Backups.Backup>> got) {
		worker.execute(() -> {
			var found = com.mopicmp.npcstudio.server.Backups.list(server);
			Minecraft.getInstance().execute(() -> got.accept(found));
		});
	}

	/**
	 * The four commands that make a copy usable, over the command channel.
	 *
	 * {@code save-off} stops it writing, {@code save-all flush} makes it finish
	 * what it started, and {@code save-on} lets it go again. If the last one does
	 * not get through, the server is left holding everything in memory — so it is
	 * tried three times and, failing that, put in front of somebody as a window:
	 * a server that is not saving is a server one crash away from losing a day.
	 */
	private com.mopicmp.npcstudio.server.Backups.Saving savingOf(ManagedServer server) {
		return new com.mopicmp.npcstudio.server.Backups.Saving() {
			@Override
			public boolean running() {
				return ServerProcess.running(server);
			}

			@Override
			public boolean pause() {
				try (Rcon rcon = Rcon.connect("127.0.0.1", server.rconPort, server.rconPassword)) {
					rcon.command("save-off");
					rcon.command("save-all flush");
					return true;
				} catch (IOException unreachable) {
					NpcStudio.LOGGER.warn("Could not quiet '{}' for a copy: {}",
						server.id, unreachable.getMessage());
					return false;
				}
			}

			@Override
			public void resume() {
				for (int attempt = 0; attempt < 3; attempt++) {
					try (Rcon rcon =
						Rcon.connect("127.0.0.1", server.rconPort, server.rconPassword)) {
						rcon.command("save-on");
						return;
					} catch (IOException unreachable) {
						NpcStudio.LOGGER.warn("Could not let '{}' save again: {}",
							server.id, unreachable.getMessage());
					}
				}
				failure = "save-off";
			}
		};
	}

	public void backup(ManagedServer server, com.mopicmp.npcstudio.server.Worlds.World world,
			Consumer<String> progress,
			Consumer<com.mopicmp.npcstudio.server.Backups.Backup> done, Consumer<String> failed) {
		worker.execute(() -> {
			try {
				var made = com.mopicmp.npcstudio.server.Backups.make(server, world,
					savingOf(server), (at, total) -> {
						String said = at / (1024 * 1024) + " MB";
						Minecraft.getInstance().execute(() -> progress.accept(said));
					});
				Minecraft.getInstance().execute(() -> done.accept(made));
			} catch (IOException | RuntimeException broken) {
				String said = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				Minecraft.getInstance().execute(() -> failed.accept(said));
			}
		});
	}

	/**
	 * Put a copy back, having first taken one of what is there.
	 *
	 * The second copy is not caution. Somebody restoring last week because of a
	 * mistake today is one press away from wanting today back, and by then today
	 * is gone. It is taken silently and named after the world like any other.
	 */
	public void restore(ManagedServer server, com.mopicmp.npcstudio.server.Backups.Backup backup,
			Consumer<String> progress, Runnable done, Consumer<String> failed) {
		worker.execute(() -> {
			try {
				var saving = savingOf(server);
				if (saving.running()) {
					throw new IOException("Stop the server first: putting files back under a"
						+ " world it has loaded gives it chunks that no longer exist.");
				}
				for (var world : com.mopicmp.npcstudio.server.Worlds.found(server)) {
					if (!world.is(backup.world())) continue;
					Minecraft.getInstance().execute(() -> progress.accept(
						net.minecraft.network.chat.Component
							.translatable("npc_studio.server.worlds.saving_first").getString()));
					com.mopicmp.npcstudio.server.Backups.make(server, world, saving,
						com.mopicmp.npcstudio.server.Downloads.Progress.IGNORED);
					break;
				}
				com.mopicmp.npcstudio.server.Backups.restore(server, backup, saving);
				Minecraft.getInstance().execute(done);
			} catch (IOException | RuntimeException broken) {
				String said = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				Minecraft.getInstance().execute(() -> failed.accept(said));
			}
		});
	}

	/**
	 * Put another dimension in place of one of this world's, having copied first.
	 *
	 * The same two rules as a restore, for the same two reasons. The server has to
	 * be down, because swapping chunk files under a loaded dimension hands it
	 * chunks that no longer exist and it writes its own back over them. And a copy
	 * of the whole world is taken before anything is deleted, because this is one
	 * of the three things in this window that cannot be undone — the old nether is
	 * not in a bin anywhere, it is gone.
	 */
	public void replaceDimension(ManagedServer server,
			com.mopicmp.npcstudio.server.Worlds.World world,
			com.mopicmp.npcstudio.server.Worlds.Dimension dimension, java.nio.file.Path source,
			Consumer<String> progress, Runnable done, Consumer<String> failed) {
		worker.execute(() -> {
			try {
				var saving = savingOf(server);
				if (saving.running()) {
					throw new IOException("Stop the server first: putting chunk files under a"
						+ " dimension it has loaded gives it chunks that no longer exist,"
						+ " and it will write its own back over them.");
				}
				Minecraft.getInstance().execute(() -> progress.accept(
					net.minecraft.network.chat.Component
						.translatable("npc_studio.server.worlds.saving_first").getString()));
				com.mopicmp.npcstudio.server.Backups.make(server, world, saving,
					com.mopicmp.npcstudio.server.Downloads.Progress.IGNORED);
				com.mopicmp.npcstudio.server.Worlds.replace(dimension, source);
				Minecraft.getInstance().execute(done);
			} catch (IOException | RuntimeException broken) {
				String said = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				Minecraft.getInstance().execute(() -> failed.accept(said));
			}
		});
	}

	/**
	 * Throw a dimension's chunks away so the core makes it again on the next start.
	 *
	 * The same conditions as a replacement — down, and copied first — because it
	 * is the same act with an empty source: what was there is gone, and only the
	 * copy taken a moment ago has it.
	 */
	public void resetDimension(ManagedServer server,
			com.mopicmp.npcstudio.server.Worlds.World world,
			com.mopicmp.npcstudio.server.Worlds.Dimension dimension,
			Consumer<String> progress, Runnable done, Consumer<String> failed) {
		worker.execute(() -> {
			try {
				var saving = savingOf(server);
				if (saving.running()) {
					throw new IOException(said("npc_studio.server.worlds.replace_running"));
				}
				Minecraft.getInstance().execute(() -> progress.accept(
					said("npc_studio.server.worlds.saving_first")));
				com.mopicmp.npcstudio.server.Backups.make(server, world, saving,
					com.mopicmp.npcstudio.server.Downloads.Progress.IGNORED);
				com.mopicmp.npcstudio.server.Worlds.reset(dimension);
				Minecraft.getInstance().execute(done);
			} catch (IOException | RuntimeException broken) {
				String told = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				Minecraft.getInstance().execute(() -> failed.accept(told));
			}
		});
	}

	/**
	 * Delete a world, having copied it first.
	 *
	 * The copy is not politeness. This is the only button in the window that
	 * removes something somebody may have spent months in, and the difference
	 * between a mistake and a disaster is a file in the copies list. It is taken
	 * even when the world is enormous, because the alternative is being fast at
	 * exactly the wrong moment.
	 */
	public void dropWorld(ManagedServer server, com.mopicmp.npcstudio.server.Worlds.World world,
			Consumer<String> progress, Runnable done, Consumer<String> failed) {
		worker.execute(() -> {
			try {
				var saving = savingOf(server);
				if (saving.running()) {
					throw new IOException(said("npc_studio.server.worlds.drop_running"));
				}
				Minecraft.getInstance().execute(() -> progress.accept(
					said("npc_studio.server.worlds.saving_first")));
				com.mopicmp.npcstudio.server.Backups.make(server, world, saving,
					com.mopicmp.npcstudio.server.Downloads.Progress.IGNORED);
				com.mopicmp.npcstudio.server.Worlds.drop(server, world);
				Minecraft.getInstance().execute(done);
			} catch (IOException | RuntimeException broken) {
				String told = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				Minecraft.getInstance().execute(() -> failed.accept(told));
			}
		});
	}

	/**
	 * Rewrite the operators for a server that has stopped checking accounts.
	 *
	 * Not a convenience: without it, turning the check off in this window takes
	 * everybody's operator rights away without saying so. The uuid a name carries
	 * depends on the check, the file matches on uuid, and the names in the file
	 * are the same names — so each one gets the identity it will now have.
	 *
	 * Only in that direction. Going back to checking accounts needs the uuid Mojang
	 * keeps, which is not in the file and cannot be worked out from a name; those
	 * entries are left alone rather than filled in with a guess.
	 */
	public void opsToOffline(ManagedServer server, Consumer<Integer> done,
			Consumer<String> failed) {
		worker.execute(() -> {
			try {
				var ops = com.mopicmp.npcstudio.server.Ops.read(server);
				List<com.mopicmp.npcstudio.server.Ops.Op> moved = new ArrayList<>();
				int changed = 0;
				for (var each : ops) {
					java.util.UUID offline = com.mopicmp.npcstudio.server.Ops.offline(each.name());
					if (!offline.equals(each.id())) changed++;
					moved.add(new com.mopicmp.npcstudio.server.Ops.Op(offline, each.name(),
						each.level(), each.bypassesPlayerLimit()));
				}
				if (changed > 0) com.mopicmp.npcstudio.server.Ops.write(server, moved);
				int said = changed;
				Minecraft.getInstance().execute(() -> done.accept(said));
			} catch (IOException | RuntimeException broken) {
				String told = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				Minecraft.getInstance().execute(() -> failed.accept(told));
			}
		});
	}

	/**
	 * Which version of the game last wrote each of these worlds.
	 *
	 * Asked for all of them at once rather than one at a time, because it is one
	 * small file per world and a map is easier to keep true than a field per row.
	 */
	public void worldVersions(ManagedServer server, List<String> worlds,
			Consumer<java.util.Map<String, String>> got) {
		worker.execute(() -> {
			java.util.Map<String, String> versions = new java.util.LinkedHashMap<>();
			for (String each : worlds) {
				String version = DataPacks.versionOf(server, each);
				if (!version.isBlank()) versions.put(each, version);
			}
			Minecraft.getInstance().execute(() -> got.accept(versions));
		});
	}

	/**
	 * Generate a made dimension again, because what it is made of has changed.
	 *
	 * Changing a template rewrites the file that describes the dimension and
	 * nothing that has already been generated — walk in afterwards and the ground
	 * is exactly as it was, which reads as the setting having been ignored. The
	 * only way to see the new one is to have none of the old.
	 */
	public void regeneratePlace(ManagedServer server, String world, String id,
			Consumer<String> progress, Runnable done, Consumer<String> failed) {
		worker.execute(() -> {
			try {
				var saving = savingOf(server);
				if (saving.running()) {
					throw new IOException(said("npc_studio.server.worlds.replace_running"));
				}
				java.nio.file.Path chunks =
					com.mopicmp.npcstudio.server.Dimensions.chunksOf(server, world, id);
				if (java.nio.file.Files.isDirectory(chunks)) {
					for (var each : com.mopicmp.npcstudio.server.Worlds.found(server)) {
						if (!each.is(world)) continue;
						Minecraft.getInstance().execute(() -> progress.accept(
							said("npc_studio.server.worlds.saving_first")));
						com.mopicmp.npcstudio.server.Backups.make(server, each, saving,
							com.mopicmp.npcstudio.server.Downloads.Progress.IGNORED);
						break;
					}
					com.mopicmp.npcstudio.server.Worlds.reset(
						new com.mopicmp.npcstudio.server.Worlds.Dimension(id, chunks, 0));
				}
				Minecraft.getInstance().execute(done);
			} catch (IOException | RuntimeException broken) {
				String told = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				Minecraft.getInstance().execute(() -> failed.accept(told));
			}
		});
	}

	/** The datapacks of a world, and which of them it has switched on. */	/** The datapacks of a world, and which of them it has switched on. */
	public void dataPacks(ManagedServer server, String world,
			Consumer<List<DataPacks.Pack>> got) {
		worker.execute(() -> {
			var found = DataPacks.of(server, world);
			Minecraft.getInstance().execute(() -> got.accept(found));
		});
	}

	/**
	 * Switch one on or off, by whichever route is the true one right now.
	 *
	 * A running server keeps the world's data in memory and writes it out when it
	 * pleases, so anything written under it is lost without a word — the command
	 * channel is the only honest way while it is up. Down, the file is ours to
	 * edit and the change is there at the next start.
	 */
	public void setDataPack(ManagedServer server, String world, String name, boolean on,
			Runnable done, Consumer<String> failed) {
		worker.execute(() -> {
			try {
				if (ServerProcess.running(server)) {
					try (Rcon rcon = Rcon.connect("127.0.0.1", server.rconPort,
							server.rconPassword)) {
						String command = (on ? "datapack enable \"file/" : "datapack disable \"file/")
							+ name + "\"";
						note("> " + command);
						String answer = rcon.command(command);
						if (answer != null && !answer.isBlank()) note(answer.trim());
					}
				} else {
					DataPacks.set(server, world, name, on);
				}
				Minecraft.getInstance().execute(done);
			} catch (IOException | RuntimeException broken) {
				String told = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				Minecraft.getInstance().execute(() -> failed.accept(told));
			}
		});
	}

	public void dropDataPack(ManagedServer server, DataPacks.Pack pack, Runnable done,
			Consumer<String> failed) {
		worker.execute(() -> {
			try {
				if (ServerProcess.running(server)) {
					throw new IOException(said("npc_studio.server.packs.stop_first"));
				}
				DataPacks.drop(pack);
				Minecraft.getInstance().execute(done);
			} catch (IOException | RuntimeException broken) {
				String told = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				Minecraft.getInstance().execute(() -> failed.accept(told));
			}
		});
	}

	/** Put a pack somebody chose into a world, folder or zip alike. */
	public void addDataPack(ManagedServer server, String world, java.nio.file.Path from,
			Runnable done, Consumer<String> failed) {
		worker.execute(() -> {
			try {
				java.nio.file.Path folder = DataPacks.folder(server, world);
				java.nio.file.Files.createDirectories(folder);
				java.nio.file.Path landing = folder.resolve(from.getFileName().toString());
				if (java.nio.file.Files.isDirectory(from)) {
					try (var walk = java.nio.file.Files.walk(from)) {
						for (java.nio.file.Path each : walk.toList()) {
							java.nio.file.Path into = landing.resolve(from.relativize(each).toString());
							if (java.nio.file.Files.isDirectory(each)) {
								java.nio.file.Files.createDirectories(into);
							} else {
								java.nio.file.Files.createDirectories(into.getParent());
								java.nio.file.Files.copy(each, into,
									java.nio.file.StandardCopyOption.REPLACE_EXISTING);
							}
						}
					}
				} else {
					java.nio.file.Files.copy(from, landing,
						java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
				Minecraft.getInstance().execute(done);
			} catch (IOException | RuntimeException broken) {
				String told = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				Minecraft.getInstance().execute(() -> failed.accept(told));
			}
		});
	}

	/** The dimensions we made for this world, read back out of the datapack. */
	public void places(ManagedServer server, String world,
			Consumer<List<com.mopicmp.npcstudio.server.Dimensions.Place>> got) {
		worker.execute(() -> {
			var found = com.mopicmp.npcstudio.server.Dimensions.read(server, world);
			Minecraft.getInstance().execute(() -> got.accept(found));
		});
	}

	/**
	 * Write the datapack that holds them, whole, from the list.
	 *
	 * Whole rather than in pieces because the pack is generated: there is one
	 * thing that can be true of it at a time, and a half-written pack is a server
	 * that will not start.
	 */
	public void writePlaces(ManagedServer server, String world,
			List<com.mopicmp.npcstudio.server.Dimensions.Place> places, Runnable done,
			Consumer<String> failed) {
		worker.execute(() -> {
			try {
				com.mopicmp.npcstudio.server.Dimensions.write(server, world, places);
				// A running server is told to read it again, because most of what
				// was just written — who may go, and the two commands themselves —
				// takes effect at once. Only a new dimension has to wait for the
				// world to load, and the window says which is which.
				if (ServerProcess.running(server)) {
					try (Rcon rcon = Rcon.connect("127.0.0.1", server.rconPort,
							server.rconPassword)) {
						note("> reload");
						String answer = rcon.command("reload");
						if (answer != null && !answer.isBlank()) note(answer.trim());
					} catch (IOException unreachable) {
						NpcStudio.LOGGER.warn("Could not ask '{}' to reload: {}",
							server.id, unreachable.getMessage());
					}
				}
				Minecraft.getInstance().execute(done);
			} catch (IOException | RuntimeException broken) {
				String told = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				Minecraft.getInstance().execute(() -> failed.accept(told));
			}
		});
	}

	/**
	 * Which world types this server knows, which depends on what is installed.
	 *
	 * Read off the disk, so off the drawing thread, and read again whenever the
	 * window is opened: a mod added between two looks adds a type between two
	 * looks.
	 */
	public void worldTypes(ManagedServer server, Consumer<List<String>> got) {
		worker.execute(() -> {
			List<String> found = com.mopicmp.npcstudio.server.WorldTypes.of(server);
			Minecraft.getInstance().execute(() -> got.accept(found));
		});
	}

	/**
	 * Everyone this server knows, and who of them is on it now.
	 *
	 * Who is on it comes from the command channel — {@code list} is the only thing
	 * that knows — and only when the server is up. Everything else comes from files
	 * and is true whether it is running or not, which is the point: the tab is
	 * usable on a stopped server, and that is when most of this work gets done.
	 */
	public void players(ManagedServer server, String world,
			Consumer<List<com.mopicmp.npcstudio.server.Players.Player>> got) {
		worker.execute(() -> {
			java.util.Set<String> online = ready() ? namesOnline(server) : java.util.Set.of();
			var found = com.mopicmp.npcstudio.server.Players.of(server, world, online);
			Minecraft.getInstance().execute(() -> got.accept(found));
		});
	}

	/**
	 * The names the server says are on it.
	 *
	 * Read out of the answer to {@code list}, which is a sentence rather than a
	 * list: "There are 2 of a max of 20 players online: Alice, Bob". Everything
	 * before the colon is prose in whatever language the server is set to, so only
	 * what follows it is read.
	 */
	private java.util.Set<String> namesOnline(ManagedServer server) {
		try (Rcon rcon = Rcon.connect("127.0.0.1", server.rconPort, server.rconPassword)) {
			String answer = rcon.command("list");
			int colon = answer.indexOf(':');
			if (colon < 0) return java.util.Set.of();
			java.util.Set<String> names = new java.util.HashSet<>();
			for (String part : answer.substring(colon + 1).split(",")) {
				String name = part.trim();
				// Some cores add "(nickname)" or a rank in front. The name is the
				// part that could be one.
				if (name.matches("[A-Za-z0-9_]{1,16}")) names.add(name);
			}
			return names;
		} catch (IOException | RuntimeException noAnswer) {
			return java.util.Set.of();
		}
	}

	/**
	 * Do something to a player: op, whitelist, ban, kick.
	 *
	 * Two ways, and which one is used is decided by whether the server is running
	 * rather than by preference. Up: the command, because a running server holds
	 * these lists in memory and writes them out itself — a file changed underneath
	 * it is a file it will overwrite. Down: the file, because there is nothing to
	 * ask. Kicking is the one that has no file half at all, and it says so.
	 */
	public void toPlayer(ManagedServer server, String what, String name, String uuid,
			String reason, Runnable done, Consumer<String> failed) {
		worker.execute(() -> {
			try {
				boolean running = ServerProcess.running(server) || ready();
				if (running) {
					String command = switch (what) {
						case "op" -> "op " + name;
						case "deop" -> "deop " + name;
						case "whitelist" -> "whitelist add " + name;
						case "unwhitelist" -> "whitelist remove " + name;
						case "ban" -> "ban " + name + (reason.isBlank() ? "" : " " + reason);
						case "pardon" -> "pardon " + name;
						case "kick" -> "kick " + name + (reason.isBlank() ? "" : " " + reason);
						default -> throw new IOException("No such thing to do: " + what);
					};
					try (Rcon rcon = Rcon.connect("127.0.0.1", server.rconPort,
							server.rconPassword)) {
						String answer = rcon.command(command);
						String said = answer.isBlank() ? "" : answer.strip();
						Minecraft.getInstance().execute(() -> {
							if (!said.isBlank()) note(said);
							done.run();
						});
					}
					return;
				}
				switch (what) {
					case "op" -> com.mopicmp.npcstudio.server.Ops.add(server,
						uuid.isBlank() ? com.mopicmp.npcstudio.server.Ops.offline(name)
							: java.util.UUID.fromString(uuid), name, 4);
					case "deop" -> {
						var left = new ArrayList<>(com.mopicmp.npcstudio.server.Ops.read(server));
						left.removeIf(op -> op.name().equalsIgnoreCase(name));
						com.mopicmp.npcstudio.server.Ops.write(server, left);
					}
					case "whitelist" -> com.mopicmp.npcstudio.server.Players
						.whitelist(server, name, uuid, true);
					case "unwhitelist" -> com.mopicmp.npcstudio.server.Players
						.whitelist(server, name, uuid, false);
					case "ban" -> com.mopicmp.npcstudio.server.Players
						.ban(server, name, uuid, reason, true);
					case "pardon" -> com.mopicmp.npcstudio.server.Players
						.ban(server, name, uuid, "", false);
					case "kick" -> throw new IOException(
						said("npc_studio.server.players.kick_stopped"));
					default -> throw new IOException("No such thing to do: " + what);
				}
				Minecraft.getInstance().execute(done);
			} catch (IOException | RuntimeException broken) {
				String said = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				Minecraft.getInstance().execute(() -> failed.accept(said));
			}
		});
	}

	/**
	 * Ban an address, or let it back in.
	 *
	 * Its own method rather than another word in {@code toPlayer}, because it is
	 * not about a player: nothing about it takes a name, nothing about it can be
	 * checked against the people the server has met, and the file it touches is a
	 * different one.
	 */
	public void toIp(ManagedServer server, String ip, boolean on, Runnable done,
			Consumer<String> failed) {
		worker.execute(() -> {
			try {
				if (ServerProcess.running(server) || ready()) {
					try (Rcon rcon = Rcon.connect("127.0.0.1", server.rconPort,
							server.rconPassword)) {
						String answer = rcon.command((on ? "ban-ip " : "pardon-ip ") + ip);
						String said = answer.isBlank() ? "" : answer.strip();
						Minecraft.getInstance().execute(() -> {
							if (!said.isBlank()) note(said);
							done.run();
						});
					}
					return;
				}
				com.mopicmp.npcstudio.server.Players.banIp(server, ip, "", on);
				Minecraft.getInstance().execute(done);
			} catch (IOException | RuntimeException broken) {
				String said = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				Minecraft.getInstance().execute(() -> failed.accept(said));
			}
		});
	}

	/**
	 * Whether the server is checking its whitelist at all.
	 *
	 * A different question from who is on the list, and the one that decides
	 * whether the list does anything: nine names on a whitelist that is switched
	 * off is a server everybody can join. It lives in {@code server.properties}
	 * rather than in a list file, which is why it is read here and not in
	 * {@link com.mopicmp.npcstudio.server.Players}.
	 */
	public void whitelistOn(ManagedServer server, Consumer<Boolean> got) {
		worker.execute(() -> {
			boolean on = false;
			try {
				if (java.nio.file.Files.exists(server.propertiesPath())) {
					on = "true".equalsIgnoreCase(com.mopicmp.npcstudio.server.PropertiesFile
						.read(server.propertiesPath()).getOr("white-list", "false"));
				}
			} catch (IOException | RuntimeException unreadable) {
				// A file that will not read leaves the switch reading "off", which
				// is what a server with no properties file does.
			}
			boolean answer = on;
			Minecraft.getInstance().execute(() -> got.accept(answer));
		});
	}

	/**
	 * Turn the whitelist on or off.
	 *
	 * The same two ways as everything else about players, decided the same way.
	 * Up: the command, and the server writes the line itself — a dedicated server
	 * puts {@code white-list} back into its properties file when the command
	 * changes it, so writing the file from here as well would be two writers on
	 * one file. Down: the file, because there is nobody to ask.
	 */
	public void enableWhitelist(ManagedServer server, boolean on, Runnable done,
			Consumer<String> failed) {
		worker.execute(() -> {
			try {
				if (ServerProcess.running(server) || ready()) {
					try (Rcon rcon = Rcon.connect("127.0.0.1", server.rconPort,
							server.rconPassword)) {
						String answer = rcon.command(on ? "whitelist on" : "whitelist off");
						String said = answer.isBlank() ? "" : answer.strip();
						Minecraft.getInstance().execute(() -> {
							if (!said.isBlank()) note(said);
							done.run();
						});
					}
					return;
				}
				Path file = server.propertiesPath();
				var properties = java.nio.file.Files.exists(file)
					? com.mopicmp.npcstudio.server.PropertiesFile.read(file)
					: com.mopicmp.npcstudio.server.PropertiesFile.empty();
				properties.set("white-list", String.valueOf(on));
				properties.write(file);
				Minecraft.getInstance().execute(done);
			} catch (IOException | RuntimeException broken) {
				String said = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				Minecraft.getInstance().execute(() -> failed.accept(said));
			}
		});
	}

	/**
	 * Ban the address somebody on the server right now is playing from.
	 *
	 * Only for a player who is online, and that is the server's limit rather than
	 * a rule invented here: {@code ban-ip} takes a name only while it can look up
	 * which address that name is connected from. The moment they leave, nobody
	 * knows — the server keeps no record of who played from where, and this window
	 * will not invent one. After that the address has to be typed, which is what
	 * the third list on the players tab is for.
	 */
	public void banAddressOf(ManagedServer server, String name, String reason, Runnable done,
			Consumer<String> failed) {
		worker.execute(() -> {
			try {
				try (Rcon rcon = Rcon.connect("127.0.0.1", server.rconPort,
						server.rconPassword)) {
					String answer = rcon.command(
						"ban-ip " + name + (reason.isBlank() ? "" : " " + reason));
					String said = answer.isBlank() ? "" : answer.strip();
					Minecraft.getInstance().execute(() -> {
						if (!said.isBlank()) note(said);
						done.run();
					});
				}
			} catch (IOException | RuntimeException broken) {
				String said = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				Minecraft.getInstance().execute(() -> failed.accept(said));
			}
		});
	}

	// ------------------------------------------------------------------ privileges

	/**
	 * What LuckPerms has, asked for the only way it can be asked.
	 *
	 * <b>Why this is a command and a file rather than either one.</b> LuckPerms
	 * keeps its data in a database whose kind is one line of its own config —
	 * H2 by default, and MySQL, SQLite, YAML or four others by choice — so there
	 * is nothing to read directly that is the same twice. What every one of them
	 * can do is {@code lp export}, which writes the plugin's own backup format
	 * into its own folder. So: delete the last one, ask for a new one, wait for
	 * the file, read it.
	 *
	 * <p>The wait is a wait on a file appearing, and that is not squeamishness:
	 * <b>LuckPerms answers nothing at all over the command channel</b> — not on
	 * success and not on failure. That was measured against a live server, and it
	 * is why nothing in this class believes what a command replies.
	 */
	public void privileges(ManagedServer server,
			Consumer<com.mopicmp.npcstudio.server.LuckPermsExport.Data> got,
			Consumer<String> failed) {
		rightsWorker.execute(() -> {
			try {
				// Read here and handed over afterwards. Reading inside the handover
				// would put a command, a wait on a file and a decompression on the
				// thread that draws — which is a frozen game for as long as somebody
				// else's plugin takes.
				var data = exportAndRead(server);
				Minecraft.getInstance().execute(() -> got.accept(data));
			} catch (IOException | RuntimeException broken) {
				String said = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				Minecraft.getInstance().execute(() -> failed.accept(said));
			}
		});
	}

	private com.mopicmp.npcstudio.server.LuckPermsExport.Data exportAndRead(ManagedServer server)
			throws IOException {
		if (!ready()) {
			// Nothing to ask. The last export on disk is still true about a server
			// that has not run since, so it is shown rather than an empty tab — and
			// the tab says out loud that it is not live.
			return com.mopicmp.npcstudio.server.Privileges.read(server);
		}
		return com.mopicmp.npcstudio.server.Privileges.pull(server);
	}

	/**
	 * Send changes, then look at what the file says afterwards.
	 *
	 * The reading is not politeness, it is the only way to know: LuckPerms replies
	 * to nothing over this channel, so a command that was refused and a command
	 * that worked are the same silence. What the screen shows after a change is
	 * therefore what the server exported after the change, never what this code
	 * hoped the change would do.
	 */
	public void toPrivileges(ManagedServer server, List<String> commands,
			Consumer<com.mopicmp.npcstudio.server.LuckPermsExport.Data> got,
			Consumer<String> failed) {
		rightsWorker.execute(() -> {
			try {
				if (!ready()) throw new IOException(said("npc_studio.server.rights.stopped"));
				var data = com.mopicmp.npcstudio.server.Privileges.push(server, commands);
				Minecraft.getInstance().execute(() -> got.accept(data));
			} catch (IOException | RuntimeException broken) {
				String said = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				Minecraft.getInstance().execute(() -> failed.accept(said));
			}
		});
	}

	/** Who the operators are, which every server has whether or not it has a plugin. */
	public void operators(ManagedServer server,
			Consumer<List<com.mopicmp.npcstudio.server.Ops.Op>> got) {
		worker.execute(() -> {
			var found = com.mopicmp.npcstudio.server.Ops.read(server);
			Minecraft.getInstance().execute(() -> got.accept(found));
		});
	}

	/**
	 * Give somebody an operator level, up or down.
	 *
	 * The running server is asked with a command, because it holds the list in
	 * memory and writes it out itself; a stopped one has its file edited. The same
	 * rule as every other list, and for the same reason.
	 *
	 * <p>{@code /op} on a running server always gives level four, so a lower level
	 * is written into the file even then — the vanilla command has no argument for
	 * it. Said plainly rather than silently doing something else.
	 */
	public void toOperator(ManagedServer server, String name, String uuid, int level,
			Runnable done, Consumer<String> failed) {
		worker.execute(() -> {
			try {
				boolean up = ServerProcess.running(server) || ready();
				if (level <= 0) {
					if (up) {
						try (Rcon rcon = Rcon.connect("127.0.0.1", server.rconPort,
								server.rconPassword)) {
							rcon.command("deop " + name);
						}
					} else {
						var left = new ArrayList<>(com.mopicmp.npcstudio.server.Ops.read(server));
						left.removeIf(op -> op.name().equalsIgnoreCase(name));
						com.mopicmp.npcstudio.server.Ops.write(server, left);
					}
					Minecraft.getInstance().execute(done);
					return;
				}
				java.util.UUID id = uuid.isBlank()
					? com.mopicmp.npcstudio.server.Ops.offline(name)
					: java.util.UUID.fromString(uuid);
				com.mopicmp.npcstudio.server.Ops.add(server, id, name, level);
				if (up) {
					// The file is what the level lives in, and a running server has
					// already read it. Reloading is what makes it true now.
					try (Rcon rcon = Rcon.connect("127.0.0.1", server.rconPort,
							server.rconPassword)) {
						rcon.command("reload permissions");
					}
				}
				Minecraft.getInstance().execute(done);
			} catch (IOException | RuntimeException broken) {
				String said = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				Minecraft.getInstance().execute(() -> failed.accept(said));
			}
		});
	}

	/** The addresses this server refuses, which is a list of its own. */
	public void ipBans(ManagedServer server,
			Consumer<List<com.mopicmp.npcstudio.server.Players.IpBan>> got) {
		worker.execute(() -> {
			var found = com.mopicmp.npcstudio.server.Players.ipBans(server);
			Minecraft.getInstance().execute(() -> got.accept(found));
		});
	}

	/**
	 * Which configuration files this server has, and whose they are.
	 *
	 * The installed list is passed in rather than read again, because the screen
	 * has it already and reading a hundred jars twice to answer the same question
	 * is a second of somebody's time for nothing.
	 */
	public void configs(ManagedServer server, List<com.mopicmp.npcstudio.server.Addon> addons,
			Consumer<List<com.mopicmp.npcstudio.server.Configs.Group>> got) {
		worker.execute(() -> {
			var found = com.mopicmp.npcstudio.server.Configs.of(server, addons);
			Minecraft.getInstance().execute(() -> got.accept(found));
		});
	}

	public void readConfig(java.nio.file.Path file,
			Consumer<com.mopicmp.npcstudio.server.ConfigFile> got, Consumer<String> failed) {
		worker.execute(() -> {
			try {
				var read = com.mopicmp.npcstudio.server.ConfigFile.read(file);
				Minecraft.getInstance().execute(() -> got.accept(read));
			} catch (IOException | RuntimeException broken) {
				String said = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				Minecraft.getInstance().execute(() -> failed.accept(said));
			}
		});
	}

	/**
	 * Write a config file whole, as somebody typed it.
	 *
	 * The one place in this mod that rewrites a configuration file rather than
	 * patching it — and the one place where that is right, because what is being
	 * written <em>is</em> the file: it was read into a text editor, changed there,
	 * and handed back. Nothing here reformats it.
	 */
	public void writeConfig(java.nio.file.Path file, String text, Runnable done,
			Consumer<String> failed) {
		worker.execute(() -> {
			try {
				if (text.length() > com.mopicmp.npcstudio.server.ConfigFile.MOST) {
					throw new IOException("That is larger than a configuration file may be here.");
				}
				com.mopicmp.npcstudio.server.ConfigFile.write(file, text);
				Minecraft.getInstance().execute(done);
			} catch (IOException | RuntimeException broken) {
				String said = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				Minecraft.getInstance().execute(() -> failed.accept(said));
			}
		});
	}

	/**
	 * Write changed values into a config file, by patching it.
	 *
	 * The file is read again here rather than the copy the screen is holding being
	 * used. It may have been rewritten since — by the server starting, by the
	 * plugin itself, by somebody in a text editor — and every value's place in the
	 * text would have moved with it. Read now, matched by key, patched now.
	 *
	 * <p>A key that is no longer in the file is dropped and said out loud. It is the
	 * honest outcome: the file that is there does not have that setting, and
	 * putting it back would be inventing where it goes.
	 */
	public void saveConfig(java.nio.file.Path file, java.util.Map<String, String> values,
			Runnable done, Consumer<String> failed) {
		worker.execute(() -> {
			try {
				var read = com.mopicmp.npcstudio.server.ConfigFile.read(file);
				List<com.mopicmp.npcstudio.server.ConfigFile.Setting> settings = new ArrayList<>();
				List<String> wanted = new ArrayList<>();
				List<String> missing = new ArrayList<>();
				for (var each : values.entrySet()) {
					var setting = read.settings().stream()
						.filter(one -> one.path().equals(each.getKey())).findFirst().orElse(null);
					if (setting == null) {
						missing.add(each.getKey());
						continue;
					}
					settings.add(setting);
					wanted.add(each.getValue());
				}
				if (settings.isEmpty()) {
					throw new IOException(missing.isEmpty()
						? "Nothing to write."
						: "This file no longer has " + String.join(", ", missing));
				}
				com.mopicmp.npcstudio.server.ConfigFile.write(file, read.with(settings, wanted));
				if (!missing.isEmpty()) {
					String said = "Written, except: " + String.join(", ", missing);
					Minecraft.getInstance().execute(() -> failed.accept(said));
					return;
				}
				Minecraft.getInstance().execute(done);
			} catch (IOException | RuntimeException broken) {
				String said = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				Minecraft.getInstance().execute(() -> failed.accept(said));
			}
		});
	}

	/**
	 * The picture and the version inside each copy, read once per list.
	 *
	 * All of them together rather than one row at a time, for the same reason the
	 * worlds' versions are: it is two small files per archive, and a map that
	 * arrives in one piece cannot be half-stale.
	 */
	public void backupLooks(List<com.mopicmp.npcstudio.server.Backups.Backup> backups,
			Consumer<java.util.Map<String, BackupLook.Look>> got) {
		worker.execute(() -> {
			java.util.Map<String, BackupLook.Look> looks = new java.util.LinkedHashMap<>();
			for (var each : backups) looks.put(each.file(), BackupLook.read(each));
			Minecraft.getInstance().execute(() -> got.accept(looks));
		});
	}

	/**
	 * Put a person's own name on a copy.
	 *
	 * A rename of the file, because the file name is where the world and the
	 * moment are kept — see {@link com.mopicmp.npcstudio.server.Backups#rename}.
	 */
	public void renameBackup(com.mopicmp.npcstudio.server.Backups.Backup backup, String label,
			Runnable done, Consumer<String> failed) {
		worker.execute(() -> {
			try {
				com.mopicmp.npcstudio.server.Backups.rename(backup, label);
				Minecraft.getInstance().execute(done);
			} catch (IOException | RuntimeException broken) {
				String said = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				Minecraft.getInstance().execute(() -> failed.accept(said));
			}
		});
	}

	public void dropBackup(com.mopicmp.npcstudio.server.Backups.Backup backup, Runnable done,
			Consumer<String> failed) {
		worker.execute(() -> {
			try {
				com.mopicmp.npcstudio.server.Backups.drop(backup);
				Minecraft.getInstance().execute(done);
			} catch (IOException | RuntimeException broken) {
				String said = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				Minecraft.getInstance().execute(() -> failed.accept(said));
			}
		});
	}

	/**
	 * Point the server at a different world, which is one line and a restart.
	 *
	 * Deliberately not dressed up as switching. Nothing here moves a world or
	 * loads one: it writes {@code level-name} and the server reads it the next
	 * time it starts. A button that implied otherwise would leave somebody
	 * watching an unchanged world and wondering what they did wrong.
	 */
	public void useWorld(ManagedServer server, String name, Runnable done,
			Consumer<String> failed) {
		if (!com.mopicmp.npcstudio.server.Worlds.validName(name)) {
			failed.accept("that is not a name a folder can have");
			return;
		}
		worker.execute(() -> {
			try {
				var file = java.nio.file.Files.exists(server.propertiesPath())
					? com.mopicmp.npcstudio.server.PropertiesFile.read(server.propertiesPath())
					: com.mopicmp.npcstudio.server.PropertiesFile.empty();
				file.set("level-name", name);
				file.write(server.propertiesPath());
				Minecraft.getInstance().execute(() -> {
					currentWorld = name;
					done.run();
				});
			} catch (IOException | RuntimeException broken) {
				String said = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				Minecraft.getInstance().execute(() -> failed.accept(said));
			}
		});
	}

	/**
	 * Make a world now, by having the core make it.
	 *
	 * The settings were written and the folder appeared on the next start, which
	 * is true to how a server works and useless to look at: somebody presses make
	 * and nothing is there. So the world is made here and now — by starting the
	 * server once, letting it generate, and stopping it again.
	 *
	 * Deliberately not by writing {@code level.dat} ourselves, which would be
	 * instant. A world's data holds its generator and its list of dimensions, and
	 * the only thing that knows what those are on this server is this server: its
	 * core, its version, its mods. Written from the client's idea of Minecraft, a
	 * world for a modded server would be a world missing the mods' dimensions and
	 * a modded generator would be unresolvable — a fast answer that is wrong for
	 * exactly the people who need it. A minute of the real core is worth more than
	 * an instant of our guess.
	 */
	public void makeWorld(ManagedServer server, String name, String seed, String type,
			String generator, Consumer<String> progress, Runnable done, Consumer<String> failed) {
		if (!com.mopicmp.npcstudio.server.Worlds.validName(name)) {
			failed.accept(said("npc_studio.server.worlds.bad_name"));
			return;
		}
		if (state != State.DOWN) {
			failed.accept(said("npc_studio.server.worlds.make_running"));
			return;
		}
		if (making) return;
		making = true;
		state = State.STARTING;
		probed = true;
		status = "";
		errand.execute(() -> {
			try {
				if (java.nio.file.Files.isDirectory(server.path().resolve(name))) {
					throw new IOException(said("npc_studio.server.worlds.taken", name));
				}
				settings(server, name, seed, type, generator);
				step(progress, "npc_studio.server.worlds.step_start");
				ServerProcess.start(server);
				store().save();

				// Waiting on the command channel rather than on the log, because
				// answering is what "ready" means here and the log is being read by
				// the half-second look already. Two readers of one file is two
				// readers seeing half the lines each.
				long deadline = System.currentTimeMillis() + PATIENCE;
				boolean generating = false;
				while (!answersRcon(server)) {
					if (!ServerProcess.running(server)) {
						throw new IOException(said("npc_studio.server.worlds.core_left"));
					}
					if (System.currentTimeMillis() > deadline) {
						throw new IOException(said("npc_studio.server.worlds.too_long"));
					}
					if (!generating
						&& java.nio.file.Files.isDirectory(server.path().resolve(name))) {
						generating = true;
						step(progress, "npc_studio.server.worlds.step_making");
					}
					Thread.sleep(1000);
				}

				step(progress, "npc_studio.server.worlds.step_stopping");
				stopAndWait(server);

				if (!java.nio.file.Files.isRegularFile(
					server.path().resolve(name).resolve("level.dat"))) {
					throw new IOException(said("npc_studio.server.worlds.no_level_dat"));
				}
				Minecraft.getInstance().execute(() -> {
					currentWorld = name;
					done.run();
				});
			} catch (InterruptedException stopped) {
				Thread.currentThread().interrupt();
			} catch (IOException | RuntimeException broken) {
				String told = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				Minecraft.getInstance().execute(() -> failed.accept(told));
			} finally {
				making = false;
				madeStep = "";
				madePercent = -1;
			}
		});
	}

	/** How long a world may take to generate before we stop believing in it. */
	private static final long PATIENCE = 15 * 60 * 1000L;

	/**
	 * The long waits, on a thread of their own.
	 *
	 * Making a world is minutes of sitting still, and the worker is what the list
	 * of worlds, the copies and the settings are read on. The lesson is the one
	 * that split the watcher off in the first place: one slow thing must not stop
	 * the window from saying what is happening.
	 */
	private final ExecutorService errand = daemon("npc-studio-errand");

	private volatile boolean making;

	/** Whether a world is being made right now — a start and a stop, not a copy. */
	public boolean makingWorld() {
		return making;
	}

	private static String said(String key, Object... with) {
		return net.minecraft.network.chat.Component.translatable(key, with).getString();
	}

	private void step(Consumer<String> progress, String key) {
		String told = said(key);
		madeStep = told;
		Minecraft.getInstance().execute(() -> progress.accept(told));
	}

	/** Ask it to stop and stay here until it has, because the folder is being written. */
	private void stopAndWait(ManagedServer server) throws IOException, InterruptedException {
		Minecraft.getInstance().execute(() -> state = State.STOPPING);
		try (Rcon rcon = Rcon.connect("127.0.0.1", server.rconPort, server.rconPassword)) {
			rcon.command("stop");
		}
		long deadline = System.currentTimeMillis() + 3 * 60 * 1000L;
		while (ServerProcess.running(server)) {
			if (System.currentTimeMillis() > deadline) {
				throw new IOException(said("npc_studio.server.worlds.slow_stop"));
			}
			Thread.sleep(500);
		}
	}

	/**
	 * The four lines in {@code server.properties} that decide what gets made.
	 *
	 * The fourth is {@code generator-settings}, which is what a flat world is
	 * flat of. It is written blank when it does not apply rather than left as it
	 * was: a leftover set of layers under a world type that is not flat is a
	 * setting nobody can see doing something nobody asked for.
	 */
	private void settings(ManagedServer server, String name, String seed, String type,
			String generator) throws IOException {
		var file = java.nio.file.Files.exists(server.propertiesPath())
			? com.mopicmp.npcstudio.server.PropertiesFile.read(server.propertiesPath())
			: com.mopicmp.npcstudio.server.PropertiesFile.empty();
		file.set("level-name", name);
		file.set("level-seed", seed == null ? "" : seed.trim());
		file.set("level-type", type == null || type.isBlank() ? "minecraft:normal" : type);
		file.set("generator-settings", generator == null ? "" : generator);
		file.write(server.propertiesPath());
	}

	/** Do something with the files of a server, off the drawing thread. */
	public void onFiles(Runnable work, Runnable then, Consumer<String> failed) {
		worker.execute(() -> {
			try {
				work.run();
				Minecraft.getInstance().execute(then);
			} catch (RuntimeException broken) {
				String said = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				Minecraft.getInstance().execute(() -> failed.accept(said));
			}
		});
	}

	private void done(ManagedServer server, String said) {
		Minecraft.getInstance().execute(() -> {
			if (watched != server) return;
			status = said == null ? "" : said;
			ticks = EVERY;
		});
	}
}
