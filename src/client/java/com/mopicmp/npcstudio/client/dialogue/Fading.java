package com.mopicmp.npcstudio.client.dialogue;

import java.util.ArrayList;
import java.util.List;

import com.mopicmp.npcstudio.net.ShowPortraitPayload;

/**
 * What is arriving, what is leaving, and how far through each of them is.
 *
 * <h2>Why a portrait fades one layer at a time</h2>
 *
 * Because a figure is a stack, and nearly every change to one changes a single piece of
 * it. A scene that swaps an expression sends the same body, the same cloak and a
 * different face — and fading the whole figure out and back in would make the body flash
 * for no reason anybody watching could name. Worse, a cross-fade of a figure against
 * itself is a ghost: two copies of the same shoulder at half strength.
 *
 * So the comparison is per layer. A picture that is in both stacks was never away and is
 * drawn solid; one that has gone fades out; one that has come fades in. The whole-figure
 * case falls out of the same rule without being a case: two entirely different portraits
 * share no layers, so every layer of one leaves while every layer of the other arrives,
 * which is a cross-fade.
 *
 * <h2>Why the arithmetic is here and not in the drawing</h2>
 *
 * Because it is the part that can be got wrong quietly. A layer whose arrival time is
 * reset when it did not actually arrive flickers once, briefly, in a scene somebody is
 * playing — which is exactly the sort of thing that is impossible to catch by looking and
 * trivial to catch by asking. Nothing in this file touches the screen.
 */
public final class Fading {

	private Fading() { }

	/**
	 * How long a picture takes to arrive or leave, in milliseconds.
	 *
	 * <h2>Why it is a constant and not something an author sets</h2>
	 *
	 * Because at this length there is nothing to set. Two hundred milliseconds is long
	 * enough that nothing snaps and short enough that a hard cut still reads as a cut —
	 * so a writer wanting an abrupt change already has one, and a writer wanting a slow
	 * dissolve is asking for a different feature with a different name.
	 *
	 * If a scene ever genuinely needs the choice, it becomes a field on the act, and it
	 * costs a codec field and two rows in a panel. Until something asks, a number that
	 * nobody has to think about is worth more than a setting nobody would change.
	 */
	public static final long OVER = 200;

	/** A picture on screen, and when it got there. */
	public record Held(ShowPortraitPayload.Layer layer, long since) { }

	/**
	 * A picture on its way out, with the shape it was being drawn in when it left.
	 *
	 * It carries its own canvas and side because those may have changed underneath it.
	 * A portrait crossing from one edge of the screen to the other has a figure leaving
	 * on the right while another arrives on the left, and a departing layer measured
	 * against the arriving figure's canvas would slide as it faded — which reads as a
	 * bug rather than as an exit.
	 */
	public record Going(ShowPortraitPayload.Layer layer, int wide, int high,
			int side, boolean mirrored, long since) { }

	/**
	 * How far through a fade something is: nought at its start, one when it is over.
	 *
	 * Clamped at both ends. A clock that jumps — and it does, when a world loads or a
	 * game is paused — would otherwise give a picture drawn at a negative strength or at
	 * six times its own, and neither of those is a thing a screen can show.
	 */
	public static float through(long since, long clock) {
		if (OVER <= 0) return 1f;
		return (float) Math.clamp((clock - since) / (double) OVER, 0.0, 1.0);
	}

	/**
	 * The new stack, with each picture keeping the time it actually arrived.
	 *
	 * <h2>The whole point of this method</h2>
	 *
	 * A picture that was already on screen must not be told it has just arrived. Told
	 * that, it fades in from nothing every time anything else about the scene changes —
	 * so a character blinks out and back once per line, and the cause is invisible to
	 * anybody watching.
	 *
	 * Sameness is the whole layer: the picture and where its corner is. A picture that
	 * has moved is not the same thing standing still, and letting it jump would be a
	 * worse artefact than the fade it gets instead.
	 */
	public static List<Held> arriving(List<Held> was, List<ShowPortraitPayload.Layer> now,
			long clock) {
		List<Held> holding = new ArrayList<>();
		for (ShowPortraitPayload.Layer layer : now) {
			long since = clock;
			for (Held had : was) {
				if (had.layer().equals(layer)) {
					since = had.since();
					break;
				}
			}
			holding.add(new Held(layer, since));
		}
		return holding;
	}

	/**
	 * The pictures that were there and are not any more, ready to fade out.
	 *
	 * They start leaving now, whenever they arrived: how long a picture stood there has
	 * nothing to do with how long it takes to go.
	 */
	public static List<Going> departing(List<Held> was, List<ShowPortraitPayload.Layer> now,
			int wide, int high, int side, boolean mirrored, long clock) {
		List<Going> leaving = new ArrayList<>();
		for (Held had : was) {
			if (now.contains(had.layer())) continue;
			leaving.add(new Going(had.layer(), wide, high, side, mirrored, clock));
		}
		return leaving;
	}

	/**
	 * The ones still going, with anything that has finished — or come back — taken out.
	 *
	 * <h2>Why coming back has to be handled at all</h2>
	 *
	 * Because a scene that takes a cloak off and puts it straight back on is ordinary,
	 * and left alone the old copy would go on fading out underneath the new one that is
	 * fading in. Two copies of one picture at partial strength are darker than one at
	 * full, so the cloak would visibly dip and recover. Taking the departing copy out the
	 * moment its picture returns is the whole fix.
	 */
	public static List<Going> stillGoing(List<Going> going, List<ShowPortraitPayload.Layer> now,
			long clock) {
		List<Going> left = new ArrayList<>();
		for (Going each : going) {
			if (now.contains(each.layer())) continue;
			if (through(each.since(), clock) >= 1f) continue;
			left.add(each);
		}
		return left;
	}

	/**
	 * A strength as the colour a picture is drawn through, or nought to skip it.
	 *
	 * White with an alpha, which is a tint that changes nothing but the strength. Below
	 * one part in two hundred and fifty-six there is no colour left to draw with, so it
	 * is reported as nothing rather than as an invisible draw call.
	 */
	public static int tint(float strength) {
		int alpha = Math.round(Math.clamp(strength, 0f, 1f) * 255f);
		return alpha <= 0 ? 0 : (alpha << 24) | 0x00FFFFFF;
	}
}
