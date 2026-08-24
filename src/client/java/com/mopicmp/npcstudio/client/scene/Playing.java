package com.mopicmp.npcstudio.client.scene;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mopicmp.npcstudio.scene.Cue;
import com.mopicmp.npcstudio.scene.Playhead;
import com.mopicmp.npcstudio.scene.Role;
import com.mopicmp.npcstudio.scene.Scene;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;

/**
 * One clock, and everybody in the scene answering to it.
 *
 * <h2>Why this runs on the client</h2>
 *
 * Because what it is for is watching and capturing. A scene is edited by one
 * person looking at it, and it is filmed by one person looking at it, and both
 * of those want the cursor to answer to a keyboard immediately rather than to a
 * round trip. Nothing is moved on the server: a character being played is a
 * character drawn somewhere other than where it stands, which is a rendering
 * override and costs the world nothing. That also means scrubbing back and forth
 * over a scene twenty times does not shove characters around a shared world
 * twenty times.
 *
 * The consequence is honest and worth saying out loud: what plays here plays for
 * whoever is playing it. A scene running for everybody at once is a different
 * feature, it needs the server to own the clock, and it can be built on the same
 * sampling when there is a reason for it.
 *
 * <h2>How a part finds its player</h2>
 *
 * By the entity's own id, which is the one name for a character that survives a
 * world being closed and opened. Everything is a lookup the other way round —
 * the renderer already has the entity and asks whether it is in the scene — so
 * there is never a search through the world for somebody, which is what makes
 * this cost nothing per frame.
 */
public final class Playing {

	private static String open = "";
	private static Playhead head = Playhead.resting();

	/**
	 * Entity id to the part it plays, rebuilt when the scene changes.
	 *
	 * Keyed by the id itself rather than by its text. The scene keeps it as text —
	 * an id that will not parse has to mean "nobody is playing this part" rather
	 * than "the scene will not load" — but this map is asked once per drawn entity
	 * per frame, and turning an id into a string that often is an allocation for
	 * every character and every object, sixty times a second, to answer a question
	 * that is nearly always no.
	 */
	private static Map<java.util.UUID, String> parts = Map.of();
	private static int builtFrom = -1;
	private static String builtFor = "";

	/** The lines that should be on the screen, oldest first. */
	private static List<Cue> showing = List.of();

	private Playing() { }

	public static String openName() {
		return open;
	}

	public static Scene scene() {
		return Scenes.get(open);
	}

	public static void open(String name) {
		open = name == null ? "" : name;
		head = head.scrubbedTo(0, length());
		showing = List.of();
	}

	public static Playhead head() {
		return head;
	}

	public static void head(Playhead replacement) {
		head = replacement;
	}

	public static int length() {
		Scene scene = scene();
		return scene == null ? 1 : scene.length();
	}

	public static boolean playing() {
		return head.playing() && scene() != null;
	}

	/** Plays: from the top, or on from where the cursor was left. */
	public static void start() {
		Scene scene = scene();
		if (scene == null) return;
		Playhead.Step step = head.started(scene.length());
		head = step.head();
		play(scene, step);
	}

	public static void stop() {
		head = head.playing(false);
	}

	/** Called once a client tick. */
	public static void tick() {
		Scene scene = scene();
		if (scene == null) {
			Music.stop();
			return;
		}
		freshenParts(scene);
		// Every tick, because stopping is as much a decision as starting: a scene
		// that has been paused, scrubbed off the end or closed has to fall silent on
		// the same tick, and the only place that notices all three is this one.
		follow(scene, head.at());
		// A capture moves the cursor itself, a frame at a time. Ticking it here as
		// well would run the scene at twenty ticks a second on top of the capture's
		// own rate, which is a film at two and a half times speed.
		if (Capture.running()) return;
		if (!head.playing()) {
			showing = scene.showingAt(head.tick());
			return;
		}
		Playhead.Step step = head.advanced(1, scene.length());
		head = step.head();
		play(scene, step);
		showing = scene.showingAt(head.tick());
	}

	/**
	 * Where the cursor is for drawing, which is between two ticks.
	 *
	 * A frame is drawn part of the way through a tick, so asking the scene where
	 * things are at the last whole tick would make a smooth curve into twenty
	 * steps a second. Wrapped rather than clamped when it runs off the end,
	 * because that is what the next tick is about to do anyway.
	 */
	public static double now(float partial) {
		// While a capture is running the cursor is exact, because the capture is what
		// moves it: one frame is one step of the scene's own clock. Smoothing towards
		// the next tick here would smear every written frame by a fraction of a
		// different clock — the machine's — which is the very thing the capture
		// exists to keep out of the film.
		if (Capture.running()) return head.at();
		if (!head.playing()) return head.at();
		int length = length();
		double at = head.at() + partial * head.speed();
		return at < length ? at : head.loops() ? at % length : length;
	}

