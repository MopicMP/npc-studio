package com.mopicmp.npcstudio.client.entity;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

/**
 * The vanilla avatar renderer, plus whatever the NPC is doing with its hands.
 *
 * Subclassed rather than mixed into, because everything vanilla does here is
 * wanted: the skin, the cape, the held item, every layer. The only addition is
 * carrying the gesture across to the model, and that is one line in a method
 * that already exists for exactly this purpose.
 */
public class NpcRenderer extends AvatarRenderer<ClientNpcEntity> {

	/**
	 * Over how many ticks a finishing gesture gives the body back.
	 *
	 * A quarter of a second. Emotes do not end anywhere near the resting pose —
	 * a bow ends bowed — so cutting one off at its last tick snaps the character
	 * upright in a single frame, which reads as the animation breaking rather
	 * than finishing. Long enough to be a movement, short enough that nobody
	 * waits for it.
	 *
	 * The built-in gestures already fade themselves over their whole length, so
	 * this only ever has anything to do for emotes from a pack.
	 */
	private static final float LEAVING = 5f;

	public NpcRenderer(EntityRendererProvider.Context context, boolean slim) {
		super(context, slim);
	}

	/** How much of the pose is left, given how many ticks remain of it. */
	private static float leaving(float remaining) {
		if (remaining >= LEAVING) return 1f;
		// Smoothed rather than straight, so the last moment of the movement slows
		// into the rest instead of arriving at a corner.
		float t = Math.max(0f, remaining) / LEAVING;
		return t * t * (3f - 2f * t);
	}

