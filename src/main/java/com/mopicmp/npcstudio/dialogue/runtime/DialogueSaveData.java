package com.mopicmp.npcstudio.dialogue.runtime;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.dialogue.Value;
import com.mopicmp.npcstudio.dialogue.codec.DialogueCodecs;

import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Everything a conversation remembers, kept in the world save.
 *
 * Three kinds of state live here, and the reason they are three and not one is
 * that they have three different lifetimes. Putting them together was the first
 * attempt and it was wrong in a way that only shows up later:
 *
 * <table>
 *   <tr><th>what</th><th>keyed by</th><th>lives until</th></tr>
 *   <tr><td>where the player is</td><td>player + NPC</td><td>the conversation ends</td></tr>
 *   <tr><td>what the player has done</td><td>player</td><td>never — it is their progress</td></tr>
 *   <tr><td>what the world is like</td><td>nothing, it is shared</td><td>never</td></tr>
 * </table>
 *
 * If progress were stored inside the bookmark, finishing a conversation would
 * erase what it taught: the smith would forget he had met you at the exact
 * moment you met him. Player progress therefore outlives every bookmark, and
 * the bookmark holds only the position.
 *
 * The bookmark is keyed by player *and* NPC because twenty guards may share one
 * script, and being halfway through with one must not drop you into the middle
 * of it with another. Progress is keyed by player alone, because "I have met
 * the smith" is true no matter who asks.
 */
public class DialogueSaveData extends SavedData {

