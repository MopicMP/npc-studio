package com.mopicmp.npcstudio.dialogue.runtime;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import com.mopicmp.npcstudio.entity.NpcEntity;
import com.mopicmp.npcstudio.net.CloseDialoguePayload;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Who currently has a line on screen, and when to take it away.
 *
 * This is not the same thing as the bookmark, and conflating them was the bug:
 * the bar was only ever closed when a conversation *ended*, so walking away,
 * logging out or simply losing interest left it sitting there forever.
 *
 * The bookmark is where the player got to and survives all of that on purpose.
 * The display is a much weaker thing — it lasts only while the player is stood
 * in front of the NPC — and the two need separate lifetimes.
 *
 * Kept in memory rather than saved, because it describes a screen rather than
 * a world: after a restart nobody is looking at anything.
 */
public final class DialogueDisplay {

	/** Checked four times a second: often enough to feel immediate, rarely enough to be free. */
	private static final int EVERY_TICKS = 5;

	/*
	 * There were two constants here — twelve blocks of wandering and ten seconds of
	 * silence — and both were opinions dressed as facts. They still exist, to the
	 * number, as the ordinary values in {@link com.mopicmp.npcstudio.dialogue.Manner},
	 * so nothing anybody has already built changes. What has changed is who holds
	 * them: the document, which is the only thing that knows whether this is a
	 * shopkeeper's greeting or the last line of a quest.
	 */

	/**
	 * What is on somebody's screen, and the terms it is on.
	 *
	 * The terms are copied in rather than looked up when they are needed, and that is
	 * deliberate: the line already left, and it should go away on the terms it arrived
	 * under. Fetching them again would let somebody editing the document in another
	 * window pull a line off a player's screen halfway through reading it.
	 */
	private record Shown(int npcId, long since, boolean fullscreen,
			com.mopicmp.npcstudio.dialogue.Manner showing, int lasts, String zone) { }

	private static final Map<UUID, Shown> SHOWING = new HashMap<>();

	private DialogueDisplay() { }

	public static void showing(ServerPlayer player, int npcId, boolean fullscreen,
			com.mopicmp.npcstudio.dialogue.Manner showing) {
		showing(player, npcId, fullscreen, showing,
			com.mopicmp.npcstudio.dialogue.Node.Line.USES_DOCUMENT, "");
	}

	public static void showing(ServerPlayer player, int npcId, boolean fullscreen,
			com.mopicmp.npcstudio.dialogue.Manner showing, String zone) {
		showing(player, npcId, fullscreen, showing,
			com.mopicmp.npcstudio.dialogue.Node.Line.USES_DOCUMENT, zone);
	}

	/**
	 * The same, told what this one line asked for.
	 *
	 * @param lasts ticks, or nought to leave it to the document. A line may want its
	 *              own answer because the last line before something happens is a
	 *              different kind of line: it is not waiting to be read and clicked
	 *              past, it is waiting for the scene to move.
	 */
	public static void showing(ServerPlayer player, int npcId, boolean fullscreen,
			com.mopicmp.npcstudio.dialogue.Manner showing, int lasts) {
		showing(player, npcId, fullscreen, showing, lasts, "");
	}

	/**
	 * The same, told which place is speaking, when a place is.
	 *
	 * <h2>Why the screen has to know this</h2>
	 *
	 * Because a line is moved on by pointing at whoever said it, and a place cannot
	 * be pointed at. So the gesture has to be "carry on with whatever is being said
	 * to me", and the only thing that knows what that is, is the screen it is being
	 * said on. Without the name here the server would receive "carry on" and have
	 * nothing to carry on with — which is precisely the state a zone conversation
	 * got stuck in: a line on screen, no character to click, and nothing anywhere
	 * able to take it further.
	 *
	 * @param zone the document and the way in, or empty when a character is speaking
	 */
	public static void showing(ServerPlayer player, int npcId, boolean fullscreen,
			com.mopicmp.npcstudio.dialogue.Manner showing, int lasts, String zone) {
		SHOWING.put(player.getUUID(), new Shown(npcId, System.currentTimeMillis(),
			fullscreen, showing == null ? com.mopicmp.npcstudio.dialogue.Manner.ORDINARY : showing,
			Math.max(0, lasts), zone == null ? "" : zone));
	}

