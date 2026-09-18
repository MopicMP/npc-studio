package com.mopicmp.npcstudio.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mopicmp.npcstudio.entity.NpcEntity;
import com.mopicmp.npcstudio.entity.NpcStudioEntities;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.Vec3;

/**
 * The one thing that has to be a command.
 *
 * <h2>What used to be here, and why it is not</h2>
 *
 * Eleven commands. Every one added to try something out, each with a comment
 * saying it was scaffolding and belonged in the character panel, and not one of
 * them ever moved. That is not an accident of discipline: a command tree has no
 * edge, so nothing in it is ever obviously finished with, and the cost of an
 * extra branch is invisible to whoever adds it and paid by everybody who types
 * a slash afterwards.
 *
 * Worse, most of them were duplicates the day they were written. The hands had
 * been settable from the character panel since before {@code /npc arm} existed;
 * the dialogue editor could already be browsed from its panel when {@code /npc
 * edit} was added; answering a question already worked by clicking it.
 *
 * So there is one rule now, and it is about where a thing lives rather than
 * about intent:
 *
 * <ul>
 * <li>A <b>setting</b> of one character — which brain, whether she watches,
 *     what is in her hands — is on the character panel.</li>
 * <li>A <b>decision</b> — when to shoot, where to walk — is block programming,
 *     and nothing else may decide it.</li>
 * <li>A <b>trial</b>, something to press and watch before a graph can order it,
 *     is the test bench panel, which is one file and is meant to be deleted.</li>
 * </ul>
 *
 * What is left over is this: putting a character into the world in the first
 * place. There is nothing to select yet and no panel to do it from, the same
 * way {@code /summon} is a command rather than a button.
 */
public final class NpcCommands {

	private NpcCommands() { }

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("npc")
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.literal("spawn")
				.executes(context -> spawn(context, "Steve"))
				.then(Commands.argument("skin", StringArgumentType.word())
					.executes(context -> spawn(context, StringArgumentType.getString(context, "skin"))))));
	}

	/**
	 * Places an NPC where the caller stands, wearing the named player's skin.
	 *
	 * Facing the opposite way to the caller on purpose: an NPC that spawns
	 * looking at you reads as placed, whereas one facing the same way looks like
	 * it was dropped there by mistake.
	 */
	private static int spawn(CommandContext<CommandSourceStack> context, String skin) {
		CommandSourceStack source = context.getSource();
		ServerLevel level = source.getLevel();
		Vec3 where = source.getPosition();

		NpcEntity npc = NpcStudioEntities.NPC.create(level, EntitySpawnReason.COMMAND);
		if (npc == null) {
			source.sendFailure(Component.literal("Could not create the NPC."));
			return 0;
		}

		ServerPlayer caller = source.getPlayer();
		float facing = caller == null ? 0f : caller.getYRot() + 180f;
		npc.snapTo(where.x, where.y, where.z, facing, 0f);
		npc.setSkin(skin);
		// Where she belongs, said at the one moment it is unambiguous. It is otherwise
		// settled on her first tick, which is the same answer for a character placed
		// today and the wrong one for a character placed before that existed: her first
		// tick after the update is wherever her graph had already walked her to, and a
		// guard whose home is the end of her own round never comes back from it.
		npc.markPost();
		level.addFreshEntity(npc);

		source.sendSuccess(() -> Component.literal("Placed an NPC wearing " + skin + "'s skin."), true);
		return 1;
	}
}
