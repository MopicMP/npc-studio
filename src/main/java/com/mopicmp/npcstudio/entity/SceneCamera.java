package com.mopicmp.npcstudio.entity;

import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Where a scene is watched from: a thing you can click, drag and turn.
 *
 * <h2>Why it is an entity when nothing about it is</h2>
 *
 * A camera has no body, takes no damage, is never saved and no other player
 * should ever see one. By every ordinary measure it is a point in a document
 * rather than a thing in a world.
 *
 * It is an entity anyway, and the reason is the tools rather than the camera.
 * Selecting by clicking, the arrows, the turning ring, framing with a double
 * click, the row in the objects list — all of that is written against
 * {@code Entity} and all of it works the moment this is one. A point in a
 * document would need every one of those taught a second kind of subject, which
 * is the same feature written twice and two places for it to disagree.
 *
 * <h2>And why the server never hears of it</h2>
 *
 * Because it is not part of the world. It is spawned into the client's own level
 * and nowhere else: nothing is sent, nothing is saved, and closing the scene
 * takes it away with nothing left behind. Two people editing two scenes on one
 * server do not see each other's cameras, which is right — a camera belongs to
 * the shot somebody is framing, not to the place they are framing it in.
 *
 * The type is registered on both sides all the same. Registration is what makes
 * a type legal to construct; spawning is a separate act, and only the client
 * ever performs it.
 */
public class SceneCamera extends Entity {

	private static final ResourceKey<EntityType<?>> KEY =
		ResourceKey.create(Registries.ENTITY_TYPE, NpcStudio.id("scene_camera"));

	/**
	 * Small, but not nothing.
	 *
	 * The box is what a click is caught by, so a camera with no size would be a
	 * camera nobody can select — which is the one thing it has to be able to do.
	 * A third of a block is about the size of the marker drawn for it, so what is
	 * clicked and what is seen are the same object.
	 */
	public static final EntityType<SceneCamera> TYPE = EntityType.Builder
		.<SceneCamera>of(SceneCamera::new, MobCategory.MISC)
		.sized(0.35f, 0.35f)
		.build(KEY);

	public static void register() {
		Registry.register(BuiltInRegistries.ENTITY_TYPE, KEY, TYPE);
	}

	public SceneCamera(EntityType<? extends SceneCamera> type, Level level) {
		super(type, level);
		noPhysics = true;
		setNoGravity(true);
	}

	/**
	 * How wide the shot is: the vertical angle, in degrees.
	 *
	 * Vertical rather than horizontal, because that is the number the game itself
	 * works in — {@code Camera.getFov} is the vertical angle and so is the setting
	 * in the options — and a camera whose number means something else than the one
	 * it is fed into is a camera that films a different shot from the one drawn for
	 * it. The frame's own shape turns it into a width; see {@code CameraMarker}.
	 *
	 * Kept on the camera rather than only in the scene so that the marker can be
	 * drawn at the right width without asking the document — and so that a camera
	 * being dragged shows the shot it is making while it is being dragged.
	 */
	private float fov = 70f;

	public float fov() {
		return fov;
	}

	public void fov(float degrees) {
		fov = Math.clamp(degrees, 10f, 160f);
	}

	/**
	 * The box, put round the point rather than standing on it.
	 *
	 * Every other entity's box rises from its feet, because every other entity has
	 * feet. This one is a point of view: what it <em>is</em> is the place the
	 * picture is taken from, and a box sitting on top of that place would mean the
	 * marker, the click and the shot were three things a sixth of a block apart.
	 * Small enough not to matter and large enough that somebody aiming at the
	 * middle of the drawing selects nothing, which is the sort of fault nobody
	 * reports as a coordinate problem.
	 */
	@Override
	protected net.minecraft.world.phys.AABB makeBoundingBox(
			net.minecraft.world.phys.Vec3 at) {
		float half = getType().getDimensions().width() / 2f;
		return new net.minecraft.world.phys.AABB(
			at.x - half, at.y - half, at.z - half,
			at.x + half, at.y + half, at.z + half);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		// Nothing. Nothing about this travels, because it never leaves this client.
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		// Never read, because never written. A camera that came back from a save
		// would be a camera the world remembers, and the world must not.
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
	}

	@Override
	public boolean shouldBeSaved() {
		return false;
	}

	/**
	 * Not a thing to walk into, ride, or hit.
	 *
	 * It has a box so that it can be clicked in the editor, and clicking in the
	 * editor is our own ray rather than the game's — so as far as the game is
	 * concerned this is air.
	 */
	@Override
	public boolean isPickable() {
		return false;
	}

	@Override
	public boolean canBeCollidedWith(Entity by) {
		return false;
	}

	@Override
	public boolean isIgnoringBlockTriggers() {
		return true;
	}

	/**
	 * Nothing can hurt it, because nothing on a server knows it exists.
	 *
	 * This is only here because {@code Entity} insists it be answered. The honest
	 * answer is that the question cannot arise: the server has never been told
	 * about this entity and never will be.
	 */
	@Override
	public boolean hurtServer(net.minecraft.server.level.ServerLevel level,
			net.minecraft.world.damagesource.DamageSource source, float amount) {
		return false;
	}

	@Override
	public void tick() {
		// Deliberately not super.tick(): that is movement, fire, water and portals,
		// none of which mean anything here. Where it is, is decided by the scene.
	}
}
