package com.mopicmp.npcstudio.client.workspace;

import java.util.ArrayList;
import java.util.List;

import com.mopicmp.npcstudio.client.edit.Doings;
import com.mopicmp.npcstudio.client.edit.History;
import com.mopicmp.npcstudio.client.map.Started;
import com.mopicmp.npcstudio.client.model.Gizmo;
import com.mopicmp.npcstudio.net.NpcPayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;

/**
 * What is offered for the thing under the cursor.
 *
 * <h2>The rule these are chosen by</h2>
 *
 * Only what belongs to the subject. Not "everything the mod can do here" — that
 * is the panel problem again, wearing a different hat, and it is what the ring's
 * eight directions exist to make impossible rather than merely discouraged.
 *
 * <h2>What a direction has to be</h2>
 *
 * Three tests, arrived at by getting this wrong twice.
 *
 * It must be something that cannot already be done faster another way. The first
 * ring offered select, frame, move and turn, which are the left button, F, 1 and
 * 2 — four directions that each replaced something quicker.
 *
 * It must be an act rather than a door. The second ring offered eight panels, and
 * that was the subtler mistake: opening the costume panel for the character you
 * pointed at is a real thing to want, but the rail button and a click already do
 * it, so the ring became a second route to the panels instead of a way to do
 * anything to the world.
 *
 * And it should as often as possible be the world half of something block
 * programming does. Every verb that aims takes a mark, and the marks were all
 * relative until a place could be put down and named; a graph is judged by
 * watching it run, and nothing anywhere could start one. Those are the two the
 * ring earns its place with.
 *
 * "Delete" is missing from a character for a harder reason, and it is worth
 * writing down. The server will only remove a placed model, deliberately — "a
 * packet that says delete a model must not be a packet that deletes somebody's
 * horse" — so there is no character removal to call. And undoing one would not
 * be putting a number back: it would be rebuilding an entity with its costume,
 * its brain and its dialogue. That is its own piece of work, not a direction on
 * a ring.
 */
public final class WorldActions {

	private WorldActions() { }

	private static Component word(String key) {
		return Component.translatable("npc_studio.world." + key);
	}

	/** Everything on offer for what the cursor is over, in the order it is offered. */
	public static List<Menu.Entry> forSubject(Under under) {
		List<Menu.Entry> entries = new ArrayList<>();
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.level == null) return entries;

