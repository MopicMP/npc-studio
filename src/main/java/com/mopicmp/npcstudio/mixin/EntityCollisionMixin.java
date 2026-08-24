package com.mopicmp.npcstudio.mixin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mopicmp.npcstudio.entity.ModelObject;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.EntityGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Gives a placed model the shape it actually has, and lets it be found along all
 * of itself.
 *
 * <h2>Two things the game does not do for a large entity</h2>
 *
 * <b>It asks for one box.</b> {@code getEntityCollisions} walks the entities in
 * the way and turns each into a shape with
 * {@code Shapes.create(entity.getBoundingBox())} — one call, one box, no hook.
 * That is fine for a pig and wrong for a hull with a mast on it, where the box is
 * mostly the air between the two: standing on that air is standing on nothing.
 *
 * <b>It looks for entities near where you are standing.</b> The search is the
 * movement box grown by two blocks, and an entity is filed in the one chunk
 * section its own position is in. A deck twenty blocks long is filed at one end of
 * itself, so walking on the other end finds nothing and goes straight through —
 * which is what happened: one block near the object's own point held, and the rest
 * was air.
 *
 * <h2>So: nothing from the walk, everything from the register</h2>
 *
 * The first injection makes a placed object contribute <em>nothing</em> where the
 * game found it, and the second adds its parts at the end, from the register the
 * objects keep of themselves. One source rather than two, so there is no case
 * where the same object is both a big box and a set of small ones and the big one
 * wins.
 *
 * <h2>Why it cannot break startup</h2>
 *
 * {@code require = 0} on both. If a future version stops calling
 * {@code Shapes.create} there, or renames the method, nothing is injected and
 * placed objects go back to being ghosts. A modelling mod is not worth a game that
 * will not start.
 */
@Mixin(EntityGetter.class)
public interface EntityCollisionMixin {

	@ModifyExpressionValue(
		method = "getEntityCollisions",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/phys/shapes/Shapes;create(Lnet/minecraft/world/phys/AABB;)Lnet/minecraft/world/phys/shapes/VoxelShape;"),
		require = 0)
	private VoxelShape npcStudio$notTheWholeBox(VoxelShape whole,
			@Local(ordinal = 1) Entity found) {
		if (!(found instanceof ModelObject object) || !object.solid()) return whole;
		// Nothing here. What this object is really shaped like is added below, and
		// leaving the box in as well would mean the air inside it stops people
		// whenever they happen to be near enough for the walk to find it.
		return Shapes.empty();
	}

	@ModifyReturnValue(method = "getEntityCollisions", at = @At("RETURN"), require = 0)
	private List<VoxelShape> npcStudio$boxPerBox(List<VoxelShape> found, Entity source,
			AABB testArea) {
		// Nobody may be asking. This argument is allowed to be null and the game
		// leans on that: "is this square of ground clear?" is a question about a
		// place rather than about a person, so the spawn finder asks it with no
		// entity at all.
		//
		// Dereferencing it anyway made every respawn throw. Not fail — throw, on the
		// server thread, while handling the packet, which the game swallows and logs.
		// So the client sat waiting for an answer that was never coming, the button
		// greyed itself out, and a player who had fallen into the void could not get
		// out of the death screen by any means at all. One missing null check, and
		// the one code path where it mattered was the one nobody exercises until
		// something has already gone wrong.
		net.minecraft.world.level.Level level = source != null ? source.level()
			: this instanceof net.minecraft.world.level.Level here ? here : null;
		if (level == null) return found;

		// STANDS STILL: every solid object in the level is tried, which is right
		// while there are tens of them and wrong at hundreds. A moving world wants
		// the register cut up by region, and that is the moment to do it.
		Collection<ModelObject> standing = ModelObject.standingIn(level);
		if (standing.isEmpty()) return found;

		List<VoxelShape> all = null;
		for (ModelObject object : standing) {
			if (object == source || !object.solid() || object.isRemoved()) continue;
			if (!object.getBoundingBox().intersects(testArea)) continue;

			for (AABB part : object.collidersInWorld()) {
				if (!part.intersects(testArea)) continue;
				if (all == null) all = new ArrayList<>(found);
				all.add(Shapes.create(part));
			}
		}
		return all == null ? found : all;
	}
}
