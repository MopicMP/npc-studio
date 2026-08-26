package com.mopicmp.npcstudio.client.entity;

import java.util.function.Supplier;

import com.mopicmp.npcstudio.entity.NpcEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.entity.ClientAvatarState;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.level.Level;

/**
 * The NPC as it exists on the client.
 *
 * It carries no logic of its own. It exists because {@code AvatarRenderer} is
 * declared as {@code <T extends Avatar & ClientAvatarEntity>}, and
 * {@code ClientAvatarEntity} is a client-only interface that a class in the
 * common source set cannot implement. Vanilla has the same pair —
 * {@code Mannequin} and {@code ClientMannequin} — so this is the marked path
 * rather than a workaround.
 *
 * Everything the interface asks for is about drawing: which skin, which parrot,
 * whether to draw the deadmau5 ears. None of it belongs on the server.
 */
public class ClientNpcEntity extends NpcEntity implements ClientAvatarEntity {

	/**
	 * The state behind the walk and cloak animation.
	 *
	 * This has to be ticked by hand — see {@link #tick()}. That answers a
	 * question left open in phase 0: an Avatar does not animate its own walking
	 * just because the server moved it. The motion is fed in here, and the model
	 * reads the result back out.
	 */
	private final ClientAvatarState avatarState = new ClientAvatarState();

	/**
	 * Looks the skin up once and keeps the handle.
	 *
	 * The cache resolves a profile in the background, so this can be asked every
	 * frame without touching the network. It is rebuilt when the profile changes
	 * rather than on every call, since a lookup per frame would defeat the point.
	 */
	private Supplier<PlayerSkinRenderCache.RenderInfo> skin;

	public ClientNpcEntity(EntityType<? extends LivingEntity> type, Level level) {
		super(type, level);
		refreshSkin();
	}

	private void refreshSkin() {
		PlayerSkinRenderCache cache = Minecraft.getInstance().playerSkinRenderCache();
		skin = cache.createLookup(getProfile());
	}

	@Override
	public void tick() {
		super.tick();
		// Feeding the movement in is what makes the legs move; without it the NPC
		// slides around in a fixed pose, which reads as a broken model rather than
		// a missing feature.
		avatarState.tick(position(), getDeltaMovement());
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
		super.onSyncedDataUpdated(key);
		// The profile arrives from the server after the entity is built, so the
		// first lookup would otherwise stick to whatever the default was.
		refreshSkin();

		// A gesture is timed from the moment it turns up here.
		//
		// Watching the tick the server asked on, not the name. The comment that used
		// to be here said exactly this — "the same gesture asked for twice should
		// start again, it is a new performance" — and the code compared names, which
		// does the opposite: the second ask changed nothing and the first frame never
		// came back.
		//
		// It is not a corner case. A chain of three blows wraps, so the third and the
		// first are separated by a pause and then repeat; a flinch answers a second
		// hit; a style with one swing repeats every time. In all of them the
		// character does the movement once and then stands holding its last frame,
		// which is the wooden thing this whole piece of work is about.
		int began = gestureBegan();
		if (began != gestureAskedOn || !gesture().equals(gestureSeen)) {
			gestureAskedOn = began;
			gestureSeen = gesture();
			gestureSeenAt = tickCount;
		}
	}

	@Override
	public ClientAvatarState avatarState() {
		return avatarState;
	}

	/**
	 * The uploaded picture if there is one, and the named player's otherwise.
	 *
	 * Asking for a skin that has not arrived yet is not a failure — it is the
	 * first frame. The character wears the fallback for a moment and changes once
	 * the picture lands, which is the same thing that happens to every player
	 * whose skin is still being fetched.
	 */
	@Override
	public PlayerSkin getSkin() {
		// A costume held up in the wardrobe wins over what the character owns, and
		// only on the client holding it up.
		PlayerSkin trying = com.mopicmp.npcstudio.client.wardrobe.TryingOn.of(getId());
		if (trying != null) return trying;

		String mark = skinMark();
		if (!mark.isEmpty()) {
			PlayerSkin uploaded = com.mopicmp.npcstudio.client.skin.CustomSkins.get(mark);
			if (uploaded != null) return uploaded;
			com.mopicmp.npcstudio.client.skin.CustomSkins.request(getId());
		}
		return skin.get().playerSkin();
	}

