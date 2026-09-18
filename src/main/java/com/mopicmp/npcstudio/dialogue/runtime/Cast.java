package com.mopicmp.npcstudio.dialogue.runtime;

import java.util.Map;
import java.util.UUID;

import com.mopicmp.npcstudio.dialogue.Route;
import com.mopicmp.npcstudio.dialogue.Value;
import com.mopicmp.npcstudio.entity.NpcEntity;

import net.minecraft.world.phys.Vec3;

/**
 * Who a conversation is with.
 *
 * <h2>Why this exists at all</h2>
 *
 * Because a conversation can now be with nobody. A place can start one — walk into
 * the room and the tutorial asks whether you are done — and there is no character
 * standing there to have said it.
 *
 * The runtime asks about a dozen questions of whoever it is talking to: where they
 * are, whether they are still alive, what they remember, which way they were placed
 * facing. Making the character nullable would put a null check on every one of them
 * and, sooner or later, miss one — and the miss would be a crash in the middle of
 * somebody's scene rather than an answer.
 *
 * So "nobody" is a thing that answers, rather than an absence that has to be
 * checked for. Every question below has a sensible answer for a conversation with
 * no character in it, and each of those answers is written down once, here, with
 * its reason.
 *
 * <h2>The one that matters most</h2>
 *
 * {@link #anchorAt} and {@link #facing}. A place written as steps hangs off the
 * character; a conversation with nobody has none, so its steps hang off the origin
 * facing south — which is exactly the same arithmetic as writing plain world
 * coordinates. That is not a fallback: it is what a zone's boxes and points are, and
 * the editor writes them that way for the same reason.
 */
public sealed interface Cast {

	/** The name this conversation is bookmarked under, beside the player's. */
	UUID id();

	/**
	 * The entity the client should draw beside the line, or −1 for none.
	 *
	 * The bar already knows what to do with −1: it is how a line with no speaker was
	 * always meant to be sent, and it was written before anything could send one.
	 */
	int shownAs();

	/** Whether this conversation still has somebody to be with. */
	boolean present();

	/** How far the player is from them, for the rules about wandering off. */
	double awayFrom(net.minecraft.world.entity.Entity who);

	/** The name of the order they are carrying out, or empty when standing still. */
	String walkingTo();

	/** What this side of the conversation remembers between visits. */
	Map<String, Value> memory();

	void rememberOnly(Map<String, Value> vars);

	/** Where steps are measured from. */
	Vec3 anchorAt();

	Route.Facing facing();

	/** The character, or null. Only for the verbs that are about a body. */
	NpcEntity body();

	// ------------------------------------------------------------ the two of them

	/** An ordinary conversation: somebody clicked a character. */
	record Character(NpcEntity npc) implements Cast {

		@Override public UUID id() {
			return npc.getUUID();
		}

		@Override public int shownAs() {
			return npc.getId();
		}

		@Override public boolean present() {
			return npc.isAlive();
		}

		@Override public double awayFrom(net.minecraft.world.entity.Entity who) {
			return Math.sqrt(npc.distanceToSqr(who));
		}

		@Override public String walkingTo() {
			return npc.walkingTo();
		}

		@Override public Map<String, Value> memory() {
			return npc.memory();
		}

		@Override public void rememberOnly(Map<String, Value> vars) {
			npc.rememberOnly(vars);
		}

		@Override public Vec3 anchorAt() {
			return npc.home();
		}

		@Override public Route.Facing facing() {
			return npc.homeFacing();
		}

		@Override public NpcEntity body() {
			return npc;
		}
	}

	/**
	 * A conversation a place started, with nobody in it.
	 *
	 * @param where the name of what began it — the document and the way in — so that
	 *              two zones of one document keep separate bookmarks and separate
	 *              memories, and so that the same zone is recognised as itself after
	 *              the world has been reloaded
	 */
	record Nobody(String where, Map<String, Value> remembered) implements Cast {