	/**
	 * What the scene says this entity is doing, or null when it says nothing.
	 *
	 * Null is the ordinary answer: most of what is standing around is not in the
	 * scene, and a renderer that gets null draws the character the way it always
	 * did.
	 */
	public static Sample sampleOf(Entity entity, float partial) {
		if (entity == null) return null;
		Scene scene = scene();
		if (scene == null || parts.isEmpty()) return null;
		String role = parts.get(entity.getUUID());
		return role == null ? null : new Sample(scene, role, now(partial));
	}

	/** One participant, at one moment. The caller brings its own fallbacks. */
	public record Sample(Scene scene, String role, double time) {

		/**
		 * A number from the scene, or what it was already.
		 *
		 * The fallback is the whole of how a scene stays polite: it animates what it
		 * mentions and leaves everything else exactly as it was. A scene that only
		 * turns a wheel does not also drag the wheel to the origin.
		 */
		public float value(String channel, float already) {
			return scene.valueAt(role, channel, time, already);
		}
	}

	/** The lines the scene wants on screen at this moment. */
	public static List<Cue> showing() {
		return showing;
	}

	// ------------------------------------------------------------- the plumbing

	private static void freshenParts(Scene scene) {
		if (Scenes.generation() == builtFrom && open.equals(builtFor)) return;
		builtFrom = Scenes.generation();
		builtFor = open;

		Map<java.util.UUID, String> found = new HashMap<>();
		for (Role role : scene.cast()) {
			if (!role.cast()) continue;
			try {
				found.put(java.util.UUID.fromString(role.bound()), role.name());
			} catch (IllegalArgumentException notAnId) {
				// A part bound to something that is not an id is a part nobody plays.
				// That is a state the scene is allowed to be in, so it is passed over
				// rather than complained about.
			}
		}
		parts = Map.copyOf(found);
	}

	/**
	 * Everything the step went past.
	 *
	 * Sounds only, for now. A line of text has nowhere to be drawn yet, so it is
	 * held rather than played — see {@link #showing()}, which is what the viewport
	 * will read when there is one.
	 */
	private static void play(Scene scene, Playhead.Step step) {
		List<Cue> heard = new ArrayList<>();
		for (Playhead.Span span : step.covered()) {
			heard.addAll(scene.between(span.after(), span.upTo()));
		}
		for (Cue cue : heard) {
			if (cue.kind() == Cue.Kind.SOUND) sound(cue.what());
		}
	}

	/**
	 * Puts the right piece of music on, or takes it off.
	 *
	 * Not driven by what the step went past, unlike a sound, and that is the whole
	 * difference between the two. A sound is a thing that happened; music is a
	 * thing that is going on, so it is decided by where the cursor <em>is</em> —
	 * the last cue at or before it, and silence if there is none. Scrub backwards
	 * and it stops of its own accord, because the answer to "what should be playing
	 * here" changed.
	 *
	 * <h2>Only while the scene is running, and why that was got wrong</h2>
	 *
	 * "Music is a state of the cursor" is right and was applied one step too far:
	 * it was played whenever the cursor stood at or past a cue, including while the
	 * scene was paused. Open a scene with a cue at nought and the music started at
	 * once and ran for ever — the timeline standing perfectly still while the track
	 * played on in real time.
	 *
	 * Which is not "music that does not stop"; it is music measuring a different
	 * clock. A paused playhead is not a playhead moving slowly, so there is nothing
	 * for a piece of music to be in step <em>with</em>, and the only honest thing to
	 * play is nothing. Press play and it starts from wherever inside the piece the
	 * cursor is; pause and it stops there.
	 *
	 * A capture is silent for a related reason and a firmer one: its clock is one
	 * frame of scene per frame written, however long that frame took, so a piece of
	 * music played against it would drift by however slow the machine is. The film
	 * carries no sound anyway — every preset passes {@code -an} — so there is
	 * nothing being lost.
	 */
	private static void follow(Scene scene, double at) {
		if (!head.playing() || Capture.running()) {
			Music.stop();
			return;
		}
		Cue latest = null;
		for (Cue cue : scene.cues()) {
			if (cue.kind() != Cue.Kind.MUSIC || cue.at() > at) continue;
			if (latest == null || cue.at() >= latest.at()) latest = cue;
		}
		if (latest == null) {
			Music.stop();
			return;
		}
		Music.want(latest.what(), latest.at(), at);
	}

	private static void sound(String named) {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null || client.player == null || named.isBlank()) return;
		Identifier id = Identifier.tryParse(named);
		if (id == null) return;
		SoundEvent found = BuiltInRegistries.SOUND_EVENT.getValue(id);
		if (found == null) return;
		// Played at the listener rather than in the world: the scene is being
		// watched from a camera that flies, so a sound placed at a character would
		// be inaudible from exactly the shot somebody set up.
		client.level.playLocalSound(client.player.getX(), client.player.getY(),
			client.player.getZ(), found, SoundSource.MASTER, 1f, 1f, false);
	}

	/** Dropped on leaving a world, along with the scenes themselves. */
	public static void forget() {
		Posing.forget();
		open = "";
		head = Playhead.resting();
		parts = Map.of();
		showing = List.of();
		builtFrom = -1;
		builtFor = "";
	}
}
