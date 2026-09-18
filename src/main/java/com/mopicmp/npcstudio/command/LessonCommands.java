package com.mopicmp.npcstudio.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mopicmp.npcstudio.dialogue.Dialogue;
import com.mopicmp.npcstudio.dialogue.runtime.DialogueRegistry;
import com.mopicmp.npcstudio.map.Lesson;
import com.mopicmp.npcstudio.map.Regions;
import com.mopicmp.npcstudio.map.WorldRegions;
import com.mopicmp.npcstudio.map.Visits;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerPlayer;

/**
 * Going to a lesson and coming back, by typing.
 *
 * <h2>Why a command when the whole mod is a window</h2>
 *
 * The same reason {@code /npc spawn} is a command: there is nothing to click yet. A guest
 * standing in the overworld has no lesson selected and no panel that lists them, and the
 * first way into a place has to work before any of the furniture for it exists.
 *
 * It is also the way in that keeps working when something else is wrong. If a lesson
 * misbehaves, or a hub is deleted while somebody is standing in it, {@code /lesson leave}
 * is a way out that does not depend on the thing that broke.
 *
 * <h2>Why the names are offered rather than typed</h2>
 *
 * Because a location is named in whatever letters its author likes, and a name typed is a
 * name misspelt. The same argument as every other list in this project, and the same cure.
 *
 * <h2>What it does not do</h2>
 *
 * Take anybody but the caller. Sending other people into a lesson is a thing an adventure
 * map will want and a thing worth doing deliberately, with a say in what happens to
 * whoever is already inside — not as an extra argument added because it was easy.
 */
public final class LessonCommands {

	private LessonCommands() { }

	/** The places this map has, offered as you type. */
	private static final SuggestionProvider<CommandSourceStack> PLACES = (context, builder) ->
		SharedSuggestionProvider.suggest(DialogueRegistry.names("").stream()
			.filter(name -> DialogueRegistry.get(name)
				.map(Dialogue::isLocation).orElse(false))
			.toList(), builder);

	/**
	 * The two corners each person has pointed at, while they are pointing at them.
	 *
	 * In memory, because a selection is not a fact about the world — it is what somebody
	 * is doing right now, and it should not outlive them leaving. Losing it on a restart
	 * costs two commands.
	 */
	private static final java.util.Map<java.util.UUID, BlockPos[]> CORNERS =
		new java.util.HashMap<>();