	@Override
	public void extractRenderState(ClientNpcEntity npc, AvatarRenderState state, float partial) {
		super.extractRenderState(npc, state, partial);
		// Where the scene says this character is, if it is in one. Written over the
		// state after vanilla has filled it in, which is the whole trick: a scene
		// moves a character by drawing it somewhere else, not by moving it. Nothing
		// is sent, nothing on the server changes, and scrubbing back and forth over
		// a scene twenty times does not shove anybody around a shared world.
		var staged = com.mopicmp.npcstudio.client.scene.Staging.place(npc, state, partial);
		// The age is taken here rather than in the model, because this is the only
		// place that still has the entity — by the time the model poses itself
		// there is nothing left but the state.
		// A preview keeps its own clock and hands over a time directly; anything in
		// a world is timed by the world, which is the only clock it shares with the
		// server that started the gesture.
		float forced = npc.previewAge();
		float age = Float.isNaN(forced) ? npc.localGestureAge(partial) : forced;

		// A gesture from a conversation wins while it is running; the rest of the
		// time the character falls back on how it is standing or moving. Timed from
		// the entity's own age rather than from a start, because a resting
		// animation has no beginning — it has been going on as long as the NPC has.
		//
		// A gesture that was given a length is dropped here the moment it runs out,
		// without waiting for the server to say so. The server clears it too, a
		// packet later; doing it locally as well is what stops the character
		// holding its last frame for the length of the round trip.
		String gesture = npc.gesture();
		int ticks = npc.gestureTicks();
		float strength = 1f;
		if (!gesture.isEmpty() && ticks > 0) {
			if (age >= ticks) gesture = "";
			else strength = leaving(ticks - age);
		} else if (NpcGestures.runsOut(gesture, age)) {
			// No length was asked for, but this one is over regardless: the built-in
			// gestures are a there-and-back arc rather than a pose to hold.
			gesture = "";
		}
		if (gesture.isEmpty()) {
			gesture = npc.restingAnimation();
			age = npc.tickCount + partial;
			strength = 1f;
		}
		GestureHolder holder = (GestureHolder) state;
		// Carried across to the model, which is where the pose is actually put on.
		// By then the entity is gone — a model is handed a render state and nothing
		// else — so this is the last place that can hand it over.
		holder.npcStudio$setStaged(staged);
		// A build being dragged through a slider wins over the one the server knows,
		// but only on the client doing the dragging and only while it is open.
		var editing = ShapeEditing.of(npc.getId());
		holder.npcStudio$setShape(editing != null ? editing : npc.bodyShape());
		holder.npcStudio$setGesture(gesture, age, strength);
		npc.changingTo(gesture, age);
		holder.npcStudio$setLeaving(npc.leavingAnimation(), npc.leavingAge(partial),
			npc.changedBy(partial));
		// The skin is read before anything is drawn over it. Nothing is assumed:
		// a face with no eyes on it does not blink, because there is nothing there
		// to shut and a lid would be a rectangle stamped over somebody's design.
		//
		// A face somebody marked out by hand in the eye grid wins over any reading
		// of the pixels, always. That is the whole point of having marked it: the
		// reading is right about the eye's rows on twenty-nine faces in thirty-eight,
		// and a person is right on all of them.
		// The whole mask, at the size somebody marked it, rather than the
		// eighth-scale view of it. On a 256-wide skin that view is one cell per
		// four texels by four, and every complaint the eyes ever attracted was
		// that rounding wearing a different hat.
		// Read from the text rather than from a decoded mask, and remembered against
		// it: this runs for every character in sight every frame, and reading a face
		// marked at the finest size is a walk over sixty-five thousand cells.
		String marked = npc.faceMaskText();
		var face = marked != null && !marked.isBlank()
			? com.mopicmp.npcstudio.client.skin.FaceReading.forMask(marked, npc.faceByHand())
			: com.mopicmp.npcstudio.client.skin.SkinPixels.forNpc(npc);

		// Worked out from the character's own id and age, so every client shows the
		// same blink and the same glance at the same moment without anybody sending
		// anything.
		float clock = npc.tickCount + partial;
		var watched = npc.level().getNearestPlayer(npc, NOTICES);
		// What an author asked the face to do, if anything. It biases the ambient
		// movements rather than replacing them: an angry character still blinks, and
		// still follows you round the room — it does both with narrowed eyes.
		var mood = npc.expression();

		// Whichever has the lids furthest down. A character squinting into the sun
		// still blinks, a blink still shuts an eye that was already half closed, and
		// being hit wins over both — nobody keeps their eyes open through a blow
		// because they happened to be blinking.
		float shut = Math.max(Lids.wince(npc.hurtTime), Math.max(
			// A frightened character blinks quickly and a startled one barely at all,
			// so the mood changes the clock rather than the movement.
			Blinking.shut(npc.getId(), clock * mood.blinks()) + mood.lids(),
			com.mopicmp.npcstudio.client.NpcStudioConfig.get().eyeDroop
				? Lids.rest(lit(npc), npc.level().getDefaultClockTime(), unwell(npc))
				: 0f));
		// The one movement a face makes on one side only. Timed from when the
		// expression began, which is the only thing about an expression that cannot
		// be worked out from the clock.
		float winks = mood.winks() ? Blinking.wink((npc.expressionAge() + partial) / 20f) : 0f;
		boolean winksRight = Blinking.winksRight(npc.getId());

		holder.npcStudio$setEyes(face != null && face.hasEyes()
			? new com.mopicmp.npcstudio.client.skin.Eyes(face,
				Math.max(shut, winksRight ? winks : 0f),
				Math.max(shut, winksRight ? 0f : winks),
				Gazing.aside(npc.getId(), clock, slack(npc, watched), watched != null),
				Math.clamp(Gazing.upDown(npc.getId(), clock, rise(npc, watched), watched != null)
					+ mood.look(), -1f, 1f),
				Math.clamp(Pupils.wide(npc.getId(), clock, lit(npc), near(npc, watched))
					+ mood.pupils(), -1f, 1f),
				brows(npc, mood, winksRight))
			: com.mopicmp.npcstudio.client.skin.Eyes.NONE);
	}

	/**
	 * Where the brows sit: what an author asked for, and what a blow does to them.
	 *
	 * <h2>The one side that is not a side</h2>
	 *
	 * A raised eyebrow is one eyebrow, and which one is the character's own affair.
	 * It borrows the side the character winks with, so that every lopsided thing a
	 * face does happens on the same side of it — a character that winks with its
	 * left and raises its right brow does not read as one character.
	 *
	 * <h2>Why a flinch is here rather than in the expression</h2>
	 *
	 * Because it is not one. Brows drawn down and knotted by a blow is an answer to
	 * something that happened, worked out from {@code hurtTime} — a number the game
	 * already keeps and already sends to everybody — so it costs nothing and needs
	 * no author. It is added to whatever face is being held rather than replacing
	 * it, which is right: a character that is hit while it is happy stops looking
	 * happy for half a second and then is happy again.
	 */
	private static com.mopicmp.npcstudio.client.skin.Brows brows(ClientNpcEntity npc,
			com.mopicmp.npcstudio.entity.Expression mood, boolean oddIsRight) {
		float flinch = Lids.wince(npc.hurtTime);
		float raise = mood.browRaise();
		// Down and together, which is what a face does when it is struck.
		float right = (mood.browOdd() && !oddIsRight ? 0f : raise) - flinch;
		float left = (mood.browOdd() && oddIsRight ? 0f : raise) - flinch;

		return new com.mopicmp.npcstudio.client.skin.Brows(
			Math.clamp(right, -1f, 1f), Math.clamp(left, -1f, 1f),
			Math.clamp(mood.browSlant() - flinch, -1f, 1f));
	}

