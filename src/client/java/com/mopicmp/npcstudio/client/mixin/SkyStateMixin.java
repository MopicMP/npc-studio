package com.mopicmp.npcstudio.client.mixin;

import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.math.Axis;
import com.mopicmp.npcstudio.client.scene.Filter;
import com.mopicmp.npcstudio.client.scene.Weather;
import com.mopicmp.npcstudio.scene.Channels;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;

/**
 * Puts the scene's sun where the scene wants it.
 *
 * <h2>Why here and not on the world's clock</h2>
 *
 * Because the two are different things and only one of them is wanted. Setting
 * the time moves the sun and also changes what mobs do, what the light level is,
 * whether the villagers go to bed — and it changes it for everybody on the
 * server, permanently, from a panel that was only being used to judge a picture.
 *
 * The sky is assembled once a frame into a state object with plain fields on it,
 * and the state is thrown away when the frame is drawn. Writing into it is
 * therefore exactly as far-reaching as it should be: this frame, this client,
 * nothing saved and nothing to put back.
 *
 * <h2>Radians, taken from the game rather than assumed</h2>
 *
 * {@code sunAngle} is stored in radians — the game multiplies its own degrees by
 * {@code 0.017453292} on the way in, which is what says so. Written down here
 * because a factor of fifty-seven is the kind of mistake that looks like the sun
 * simply being somewhere else.
 */
@Mixin(SkyRenderer.class)
public abstract class SkyStateMixin {

	private static final float RADIANS = (float) (Math.PI / 180);

	@Inject(method = "extractRenderState", at = @At("TAIL"), require = 0)
	private void npcStudio$sceneSky(ClientLevel level, float partial, Camera camera,
			SkyRenderState state, CallbackInfo info) {
		if (!Weather.showing()) return;

		if (Weather.says(Channels.SUN)) {
			// A whole turn, wrapped, so a key at 370 degrees is ten past noon rather
			// than an error. The moon and the stars keep station with it, or a scene
			// that moved the sun would have the night sky standing still behind it.
			float degrees = Weather.sun(0);
			float turn = degrees - (float) Math.floor(degrees / 360f) * 360f;
			float was = state.sunAngle;
			state.sunAngle = turn * RADIANS;
			state.moonAngle += state.sunAngle - was;
			state.starAngle += state.sunAngle - was;
		}

		state.skyColor = Weather.sky(state.skyColor);

		// And the glow along the horizon, which belongs to the sun rather than to the
		// sky: it is the sun's light in the air, so it takes the sun's colour. Without
		// this a green sun rose behind an ordinary orange dawn.
		float[] sun = Weather.sunFilter();
		if (Filter.changes(sun)) {
			state.sunriseAndSunsetColor = Filter.over(state.sunriseAndSunsetColor, sun);
		}
	}

	/**
	 * Which way round the horizon the sun's arc runs.
	 *
	 * <h2>What this one constant is</h2>
	 *
	 * The whole celestial sphere is drawn inside one rotation about the vertical,
	 * written in the game as {@code Axis.YP.rotationDegrees(-90)}, and everything
	 * that moves with the day — sun, moon, stars — sits inside it. So the arc's
	 * compass direction is that number and nothing else, and turning it turns the
	 * sky rigidly: the sun goes on rising and setting exactly as it did, on a line
	 * drawn somewhere else across the sky.
	 *
	 * That is the half of "put the sun where I want it" that a single angle cannot
	 * do. One number moves it along its arc; this one chooses which arc.
	 */
	@ModifyConstant(method = "renderSunMoonAndStars", constant = @Constant(floatValue = -90f),
		require = 0)
	private float npcStudio$sunAzimuth(float eastward) {
		return Weather.showing() ? eastward + Weather.sunTurn(0) : eastward;
	}

	/**
	 * The dawn, turned to follow the sun it belongs to.
	 *
	 * A second hook because the glow is a second call, with its own pose and its
	 * own pair of rotations — it is not inside the sphere the sun is drawn in, so
	 * the constant above does not reach it. Left alone, moving the sun to the north
	 * would leave its sunrise happening in the east.
	 *
	 * Composed onto the front of the fan's own first rotation rather than pushed
	 * separately, because there is nowhere here to pop a push: the pose belongs to
	 * the caller and is used again for the sun immediately afterwards. Multiplying
	 * in at the first rotation gives {@code pose · turn · fan}, which is exactly
	 * what applying the turn before the fan means.
	 */
	@ModifyArg(method = "renderSunriseAndSunset",
		at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lorg/joml/Quaternionfc;)V",
			ordinal = 0),
		index = 0, require = 0)
	private Quaternionfc npcStudio$dawnAzimuth(Quaternionfc fan) {
		if (!Weather.showing()) return fan;
		float turn = Weather.sunTurn(0);
		if (turn == 0) return fan;
		return new Quaternionf(Axis.YP.rotationDegrees(turn)).mul(fan);
	}

	/**
	 * The sun, in whatever colour the scene asked for.
	 *
	 * The disc is drawn through a pipeline that already takes a colour: the game
	 * hands it {@code (1, 1, 1, alpha)}, the shader multiplies the sprite by it,
	 * and the alpha is how far through the day it is. So a tint is one number
	 * changed on its way past, and the fade at dawn and dusk goes on working
	 * because the alpha is not touched.
	 *
	 * <h2>Why it is a filter and not the colour itself</h2>
	 *
	 * It was the colour itself, divided by two hundred and fifty-five, and that is
	 * a dimmer wearing a colour picker's clothes. A green of {@code 65, 150, 67} —
	 * a perfectly ordinary green, and bright in the swatch beside the slider — came
	 * out as a multiplier of about a quarter on red and blue and three fifths on
	 * green. The sun went to a fifth of its brightness, on a sky that had been made
	 * green by the same hand, and disappeared. It was reported as the sun vanishing,
	 * which is exactly what it was.
	 *
	 * A colour chosen from a swatch is a hue. If somebody wants a dim sun they can
	 * say so with the time of day, which is the control that means it. See
	 * {@link Filter}, where the same rule and the same arithmetic serve the sky.
	 */
	@ModifyArg(
		method = "renderSun",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/DynamicUniforms;writeTransform(Lorg/joml/Matrix4f;Lorg/joml/Vector4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"),
		index = 1,
		require = 0)
	private Vector4f npcStudio$sunColour(Vector4f tint) {
		float[] sun = Weather.sunFilter();
		if (!Filter.changes(sun)) return tint;
		npcStudio$noteColouring(sun);
		return new Vector4f(tint.x * sun[0], tint.y * sun[1], tint.z * sun[2], tint.w);
	}

	/**
	 * Says once, in the log, that the sun really is being coloured.
	 *
	 * Here because the injection above may silently not exist. Everything in this
	 * file is written with {@code require = 0} so that a version which has moved
	 * the target does not stop the game — which is the right trade and has one
	 * cost: a hook that never fires and a hook that fires with nothing to do look
	 * identical from the outside, and "the sun is always white" is the sentence
	 * both of them produce. One line settles it without anybody guessing again.
	 */
	private static boolean npcStudio$noted;

	private static void npcStudio$noteColouring(float[] sun) {
		if (npcStudio$noted) return;
		npcStudio$noted = true;
		com.mopicmp.npcstudio.NpcStudio.LOGGER.info(
			"Colouring the sun by {}, {}, {}", sun[0], sun[1], sun[2]);
	}
}
