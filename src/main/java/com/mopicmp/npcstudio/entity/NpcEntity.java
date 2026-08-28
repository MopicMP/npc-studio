package com.mopicmp.npcstudio.entity;

import java.util.function.BiFunction;

import com.mopicmp.npcstudio.dialogue.runtime.DialogueRuntime;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * An NPC: a player-shaped entity that is not a player.
 *
 * Built on {@link Avatar}, the abstract class Minecraft itself introduced for
 * exactly this — {@code Player} and {@code Mannequin} are its other two
 * subclasses. It gives us the player silhouette, the skin profile, the hideable
 * skin layers and the pose dimensions for free, without dragging in what
 * {@code Player} carries: an inventory, container handling, abilities, none of
 * which an NPC has any use for.
 *
 * Worth recording how little it asks in return: {@code getProfile()} is the
 * only abstract method on the whole chain. That was checked with the compiler
 * rather than assumed, and it is the strongest sign that this is the extension
 * point Mojang intended rather than a class we are leaning on sideways.
 *
 * A mannequin was the other candidate and was ruled out because it cannot move
 * at all — no AI, no pathfinding — and movement is most of what separates an
 * NPC from a statue.
 */
public class NpcEntity extends Avatar {

	/**
	 * Whose skin to wear.
	 *
	 * Synched rather than kept on the server, because the client is what draws
	 * the skin and it has no other way to learn which one. This mirrors how
	 * {@code Mannequin} does it, which is a good sign we are on the marked path.
	 */
	private static final EntityDataAccessor<ResolvableProfile> DATA_PROFILE =
		SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.RESOLVABLE_PROFILE);

	/**
	 * Which dialogue this NPC opens.
	 *
	 * A name, not the dialogue itself. Twenty copies of a guard can point at one
	 * conversation, and fixing a typo in it fixes all twenty — which is most of
	 * what templates would have bought us, without templates.
	 */
	private static final EntityDataAccessor<String> DATA_DIALOGUE =
		SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.STRING);

	/**
	 * The gesture the NPC is making, and when it started.
	 *
	 * Synched because the client is what draws it. The tick is part of the value
	 * rather than something the client works out for itself: two players joining
	 * at different moments must see the same wave at the same point, and a player
	 * arriving halfway through should catch the second half rather than start it
	 * again.
	 */
	private static final EntityDataAccessor<String> DATA_GESTURE =
		SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.STRING);

	/**
	 * The animations an NPC falls back on when nobody is talking to it.
	 *
	 * On the entity rather than in a dialogue, because standing, walking and
	 * jumping are things a character does whether or not it has anything to say —
	 * an NPC with no dialogue at all still has to stand somehow. A gesture from a
	 * conversation overrides these for as long as it runs.
	 *
	 * One accessor per state rather than a packed list, so the client can be told
	 * about a change to one of them without resending the rest.
	 */
	private static final EntityDataAccessor<String> DATA_ANIM_IDLE =
		SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.STRING);

	private static final EntityDataAccessor<String> DATA_ANIM_WALK =
		SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.STRING);

	private static final EntityDataAccessor<String> DATA_ANIM_RUN =
		SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.STRING);

	private static final EntityDataAccessor<String> DATA_ANIM_JUMP =
		SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.STRING);

	/** What an NPC is doing with its body when left to itself. */
	public enum Motion { IDLE, WALK, RUN, JUMP }

	/**
	 * A skin somebody supplied as a file, rather than by naming a player.
	 *
	 * The picture is kept here on the server and handed out on request. What is
	 * synched is only its fingerprint — a client seeing a fingerprint it has no
	 * picture for asks for one, and a client that already has it says nothing.
	 * Sending a few kilobytes to everybody in range every time an entity updates
	 * would be the obvious way and the wrong one.
	 */
	private static final EntityDataAccessor<String> DATA_SKIN_MARK =
		SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.STRING);

	private byte[] customSkin;

	public byte[] customSkin() {
		return customSkin;
	}

	public String skinMark() {
		return entityData.get(DATA_SKIN_MARK);
	}

	public void setCustomSkin(byte[] pixels) {
		customSkin = pixels;
		entityData.set(DATA_SKIN_MARK, pixels == null || pixels.length == 0
			? "" : Integer.toHexString(java.util.Arrays.hashCode(pixels)));
	}

	/**
	 * The looks this character owns, and which one it has on.
	 *
	 * Server-side only. What everyone needs to see is the skin being worn, and
	 * that is already synched — the wardrobe itself only matters to whoever is
	 * editing, so it travels when the editor asks and not before. Sending a
	 * cupboard of skins to every player in range would be paying constantly for
	 * something looked at once.
	 */
	private final java.util.List<Outfit> wardrobe = new java.util.ArrayList<>();
	private int worn = -1;

	/** Adds a look and puts it on, because adding one is how you say you want it. */
	public void addOutfit(Outfit outfit) {
		wardrobe.add(outfit);
		wear(wardrobe.size() - 1);
	}

	/**
	 * Puts on one costume and keeps nothing else.
	 *
	 * The difference from {@link #addOutfit} is the whole point, and it is a bug
	 * fix rather than a preference. Once the world grew a shared wardrobe, every
	 * dressing came through it and every dressing appended — so a character
	 * changed twenty times was carrying twenty whole PNGs in its own saved data,
	 * nineteen of them unreachable, all of them written into the chunk. Skins run
	 * to a couple of hundred kilobytes at the sizes we allow, and the entity was
	 * the only thing growing.
	 *
	 * Dropping the rest is safe precisely because the wardrobe is shared: the
	 * costume is not being thrown away, it is on a shelf that every character can
	 * reach. The character holds what it is wearing, and nothing more.
	 */
	public void dressIn(Outfit outfit) {
		wardrobe.clear();
		addOutfit(outfit);
	}

	public void wear(int index) {
		if (index < 0 || index >= wardrobe.size()) return;
		Outfit outfit = wardrobe.get(index);
		worn = index;
		setFaceMask(outfit.eyes());
		if (outfit.isPicture()) setCustomSkin(outfit.pixels());
		else {
			setCustomSkin(null);
			setSkin(outfit.name());
		}
	}

	/**
	 * Whether she is squared up for a fight.
	 *
	 * Synched, because it changes how she is drawn and drawing happens on the other
	 * side. Everything it produces — the stance, the walk — is worked out from it
	 * there rather than sent, so this is one bit for a whole manner of moving.
	 */
	private static final EntityDataAccessor<Boolean> DATA_GUARD =
		SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.BOOLEAN);

	private static final EntityDataAccessor<Integer> DATA_GESTURE_START =
		SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.INT);

	/**
	 * The face this character is making, and for how long.
	 *
	 * Synchronised rather than worked out, because unlike everything else the eyes
	 * do, an expression is an <b>event</b>: somebody decided, at a moment, that
	 * this line is said angrily. A blink can be recomputed from the clock on every
	 * client and always agree; a decision cannot.
	 *
	 * Held as a name rather than a number so that a dialogue naming an expression
	 * this version has never heard of still loads, and simply makes an ordinary
	 * face.
	 */
	private static final EntityDataAccessor<String> DATA_EXPRESSION =
		SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.STRING);

	private static final EntityDataAccessor<Integer> DATA_EXPRESSION_START =
		SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.INT);

	private static final EntityDataAccessor<Integer> DATA_EXPRESSION_TICKS =
		SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.INT);

	private static final EntityDataAccessor<Integer> DATA_GESTURE_TICKS =
		SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.INT);

	/**
	 * How the character is built, packed into eight bytes.
	 *
	 * Synched because everyone who can see the character has to draw it, and
	 * entity data is the one channel that handles somebody walking into view
	 * later without anybody arranging it.
	 */
	private static final EntityDataAccessor<Long> DATA_SHAPE =
		SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.LONG);

	/** The bend of the back, kept apart because the first eight bytes were full. */
	private static final EntityDataAccessor<Long> DATA_POSTURE =
		SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.LONG);

	/**
	 * Where the face this character is wearing keeps its eyes and brows.
	 *
	 * The mask as its own run-length text, at whatever size it was marked at.
	 * Sent rather than worked out on each client, so that a character blinks the
	 * same way for everybody watching, and so that a face somebody marked out by
	 * hand is not quietly replaced by a guess on somebody else's screen.
	 *
	 * Text rather than the three longs it used to be, and that is the fix for
	 * everything the eyes were doing wrong — see {@link #faceMask()}. Three longs
	 * is exactly eight by eight, which is one bit per texel on an ordinary skin
	 * and one bit per sixteen texels on a two-hundred-and-fifty-six-wide one.
	 * Run-length text costs a few hundred characters for a face of any size,
	 * because a face is mostly long runs of nothing.
	 */
	private static final EntityDataAccessor<String> DATA_FACE =
		SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.STRING);

	/** Whether that map was drawn by a person, as against read off the picture. */
	private static final EntityDataAccessor<Boolean> DATA_EYES_AUTHORED =
		SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.BOOLEAN);

	/**
	 * What actually gets built when an NPC spawns.
	 *
	 * The renderer insists on {@code Avatar & ClientAvatarEntity}, and that
	 * second interface exists only on the client — so the client needs its own
	 * subclass, exactly as vanilla has {@code ClientMannequin} beside
	 * {@code Mannequin}. Common code cannot name a client-only class, so the
	 * client initializer writes its constructor in here instead, and the entity
	 * type reads this field at spawn time rather than at registration.
	 *
	 * A dedicated hook in {@code EntityType.Builder} would be tidier, but there
	 * isn't one; this is the same trick with the moving part left visible.
	 */
	public static BiFunction<EntityType<? extends LivingEntity>, Level, NpcEntity> factory = NpcEntity::new;

	/**
	 * Every part of the second skin layer switched on.
	 *
	 * The same seven parts our Blockbench plugin turns into real geometry: hat,
	 * jacket, both sleeves, both trouser legs, cape.
	 */
	private static final byte ALL_LAYERS = allLayers();

	private static byte allLayers() {
		int mask = 0;
		for (PlayerModelPart part : PlayerModelPart.values()) {
			mask |= part.getMask();
		}
		return (byte) mask;
	}

	public NpcEntity(EntityType<? extends LivingEntity> type, Level level) {
		super(type, level);

		// Avatar defines this mask as a literal zero, meaning no second layer at
		// all — checked in the bytecode, not guessed. That is right for a Player,
		// whose real value arrives from the client's settings packet, and for a
		// Mannequin, which sets it explicitly. An NPC has neither, so without this
		// line it shows up bald and sleeveless.
		entityData.set(DATA_PLAYER_MODE_CUSTOMISATION, ALL_LAYERS);
	}

	/** Which parts of the second layer are drawn, as a {@link PlayerModelPart} mask. */
	public byte skinLayers() {
		return entityData.get(DATA_PLAYER_MODE_CUSTOMISATION);
	}

	public void setSkinLayers(byte mask) {
		entityData.set(DATA_PLAYER_MODE_CUSTOMISATION, mask);
	}

	/** Turns one part of the second layer on or off. */
	public void setSkinLayer(PlayerModelPart part, boolean shown) {
		int mask = skinLayers();
		setSkinLayers((byte) (shown ? mask | part.getMask() : mask & ~part.getMask()));
	}

	/**
	 * An NPC stands where it is put and does not wander off.
	 *
	 * No follow range and no movement speed yet: routes arrive in a later phase,
	 * and an NPC that drifts before then would look like a bug rather than an
	 * unfinished feature.
	 */
	public static AttributeSupplier.Builder createAttributes() {
		return LivingEntity.createLivingAttributes()
			.add(Attributes.MAX_HEALTH, 20.0)
			// A walking pace, and it does not make anybody wander: nothing moves a
			// character except being told to walk somewhere, and the only thing that
			// does that is going to look at what made a noise. An NPC with no reason
			// to go anywhere still stands exactly where it was put.
			.add(Attributes.MOVEMENT_SPEED, 0.23)
			// What a bare fist is worth, and how far she can reach. Both are the
			// player's own numbers, because this is a player-shaped body and the
			// weapon in its hand adds to the first exactly as it does for anybody.
			// Without them a character holding a sword swings it and nothing at all
			// happens, which is not a thing anyone would think to look for.
			.add(Attributes.ATTACK_DAMAGE, 1.0)
			.add(Attributes.ENTITY_INTERACTION_RANGE, 3.0)
			.add(Attributes.STEP_HEIGHT, 0.6)
			// The game's own scale, not one of ours. It carries the hitbox, the eye
			// height, how far the character can reach and how big it is drawn — all
			// the things a size ought to change, and all of them already wired up.
			// A number of our own would have moved the picture and left the body
			// where it was.
			.add(Attributes.SCALE, 1.0);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(DATA_PROFILE, ResolvableProfile.createUnresolved("Steve"));
		builder.define(DATA_DIALOGUE, "");
		builder.define(DATA_GESTURE, "");
		builder.define(DATA_GESTURE_START, 0);
		builder.define(DATA_GUARD, false);
		builder.define(DATA_GESTURE_TICKS, 0);
		builder.define(DATA_EXPRESSION, "");
		builder.define(DATA_EXPRESSION_START, 0);
		builder.define(DATA_EXPRESSION_TICKS, 0);
		builder.define(DATA_SHAPE, BodyShape.DEFAULT.packed());
		builder.define(DATA_POSTURE, BodyShape.DEFAULT.packedPosture());
		builder.define(DATA_FACE, "");
		builder.define(DATA_EYES_AUTHORED, false);
		builder.define(DATA_ANIM_IDLE, "");
		builder.define(DATA_ANIM_WALK, "");
		builder.define(DATA_ANIM_RUN, "");
		builder.define(DATA_ANIM_JUMP, "");
		builder.define(DATA_SKIN_MARK, "");
		builder.define(DATA_WATCHFUL, false);
		builder.define(DATA_MOOD, (byte) 0);
	}

	/**
	 * Whether this character keeps an eye out at all.
	 *
	 * <h2>Why it is off unless somebody says otherwise</h2>
	 *
	 * Because every NPC already placed in every world is a performer, not a guard,
	 * and a great many of them are standing in scenes with their heads keyed frame
	 * by frame. Watching turns heads. Switched on by default this would have gone
	 * through every existing world turning the cast to face whoever walked in, and
	 * the report would rightly have been that the animation broke.
	 *
	 * So it is a property of the character, off until asked for, and the switch is
	 * the line between "somebody in the scene" and "something to be wary of".
	 */
	private static final EntityDataAccessor<Boolean> DATA_WATCHFUL =
		SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.BOOLEAN);

	/**
	 * What it currently makes of the world, as a number the client can be told.
	 *
	 * Sent rather than worked out on both sides, and that is the whole point: two
	 * answers to "have I been seen" is worse than none. It also means a client
	 * cannot find out whether it has been spotted by asking its own copy of the
	 * world, which it could if the looking happened there.
	 */
	private static final EntityDataAccessor<Byte> DATA_MOOD =
		SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.BYTE);

	public boolean watchful() {
		return entityData.get(DATA_WATCHFUL);
	}

	public void setWatchful(boolean on) {
		entityData.set(DATA_WATCHFUL, on);
		if (!on) {
			watch.standDown();
			entityData.set(DATA_MOOD, (byte) 0);
		}
	}

	/** What this character makes of the world, on either side. */
	public com.mopicmp.npcstudio.foe.Alarm.Mood mood() {
		byte said = entityData.get(DATA_MOOD);
		var moods = com.mopicmp.npcstudio.foe.Alarm.Mood.values();
		return said >= 0 && said < moods.length ? moods[said] : moods[0];
	}

	/**
	 * The looking itself, which only the server does.
	 *
	 * Not saved and not synced beyond the mood: it is rebuilt from the world on the
	 * first tick after loading, and a character who has to notice you again after a
	 * reload is behaving correctly rather than forgetfully.
	 */
	private final com.mopicmp.npcstudio.foe.Watch watch = new com.mopicmp.npcstudio.foe.Watch(this);

	public com.mopicmp.npcstudio.foe.Watch watch() {
		return watch;
	}

	/**
	 * One tick of keeping an eye out, and the head that follows from it.
	 *
	 * <h2>The one thing this must never do</h2>
	 *
	 * Fight a scene. A character being filmed has its head placed frame by frame by
	 * the timeline, and a watchman turning it towards whoever wandered past would
	 * be two authors of one number — which does not error, it produces an animation
	 * that is subtly wrong in a way nobody can point at.
	 *
	 * The guard for now is the switch itself: a performer is not watchful, so the
	 * question does not arise. When a character has to be both — a boss who watches
	 * the door and then plays a scripted entrance — this is where the scene will
	 * have to say so, and {@link com.mopicmp.npcstudio.foe.Watch#standDown()} is
	 * what it will call.
	 */
	private void keepWatch() {
		if (!watchful()) return;
		watch.tick();
		entityData.set(DATA_MOOD, (byte) watch.mood().ordinal());

		// A character with a brain is run by it. Everything below this line is the
		// reflex - going to look, turning to face - and every part of it is a
		// decision, which is exactly what a graph is for. Leaving both running
		// would be two authors of one body.
		//
		// It is also the measure of step D: when the reflex has been written as a
		// graph that ships with the mod, everything below goes.
		if (!graphId().isEmpty()) return;

		goAndLook();

		Vec3 at = watch.lookingAt();
		if (at == null) return;
		var lead = watch.lead();
		if (lead == null) return;
		// While walking, the feet decide which way the body faces and the head is
		// free to look elsewhere. Somebody crossing a courtyard towards a noise while
		// watching a window is doing two things at once, and that is what it should
		// look like.
		turnTowards(at, lead, watch.squaresUp() && !walk.walking());
	}

	private final com.mopicmp.npcstudio.foe.Walk walk = new com.mopicmp.npcstudio.foe.Walk();

	/** Where she set off for, so that a new noise in the same place is not a new walk. */
	private Vec3 headingFor;

	/**
	 * Walking over to see what that was.
	 *
	 * <h2>Why the finding and the walking happen at different rates</h2>
	 *
	 * Searching for a route is expensive and rarely needs redoing; putting one foot
	 * in front of the other happens every tick. So a path is found when the
	 * destination changes and followed until it runs out — the same arrangement as
	 * looking and the alarm meter, for the same reason.
	 */
	/**
	 * Walking over to see what that was.
	 *
	 * <h2>The stutter this was rewritten to cure</h2>
	 *
	 * Reported as: she runs, stops for a second, runs again, stops. Two causes, and
	 * both were mine.
	 *
	 * The destination is a <em>guess</em> at where a noise came from, and every
	 * fresh noise produces a fresh guess a few blocks off the last one. Each of
	 * those looked like a new place to go, so the route was thrown away and rebuilt
	 * — and rebuilding starts the walk at the first waypoint again, which is usually
	 * behind her. Off she went, back the way she came, half a second at a time.
	 *
	 * And the moment the alarm dipped or the lead was briefly outranked, there was
	 * nothing worth investigating and she stopped where she stood. But somebody who
	 * has set off to look at something does not abandon it because they stopped
	 * hearing it — that is when they most want to go and see.
	 *
	 * So a walk is now <b>committed to</b>: begun once, kept until she arrives, gets
	 * stuck, calms down, or something genuinely elsewhere turns up.
	 */
	private void goAndLook() {
		Vec3 wanted = watch.worthInvestigating();

		if (wanted != null && wantsToGoElsewhere(wanted)) {
			walkTo(wanted, paceFor(watch.lead()));
		}
		// Calming down is the one thing that ends a walk early. Losing the sound is
		// not: that is the moment somebody most wants to go and look.
		if (watch.mood() == com.mopicmp.npcstudio.foe.Alarm.Mood.CALM) {
			halt();
		}
	}

	/**
	 * A stroll to see what a door was, a run at a gunshot.
	 *
	 * Urgency already means exactly this, so it is what decides. It stays here
	 * rather than moving into the walking because it is a judgement, and a graph
	 * makes its own by naming a pace when it orders a walk.
	 */
	private static float paceFor(com.mopicmp.npcstudio.foe.Lead lead) {
		return lead != null && lead.urgency() >= HURRIES_AT
			? com.mopicmp.npcstudio.foe.Walk.HURRYING
			: com.mopicmp.npcstudio.foe.Walk.WANDERING;
	}

	// -------------------------------------------------------------- the verbs

	/** How fast the current walk is meant to be, whoever asked for it. */
	private float wantedPace = com.mopicmp.npcstudio.foe.Walk.WANDERING;

	/**
	 * Sets off for a place.
	 *
	 * Finding the route happens here, once; following it happens in
	 * {@link #walkOn}, every tick. That split is what lets the search be
	 * expensive: it runs when the destination changes and not otherwise.
	 *
	 * @return whether there was a way there at all, so that a graph can tell
	 *         being unable to go from having arrived
	 */
	public boolean walkTo(Vec3 wanted, float pace) {
		if (wanted == null) return false;
		// Not while she is swinging. A strike is a step with the weight on it, and a
		// character who walks out of her own blow is the wooden thing this whole
		// piece of work is about — the arm carries on through an animation while the
		// feet take her somewhere else entirely.
		if (blow.committed()) return false;
		wantedPace = Math.clamp(pace, 0f, 1f);

		// Ordered somewhere she is already going. This is not an optimisation, it
		// is the same bug as before wearing new clothes: rebuilding a route starts
		// the walk at its first waypoint, which is usually behind her, so she turns
		// round, comes back, and sets off again. It was reported once already as
		// "runs, stops, runs, stops", when a fresh guess at a noise arrived every
		// few ticks.
		//
		// A graph chasing somebody re-orders the walk constantly and by design, so
		// without this the fault would return the moment two characters chased each
		// other — and it would look like the pathfinder being wrong rather than
		// like being asked too often.
		if (!wantsToGoElsewhere(wanted)) return true;

		headingFor = wanted;
		var route = routeTo(wanted);
		walk.follow(route);
		return route != null && !route.isEmpty();
	}

	public void halt() {
		stopWalking();
	}

	/**
	 * One tick of putting one foot in front of the other.
	 *
	 * Runs whoever gave the order and whether or not she has a brain, because
	 * walking is not a decision - it is the carrying out of one, and a character
	 * whose legs stop while her graph is thinking is a character standing in a
	 * doorway.
	 */
	private void walkOn() {
		walk.moved(getX(), getZ());
		if (!walk.walking()) return;

		// Wedged against a fence post. Nobody needs to decide this: a route that
		// cannot be followed is not a route, and keeping it would mean pushing at
		// the post until something else happened.
		if (walk.stuck()) {
			stopWalking();
			return;
		}

		double[] step = walk.heading(getX(), getY(), getZ());
		if (step == null) {
			stopWalking();
			return;
		}
		stride(step);
	}

	/**
	 * What a graph told her to look at, or empty for nothing.
	 *
	 * Held rather than done once, because looking at somebody is a state. It
	 * outranks the watching reflex, because an author outranks a reflex - a
	 * scripted stare broken by a door closing behind the camera would be nobody's
	 * idea of a scene.
	 */
	private String gazingAt = "";

	public void lookAt(String mark) {
		gazingAt = mark == null ? "" : mark;
	}

	public String gazingAt() {
		return gazingAt;
	}

	/** How fast a head comes round to something she was told to watch. */
	private static final float GAZE_RATE = 12f;

	private void gaze() {
		if (gazingAt.isEmpty()) return;
		if (com.mopicmp.npcstudio.dialogue.Mark.NOTHING.equals(gazingAt)) return;
		Vec3 at = com.mopicmp.npcstudio.brain.Marks.eyes(this, gazingAt);
		// A mark that names nothing at the moment is not an error and not a reason
		// to let go: told to watch the lead, she goes on facing where it was until
		// the graph says otherwise, which is what a person does.
		if (at == null) return;
		turnBodily(at, GAZE_RATE);
	}

	/**
	 * Brings head and shoulders round to a point, at a given rate.
	 *
	 * Body as well as head, unlike a glance: a graph pointing a character at
	 * something means her attention. It also matters for shooting, because a
	 * projectile leaves along the body's rotation and not the head's - a
	 * character aiming with her neck alone fires wherever her shoulders happen to
	 * be pointing.
	 */
	private void turnBodily(Vec3 at, float rate) {
		float wanted = (float) com.mopicmp.npcstudio.foe.Sight.yawTo(getX(), getZ(), at.x, at.z);
		float body = com.mopicmp.npcstudio.foe.Neck.step(yBodyRot, wanted, rate);
		yBodyRot = body;
		setYRot(body);
		setYHeadRot(com.mopicmp.npcstudio.foe.Neck.step(getYHeadRot(), wanted, rate * 2f));
		Vec3 eye = getEyePosition();
		float pitch = com.mopicmp.npcstudio.foe.Neck.pitchTo(eye.x, eye.y, eye.z, at.x, at.y, at.z);
		setXRot(com.mopicmp.npcstudio.foe.Neck.step(getXRot(), pitch, rate));
	}

	/**
	 * Where she is to come back to.
	 *
	 * A place rather than a name, because "where I was told to stand" is a fact
	 * about this character in this world and there is nothing else it could mean.
	 * Unset until a graph asks for it, so a character who never posts anywhere
	 * carries nothing.
	 */
	private Vec3 post;

	public Vec3 post() {
		return post;
	}

	public void markPost() {
		post = position();
	}

	/**
	 * Whether the place to look at has really moved, or only been guessed at again.
	 *
	 * Four blocks, which is wider than the wobble on a guess and narrower than a
	 * different room. Under it, she carries on to where she was already going —
	 * which is right in itself: whoever made the noise is around there somewhere,
	 * and shuffling the destination by a block or two changes nothing about that.
	 */
	private boolean wantsToGoElsewhere(Vec3 wanted) {
		if (headingFor == null || !walk.walking()) return true;
		return headingFor.distanceToSqr(wanted) > 16;
	}

	private void stopWalking() {
		walk.stop();
		headingFor = null;
		zza = 0;
		// Still eased, because coming to a stop is a movement too and a character who
		// stops dead is as wrong as one who starts dead.
		setSpeed(WALKING_PACE * walk.pacing(0));
	}

	/** How fast a character moves at full pelt; everything else is a fraction of it. */
	private static final float WALKING_PACE = 0.26f;

	/**
	 * One step towards the next waypoint.
	 *
	 * The physics is the game's own: the body is pointed at where it is going and
	 * told to walk forward, and vanilla does gravity, collision, slabs, stairs and
	 * water. Writing the movement by hand would mean reimplementing all of that
	 * badly, and this character is a player in every other respect already.
	 */
	private void stride(double[] step) {
		float bearing = (float) com.mopicmp.npcstudio.foe.Sight.yawTo(
			getX(), getZ(), step[0], step[2]);
		yBodyRot = bearing;
		setYRot(bearing);

		// How fast was settled when the walk was ordered - by a reflex or by a
		// graph - and is not decided again on every step.
		setSpeed(WALKING_PACE * walk.pacing(wantedPace));
		zza = 1;
		xxa = 0;

		if (isInWater()) {
			// Swimming is vanilla's, and the way to ask for it is to keep pressing
			// jump: that is what holds a body at the surface rather than walking it
			// along the bottom.
			setJumping(step[1] >= getY());
			return;
		}
		setJumping(false);

		// A ladder is climbed by walking into it, which vanilla already handles once
		// the body is pointed at it — which stride has just done.
		if (onClimbable()) return;

		// Up a block, which needs a jump: a person's step height is not a whole
		// block, and the route deliberately allows climbing one because people climb.
		if (onGround() && step[1] > getY() + 0.4) jumpFromGround();
	}

	/** Above this much urgency, she does not walk over — she goes. */
	private static final float HURRIES_AT = 0.7f;

	/**
	 * A route through the world, asked of the blocks.
	 *
	 * The ground rules are the honest ones: somewhere to put your feet, room for
	 * your body, and nothing to walk through. Everything soft enough to walk through
	 * — crops, grass, open doors — is clear, which is why a character crossing a
	 * field walks through it rather than round it.
	 */
	private java.util.List<int[]> routeTo(Vec3 wanted) {
		var level = level();
		var to = net.minecraft.core.BlockPos.containing(wanted);
		var from = blockPosition();

		com.mopicmp.npcstudio.foe.Ways.Ground ground = new com.mopicmp.npcstudio.foe.Ways.Ground() {
			@Override
			public boolean clear(int x, int y, int z) {
				var where = new net.minecraft.core.BlockPos(x, y, z);
				var state = level.getBlockState(where);
				return state.isAir() || state.getCollisionShape(level, where).isEmpty();
			}

			@Override
			public boolean solid(int x, int y, int z) {
				var where = new net.minecraft.core.BlockPos(x, y, z);
				return !level.getBlockState(where).getCollisionShape(level, where).isEmpty();
			}

			@Override
			public boolean holdsUp(int x, int y, int z) {
				var where = new net.minecraft.core.BlockPos(x, y, z);
				var state = level.getBlockState(where);
				// Water to float in and ladders to hang from. Asked of the game's own
				// tag rather than of a list of blocks, so scaffolding, vines, chains
				// and whatever any other mod calls a ladder all count without either
				// of us knowing about the other.
				return state.is(net.minecraft.tags.BlockTags.CLIMBABLE)
					|| state.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
			}
		};

		// The place a noise came from is a guess and may well be inside a wall or in
		// mid-air. Walking to the nearest standable block beside it is what somebody
		// would do anyway: you go and look at the spot, not into it.
		var landing = nearestFooting(ground, to);
		if (landing == null) return java.util.List.of();
		return com.mopicmp.npcstudio.foe.Ways.to(ground,
			from.getX(), from.getY(), from.getZ(),
			landing.getX(), landing.getY(), landing.getZ());
	}

	/** Somewhere near the guess that somebody could actually stand. */
	private net.minecraft.core.BlockPos nearestFooting(
			com.mopicmp.npcstudio.foe.Ways.Ground ground, net.minecraft.core.BlockPos about) {
		for (int spread = 0; spread <= 3; spread++) {
			for (int dx = -spread; dx <= spread; dx++) {
				for (int dz = -spread; dz <= spread; dz++) {
					for (int dy = spread; dy >= -spread; dy--) {
						if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != spread) {
							continue;
						}
						int x = about.getX() + dx;
						int y = about.getY() + dy;
						int z = about.getZ() + dz;
						if (com.mopicmp.npcstudio.foe.Ways.standable(ground, x, y, z)) {
							return new net.minecraft.core.BlockPos(x, y, z);
						}
					}
				}
			}
		}
		return null;
	}

	/** How far along a walk she is, for a readout. */
	public com.mopicmp.npcstudio.foe.Walk walking() {
		return walk;
	}

	// ----------------------------------------------------------------- the brain

	/**
	 * Which graph runs this character.
	 *
	 * <h2>There used to be two of these, and that was the mistake</h2>
	 *
	 * A character carried a dialogue <em>and</em> a brain, chosen separately, and
	 * that is not what a brain is. A brain is a <b>library of skills</b>, and the
	 * only place a skill is ever called from is a dialogue. So a character has one
	 * document and it is the dialogue; a brain is something that document uses.
	 *
	 * The two-field version was wrong in a way that cost a session: {@code duel}
	 * went into the brain field, which nothing was designed to make work, and the
	 * fix I made — giving both fields the same tidy dropdown — kept the wrong shape
	 * and made it comfortable. Removing the field is the fix.
	 *
	 * So this is {@link #dialogueId()}, and there is no second answer.
	 */
	public String graphId() {
		return dialogueId();
	}

	/**
	 * What became of a brain this character was carrying before the two merged.
	 *
	 * Empty for everybody who never had one. Kept and said out loud rather than
	 * quietly dropped: a world already holds characters with {@code duel} in the
	 * old field, and deleting it silently would leave them standing with nothing
	 * anywhere saying why.
	 *
	 * That is the third time this family of bug has come up — the lost
	 * {@code segments}, the gesture that never restarts, and this — which is why
	 * it is a field and not a comment.
	 */
	private String movedBrain = "";

	public String movedBrain() {
		return movedBrain;
	}

	/**
	 * Puts down whatever she was in the middle of.
	 *
	 * Called whenever the document under her changes. Her place in it is
	 * meaningless afterwards, and a skill outlives its caller even less: it would
	 * leave a character fighting to instructions nobody can now read.
	 */
	public void forgetWhereSheWas() {
		mind = null;
		waiting = 0;
		stopDoing();
	}

	/**
	 * Where she has got to in her graph, and what she has learnt.
	 *
	 * <h2>Not saved, on purpose, and only for now</h2>
	 *
	 * A character who resumes mid-thought after a server restart is a nicety; a
	 * character who resumes at a node that no longer exists because the graph was
	 * edited in between is a bug report. Until graphs stop changing under people,
	 * beginning again on load is both simpler and more predictable — and it is
	 * what she does after any other interruption anyway.
	 *
	 * What this does mean is that variables a behaviour graph sets do not yet
	 * outlive a restart. That is the next step's problem: the scope it wants is
	 * CHARACTER, which does not exist yet either.
	 */
	private com.mopicmp.npcstudio.dialogue.DialogueState mind;

	/**
	 * What she knows about herself, which is a different thing and does survive.
	 *
	 * The two are forgotten at different moments and that is the whole reason they
	 * are apart. Where she had got to is dropped whenever she is interrupted —
	 * being interrupted is ordinary, and a graph edited in between would leave her
	 * standing at a node that no longer exists. What she learnt is not dropped: a
	 * character who begins her rounds again but still knows she has raised the
	 * alarm is behaving correctly, and one who forgets raises it for ever.
	 */
	private java.util.Map<String, com.mopicmp.npcstudio.dialogue.Value> memory = java.util.Map.of();

	private static final com.mojang.serialization.Codec<
			java.util.Map<String, com.mopicmp.npcstudio.dialogue.Value>> MEMORY_CODEC =
		com.mojang.serialization.Codec.unboundedMap(
			com.mojang.serialization.Codec.STRING,
			com.mopicmp.npcstudio.dialogue.codec.DialogueCodecs.VALUE);

	public java.util.Map<String, com.mopicmp.npcstudio.dialogue.Value> memory() {
		return memory;
	}

	/** Her place in the graph, started from the beginning if she has none. */
	public com.mopicmp.npcstudio.dialogue.DialogueState mind(
			com.mopicmp.npcstudio.dialogue.Dialogue graph) {
		if (mind == null) {
			mind = com.mopicmp.npcstudio.dialogue.DialogueState.start(
				graph, java.util.Map.of(), memory);
		}
		return mind;
	}

	/**
	 * Keeps the bookmark, and keeps what was learnt somewhere it will outlive it.
	 *
	 * Both come out of the same value, which is why this is one call: forgetting to
	 * write one of them back is a bug that shows up as a character who cannot
	 * learn, hours later, in somebody else's world.
	 */
	public void remember(com.mopicmp.npcstudio.dialogue.DialogueState now) {
		mind = now;
		memory = now.characterVars();
	}

	/** What a conversation taught her, kept by the same rule as the rest. */
	public void rememberOnly(
			java.util.Map<String, com.mopicmp.npcstudio.dialogue.Value> learnt) {
		memory = java.util.Map.copyOf(learnt);
		// Her train of thought is now built on something that has changed under it.
		// Starting the graph again is cheap and correct; carrying on with a stale
		// copy of what she knows is neither.
		mind = null;
	}

	/**
	 * The skill she has running, alongside whatever set it going.
	 *
	 * <h2>Why there are two bookmarks and not one</h2>
	 *
	 * Because a conversation that starts a fight has to be able to end while the
	 * fight goes on. One bookmark would mean the caller was inside the call, and
	 * the last line would be spoken once somebody had won.
	 *
	 * Two graphs driving one body is the thing that had been put off as awkward,
	 * and the awkwardness is real but it is only one question: who wins when both
	 * speak in the same tick. The answer is the segment, because the segment is
	 * the specialist and the scenario is what chose it — so the scenario is
	 * offered its turn first and the segment acts last.
	 */
	private String doingNow = "";
	private com.mopicmp.npcstudio.dialogue.DialogueState doingState;
	private String doingAt = com.mopicmp.npcstudio.dialogue.Mark.NOTHING;
	private int doingWait;

	/**
	 * Which document the running skill's nodes live in.
	 *
	 * Usually her own, and not always: a skill may be called out of a library — a
	 * document written to hold skills and nothing else, which is what a brain
	 * actually is. Its nodes are not in her graph, so stepping them against her
	 * graph would look up node names that are not there and stop with an
	 * unhelpful complaint about a graph that is perfectly fine.
	 *
	 * Kept beside the bookmark rather than worked out from the segment's name each
	 * tick, because the two must not be able to disagree: a bookmark into one
	 * document and a document chosen freshly from a name is how a skill ends up
	 * being read out of the wrong book halfway through.
	 */
	private String doingIn = "";

	public String doingNow() {
		return doingNow;
	}

	/** Which document the running skill came out of, or empty for her own. */
	public String doingIn() {
		return doingIn;
	}

	/**
	 * The last thing her brain could not do, in words, or empty.
	 *
	 * <h2>Why this is kept rather than only logged</h2>
	 *
	 * Because a refusal in a server log is a refusal nobody reads. A graph that
	 * calls a skill which is not there produces a character standing perfectly
	 * still, and the one line explaining it is in a file behind the game — which
	 * is exactly as useful as no line at all.
	 */
	private String brainTrouble = "";

	public String brainTrouble() {
		return brainTrouble;
	}

	public void brainTrouble(String said) {
		brainTrouble = said == null ? "" : said;
	}

	/** The last answer to "who else has my brain", and when it was worked out. */
	private int kinAsOf = -1;
	private NpcEntity kinFound;

	public boolean kinKnown(int tick) {
		return kinAsOf == tick;
	}

	public NpcEntity kinRemembered() {
		return kinFound;
	}

	public void rememberKin(int tick, NpcEntity found) {
		kinAsOf = tick;
		kinFound = found;
	}

	/** Whoever the running skill was told to act on. */
	public String doingAt() {
		return doingAt;
	}

	public com.mopicmp.npcstudio.dialogue.DialogueState doingState() {
		return doingState;
	}

	public void setDoingState(com.mopicmp.npcstudio.dialogue.DialogueState now) {
		doingState = now;
	}

	/**
	 * Sets a skill running, from its named way in, told these things.
	 *
	 * @param in which document its nodes are in, or empty for her own — see
	 *           {@link #doingIn()}
	 */
	public void beginDoing(String segment, String in, String at,
			com.mopicmp.npcstudio.dialogue.DialogueState from) {
		doingNow = segment;
		doingIn = in == null ? "" : in;
		doingAt = at == null ? com.mopicmp.npcstudio.dialogue.Mark.NOTHING : at;
		doingState = from;
		doingWait = 0;
	}

	/**
	 * Puts the skill down.
	 *
	 * The weapon and the walking are let go with it. A character told to stop
	 * fighting who goes on drawing a bow at somebody is a character who did not
	 * stop, and the graph that stopped her has no other way to say so.
	 */
	public void stopDoing() {
		doingNow = "";
		doingIn = "";
		doingState = null;
		doingAt = com.mopicmp.npcstudio.dialogue.Mark.NOTHING;
		doingWait = 0;
		draw.ease();
		halt();
	}

	public void waitDoing(int ticks) {
		doingWait = Math.max(ticks, 0);
	}

	public boolean skillStillWaiting() {
		if (doingWait <= 0) return false;
		doingWait--;
		return true;
	}

	/** Ticks left before the graph is worth asking again. */
	private int waiting;

	public void waitFor(int ticks) {
		waiting = Math.max(ticks, 0);
	}

	/** Counts a tick off the wait and says whether there is still one to serve. */
	public boolean stillWaiting() {
		if (waiting <= 0) return false;
		waiting--;
		return true;
	}

	// ---------------------------------------------------------------- the weapon

	private final com.mopicmp.npcstudio.foe.Draw draw = new com.mopicmp.npcstudio.foe.Draw();

	public com.mopicmp.npcstudio.foe.Draw draw() {
		return draw;
	}

	/**
	 * Whether her ammunition ever runs out.
	 *
	 * The game already has this idea and already honours it everywhere ammunition
	 * is spent — it is what creative mode means to a bow. Borrowing it rather than
	 * inventing an endless-arrows flag of our own means the one switch is obeyed by
	 * vanilla's own code, and there is no second place for the two to disagree.
	 */
	private boolean endless;

	public boolean endless() {
		return endless;
	}

	public void setEndless(boolean on) {
		endless = on;
	}

	@Override
	public boolean hasInfiniteMaterials() {
		return endless;
	}

	/** Orders one shot: draw whatever is in hand for this long, then loose it. */
	public void fire(int drawFor) {
		draw.pull(drawFor);
	}

	/**
	 * What she is shooting at, or empty for nothing.
	 *
	 * <h2>Why aiming is held rather than done once</h2>
	 *
	 * Because a bow takes a second to draw and anything worth shooting at has
	 * moved by the time it is ready. Pointing her at the order and letting go
	 * twenty ticks later is how you build a character who reliably shoots at
	 * where you used to be.
	 *
	 * Separate from {@link #gazingAt} on purpose, though they usually agree. A
	 * character can be watching a doorway and shooting at what came out of a
	 * different one, and more to the point she can be told to keep looking at
	 * somebody after the shot is gone.
	 */
	private String aimingAt = "";

	/**
	 * The swing she is in the middle of, as an interval rather than a moment.
	 *
	 * Everything about a blow that is arithmetic lives in {@link com.mopicmp.npcstudio.foe.Blow};
	 * this is the one copy of it that belongs to a body.
	 */
	private final com.mopicmp.npcstudio.foe.Blow blow = new com.mopicmp.npcstudio.foe.Blow();

	/**
	 * What happened to her this tick, for the tape and nothing else.
	 *
	 * Cleared every tick after it is written down. A field rather than a return
	 * value because the events come from four different places — a swing beginning,
	 * a blade landing, a blow taken, a graph refusing — and threading a return
	 * through all four to reach one recorder would put the recorder into the
	 * fighting, which is the opposite of what it is for.
	 */
	private String happened = "";

	private void happened(String what) {
		if (!com.mopicmp.npcstudio.foe.Tape.rolling()) return;
		happened = happened.isEmpty() ? what : happened + "; " + what;
	}

	/** Whom the swing in progress was aimed at, so the blade can look again. */
	private String swingingAt = "";

	/**
	 * The animation this swing put on, so that it can be taken off again.
	 *
	 * Taken off at the end of the blow rather than left holding its last frame.
	 * Holding was right while there was nothing to go back to; with a stance to
	 * return to it is the difference between a fighter between blows and a
	 * photograph of one.
	 *
	 * Only if it is still hers. Anything else that has since asked for an animation
	 * outranks a swing that is over, and clearing somebody else's gesture would be
	 * the wrong kind of tidy.
	 */
	private String swingShowing = "";

	/** Whether this blow has already touched somebody. */
	private boolean blowLanded;

	/** Whether she is mid-swing and may not be given other orders. */
	public boolean swinging() {
		return blow.committed();
	}

	public com.mopicmp.npcstudio.foe.Blow blow() {
		return blow;
	}

	/**
	 * Begins a swing at somebody.
	 *
	 * <h2>The damage is no longer here</h2>
	 *
	 * It used to be: the graph said strike, the arm waved, and the damage landed in
	 * the same tick. Nothing was placed in time, so nothing could read as having
	 * weight — and on eight of the animations the blade did not reach anybody until
	 * up to half a second later, so the two were not even about the same moment.
	 *
	 * Now this starts an interval. The picture and the damage belong to one swing
	 * because the swing decides both: which animation, from the chain, and which
	 * tick, from that animation's own timing. See {@link com.mopicmp.npcstudio.foe.Swings}.
	 *
	 * <h2>She is committed once it has begun</h2>
	 *
	 * The graph may not call it off, walk out of it, or start another. That costs
	 * her the rest of the swing in reaction time, and buying that is the point: a
	 * blow that can be cancelled never had any weight to begin with.
	 *
	 * @return whether a swing began. Not whether it landed — nothing has landed
	 *         yet, and {@link #lastBlow()} is where the outcome goes
	 */
	public boolean strikeAt(String mark) {
		if (blow.committed()) return false;
		var target = com.mopicmp.npcstudio.brain.Marks.creature(this, mark);
		if (target == null) {
			lastBlow = "nobody to hit";
			return false;
		}

		turnBodily(target.getEyePosition(), AIM_RATE);

		var style = com.mopicmp.npcstudio.foe.Style.forWeapon(
			com.mopicmp.npcstudio.foe.Arms.of(getMainHandItem()));
		int link = blow.link(style.chain().size());
		if (!blow.begin(style.shape(link))) return false;

		blowLanded = false;
		swingingAt = mark;
		swingShowing = style.swing(link);
		happened("BEGAN " + style.swing(link) + " contact@" + style.timing(link).contact()
			+ " cancel@" + style.timing(link).cancel());
		// Vanilla's own arm swing as well, because everything hung on it — the
		// sound, the sweep particles, a mod watching for an attack — is hung on that
		// and not on ours.
		swing(InteractionHand.MAIN_HAND);
		// Held rather than given a length. An emote ends where the animator left it,
		// and holding that last frame until the next blow or the stance replaces it
		// is what lets the follow-through play out instead of being cut at the tick
		// she is free to move again.
		playGesture(style.swing(link), 0);
		lastBlow = "swinging " + style.swing(link) + ", lands on tick "
			+ style.timing(link).contact()
			+ (style.timing(link).checked() ? "" : " (nobody has looked at this one)");
		return true;
	}

	/**
	 * One tick of the swing, and the blow itself when the blade comes round.
	 *
	 * <h2>Why the target is looked up again rather than held</h2>
	 *
	 * Because half a second passes between the order and the edge, and in half a
	 * second somebody can step out of reach. Holding the entity would hit whoever
	 * was there when she decided, which is the same fault as landing the damage on
	 * the tick of the order — an answer about a moment that has gone.
	 *
	 * It also gives the readout something worth saying: "nothing there when the
	 * blade came round" and "out of reach" are different failures and used to look
	 * identical from outside.
	 */
	private void workTheBlow() {
		var phase = blow.tick();
		if (phase == com.mopicmp.npcstudio.foe.Blow.Phase.CONTACT && !blowLanded) {
			// Once per swing, however many ticks the edge is dangerous for. The window
			// is there so that somebody moving through the arc is caught, not so that
			// one blow hurts three times.
			blowLanded = land(swingingAt);
		}
		if (phase == com.mopicmp.npcstudio.foe.Blow.Phase.READY) {
			swingingAt = "";
			// And the body is handed back, so whatever she does between blows can be
			// seen. Guarded by the name, because by now somebody else may have asked
			// for something and a swing that is over does not outrank them.
			if (!swingShowing.isEmpty()) {
				if (swingShowing.equals(gesture())) playGesture("", 0);
				swingShowing = "";
			}
		}
	}

	/**
	 * How far she can hit, from the edge of her body to the edge of theirs.
	 *
	 * From the edges rather than centre to centre, so that two broad characters can
	 * reach each other and a scaled-up one reaches further, which is what size
	 * already means everywhere else.
	 *
	 * Public because a graph has to be able to ask it. The alternative was every
	 * graph writing down a number, and every one of those numbers being wrong for
	 * somebody built differently.
	 */
	public boolean canReach(String mark) {
		var target = com.mopicmp.npcstudio.brain.Marks.creature(this, mark);
		if (target == null) return false;
		double reach = getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
		return distanceTo(target) <= reach + getBbWidth() / 2 + target.getBbWidth() / 2;
	}

	/**
	 * How long a blow costs whoever takes it, in ticks.
	 *
	 * A third of a second. The half of a reaction that can be built without an
	 * animation: she does not get to act on the beat she was hit on, so whoever
	 * lands a blow gets to follow it up rather than trading one for one for ever.
	 *
	 * The other half — a body that visibly flinches — is not in the pack and has to
	 * be made, which is a later step. This is deliberately not a substitute for it,
	 * and saying so is the point: without the picture a player feels the rhythm
	 * change without seeing why.
	 */
	private static final int WINDED = 6;

	/**
	 * How much of the flinch is shown, in ticks.
	 *
	 * Shorter than the beat it costs her, and they are separate numbers because
	 * they are separate things: one is how long she is out of the fight, the other
	 * is how far into a picture she gets.
	 *
	 * Four, read off the frames rather than chosen. The animation it borrows from
	 * is a death, and its opening is the part about being hit: at its second and
	 * third tick the body dips over the blow, and by its fifth it is doubled over
	 * on its way to the floor. Six ticks of it was reported as "he fell down and
	 * got up again", which is a fair description of what a death animation does
	 * when you play too much of it.
	 */
	private static final int FLINCH = 4;

	@Override
	public boolean hurtServer(net.minecraft.server.level.ServerLevel level,
			net.minecraft.world.damagesource.DamageSource source, float amount) {
		boolean hurt = super.hurtServer(level, source, amount);
		if (!hurt) return false;

		// A blow that is landed on is a blow that does not finish. Dropping it rather
		// than letting it run is what makes an exchange an exchange: whoever got
		// there first keeps the initiative, and being quicker means something.
		//
		// The combination is not forgotten with it. Being interrupted is what an
		// exchange is, and forgetting on every interruption meant both fighters were
		// permanently on the first swing of three — one movement, repeated.
		happened("HURT " + String.format("%.1f", amount) + " by " + (source.getEntity() == null
			? source.getMsgId() : source.getEntity().getName().getString()));
		blow.drop();
		swingingAt = "";
		waitDoing(WINDED);

		// And it is seen. Played for the beat it costs her, so the body folds over
		// the blow and comes back out of it rather than running on into falling
		// over — the animation this borrows from is a death, and only its opening is
		// about being hit.
		playGesture(com.mopicmp.npcstudio.foe.Style
			.forWeapon(com.mopicmp.npcstudio.foe.Arms.of(getMainHandItem())).flinch(), FLINCH);
		swingShowing = "";
		return true;
	}

	/** The blow, once the blade is actually passing through. */
	private boolean land(String mark) {
		var target = com.mopicmp.npcstudio.brain.Marks.creature(this, mark);
		if (target == null) {
			lastBlow = "nothing there when the blade came round";
			return false;
		}
		double reach = getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
		// From the edges rather than centre to centre, so that two broad characters
		// can reach each other and a scaled-up one reaches further, which is what
		// the size already means everywhere else.
		if (distanceTo(target) > reach + getBbWidth() / 2 + target.getBbWidth() / 2) {
			lastBlow = "swung and missed — " + Math.round(distanceTo(target))
				+ " blocks, reach " + Math.round(reach);
			return false;
		}
		if (!(level() instanceof net.minecraft.server.level.ServerLevel server)) return false;

		boolean landed = hit(server, target);
		lastBlow = landed ? "landed" : "blocked or absorbed";
		happened(landed ? "LANDED on " + target.getName().getString() : "BLOCKED");
		return landed;
	}

	/**
	 * The blow itself.
	 *
	 * <h2>Why this is written out rather than delegated</h2>
	 *
	 * Because {@code LivingEntity.doHurtTarget} does nothing. In 26.2 it is two
	 * instructions — remember who was aimed at, return false — and the attack that
	 * everybody means by that name lives only on {@code Mob}, which we are not.
	 *
	 * That is the same trap the bow set, from the same direction: a method that
	 * exists on our class, compiles, runs, and is a stub for anything that is not
	 * the subclass it was written for. Both times the symptom was a character
	 * doing the motion and having no effect on the world.
	 *
	 * So this is {@code Mob}'s own attack, step for step, calling the same public
	 * things it calls. Nothing about the damage is ours: the weapon says what it
	 * does, the enchantments modify it, the game applies it. What we supply is
	 * who and when — which is all we ever wanted to supply.
	 *
	 * <h2>One line here matters more than it looks</h2>
	 *
	 * {@code doPostAttackEffects} is what fires an enchantment hung on hitting —
	 * which is precisely how a datapack builds a gun. A character who can strike
	 * properly is a character who can, in principle, fire one.
	 */
	private boolean hit(net.minecraft.server.level.ServerLevel server,
			net.minecraft.world.entity.LivingEntity target) {
		float damage = (float) getAttributeValue(Attributes.ATTACK_DAMAGE);
		net.minecraft.world.item.ItemStack weapon = getWeaponItem();
		var source = weapon.getDamageSource(this);
		damage = net.minecraft.world.item.enchantment.EnchantmentHelper
			.modifyDamage(server, weapon, target, source, damage);
		damage += weapon.getItem().getAttackDamageBonus(target, damage, source);

		Vec3 was = target.getDeltaMovement();
		boolean landed = target.hurtServer(server, source, damage);
		if (!landed) return false;

		float knock = getKnockback(target, source);
		if (knock > 0) causeExtraKnockback(target, knock, was, source, 0f, true);
		weapon.hurtEnemy(target, this);
		net.minecraft.world.item.enchantment.EnchantmentHelper
			.doPostAttackEffects(server, target, source);
		setLastHurtMob(target);
		playAttackSound();
		postPiercingAttack();
		return true;
	}

	/**
	 * What came of the last swing, in words.
	 *
	 * Kept because from outside a miss, a blocked blow and a swing that does
	 * nothing at all are the same character waving an arm — and it was the third
	 * of those for a whole session without anything anywhere saying so.
	 */
	private String lastBlow = "";

	public String lastBlow() {
		return lastBlow;
	}

	/** Orders a shot at a mark, drawn for as long as the weapon is worth. */
	public void fireAt(String mark, int drawFor) {
		aimingAt = mark == null ? "" : mark;
		draw.pull(drawFor);
	}

	public String aimingAt() {
		return aimingAt;
	}

	/**
	 * How fast she swings round to bring a weapon to bear.
	 *
	 * Brisker than a glance and brisker than a gaze, because it is neither: it is
	 * somebody who has decided to shoot. Over an ordinary bow draw this is enough
	 * to come round from any angle, so a shot that misses is a shot that missed
	 * rather than a character who never finished turning.
	 */
	private static final float AIM_RATE = 20f;

	/**
	 * One tick of using the thing in her hands.
	 *
	 * <h2>Three lines, and why that is the whole point</h2>
	 *
	 * Because this is what every other mod's NPC does not do. They animate an
	 * archer; this starts using the item and lets go of it, which is what a player
	 * does, so the bow draws because it is being drawn and the arrow leaves the
	 * hand because the bow put it there. Everything that follows from an item being
	 * used — enchantments, durability, the ammunition leaving the quiver, whatever
	 * a mod hung on its own weapon — follows here for nothing.
	 */
	private void workTheWeapon() {
		// Before the trigger, not after: the shot leaves along the rotation this
		// sets, so turning after the release would aim the previous shot.
		if (!aimingAt.isEmpty() && draw.pulling()) {
			Vec3 at = com.mopicmp.npcstudio.brain.Marks.eyes(this, aimingAt);
			if (at != null) turnBodily(at, AIM_RATE);
		}

		int drawnFor = isUsingItem() ? getTicksUsingItem() : -1;
		switch (draw.tick(drawnFor)) {
			case START -> startUsingItem(InteractionHand.MAIN_HAND);
			case LOOSE -> {
				releaseUsingItem();
				// The aim is let go with the arrow. Holding it would mean a character
				// who fired once tracking her target for ever afterwards, which is a
				// different thing and the graph should have to ask for it.
				aimingAt = "";
			}
			case LET_GO -> {
				stopUsingItem();
				aimingAt = "";
			}
			case NOTHING -> { }
		}
	}

	/**
	 * Turns to look at a point, head first and body only when the neck runs out.
	 *
	 * <h2>Why not the game's own lookAt</h2>
	 *
	 * Because it is not a turn, it is three instant assignments — pitch, body yaw,
	 * and the head snapped to the body — all in one tick. Used here it made a
	 * character noticing you spin bodily on the spot, which was reported as the
	 * head snapping round and, separately, as vision seeming to work through the
	 * whole circle. The second follows from the first: once the body has spun to
	 * face you, you are in front of it, and everything after that is consistent
	 * with having been seen all along.
	 *
	 * <h2>Two speeds, and what they say</h2>
	 *
	 * <h2>The speed says what happened</h2>
	 *
	 * It used to be chosen by her mood, which cannot say it: mood moves slowly by
	 * design, so a dropped stick and a stick of dynamite turned her head at the same
	 * rate. She whipped round at nothing and took her time about explosions.
	 *
	 * The rate now comes from how urgent the thing was, which is already the number
	 * that means exactly this. Quick for a bang, unhurried for a footstep — and a
	 * player learns to read which happened from across a room without being told.
	 *
	 * <h2>And then she squares up</h2>
	 *
	 * A glance is the head alone. Attention is not: nobody stands with their neck
	 * held at sixty degrees while they talk to somebody, and a character who does
	 * looks wrong in a way that is hard to name and impossible to ignore. It was
	 * reported exactly so — turning the head towards a stranger is natural, standing
	 * there sideways at them is not.
	 *
	 * So after about a second of paying attention the shoulders come round as well.
	 * That also settles a second complaint, and it is worth noticing that it is the
	 * same complaint: while the body never turned, the cone never turned either, so
	 * somebody could jog in a circle around her and stay behind her for ever. A
	 * character who has turned to face you cannot be walked around.
	 *
	 * @param squareUp whether to bring the body round as well, or only the head
	 */
	private void turnTowards(Vec3 at, com.mopicmp.npcstudio.foe.Lead lead, boolean squareUp) {
		float wanted = (float) com.mopicmp.npcstudio.foe.Sight.yawTo(getX(), getZ(), at.x, at.z);
		float head = com.mopicmp.npcstudio.foe.Neck.step(getYHeadRot(), wanted, lead.turningAt());

		// While it is a glance, the body moves only when the neck runs out — the game
		// already has an opinion about how far that is. Once it is attention, the
		// body goes where the head is going.
		float limit = getMaxHeadRotationRelativeToBody();
		float excess = squareUp
			? com.mopicmp.npcstudio.foe.Neck.wrap(head - yBodyRot)
			: com.mopicmp.npcstudio.foe.Neck.overTurn(head, yBodyRot, limit);
		if (excess != 0) {
			float body = com.mopicmp.npcstudio.foe.Neck.step(yBodyRot, yBodyRot + excess,
				lead.squaringAt());
			yBodyRot = body;
			setYRot(body);
		}

		// Last, and after the body has moved. The head turns three times as fast as
		// the shoulders, so between reaching the limit and the body catching up it
		// goes on outrunning it — which came out as a neck bent ten degrees further
		// than a neck bends, for a fifth of a second, every time she turned round.
		setYHeadRot(com.mopicmp.npcstudio.foe.Neck.held(head, yBodyRot, limit));
		Vec3 eye = getEyePosition();
		float pitch = com.mopicmp.npcstudio.foe.Neck.pitchTo(eye.x, eye.y, eye.z, at.x, at.y, at.z);
		setXRot(com.mopicmp.npcstudio.foe.Neck.step(getXRot(), pitch, lead.turningAt()));
	}

	private EntityDataAccessor<String> slotFor(Motion motion) {
		return switch (motion) {
			case IDLE -> DATA_ANIM_IDLE;
			case WALK -> DATA_ANIM_WALK;
			case RUN -> DATA_ANIM_RUN;
			case JUMP -> DATA_ANIM_JUMP;
		};
	}

	public String motionAnimation(Motion motion) {
		return entityData.get(slotFor(motion));
	}

	public void setMotionAnimation(Motion motion, String animation) {
		entityData.set(slotFor(motion), animation == null ? "" : animation);
	}

	/**
	 * What this NPC should be playing right now if nothing is talking to it.
	 *
	 * Worked out from how it is actually moving rather than from a state somebody
	 * has to remember to set, so an NPC pushed along by a route or by a piston
	 * animates without anyone arranging it.
	 */
	/** Whether she is carrying herself as somebody expecting a fight. */
	public boolean guarding() {
		return entityData.get(DATA_GUARD);
	}

	/**
	 * Squares her up, or lets her stand easy.
	 *
	 * Only the graph says this. Java has no business deciding that somebody is on
	 * guard — a character can be armed and relaxed or unarmed and squaring up, and
	 * guessing from the weapon would make both impossible to write.
	 */
	public void guard(boolean up) {
		entityData.set(DATA_GUARD, up);
		// Standing easy ends the combination. A fight that is over is the one moment
		// where starting again from the first blow is right, and it is a better
		// answer than a timer because it is the actual event.
		if (!up) blow.standDown();
	}

	/**
	 * The manner she moves in while on guard, from whatever is in her hands.
	 *
	 * Empty for a movement the pack has nothing for — bare hands have no fighting
	 * walk — and empty means "the way she always moves", which is a better answer
	 * than a swordsman's gait on a boxer.
	 */
	private String carriage(boolean moving, boolean quickly) {
		if (!guarding()) return "";
		return com.mopicmp.npcstudio.foe.Style
			.forWeapon(com.mopicmp.npcstudio.foe.Arms.of(getMainHandItem()))
			.carriage(moving, quickly);
	}

	public String restingAnimation() {
		// Standing still is decided by standing still, not by the ground flag. A
		// client's copy of an entity is not run through the physics the server
		// uses, so its `onGround` can read false for a character that has been
		// stood in the same spot for an hour — and asking that question first
		// meant the idle animation never played at all.
		double speed = getDeltaMovement().horizontalDistanceSqr();
		double falling = Math.abs(getDeltaMovement().y);
		if (falling > 0.08 && !onGround()) return motionAnimation(Motion.JUMP);
		// Squared, because comparing squared lengths avoids a square root and the
		// threshold is arbitrary anyway. Roughly a brisk walk.
		boolean moving = speed > 0.0005;
		boolean quickly = speed > 0.02;

		// On guard wins over the four animations somebody chose for this character,
		// and only while she is on guard. That is the whole of the second half of the
		// wooden fight: a blow with phases still reads as nothing if the character
		// between blows stands the way she stands in a queue and closes the distance
		// the way she walks to a shop.
		String guarded = carriage(moving, quickly);
		if (!guarded.isEmpty()) return guarded;

		if (quickly) return motionAnimation(Motion.RUN);
		if (moving) return motionAnimation(Motion.WALK);
		return motionAnimation(Motion.IDLE);
	}

	@Override
	public ResolvableProfile getProfile() {
		return entityData.get(DATA_PROFILE);
	}

	/** Dresses the NPC in a player's skin. Resolved by the server when needed. */
	public void setSkin(String playerName) {
		entityData.set(DATA_PROFILE, ResolvableProfile.createUnresolved(playerName));
	}

	/** Wears someone else's look wholesale — used to dress a preview as this NPC. */
	public void setProfile(ResolvableProfile profile) {
		entityData.set(DATA_PROFILE, profile);
	}

	@Override
	public void tick() {
		super.tick();
		// Only the side that owns the truth. The client works out for itself when to
		// stop drawing a gesture, and having it also clear the field would mean two
		// answers to the same question that drift apart.
		if (!level().isClientSide()) {
			expireGesture();
			expireExpression();
			refreshFace();
			keepWatch();
			// The doing, as against the deciding: whoever ordered the walk - a
			// reflex or a graph - the legs move here, every tick, the same way.
			// Before the graph, so that a graph asking whether she is free this tick
			// gets an answer about this tick rather than the last one.
			workTheBlow();
			walkOn();
			gaze();
			workTheWeapon();
			com.mopicmp.npcstudio.brain.Brain.tick(this);
			// Last, so the line describes the tick as it ended rather than as it began.
			record();
		}
	}

	/**
	 * One line of the tape, if one is being written.
	 *
	 * Everything is taken from where it actually lives rather than from anything
	 * kept for the purpose. A recorder with its own copy of the state records its
	 * own copy going wrong.
	 */
	private void record() {
		if (!com.mopicmp.npcstudio.foe.Tape.rolling()) return;

		var other = com.mopicmp.npcstudio.brain.Marks.creature(this,
			com.mopicmp.npcstudio.dialogue.Mark.TARGET);
		if (other == null) other = com.mopicmp.npcstudio.brain.Marks.kin(this);

		com.mopicmp.npcstudio.foe.Tape.note(tickCount,
			getName().getString() + "#" + getId(),
			guarding(),
			blow.committed() ? blow.phaseNow().name().toLowerCase(java.util.Locale.ROOT) : "",
			blow.link(3),
			blow.into(),
			gesture(),
			gestureAge(),
			gestureTicks(),
			doingState() == null ? doingNow() : doingState().currentNode(),
			other == null ? -1 : distanceTo(other),
			getHealth(),
			happened);
		happened = "";
	}

	/** Whether this character has yet asked the wardrobe where its eyes are. */
	private boolean askedForItsFace;

	/**
	 * Takes the worn costume's face from the world's wardrobe, once, after loading.
	 *
	 * <h2>Why a character's own copy is not good enough</h2>
	 *
	 * Because it is a copy, and it was taken at the moment the character was
	 * dressed. Everything since then — somebody marking the face properly, the mask
	 * learning to keep the size it was marked at — happened to the wardrobe's record
	 * and not to the copy. A character dressed before any of it goes on wearing the
	 * eight-by-eight answer it was handed, on a face drawn at thirty-two, for ever.
	 *
	 * That is not a small difference and it is exactly the one that was reported
	 * against: at eight cells a block is four texels by four, so the eye's opening
	 * runs a whole texel row below the eye and two texels into the cheek. The iris
	 * slides onto skin, skin travels with it, and the lash — one cell, honestly
	 * measured — is the entire eyelid.
	 *
	 * The wardrobe is the record, so the wardrobe is asked. Once, on the first tick
	 * after loading, and the answer is written back into the character's own outfit
	 * so that the next load has nothing to do.
	 */
	private void refreshFace() {
		if (askedForItsFace) return;
		if (costumeId.isEmpty() || worn < 0 || worn >= wardrobe.size()) {
			askedForItsFace = true;
			return;
		}

		// Not marked as asked until there was somebody to ask. A character can tick
		// before the world's wardrobe has opened, and giving up then would leave it
		// on its own copy until the next load — which is the whole fault, once more
		// and quieter.
		var library = com.mopicmp.npcstudio.wardrobe.Wardrobes.library();
		if (library == null) return;
		askedForItsFace = true;
		var found = library.find(costumeId);
		if (found.isEmpty()) return;

		FaceMask face = found.get().face();
		if (face.equals(wardrobe.get(worn).eyes())) return;
		wardrobe.set(worn, wardrobe.get(worn).looking(face));
		setFaceMask(face);
	}

	/**
	 * Puts a face on this character if it is wearing the costume named.
	 *
	 * So that marking a face is visible on everybody wearing it at once, rather
	 * than on whoever is dressed next. Called by the wardrobe when somebody
	 * finishes marking.
	 */
	public void refaceIfWearing(String costume, FaceMask face) {
		if (costume == null || costume.isEmpty() || !costume.equals(costumeId)) return;
		if (worn >= 0 && worn < wardrobe.size()) {
			wardrobe.set(worn, wardrobe.get(worn).looking(face));
		}
		setFaceMask(face);
	}

	/** What the NPC is doing with itself, or empty for nothing. */
	public String gesture() {
		return entityData.get(DATA_GESTURE);
	}

	/** How many ticks the current gesture has been running, on either side. */
	public int gestureAge() {
		return tickCount - entityData.get(DATA_GESTURE_START);
	}

	/**
	 * The tick the current gesture was asked for, on the server's own clock.
	 *
	 * Meaningless as a time on any other machine — a client counts an entity from
	 * when it came into view — and that is fine, because nobody reads it as a time.
	 * It is read as an <em>identity</em>: when this number changes, a new
	 * performance was asked for, even when it is a performance of the same thing.
	 */
	public int gestureBegan() {
		return entityData.get(DATA_GESTURE_START);
	}

	/**
	 * How long the current gesture was asked to last, or zero for indefinitely.
	 *
	 * Synched rather than kept on the server alone, because both sides need it and
	 * for different reasons. The server clears the gesture when the time is up, so
	 * that somebody arriving later is not told a character is still bowing. The
	 * client stops drawing it at exactly the right tick instead of waiting to be
	 * told — otherwise every gesture would hold its last frame for however long
	 * the packet took, which is the pose freezing and then snapping.
	 */
	public int gestureTicks() {
		return entityData.get(DATA_GESTURE_TICKS);
	}

	public void playGesture(String name, int ticks) {
		entityData.set(DATA_GESTURE, name == null ? "" : name);
		entityData.set(DATA_GESTURE_START, tickCount);
		entityData.set(DATA_GESTURE_TICKS, Math.max(0, ticks));
	}

	/**
	 * How long the current expression has been going, in ticks.
	 *
	 * A held face does not need this and a wink is nothing without it: a wink is a
	 * movement with a beginning, and the beginning is the only part of an
	 * expression that cannot be worked out from the clock alone.
	 */
	public int expressionAge() {
		return Math.max(0, tickCount - entityData.get(DATA_EXPRESSION_START));
	}

	/** The face this character is making, or {@link Expression#NEUTRAL}. */
	public Expression expression() {
		return Expression.named(entityData.get(DATA_EXPRESSION));
	}

	/**
	 * How far through the expression we are, nought to one, or one when it is held.
	 *
	 * Given as a fraction rather than a count so that the client can ease a face
	 * off at its end without knowing how long it was meant to last.
	 */
	public float expressionProgress() {
		int ticks = entityData.get(DATA_EXPRESSION_TICKS);
		if (ticks <= 0) return 1f;
		return Math.clamp((tickCount - entityData.get(DATA_EXPRESSION_START)) / (float) ticks,
			0f, 1f);
	}

	/**
	 * Sets the face, for a while.
	 *
	 * @param name  an {@link Expression} by name; anything unrecognised is an
	 *              ordinary face rather than an error
	 * @param ticks how long to hold it, or nought to hold it until something else
	 *              changes it
	 */
	public void express(String name, int ticks) {
		entityData.set(DATA_EXPRESSION, name == null ? "" : name);
		entityData.set(DATA_EXPRESSION_START, tickCount);
		entityData.set(DATA_EXPRESSION_TICKS, Math.max(0, ticks));
	}

	/**
	 * Puts the body back to itself once a bounded gesture has run its course.
	 *
	 * Only the server does this, and only for a gesture that was given a length.
	 * Clearing the name is what lets the resting animation come back, since the
	 * renderer falls back to standing or walking exactly when there is no gesture
	 * to show.
	 */
	/**
	 * Puts the face back to itself once a bounded expression has run its course.
	 *
	 * The same shape as a gesture expiring and for the same reason: without it, a
	 * character told to look angry for one line looks angry for the rest of the
	 * session.
	 */
	private void expireExpression() {
		int ticks = entityData.get(DATA_EXPRESSION_TICKS);
		if (ticks <= 0 || entityData.get(DATA_EXPRESSION).isEmpty()) return;
		if (tickCount - entityData.get(DATA_EXPRESSION_START) < ticks) return;
		entityData.set(DATA_EXPRESSION, "");
		entityData.set(DATA_EXPRESSION_TICKS, 0);
	}

	private void expireGesture() {
		int ticks = entityData.get(DATA_GESTURE_TICKS);
		if (ticks <= 0 || entityData.get(DATA_GESTURE).isEmpty()) return;
		if (gestureAge() < ticks) return;
		entityData.set(DATA_GESTURE, "");
		entityData.set(DATA_GESTURE_TICKS, 0);
	}

	/**
	 * How this character is built.
	 *
	 * Kept apart from {@link #scale()} on purpose, and the difference is not
	 * cosmetic. Scale is the game's own attribute: it moves the hitbox, the eye
	 * height and how far the character can reach. Shape is only how the model is
	 * drawn — a fat innkeeper does not catch arrows on a belly the server has
	 * never heard of. Worth saying out loud, because the alternative is somebody
	 * reporting it later as a bug.
	 */
	public BodyShape bodyShape() {
		return BodyShape.unpack(entityData.get(DATA_SHAPE), entityData.get(DATA_POSTURE));
	}

	/**
	 * Where this character's face keeps its eyes, as everybody watching sees it.
	 *
	 * <h2>Why this is the whole mask and not the eighth-scale grid</h2>
	 *
	 * It was the grid, and the grid is eight by eight over the face. On an ordinary
	 * sixty-four-wide skin that is one cell per texel and loses nothing. On a
	 * two-hundred-and-fifty-six-wide skin the face is thirty-two texels across and
	 * one cell is <b>four by four of them</b> — so an eye four texels tall became
	 * one row, a pupil four wide became one column, and every mark somebody made by
	 * hand was rounded to a block of sixteen texels.
	 *
	 * Everything reported about the eyes came from that one fact. Skin moved with
	 * the pupil because a four-by-four block moved. Narrowing tore the drawing
	 * because the patch painted over it was a four-by-four block. The lid borrowed
	 * its colour "two rows above the eye", which is eight texels up and lands in the
	 * eye itself or in the fringe. And a glance stepped a third of an eye at a time
	 * instead of sliding.
	 *
	 * So the mask travels whole, at whatever size it was marked at, as its own
	 * run-length text. The coarse grid is still here as a view for anything that
	 * only ever wanted rows — it is a view of the record now rather than the record.
	 */
	public FaceMask faceMask() {
		return FaceMask.decode(entityData.get(DATA_FACE), entityData.get(DATA_EYES_AUTHORED));
	}

	public EyeMap eyeMap() {
		return faceMask().reduce();
	}

	/**
	 * The marking as it travels: one line of text, undecoded.
	 *
	 * For the one caller that runs every frame. Decoding a mask and reading a face
	 * out of it is a walk over every cell of it — sixty-five thousand of them on a
	 * face marked at the finest size — and the answer only changes when somebody
	 * marks the face again. The text is the cheapest thing that says whether they
	 * have.
	 */
	public String faceMaskText() {
		return entityData.get(DATA_FACE);
	}

	/** Whether that marking was made by a person rather than read off the picture. */
	public boolean faceByHand() {
		return entityData.get(DATA_EYES_AUTHORED);
	}

	public void setFaceMask(FaceMask mask) {
		FaceMask face = mask == null ? FaceMask.NONE : mask;
		entityData.set(DATA_FACE, face.isNone() ? "" : face.encode());
		entityData.set(DATA_EYES_AUTHORED, face.authored());
	}

	public void setBodyShape(BodyShape shape) {
		BodyShape wanted = shape == null ? BodyShape.DEFAULT : shape;
		entityData.set(DATA_SHAPE, wanted.packed());
		entityData.set(DATA_POSTURE, wanted.packedPosture());
	}

	/**
	 * Which costume this character is wearing, or empty for none.
	 *
	 * Server-side only, and kept for one purpose: so that "put the build back to
	 * the costume's" can be asked for without the client naming a costume. A name
	 * that arrives over the wire is a name somebody chose; a name the server
	 * remembers is one it handed out.
	 */
	private String costumeId = "";

	public String costumeId() {
		return costumeId;
	}

	public void setCostumeId(String id) {
		costumeId = id == null ? "" : id;
	}

	/** How big this character is, where one is ordinary player size. */
	public float scale() {
		return (float) getAttributeValue(Attributes.SCALE);
	}

	public void setScale(float scale) {
		var instance = getAttribute(Attributes.SCALE);
		if (instance != null) instance.setBaseValue(Math.clamp(scale, 0.25f, 4.0f));
	}

	public String dialogueId() {
		return entityData.get(DATA_DIALOGUE);
	}

	public void setDialogueId(String id) {
		String now = id == null ? "" : id;
		if (now.equals(dialogueId())) return;
		entityData.set(DATA_DIALOGUE, now);
		// The document under her has changed, so where she had got to in it is not a
		// place any more. Guarded by the comparison above, because this is also
		// called on every load and on every save of an unrelated field, and a
		// character whose bookmark is reset twenty times a second never gets past
		// her first node.
		forgetWhereSheWas();
	}

	/**
	 * Right-clicking starts, or continues, the conversation.
	 *
	 * Continues is the important half. While a dialogue is open this click means
	 * "go on" rather than "talk to me", so a player who pressed Esc on a choice
	 * and came back picks up where they left off instead of starting over.
	 * The engine already behaves that way; wiring it up is the next step.
	 *
	 * This is {@code interact}, not {@code mobInteract}: the latter lives on
	 * {@code Mob}, which an {@link Avatar} is not. The third argument is where on
	 * the body the click landed — new in this version, and worth keeping in mind
	 * for later, when clicking an NPC's head might mean something different from
	 * clicking its hand.
	 */
	@Override
	public InteractionResult interact(Player player, InteractionHand hand, Vec3 hit) {
		if (level().isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		if (!(player instanceof ServerPlayer talker)) {
			return InteractionResult.PASS;
		}
		// Crouching means "let me at the workings" — the same gesture that opens a
		// block's own screen instead of using it. A plain click still talks, so an
		// author testing their dialogue is never one keypress away from a settings
		// screen they did not want.
		if (player.isShiftKeyDown() && player.isCreative()) {
			com.mopicmp.npcstudio.net.NpcEditing.open(talker, getId());
			return InteractionResult.SUCCESS;
		}
		if (dialogueId().isEmpty()) {
			return InteractionResult.PASS;
		}
		DialogueRuntime.talkTo(talker, this);
		return InteractionResult.SUCCESS;
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.store("Profile", ResolvableProfile.CODEC, getProfile());
		output.putString("Dialogue", dialogueId());
		output.putByte("SkinLayers", skinLayers());
		if (customSkin != null && customSkin.length > 0) {
			// Base64 because this file holds text and numbers, and a picture is
			// neither. It costs a third more room and saves inventing a format.
			output.putString("SkinFile", java.util.Base64.getEncoder().encodeToString(customSkin));
		}
		output.putInt("Worn", worn);
		for (int i = 0; i < wardrobe.size(); i++) {
			Outfit outfit = wardrobe.get(i);
			output.putString("Outfit" + i + "Label", outfit.label());
			output.putString("Outfit" + i + "Name", outfit.name());
			if (outfit.isPicture()) {
				output.putString("Outfit" + i + "Skin",
					java.util.Base64.getEncoder().encodeToString(outfit.pixels()));
			}
			if (!outfit.eyes().isNone()) {
				output.putString("Outfit" + i + "Face", outfit.eyes().encode());
				output.putBoolean("Outfit" + i + "EyesByHand", outfit.eyes().authored());
			}
		}
		output.putInt("Outfits", wardrobe.size());
		for (Motion motion : Motion.values()) {
			output.putString("Anim" + motion.name(), motionAnimation(motion));
		}
		// Written as the packed long rather than as named numbers, because unlike
		// the wardrobe file nobody opens a chunk in a text editor.
		output.putLong("Shape", entityData.get(DATA_SHAPE));
		output.putLong("Posture", entityData.get(DATA_POSTURE));
		output.putString("Costume", costumeId);
		output.putBoolean("Watchful", watchful());
		// The hands themselves are already saved by LivingEntity, which keeps
		// equipment for everybody. Only the mark on the quiver is ours.
		output.putBoolean("Endless", endless);
		// "Brain" is deliberately not written back. It was the second document a
		// character used to carry, it is read on load and turned into a dialogue,
		// and writing it again would keep resurrecting a field that no longer means
		// anything. What was carried over is remembered in `movedBrain` so that the
		// bench can say so out loud, once, to whoever comes looking.
		// What she has learnt about herself. Not the bookmark — see `memory`.
		if (!memory.isEmpty()) {
			output.store("Memory", MEMORY_CODEC, memory);
		}
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		input.read("Profile", ResolvableProfile.CODEC).ifPresent(p -> entityData.set(DATA_PROFILE, p));
		setDialogueId(input.getStringOr("Dialogue", ""));
		// An NPC placed before this field existed has no value saved, and the whole
		// layer should stay on rather than quietly vanishing on the next reload.
		setSkinLayers(input.getByteOr("SkinLayers", ALL_LAYERS));
		for (Motion motion : Motion.values()) {
			setMotionAnimation(motion, input.getStringOr("Anim" + motion.name(), ""));
		}
		// A character placed before shapes existed is shaped like everybody else.
		entityData.set(DATA_SHAPE, input.getLongOr("Shape", BodyShape.DEFAULT.packed()));
		// Through readPosture, because a character placed before height and taper
		// existed has zeros where they now live, and zero in that encoding means
		// the shortest possible person rather than an ordinary one.
		entityData.set(DATA_POSTURE, BodyShape.readPosture(
			input.getLongOr("Posture", BodyShape.DEFAULT.packedPosture())));
		costumeId = input.getStringOr("Costume", "");
		// Off for every character placed before this existed, which is every character
		// in every world so far and is the answer they all want.
		setWatchful(input.getBooleanOr("Watchful", false));
		endless = input.getBooleanOr("Endless", false);
		// A character saved when brains were a second field of their own. The graph
		// she was given is the graph she keeps — it moves into the one document a
		// character now has, rather than being dropped on the floor.
		//
		// A dialogue already set wins, because somebody chose it later and under the
		// arrangement that still exists. Either way what happened is remembered, so
		// that "she used to have a brain and now runs this" is a sentence somebody
		// can read rather than a difference they have to work out.
		String was = input.getStringOr("Brain", "");
		if (!was.isEmpty()) {
			movedBrain = was;
			if (dialogueId().isEmpty()) setDialogueId(was);
		}
		memory = input.read("Memory", MEMORY_CODEC).orElse(java.util.Map.of());
		wardrobe.clear();
		int outfits = input.getIntOr("Outfits", 0);
		for (int i = 0; i < outfits; i++) {
			String label = input.getStringOr("Outfit" + i + "Label", "");
			String name = input.getStringOr("Outfit" + i + "Name", "");
			String picture = input.getStringOr("Outfit" + i + "Skin", "");
			// A costume saved before faces were read has no map, which is not the
			// same as a face with no eyes: nought means "nobody has looked yet".
			//
			// The eighth-scale form is still read, because worlds are full of it.
			// Grown to a mask it is exactly as coarse as it was — nothing is
			// invented — but it goes down one path from here rather than two.
			boolean byHand = input.getBooleanOr("Outfit" + i + "EyesByHand", false);
			String face = input.getStringOr("Outfit" + i + "Face", "");
			FaceMask read = face.isEmpty()
				? FaceMask.of(new EyeMap(
					input.getLongOr("Outfit" + i + "Eyes", 0L),
					input.getLongOr("Outfit" + i + "Whites", 0L),
					input.getLongOr("Outfit" + i + "Brows", 0L),
					byHand))
				: FaceMask.decode(face, byHand);
			if (picture.isEmpty()) {
				wardrobe.add(Outfit.named(label, name).looking(read));
			} else {
				try {
					wardrobe.add(Outfit.picture(label,
						java.util.Base64.getDecoder().decode(picture)).looking(read));
				} catch (IllegalArgumentException unreadable) {
					// One unreadable costume, not a lost character.
					com.mopicmp.npcstudio.NpcStudio.LOGGER.warn(
						"Could not read a saved outfit: {}", unreadable.getMessage());
				}
			}
		}
		worn = Math.clamp(input.getIntOr("Worn", -1), -1, wardrobe.size() - 1);

		// And put the worn costume's face back on.
		//
		// This is why a face marked out by hand came back forgotten. The map lives
		// in the synchronised data so that everybody watching blinks the same way,
		// and synchronised data is not saved with the entity — it is set when the
		// costume is put on. Loading restored the wardrobe and which costume was
		// worn, and stopped there, so the map came back at its default: nought,
		// which means "nobody has looked at this face yet". The renderer then did
		// what it is supposed to do with that and read the face afresh, throwing
		// away the answer somebody had given it.
		//
		// Only the map, not the whole of wearing: the skin is restored below from
		// its own entry, and calling wear() here would set it twice — once from the
		// outfit and once from the file, with the second silently winning.
		if (worn >= 0) setFaceMask(wardrobe.get(worn).eyes());

		String saved = input.getStringOr("SkinFile", "");
		if (!saved.isEmpty()) {
			try {
				setCustomSkin(java.util.Base64.getDecoder().decode(saved));
			} catch (IllegalArgumentException unreadable) {
				// A corrupted skin costs this NPC its face, not the world its load.
				com.mopicmp.npcstudio.NpcStudio.LOGGER.warn(
					"Could not read the saved skin for an NPC: {}", unreadable.getMessage());
			}
		}
	}

	/** Nothing here should be pushed around by mobs or water. */
	@Override
	public boolean isPushable() {
		return false;
	}
}
