package com.mopicmp.npcstudio.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The settings a flat world is made of.
 *
 * A flat world is not one thing. {@code level-type=minecraft:flat} says only
 * "flat"; what it is flat <em>of</em> — bedrock and grass, a hundred and sixteen
 * of sandstone, one layer of air — is a second setting, {@code generator-settings},
 * and without it every flat world anybody makes is the same three layers. That is
 * why the templates from the game's own creation screen are here: they exist in
 * the client as code, not as files, so a server manager that wants to offer them
 * has to carry them.
 *
 * <p>The layers below were read out of the game's own {@code
 * FlatLevelGeneratorPresets} rather than remembered, because a preset misspelt is
 * a world somebody generates, walks around in and has to throw away. They are
 * listed top downwards, as the game lists them, and written out bottom upwards,
 * as the file wants them — a difference worth one method and one test rather than
 * an afternoon of wondering why the bedrock is in the sky.
 *
 * <p>The old {@code 3;minecraft:bedrock,2*minecraft:dirt} notation the server took
 * years ago is gone from the file format and has not gone from anybody's notes, so
 * what somebody types is read in that notation and written as JSON.
 */
public final class Flat {

	private Flat() {
	}

	/** One layer: how many blocks thick, and of what. */
	public record Layer(int height, String block) {
	}

	/** A named set of layers, as the game's own creation screen offers them. */
	public record Preset(String id, String biome, boolean features, boolean lakes,
			List<Layer> topDown) {
	}

	/**
	 * The templates the game ships with.
	 *
	 * Bottomless pit has no bedrock — that is what makes it bottomless, not an
	 * omission — and the void is one layer of air, which is the one people
	 * building things actually want.
	 */
	public static final List<Preset> PRESETS = List.of(
		new Preset("classic_flat", "minecraft:plains", false, false, List.of(
			new Layer(1, "minecraft:grass_block"),
			new Layer(2, "minecraft:dirt"),
			new Layer(1, "minecraft:bedrock"))),
		new Preset("tunnelers_dream", "minecraft:windswept_hills", true, false, List.of(
			new Layer(1, "minecraft:grass_block"),
			new Layer(5, "minecraft:dirt"),
			new Layer(230, "minecraft:stone"),
			new Layer(1, "minecraft:bedrock"))),
		new Preset("water_world", "minecraft:deep_ocean", false, false, List.of(
			new Layer(90, "minecraft:water"),
			new Layer(5, "minecraft:gravel"),
			new Layer(5, "minecraft:dirt"),
			new Layer(5, "minecraft:stone"),
			new Layer(64, "minecraft:deepslate"),
			new Layer(1, "minecraft:bedrock"))),
		new Preset("overworld", "minecraft:plains", true, true, List.of(
			new Layer(1, "minecraft:grass_block"),
			new Layer(3, "minecraft:dirt"),
			new Layer(59, "minecraft:stone"),
			new Layer(1, "minecraft:bedrock"))),
		new Preset("snowy_kingdom", "minecraft:snowy_plains", false, false, List.of(
			new Layer(1, "minecraft:snow"),
			new Layer(1, "minecraft:grass_block"),
			new Layer(3, "minecraft:dirt"),
			new Layer(59, "minecraft:stone"),
			new Layer(1, "minecraft:bedrock"))),
		new Preset("bottomless_pit", "minecraft:plains", false, false, List.of(
			new Layer(1, "minecraft:grass_block"),
			new Layer(3, "minecraft:dirt"),
			new Layer(2, "minecraft:cobblestone"))),
		new Preset("desert", "minecraft:desert", true, false, List.of(
			new Layer(8, "minecraft:sand"),
			new Layer(52, "minecraft:sandstone"),
			new Layer(3, "minecraft:stone"),
			new Layer(1, "minecraft:bedrock"))),
		new Preset("redstone_ready", "minecraft:desert", false, false, List.of(
			new Layer(116, "minecraft:sandstone"),
			new Layer(3, "minecraft:stone"),
			new Layer(1, "minecraft:bedrock"))),
		new Preset("the_void", "minecraft:the_void", true, false, List.of(
			new Layer(1, "minecraft:air"))));

	public static Preset preset(String id) {
		for (Preset each : PRESETS) {
			if (each.id().equals(id)) return each;
		}
		return PRESETS.getFirst();
	}

	/** The value {@code generator-settings} takes for this template. */
	public static String settings(Preset preset) {
		return json(preset.topDown(), preset.biome(), preset.features(), preset.lakes());
	}

	/**
	 * The same from what somebody typed, in the notation everybody still writes.
	 *
	 * {@code minecraft:bedrock,2*minecraft:dirt,minecraft:grass_block} — bottom
	 * upwards, a count and a star before a block that repeats. A namespace may be
	 * left off and is filled in as {@code minecraft}, because nobody types it.
	 */
	public static String fromWritten(String written, String biome) {
		List<Layer> bottomUp = new ArrayList<>();
		for (String piece : written.split(",")) {
			String each = piece.trim();
			if (each.isEmpty()) continue;
			int height = 1;
			int star = each.indexOf('*');
			if (star > 0) {
				height = number(each.substring(0, star));
				each = each.substring(star + 1).trim();
			}
			if (height <= 0) throw new IllegalArgumentException("A layer of no blocks: " + piece);
			if (height > MOST_LAYERS) {
				throw new IllegalArgumentException("A layer taller than the world: " + piece);
			}
			String block = each.toLowerCase(Locale.ROOT);
			if (!block.contains(":")) block = "minecraft:" + block;
			if (!block.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
				throw new IllegalArgumentException("Not the name of a block: " + piece);
			}
			bottomUp.add(new Layer(height, block));
		}
		if (bottomUp.isEmpty()) throw new IllegalArgumentException("No layers at all");

		List<Layer> topDown = new ArrayList<>(bottomUp);
		java.util.Collections.reverse(topDown);
		return json(topDown, biome == null || biome.isBlank() ? "minecraft:plains" : biome,
			false, false);
	}

	/** Taller than any world is, so a typed number cannot become an hour of generating. */
	private static final int MOST_LAYERS = 4064;

	private static int number(String text) {
		try {
			return Integer.parseInt(text.trim());
		} catch (NumberFormatException notANumber) {
			throw new IllegalArgumentException("Not a count: " + text);
		}
	}

	/**
	 * The file's own form, which lists layers from the bottom up.
	 *
	 * Written by hand rather than through a library because it is four keys and
	 * because this string ends up in {@code server.properties}, where a newline
	 * would end the line and lose the rest of it.
	 */
	private static String json(List<Layer> topDown, String biome, boolean features,
			boolean lakes) {
		Map<String, String> ordered = new LinkedHashMap<>();
		StringBuilder layers = new StringBuilder("[");
		for (int at = topDown.size() - 1; at >= 0; at--) {
			Layer each = topDown.get(at);
			if (layers.length() > 1) layers.append(',');
			layers.append("{\"block\":\"").append(each.block()).append("\",\"height\":")
				.append(each.height()).append('}');
		}
		layers.append(']');
		ordered.put("layers", layers.toString());
		ordered.put("biome", "\"" + biome + "\"");
		ordered.put("features", String.valueOf(features));
		ordered.put("lakes", String.valueOf(lakes));

		StringBuilder out = new StringBuilder("{");
		for (var entry : ordered.entrySet()) {
			if (out.length() > 1) out.append(',');
			out.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
		}
		return out.append('}').toString();
	}
}
