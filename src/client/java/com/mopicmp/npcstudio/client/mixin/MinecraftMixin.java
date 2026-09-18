package com.mopicmp.npcstudio.client.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The frame, and the three buttons a route borrows while one is being drawn.
 *
 * <h2>The frame</h2>
 *
 * A tick will not do for a capture. Ticks come twenty a second whatever the
 * machine is doing, and a frame is what the capture is counting — the whole
 * point of it is that the scene advances because a frame was written rather than
 * because time passed.
 *
 * At the tail rather than the head, so the frame being written is the one that
 * has just been drawn instead of the one about to be.
 *
 * <h2>Why placing a route reaches this far down</h2>
 *
 * Because a route is placed with no window open. A window is what stops the
 * movement keys reaching the player, and a route longer than a room has to be
 * walked. So there is nothing on the display to take a click, and the click has
 * to be taken here.
 *
 * A key of our own was the alternative and it is worse in the way that matters:
 * this mod deliberately binds nothing on the way in — "a mod that takes a letter
 * of the keyboard has decided something that is not its to decide" — so a fresh
 * install would offer a button that opens a mode nobody can then use.
 *
 * <h2>What keeps the borrowing safe</h2>
 *
 * Every one of these does nothing at all unless a route is actually being placed,
 * which is a mode entered from a button and left by pressing escape. And escape is
 * let through the moment the mode is off, so the worst a fault here can do is cost
 * one press of a key that then works — rather than leaving somebody in a world with
 * no way to reach the menu, which is the failure this shape was chosen to make
 * impossible.
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {

	@Inject(at = @At("TAIL"), method = "runTick", require = 0)
	private void npcStudio$frame(boolean render, CallbackInfo info) {
		com.mopicmp.npcstudio.client.scene.Capture.frame();
	}

	/**
	 * The attack button puts a point down instead of swinging.
	 *
	 * Cancelled rather than merely acted on, because in creative a left click
	 * destroys the block it lands on — and the blocks it would land on are the floor
	 * of the path somebody is drawing.
	 */
	@Inject(at = @At("HEAD"), method = "startAttack", cancellable = true, require = 0)
	private void npcStudio$placePoint(CallbackInfoReturnable<Boolean> info) {
		if (com.mopicmp.npcstudio.client.map.Routing.clicked(
				com.mopicmp.npcstudio.client.map.Routing.removing())) {
			// False, which is what the game reads as "no swing happened". True would
			// leave the arm animating for a click that was never about the world.
			info.setReturnValue(false);
		}
	}

	/**
	 * And holding it does not mine.
	 *
	 * A separate method from the press, and it has to be: the press is one click and
	 * this is what runs every tick the button is held. Without it a held button would
	 * carry on breaking blocks while the presses were being taken.
	 */
	@Inject(at = @At("HEAD"), method = "continueAttack", cancellable = true, require = 0)
	private void npcStudio$noMining(boolean holding, CallbackInfo info) {
		if (com.mopicmp.npcstudio.client.map.Routing.inWorld()) info.cancel();
	}

	/**
	 * The use button does nothing at all while a route is being drawn.
	 *
	 * Swallowed rather than left alone: the whole mode is spent aiming at floors
	 * with whatever happens to be in hand, and a live use button means a route with
	 * a trail of somebody's building blocks laid along it.
	 */
	@Inject(at = @At("HEAD"), method = "startUseItem", cancellable = true, require = 0)
	private void npcStudio$removePoint(CallbackInfo info) {
		if (com.mopicmp.npcstudio.client.map.Routing.rightClicked()) {
			info.cancel();
			return;
		}
		// And the same button carries a conversation on when there is nobody to point
		// at — a scene a doorway began, spoken by the room. Second rather than first
		// because drawing a route is a mode somebody is deliberately in, and a mode
		// wins over a reflex. See SpeakingOn, which takes the press on the narrowest
		// condition there is and leaves the button alone in every other case.
		if (com.mopicmp.npcstudio.client.dialogue.SpeakingOn.took()) info.cancel();
	}

	/**
	 * A number key answers the question in the bar.
	 *
	 * Cancelled only on the press that actually answered, so this is one tick without
	 * the other keybinds and only when a question was up and a number was pressed.
	 * Cancelling whenever a question is on screen would be a player who cannot open
	 * their inventory while somebody is asking them something.
	 */
	@Inject(at = @At("HEAD"), method = "handleKeybinds", cancellable = true, require = 0)
	private void npcStudio$answerByNumber(CallbackInfo info) {
		if (com.mopicmp.npcstudio.client.dialogue.Answering.took()) info.cancel();
	}

	/**
	 * Escape ends the placing, and only the first one.
	 *
	 * The second press finds the mode already off and opens the pause menu as it
	 * always did. That ordering is the whole safety of this file: there is no state
	 * of the route placer that can cost more than one press of escape.
	 */
	@Inject(at = @At("HEAD"), method = "pauseGame", cancellable = true, require = 0)
	private void npcStudio$doneRouting(boolean pause, CallbackInfo info) {
		if (com.mopicmp.npcstudio.client.map.Routing.escaped()) info.cancel();
	}
}