	/** Which place is speaking to this player, or empty when it is a character or nobody. */
	public static String zoneOn(ServerPlayer player) {
		Shown shown = SHOWING.get(player.getUUID());
		return shown == null ? "" : shown.zone();
	}

	/**
	 * Whether this player is looking at this NPC's line right now.
	 *
	 * This is what decides between carrying on and starting the line again. If
	 * the bar has gone — timed out, walked away from — the player has lost the
	 * thread, and skipping straight to the next line would answer a question they
	 * can no longer see. So the same line is spoken again, and only a click while
	 * it is still up moves the conversation on.
	 */
	/**
	 * Whether this player has anything on screen at all.
	 *
	 * Asked by a place before it speaks. A trigger waits for the bar rather than
	 * taking it, so that walking past a doorway can never cut a character off in the
	 * middle of a sentence — and waiting costs nothing, because the player is standing
	 * in the box and will be asked again a quarter of a second later.
	 */
	public static boolean isShowing(ServerPlayer player) {
		return SHOWING.containsKey(player.getUUID());
	}

	public static boolean isShowing(ServerPlayer player, NpcEntity npc) {
		Shown shown = SHOWING.get(player.getUUID());
		return shown != null && shown.npcId() == npc.getId();
	}

	public static void hide(ServerPlayer player) {
		if (SHOWING.remove(player.getUUID()) != null) {
			ServerPlayNetworking.send(player, new CloseDialoguePayload());
		}
	}

	/** A player who left takes their conversation off screen with them. */
	public static void forget(ServerPlayer player) {
		SHOWING.remove(player.getUUID());
	}

	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % EVERY_TICKS != 0 || SHOWING.isEmpty()) return;

		long now = System.currentTimeMillis();
		Iterator<Map.Entry<UUID, Shown>> entries = SHOWING.entrySet().iterator();
		while (entries.hasNext()) {
			Map.Entry<UUID, Shown> entry = entries.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) {
				entries.remove();
				continue;
			}
			if (!stillWatching(player, entry.getValue(), now)) {
				entries.remove();
				ServerPlayNetworking.send(player, new CloseDialoguePayload());
			}
		}
	}

	/**
	 * Whether the line should still be up.
	 *
	 * Four ways it should not be: the NPC is gone, it is dead, the player has
	 * walked off, or nobody has touched it for a while. The bookmark is untouched
	 * in every case — coming back and clicking picks the conversation up where it
	 * was left, repeating the line rather than skipping it.
	 */
	private static boolean stillWatching(ServerPlayer player, Shown shown, long now) {
		var terms = shown.showing();
		// The idle timer is for a line the player can walk away from. A full-screen
		// conversation has their whole attention and no way to click the NPC, so
		// timing it out would tear the screen away from someone still reading.
		//
		// A document may also say the line waits for ever, which is what a scene
		// wants when the next thing that happens is the player answering. Expressed
		// as nought rather than as an enormous number, so it is a decision in the
		// file rather than a bet on nobody reading slowly.
		// What the line itself asked for wins, and it wins even over "never" — a line
		// that named a length named it on purpose, and the document's answer is the one
		// for lines that said nothing.
		//
		// It applies in every mode, unlike the document's, because a line that asked to
		// be taken away after four seconds means it whether it is a bar or a screen.
		if (shown.lasts() > 0) {
			if (now - shown.since() > shown.lasts() * 50L) return false;
		} else if (!shown.fullscreen() && !terms.staysUp()
				&& now - shown.since() > terms.idleTicks() * 50L) {
			return false;
		}
		// A line a place is saying has no body to lose, so the questions about one are
		// not asked of it. Minus one is how "nobody is speaking" has always been sent.
		if (shown.npcId() < 0) return true;
		var entity = player.level().getEntity(shown.npcId());
		if (!(entity instanceof NpcEntity npc) || !npc.isAlive()) return false;
		// And the same for walking away: a document may say the line does not care.
		if (terms.survivesDistance()) return true;
		return npc.distanceToSqr(player) <= terms.range() * terms.range();
	}
}
