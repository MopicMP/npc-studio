package com.mopicmp.npcstudio.entity;

import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A model standing in the world.
 *
 * <h2>What it carries, and what it does not</h2>
 *
 * A name and a size. Not the geometry — the geometry is a document that is
 * edited, sometimes for an hour, and shipping it through entity data on every
 * keystroke would be a packet per nudge of a box. The name says which model this
 * is; whoever is drawing or measuring it looks it up.
 *
 * The document itself travels once, when it is saved, and lives in the world
 * afterwards — see {@link com.mopicmp.npcstudio.model.ServerModels}. That is what
 * makes an object visible to somebody who did not draw it, and it is also what
 * lets the server work out the object's shape rather than being told it.
 *
 * <h2>Why an entity rather than something of our own</h2>
 *
 * Because an entity is already a thing at a place with a rotation that the
 * game moves, saves, sends to clients and hands to a renderer. Everything a
 * placed object needs, minus the drawing. Axiom reached the same conclusion and
 * used display entities; ours differs only in that what it draws is ours.
 */
public class ModelObject extends Entity {

	private static final EntityDataAccessor<String> DATA_MODEL =
		SynchedEntityData.defineId(ModelObject.class, EntityDataSerializers.STRING);

	/**
	 * How big it is drawn, one being the size it was drawn at.
	 *
	 * Separate from the model rather than baked into it, because the same mast
	 * is a mast on a rowing boat and a mast on a galleon, and editing the
	 * geometry to say so would make two models that have to be kept in step.
	 */
	private static final EntityDataAccessor<Float> DATA_SCALE =
		SynchedEntityData.defineId(ModelObject.class, EntityDataSerializers.FLOAT);