	/**
	 * How lit the character is, 0 to 15.
	 *
	 * Taken at its eyes rather than at its feet, since that is where the light
	 * reaching a pupil arrives — and a character standing in a doorway is often
	 * lit at the head and dark at the boots.
	 *
	 * Asked of the client's own copy of the world, which is the only place this is
	 * ever needed: nobody else has to agree about it, because everyone can see the
	 * same torches.
	 */
	private static int lit(ClientNpcEntity npc) {
		// A character who cannot see the light, or who can see without it, has the
		// eyes of somebody standing in the dark. Blindness and night vision arrive
		// at the same face from opposite directions, and both are already
		// synchronised to every client — nothing new has to be sent for a potion to
		// show in somebody's eyes.
		if (npc.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)
			|| npc.hasEffect(net.minecraft.world.effect.MobEffects.NIGHT_VISION)) return 0;
		return npc.level().getMaxLocalRawBrightness(npc.blockPosition().above());
	}

	/**
	 * How ill the character looks, nought to one.
	 *
	 * Read off the effects it is already carrying rather than from anything new.
	 * Wither counts for more than poison because it is worse, and several at once
	 * are worse than one — but never past a face that has closed its eyes, which is
	 * unconsciousness rather than illness.
	 */
	private static float unwell(ClientNpcEntity npc) {
		float ill = 0f;
		if (npc.hasEffect(net.minecraft.world.effect.MobEffects.POISON)) ill += 0.5f;
		if (npc.hasEffect(net.minecraft.world.effect.MobEffects.WITHER)) ill += 0.7f;
		if (npc.hasEffect(net.minecraft.world.effect.MobEffects.WEAKNESS)) ill += 0.35f;
		if (npc.hasEffect(net.minecraft.world.effect.MobEffects.MINING_FATIGUE)) ill += 0.3f;
		return Math.min(1f, ill);
	}

	/**
	 * How far the head is short of what it is looking at, upright, in degrees.
	 *
	 * Positive when that is above the character — a player standing on a block, or
	 * simply being taller. The same subtraction as sideways, and it matters more
	 * than it sounds: a character whose eyes never go up has been looking at
	 * everybody's chest.
	 */
	private static float rise(ClientNpcEntity npc,
			net.minecraft.world.entity.player.Player watched) {
		if (watched == null) return 0f;

		double dx = watched.getX() - npc.getX();
		double dz = watched.getZ() - npc.getZ();
		double dy = watched.getEyeY() - npc.getEyeY();
		double flat = Math.sqrt(dx * dx + dz * dz);
		float towards = (float) Math.toDegrees(Math.atan2(dy, Math.max(0.001, flat)));
		// A head looking up has a negative pitch, so what it has already managed is
		// the other way about.
		return towards + npc.getXRot();
	}

	/** How far off the nearest person is, or far enough not to matter. */
	private static float near(ClientNpcEntity npc,
			net.minecraft.world.entity.player.Player watched) {
		return watched == null ? Float.MAX_VALUE : (float) Math.sqrt(npc.distanceToSqr(watched));
	}

	/** How far away anybody has to be before a character stops noticing them. */
	private static final double NOTICES = 12;

	/**
	 * How far the head is short of what it is looking at, in degrees.
	 *
	 * Positive to the character's own right. This is the whole reason the glance
	 * has anything to do: the head already turns towards whoever is near, so if
	 * the eyes followed the same target they would sit dead centre for ever. The
	 * head only turns so far, though — vanilla stops it well short of the
	 * shoulder — so somebody standing off to the side leaves an angle over, and
	 * that leftover is what the eyes take up.
	 */
	private static float slack(ClientNpcEntity npc, net.minecraft.world.entity.player.Player watched) {
		if (watched == null) return 0f;

		double dx = watched.getX() - npc.getX();
		double dz = watched.getZ() - npc.getZ();
		float towards = (float) (Math.atan2(dz, dx) * (180 / Math.PI)) - 90f;
		return net.minecraft.util.Mth.wrapDegrees(towards - npc.getYHeadRot());
	}
}
