package com.mopicmp.npcstudio.show;

import java.util.ArrayList;
import java.util.List;

import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.dialogue.Shown;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Putting a {@link Shown} into the world, changing it, and taking it away.
 *
 * <h2>Why everything goes through save data</h2>
 *
 * Because a display has no public setters at all — every field of its own is private in
 * 26.2 — and the way the game builds one is to load it. So this writes the tag the game
 * would have written and hands it to {@link Entity#load}, which is the same door the
 * world's own save file comes in through.
 *
 * It is also why nothing here is clever. The one thing this file knows that nothing else
 * does is the game's spelling of a display's fields, and the whole of its job is to be the
 * only place that knows it.
 *
 * <h2>The position goes in the tag, and that is not a detail</h2>
 *
 * {@link Entity#load} reads {@code Pos} and, finding none, puts the entity at the origin of
 * the world. An earlier version of this stood the entity where it belonged and then loaded
 * it, and every hologram anybody made went silently to (0, 0, 0) — the window that made
 * them listed nothing nearby ever again, and the fault read as a dead button.
 *
 * So there is one door and everything goes through it. Placing an entity and then loading
 * over the top of it is two ideas about where it is.
 *
 * <h2>How a name finds its thing</h2>
 *
 * By a scoreboard tag, scanned for. Not by a register of names to entities, for the reason
 * this project keeps coming back to: the world is where somebody deletes one with
 * {@code /kill}, and a register would go on insisting it is there.
 *
 * A tag also survives being copied into a saved location without being asked, which is the
 * whole point of a lesson: the room is captured with its holograms in it, and the copy's
 * names still answer.
 *
 * The scan is over the loaded displays of one world. That is a few dozen entities on a map
 * with holograms on it and none at all on a map without, and it happens when a graph shows
 * or hides something rather than on a beat.
 */
public final class Showing {

	private Showing() { }

	/** Marks a display as ours, so a scan can find them without reading every tag. */
	public static final String OURS = "npc_studio:shown";

	/** Carries what a thing is called: this tag, then the place, then the name. */
	public static final String CALLED = "npc_studio:called=";

	/**
	 * The full handle: a name means one thing inside one place.
	 *
	 * Which is what keeps two people in two copies of the same lesson apart. Both run the
	 * same graph, both show {@code menu.play}, and neither can see or change the other's —
	 * the copies are the scope, and the scope is written into the tag.
	 */
	public static String handle(String place, String name) {
		return CALLED + (place == null ? "" : place) + "/" + (name == null ? "" : name);
	}

	// ------------------------------------------------------------------ putting one up

	/**
	 * Stands a thing at a point, or changes the one already standing there by that name.
	 *
	 * The same call for both, because a graph re-entered has to be allowed to say the same
	 * thing again — see {@link com.mopicmp.npcstudio.dialogue.Effect.Show}. A change of
	 * kind is a new entity, since a text display cannot become a block one; the old one is
	 * taken away first so the place does not end up with both.
	 *
	 * @param says the words with their variables already filled in — this does not know
	 *             about variables, and does not know whose they were
	 * @return the display standing there, or null when the game would not make one
	 */
	public static Display put(ServerLevel level, String place, String name, Shown what,
			com.mopicmp.npcstudio.dialogue.text.Words says, Vec3 where) {
		EntityType<? extends Display> type = typeOf(what.kind());
		Display standing = find(level, place, name);
		if (standing != null && standing.getType() != type) {
			standing.discard();
			standing = null;
		}

		if (standing == null) {
			Display made = type.create(level, EntitySpawnReason.COMMAND);
			if (made == null) return null;
			made.load(read(level, tag(level, what, says, where)));
			made.addTag(OURS);
			made.addTag(handle(place, name));
			level.addFreshEntity(made);
			return made;
		}

		// Already there: loaded over, which moves it as well. Deliberate — the node says
		// where, and a thing that stayed behind when its node was edited would be a
		// hologram nobody can get rid of by editing the graph.
		standing.load(read(level, tag(level, what, says, where)));
		standing.addTag(OURS);
		standing.addTag(handle(place, name));
		return standing;
	}

	/** Takes one away. Nothing by that name is not an error; see Effect.Unshow. */
	public static boolean take(ServerLevel level, String place, String name) {
		Display standing = find(level, place, name);
		if (standing == null) return false;
		standing.discard();
		return true;
	}

	// ------------------------------------------------------------------ finding them

	/** The thing this place calls by this name, or null when there is none. */
	public static Display find(ServerLevel level, String place, String name) {
		String handle = handle(place, name);
		for (Display each : level.getEntities(EntityTypeTest.forClass(Display.class),
				shown -> shown.entityTags().contains(handle))) {
			return each;
		}
		return null;
	}

	/**
	 * What ours near a point are called and how large they are, for working out a press.
	 *
	 * Only ones belonging to the place asked about, because a press is made by somebody
	 * standing somewhere: a guest in one copy of a lesson must not be able to press the
	 * next guest's menu through the wall between them.
	 */
	public static List<Aim> near(ServerLevel level, String place, Vec3 where, double howFar) {
		String prefix = handle(place, "");
		List<Aim> found = new ArrayList<>();
		for (Display each : level.getEntitiesOfClass(Display.class,
				AABB.ofSize(where, howFar * 2, howFar * 2, howFar * 2),
				shown -> shown.entityTags().contains(OURS))) {
			String name = nameOf(each, prefix);
			if (name.isEmpty()) continue;
			Vec3 at = each.position();
			found.add(new Aim(name, at.x, at.y, at.z, sizeOf(each)));
		}
		return found;
	}

	/** What this display is called in that place, or empty when it belongs to another. */
	private static String nameOf(Display shown, String prefix) {
		for (String tag : shown.entityTags()) {
			if (tag.startsWith(prefix)) return tag.substring(prefix.length());
		}
		return "";
	}

	/**
	 * How large a display is drawn, read back off the entity.
	 *
	 * Out through the same door it went in by, because the getter is private in the same
	 * way the setter is. One is enough: the scale is written on all three axes together,
	 * so the first is the size.
	 */
	private static float sizeOf(Display shown) {
		var out = net.minecraft.world.level.storage.TagValueOutput.createWithoutContext(
			new ProblemReporter.ScopedCollector(NpcStudio.LOGGER));
		shown.saveWithoutId(out);
		var scale = out.buildResult().getCompoundOrEmpty("transformation").getListOrEmpty("scale");
		return scale.isEmpty() ? 1.0f : scale.getFloatOr(0, 1.0f);
	}

	// ------------------------------------------------------------------ the game's spelling

	private static EntityType<? extends Display> typeOf(Shown.Kind kind) {
		return switch (kind) {
			case TEXT -> EntityTypes.TEXT_DISPLAY;
			case BLOCK -> EntityTypes.BLOCK_DISPLAY;
			case ITEM -> EntityTypes.ITEM_DISPLAY;
		};
	}

	/**
	 * A shown thing as the save file writes it.
	 *
	 * The only place in this project that knows any of these words. Every one of them was
	 * read off the game's own classes rather than a wiki, because the wiki's page for this
	 * says of itself that it is a work in progress and leaves out the three that matter.
	 */
	private static CompoundTag tag(ServerLevel level, Shown what,
			com.mopicmp.npcstudio.dialogue.text.Words says, Vec3 where) {
		CompoundTag tag = new CompoundTag();
		tag.put("Pos", doubles(where.x, where.y, where.z));
		// Lit regardless of the room, written as the two light levels the game packs
		// together, both at their brightest. The game has this for exactly this purpose and
		// almost nothing uses it, which is why other people's holograms go out at dusk.
		CompoundTag light = new CompoundTag();
		light.putInt("block", 15);
		light.putInt("sky", 15);
		tag.put("brightness", light);
		tag.putFloat("view_range", 1.0f);
		// Words and items turn to the reader; a block does not. A block that swivelled to
		// face whoever walked past would be the one thing in the room that is obviously not
		// a block.
		tag.putString("billboard", what.kind() == Shown.Kind.BLOCK ? "fixed" : "center");
		tag.put("transformation", how(what));

		switch (what.kind()) {
			case TEXT -> {
				tag.putInt("line_width", Shown.LINE_WIDTH);
				tag.putInt("background", what.behind());
				tag.putBoolean("see_through", true);
				tag.putBoolean("shadow", true);
				tag.putString("alignment", "center");
				tag.put("text", text(says));
			}
			case BLOCK -> {
				var state = com.mopicmp.npcstudio.dialogue.runtime.BlockLook.state(what.plain());
				tag.put("block_state", NbtUtils.writeBlockState(
					state == null
						? net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()
						: state));
			}
			case ITEM -> {
				tag.put("item", item(level, what.plain()));
				// Flat, and facing whoever looks, which is what a thing hanging in the air
				// as a picture of itself wants. The alternative is the tilted pose an item
				// takes on the ground, which reads as a dropped item somebody can pick up.
				tag.putString("item_display", "fixed");
			}
		}
		return tag;
	}

	/**
	 * Scale, and where the thing sits relative to the point it was given.
	 *
	 * A block display draws its block in the cube <em>after</em> its position, so a block
	 * asked for at a point would hang up and to one side of it. Moved back by half its own
	 * size, "where" means the middle of the thing for all three kinds — which is the only
	 * way a menu of words and blocks can be lined up by anybody.
	 */
	private static Tag how(Shown what) {
		float size = what.size();
		CompoundTag how = new CompoundTag();
		how.put("scale", floats(size, size, size));
		how.put("translation", what.kind() == Shown.Kind.BLOCK
			? floats(-size / 2, -size / 2, -size / 2) : floats(0, 0, 0));
		how.put("left_rotation", floats(0, 0, 0, 1));
		how.put("right_rotation", floats(0, 0, 0, 1));
		return how;
	}

	/**
	 * The words, dressed, as a text component in save data.
	 *
	 * Written through the game's own component codec rather than assembled by hand, because
	 * a component written by hand is a component that is subtly wrong on the first line
	 * somebody puts a quote mark in.
	 *
	 * One stretch of the writing becomes one sibling with its own style, which is how a
	 * label can have one word of it picked out — the same looks a spoken line carries,
	 * written in the same window.
	 *
	 * <h2>What is dropped, and why it is dropped here</h2>
	 *
	 * A look's own size. A text display has one scale for the whole entity, so there is no
	 * such thing as one word twice the height of the next; the size of the hologram is
	 * {@link Shown#size}. Dropped at the last moment rather than refused in the editor,
	 * because the same words may be spoken by a character as well, where the size does
	 * work — the writing is not wrong, this one reader cannot honour all of it.
	 */
	private static Tag text(com.mopicmp.npcstudio.dialogue.text.Words says) {
		net.minecraft.network.chat.MutableComponent said = Component.empty();
		for (var run : says.runs()) {
			said.append(Component.literal(run.text()).withStyle(style -> dressed(style, run.look())));
		}
		return ComponentSerialization.CODEC
			.encodeStart(NbtOps.INSTANCE, said)
			.result()
			.orElseGet(() -> StringTag.valueOf(says.plain()));
	}

	/** One stretch's look, in the game's own words for the same things. */
	private static net.minecraft.network.chat.Style dressed(
			net.minecraft.network.chat.Style style,
			com.mopicmp.npcstudio.dialogue.text.Look look) {
		net.minecraft.network.chat.Style now = style
			.withBold(look.bold())
			.withItalic(look.italic())
			.withUnderlined(look.underlined())
			.withStrikethrough(look.struck());
		if (look.colour() != null) now = now.withColor(look.colour() & 0xFFFFFF);
		if (look.font() != null && !look.font().isBlank()) {
			var font = Identifier.tryParse(look.font());
			if (font != null) {
				now = now.withFont(new net.minecraft.network.chat.FontDescription.Resource(font));
			}
		}
		return now;
	}

	/**
	 * One item, as save data.
	 *
	 * An item that does not exist comes out as a stone, rather than as nothing: a hologram
	 * that is missing entirely says nothing about which word was misspelt, and the misspelt
	 * word is in the graph where somebody can find it.
	 */
	private static Tag item(ServerLevel level, String id) {
		var item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id))
			.orElse(net.minecraft.world.item.Items.STONE);
		var ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
		return ItemStack.CODEC.encodeStart(ops, new ItemStack(item))
			.result()
			.orElseGet(CompoundTag::new);
	}

	private static Tag floats(float... values) {
		net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
		for (float value : values) list.add(net.minecraft.nbt.FloatTag.valueOf(value));
		return list;
	}

	private static Tag doubles(double... values) {
		net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
		for (double value : values) list.add(net.minecraft.nbt.DoubleTag.valueOf(value));
		return list;
	}

	/**
	 * Save data the game will read, with anything it cannot understand reported.
	 *
	 * Reported rather than swallowed: a field this project spells wrong would otherwise be
	 * a hologram that comes out looking ordinary, and "ordinary" is the hardest wrongness
	 * to notice.
	 */
	private static net.minecraft.world.level.storage.ValueInput read(
			ServerLevel level, CompoundTag tag) {
		return TagValueInput.create(
			new ProblemReporter.ScopedCollector(NpcStudio.LOGGER), level.registryAccess(), tag);
	}
}