	/**
	 * The corners of what is drawn, in blocks, relative to where the object stands.
	 *
	 * <h2>Corners rather than a width and a height</h2>
	 *
	 * Because a model is not centred on the thing it belongs to. Its Y counts up
	 * from the foot and its X and Z start wherever the boxes were drawn, so a cube
	 * from zero to one block sits entirely to one side of the object's own point. A
	 * width-and-height box is centred by definition, so covering that cube took a
	 * box two blocks wide reaching a block into empty air on the other side — which
	 * is the collision being bigger than the cube, exactly as it looked.
	 *
	 * <h2>What this is for, now that it is not the collision</h2>
	 *
	 * The broad phase and the drawing distance. What actually stops somebody is the
	 * list of parts below; this is the one box round all of them, which is what the
	 * game searches with before it asks anything finer.
	 *
	 * Worked out from the model by {@link #shapeFrom}, on whichever side is asking.
	 * It used to be sent by the client, and being told a summary of something you
	 * could measure yourself is how the two sides came to disagree.
	 */
	private static final EntityDataAccessor<Float> DATA_LOW_X =
		SynchedEntityData.defineId(ModelObject.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> DATA_LOW_Y =
		SynchedEntityData.defineId(ModelObject.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> DATA_LOW_Z =
		SynchedEntityData.defineId(ModelObject.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> DATA_HIGH_X =
		SynchedEntityData.defineId(ModelObject.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> DATA_HIGH_Y =
		SynchedEntityData.defineId(ModelObject.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> DATA_HIGH_Z =
		SynchedEntityData.defineId(ModelObject.class, EntityDataSerializers.FLOAT);

	private static final EntityDataAccessor<Boolean> DATA_SOLID =
		SynchedEntityData.defineId(ModelObject.class, EntityDataSerializers.BOOLEAN);

	public static final EntityType<ModelObject> TYPE = EntityType.Builder
		.<ModelObject>of(ModelObject::new, net.minecraft.world.entity.MobCategory.MISC)
		.sized(1f, 1f)
		.build(net.minecraft.resources.ResourceKey.create(
			net.minecraft.core.registries.Registries.ENTITY_TYPE, NpcStudio.id("model_object")));

	public ModelObject(EntityType<? extends ModelObject> type, Level level) {
		super(type, level);
		this.noPhysics = true;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(DATA_MODEL, "");
		builder.define(DATA_SCALE, 1f);
		builder.define(DATA_LOW_X, -0.5f);
		builder.define(DATA_LOW_Y, 0f);
		builder.define(DATA_LOW_Z, -0.5f);
		builder.define(DATA_HIGH_X, 0.5f);
		builder.define(DATA_HIGH_Y, 1f);
		builder.define(DATA_HIGH_Z, 0.5f);
		builder.define(DATA_SOLID, true);
	}

	public boolean solid() {
		return entityData.get(DATA_SOLID);
	}

	public void setSolid(boolean on) {
		entityData.set(DATA_SOLID, on);
	}

	/** Takes the corners the client worked out from the model, in blocks. */
	public void setBounds(float lowX, float lowY, float lowZ,
			float highX, float highY, float highZ) {
		entityData.set(DATA_LOW_X, Math.min(lowX, highX));
		entityData.set(DATA_LOW_Y, Math.min(lowY, highY));
		entityData.set(DATA_LOW_Z, Math.min(lowZ, highZ));
		entityData.set(DATA_HIGH_X, Math.max(lowX, highX));
		entityData.set(DATA_HIGH_Y, Math.max(lowY, highY));
		entityData.set(DATA_HIGH_Z, Math.max(lowZ, highZ));
		setBoundingBox(makeBoundingBox(position()));
	}

	/**
	 * The box, made from the corners as they were sent.
	 *
	 * Overridden rather than left to the dimensions, because dimensions are a width
	 * and a height and are centred on the entity by construction. What is wanted
	 * here is a box that sits where the model sits.
	 *
	 * The object's turn is <em>not</em> applied here, and that is the fix rather
	 * than an omission: it is applied on the way in, to each corner of each box of
	 * the model, before any of them is enclosed. Turning an enclosure encloses
	 * twice, and two enclosures at an angle to each other come out half as big
	 * again as what is inside them — which is a collision reaching well past the
	 * model in width and length, which is what it did.
	 */
	@Override
	protected net.minecraft.world.phys.AABB makeBoundingBox(net.minecraft.world.phys.Vec3 at) {
		return new net.minecraft.world.phys.AABB(
			at.x + entityData.get(DATA_LOW_X),
			at.y + entityData.get(DATA_LOW_Y),
			at.z + entityData.get(DATA_LOW_Z),
			at.x + entityData.get(DATA_HIGH_X),
			at.y + entityData.get(DATA_HIGH_Y),
			at.z + entityData.get(DATA_HIGH_Z));
	}

	/**
	 * How far away it may be and still be drawn.
	 *
	 * The game's own rule is sixty-four blocks for an entity a block across, scaled
	 * by how big its box is. That rule is for mobs and dropped items, where a
	 * speck at two hundred blocks is not worth a draw call. Scenery is the opposite
	 * case: the whole point of a ship is that it is there when you look at it from
	 * across the bay, and a mast that fades out at sixty-four blocks is a mast that
	 * disappears while you are flying away from it — which is what happened.
	 *
	 * So: as far as the view goes, and let the frustum do the culling. The cost is
	 * drawing scenery that is far away, which is the thing that was asked for.
	 */
	@Override
	public boolean shouldRenderAtSqrDistance(double distanceSquared) {
		return true;
	}

	/**
	 * One box per box of the model, relative to where the object stands.
	 *
	 * <h2>Why an entity that has one box carries a list of them</h2>
	 *
	 * Because the one box is a lie about the shape. A hull and its mast share a box
	 * that is mostly the air between them, and standing on that air is standing on
	 * nothing. The game builds an entity's collision from {@link #getBoundingBox()}
	 * and offers no hook for anything finer, so the finer answer is kept here and a
	 * mixin hands it over at the one place the shape is made.
	 *
	 * Empty means "no answer yet", and the single box is used instead — which is
	 * the behaviour without any of this, so nothing gets worse while it is missing.
	 *
	 * Not synched. The server is told them, because it has never seen a box; a
	 * client works them out from the model it already has, which is cheaper than
	 * sending them and cannot disagree with what it is drawing.
	 */
	private java.util.List<net.minecraft.world.phys.AABB> colliders = java.util.List.of();

	/** What the parts were last worked out from, so the work is not repeated. */
	private com.mopicmp.npcstudio.model.Model shapedFrom;
	private float shapedYaw;
	private float shapedScale;

	public java.util.List<net.minecraft.world.phys.AABB> colliders() {
		return colliders;
	}

	public void setColliders(java.util.List<net.minecraft.world.phys.AABB> boxes) {
		colliders = boxes == null ? java.util.List.of() : java.util.List.copyOf(boxes);
	}

	/**
	 * Works out the solid parts from a model, and takes them.
	 *
	 * <h2>One calculation, both sides</h2>
	 *
	 * This is the only place either side works out what an object is shaped like.
	 * It used to be two: a client measured the thing and sent the numbers, a server
	 * stored them. Two calculations of one thing disagree the moment anything is
	 * slightly out of step — a different angle, a packet not yet arrived — and a
	 * disagreement about collision is a player being pushed back where they came
	 * from, or a wall in mid-air. Both were seen.
	 *
	 * So the arithmetic lives in {@code main}, where both sides can reach it, and
	 * both sides run it on the same document. There is nothing left to disagree
	 * about except which document, and that is a name.
	 */
	public void shapeFrom(com.mopicmp.npcstudio.model.Model model) {
		if (model == null) {
			shapedFrom = null;
			setColliders(java.util.List.of());
			return;
		}
		// The same document as last time means the same answer as last time. A model
		// is immutable, so this is an identity check rather than a guess — and it is
		// what keeps a client from cutting two hundred turned boxes into pieces
		// twenty times a second to arrive at what it already had.
		if (model == shapedFrom && shapedYaw == getYRot() && shapedScale == modelScale()) return;
		shapedFrom = model;
		shapedYaw = getYRot();
		shapedScale = modelScale();

		float scale = modelScale() / 16f;
		java.util.List<net.minecraft.world.phys.AABB> found = new java.util.ArrayList<>();
		float lowX = Float.MAX_VALUE;
		float lowY = Float.MAX_VALUE;
		float lowZ = Float.MAX_VALUE;
		float highX = -Float.MAX_VALUE;
		float highY = -Float.MAX_VALUE;
		float highZ = -Float.MAX_VALUE;

		for (float[] box : com.mopicmp.npcstudio.model.ModelPose.boxes(model, getYRot())) {
			found.add(new net.minecraft.world.phys.AABB(
				box[0] * scale, box[1] * scale, box[2] * scale,
				box[3] * scale, box[4] * scale, box[5] * scale));
			lowX = Math.min(lowX, box[0] * scale);
			lowY = Math.min(lowY, box[1] * scale);
			lowZ = Math.min(lowZ, box[2] * scale);
			highX = Math.max(highX, box[3] * scale);
			highY = Math.max(highY, box[4] * scale);
			highZ = Math.max(highZ, box[5] * scale);
		}

		setColliders(found);
		if (!found.isEmpty()) setBounds(lowX, lowY, lowZ, highX, highY, highZ);
	}

	/**
	 * Every placed object standing in a level, so a big one can be found from
	 * anywhere along itself.
	 *
	 * <h2>Why a list of our own</h2>
	 *
	 * The game finds the entities in the way by looking in the chunk sections near
	 * where somebody is walking — the search box grown by two blocks — and an entity
	 * is filed in the one section its own position is in. That works because
	 * entities are about a block across. A model is not: a deck twenty blocks long
	 * is filed at one end of itself, so walking on the other end finds nothing at
	 * all and goes straight through. Which is exactly what it did — one block near
	 * the object's own point held, and the rest of it was air.
	 *
	 * There is no way to file an entity in every section it covers. So the objects
	 * keep a register, and the collision asks the register. A handful of objects
	 * checked with an overlap test each is cheaper than the section walk it stands
	 * beside.
	 *
	 * Weak on the level, so a world that has been left does not keep its objects
	 * alive; the set is concurrent because a server and an integrated client tick
	 * different levels on different threads.
	 */
	private static final java.util.Map<Level, java.util.Set<ModelObject>> STANDING =
		java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

	public static java.util.Collection<ModelObject> standingIn(Level level) {
		java.util.Set<ModelObject> found = STANDING.get(level);
		return found == null ? java.util.List.of() : found;
	}

	private void stand() {
		STANDING.computeIfAbsent(level(), any -> java.util.concurrent.ConcurrentHashMap.newKeySet())
			.add(this);
	}

	@Override
	public void remove(RemovalReason why) {
		java.util.Set<ModelObject> here = STANDING.get(level());
		if (here != null) here.remove(this);
		super.remove(why);
	}

	/**
	 * The solid parts, where they are in the world.
	 *
	 * Nothing at all when they are not known yet, and that is deliberate. The
	 * obvious fallback is the box round the whole object, and it is a trap: an
	 * object whose parts are spread over twenty blocks has a box that is twenty
	 * blocks of mostly air, so falling back to it turns a moment of not knowing into
	 * an invisible wall — one that stops somebody in open sky with nothing in reach,
	 * and stops them again when they try to drop off a ledge.
	 *
	 * Between a wall where there is nothing and a ghost where there is something,
	 * the ghost is the safe half of being wrong: it is visible, it is temporary, and
	 * nobody is thrown across the sky by it.
	 */
	public java.util.List<net.minecraft.world.phys.AABB> collidersInWorld() {
		if (colliders.isEmpty()) return java.util.List.of();
		// STANDS STILL: the parts are kept relative to where the object is and moved
		// on the way out, which is exact for something that does not move and one
		// frame behind for something that does. A moving object wants them swept
		// between where it was and where it is.
		net.minecraft.world.phys.Vec3 at = position();
		java.util.List<net.minecraft.world.phys.AABB> found =
			new java.util.ArrayList<>(colliders.size());
		for (net.minecraft.world.phys.AABB box : colliders) found.add(box.move(at));
		return found;
	}

	/**
	 * Re-measures when the numbers arrive.
	 *
	 * On a client the corners come in as entity data after the entity itself, so
	 * without this the box stays whatever it was made with until something else
	 * happens to refresh it — which for an object that never moves is never.
	 */
	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> what) {
		super.onSyncedDataUpdated(what);
		if (DATA_LOW_X.equals(what) || DATA_HIGH_Y.equals(what) || DATA_HIGH_X.equals(what)) {
			setBoundingBox(makeBoundingBox(position()));
		}
	}

	public String model() {
		return entityData.get(DATA_MODEL);
	}

	public void setModel(String name) {
		entityData.set(DATA_MODEL, name == null ? "" : name);
	}

	public float modelScale() {
		return entityData.get(DATA_SCALE);
	}

	public void setModelScale(float scale) {
		entityData.set(DATA_SCALE, Math.max(0.01f, scale));
	}

	/**
	 * Nothing aims at it, and that is not the same as nothing collides with it.
	 *
	 * <h2>The invisible obstruction</h2>
	 *
	 * This said yes, and saying yes puts the object in the way of the crosshair —
	 * the game asks every pickable entity whether the line of sight meets its
	 * bounding box, and takes the nearest answer. The box is the one round the whole
	 * model: for something spread over twenty blocks that is twenty blocks of mostly
	 * air, all of it standing between the player and whatever they are looking at.
	 *
	 * So a block behind it could not be reached, hit or broken. Turning the collision
	 * off did nothing, because this was never the collision — the reach was blocked,
	 * not the walking, and the two are separate questions with separate answers.
	 *
	 * <h2>What is given up</h2>
	 *
	 * Nothing. The editor never used this: clicking an object in the modelling view
	 * goes through {@link com.mopicmp.npcstudio.entity.ModelObject}'s own picking,
	 * which tries the boxes of the model themselves rather than the box round them —
	 * and does it better, since it can say which box was hit.
	 */
	@Override
	public boolean isPickable() {
		return false;
	}

	/**
	 * Whether anything walks into it.
	 *
	 * Axiom's objects are always ghosts, and that is a defensible choice — a thing
	 * being placed is a thing being moved through — but it is not the only one, and
	 * a deck nobody can stand on is a deck nobody can film a scene on. So it is a
	 * switch, and it is on: something put down in a world is a thing until somebody
	 * says otherwise.
	 */
	@Override
	public boolean canBeCollidedWith(Entity by) {
		return solid();
	}

	/**
	 * Nothing can hurt it, because there is nothing there to hurt.
	 *
	 * An object is scenery. Letting it take damage would mean deciding what a
	 * broken mast looks like, and the answer is that it does not break — it is
	 * removed by whoever placed it, in the editor where it was placed.
	 */
	@Override
	public boolean hurtServer(net.minecraft.server.level.ServerLevel level,
			net.minecraft.world.damagesource.DamageSource source, float amount) {
		return false;
	}

	@Override
	public void tick() {
		// STANDS STILL: deliberately. It has no physics, no gravity and nothing to
		// update; what moves it is somebody moving it. The day objects move, this is
		// where the moving goes, and every other note under that marker has to be
		// read again — search for it.
		setOldPosAndRot();

		// The box, rebuilt from where the thing actually is. Everything else that
		// keeps a bounding box in step does it when the entity moves, and this one
		// never moves — so the box is only ever built at the moments its corners or
		// its position are set, and any order in which those two arrive that puts
		// the corners first leaves a box measured from wherever the entity was
		// standing at the time, which on a client that has not been told the
		// position yet is the origin of the world. Doing it every tick costs one
		// object a tick for a thing there are a handful of, and removes the whole
		// question.
		setBoundingBox(makeBoundingBox(position()));

		// And says it is here, every tick, on both sides. Registering once on the way
		// in would be tidier and would miss the case that matters: an object that
		// arrives on a client before the level it belongs to has settled.
		stand();

		// A server that knows the model but has not measured this object yet does it
		// now. Self-healing rather than ordered: an object loaded from a world file,
		// one just placed, and one whose model has only now arrived are three
		// different orders of events with one right answer.
		// STANDS STILL: measured once and kept. A turning object would need this
		// every tick, and then the cost of the staircase stops being free.
		if (colliders.isEmpty() && !level().isClientSide()) {
			com.mopicmp.npcstudio.model.Model theirs =
				com.mopicmp.npcstudio.model.ServerModels.get(model());
			if (theirs != null) shapeFrom(theirs);
		}
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		entityData.set(DATA_MODEL, input.getStringOr("Model", ""));
		entityData.set(DATA_SCALE, input.getFloatOr("Scale", 1f));
		entityData.set(DATA_LOW_X, input.getFloatOr("LowX", -0.5f));
		entityData.set(DATA_LOW_Y, input.getFloatOr("LowY", 0f));
		entityData.set(DATA_LOW_Z, input.getFloatOr("LowZ", -0.5f));
		entityData.set(DATA_HIGH_X, input.getFloatOr("HighX", 0.5f));
		entityData.set(DATA_HIGH_Y, input.getFloatOr("HighY", 1f));
		entityData.set(DATA_HIGH_Z, input.getFloatOr("HighZ", 0.5f));
		entityData.set(DATA_SOLID, input.getBooleanOr("Solid", true));
		setBoundingBox(makeBoundingBox(position()));

		// Straightened here, before anything has been said about its shape. Objects
		// used to be put down facing whoever placed them, and nobody stands on an
		// axis — so every model in every world already saved arrived turned by some
		// arbitrary amount. Doing it at load rather than when a shape arrives matters:
		// a straighten in the middle of that conversation leaves one side holding
		// boxes worked out at the old angle.
		setYRot(0);
		setOldPosAndRot();

		// Six numbers a box, in the order they were written. Saved because a server
		// that has just started has no client to ask and a deck has to hold from the
		// first tick, not from the first time somebody opens the editor.
		java.util.List<Float> flat = new java.util.ArrayList<>();
		input.listOrEmpty("Colliders", com.mojang.serialization.Codec.FLOAT).forEach(flat::add);
		java.util.List<net.minecraft.world.phys.AABB> boxes = new java.util.ArrayList<>();
		for (int at = 0; at + 5 < flat.size(); at += 6) {
			boxes.add(new net.minecraft.world.phys.AABB(
				flat.get(at), flat.get(at + 1), flat.get(at + 2),
				flat.get(at + 3), flat.get(at + 4), flat.get(at + 5)));
		}
		setColliders(boxes);
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		output.putString("Model", model());
		output.putFloat("Scale", modelScale());
		// Saved, because a world reloaded has to be the world that was left. The
		// client would send the corners again the next time the model is edited, but
		// "next time somebody opens the editor" is not when a deck has to hold.
		output.putFloat("LowX", entityData.get(DATA_LOW_X));
		output.putFloat("LowY", entityData.get(DATA_LOW_Y));
		output.putFloat("LowZ", entityData.get(DATA_LOW_Z));
		output.putFloat("HighX", entityData.get(DATA_HIGH_X));
		output.putFloat("HighY", entityData.get(DATA_HIGH_Y));
		output.putFloat("HighZ", entityData.get(DATA_HIGH_Z));
		output.putBoolean("Solid", solid());

		var written = output.list("Colliders", com.mojang.serialization.Codec.FLOAT);
		for (net.minecraft.world.phys.AABB box : colliders) {
			written.add((float) box.minX);
			written.add((float) box.minY);
			written.add((float) box.minZ);
			written.add((float) box.maxX);
			written.add((float) box.maxY);
			written.add((float) box.maxZ);
		}
	}
}