	/** The ground this world has copies of, offered as you type. */
	private static final SuggestionProvider<CommandSourceStack> GROUND = (context, builder) ->
		SharedSuggestionProvider.suggest(
			WorldRegions.of(context.getSource().getLevel()).names(), builder);

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("lesson")
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			// Bare, it means the hub, because that is where a guide starts and because
			// somebody who types only the word wants to be shown the way in. With no hub
			// yet it means the workshop, because otherwise the only answer this command
			// could give somebody with an empty map is "no", and the thing they were
			// about to do was build the hub.
			.executes(LessonCommands::toHub)
			.then(Commands.literal("hub").executes(LessonCommands::toHub))
			.then(Commands.literal("workshop").executes(LessonCommands::toWorkshop))
			.then(Commands.literal("leave").executes(LessonCommands::leave))
			.then(Commands.literal("go")
				.then(Commands.argument("place", StringArgumentType.string())
					.suggests(PLACES)
					.executes(context -> go(context,
						StringArgumentType.getString(context, "place")))))
			// Taking a copy of the ground. Two corners and a name, which is the same
			// gesture the game's own structure block asks for, minus the block.
			.then(Commands.literal("corner").executes(LessonCommands::corner))
			.then(Commands.literal("take")
				.then(Commands.argument("name", StringArgumentType.string())
					.suggests(GROUND)
					.executes(context -> take(context,
						StringArgumentType.getString(context, "name")))))
			.then(Commands.literal("ground").executes(LessonCommands::ground)));
	}

	/**
	 * Marks where the caller stands as a corner of the next copy.
	 *
	 * Two of them, and the second replaces the older of the two — so pointing at a third
	 * corner means "no, that one", which is what somebody correcting themselves expects.
	 */
	private static int corner(CommandContext<CommandSourceStack> context) {
		ServerPlayer player = context.getSource().getPlayer();
		if (player == null) {
			context.getSource().sendFailure(
				Component.translatable("npc_studio.lesson.only_players"));
			return 0;
		}
		BlockPos at = player.blockPosition();
		BlockPos[] both = CORNERS.computeIfAbsent(player.getUUID(), key -> new BlockPos[2]);
		boolean first = both[0] == null || both[1] != null;
		if (first) {
			both[0] = at;
			both[1] = null;
		} else {
			both[1] = at;
		}
		context.getSource().sendSuccess(() -> Component.translatable(
			first ? "npc_studio.lesson.corner_first" : "npc_studio.lesson.corner_second",
			at.getX(), at.getY(), at.getZ()), false);
		return 1;
	}

	/**
	 * Takes a copy of the ground between the two corners.
	 *
	 * Every refusal is said in full, with the number that caused it, because the thing to
	 * do about each is different: a name too long is retyped, ground too big is either
	 * drawn smaller or allowed more by the world's own rule, and a world already full has
	 * to have something taken off it.
	 */
	private static int take(CommandContext<CommandSourceStack> context, String name) {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.translatable("npc_studio.lesson.only_players"));
			return 0;
		}
		BlockPos[] both = CORNERS.get(player.getUUID());
		if (both == null || both[0] == null || both[1] == null) {
			source.sendFailure(Component.translatable("npc_studio.lesson.need_corners"));
			return 0;
		}

		Regions.Taken done = Regions.capture(source.getLevel(), both[0], both[1], name);
		switch (done) {
			case Regions.Taken.Kept(String kept, Vec3i size, long blocks) -> {
				source.sendSuccess(() -> Component.translatable("npc_studio.lesson.took",
					kept, size.getX(), size.getY(), size.getZ(), blocks), true);
				CORNERS.remove(player.getUUID());
				return 1;
			}
			case Regions.Taken.TooBig(long blocks, int allowed) ->
				source.sendFailure(Component.translatable("npc_studio.lesson.too_big",
					blocks, allowed));
			// The name it refused is the one that was just typed, so saying it back adds
			// nothing; what is useful is how long a name may be.
			case Regions.Taken.BadName(String _) ->
				source.sendFailure(Component.translatable("npc_studio.lesson.bad_name",
					WorldRegions.LONGEST));
			case Regions.Taken.TooMany(int most) ->
				source.sendFailure(Component.translatable("npc_studio.lesson.too_many", most));
			case Regions.Taken.NotWritten(String which) ->
				source.sendFailure(Component.translatable("npc_studio.lesson.not_written", which));
		}
		return 0;
	}

	/** What ground this world has copies of, and how big each is. */
	private static int ground(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		WorldRegions regions = WorldRegions.of(source.getLevel());
		if (regions.count() == 0) {
			source.sendSuccess(() ->
				Component.translatable("npc_studio.lesson.no_ground_yet"), false);
			return 0;
		}
		for (var one : regions.all()) {
			Vec3i size = one.getValue().size();
			source.sendSuccess(() -> Component.translatable("npc_studio.lesson.ground_row",
				one.getKey(), size.getX(), size.getY(), size.getZ()), false);
		}
		return regions.count();
	}

	private static int toWorkshop(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.translatable("npc_studio.lesson.only_players"));
			return 0;
		}
		if (!Visits.toWorkshop(player)) {
			source.sendFailure(Component.translatable("npc_studio.lesson.no_world"));
			return 0;
		}
		source.sendSuccess(() -> Component.translatable("npc_studio.lesson.at_workshop"), false);
		return 1;
	}

	private static int toHub(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.translatable("npc_studio.lesson.only_players"));
			return 0;
		}
		if (Lesson.level(source.getServer()) == null) {
			source.sendFailure(Component.translatable("npc_studio.lesson.no_world"));
			return 0;
		}
		if (!Visits.toHub(player)) {
			// No hub yet, which on an empty map is simply where everybody starts. Sending
			// them to the workshop instead of refusing, because the thing they were about
			// to do is build the hub, and refusing entry to the only world they could
			// build it in is a circle with no way in.
			if (!Visits.toWorkshop(player)) {
				source.sendFailure(Component.translatable("npc_studio.lesson.no_world"));
				return 0;
			}
			source.sendSuccess(() ->
				Component.translatable("npc_studio.lesson.no_hub_yet"), false);
			return 1;
		}
		return 1;
	}

	private static int go(CommandContext<CommandSourceStack> context, String place) {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.translatable("npc_studio.lesson.only_players"));
			return 0;
		}
		Dialogue graph = DialogueRegistry.get(place).orElse(null);
		if (graph == null || !graph.isLocation()) {
			source.sendFailure(Component.translatable("npc_studio.lesson.not_a_place", place));
			return 0;
		}
		if (!Visits.enter(player, place)) {
			// The one refusal worth spelling out, because it is the ordinary one: a place
			// exists as a document but nobody has taken a copy of its ground yet.
			source.sendFailure(Component.translatable("npc_studio.lesson.no_ground", place));
			return 0;
		}
		source.sendSuccess(() -> Component.translatable("npc_studio.lesson.went", place), false);
		return 1;
	}

	private static int leave(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendFailure(Component.translatable("npc_studio.lesson.only_players"));
			return 0;
		}
		if (!Visits.leave(player)) {
			source.sendFailure(Component.translatable("npc_studio.lesson.not_inside"));
			return 0;
		}
		source.sendSuccess(() -> Component.translatable("npc_studio.lesson.left"), false);
		return 1;
	}
}
