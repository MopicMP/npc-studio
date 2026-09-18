package com.mopicmp.npcstudio.net;

import java.util.List;

import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Talking to the dialogue editor.
 *
 * A dialogue travels as its own JSON rather than as a bespoke packet layout.
 * The codec already exists, it is already tested both ways, and a second
 * description of the same thing would be a second thing to keep in step — the
 * kind of duplication that stays correct right up until someone adds a field.
 */
public final class EditorPayloads {

	private EditorPayloads() { }

	/**
	 * Client asks what dialogues there are.
	 *
	 * <h2>Why it has to say why it is asking</h2>
	 *
	 * Because two things want this answer for opposite reasons. A dropdown wants the
	 * names to fill itself in and must stay where it is; the dialogue list wants them
	 * because somebody asked to see the list. There was no difference in the packet,
	 * so the client took every answer as "show me" — and the character panel asks for
	 * the names the moment a character is opened, which is the moment the workspace
	 * appears. The dialogue window therefore opened over the world every single time
	 * anybody opened the menu, closed or not, and being a panel that takes the whole
	 * window it covered the buttons as well.
	 *
	 * A flag rather than two packets, because it is one question with one answer and
	 * the difference is only what the asker means to do with it.
	 *
	 * @param toShow true when somebody asked to see the list, false to fill a menu in
	 */
	public record Browse(boolean toShow) implements CustomPacketPayload {
		public static final Type<Browse> TYPE = new Type<>(NpcStudio.id("editor_browse"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Browse> CODEC =
			ByteBufCodecs.BOOL.map(Browse::new, Browse::toShow);

		/** For a caller that only wants the names, which is the quiet half of them. */
		public static Browse quietly() {
			return new Browse(false);
		}

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * Server answers with the names on offer, and with what was asked of it.
	 *
	 * The question comes back with the answer rather than being remembered on the
	 * client. Remembering would be a flag set when a packet leaves and read when one
	 * arrives, and two askers can have packets in flight at once — which is a race
	 * over whether a window opens.
	 */
	public record Listing(List<Known> graphs, boolean toShow) implements CustomPacketPayload {
		public static final Type<Listing> TYPE = new Type<>(NpcStudio.id("editor_listing"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Listing> CODEC =
			StreamCodec.composite(
				Known.CODEC.apply(ByteBufCodecs.list(512)), Listing::graphs,
				ByteBufCodecs.BOOL, Listing::toShow,
				Listing::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

		/** Just the names, for the places that only ever wanted those. */
		public List<String> names() {
			return graphs.stream().map(Known::name).toList();
		}
	}

	/**
	 * A graph, and what it can be used as.
	 *
	 * <h2>Why the sort travels with the name</h2>
	 *
	 * Because the client has to offer a list to choose from, and offering the
	 * wrong entries is how the choosing goes wrong. Only the server knows what is
	 * in a graph, so only the server can say whether it is a conversation, a
	 * brain, or capable of either — and a name on its own has already proved not
	 * to be enough.
	 *
	 * @param kind what the document is for, so the list can group by it rather than
	 *             leaving forty sets of rules mixed in among the conversations. Sent
	 *             as the name of the value: the client holds the same enum, and a
	 *             number would be one more thing to keep in step for no gain.
	 */
	public record Known(String name, boolean speaks, boolean waits, List<String> skills,
			String kind) {
		public static final StreamCodec<io.netty.buffer.ByteBuf, Known> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, Known::name,
				ByteBufCodecs.BOOL, Known::speaks,
				ByteBufCodecs.BOOL, Known::waits,
				// What this document can be called into. Sent because the editor has
				// to be able to offer them: a skill named by typing is a skill misspelt,
				// and a misspelt call is a character standing perfectly still.
				ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(64)), Known::skills,
				ByteBufCodecs.STRING_UTF8, Known::kind,
				Known::new);

		/**
		 * Whether this is something a character can be given.
		 *
		 * There used to be two of these — one for the conversation field and one for
		 * the brain field — because a character carried two documents and putting the
		 * wrong kind in either left her standing silently with nothing saying why.
		 *
		 * A character carries one document now, so the question is simply whether
		 * this document is one that can be carried. Everything with a beginning is;
		 * a library of skills has no beginning, which is exactly what makes it a
		 * library rather than a character's own graph.
		 *
		 * Both flags are kept because both are still worth knowing about a graph —
		 * an editor showing whether something talks is showing something true — and
		 * because dropping a field from a packet to save four bits is how the next
		 * thing gets lost.
		 */
		public boolean fitsOnACharacter() {
			// A set of rules is carried by nobody: it has no beginning, so a character
			// given one would stand there doing nothing when clicked. Kept out of the
			// list rather than refused after the fact, because a list that offers what
			// cannot be chosen is a list that teaches people to distrust it.
			return "scene".equals(kind);
		}
	}

	/** Client asks to edit something, by name. Empty name means "start a new one". */
	/**
	 * "Forget that I have played this one", so that it can be played again.
	 *
	 * <h2>Why this is not the same thing as the forget node</h2>
	 *
	 * The node is part of the story: the errand ends, and the errand can be taken up
	 * again. This is not part of anything — it is the author wanting to watch the scene
	 * from the top for the fourth time this afternoon, and writing that into the graph
	 * would mean writing it back out before anybody else played it.
	 *
	 * It touches only the person who asked. Not because anything technical requires it,
	 * but because a button in an editor that quietly reset a map's progress for
	 * everybody on it would be the worst button in this mod.
	 */
	public record Replay(String id) implements CustomPacketPayload {
		public static final Type<Replay> TYPE = new Type<>(NpcStudio.id("editor_replay"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Replay> CODEC =
			ByteBufCodecs.STRING_UTF8.map(Replay::new, Replay::id);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * Open a document, or make one.
	 *
	 * <h2>Why a new one has to say what sort it is</h2>
	 *
	 * Because the window lists the four sorts in four tabs, and the tab somebody is
	 * looking at is the only thing that says what they meant to make. Without it the
	 * server would make a conversation every time and the new document would appear in
	 * a tab other than the one whose "new" was pressed — which reads as the button not
	 * working.
	 *
	 * Ignored when {@code id} names something: an existing document already knows what
	 * it is, and letting a packet say otherwise would be a way to change any document's
	 * sort by opening it.
	 *
	 * @param id   the document to open, or empty to make one
	 * @param kind what sort to make, as the file writes it: scene, player, thing, location
	 */
	public record Open(String id, String kind) implements CustomPacketPayload {
		public static final Type<Open> TYPE = new Type<>(NpcStudio.id("editor_open"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Open> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, Open::id,
				ByteBufCodecs.STRING_UTF8, Open::kind,
				Open::new);

		/** Opening something that exists, where the sort is not ours to say. */
		public Open(String id) {
			this(id, "scene");
		}

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * Server sends the dialogue over, with the names of the others alongside.
	 *
	 * The list of names comes too because the editor needs it for the "go to"
	 * fields: a transition has to point at a node, and offering a free-text box
	 * for something that must match exactly is a way of manufacturing typos.
	 */
	public record Editing(String json, List<String> names) implements CustomPacketPayload {
		public static final Type<Editing> TYPE = new Type<>(NpcStudio.id("editor_editing"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Editing> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, Editing::json,
				ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(512)), Editing::names,
				Editing::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Client sends the edited dialogue back. */
	/**
	 * Client asks for a dialogue to be called something else.
	 *
	 * <h2>Why this is not "save it under the new name"</h2>
	 *
	 * Because that leaves the old one. A dialogue is stored under its own id, so
	 * changing the id in the editor and pressing save writes a second document and
	 * keeps the first — which is a copy, not a rename, and the copy is the one every
	 * character in the world is still pointing at.
	 *
	 * Both names travel, and the server does the two halves together. Sending only
	 * the new one would mean the server had to guess which document was being
	 * renamed, and the answer would be "whichever this player last opened", which is
	 * a guess about somebody's window.
	 */
	public record Rename(String from, String to) implements CustomPacketPayload {
		public static final Type<Rename> TYPE = new Type<>(NpcStudio.id("editor_rename"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Rename> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, Rename::from,
				ByteBufCodecs.STRING_UTF8, Rename::to,
				Rename::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record Save(String json) implements CustomPacketPayload {
		public static final Type<Save> TYPE = new Type<>(NpcStudio.id("editor_save"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Save> CODEC =
			ByteBufCodecs.STRING_UTF8.map(Save::new, Save::json);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * How the save went.
	 *
	 * Failure carries the validator's own words. "Could not save" would leave
	 * someone guessing at a graph they have been staring at for an hour; "menu
	 * leads to repair, which does not exist" tells them where to click.
	 */
	public record Saved(boolean ok, String message) implements CustomPacketPayload {
		public static final Type<Saved> TYPE = new Type<>(NpcStudio.id("editor_saved"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Saved> CODEC =
			StreamCodec.composite(
				ByteBufCodecs.BOOL, Saved::ok,
				ByteBufCodecs.STRING_UTF8, Saved::message,
				Saved::new);

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}
}