		switch (under.kind()) {
			case CHARACTER -> {
				Entity who = under.who();
				// All three of these are things only one of ours can be told to do, and
				// the cursor lands on anything with a body — a cow, a dropped apple, a
				// boat. Offering them anyway would send three packets the server refuses
				// one after another, which from this side is a ring that looks right and
				// does nothing. Better to have nothing to offer and say so.
				if (!(who instanceof com.mopicmp.npcstudio.entity.NpcEntity)) break;

				// Acts, not doors.
				//
				// This ring has been wrong twice. First it offered select, frame, move and
				// turn — four directions each replacing something quicker, and not one of
				// them about the character. Then it offered eight panels, which was worse
				// in a way that took longer to see: opening the costume panel for this
				// character is a real thing to want, but it is a thing the rail button and
				// a click already do, and putting it on the ring made the ring a second
				// way to reach what a panel does rather than a way to do anything.
				//
				// The rule that came out of it: a direction here has to be something that
				// cannot be done anywhere else, has to be about the scene, and should as
				// often as possible be the world half of something block programming does.
				// Both of these are exactly that — a graph orders "walk to post" and a
				// graph is judged by watching it run, and neither had any button at all.
				entries.add(Menu.Entry.of(Icon.PLAY, word("restart"),
					() -> order(who, "restart")));
				entries.add(Menu.Entry.of(Icon.SPOT, word("post_here"),
					() -> order(who, "post")));
				entries.add(Menu.Entry.of(Icon.EYES, word("senses"),
					() -> order(who, "senses")));
			}
			case OBJECT -> {
				Entity who = under.who();
				entries.add(Menu.Entry.of(Icon.VIEWPORT, word("frame"), () -> {
					Workspace.select(who.getId());
					WorkspaceCamera.frame(who);
				}));
				entries.add(Menu.Entry.of(Icon.MOVE_TOOL, word("move_tool"), () -> {
					Workspace.select(who.getId());
					Gizmo.tool(Gizmo.Tool.MOVE);
				}));
				entries.add(Menu.Entry.of(Icon.TURN_TOOL, word("turn_tool"), () -> {
					Workspace.select(who.getId());
					Gizmo.tool(Gizmo.Tool.ROTATE);
				}));
				// The only removal the server has, and deliberately so.
				entries.add(Menu.Entry.of(Icon.REMOVE, word("remove"), () -> remove(who)));
			}
			case CAMERA -> {
				Entity who = under.who();
				entries.add(open(Icon.CAMERA, "shot", "camera", who));
				entries.add(Menu.Entry.of(Icon.VIEWPORT, word("frame"), () -> {
					Workspace.select(who.getId());
					WorkspaceCamera.frame(who);
				}));
			}
			case BONE -> {
				entries.add(Menu.Entry.of(Icon.BODY,
					Component.translatable("npc_studio.world.bone", under.limb()),
					() -> com.mopicmp.npcstudio.client.scene.BoneHandles.pose(under.limb())));
				entries.add(Menu.Entry.of(Icon.VIEWPORT, word("frame"),
					() -> WorkspaceCamera.frame(under.who())));
			}
			case GROUND -> {
				// The place, first. It is the one act here that block programming cannot
				// do without and cannot do for itself: every verb that aims takes a mark,
				// and until this existed every mark was relative — whoever she noticed,
				// the nearest player, where she was standing. None of them can say "the
				// gate". Placing one is a world act by construction, because where it goes
				// is decided by looking at where it should go.
				entries.add(Menu.Entry.of(Icon.SPOT, word("place_spot"),
					() -> placeSpot(under.at())));
				String on = spotAt(under.block());
				if (on != null) {
					entries.add(Menu.Entry.of(Icon.REMOVE, word("drop_spot"),
						() -> com.mopicmp.npcstudio.client.map.Spots.drop(on)));
				}
				Entity chosen = Workspace.selection();
				if (chosen != null) {
					entries.add(Menu.Entry.of(Icon.BRING_HERE, word("bring_here"),
						() -> bringHere(chosen, under.at())));
				}
				entries.add(Menu.Entry.of(Icon.START, word("spawn_here"),
					() -> spawnHere(under.block(), under.at())));
			}
			case NOTHING -> { }
		}
		return entries;
	}

	/**
	 * Tells a character to do something now, so that it can be watched.
	 *
	 * Through the bench's own payload rather than a new one of ours, and that is
	 * the honest route rather than a shortcut: the bench is defined as "an action a
	 * graph will eventually order and cannot yet", which is precisely what these
	 * are. It is also where the permission check and the distance check already
	 * live, and a second copy of either would be a second thing to get wrong.
	 *
	 * Selecting first because the answer comes back about whoever is selected, and
	 * because pointing at somebody is how you say which one you mean.
	 */
	private static void order(Entity who, String what) {
		Workspace.select(who.getId());
		ClientPlayNetworking.send(
			new com.mopicmp.npcstudio.net.BenchPayloads.Ask(who.getId(), what));
	}

	/**
	 * Select this subject, then bring the panel that is the whole of what it is.
	 *
	 * One user of this is left, and it is the exception that shows the rule. A scene
	 * camera <em>is</em> its settings — lens, target, path — and there is nothing to
	 * do to one in the world except aim it, which the handles already do. For a
	 * character the same shape was wrong: eight directions that opened eight panels
	 * made the ring a slower way to reach what the rail buttons reach.
	 */
	private static Menu.Entry open(Icon icon, String word, String panel, Entity who) {
		return Menu.Entry.of(icon, word(word), () -> {
			Workspace.select(who.getId());
			WorkspaceScreen.summon(panel);
		});
	}

	/**
	 * Starts naming a place at the point clicked on.
	 *
	 * Nothing is sent until the name is typed, because a place with no name is not a
	 * place — a graph refers to these by name and by nothing else. The box that
	 * takes the letters is in the viewport rather than in a panel: the one part of
	 * placing a point that cannot be done by pointing is the word, and going to the
	 * side of the screen for it would mean looking away from what is being named.
	 */
	private static void placeSpot(Vec3 at) {
		// The block above the surface, which is where a body stands — the same reading
		// the start point takes, and for the same reason: a place inside the floor is
		// a place nobody can walk to.
		BlockPos to = BlockPos.containing(at.x, at.y + 0.5, at.z);
		com.mopicmp.npcstudio.client.map.Naming.start(to,
			com.mopicmp.npcstudio.client.map.Spots.spare(
				Component.translatable("npc_studio.spot.fresh").getString()));
	}

	/** The name of the place standing on this block, or null when there is none. */
	private static String spotAt(BlockPos block) {
		for (var spot : com.mopicmp.npcstudio.client.map.Spots.all()) {
			if (spot.at().equals(block) || spot.at().equals(block.above())) return spot.name();
		}
		return null;
	}

	/**
	 * Removes a placed model, and says why when it will not.
	 *
	 * The one entry point for both the ring and the key, so the two cannot come to
	 * disagree about what may be deleted — and the refusal is spoken, because a key
	 * that silently does nothing is a key people press twice and then report.
	 *
	 * Only a model, because that is the only removal the server has and it is
	 * deliberate: "a packet that says delete a model must not be a packet that
	 * deletes somebody's horse". There is no undo entry for the same reason there
	 * is no character removal — putting one back is rebuilding an entity, not
	 * restoring a number, and that is its own piece of work.
	 */
	public static void remove(Entity who) {
		Minecraft client = Minecraft.getInstance();
		if (who == null) return;
		if (!(who instanceof com.mopicmp.npcstudio.entity.ModelObject)) {
			if (client != null && client.player != null) {
				client.player.sendOverlayMessage(word("only_models"));
			}
			return;
		}
		ClientPlayNetworking.send(new NpcPayloads.RemoveModel(who.getId()));
	}

	/**
	 * Moves whoever is selected to the point clicked on.
	 *
	 * The same payload the handles send when a drag finishes, and the same undo
	 * entry: this is the one gesture where the handles are the slow way round —
	 * three drags along three axes to reach somewhere you can already see.
	 */
	private static void bringHere(Entity who, Vec3 to) {
		float yaw = who instanceof LivingEntity living ? living.yBodyRot : who.getYRot();
		var placing = new Doings.Placing(who.getId(),
			who.getX(), who.getY(), who.getZ(), yaw, to.x, to.y, to.z, yaw);
		if (!placing.anything()) return;
		History.did(placing);
		ClientPlayNetworking.send(new NpcPayloads.Place(who.getId(), to.x, to.y, to.z, yaw));
	}

	/**
	 * Puts the map's start where the cursor is.
	 *
	 * By the ladder this was never a panel: a point in the world has a place, and
	 * a thing with a place is pointed at. It keeps the angles the player is
	 * standing at, because the direction somebody arrives facing is chosen by
	 * looking that way, not by typing a number.
	 */
	private static void spawnHere(BlockPos block, Vec3 at) {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.level == null || client.player == null) return;

		// The block above the surface clicked on, which is where a body stands. The
		// block itself is the one the ray hit, and putting somebody inside it is how
		// a spawn point ends up underground.
		BlockPos to = BlockPos.containing(at.x, at.y + 0.5, at.z);
		LevelData.RespawnData was = client.level.getLevelData().getRespawnData();
		float yaw = client.player.getYRot();
		float pitch = client.player.getXRot();

		var spawning = new Doings.Spawning(was.pos(), was.yaw(), was.pitch(), to, yaw, pitch);
		if (spawning.anything()) History.did(spawning);
		Started.setSpawn(to, yaw, pitch);
	}
}
