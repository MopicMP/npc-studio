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
			.add(Attributes.MOVEMENT_SPEED, 0.0)
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

		Vec3 at = watch.lookingAt();
		if (at == null) return;
		turnTowards(at, watch.mood() == com.mopicmp.npcstudio.foe.Alarm.Mood.ALERT);
	}

	/** How far the head may swing in one tick: a glance, and a snap round. */
	private static final float GLANCING = 6f;
	private static final float SHARPLY = 15f;

	/** And the body, which always lags the head. */
	private static final float SHOULDERS = 5f;

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
	 * A suspicious character glances; an alert one snaps round. Reproducing that is
	 * not decoration — from across a room those two mean "I might still get away"
	 * and "I will not", and that is information the player needs and has no other
	 * way of getting.
	 */
	private void turnTowards(Vec3 at, boolean sharply) {
		float wanted = (float) com.mopicmp.npcstudio.foe.Sight.yawTo(getX(), getZ(), at.x, at.z);
		float head = com.mopicmp.npcstudio.foe.Neck.step(getYHeadRot(), wanted,
			sharply ? SHARPLY : GLANCING);

		// The body follows only once the head cannot turn any further, and the game
		// already has an opinion about how far that is. A character following
		// somebody across a doorway with her eyes should not shuffle round on the
		// spot to do it.
		float limit = getMaxHeadRotationRelativeToBody();
		float excess = com.mopicmp.npcstudio.foe.Neck.overTurn(head, yBodyRot, limit);
		if (excess != 0) {
			float body = com.mopicmp.npcstudio.foe.Neck.step(yBodyRot, yBodyRot + excess, SHOULDERS);
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
		setXRot(com.mopicmp.npcstudio.foe.Neck.step(getXRot(), pitch, sharply ? SHARPLY : GLANCING));
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
		if (speed > 0.02) return motionAnimation(Motion.RUN);
		if (speed > 0.0005) return motionAnimation(Motion.WALK);
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
		}
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
		entityData.set(DATA_DIALOGUE, id == null ? "" : id);
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
