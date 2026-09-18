package com.mopicmp.npcstudio.client.knack;

import java.util.List;

import com.mopicmp.npcstudio.dialogue.Knack;
import com.mopicmp.npcstudio.net.KnackPayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * The abilities this mod writes itself, on the side where they happen.
 *
 * <h2>Why any of this is on the client</h2>
 *
 * Because a jump is. The key is pressed here, the movement is predicted here, and the
 * server only ever learns where somebody ended up. An ability that asked the server
 * whether it was allowed would answer a fifth of a second after the key — for a jump
 * that is not late, it is broken.
 *
 * So the answer arrives before the question: the server sends what is allowed, this
 * decides locally, and tells the server what it did so a graph can charge for it.
 *
 * <h2>Why the once-per-fall latch is here and not on the server</h2>
 *
 * Because "twice" is the whole of what a double jump is, and the moment it resets is the
 * moment the feet touch the ground — which this side knows exactly and the other side
 * knows a packet later. Left to a round trip, holding the key would give three jumps on
 * a bad connection and two on a good one, which is a rule nobody could write against.
 */
public final class Knacks {

	private Knacks() { }

	private static List<String> have = List.of();

	/** True while the key is down, so a hold is not a second press. */
	private static boolean jumpWasDown;

	/** Spent when a second jump is taken, put back when the ground is touched. */
	private static boolean spent;

	public static void accept(List<String> now) {
		have = List.copyOf(now);
	}

	/** Dropped with the world. The next one decides for itself what anybody may do. */
	public static void forget() {
		have = List.of();
		jumpWasDown = false;
		spent = false;
	}

	public static boolean has(String knack) {
		return have.contains(knack);
	}

	/**
	 * A tick of the local player, watching for a second jump.
	 *
	 * <h2>Why the key is read rather than the jump being intercepted</h2>
	 *
	 * Because the game does not raise anything when a jump is refused, and a jump in
	 * mid-air is refused — there is no event to listen for. What there is, is a key that
	 * went down while the feet were not on the ground, which is the same fact said
	 * without a mixin into movement.
	 */
	public static void tick(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null) {
			jumpWasDown = false;
			spent = false;
			return;
		}

		if (player.onGround()) spent = false;

		boolean down = client.options.keyJump.isDown();
		boolean pressed = down && !jumpWasDown;
		jumpWasDown = down;

		if (!pressed || spent || player.onGround()) return;
		if (!has(Knack.DOUBLE_JUMP)) return;
		// Not while swimming, climbing or flying: in all three the key already means
		// something, and taking it would be this mod breaking a control the game owns.
		if (player.isInWater() || player.onClimbable() || player.getAbilities().flying) return;

		// Upwards only, and set rather than added, so that a second jump out of a fall
		// is the same height as one out of a rise. Added, it would be a small hop when
		// falling fast and a rocket at the top of the first jump.
		Vec3 was = player.getDeltaMovement();
		player.setDeltaMovement(was.x, jumpUp(player), was.z);
		// The movement packet goes out on its own next tick, which is soon enough: the
		// server learns where somebody ended up rather than being asked permission, and
		// that is the whole arrangement — see KnackPayloads.
		spent = true;

		ClientPlayNetworking.send(new KnackPayloads.Used(Knack.DOUBLE_JUMP));
	}

	/**
	 * How hard the second jump pushes.
	 *
	 * Taken from the player's own jump rather than fixed, so that the game's forty
	 * properties still mean what they say: somebody with the jump strength raised jumps
	 * higher twice, which is what they asked for and what a fixed number would ignore.
	 */
	private static double jumpUp(LocalPlayer player) {
		return player.getJumpBoostPower() + player.getAttributeValue(
			net.minecraft.world.entity.ai.attributes.Attributes.JUMP_STRENGTH);
	}
}
