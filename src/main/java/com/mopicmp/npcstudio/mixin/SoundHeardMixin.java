package com.mopicmp.npcstudio.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mopicmp.npcstudio.foe.Din;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Listening in on the server's own sound plumbing.
 *
 * <h2>What this buys, and why it is two hooks and not twenty</h2>
 *
 * Reacting to a block being broken, an arrow loosed, a piston, a pressure plate
 * or a stick of dynamite could be twenty pieces of code, one per thing, and the
 * list would never be finished — the next version adds to it and so does every
 * other mod installed.
 *
 * But all of it already passes through here. Everything in the game that makes a
 * noise says so, with a position and a volume, through {@code playSeededSound};
 * and the few effects that are sent as an event rather than a sound — a block
 * finally giving way is the one that matters — go through {@code levelEvent}. So
 * two hooks cover the lot, including everything nobody has written yet.
 *
 * <h2>Only the server</h2>
 *
 * {@code ServerLevel} rather than {@code Level}, because a character's ears are
 * the server's business — see {@link com.mopicmp.npcstudio.foe.Watch}. The
 * client plays the same sounds again for the player to actually hear, and
 * writing those down as well would be the same bang counted twice.
 */
@Mixin(ServerLevel.class)
public class SoundHeardMixin {

	/**
	 * Whether a sound is the sort a character could react to.
	 *
	 * Music and records are not events in the world, they are things a player has
	 * put on, and a guard who investigates a jukebox is a joke. Weather is
	 * everywhere at once and so tells nobody anything about where to look.
	 */
	private static boolean npcStudio$worthHearing(SoundSource source) {
		return source != SoundSource.MUSIC && source != SoundSource.RECORDS
			&& source != SoundSource.WEATHER && source != SoundSource.AMBIENT
			&& source != SoundSource.UI && source != SoundSource.VOICE;
	}

	@Inject(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;DDD"
		+ "Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V", at = @At("HEAD"))
	private void npcStudio$noiseSomewhere(Entity by, double x, double y, double z,
			Holder<SoundEvent> sound, SoundSource source, float volume, float pitch,
			long seed, CallbackInfo info) {
		if (!npcStudio$worthHearing(source)) return;
		ServerLevel self = (ServerLevel) (Object) this;
		Din.made(new Vec3(x, y, z), volume, self.getGameTime());
	}

	@Inject(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;"
		+ "Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;"
		+ "Lnet/minecraft/sounds/SoundSource;FFJ)V", at = @At("HEAD"))
	private void npcStudio$noiseFromSomebody(Entity by, Entity from,
			Holder<SoundEvent> sound, SoundSource source, float volume, float pitch,
			long seed, CallbackInfo info) {
		if (!npcStudio$worthHearing(source)) return;
		ServerLevel self = (ServerLevel) (Object) this;
		Din.made(from.position(), volume, self.getGameTime());
	}

	/**
	 * The effects that travel as an event rather than as a sound.
	 *
	 * A block finally giving way is the one that matters and it is the one that was
	 * asked about first: mining sends {@code 2001}, and the noise it makes is worked
	 * out on the client from the block that broke. Without this hook, breaking a
	 * wall to get into somewhere is completely silent to everybody in it — which is
	 * the opposite of what breaking a wall is.
	 *
	 * The volume is ours rather than the game's, because at this point there is not
	 * one: an event carries a block id and nothing else. Loud, because it is.
	 */
	@Inject(method = "levelEvent(Lnet/minecraft/world/entity/Entity;ILnet/minecraft/core/BlockPos;I)V",
		at = @At("HEAD"))
	private void npcStudio$somethingHappened(Entity by, int what, BlockPos where,
			int detail, CallbackInfo info) {
		if (what != BREAKING) return;
		ServerLevel self = (ServerLevel) (Object) this;
		Din.made(Vec3.atCenterOf(where), 1f, self.getGameTime());
	}

	/** The game's own number for "a block was destroyed here". */
	private static final int BREAKING = 2001;
}
