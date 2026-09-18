package com.mopicmp.npcstudio.dialogue.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mopicmp.npcstudio.NpcStudio;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Walls a conversation put up, and the promise that they come down again.
 *
 * <h2>What this class is really for</h2>
 *
 * Not putting blocks in a world — that is three lines. It exists because of the
 * other half: a wall that outlives the conversation that raised it is an
 * <em>invisible</em> wall across somebody's corridor, in a map they are building,
 * with nothing anywhere saying it is there. There is no error, no log line and
 * nothing to see. The author walks into thin air for the rest of the afternoon.
 *
 * So everything here is arranged around taking them down, and there are five ways
 * a conversation can end without meaning to:
 *
 * <ul>
 * <li>it finishes — the ordinary way;</li>
 * <li>the player walks off and it times out on {@link DialogueRuntime#PATIENCE};</li>
 * <li>the player disconnects;</li>
 * <li>the server is stopped;</li>
 * <li>the machine loses power in the middle of it.</li>
 * </ul>
 *
 * The first four are hooks. The fifth cannot be hooked, which is the whole reason
 * this is a {@link SavedData} rather than a map in memory: what was filled is
 * written into the save beside the blocks themselves, so a world coming back from
 * a crash knows what to sweep up.
 *
 * <h2>Why only empty cells are filled</h2>
 *
 * Because then putting it back is knowing nothing. A wall that replaced somebody's
 * stone would have to remember the stone — its state, its block entity, its
 * neighbours' reaction to losing it — and every one of those is a way to hand back
 * something subtly different from what was taken. Filling air and clearing air is
 * an operation that cannot lose anything, and a passage sealed with a wall that
 * skipped the cells which were already solid is sealed exactly as well.
 *
 * <h2>Why a barrier</h2>
 *
 * It is the block the game already has for this: no picture, full collision, and
 * not breakable by a player who is not in creative. Making our own would be a
 * block whose only difference is that it is ours.
 *
 * How the wall <em>looks</em> is a separate question with a separate answer, and
 * keeping the two apart is what let this one be finished on its own.
 */
public class Walls extends SavedData {

	/**
	 * The blocks, as four questions and answers.
	 *
	 * <h2>Why the world is behind an interface</h2>
	 *
	 * The same reason the pathfinder's ground is — see {@code Ways.Ground} — and it
	 * matters more here. Everything in this class is bookkeeping: which cells belong
	 * to which conversation, which are shared with another, which have since been
	 * replaced by somebody in creative. That bookkeeping is where the failure lives,
	 * and its failure is a wall nobody can see and nobody can break.
	 *
	 * Wired to a {@code ServerLevel} it could only be checked by playing, which for
	 * this particular fault means noticing that a corridor you walked down an hour
	 * ago is now solid. Behind four methods it can be checked in a second, before
	 * anybody is standing in it.
	 */
	public interface Ground {

		/** Whether this cell is empty and inside the world: somewhere a wall may go. */
		boolean empty(BlockPos at);

		/**
		 * Whether this cell holds one of our walls.
		 *
		 * Asked before clearing, never before filling. A cell that holds anything else
		 * is a cell somebody has filled since — in creative, or by a command — and
		 * clearing it would delete their block on the strength of our own old note.
		 */
		boolean ours(BlockPos at);

		void fill(BlockPos at);

		void clear(BlockPos at);

		/**
		 * As many blocks as this world will put down in one act.
		 *
		 * Asked of the ground rather than held as a constant here, because 26.2 moved
		 * this ceiling into a game rule and made rules belong to a dimension. The
		 * constant below was right on the day it was written — it was the game's own
		 * number rather than an invented one — and the game has since made it something
		 * an operator sets, which a copy of it cannot follow.
		 *
		 * It matters where it matters: a location that raises the rule for itself so
		 * that its ground can be laid out would otherwise find its walls still refusing
		 * at the old number, for a reason nothing in the game would say.
		 */
		default int allowance() {
			return MOST;
		}
	}

	/**
	 * The real world, as that.
	 *
	 * A barrier is the block the game already has for exactly this: no picture, full
	 * collision, not breakable by anybody outside creative. Making our own would be a
	 * block whose only difference is that it is ours.
	 */
	public static Ground in(ServerLevel level) {
		return new Ground() {
			@Override public boolean empty(BlockPos at) {
				// Outside the world is not somewhere a wall can stand. Asked without this
				// the answer is air, and a cell would go into the list that nothing can
				// ever clear.
				return !level.isOutsideBuildHeight(at) && level.getBlockState(at).isAir();
			}

			@Override public boolean ours(BlockPos at) {
				return level.getBlockState(at).is(Blocks.BARRIER);
			}

			@Override public void fill(BlockPos at) {
				level.setBlock(at, Blocks.BARRIER.defaultBlockState(), 3);
			}

			@Override public int allowance() {
				return level.getGameRules().get(
					net.minecraft.world.level.gamerules.GameRules.MAX_BLOCK_MODIFICATIONS);
			}

			@Override public void clear(BlockPos at) {
				level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
			}
		};
	}

	/**
	 * One wall that is standing, and who it belongs to.
	 *
	 * The cells are listed rather than being worked out again from the box. Two
	 * reasons, and the second is the one that matters: a box written as steps moves
	 * with the character, so asking the box again after she has walked on would
	 * clear a different set of blocks from the ones that were filled — and leave the
	 * real wall standing for ever. A list cannot drift.
	 */
	private record Standing(UUID player, UUID npc, String area, List<BlockPos> cells) {
		static final Codec<Standing> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			UUIDUtil.CODEC.fieldOf("player").forGetter(Standing::player),
			UUIDUtil.CODEC.fieldOf("npc").forGetter(Standing::npc),
			Codec.STRING.fieldOf("area").forGetter(Standing::area),
			BlockPos.CODEC.listOf().fieldOf("cells").forGetter(Standing::cells)
		).apply(instance, Standing::new));
	}

	private static final Codec<Walls> CODEC = Standing.CODEC.listOf()
		.optionalFieldOf("standing", List.of())
		.xmap(Walls::new, walls -> List.copyOf(walls.standing))
		.codec();

	private static final SavedDataType<Walls> TYPE = new SavedDataType<>(
		NpcStudio.id("walls"), Walls::new, CODEC, DataFixTypes.LEVEL);

	/**
	 * As many blocks as one wall may fill, where nobody has said otherwise.
	 *
	 * The number the game itself already decided is a reasonable one-shot fill —
	 * {@code /fill} refused more than this — rather than one invented here. A wall
	 * across a corridor is about twenty blocks; a wall across a cave mouth a few
	 * hundred. Anything at this size is a mistake in a box, and the honest response
	 * to a mistake in a box is to refuse it and say so, not to spend a minute of the
	 * server's time proving it.
	 *
	 * In 26.2 the game turned this into the game rule {@code max_block_modifications},
	 * and made rules belong to a dimension. So this is now the default rather than the
	 * answer, and the answer is asked of the ground — see {@link Ground#allowance()}.
	 * It stays here because a {@code Ground} that is not a world, such as the one the
	 * tests use, still needs a number and this is the right one for it.
	 */
	public static final int MOST = 32768;

	private final List<Standing> standing = new ArrayList<>();

	public Walls() { }

	private Walls(List<Standing> kept) {
		standing.addAll(kept);
	}

	/**
	 * The book for a world.
	 *
	 * The overworld's, like the conversations beside it, and for a plainer reason
	 * than theirs: whichever dimension a wall stands in, there is one list and one
	 * sweep, and a second book per dimension would be a second thing to remember to
	 * empty.
	 */
	public static Walls of(ServerLevel level) {
		return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	// ------------------------------------------------------------------ raising

	/**
	 * Fills the empty cells of a box, and remembers exactly which ones.
	 *
	 * Raising a wall that is already up does nothing at all rather than filling it
	 * twice. Which matters more than it sounds: a graph that walks past the same
	 * {@code act} twice — a loop, a conversation reopened — would otherwise leave
	 * two records of one wall, and dropping it once would leave the other standing
	 * with nobody left who knows how to take it down.
	 *
	 * @return how many blocks were placed, or -1 when the box was too big to try
	 */
	public int raise(Ground ground, UUID player, UUID npc, String area,
			int[] low, int[] high) {
		if (find(player, npc, area) != null) return 0;

		long many = (long) (high[0] - low[0] + 1) * (high[1] - low[1] + 1) * (high[2] - low[2] + 1);
		if (many > ground.allowance()) return -1;

		// Two counts, and the difference between them is a bug this class had until a
		// test asked about it. What is *placed* is only the empty cells. What is
		// *claimed* is every cell of the box that ends up holding one of our walls —
		// including the ones another conversation had already filled.
		//
		// Without the second, a wall raised over a passage somebody else had already
		// sealed placed nothing, recorded nothing, and therefore claimed nothing. When
		// the first conversation ended it cleared the lot, and the second player was
		// left standing in an open corridor in the middle of being shut in — with the
		// graph perfectly certain the wall was up.
		List<BlockPos> claimed = new ArrayList<>();
		int placed = 0;
		for (int x = low[0]; x <= high[0]; x++) {
			for (int y = low[1]; y <= high[1]; y++) {
				for (int z = low[2]; z <= high[2]; z++) {
					BlockPos here = new BlockPos(x, y, z);
					if (ground.empty(here)) {
						ground.fill(here);
						placed++;
					} else if (!ground.ours(here)) {
						// Somebody's own stone. It seals the passage as well as ours would
						// and it is not ours to claim, to clear, or to count.
						continue;
					}
					claimed.add(here);
				}
			}
		}
		if (claimed.isEmpty()) return 0;
		standing.add(new Standing(player, npc, area, List.copyOf(claimed)));
		setDirty();
		return placed;
	}

	// ----------------------------------------------------------------- dropping

	/** Takes down one wall, if that conversation raised one there. */
	public void drop(Ground ground, UUID player, UUID npc, String area) {
		Standing was = find(player, npc, area);
		if (was == null) return;
		standing.remove(was);
		clear(ground, was);
		setDirty();
	}

	/**
	 * Takes down everything one conversation raised.
	 *
	 * The one called from every way a conversation can end. A graph that raised two
	 * walls and reached its ending through a branch that only drops one is not a
	 * broken graph — it is an ordinary graph written by somebody thinking about the
	 * scene rather than about the blocks — so the ending has to be the thing that
	 * sweeps up, not the author.
	 */
	public void dropAll(Ground ground, UUID player) {
		List<Standing> theirs = standing.stream().filter(each -> each.player().equals(player)).toList();
		if (theirs.isEmpty()) return;
		standing.removeAll(theirs);
		for (Standing each : theirs) clear(ground, each);
		setDirty();
	}

	/**
	 * Takes down every wall in the world, whoever raised it.
	 *
	 * For the two moments when there is no conversation left to belong to: the
	 * server stopping, and a world opening with walls recorded in it — which can
	 * only mean the last run of it did not stop, and the blocks are still there.
	 */
	public void dropEverything(Ground ground) {
		if (standing.isEmpty()) return;
		List<Standing> all = List.copyOf(standing);
		standing.clear();
		for (Standing each : all) clear(ground, each);
		setDirty();
	}

	/**
	 * Clears the cells of one wall, leaving alone anything that is not ours.
	 *
	 * Two checks, and both are about not taking something that was never given. A
	 * cell holding anything but a barrier is a cell somebody has since filled — in
	 * creative, or by a command — and clearing it would delete their block. A cell
	 * another standing wall also claims is a passage two conversations are sealing
	 * at once, and the second one is not over.
	 */
	private void clear(Ground ground, Standing wall) {
		for (BlockPos at : wall.cells()) {
			if (!ground.ours(at)) continue;
			if (claimedByAnother(at)) continue;
			ground.clear(at);
		}
	}

	private boolean claimedByAnother(BlockPos at) {
		for (Standing each : standing) {
			if (each.cells().contains(at)) return true;
		}
		return false;
	}

	private Standing find(UUID player, UUID npc, String area) {
		for (Standing each : standing) {
			if (each.player().equals(player) && each.npc().equals(npc)
					&& each.area().equals(area)) {
				return each;
			}
		}
		return null;
	}

	/** How many walls are standing, for a test and for a line in the log. */
	public int count() {
		return standing.size();
	}
}
