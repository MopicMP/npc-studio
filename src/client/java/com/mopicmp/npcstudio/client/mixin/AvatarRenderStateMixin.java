package com.mopicmp.npcstudio.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import com.mopicmp.npcstudio.client.entity.GestureHolder;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;

/** Gives the vanilla render state somewhere to carry a gesture. */
@Mixin(AvatarRenderState.class)
public class AvatarRenderStateMixin implements GestureHolder {

	@Unique private String npcStudio$name = "";
	@Unique private float npcStudio$age;
	@Unique private float npcStudio$strength = 1f;
	@Unique private com.mopicmp.npcstudio.client.skin.Eyes npcStudio$eyes;

	// Ordinary rather than null, so that everything downstream can ask without
	// checking. Players never have one set, and the ordinary build is exactly
	// what a player is.
	@Unique private com.mopicmp.npcstudio.entity.BodyShape npcStudio$build =
		com.mopicmp.npcstudio.entity.BodyShape.DEFAULT;

	@Override
	public String npcStudio$gesture() {
		return npcStudio$name;
	}

	@Override
	public float npcStudio$gestureAge() {
		return npcStudio$age;
	}

	@Override
	public com.mopicmp.npcstudio.entity.BodyShape npcStudio$shape() {
		return npcStudio$build;
	}

	@Override
	public void npcStudio$setShape(com.mopicmp.npcstudio.entity.BodyShape shape) {
		npcStudio$build = shape == null ? com.mopicmp.npcstudio.entity.BodyShape.DEFAULT : shape;
	}

	@Override
	public com.mopicmp.npcstudio.client.skin.Eyes npcStudio$eyes() {
		return npcStudio$eyes == null ? com.mopicmp.npcstudio.client.skin.Eyes.NONE : npcStudio$eyes;
	}

	@Override
	public void npcStudio$setEyes(com.mopicmp.npcstudio.client.skin.Eyes eyes) {
		npcStudio$eyes = eyes;
	}

	@Unique private com.mopicmp.npcstudio.client.scene.Playing.Sample npcStudio$staged;

	@Override
	public com.mopicmp.npcstudio.client.scene.Playing.Sample npcStudio$staged() {
		return npcStudio$staged;
	}

	@Override
	public void npcStudio$setStaged(com.mopicmp.npcstudio.client.scene.Playing.Sample staged) {
		npcStudio$staged = staged;
	}

	@Override
	public float npcStudio$gestureStrength() {
		return npcStudio$strength;
	}

	@Override
	public void npcStudio$setGesture(String name, float age, float strength) {
		npcStudio$name = name;
		npcStudio$age = age;
		npcStudio$strength = strength;
	}
}