	/** Whose conversation with whom. */
	private record Bookmark(UUID player, UUID npc) {
		static final Codec<Bookmark> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			UUIDUtil.CODEC.fieldOf("player").forGetter(Bookmark::player),
			UUIDUtil.CODEC.fieldOf("npc").forGetter(Bookmark::npc)
		).apply(instance, Bookmark::new));
	}

	private record BookmarkEntry(Bookmark where, String node) {
		static final Codec<BookmarkEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Bookmark.CODEC.fieldOf("at").forGetter(BookmarkEntry::where),
			Codec.STRING.fieldOf("node").forGetter(BookmarkEntry::node)
		).apply(instance, BookmarkEntry::new));
	}

	/**
	 * One player's progress.
	 *
	 * `visited` is a flat list of node names. They are only compared against
	 * names from the same dialogue, and a nested structure would buy nothing but
	 * a harder file to read.
	 */
	private record Progress(UUID player, Map<String, Value> vars, List<String> visited) {
		static final Codec<Progress> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			UUIDUtil.CODEC.fieldOf("player").forGetter(Progress::player),
			Codec.unboundedMap(Codec.STRING, DialogueCodecs.VALUE)
				.optionalFieldOf("vars", Map.of()).forGetter(Progress::vars),
			Codec.STRING.listOf().optionalFieldOf("visited", List.of()).forGetter(Progress::visited)
		).apply(instance, Progress::new));
	}

	public static final Codec<DialogueSaveData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		// Lists of pairs rather than maps: the keys are UUIDs, and a JSON object
		// only has string keys, so a map would mean squashing them into text and
		// picking them apart again on the way back.
		BookmarkEntry.CODEC.listOf().optionalFieldOf("bookmarks", List.of())
			.forGetter(DialogueSaveData::bookmarkList),
		Progress.CODEC.listOf().optionalFieldOf("players", List.of())
			.forGetter(DialogueSaveData::progressList),
		Codec.unboundedMap(Codec.STRING, DialogueCodecs.VALUE)
			.optionalFieldOf("world_vars", Map.of()).forGetter(data -> data.worldVars),
		// What each place remembers. Last, so that every argument before it keeps the
		// position it had — and absent in every save written before a place could hold
		// a conversation, which is every save so far.
		Codec.unboundedMap(Codec.STRING,
				Codec.unboundedMap(Codec.STRING, DialogueCodecs.VALUE))
			.optionalFieldOf("zones", Map.of())
			.forGetter(data -> data.zoneVars)
	).apply(instance, DialogueSaveData::new));

	private static final SavedDataType<DialogueSaveData> TYPE = new SavedDataType<>(
		NpcStudio.id("dialogues"), DialogueSaveData::new, CODEC, DataFixTypes.LEVEL);

	private final Map<Bookmark, String> bookmarks = new HashMap<>();
	private final Map<UUID, Map<String, Value>> playerVars = new HashMap<>();
	private final Map<UUID, Set<String>> visited = new HashMap<>();
	private final Map<String, Value> worldVars = new HashMap<>();

	/**
	 * What each place remembers, where a character would remember it herself.
	 *
	 * A conversation a zone started has no character, and character-scoped variables
	 * have to live somewhere or a whole scope silently stops working in exactly the
	 * scenes most likely to use it — "have they been told this before" is the first
	 * thing anybody writes on a trigger.
	 *
	 * Keyed by the zone's own name rather than by its identifier, so the file is
	 * readable by the person whose map it is.
	 */
	private final Map<String, Map<String, Value>> zoneVars = new HashMap<>();

	/**
	 * What this place remembers, as a map that can be written into.
	 *
	 * Handed out live rather than copied, because the engine is given it, changes it,
	 * and hands it back — the same bargain a character's own memory strikes.
	 */
	public Map<String, Value> zoneVars(String zone) {
		return zoneVars.computeIfAbsent(zone, ignored -> new HashMap<>());
	}

	public DialogueSaveData() { }

	private DialogueSaveData(List<BookmarkEntry> bookmarks, List<Progress> players,
			Map<String, Value> worldVars, Map<String, Map<String, Value>> zones) {
		for (BookmarkEntry entry : bookmarks) this.bookmarks.put(entry.where(), entry.node());
		for (Progress progress : players) {
			playerVars.put(progress.player(), new HashMap<>(progress.vars()));
			visited.put(progress.player(), new HashSet<>(progress.visited()));
		}
		this.worldVars.putAll(worldVars);
		for (var zone : zones.entrySet()) {
			zoneVars.put(zone.getKey(), new HashMap<>(zone.getValue()));
		}
	}

	private List<BookmarkEntry> bookmarkList() {
		return bookmarks.entrySet().stream()
			.map(e -> new BookmarkEntry(e.getKey(), e.getValue())).toList();
	}

	private List<Progress> progressList() {
		Set<UUID> everyone = new HashSet<>(playerVars.keySet());
		everyone.addAll(visited.keySet());
		return everyone.stream()
			.map(id -> new Progress(id,
				playerVars.getOrDefault(id, Map.of()),
				List.copyOf(visited.getOrDefault(id, Set.of()))))
			.toList();
	}

	/**
	 * The store for a world.
	 *
	 * Always the overworld's, whichever dimension the conversation happens in: a
	 * quest flag that reset when the player stepped through a portal would be a
	 * strange kind of memory.
	 */
	public static DialogueSaveData of(ServerLevel level) {
		return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	public String bookmark(UUID player, UUID npc) {
		return bookmarks.get(new Bookmark(player, npc));
	}

	public void putBookmark(UUID player, UUID npc, String node) {
		bookmarks.put(new Bookmark(player, npc), node);
		setDirty();
	}

	public void clearBookmark(UUID player, UUID npc) {
		if (bookmarks.remove(new Bookmark(player, npc)) != null) setDirty();
	}

	/**
	 * Every bookmark one player holds, dropped.
	 *
	 * <h2>Why all of them and not this document's</h2>
	 *
	 * Because a bookmark is not filed under a document. It is filed under who was
	 * talking — a player and a character — and which script that character happens to
	 * be running is a fact about the character, in the world, possibly in a chunk
	 * nobody has loaded. So "this document's bookmarks" is a question the save cannot
	 * answer, and answering it by scanning whichever characters happen to be loaded
	 * would clear some of them and quietly leave the rest.
	 *
	 * Clearing all of them for the one person who pressed the button is both honest
	 * and, for what the button is for, right: somebody about to replay a scene from the
	 * top does not want to be standing in the middle of a different one either.
	 */
	public void clearBookmarks(UUID player) {
		if (bookmarks.keySet().removeIf(at -> at.player().equals(player))) setDirty();
	}

	public Map<String, Value> varsOf(UUID player) {
		return Map.copyOf(playerVars.getOrDefault(player, Map.of()));
	}

	public Set<String> visitedBy(UUID player) {
		return Set.copyOf(visited.getOrDefault(player, Set.of()));
	}

	public Map<String, Value> worldVars() {
		return Map.copyOf(worldVars);
	}

	/**
	 * Writes back everything one step of a conversation produced.
	 *
	 * Guarded by a comparison because every write marks the save dirty, and a
	 * dialogue that only reads would otherwise have the file rewritten on every
	 * single click.
	 */
	public void remember(UUID player, Map<String, Value> vars, Set<String> seen,
			Map<String, Value> world) {
		boolean changed = false;
		if (!vars.equals(playerVars.get(player))) {
			playerVars.put(player, new HashMap<>(vars));
			changed = true;
		}
		if (!seen.equals(visited.get(player))) {
			visited.put(player, new HashSet<>(seen));
			changed = true;
		}
		if (!world.equals(worldVars)) {
			worldVars.clear();
			worldVars.putAll(world);
			changed = true;
		}
		if (changed) setDirty();
	}

	/** A place has changed its mind about something it remembers. */
	public void rememberZone(String zone, Map<String, Value> vars) {
		Map<String, Value> was = zoneVars.get(zone);
		if (vars.equals(was)) return;
		zoneVars.put(zone, new HashMap<>(vars));
		setDirty();
	}
}
