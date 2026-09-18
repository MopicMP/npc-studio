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
	 * Whether a route told her to walk or to run, as a number the client can see.
	 *
	 * Nought for "nobody said", then one past the ordinal, so that the absence and
	 * the first value are not the same byte. Synched because the animation is chosen
	 * where the character is drawn, and only the server knows what the graph ordered.
	 */
	private static final EntityDataAccessor<Byte> DATA_GAIT =
		SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.BYTE);

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
	 * Which tick of the animation the gesture starts at, and almost always nought.
	 *
	 * <h2>Why an animation gets to start partway in</h2>
	 *
	 * Because the movement we want is often a stretch inside a longer one that we
	 * have no reason to own the rest of. The flinch is the clearest case: the pack
	 * has no animation for taking a blow, but a death begins with somebody being
	 * hit, and the two ticks before the body folds are the character still standing
	 * there. Played from the top, a four-tick flinch spends half of itself doing
	 * nothing and then stops before the fold arrives.
	 *
	 * The alternative was cutting new files, which is a worse answer than saying
	 * which part we meant.
	 */
	private static final EntityDataAccessor<Integer> DATA_GESTURE_FROM =
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
		builder.define(DATA_GESTURE_FROM, 0);
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
		builder.define(DATA_GAIT, (byte) 0);
		builder.define(DATA_POST, net.minecraft.core.BlockPos.ZERO);
		builder.define(DATA_POST_YAW, 0f);
		builder.define(DATA_POSTED, false);
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

	/**
	 * Whether she stays in the air where she was put.
	 *
	 * <h2>Why nothing of ours is stored for this</h2>
	 *
	 * Because the game already has the flag, syncs it, and saves it. {@code NoGravity}
	 * is on every entity, travels to clients by itself, and is written into the world by
	 * vanilla's own save code — so a field here would be a second copy of a fact, which
	 * is the mistake this file has paid for three times over.
	 *
	 * <h2>What it is actually for</h2>
	 *
	 * Asked for as "so the NPC does not fall", and it is worth writing down that this
	 * also settles an animation fault. A character that is falling — even by the half
	 * block of a step, even for the two ticks after being nudged — is reported by
	 * {@link #restingAnimation} as jumping, and a character with no jump animation set
	 * then shows nothing at all. Standing still is what makes an idle animation play.
	 */
	public boolean floating() {
		return isNoGravity();
	}

	public void setFloating(boolean on) {
		setNoGravity(on);
		// Whatever fall she was in the middle of stops with it. Without this she keeps
		// the speed she had and drifts down for ever, which is the one behaviour the
		// switch exists to prevent and would be the first thing anybody saw.
		if (on) setDeltaMovement(getDeltaMovement().x, 0, getDeltaMovement().z);
	}

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
		// Not while she is going somewhere. This used to turn the head anyway, on the
		// grounds that somebody crossing a courtyard towards a noise while watching a
		// window is doing two things at once — which is true of a person and reads as
		// broken on a character, because there is no face on the model to sell it and
		// no neck that bends. Reported plainly: she walks with her head cranked ninety
		// degrees to one side.
		//
		// So while she walks she looks where she is walking, and the reflex has its
		// head back the moment she stops. What is given up is a bit of life while
		// crossing a room; what is bought is a character who does not look broken.
		if (walk.walking()) return;
		turnTowards(at, lead, watch.squaresUp());
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
	/**
	 * Whom she is walking at, or empty when she is walking to a place.
	 *
	 * A place stays where it is and a person does not, and the difference decides
	 * when the walk is over. Arriving at a place is arriving near the spot; arriving
	 * at a person is being near enough to <em>them</em>, measured against them,
	 * every tick — by the time she reaches the block somebody was standing on they
	 * are somewhere else.
	 */
	private String walkingAt = "";

	/**
	 * Sets off after somebody.
	 *
	 * The same walk, told what it is walking at, so that arriving can be judged
	 * against a thing that moves. Kept apart from {@link #walkTo} rather than
	 * folded into it because a graph asking to go to a doorway and a graph asking
	 * to close on a swordsman want different endings, and only one of them can be
	 * the default.
	 */
	public boolean walkAt(String mark, float pace) {
		walkingAt = mark == null ? "" : mark;
		return walkTo(com.mopicmp.npcstudio.brain.Marks.feet(this, mark), pace, walkingAt);
	}

	public boolean walkTo(Vec3 wanted, float pace) {
		return walkTo(wanted, pace, "");
	}

	private boolean walkTo(Vec3 wanted, float pace, String at) {
		if (wanted == null) return false;
		// Not while she is swinging. A strike is a step with the weight on it, and a
		// character who walks out of her own blow is the wooden thing this whole
		// piece of work is about — the arm carries on through an animation while the
		// feet take her somewhere else entirely.
		if (blow.committed()) return false;
		walkingAt = at;
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

	/**
	 * Stop where she is, and let go of the round she was on.
	 *
	 * <h2>Why halting ends the route rather than pausing it</h2>
	 *
	 * Because the node that ordered it is still the node she is standing at, and it
	 * puts a character back on a route it finds nobody carrying — that is what makes
	 * a patrol survive being interrupted. So a halt that merely cleared the order
	 * would be undone on the next tick, twenty times a second, and the character
	 * would stutter rather than stop, with two perfectly correct instructions
	 * fighting and nothing to point at.
	 *
	 * Ended, the arrival is there for the graph to read, and the walk node moves on
	 * to whatever comes after it. Which is what "stop walking" ought to mean to a
	 * graph: not "freeze", but "that is done with".
	 */
	public void halt() {
		stopWalking();
		if (!routeOrder.isEmpty()) routeOver();
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

		// Near enough to whoever she is walking at, judged against them rather than
		// against the last waypoint of a route that was worked out several ticks ago.
		// Before the check below, because arriving at somebody is a thing that can
		// happen without a route: they can walk into her.
		if (!walkingAt.isEmpty()) {
			var whom = com.mopicmp.npcstudio.brain.Marks.creature(this, walkingAt);
			if (whom != null && distanceTo(whom) <= closeEnoughTo(walkingAt)) {
				stopWalking();
				return;
			}
		}

		if (!walk.walking()) {
			// No route, which for somebody a step away is the search agreeing there is
			// nothing to search: every block between here and them is the one she is
			// standing in. She still has somewhere to go.
			closeTheLastStretch();
			return;
		}

		// Wedged against a fence post. Nobody needs to decide this: a route that
		// cannot be followed is not a route, and keeping it would mean pushing at
		// the post until something else happened.
		if (walk.stuck()) {
			// Which post, though. Written down before the walk is thrown away, because
			// the walk is the only thing that knows — and the next search is worth
			// asking only if it is told something the last one did not know.
			int[] post = walk.stoppedAt();
			if (post != null) {
				shun.add(com.mopicmp.npcstudio.foe.Ways.named(post[0], post[1], post[2]));
				// Kept small. This is a note about the last minute of walking, not a map
				// of the world's awkward corners — ground that was blocked by somebody
				// standing in it is passable again the moment they move, and a character
				// who remembers every doorway she ever waited at ends up refusing to use
				// her own house.
				while (shun.size() > MOST_SHUNNED) {
					shun.remove(shun.iterator().next());
				}
			}
			stopWalking();
			return;
		}

		// Walking at somebody, the route is not what says she has arrived — she has,
		// when she is near enough to them, and that was decided above. So the last
		// waypoint is held onto rather than dropped a block and a half out, which is
		// further away than any blow lands.
		double[] step = walk.heading(getX(), getY(), getZ(),
			walkingAt.isEmpty() ? com.mopicmp.npcstudio.foe.Walk.ARRIVED
				: com.mopicmp.npcstudio.foe.Walk.CLOSE_ENOUGH);
		if (step == null) {
			// The route ran out with her still short of them, which for somebody she
			// is closing on is the ordinary ending rather than a failure — see below.
			if (!closeTheLastStretch()) stopWalking();
			return;
		}
		stride(step);
	}

	/**
	 * How near somebody has to be before she stops asking for a route to them.
	 *
	 * Three blocks. Far enough that the last stretch of any approach is covered,
	 * short enough that walking straight cannot take her through much of anything:
	 * there is no wall three blocks long that she could be on the wrong side of
	 * without the route having said so already.
	 */
	private static final double LAST_STRETCH = 3.0;

	/**
	 * Walks the final part of an approach without a route.
	 *
	 * <h2>Why the pathfinder cannot do this bit</h2>
	 *
	 * Because a route is a list of blocks and the end of closing on somebody is
	 * shorter than a block. Two fighters a metre apart want to be four fifths of a
	 * metre apart; there is no sequence of block centres that says so, and every
	 * answer the search can give is either "you are already there" or a step past
	 * them.
	 *
	 * So the route gets her to the same few blocks and this covers the rest. It is
	 * not a shortcut around the pathfinding — it is the part that was never
	 * pathfinding, and pretending otherwise is what left two characters standing a
	 * metre apart looking at each other.
	 *
	 * Safe to walk blindly because it is three blocks at most and because
	 * {@link com.mopicmp.npcstudio.foe.Walk#stuck} is still watching: if she is
	 * walking into a pillar she stops the same way she would anywhere else.
	 *
	 * @return whether she is still closing, so the caller can tell this from the
	 *         walk being genuinely over
	 */
	private boolean closeTheLastStretch() {
		if (walkingAt.isEmpty()) return false;
		// The same rule the walk itself keeps: she does not walk out of her own blow.
		// Worth repeating here rather than relying on the walk having refused, since
		// this runs when there is no walk to have refused anything.
		if (blow.committed()) return false;
		var whom = com.mopicmp.npcstudio.brain.Marks.creature(this, walkingAt);
		if (whom == null) return false;
		double away = distanceTo(whom);
		if (away > LAST_STRETCH || away <= closeEnoughTo(walkingAt)) return false;
		stride(new double[] { whom.getX(), whom.getY(), whom.getZ() });
		return true;
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

	/**
	 * One tick of looking at what a graph pointed her at.
	 *
	 * <h2>Why it stands down while she is walking</h2>
	 *
	 * Because it was not merely turning her head — it was steering her.
	 *
	 * The order in {@code tick} is {@code walkOn}, then this. {@code stride} points
	 * the body at the next waypoint and sets {@code zza = 1}, which is "walk forward
	 * along the way you are facing"; then this ran and turned the body somewhere else;
	 * and the movement itself happens at the top of the <em>next</em> tick, inside
	 * {@code super.tick}. So the direction actually walked in was the direction of the
	 * gaze, and the waypoint had nothing to do with it.
	 *
	 * That is the whole of the report, both halves of it. A character told to look at
	 * the player and then sent along a route walks with her head screwed round behind
	 * her — and she walks <em>at the player</em>, into whatever happens to be between
	 * them, which is why a route that is fine half the time is hopeless the other
	 * half. Whether she got stuck depended on nothing about the route: it depended on
	 * whether a graph had told her to look at anything.
	 *
	 * <h2>Why the whole gaze and not just the body</h2>
	 *
	 * Leaving the head turned would fix the walking and keep the picture, and it is
	 * the wrong picture. Asked for plainly: while she is going somewhere she looks
	 * where she is going. A person crossing a courtyard does not walk it backwards
	 * watching you.
	 *
	 * <h2>Why it is not forgotten</h2>
	 *
	 * A gaze is a hold. She stops looking while her legs need the body and takes it up
	 * again when they stop, without the graph having to say it twice — which is what
	 * makes "look at the player, then walk to the gate, then talk" read the way it is
	 * written.
	 */
	private void gaze() {
		if (gazingAt.isEmpty()) return;
		if (com.mopicmp.npcstudio.dialogue.Mark.NOTHING.equals(gazingAt)) return;
		// The legs own the body while there is somewhere to be. Two authors of one
		// number is always a fault; here it was a fault that moved her.
		if (walk.walking()) return;
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
	 * Puts her at a place at once, without walking to it.
	 *
	 * <h2>What is dropped on the way</h2>
	 *
	 * The walk, and only the walk. A character who is somewhere else is not still
	 * following a path to somewhere she used to be going: the waypoints behind her
	 * would send her back through the wall she has just appeared on the far side of.
	 *
	 * Her home is <em>not</em> moved, and that is the point of it being separate. A
	 * scene that puts her round a corner has not changed where she belongs, and the
	 * steps of every route she has are still measured from the same place — otherwise
	 * one teleport would silently rewrite every path she owns.
	 *
	 * <h2>Why she is not turned as well</h2>
	 *
	 * Because she arrives facing the way she was going, which is the one thing about
	 * the moment the author already controls: she walked round the corner facing
	 * somewhere, and a body that also spins on arrival reads as a glitch rather than
	 * as a cut.
	 */
	public void appearAt(com.mopicmp.npcstudio.dialogue.Route.Point where) {
		if (where == null) return;
		Vec3 anchor = home();
		int[] to = com.mopicmp.npcstudio.dialogue.Route.world(where,
			net.minecraft.util.Mth.floor(anchor.x),
			net.minecraft.util.Mth.floor(anchor.y),
			net.minecraft.util.Mth.floor(anchor.z),
			homeFacing());
		stopWalking();
		// The bottom middle of the block, which is where a body stands — the same
		// reading a route point, a named place and the map's start all take of theirs.
		Vec3 at = Vec3.atBottomCenterOf(new net.minecraft.core.BlockPos(to[0], to[1], to[2]));
		// The game's own way of moving a body rather than setting the position by hand,
		// so the chunk is loaded and the client is told it was a move and not a stride.
		teleportTo(at.x, at.y, at.z);
	}

	/**
	 * Where she is to come back to.
	 *
	 * A place rather than a name, because "where I was told to stand" is a fact
	 * about this character in this world and there is nothing else it could mean.
	 * Unset until a graph asks for it, so a character who never posts anywhere
	 * carries nothing.
	 */
	/**
	 * Where she belongs, and which way she stands there.
	 *
	 * <h2>Why a place was not enough</h2>
	 *
	 * Reported plainly: a character sent home arrives in the right square with her
	 * nose against whatever wall she happened to approach from, which reads as
	 * broken rather than as finished. Going back somewhere means standing there as
	 * you stood, and half of standing somewhere is which way you are looking.
	 *
	 * It is also what makes a route of steps possible at all — "four forward" has no
	 * meaning without a forward, and this is it.
	 *
	 * <h2>Why it is synched, which it was not for an afternoon</h2>
	 *
	 * Because both sides measure against it and they have to agree to the block. The
	 * legs turn a step into a place on the server; the editor turns a click into a
	 * step on the client. While this lived in a plain field the client had no post at
	 * all and quietly measured from wherever she happened to be standing — so a route
	 * would have been right on the screen and wrong on the ground, which is the worse
	 * of the two and the harder to see.
	 */
	private static final EntityDataAccessor<net.minecraft.core.BlockPos> DATA_POST =
		SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.BLOCK_POS);

	private static final EntityDataAccessor<Float> DATA_POST_YAW =
		SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.FLOAT);

	/**
	 * Whether anybody has said where she belongs yet.
	 *
	 * A flag rather than a sentinel position, because there is no block of a world
	 * that cannot legitimately be somebody's post — and a character standing at the
	 * origin is a character standing at the origin, not one nobody has placed.
	 */
	private static final EntityDataAccessor<Boolean> DATA_POSTED =
		SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.BOOLEAN);

	/** Where she belongs, or null when nobody has said. */
	public Vec3 post() {
		return posted() ? Vec3.atBottomCenterOf(entityData.get(DATA_POST)) : null;
	}

	public boolean posted() {
		return entityData.get(DATA_POSTED);
	}

	public float postYaw() {
		return entityData.get(DATA_POST_YAW);
	}

	public void markPost() {
		markPost(blockPosition(), getYRot());
	}

	public void markPost(net.minecraft.core.BlockPos where, float facing) {
		entityData.set(DATA_POST, where);
		entityData.set(DATA_POST_YAW, facing);
		entityData.set(DATA_POSTED, true);
		// Saved from here on — see addAdditionalSaveData. It used to live only in
		// memory, which was honest while it meant "where I happened to be standing
		// when a graph asked", and stopped being honest the moment it became the place
		// a character belongs and the frame a route of steps is measured in. Both of
		// those are authorship, and authorship survives closing the world.
	}

	/**
	 * Where she belongs, falling back to where she is.
	 *
	 * <h2>Why the fallback must never be reached in practice</h2>
	 *
	 * Because it moves. A route of steps measured against "where she is now" walks
	 * her four blocks further out on every round, which over an afternoon is a
	 * character who has wandered off with no line anywhere to blame for it.
	 *
	 * So the post is pinned on her first tick — see {@link #tick} — and this fallback
	 * exists for the one frame before that and for a client that has not been told
	 * yet. It is the honest answer in both, and in neither is it used to walk.
	 */
	public Vec3 home() {
		Vec3 where = post();
		return where != null ? where : position();
	}

	public float homeYaw() {
		return posted() ? postYaw() : getYRot();
	}

	/** The frame a route's steps are measured in: her home, snapped to a quarter turn. */
	public com.mopicmp.npcstudio.dialogue.Route.Facing homeFacing() {
		return com.mopicmp.npcstudio.dialogue.Route.Facing.ofYaw(homeYaw());
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

	/**
	 * Blocks that have actually stopped her, which the next search is told about.
	 *
	 * Ordered, so that dropping the oldest is dropping the oldest rather than dropping
	 * whichever the hash happened to put first — a memory that forgets at random is a
	 * character who behaves differently on two runs of the same scene.
	 */
	private final java.util.LinkedHashSet<Long> shun = new java.util.LinkedHashSet<>();

	/**
	 * How many of those are remembered.
	 *
	 * Small on purpose. Whatever stopped her is usually a thing that moves — a shut
	 * gate, a villager in a doorway — so this is a note about the last minute rather
	 * than a map of the world, and a long memory would have her walking the long way
	 * round for ever because of somebody who has since wandered off.
	 */
	private static final int MOST_SHUNNED = 8;

	private void stopWalking() {
		walk.stop();
		headingFor = null;
		// Whoever she was following is forgotten with it. Otherwise arriving would
		// leave her quietly re-closing on them for ever afterwards, without anybody
		// having asked — and being shoved backwards by a blow would put her back in
		// on her own account rather than because her graph decided to.
		walkingAt = "";
		zza = 0;
		// Still eased, because coming to a stop is a movement too and a character who
		// stops dead is as wrong as one who starts dead.
		setSpeed(WALKING_PACE * walk.pacing(0));
	}

	/** How fast a character moves at full pelt; everything else is a fraction of it. */
	private static final float WALKING_PACE = 0.26f;

	/**
	 * The last climb she jumped for: where to, where from, and what she can step.
	 *
	 * Kept for the bench and nothing else. Jumping has been reported three times and
	 * I have now been wrong about the cause twice, both times because the report is
	 * necessarily a description — "he jumps a lot" — and the answer is arithmetic.
	 * Three numbers settle it: a climb of about a block with a step of six tenths is
	 * a route drawn a block too high, and no jump recorded at all while she is
	 * visibly hopping means the body never left the ground and it is the animation.
	 */
	private double[] lastJump;

	public double[] lastJump() {
		return lastJump;
	}

	/**
	 * One step towards the next waypoint.
	 *
	 * The physics is the game's own: the body is pointed at where it is going and
	 * told to walk forward, and vanilla does gravity, collision, slabs, stairs and
	 * water. Writing the movement by hand would mean reimplementing all of that
	 * badly, and this character is a player in every other respect already.
	 */
	/**
	 * How fast a head comes back to facing the way she is walking.
	 *
	 * Eased rather than snapped, because a head that jumps forward the instant the
	 * feet move is the same fault as one that snaps round to look at you — and both
	 * were reported. Unhurried on purpose: a shade slower than a gaze, so that setting
	 * off looks like turning to go rather than like the head being put back.
	 */
	private static final float FACES_THE_WAY = 9f;

	private void stride(double[] step) {
		float bearing = (float) com.mopicmp.npcstudio.foe.Sight.yawTo(
			getX(), getZ(), step[0], step[2]);
		yBodyRot = bearing;
		setYRot(bearing);

		// And the head with it. Nothing used to bring it forward: the body was pointed
		// down the path and the head kept whatever angle it had been left at, so a
		// character who had glanced at something and then set off walked the whole way
		// looking sideways at nothing. The body turning is not the same as the head
		// turning, and only one of them was ever written down.
		setYHeadRot(com.mopicmp.npcstudio.foe.Neck.step(getYHeadRot(), bearing, FACES_THE_WAY));
		// Level, too. A head left tilted up at a rooftop stays tilted up all the way
		// across the square, which is the same fault in the other axis.
		setXRot(com.mopicmp.npcstudio.foe.Neck.step(getXRot(), 0, FACES_THE_WAY));

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
		//
		// <h2>Why this is measured against her own step and not against a number</h2>
		//
		// It was 0.4, and 0.4 is less than half a block — so every slab, stair, path
		// block, layer of snow and carpet made her jump, continuously, for as long as
		// she was heading for a waypoint on one. Reported as "he jumps all the time
		// and in great quantities", and in a built map that is nearly everywhere.
		//
		// Two mistakes in one line. The waypoint's height is the block her feet go in,
		// so the floor of it is at exactly that number, while getY() is where her feet
		// actually are — and on half a block those differ by half a block with nothing
		// to climb. And the threshold was below her own step height, so even a real
		// half-block rise was jumped rather than walked up.
		//
		// Asked of the body rather than written down, because the step is an attribute
		// and anything that scales her changes it. A number here would be a number that
		// is right for one build.
		if (onGround() && step[1] - getY() > maxUpStep()) {
			// Written down as well as done, because this has been reported three times
			// and described differently each time. The bench shows the last one, so the
			// next report can be three numbers instead of a sentence.
			lastJump = new double[] { step[1], getY(), maxUpStep() };
			jumpFromGround();
		}
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
				if (state.isAir()) return true;
				var shape = state.getCollisionShape(level, where);
				if (shape.isEmpty()) return true;
				// Low enough to walk onto rather than into. A carpet, a layer of snow,
				// a pressure plate, the bottom half of a slab — a body steps up onto
				// all of those without leaving the ground, and calling them walls was
				// what sent every route one block into the air above them.
				//
				// Her own step rather than a number written here, because anything that
				// scales her changes it.
				return shape.max(net.minecraft.core.Direction.Axis.Y) <= maxUpStep();
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
			landing.getX(), landing.getY(), landing.getZ(),
			com.mopicmp.npcstudio.foe.Ways.FURTHEST,
			com.mopicmp.npcstudio.foe.Ways.LOOKED_AT,
			// What has already stopped her, priced rather than forbidden. This is what
			// makes the second attempt a different attempt: the search that produced the
			// path she could not walk had no idea it was doing so, and now it does.
			shun);
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

	// ---------------------------------------------------------------- the route

	/**
	 * Walking a whole path rather than going to one place.
	 *
	 * <h2>Why the count of points is here and not in the graph</h2>
	 *
	 * A bookmark is a place in a document, and it has nowhere to keep "I am on the
	 * fourth of six" — the one node that tried would have been the one node that
	 * needed saving, reloading and getting wrong. So the count lives beside the
	 * walking, which is where the rest of the truth about where her feet are
	 * already lives, and the node asks about it in two readings.
	 *
	 * <h2>What is deliberately not saved with the world</h2>
	 *
	 * All of it. A world reopened mid-round finds nobody carrying an order, so the
	 * node sends her round again from its first point. That is the same trade
	 * {@link com.mopicmp.npcstudio.dialogue.Node.Every} makes about a timer and it
	 * is right for the same reason: nobody can tell, and the alternative is a
	 * second kind of bookmark to save and to get wrong.
	 */
	private String routeOrder = "";

	private com.mopicmp.npcstudio.dialogue.Route route =
		com.mopicmp.npcstudio.dialogue.Route.NOWHERE;

	/** Which point of it she is making for. */
	private int routeAt;

	/**
	 * Whether the walk for {@link #routeAt} has already gone out.
	 *
	 * The one bit that tells "she has not set off yet" from "she has arrived", and
	 * both of them look identical from outside: in either she is standing still
	 * with a route in hand. Without it a route either never starts or finishes
	 * instantly, depending on which way round the question is asked.
	 */
	private boolean routeSent;

	/** Ticks left standing at a point that asked to be stood at. */
	private int routeStanding;

	/**
	 * How many times the point she is on has been ordered and not arrived at.
	 *
	 * Reset by arriving and by moving on, so it counts tries at <em>this</em> point
	 * rather than trouble over the round as a whole. A patrol with one awkward corner
	 * in it should not run out of patience on the fourth lap.
	 */
	private int routeTries;

	/** The last order she stopped carrying out, until the graph takes notice. */
	private String routeWalked = "";

	public String walkingTo() {
		return routeOrder;
	}

	public String walked() {
		return routeWalked;
	}

	/**
	 * Sets her along a path, or carries on with the one she is already on.
	 *
	 * The same order arriving again is the ordinary case rather than a mistake: the
	 * node that owns it says so on every tick it is standing at. A route that has
	 * changed under the same name is taken up where she is — that is somebody
	 * editing the path while she walks it, and sending her back to the first point
	 * on every keystroke would make the editing unusable.
	 */
	public void follow(String order, com.mopicmp.npcstudio.dialogue.Route wanted) {
		if (order == null || order.isEmpty() || wanted == null) return;
		com.mopicmp.npcstudio.dialogue.Route now = wanted;

		if (order.equals(routeOrder)) {
			if (!now.equals(route)) {
				route = now;
				// Clamped rather than reset. A point removed from under her leaves the
				// index past the end, and the route would otherwise finish on the spot.
				if (routeAt > route.size()) routeAt = route.size();
				// And the manner with it. Changing "walks" to "runs" while she is on
				// her round has to take effect on that round, or the switch is one
				// that does nothing until the next time she happens to set off.
				entityData.set(DATA_GAIT, (byte) (route.gait().ordinal() + 1));
			}
			return;
		}

		routeOrder = order;
		route = now;
		routeAt = 0;
		routeSent = false;
		routeStanding = 0;
		routeTries = 0;
		// A fresh order takes the old arrival with it, so that a graph cannot read an
		// answer about a route it has already left.
		if (order.equals(routeWalked)) routeWalked = "";
		entityData.set(DATA_GAIT, (byte) (route.gait().ordinal() + 1));
	}

	/**
	 * The order a route uses to walk her home, kept apart from any node's name.
	 *
	 * A name no node can have, because node names come out of the editor and it
	 * makes them from the kind — {@code walk 1}, {@code line 3} — and nothing in it
	 * can produce a space-free word beginning with a colon. Without that, a graph
	 * with a route node called "home" would find its own arrival answered by the
	 * walk back, which is the sort of collision that shows up once, in somebody
	 * else's map.
	 */
	private static final String HOMEWARD = ":home";

	/**
	 * Sends her back to where she was placed, standing as she was placed.
	 *
	 * <h2>Why walking home goes through the route machinery</h2>
	 *
	 * Because it is a route of one point, and everything a walk home needs — the
	 * pathfinding, the arrival, the giving up when a wall has gone up across the
	 * way, the animation that goes with it — is already there and already tested.
	 * A second way of walking somewhere would be a second thing to get wrong, and
	 * the first thing it would get wrong is the giving up.
	 *
	 * The facing is set on arrival for walking and at once for the other, which is
	 * the whole of the difference between the two.
	 */
	public void goHome(boolean walking) {
		Vec3 back = home();
		float facing = homeYaw();

		// Already on the way. The ending that ordered this is still the node the
		// graph is standing at, and a finished graph is stepped again on the next
		// tick — so without this the order would be given afresh twenty times a
		// second, and each fresh order starts the walk from its first point again.
		if (HOMEWARD.equals(routeOrder)) return;

		// Or already there, which is the same problem one step further on. She gets
		// home, the graph is stepped again, and off she goes home from home — which
		// is what "he came back only to walk the round again" was: two orders, both
		// correct, taking turns with the body.
		//
		// The facing is still set, because it costs nothing and because this is also
		// the path taken by a character who was standing at home when the graph
		// ended and should be turned to face the way she was placed.
		if (position().distanceToSqr(back) <= com.mopicmp.npcstudio.foe.Walk.ARRIVED
				* com.mopicmp.npcstudio.foe.Walk.ARRIVED) {
			setYRot(facing);
			setYHeadRot(facing);
			yBodyRot = facing;
			return;
		}

		if (!walking) {
			halt();
			// Through the server's own teleport rather than by writing the position,
			// so that whatever is riding on knowing where an entity is — the chunk it
			// belongs to, the clients watching it — is told properly.
			snapTo(back.x, back.y, back.z);
			setYRot(facing);
			setYHeadRot(facing);
			yBodyRot = facing;
			return;
		}

		homewardYaw = facing;
		follow(HOMEWARD, new com.mopicmp.npcstudio.dialogue.Route(
			java.util.List.of(new com.mopicmp.npcstudio.dialogue.Route.Point.At(
				net.minecraft.util.Mth.floor(back.x),
				net.minecraft.util.Mth.floor(back.y),
				net.minecraft.util.Mth.floor(back.z))),
			com.mopicmp.npcstudio.dialogue.Route.Gait.WALK,
			com.mopicmp.npcstudio.dialogue.Route.STROLL,
			com.mopicmp.npcstudio.dialogue.Route.From.WORLD));
	}

	/**
	 * Which way to stand once the walk home is over.
	 *
	 * Held here rather than being worked out on arrival, because by then she is
	 * home and "the way she was placed" and "the way she is standing" are the same
	 * answer — which is how a character ends up facing the wall she walked at.
	 */
	private Float homewardYaw;

	/** The graph has taken notice of an arrival, so it stops being one. */
	public void tookArrival(String order) {
		if (order != null && order.equals(routeWalked)) routeWalked = "";
	}

	/**
	 * One tick of walking a path.
	 *
	 * <h2>Why a point she cannot reach is stepped over rather than fatal</h2>
	 *
	 * Because both ways of failing to walk somewhere look the same from here and
	 * neither is worth stopping for. She is already standing on the point, so the
	 * search finds nothing to search; or a wall has gone up across the round since
	 * it was drawn. In the first the right answer is plainly to carry on, and in
	 * the second it is to carry on too — a guard who meets a new wall should walk
	 * the rest of her round, not stand at it for the life of the world.
	 */
	private void walkTheRoute() {
		if (routeOrder.isEmpty()) return;

		if (routeStanding > 0) {
			routeStanding--;
			return;
		}

		var point = route.at(routeAt);
		if (point == null) {
			routeOver();
			return;
		}
		int[] where = pointAt(point);

		if (!routeSent) {
			// The bottom middle of the block, which is where a body stands — the same
			// reading a named place and the map's start both take of theirs.
			walkTo(Vec3.atBottomCenterOf(
				new net.minecraft.core.BlockPos(where[0], where[1], where[2])),
				route.pace());
			// Set whether or not there was a way. An order that found none leaves her
			// not walking, and the next tick reads that as having arrived, which steps
			// over the point instead of standing at it.
			routeSent = true;
			return;
		}

		if (walk.walking()) return;

		// Arriving and giving up used to be the same thing here, and that was wrong in
		// the one way nobody could see. A walk ends when she gets there, and it ends
		// when she has been pushing at a wall for a second and a half — and this read
		// both as "she is there", stepped on to the next point, and on the last point
		// finished the round. The character stops part way along with the graph
		// certain the route was walked. Reported as exactly that: she catches on the
		// walls and then stands still without reaching the end.
		//
		// So it asks. She is either near the place or she is not, and if she is not
		// the walk is ordered again — from where she now stands, which is what makes
		// the second search different from the first and able to go round whatever
		// stopped the first.
		if (!com.mopicmp.npcstudio.foe.Walk.reached(where, getX(), getY(), getZ(),
				com.mopicmp.npcstudio.foe.Walk.ARRIVED)
				&& routeTries < com.mopicmp.npcstudio.foe.Walk.TRIES) {
			routeTries++;
			routeSent = false;
			return;
		}

		routeSent = false;
		routeTries = 0;
		// She got here, so whatever she was avoiding has done its work and the next leg
		// starts with an open mind. Held any longer, a gate that was shut once would go
		// on being walked round for the rest of the afternoon after somebody opened it —
		// which is a character taking the long way for a reason nobody can see.
		shun.clear();
		routeStanding = point.stay();
		routeAt++;
		if (routeAt >= route.size()) routeOver();
	}

	/**
	 * Where one point of the route is, in blocks of this world.
	 *
	 * Steps are turned into places here and nowhere else. Against her home rather
	 * than against where she is now: an anchor that moved with her would make a round
	 * of "four forward" walk her four blocks further out every time it came round,
	 * which is a character wandering off over an afternoon with no line anywhere to
	 * blame.
	 */
	private int[] pointAt(com.mopicmp.npcstudio.dialogue.Route.Point point) {
		Vec3 anchor = home();
		return com.mopicmp.npcstudio.dialogue.Route.world(point,
			net.minecraft.util.Mth.floor(anchor.x),
			net.minecraft.util.Mth.floor(anchor.y),
			net.minecraft.util.Mth.floor(anchor.z),
			homeFacing());
	}

	/**
	 * The end of a route, however it ended.
	 *
	 * Arriving and giving up are written down the same way on purpose. The graph
	 * asks "am I still on it", and to a graph the honest answer to both is no —
	 * see {@link com.mopicmp.npcstudio.dialogue.Sense#WALKED}, which says so out
	 * loud so that nobody writes a condition believing it means success.
	 */
	private void routeOver() {
		// Home, and standing as she was placed. Set here rather than when the walk was
		// ordered, because it is only true once she has arrived — and it is set on
		// giving up as well, which is right: a character who could not get home should
		// at least stop staring at whatever stopped her.
		if (HOMEWARD.equals(routeOrder) && homewardYaw != null) {
			setYRot(homewardYaw);
			setYHeadRot(homewardYaw);
			yBodyRot = homewardYaw;
			homewardYaw = null;
		}
		routeWalked = routeOrder;
		routeOrder = "";
		route = com.mopicmp.npcstudio.dialogue.Route.NOWHERE;
		routeAt = 0;
		routeSent = false;
		routeStanding = 0;
		routeTries = 0;
		entityData.set(DATA_GAIT, (byte) 0);
	}

	/**
	 * How she was told to carry herself, or null when nobody said.
	 *
	 * Told rather than measured. The animation used to be picked from how fast the
	 * body was actually moving, which made "walks or runs" and "how fast" one dial
	 * with two knobs on it — and a node offering both would have had one of them
	 * lying the moment the other was moved.
	 */
	public com.mopicmp.npcstudio.dialogue.Route.Gait toldGait() {
		byte told = entityData.get(DATA_GAIT);
		if (told <= 0 || told > com.mopicmp.npcstudio.dialogue.Route.Gait.values().length) {
			return null;
		}
		return com.mopicmp.npcstudio.dialogue.Route.Gait.values()[told - 1];
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
	 *
	 * <h2>And it is written down, which it was not</h2>
	 *
	 * A fourth time, in the same paragraph as the other three. It was read off the
	 * old key and never saved under one of its own, so it lasted exactly as long as
	 * the session the migration happened in — and that is the session where nobody
	 * asks, because nothing has gone wrong yet. The question comes months later:
	 * "why does she run this graph, I never gave her one." A field that cannot
	 * answer then is a comment with extra steps, which is precisely what the note
	 * above says it must not be.
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

	/** How many ticks the skill is still parked for, for a readout. */
	public int doingWaitLeft() {
		return doingWait;
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
	 * How far the blow she is about to throw actually reaches, in blocks.
	 *
	 * <h2>Off the animation, because that is where the answer was all along</h2>
	 *
	 * This used to be {@code ENTITY_INTERACTION_RANGE} — three blocks, plus half of
	 * each body, so three and a half. That attribute is how far a player can click
	 * on a mob. It is not a fact about an arm and it is roughly three times one,
	 * and the result was two characters standing three and a half blocks apart
	 * swinging at the air between them, which is what was reported.
	 *
	 * The real answer is written in the animation: where the edge is on the tick
	 * the blow lands. It is measured with {@code tools/shape-look/reach.ps1} and
	 * kept beside the contact tick in {@code swings.json}, because it is the same
	 * kind of fact about the same picture and belongs in the same place — where
	 * somebody can change it.
	 *
	 * <h2>What is added to it, and what is not</h2>
	 *
	 * Her scale, because a giant's arm is a giant arm and everything else about
	 * size already works that way. And whatever a weapon says about itself over
	 * vanilla's own baseline: an item carrying an {@code ENTITY_INTERACTION_RANGE}
	 * modifier is a modded polearm telling the world it reaches further, and it
	 * should be believed rather than argued with.
	 *
	 * Her own half-width is <em>not</em> added. The measurement starts at the
	 * model's own axis, which is where she stands, so her body is already inside
	 * the number — adding it again was the old formula counting her twice.
	 */
	public double reachOfBlow() {
		var style = com.mopicmp.npcstudio.foe.Style.forWeapon(
			com.mopicmp.npcstudio.foe.Arms.of(getMainHandItem()));
		double arm = style.timing(blow.link(style.chain().size())).reach() * getScale();
		double bare = Attributes.ENTITY_INTERACTION_RANGE.value().getDefaultValue();
		double weapon = getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE) - bare;
		return arm + Math.max(0, weapon);
	}

	/**
	 * How much room she leaves between herself and somebody she has walked up to.
	 *
	 * A fifth of a block. Not a preference — bodies push each other apart in this
	 * game, and a walk that ends inside somebody is a walk that ends in the two of
	 * them shoving. That was reported once already, as two characters circling each
	 * other instead of fighting.
	 */
	private static final double ELBOW_ROOM = 0.2;

	/**
	 * How near she means to get, walking at this mark.
	 *
	 * <h2>Three quarters of her reach, and why it is short of it</h2>
	 *
	 * Because arriving exactly at the edge of what she can hit means the first step
	 * either of them takes puts her outside it again, and the fight becomes walking.
	 * Standing inside her own reach costs nothing — a blow that could have landed
	 * from further away still lands from here.
	 *
	 * <h2>Why there is no upper limit on it</h2>
	 *
	 * There was one, and it would have been wrong for exactly the character it
	 * matters most for. A general walk stops a block and a half short, which is
	 * fine for a doorway and fine for a fist; a spear reaches over two blocks, and
	 * capping the approach at the general number would walk a spearman into
	 * punching range to use a polearm. What she wants is her own reach, whatever
	 * that is.
	 */
	public double closeEnoughTo(String mark) {
		var whom = com.mopicmp.npcstudio.brain.Marks.creature(this, mark);
		// A place, and nobody stands on an exact spot.
		if (whom == null) return com.mopicmp.npcstudio.foe.Walk.ARRIVED;
		double touching = getBbWidth() / 2 + whom.getBbWidth() / 2 + ELBOW_ROOM;
		return Math.max(touching, reachOfBlow() * 0.75 + whom.getBbWidth() / 2);
	}

	/**
	 * Whether the target is close enough to hit.
	 *
	 * Public because a graph has to be able to ask it, and it has to be this exact
	 * question. A graph that wrote down a distance instead was wrong for anybody
	 * built differently, and the gap between its number and the body's was where a
	 * blow's own knockback used to land her: still able to hit, and told to walk.
	 */
	public boolean canReach(String mark) {
		var target = com.mopicmp.npcstudio.brain.Marks.creature(this, mark);
		if (target == null) return false;
		// Their half-width, because the edge has to arrive inside their body rather
		// than at the point they are standing on. Hers is already in the reach.
		return distanceTo(target) <= reachOfBlow() + target.getBbWidth() / 2;
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

		// And it is seen: the few ticks of a body folding over a blow, and no more.
		// Where those ticks are inside the animation is the style's business, since
		// it is the style that knows which animation it is.
		var flinch = com.mopicmp.npcstudio.foe.Style
			.forWeapon(com.mopicmp.npcstudio.foe.Arms.of(getMainHandItem())).flinch();
		playGesture(flinch.animation(), flinch.ticks(), flinch.from());
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
		// The same question the graph asked before she committed, asked the same way.
		// Two ways of working out one reach is two numbers, and the gap between them
		// is where a fight goes wrong invisibly: she walks in because one says yes
		// and swings at nothing because the other says no.
		double reach = reachOfBlow();
		if (distanceTo(target) > reach + target.getBbWidth() / 2) {
			lastBlow = String.format(java.util.Locale.ROOT,
				"swung and missed — %.1f blocks away, reaches %.1f",
				distanceTo(target), reach + target.getBbWidth() / 2);
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

	/**
	 * The animation for this way of moving, or the resting one if none was set.
	 *
	 * <h2>Why an empty slot means "nothing special" and not "nothing"</h2>
	 *
	 * Reported as a character whose animation would not show, restarted itself, and
	 * worked only inside a scene. All of it was one thing: a slot with nothing in it
	 * meant nothing was drawn, and a character is constantly passing through ways of
	 * moving nobody wrote an animation for.
	 *
	 * A step down a slab is a tick or two of falling, which reads as a jump. A nudge is
	 * two ticks of walking. With only an idle animation set, each of those blanked the
	 * character for a moment and then brought the idle back — and coming back is a new
	 * performance, so it started again from its first frame. That is the restarting.
	 *
	 * So an unset slot now says "I have nothing special for this", which is what
	 * somebody who left it empty meant. The cost is honest and worth naming: a
	 * character walking with no walk animation glides in her idle pose without moving
	 * her feet. An empty idle as well means the empty string, which is the vanilla body
	 * doing vanilla things — the behaviour of a character nobody has animated at all.
	 */
	private String motionOrResting(Motion motion) {
		String set = motionAnimation(motion);
		return set.isEmpty() ? motionAnimation(Motion.IDLE) : set;
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
	/**
	 * How much of a blow's shove a braced fighter keeps her feet against.
	 *
	 * <h2>Read off a recording, not chosen</h2>
	 *
	 * A tape of two of them punching showed the same eleven ticks after every
	 * landed blow: the distance went from 2.3 to 4.0 and back to 2.9. Reach is
	 * about 3.6, so every single exchange ended with them out of range and needing
	 * to walk back in — and five of those ticks were the two of them still sliding
	 * apart on the knockback.
	 *
	 * Half of it keeps them inside reach: 2.3 plus 0.85 is 3.15. So a blow still
	 * moves somebody, and it stops being a blow that ends the exchange.
	 *
	 * <h2>Why it is an attribute and only while she is on guard</h2>
	 *
	 * Because bracing is what a fighter does and standing about is not, and because
	 * an attribute is a number the game already understands: a datapack can see it,
	 * a character can be built to shrug off more or less of it, and nothing here
	 * had to invent a second idea of being hard to push.
	 */
	private static final double BRACED = 0.5;

	private static final net.minecraft.resources.Identifier BRACING =
		com.mopicmp.npcstudio.NpcStudio.id("bracing");

	public void guard(boolean up) {
		entityData.set(DATA_GUARD, up);
		// Standing easy ends the combination. A fight that is over is the one moment
		// where starting again from the first blow is right, and it is a better
		// answer than a timer because it is the actual event.
		if (!up) blow.standDown();

		var footing = getAttribute(Attributes.KNOCKBACK_RESISTANCE);
		if (footing == null) return;
		footing.removeModifier(BRACING);
		if (up) {
			footing.addTransientModifier(new net.minecraft.world.entity.ai.attributes
				.AttributeModifier(BRACING, BRACED,
					net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
		}
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

	/**
	 * How many ticks a new way of moving has to last before it is believed.
	 *
	 * <h2>Why anything has to wait at all</h2>
	 *
	 * Because the readings are twitchy in a way the character is not. Vanilla's own
	 * numbers put a single tick of gravity at 0.08 and a real fall past 0.4 in about
	 * five ticks, and a step off a slab spends one or two ticks in between — so a
	 * character walking a path of slabs genuinely is "falling" every other step,
	 * briefly, by any measure taken from her speed alone.
	 *
	 * Four ticks sits between the two: longer than the blips, and a fifth of a second,
	 * which is under what anybody notices as a delay in starting to walk. It is
	 * reasoned from vanilla's constants rather than measured in a running game — that
	 * is worth saying, because if it turns out to be wrong it will be wrong by being
	 * too short.
	 */
	private static final int SETTLE = 4;

	/** The way of moving currently believed. The rule for believing it is in Settled. */
	private final Settled<Motion> settled = new Settled<>(Motion.IDLE, SETTLE);

	/**
	 * Takes one reading of how she is moving and lets it in only if it persists.
	 *
	 * Run on both sides, because both work the animation out for themselves: the client
	 * to draw it and the server to know what it is showing. They see slightly different
	 * movement — a client's copy is not run through the server's physics — and that is
	 * fine, since neither is telling the other. What matters is that each is steady.
	 */
	private void settleMotion() {
		settled.saw(movingAs());
	}

	/**
	 * How she is moving this very tick, before anything has been allowed to settle.
	 *
	 * <h2>Standing still is decided by standing still</h2>
	 *
	 * Not by the ground flag. A client's copy of an entity is not run through the
	 * physics the server uses, so its {@code onGround} can read false for a character
	 * that has been stood in the same spot for an hour — and asking that question first
	 * meant the idle animation never played at all.
	 *
	 * <h2>Why half a block down is not a jump</h2>
	 *
	 * It was {@code |dy| > 0.08} with {@code !onGround()} meant as the real guard, and
	 * that guard does not hold here for the reason just given. So the whole test was the
	 * number, and the number is a single tick of gravity — which any step down clears.
	 *
	 * A path of slabs laid into grass is half a block up and half a block down at every
	 * step, so she played the jump animation on every other one. Reported as "he jumps
	 * all the time, and in great quantities", over a screenshot of exactly that path.
	 *
	 * Rising is unambiguous: nothing but a jump lifts her, and vanilla's jump begins at
	 * 0.42, while stepping up a slab is a position the movement code corrects rather
	 * than a speed. Falling has to be a fall rather than a step: gravity reaches four
	 * tenths after about five ticks, which is two and a half blocks, and anything
	 * shorter than that is somebody walking downstairs.
	 *
	 * The speeds are squared, because comparing squared lengths avoids a square root and
	 * the threshold is arbitrary anyway. Roughly a brisk walk.
	 */
	private Motion movingAs() {
		double speed = getDeltaMovement().horizontalDistanceSqr();
		double vertical = getDeltaMovement().y;
		if (vertical > 0.1 || vertical < -0.4) return Motion.JUMP;
		if (speed > 0.02) return Motion.RUN;
		if (speed > 0.0005) return Motion.WALK;
		return Motion.IDLE;
	}

	public String restingAnimation() {
		// Read off the settled answer rather than off this tick's speed. Where the
		// thresholds are and why they are those numbers is at movingAs; what settleMotion
		// adds is that one twitchy tick is not a change of animation. See SETTLE.
		if (settled.believed() == Motion.JUMP) return motionOrResting(Motion.JUMP);
		boolean moving = settled.believed() != Motion.IDLE;
		boolean quickly = settled.believed() == Motion.RUN;

		// On guard wins over the four animations somebody chose for this character,
		// and only while she is on guard. That is the whole of the second half of the
		// wooden fight: a blow with phases still reads as nothing if the character
		// between blows stands the way she stands in a queue and closes the distance
		// the way she walks to a shop.
		String guarded = carriage(moving, quickly);
		if (!guarded.isEmpty()) return guarded;

		// What a route said, in preference to what the speed implies.
		//
		// Only while she is moving. A gait is an answer to "how does she travel", and
		// a character standing at a point of her round with the run animation playing
		// would be running on the spot.
		//
		// This is the whole of what makes the node's two settings two settings. The
		// animation was read off the measured speed, so an author who chose "runs" and
		// then slowed her below about half pace watched her start walking, with the
		// switch still reading "runs" and nothing anywhere admitting the disagreement.
		if (moving) {
			var told = toldGait();
			if (told == com.mopicmp.npcstudio.dialogue.Route.Gait.RUN) {
				return motionOrResting(Motion.RUN);
			}
			if (told == com.mopicmp.npcstudio.dialogue.Route.Gait.WALK) {
				return motionOrResting(Motion.WALK);
			}
		}

		if (quickly) return motionOrResting(Motion.RUN);
		if (moving) return motionOrResting(Motion.WALK);
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
		// On both sides, because both work out the animation for themselves and both
		// need it to be steady. Before anything else this tick, so that whatever asks
		// afterwards asks the same question and gets the same answer.
		settleMotion();
		// Only the side that owns the truth. The client works out for itself when to
		// stop drawing a gesture, and having it also clear the field would mean two
		// answers to the same question that drift apart.
		if (!level().isClientSide()) {
			// Where she belongs, settled once and never guessed at again.
			//
			// Pinned here rather than left to whoever first asks, because the fallback
			// answer is "wherever she is standing" and that moves. A route of steps
			// measured against a moving anchor walks her a little further out on every
			// round, which reads as a character quietly wandering off over an afternoon
			// with nothing in the graph to blame.
			//
			// For a character placed before this existed, the first tick after the
			// update is where she is, which is where she was placed unless a graph has
			// already moved her. The ring's "post here" is how that is corrected.
			if (!posted()) markPost();
			expireGesture();
			expireExpression();
			refreshFace();
			keepWatch();
			// The doing, as against the deciding: whoever ordered the walk - a
			// reflex or a graph - the legs move here, every tick, the same way.
			walkOn();
			// And the route after them, not before. Reaching a point is not something
			// the route notices — it is the walking that ends the walk, in the line
			// above — so asking first would see her still going and put the next point
			// off to the following tick. Over a dozen points that is a character who
			// walks in a series of small hesitations, and it would have looked like the
			// pathfinder rather than like an order of two lines.
			walkTheRoute();
			gaze();
			workTheWeapon();
			com.mopicmp.npcstudio.brain.Brain.tick(this);
			// After the graph, so that a blow and the animation of it share a clock.
			//
			// It ran before, and the tape caught the cost: a swing begun during the
			// graph's turn had its first tick consumed on the following one, so the
			// blade landed on the fifth frame of an animation whose contact was
			// authored on the fourth. One tick, invisible to anybody watching and
			// exactly the sort of thing that makes every measured number a lie.
			//
			// What it costs the other way is that a graph asking whether she is free
			// is answered about the previous tick, which can only ever delay her by
			// one — a thing nobody can see, unlike damage arriving off its picture.
			workTheBlow();
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
			// Where the skill is parked and for how long. The node alone always read
			// as the same one — a wait moves the bookmark past itself, so the name is
			// where she will resume rather than what she is doing — and a column that
			// says one thing for four hundred lines is a column nobody reads twice.
			(doingState() == null ? doingNow() : doingState().currentNode())
				+ (doingWaitLeft() > 0 ? " +" + doingWaitLeft() : ""),
			other == null ? -1 : distanceTo(other),
			other == null ? reachOfBlow() : reachOfBlow() + other.getBbWidth() / 2,
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

	/** Which tick of the animation the current gesture began at. */
	public int gestureFrom() {
		return entityData.get(DATA_GESTURE_FROM);
	}

	public void playGesture(String name, int ticks) {
		playGesture(name, ticks, 0);
	}

	/**
	 * Plays a stretch of an animation.
	 *
	 * @param ticks how long to play for, or nought to hold it until told otherwise
	 * @param from  which tick of the animation to start at. The length is still a
	 *              length rather than an end: asking for four ticks from two plays
	 *              two, three, four and five, which is what somebody naming a
	 *              stretch of a movement means and saves them one subtraction.
	 */
	public void playGesture(String name, int ticks, int from) {
		entityData.set(DATA_GESTURE, name == null ? "" : name);
		entityData.set(DATA_GESTURE_START, tickCount);
		entityData.set(DATA_GESTURE_TICKS, Math.max(0, ticks));
		entityData.set(DATA_GESTURE_FROM, Math.max(0, from));
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
		// Where she belongs, and which way she stands there. Authorship rather than
		// runtime — it is the place a graph sends her back to and the frame a route
		// of steps is measured in — so unlike the walking itself it survives the
		// world being closed.
		if (posted()) {
			net.minecraft.core.BlockPos where = entityData.get(DATA_POST);
			output.putInt("PostX", where.getX());
			output.putInt("PostY", where.getY());
			output.putInt("PostZ", where.getZ());
			output.putFloat("PostYaw", postYaw());
		}
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
		// anything.
		//
		// What was carried over is written under a name of its own instead, so the
		// answer to "why does she run this graph" outlives the one session in which
		// the moving happened. Under the old name it would be read back as a brain
		// again and moved again for ever; under this one it is a note about what was
		// done, which is a different thing and says so.
		if (!movedBrain.isEmpty()) output.putString("MovedBrain", movedBrain);
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
		// Absent for every character placed before this was saved, and absent has to
		// go on meaning "wherever she is standing" — see home(), which is what makes a
		// route of steps work the moment somebody is put down, with nothing set up.
		// Absent for every character saved before this existed, and absent has to go
		// on meaning "nobody has said" — the first tick then pins it where she stands,
		// which for a character nobody has moved is where she was placed.
		if (input.getIntOr("PostY", Integer.MIN_VALUE) != Integer.MIN_VALUE) {
			markPost(new net.minecraft.core.BlockPos(
				input.getIntOr("PostX", 0), input.getIntOr("PostY", 0),
				input.getIntOr("PostZ", 0)), input.getFloatOr("PostYaw", 0));
		}
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
		// The note left by an earlier load, first, so that a character who was moved
		// three worlds ago can still say what happened to her.
		movedBrain = input.getStringOr("MovedBrain", "");

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
