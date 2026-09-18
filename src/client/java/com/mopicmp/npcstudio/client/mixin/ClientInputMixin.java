package com.mopicmp.npcstudio.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mopicmp.npcstudio.client.dialogue.Holding;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * Holds the player still while a line asks to be listened to.
 *
 * <h2>Why this needs a mixin at all</h2>
 *
 * The other two ways of showing a line already take control, and they take it by
 * being screens — a screen is open, so the keyboard belongs to it. A subtitle is
 * deliberately not a screen: the whole point of it is that the world is still
 * there and the player is still in it.
 *
 * So the only place left to say "not this line" is where the movement keys are
 * read. Doing it a tick later, from the client tick, does not work and is worth
 * saying so nobody tries: the input rebuilds the movement from the keyboard every
 * tick, so anything cleared afterwards is filled in again before it is used.
 *
 * <h2>What it does not touch</h2>
 *
 * Looking around. A player told to stand and listen may still turn their head, and
 * taking the mouse as well would be the difference between a pause and a seizure —
 * the camera stopping dead reads as the game having frozen.
 *
 * <h2>Two mistakes, and what they cost</h2>
 *
 * Worth writing down together, because they are the same mistake twice and the
 * second one took the game down.
 *
 * <b>It was on {@code ClientInput}.</b> {@code KeyboardInput extends ClientInput}
 * and <em>overrides</em> {@code tick}, and the player's input object is a
 * {@code KeyboardInput} — so {@code ClientInput.tick} is never called for a player
 * at all. The mixin applied, injected, reported success, and was dead.
 *
 * <b>Then it was moved here and kept the {@code @Shadow}.</b> {@code moveVector} is
 * declared on {@code ClientInput}, not here, and {@code @Shadow} resolves against
 * the target class alone — it does not follow inheritance. That is not a warning at
 * startup: mixin application fails, the whole class fails to transform, and the
 * client drops the world it is joining with "network protocol error", which names
 * nothing and points nowhere.
 *
 * The fix is the ordinary way to reach an inherited member: the mixin declares the
 * target's own superclass as its superclass, and then {@code moveVector} and
 * {@code keyPresses} are simply inherited fields that need no shadowing.
 *
 * <b>The lesson.</b> For a mixin, two questions have to be answered separately and
 * neither can be assumed from the other: <em>which class runs this method</em>, and
 * <em>which class declares this field</em>. Both were answerable in a second from
 * {@code javap} against the jar, both times, and both times I answered only one.
 */
@Mixin(KeyboardInput.class)
public abstract class ClientInputMixin extends ClientInput {

	@Inject(method = "tick", at = @At("TAIL"))
	private void npcStudio$holdStill(CallbackInfo info) {
		// One question, asked in one place. Whether it is a verb of the graph or a
		// document's blanket rule is Holding's business, and so is every way out of
		// it — the clock and the player's own hands. Asking any of that here would be
		// a second copy of the rules that let somebody go.
		if (!Holding.held()) return;

		// Both, because they are two accounts of the same press: the record is what
		// goes to the server, and the vector is what moves the body here. Clearing
		// one would leave the player walking on their own screen and standing still
		// on everybody else's.
		//
		// Inherited rather than shadowed. See above: shadowing them from here is what
		// stopped the game starting.
		this.keyPresses = Input.EMPTY;
		this.moveVector = Vec2.ZERO;
		// Jumping is a third account of the same press and is read from the record
		// above, so it is already answered — but sprinting is remembered on the player
		// rather than asked for each tick, and a sprint begun a moment before the line
		// arrived would otherwise carry them out of the scene.
		Minecraft client = Minecraft.getInstance();
		if (client.player != null) client.player.setSprinting(false);
	}
}
