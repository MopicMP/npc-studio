package com.mopicmp.npcstudio.map;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Which guest has which location laid out, and where it was put.
 *
 * <h2>Why a copy each</h2>
 *
 * The author's answer, and it follows from the one before it: a lesson is put back the
 * way it was built every time somebody walks in. Two people sharing one would mean one of
 * them pulling a lever the other had already pulled, and a reset breaking the game for
 * whoever did not ask for it.
 *
 * It is affordable only because of that same rule. A copy exists while somebody is
 * standing in it and not a moment longer, so the number of them is the number of people
 * inside at once — not the number of people with accounts, which is a different number by
 * an order of magnitude.
 *
 * <h2>Why this is a place rather than a map from a person to a name</h2>
 *
 * Because the question everything else asks is the other way round: given a point in the
 * world, which lesson is this? A character standing in a copy has to find the documents
 * of <em>that</em> lesson, and she is not a player and has nobody to be looked up under.
 * She has a position, and so does every block anything might ask about.
 *
 * <h2>Why it is written down, when copies do not survive a restart</h2>
 *
 * They do not, but their blocks do. A server that stops badly leaves the ground of every
 * copy standing in the lessons' world with nobody left who knows it is there — the same
 * shape of problem as a wall left up by a conversation that never ended, and swept the
 * same way, at startup, from the list beside it.
 */
public class Copies extends SavedData {

	/**
	 * One lesson, laid out for one person.
	 *
	 * @param location the document this is a copy of
	 * @param guest    who it was laid out for, and the only one meant to be in it
	 * @param origin   the low corner it was placed at
	 * @param size     how big the ground is, so the box can be worked out without
	 *                 loading the blocks back off the disk
	 * @param backIn   the world this person came from, and {@code backAt} the spot in
	 *                 it. Written down rather than remembered, because somebody who logs
	 *                 out inside a lesson has to be able to get home after a restart.
	 *                 <p>
	 *                 Two fields and not the game's own {@code GlobalPos}, which is
	 *                 exactly this pair and would read better. Its codec initialises
	 *                 {@code Level}, which initialises the particle registries, which
	 *                 need a game that has started — so a record holding one cannot be
	 *                 checked by a test at all. The pairing is worth less than being
	 *                 able to prove the arithmetic around it.
	 * @param sticks   the hub: ground that is laid out once and never cleared. The one
	 *                 thing a map keeps, said as a flag rather than as a second kind of
	 *                 record, so that everything asking "which lesson is this point in"
	 *                 goes on asking one question
	 */
	public record Copy(String location, UUID guest, BlockPos origin, Vec3i size,
			ResourceKey<Level> backIn, BlockPos backAt, boolean sticks) {

		public static final Codec<Copy> CODEC = RecordCodecBuilder.create(instance ->
			instance.group(
				Codec.STRING.fieldOf("location").forGetter(Copy::location),
				UUIDUtil.CODEC.fieldOf("guest").forGetter(Copy::guest),
				BlockPos.CODEC.fieldOf("origin").forGetter(Copy::origin),
				Vec3i.CODEC.fieldOf("size").forGetter(Copy::size),
				ResourceKey.codec(Registries.DIMENSION).fieldOf("back_in")
					.forGetter(Copy::backIn),
				BlockPos.CODEC.fieldOf("back_at").forGetter(Copy::backAt),
				Codec.BOOL.optionalFieldOf("sticks", false).forGetter(Copy::sticks)
			).apply(instance, Copy::new));

		/** Whether a point is inside this copy's ground. */
		public boolean holds(BlockPos at) {
			return at.getX() >= origin.getX() && at.getX() < origin.getX() + size.getX()
				&& at.getY() >= origin.getY() && at.getY() < origin.getY() + size.getY()
				&& at.getZ() >= origin.getZ() && at.getZ() < origin.getZ() + size.getZ();
		}

		/** Where this copy ends along the row copies are laid out in. */
		public int endsAt() {
			return origin.getX() + size.getX();
		}
	}

	public static final Codec<Copies> CODEC =
		Codec.unboundedMap(UUIDUtil.STRING_CODEC, Copy.CODEC).xmap(Copies::new, data -> data.byGuest);

	private static final SavedDataType<Copies> TYPE = new SavedDataType<>(
		NpcStudio.id("lesson_copies"), Copies::new, CODEC, DataFixTypes.LEVEL);