		/**
		 * The name a zone goes by where a character's own name would be.
		 *
		 * Worked out from the words rather than made up, so it is the same identifier
		 * on Tuesday as it was on Monday — the bookmark is written into the world's save
		 * and has to still mean this zone after a restart.
		 */
		public static UUID named(String where) {
			return UUID.nameUUIDFromBytes(("npc_studio:zone:" + where)
				.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}

		@Override public UUID id() {
			return named(where);
		}

		@Override public int shownAs() {
			// Nobody is speaking. The line still has a speaker's name if the author
			// wrote one — a place can say something in somebody's voice — but there is
			// no body on the ground to draw beside it.
			return -1;
		}

		@Override public boolean present() {
			// A place cannot die or wander off. What ends a zone conversation is the
			// graph ending it, or the player leaving, and both are asked elsewhere.
			return true;
		}

		@Override public double awayFrom(net.minecraft.world.entity.Entity who) {
			// Nought, which reads as "right here" to every rule about wandering off.
			// Those rules are about losing sight of somebody, and there is nobody to
			// lose sight of; what bounds a zone conversation is the box it started in.
			return 0;
		}

		@Override public String walkingTo() {
			return "";
		}

		@Override public Map<String, Value> memory() {
			return remembered;
		}

		@Override public void rememberOnly(Map<String, Value> vars) {
			remembered.clear();
			remembered.putAll(vars);
		}

		@Override public Vec3 anchorAt() {
			// The origin, which makes a step and a coordinate the same arithmetic. A
			// zone's boxes and points are written in world coordinates for exactly this
			// reason: there is nothing for them to hang off.
			return Vec3.ZERO;
		}

		@Override public Route.Facing facing() {
			return Route.Facing.SOUTH;
		}

		@Override public NpcEntity body() {
			return null;
		}
	}

	/**
	 * A stack of something somebody is holding.
	 *
	 * <h2>Why an item is a subject at all, having argued it is a cause</h2>
	 *
	 * Both, and the difference is what the document is for. "Flint and steel lights
	 * things" is a rule about the kind, and the subject there is the player. "This
	 * sword remembers how many it has killed" is about one stack, and there the stack
	 * is the subject — which is what makes {@code Scope.CHARACTER} mean something in a
	 * document about things.
	 *
	 * <h2>Why the hand and not the stack</h2>
	 *
	 * Because a stack is a value, not a place: split it, merge it, or use one up and
	 * the object in hand is a different object. Held by the hand, what is written back
	 * lands on whatever is actually there when the writing happens, which is the only
	 * answer that stays true across a step that waits.
	 */
	record Held(net.minecraft.server.level.ServerPlayer holder,
			net.minecraft.world.InteractionHand hand) implements Cast {

		/**
		 * The name a hand goes by where a character's own name would be.
		 *
		 * By the hand rather than by the stack, so a bookmark still finds it after the
		 * stack has been used up or swapped — and so the two hands are two subjects,
		 * which is what somebody holding two of these would expect.
		 */
		@Override public UUID id() {
			return UUID.nameUUIDFromBytes(("npc_studio:hand:" + hand.name())
				.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}

		public net.minecraft.world.item.ItemStack stack() {
			return holder.getItemInHand(hand);
		}

		@Override public int shownAs() {
			// Nothing is speaking. A rule about an item may still say a line in
			// somebody's voice, but there is no body on the ground to draw beside it.
			return -1;
		}

		@Override public boolean present() {
			return !stack().isEmpty();
		}

		@Override public double awayFrom(net.minecraft.world.entity.Entity who) {
			// In their hand, which is as close as anything gets.
			return 0;
		}

		@Override public String walkingTo() {
			return "";
		}

		@Override public Map<String, Value> memory() {
			return Kept.of(stack());
		}

		@Override public void rememberOnly(Map<String, Value> vars) {
			// Onto whatever is in that hand now. And writing anything at all is what
			// stops it stacking — see Kept, and the reason that is a rule rather than a
			// restriction.
			Kept.remember(stack(), vars);
		}

		@Override public Vec3 anchorAt() {
			return holder.position();
		}

		@Override public Route.Facing facing() {
			return Route.Facing.SOUTH;
		}

		@Override public NpcEntity body() {
			return null;
		}
	}
}