	/**
	 * A time to play at, set by the picker and by nothing else.
	 *
	 * An NPC in the world takes its timing from the tick count and the partial
	 * tick, which is right for it and useless for a figure that is not in a
	 * world: the partial tick belongs to a clock this one is not on, so the
	 * animation would jitter by up to a tick either way. The preview keeps its
	 * own clock in fractions of a tick and hands it over whole.
	 *
	 * Not a number when nothing has set it, which is the ordinary case.
	 */
	private float previewAge = Float.NaN;

	/**
	 * When this client saw the gesture change, on this client's own clock.
	 *
	 * The server sends the tick it started the gesture on, and that number is
	 * meaningless here: a client's copy of an entity starts counting from zero
	 * when it comes into view, while the server's has been counting since the NPC
	 * was placed. Subtracting one from the other gave an age of minus several
	 * thousand, and the emote sat before its own first frame until the clocks
	 * happened to cross — which looked like an animation that fired at random,
	 * three conversations later.
	 *
	 * So the arrival is what is timed. Everyone watching still sees the same
	 * gesture; they see it from when they were told about it, which for something
	 * a conversation just triggered is the same moment.
	 */
	private int gestureSeenAt = -1;
	private String gestureSeen = "";

	/** The server's tick for the gesture we are already showing — its identity. */
	private int gestureAskedOn = Integer.MIN_VALUE;

	/** How long this client has been showing the current gesture, in ticks. */
	public float localGestureAge(float partial) {
		if (gestureSeenAt < 0) return 0;
		return tickCount - gestureSeenAt + partial;
	}

	// ------------------------------------------------------- changing animation

	/**
	 * Over how many ticks one animation becomes another.
	 *
	 * A third of a second. Long enough to read as a movement, short enough that a
	 * character who has just noticed you does not appear to think about it first.
	 */
	private static final float CHANGES_OVER = 7f;

	private String leaving = "";
	private float leavingFrom;
	private int changedAt = -1;
	private String showing = "";
	private int showingAskedOn = Integer.MIN_VALUE;

	/**
	 * Notices that the animation has changed, and starts the change.
	 *
	 * <h2>Kept here rather than in the renderer</h2>
	 *
	 * Because the renderer runs once per frame and this has to happen once per
	 * change. At sixty frames a second, a fade started in the renderer would be
	 * restarted sixty times a second and never get anywhere — it would look exactly
	 * like the snap it was meant to cure, which is the worst kind of bug to have
	 * written: one whose symptom is identical to the one it replaced.
	 *
	 * The entity has the clock and the memory, so the entity notices.
	 */
	public void changingTo(String animation, float age, int askedOn) {
		// The same trap as above, one layer along: a repeat of one animation is a
		// change even though the name has not changed, and without noticing it the
		// second performance has nothing to fade in from.
		if (animation.equals(showing) && askedOn == showingAskedOn) return;
		showingAskedOn = askedOn;
		// The one going out keeps its own age, frozen where it got to. An emote is a
		// timeline and a fading one should stay on the frame it reached rather than
		// carrying on playing to nobody.
		leaving = showing;
		leavingFrom = age;
		changedAt = tickCount;
		showing = animation;
	}

	public String leavingAnimation() {
		return leaving;
	}

	public float leavingAge(float partial) {
		return leavingFrom;
	}

	/** How far through the change: nought as it begins, one when it is done. */
	public float changedBy(float partial) {
		if (changedAt < 0 || leaving.isEmpty()) return 1f;
		float since = tickCount - changedAt + partial;
		if (since >= CHANGES_OVER) return 1f;
		float t = Math.max(0f, since) / CHANGES_OVER;
		// Eased at both ends, so the change leaves one pose and settles into the
		// other rather than starting and stopping at corners.
		return t * t * (3f - 2f * t);
	}

	public void previewAge(float age) {
		previewAge = age;
	}

	public float previewAge() {
		return previewAge;
	}

	/** NPCs carry no parrots. */
	@Override
	public Parrot.Variant getParrotVariantOnShoulder(boolean leftShoulder) {
		return null;
	}

	/** The deadmau5 ears are a joke about one specific player, not about NPCs. */
	@Override
	public boolean showExtraEars() {
		return false;
	}
}