	/**
	 * The floor copies are laid out on.
	 *
	 * Not the bottom of the world: a build wants room under it for a cellar, and a
	 * location whose lowest block was the world's lowest block could never have one.
	 */
	public static final int FLOOR = 0;

	/**
	 * The least room left between two copies, whatever the view distance says.
	 *
	 * A floor under a gap that is otherwise measured. A server set to a view distance of
	 * two would put lessons close enough that walking the wrong way leaves one and
	 * arrives in the next, which is worse than seeing it.
	 */
	public static final int LEAST_GAP = 64;

	private final Map<UUID, Copy> byGuest = new LinkedHashMap<>();

	public Copies() { }

	private Copies(Map<UUID, Copy> loaded) {
		this.byGuest.putAll(loaded);
	}

	public static Copies of(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(TYPE);
	}

	/** What this person has laid out, or null when they are not in a lesson. */
	public Copy forGuest(UUID guest) {
		return guest == null ? null : byGuest.get(guest);
	}

	/**
	 * Which lesson a point is in, or empty for the map's own ground.
	 *
	 * The one question the rest of the mod asks. Empty rather than null because that is
	 * what a document not living in any location says about itself, and the two have to
	 * compare equal without anybody remembering to check for nothing.
	 */
	public String at(BlockPos where) {
		if (where == null) return "";
		for (Copy copy : byGuest.values()) {
			if (copy.holds(where)) return copy.location();
		}
		return "";
	}

	public List<Copy> all() {
		return new ArrayList<>(byGuest.values());
	}

	public int count() {
		return byGuest.size();
	}

	/**
	 * Where to put a copy of this size so that it touches nothing.
	 *
	 * <h2>Why a row and not a grid</h2>
	 *
	 * Because a row needs one number compared against one number, and the world it is
	 * laid out in is empty and has no edges. A grid would be cleverer and would buy
	 * nothing but the arithmetic to get wrong.
	 *
	 * <h2>Why the gap is asked for rather than chosen here</h2>
	 *
	 * Because the right gap is "further than anybody can see", and how far that is, is
	 * the server's view distance — a setting, not a constant. Guessed too small, a guest
	 * sees the next person's lesson hanging in the void beside their own; guessed too
	 * large, nothing at all goes wrong, which is why the caller measures and this floors
	 * it at {@link #LEAST_GAP}.
	 *
	 * @param gap how much clear room to leave on each side
	 */
	public BlockPos free(Vec3i size, int gap) {
		int room = Math.max(LEAST_GAP, gap);
		int x = 0;
		// Scanning from nought every time, so that room freed by somebody leaving is used
		// again. Without it the row only ever grows, and a server that has run for a
		// month lays its lessons out a hundred thousand blocks from the middle.
		boolean moved = true;
		while (moved) {
			moved = false;
			for (Copy copy : byGuest.values()) {
				// The hub stands apart from the row and is never moved, so it takes no
				// part in deciding where the next lesson goes.
				if (copy.sticks()) continue;
				boolean clashes = x < copy.endsAt() + room
					&& copy.origin().getX() < x + size.getX() + room;
				if (clashes) {
					x = copy.endsAt() + room;
					moved = true;
				}
			}
		}
		return new BlockPos(x, FLOOR, 0);
	}

	/**
	 * Notes a copy down, replacing whatever this person had before.
	 *
	 * Replacing rather than refusing, because one person is in one lesson at a time and
	 * the old note would otherwise keep a stretch of the row reserved for ground that is
	 * no longer there.
	 */
	public void put(Copy copy) {
		if (copy == null) return;
		byGuest.put(copy.guest(), copy);
		setDirty();
	}

	public Copy drop(UUID guest) {
		Copy gone = byGuest.remove(guest);
		if (gone != null) setDirty();
		return gone;
	}

	/**
	 * Everything that has to be cleared away, on the way out of a sweep.
	 *
	 * The hub is not among them, and that is the whole of what makes it the hub: its
	 * ground stands through a restart, so the note about it has to stand too or the next
	 * visitor lays a second one on top of the first.
	 */
	public List<Copy> dropEverything() {
		List<Copy> going = new ArrayList<>();
		for (Copy copy : all()) {
			if (!copy.sticks()) going.add(copy);
		}
		if (going.isEmpty()) return going;
		for (Copy copy : going) byGuest.remove(copy.guest());
		setDirty();
		return going;
	}

	/** Whether a location's ground is already standing as the hub. */
	public Copy hubStanding() {
		for (Copy copy : byGuest.values()) {
			if (copy.sticks()) return copy;
		}
		return null;
	}
}
