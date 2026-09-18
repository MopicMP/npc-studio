package com.mopicmp.npcstudio.scene;

import java.util.ArrayList;
import java.util.List;

/**
 * A performance: parts, the numbers that move over time, and what happens when.
 *
 * <h2>One document with one clock</h2>
 *
 * The alternative was a set of animations, one per character, played together —
 * which is simpler and cannot express the thing this is for. Three people on a
 * deck, a wheel, a piece of music and a line of text that has to land on a beat
 * are not five independent performances that happen to start at once; they are
 * one performance, and the whole difficulty of making it is that a change to any
 * of them is a change in relation to the rest. So there is one time cursor and
 * everything hangs off it.
 *
 * <h2>Immutable, and edited by replacement</h2>
 *
 * The same bargain as {@link com.mopicmp.npcstudio.model.Model}, for the same
 * reason: undo is free when the old value is still a value. A scene is thousands
 * of small numbers rather than millions, so copying one on every edit is not a
 * cost worth designing around — and the alternative, reconstructing a past state
 * from a list of differences, is where an editor's worst bugs live.
 *
 * <h2>What is deliberately not here</h2>
 *
 * Nothing in this package knows what a character is, where a bone lives, or how
 * to make a sound. It knows numbers, moments and names. That is what lets the
 * whole of it be tested without starting the game, which matters more here than
 * anywhere else in this mod: a mistake in this arithmetic does not crash, it
 * produces an animation that is slightly wrong in a way nobody can point at.
 */
public record Scene(String name, int length, List<Role> cast, List<Track> tracks, List<Cue> cues) {

	/** Ticks in a second, which is the game's rate and therefore the scene's. */
	public static final int RATE = 20;

	public Scene {
		length = Math.max(1, length);
		cast = List.copyOf(cast);
		tracks = List.copyOf(tracks);
		List<Cue> ordered = new ArrayList<>(cues);
		ordered.sort((one, other) -> Integer.compare(one.at(), other.at()));
		cues = List.copyOf(ordered);
	}

	/** A new scene, five seconds long, with nobody in it yet. */
	public static Scene empty(String name) {
		return new Scene(name, RATE * 5, List.of(), List.of(), List.of());
	}

	public Scene named(String fresh) {
		return new Scene(fresh, length, cast, tracks, cues);
	}

	/**
	 * How long the scene runs, whatever is in it.
	 *
	 * Stored rather than worked out from the last key, because silence at the end
	 * is a real thing to want — a scene that holds for two seconds after the last
	 * movement is a scene with a pause in it, and one that ends on the last key
	 * cannot have one. It also means shortening a scene does not delete anything:
	 * keys past the end are still there, simply not reached.
	 */
	public Scene lengthened(int ticks) {
		return new Scene(name, ticks, cast, tracks, cues);
	}

	/** The last moment anything happens, which is what "fit to content" needs. */
	public int lastMoment() {
		int last = 0;
		for (Track track : tracks) last = Math.max(last, track.lastMoment());
		for (Cue cue : cues) last = Math.max(last, cue.ends());
		return last;
	}

	// ---------------------------------------------------------------- the parts

	public Role role(String named) {
		for (Role role : cast) {
			if (role.name().equals(named)) return role;
		}
		return null;
	}

	public Scene withCast(List<Role> replacement) {
		return new Scene(name, length, replacement, tracks, cues);
	}

	/**
	 * Adds a part, or replaces the one of that name.
	 *
	 * Names are how tracks find their subject, so two parts with one name is not a
	 * tidiness problem — it is a track that belongs to both and neither.
	 */
	public Scene with(Role role) {
		List<Role> changed = new ArrayList<>(cast);
		for (int i = 0; i < changed.size(); i++) {
			if (changed.get(i).name().equals(role.name())) {
				changed.set(i, role);
				return withCast(changed);
			}
		}
		changed.add(role);
		return withCast(changed);
	}

	/**
	 * Removes a part, and everything it was doing.
	 *
	 * The tracks go with it, as a bone's cubes go with the bone. Left behind they
	 * would be rows in the timeline belonging to nobody, and the first thing
	 * anybody would do is wonder what they were.
	 */
	public Scene withoutRole(String named) {
		List<Role> left = new ArrayList<>();
		for (Role role : cast) {
			if (!role.name().equals(named)) left.add(role);
		}
		List<Track> kept = new ArrayList<>();
		for (Track track : tracks) {
			if (!track.subject().equals(named)) kept.add(track);
		}
		return new Scene(name, length, left, kept, cues);
	}

	/**
	 * Renames a part and brings its tracks along.
	 *
	 * Worth having as one operation rather than two, because doing it as two
	 * leaves a moment where the tracks point at a name nothing answers to — and
	 * whatever happens to run in that moment sees a scene with a hole in it.
	 */
	public Scene renaming(String from, String to) {
		if (from.equals(to) || role(from) == null || role(to) != null) return this;
		List<Role> renamed = new ArrayList<>();
		for (Role role : cast) renamed.add(role.name().equals(from) ? role.renamed(to) : role);
		List<Track> moved = new ArrayList<>();
		for (Track track : tracks) {
			moved.add(track.subject().equals(from)
				? new Track(to, track.channel(), track.keys()) : track);
		}
		return new Scene(name, length, renamed, moved, cues);
	}

