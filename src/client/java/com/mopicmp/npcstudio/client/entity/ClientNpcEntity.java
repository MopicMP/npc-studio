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
	 * The longest a change may take, in ticks.
	 *
	 * Six tenths of a second, and it is reached by one pair in the pack: a fighting
	 * stance against a body lying on the floor. Nothing a person does is further
	 * from anything else a person does.
	 */
	private static final float LONGEST_CHANGE = 12f;

	/**
	 * The shortest change worth easing, in ticks.
	 *
	 * Below about a tenth of a second there is nothing left to ease and a change
	 * simply arrives. It is a floor rather than a default: a change is capped at
	 * half of what it is changing into — a fade that outlasts its own animation is
	 * one nobody ever sees the end of — and half of a four-tick flinch is two.
	 */
	private static final float SHORTEST_CHANGE = 2f;

	/**
	 * How much a body has to move, in model pixels, to earn one more tick of change.
	 *
	 * <h2>Where sixteen comes from</h2>
	 *
	 * Measured pairs, with {@code tools/shape-look/change.ps1}. The travel between
	 * two poses comes out like this across a fight:
	 *
	 * <pre>
	 *   a walk into a stance                  32
	 *   a stance into a punch                 70
	 *   a punch into the next punch           76
	 *   a stance into a sword swing           98
	 *   a stance into lying on the floor     161
	 * </pre>
	 *
	 * Two ticks plus a tick per sixteen puts a step at four ticks, an ordinary blow
	 * at six or seven, a big swing at eight and falling over at twelve — which is
	 * the spread that was missing when every one of them took seven.
	 */
	private static final float PIXELS_A_TICK = 16f;

	/** What a change takes when there is no pose to measure. What they all took. */
	private static final float WITHOUT_MEASURING = 7f;

	private String leaving = "";
	private float leavingFrom;
	private boolean leavingCarriesOn;
	private int changedAt = -1;
	private float changesOver = LONGEST_CHANGE;
	private String showing = "";
	private float showingAge;
	private boolean showingCarriesOn;
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
	public void changingTo(String animation, float age, int askedOn, int lasts,
			boolean carriesOn) {
		// The same trap as above, one layer along: a repeat of one animation is a
		// change even though the name has not changed, and without noticing it the
		// second performance has nothing to fade in from.
		if (animation.equals(showing) && askedOn == showingAskedOn) {
			// Remembered every frame, because the frame this one is on now is the
			// frame it will have to be frozen at whenever it is eventually replaced.
			showingAge = age;
			return;
		}
		showingAskedOn = askedOn;
		// The one going out keeps its own age, frozen where it got to. An emote is a
		// timeline and a fading one should stay on the frame it reached rather than
		// carrying on playing to nobody.
		//
		// <h2>Its own age, and that word is the whole bug</h2>
		//
		// This used to freeze the outgoing animation at the age of the one coming
		// in, which is nearly harmless between two gestures and ruinous at the end
		// of one: a gesture that runs out is replaced by a resting animation, and a
		// resting animation is timed from the entity's whole life rather than from a
		// start. So the outgoing one was frozen at a time in the thousands.
		//
		// Emotes loop. Fifteen in the pack loop a single tick at the very end, which
		// is how the format writes "hold the last pose", so a time in the thousands
		// resolves to the last frame — and for a flinch borrowed from the opening of
		// a death, the last frame is the body flat on the floor. It was then drawn at
		// full strength for the length of the change.
		//
		// Which is exactly what was reported: no falling, just lying, and up again.
		leaving = showing;
		leavingFrom = showingAge;
		leavingCarriesOn = showingCarriesOn;
		changedAt = tickCount;
		changesOver = changeOver(leaving, leavingFrom, animation, age, lasts);
		showing = animation;
		showingAge = age;
		showingCarriesOn = carriesOn;
	}

	/**
	 * How long this particular change should take.
	 *
	 * <h2>Why it is not one number</h2>
	 *
	 * It was, and one number cannot be right for every pair. Seven ticks was spent
	 * equally on a wrist moving a hand's breadth and on a whole body turning over,
	 * so small changes dragged and big ones snapped — which is what "the changes
	 * between animations are not smooth enough" is, when you go and measure it.
	 *
	 * How far the limbs have to travel is written in the two animations. Nobody has
	 * to author it, no table has to be kept in step with the pack, and an animation
	 * from a mod nobody has ever seen gets an answer of the same quality as a
	 * shipped one.
	 */
	private static float changeOver(String from, float fromAge, String to, float toAge,
			int lasts) {
		// Where one of the two is not an emote at all — a built-in gesture, or a pack
		// this client has not loaded — there is nothing to measure and this is what
		// every change used to take. A middling answer, and it is the honest one:
		// not knowing how far it is should not read as knowing it is far.
		float over = WITHOUT_MEASURING;
		var going = com.mopicmp.npcstudio.client.emote.EmoteLibrary.emote(from);
		var coming = com.mopicmp.npcstudio.client.emote.EmoteLibrary.emote(to);
		if (going.isPresent() && coming.isPresent()) {
			double travel = going.get().poseAt(going.get().timeAt(fromAge))
				.travelTo(coming.get().poseAt(coming.get().timeAt(toAge)));
			over = SHORTEST_CHANGE + (float) (travel / PIXELS_A_TICK);
		}
		// A change into a bounded gesture is still cut to fit it, whatever the poses
		// say. Half a flinch spent arriving at the flinch is as much as it can bear.
		if (lasts > 0) over = Math.min(over, lasts / 2f);
		return Math.clamp(over, SHORTEST_CHANGE, LONGEST_CHANGE);
	}

	public String leavingAnimation() {
		return leaving;
	}

	/**
	 * Where the animation being faded out has got to.
	 *
	 * <h2>A stance keeps going; a gesture holds where it stopped</h2>
	 *
	 * The difference is whether it was interrupted or finished. A swing that has
	 * ended should hold its last frame — it is over, and playing on into whatever
	 * the file does next is playing to nobody. But a stance, a walk or a run has no
	 * end at all: it was replaced mid-stride, and freezing it there stops the legs
	 * dead while the new pose slides in over them. That reads as a hitch every time
	 * she stops walking, which is often.
	 */
	public float leavingAge(float partial) {
		if (!leavingCarriesOn) return leavingFrom;
		return leavingFrom + (tickCount - changedAt) + partial;
	}

	/** How far through the change: nought as it begins, one when it is done. */
	public float changedBy(float partial) {
		if (changedAt < 0 || leaving.isEmpty()) return 1f;
		float since = tickCount - changedAt + partial;
		if (since >= changesOver) return 1f;
		float t = Math.max(0f, since) / changesOver;
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
