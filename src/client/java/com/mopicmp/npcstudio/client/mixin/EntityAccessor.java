package com.mopicmp.npcstudio.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.world.entity.Entity;

/**
 * Reaches the shared flag that actually decides whether something glows.
 *
 * {@code setGlowingTag} looks like the way to light an entity up and is not,
 * on this side. It writes a field the server owns and then copies the answer
 * into the shared flag by asking {@code isCurrentlyGlowing}, which on a client
 * reads that same shared flag — so it sets the flag to the value the flag
 * already had, and nothing happens. Reading the bytecode was the only way to
 * find that; the method name says the opposite.
 *
 * The client's own copy of the flag is the right thing to write anyway. The
 * outline is a note about which character is being worked on, not something
 * about the character, and nobody else should see it.
 *
 * <h2>Nothing but the method</h2>
 *
 * No constants here, however tempting. An interface mixin may hold shadows and
 * accessors and nothing else — a field in an interface is implicitly a field,
 * and Mixin refuses the whole class for it at load time, which is to say the
 * game does not start. The compiler has no opinion on this, so the only place
 * it shows up is the log.
 */
@Mixin(Entity.class)
public interface EntityAccessor {

	@Invoker("setSharedFlag")
	void npcStudio$setSharedFlag(int flag, boolean value);
}