	// --------------------------------------------------------------- the tracks

	public Track track(String subject, String channel) {
		for (Track track : tracks) {
			if (track.is(subject, channel)) return track;
		}
		return null;
	}

	public List<Track> tracksOf(String subject) {
		List<Track> found = new ArrayList<>();
		for (Track track : tracks) {
			if (track.subject().equals(subject)) found.add(track);
		}
		return List.copyOf(found);
	}

	public Scene withTracks(List<Track> replacement) {
		return new Scene(name, length, cast, replacement, cues);
	}

	public Scene with(Track track) {
		List<Track> changed = new ArrayList<>(tracks);
		for (int i = 0; i < changed.size(); i++) {
			if (changed.get(i).is(track.subject(), track.channel())) {
				changed.set(i, track);
				return withTracks(changed);
			}
		}
		changed.add(track);
		return withTracks(changed);
	}

	public Scene without(String subject, String channel) {
		List<Track> left = new ArrayList<>();
		for (Track track : tracks) {
			if (!track.is(subject, channel)) left.add(track);
		}
		return withTracks(left);
	}

	/**
	 * Puts a key down, making the track if there was not one.
	 *
	 * The verb the editor uses for almost everything: moving a bone with the
	 * cursor somewhere is this, three times.
	 *
	 * <h2>It does not ask whether the part exists, and that is on purpose</h2>
	 *
	 * Written down because it looks like an oversight and is not. A guard was added
	 * here on the reasoning that a track naming nobody in the cast belongs to nobody,
	 * and eight tests said otherwise at once — which was the right answer.
	 *
	 * The two lists are not one list. A {@link Role} binds a name to something in the
	 * world: who is playing the guard. A {@link Track} moves a name. Animating "the
	 * guard" before anybody has been cast as the guard is an ordinary way to work,
	 * and {@code valueAt} answers for a name with no part on purpose for the same
	 * reason.
	 *
	 * What {@link #withoutRole} and {@link #renaming} do — carrying tracks with the
	 * part they name — is those operations being tidy about the thing they are doing,
	 * not evidence of a rule holding across the whole document.
	 */
	public Scene keyed(String subject, String channel, Key key) {
		Track track = track(subject, channel);
		return with((track == null ? Track.of(subject, channel) : track).with(key));
	}

	/**
	 * Takes a key away, and leaves the track behind even when it was the last one.
	 *
	 * An empty track is a row in the timeline with nothing on it, which is exactly
	 * what somebody who has just deleted their only key is looking at and about to
	 * put something back into. Removing the row as well would mean the way to undo
	 * a deletion is to remember which channel it was.
	 */
	public Scene unkeyed(String subject, String channel, int at) {
		Track track = track(subject, channel);
		return track == null ? this : with(track.without(at));
	}

	// -------------------------------------------------------------- the sampling

	/**
	 * What a number is at a moment, or the fallback where the scene says nothing.
	 *
	 * The fallback is the caller's business and it matters: for a bone it is
	 * whatever the pose already was, so an unanimated arm keeps doing what it was
	 * doing rather than snapping to zero. A scene animates what it mentions and
	 * nothing else.
	 */
	public float valueAt(String subject, String channel, double time, float fallback) {
		Track track = track(subject, channel);
		if (track == null || !track.speaks()) return fallback;
		// Folded through the part's own loop, and this is the only place it happens.
		// Everything that reads a scene — playback, the handles, the capture, the
		// panels — comes through here, so a part that repeats repeats everywhere at
		// once rather than in whichever of those somebody remembered to teach.
		Role role = role(subject);
		return track.valueAt(role == null ? time : role.fold(time));
	}

	// ------------------------------------------------------------------ the cues

	public Scene with(Cue cue) {
		List<Cue> grown = new ArrayList<>(cues);
		grown.add(cue);
		return new Scene(name, length, cast, tracks, grown);
	}

	public Scene without(Cue cue) {
		List<Cue> left = new ArrayList<>(cues);
		left.remove(cue);
		return new Scene(name, length, cast, tracks, left);
	}

	/**
	 * The cues passed between one moment and another, the first one excluded.
	 *
	 * Half open on purpose, and this is the whole reason the method exists rather
	 * than being written out at the call site each time. Playback remembers the
	 * last tick it dealt with and asks for everything since; if the range included
	 * both ends, every cue sitting exactly on a tick boundary would fire twice —
	 * once as the end of one step and once as the start of the next. Scrubbing
	 * backwards gives an empty answer, which is right: a sound is not played
	 * because the cursor went past it in the wrong direction.
	 */
	public List<Cue> between(int after, int upTo) {
		List<Cue> found = new ArrayList<>();
		for (Cue cue : cues) {
			if (cue.at() > after && cue.at() <= upTo) found.add(cue);
		}
		return List.copyOf(found);
	}

	/** The text that should be on the screen at a moment, in the order it started. */
	public List<Cue> showingAt(int tick) {
		List<Cue> found = new ArrayList<>();
		for (Cue cue : cues) {
			if (cue.kind() != Cue.Kind.TEXT) continue;
			if (cue.at() <= tick && tick < cue.ends()) found.add(cue);
		}
		return List.copyOf(found);
	}
}
