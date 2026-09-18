package com.mopicmp.npcstudio.map;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * What a map asks of a player who has just arrived.
 *
 * <h2>What is not in here</h2>
 *
 * The spawn point. It lives in the world's own {@code LevelData.RespawnData},
 * where the game put it, and keeping a second copy would mean two places that
 * disagree the first time somebody runs {@code /setworldspawn}. The panel writes
 * to the vanilla one; a map opened without this mod still starts in the right
 * place.
 *
 * The resource pack, for the same shape of reason: a pack dropped in the world
 * folder as {@code resources.zip} is applied by the game itself, and a setting
 * of ours saying so would be a second switch for something that already has one.
 *
 * So what is left here is exactly what vanilla has nowhere to put: the client's
 * own settings, which no server can reach.
 */
public record MapStart(List<Entry> settings, String shaderPack, String playerSkin) {

	public static final MapStart NOTHING = new MapStart(List.of(), "", "");

	/** The two-part form, from before a map could dress the person playing it. */
	public MapStart(List<Entry> settings, String shaderPack) {
		this(settings, shaderPack, "");
	}

	/**
	 * One setting, its number, and the two things that say what the number means.
	 *
	 * @param what     which client setting
	 * @param value    the number, kept inside the game's own range
	 * @param firmness how hard the map insists
	 * @param bound    whether the number is the value, a floor or a ceiling
	 */
	public record Entry(Setting what, double value, Firmness firmness, Bound bound) {

		public static final Codec<Entry> CODEC = RecordCodecBuilder.create(it -> it.group(
			Setting.CODEC.fieldOf("setting").forGetter(Entry::what),
			Codec.DOUBLE.fieldOf("value").forGetter(Entry::value),
			Firmness.CODEC.fieldOf("firmness").forGetter(Entry::firmness),
			Bound.CODEC.fieldOf("bound").forGetter(Entry::bound)
		).apply(it, Entry::new));

		public Entry {
			value = what.sane(value);
		}

		public static Entry starting(Setting what) {
			return new Entry(what, what.usual(), Firmness.SUGGESTED, Bound.EXACT);
		}
	}

	public static final Codec<MapStart> CODEC = RecordCodecBuilder.create(it -> it.group(
		Entry.CODEC.listOf().optionalFieldOf("settings", List.of()).forGetter(MapStart::settings),
		Codec.STRING.optionalFieldOf("shader_pack", "").forGetter(MapStart::shaderPack),
		// Absent on every map made before this existed, and absent has to go on
		// meaning "the player wears their own skin" for ever.
		Codec.STRING.optionalFieldOf("player_skin", "").forGetter(MapStart::playerSkin)
	).apply(it, MapStart::new));

	public MapStart {
		settings = List.copyOf(settings);
		shaderPack = shaderPack == null ? "" : shaderPack;
		playerSkin = playerSkin == null ? "" : playerSkin;
	}

	/**
	 * The skin everybody playing this map wears, whatever they arrived in.
	 *
	 * <h2>Why a fingerprint and not the name of a costume</h2>
	 *
	 * Because the client has to draw it, and a costume's name means nothing without
	 * the library — which is sent only to somebody who has opened the wardrobe. A
	 * fingerprint is the picture, and the picture can be asked for by anybody: it is
	 * exactly the bargain a character's own skin already strikes, where the mark is
	 * synched and the bytes are fetched once. One convention for both.
	 *
	 * <h2>Why it lives here and not on a dialogue</h2>
	 *
	 * Because it is true all the time, which is how it was asked for. A dialogue is a
	 * scene; this is the map. Hung on a document it would mean "while this graph is
	 * loaded", which is not a thing anybody can see or reason about — and two
	 * documents disagreeing would be settled by load order.
	 */
	public MapStart withPlayerSkin(String fingerprint) {
		return new MapStart(settings, shaderPack, fingerprint == null ? "" : fingerprint);
	}

	public Entry entry(Setting what) {
		for (Entry entry : settings) {
			if (entry.what() == what) return entry;
		}
		return null;
	}

	/**
	 * The same map with one setting replaced, or added if it was not there.
	 *
	 * One entry per setting is the invariant, and it is kept here rather than
	 * checked later: two entries for brightness would both be applied, in list
	 * order, and the map would mean whichever happened to be last.
	 */
	public MapStart with(Entry entry) {
		List<Entry> next = new ArrayList<>();
		boolean replaced = false;
		for (Entry had : settings) {
			if (had.what() == entry.what()) {
				next.add(entry);
				replaced = true;
			} else {
				next.add(had);
			}
		}
		if (!replaced) next.add(entry);
		return new MapStart(next, shaderPack, playerSkin);
	}

	public MapStart without(Setting what) {
		return new MapStart(settings.stream().filter(it -> it.what() != what).toList(),
			shaderPack, playerSkin);
	}

	public MapStart withShader(String pack) {
		return new MapStart(settings, pack, playerSkin);
	}

	/** Whether a player arriving is going to be asked anything at all. */
	public boolean suggestsAnything() {
		return settings.stream().anyMatch(it -> it.firmness() == Firmness.SUGGESTED);
	}

	/**
	 * Whether there is anything to put a window in front of somebody for.
	 *
	 * A shader counts on its own. It is the one thing here that is never applied
	 * without being asked — it costs more frames than everything else together —
	 * so a map that recommends only a shader still has something to say, and
	 * checking the settings alone would leave it saying nothing.
	 */
	public boolean worthAsking() {
		return suggestsAnything() || !shaderPack.isEmpty();
	}
}
