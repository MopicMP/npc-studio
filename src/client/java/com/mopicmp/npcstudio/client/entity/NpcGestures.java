package com.mopicmp.npcstudio.client.entity;

import com.mopicmp.npcstudio.client.emote.Emote;
import com.mopicmp.npcstudio.client.emote.EmoteApplier;
import com.mopicmp.npcstudio.client.emote.EmoteLibrary;

import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.util.Mth;

/**
 * The few things an NPC can do with its body.
 *
 * Built in rather than loaded, and deliberately a short list. The real
 * animation system reads Blockbench files and drives arbitrary bones — that is
 * the same piece of work as the GeckoLib support and is waiting for it. What
 * this is for is the gap in between: a conversation where the speaker nods or
 * points is worth far more than one where the speaker stands rigid, and it does
 * not need a file format to arrive first.
 *
 * Everything here poses the vanilla player model's six parts. When the real
 * system lands, these become the fallback for an NPC with no model of its own,
 * which is most of them.
 */
public final class NpcGestures {

	/** After this many ticks a gesture has run its course and the body relaxes. */
	private static final int LENGTH = 40;

	private NpcGestures() { }

	public static boolean known(String name) {
		return builtIn(name) || EmoteLibrary.known(name);
	}

	/**
	 * Whether a gesture nobody gave a length has visibly finished.
	 *
	 * Only the built-in six ever say yes. They are a there-and-back movement —
	 * a wave goes up and comes down — so once the arc is over there is nothing
	 * being shown, and leaving the name set would keep the character out of its
	 * resting animation while displaying nothing at all. That was the shape of
	 * the bug: a nod that ended left an NPC standing plainly for good.
	 *
	 * An emote is different and deliberately says no. It ends wherever the
	 * animator left it, so holding the last frame is a real answer to "no length
	 * given" — a guard told to stand at attention should stand at attention.
	 */
	public static boolean runsOut(String name, float age) {
		return builtIn(name) && age > LENGTH;
	}

	/**
	 * How long this animation runs if left to itself, in ticks.
	 *
	 * Answered on the client, because only the client has the pack — the server
	 * knows an animation by name and nothing more. So this is for the editor to
	 * ask while somebody is choosing, and the number it fills in travels in the
	 * file. That is the right way round anyway: a dialogue that stores what it
	 * meant plays the same next year, whereas one that asked the pack at run time
	 * would quietly change when the pack did.
	 *
	 * A looping emote has no natural end, so its answer is one pass.
	 */
	public static int lengthOf(String name) {
		if (name == null || name.isEmpty()) return 0;
		if (builtIn(name)) return LENGTH;
		return EmoteLibrary.emote(name).map(Emote::length).orElse(LENGTH);
	}

	private static boolean builtIn(String name) {
		return switch (name) {
			case "wave", "nod", "shake", "point", "shrug", "think" -> true;
			default -> false;
		};
	}

	/**
	 * Poses the model for a gesture.
	 *
	 * A name that belongs to an emote pack is handed straight to the player for
	 * it. The two kinds are told apart here rather than by whoever is asking,
	 * because to everything upstream — the dialogue, the editor, the network —
	 * an animation is just a name.
	 *
	 * @param age how many ticks in, so a gesture that has finished simply stops
	 *            being applied and the vanilla pose shows through
	 * @param leaving how much of the pose to show, nought to one. One almost
	 *                always; below it only while a gesture with a set length is
	 *                giving the body back
	 */
	public static void apply(PlayerModel model, String name, float age, float leaving) {
		if (!builtIn(name)) {
			EmoteLibrary.emote(name).ifPresent(emote -> EmoteApplier.apply(model, emote, age, leaving));
			return;
		}
		if (age < 0 || age > LENGTH) return;
		float t = age / LENGTH;
		// Fades in and out at the ends, so a gesture starts and finishes from the
		// resting pose instead of snapping into and out of it. A built-in gesture
		// therefore has its own ending already, and the two multiply rather than
		// argue: cutting one short still fades, just sooner.
		float strength = Mth.sin(t * Mth.PI) * leaving;

		switch (name) {
			case "wave" -> {
				// The arm goes up and the forearm swings; a raised arm that does not
				// move reads as a salute rather than a greeting.
				model.rightArm.xRot = -2.0f * strength;
				model.rightArm.zRot = (0.4f + Mth.cos(age * 0.6f) * 0.35f) * strength;
			}
			case "nod" -> model.head.xRot += Mth.sin(age * 0.5f) * 0.35f * strength;
			case "shake" -> model.head.yRot += Mth.sin(age * 0.5f) * 0.5f * strength;
			case "point" -> {
				model.rightArm.xRot = -1.5f * strength;
				model.rightArm.yRot = -0.2f * strength;
			}
			case "shrug" -> {
				model.rightArm.zRot = 0.9f * strength;
				model.leftArm.zRot = -0.9f * strength;
				model.rightArm.xRot = -0.4f * strength;
				model.leftArm.xRot = -0.4f * strength;
			}
			case "think" -> {
				// Hand towards the chin, head tilted down a little.
				model.rightArm.xRot = -2.2f * strength;
				model.rightArm.zRot = -0.5f * strength;
				model.head.xRot += 0.2f * strength;
			}
			default -> { }
		}
	}
}
