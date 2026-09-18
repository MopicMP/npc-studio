package com.mopicmp.npcstudio.client.edit;

import com.mopicmp.npcstudio.client.map.Started;
import com.mopicmp.npcstudio.net.NpcPayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.storage.LevelData;

/** The things this client knows how to take back. */
public final class Doings {

	private Doings() { }

	/**
	 * How far a value may have drifted and still count as untouched.
	 *
	 * There is one, rather than an exact comparison, because the number that comes
	 * back is not the number that was sent: a position goes to the server as a
	 * double, is stored, and arrives back through the movement packets rounded to
	 * a fixed point. Comparing exactly would report every character as "moved by
	 * somebody else" and refuse every undo.
	 *
	 * A sixteenth of a block is far below anything a person places on purpose and
	 * far above what the rounding does.
	 */
	private static final double SAME = 1.0 / 16.0;

	private static final float SAME_ANGLE = 1.0f;

	/**
	 * Whether two angles are the same one, given that angles wrap.
	 *
	 * Written out because the obvious version is wrong in a way nothing catches:
	 * {@code abs(a - b) % 360} says that 179° and −179° are 358° apart, when they
	 * are two. A character facing very nearly south is the ordinary case, so the
	 * obvious version refuses to undo exactly there and nowhere else — which looks
	 * like the undo being flaky rather than like an arithmetic mistake.
	 */
	static boolean sameAngle(float a, float b) {
		return Math.abs(net.minecraft.util.Mth.wrapDegrees(a - b)) <= SAME_ANGLE;
	}

	/** Whether a point is close enough to count as untouched, on every axis. */
	static boolean samePlace(double x, double y, double z,
			double otherX, double otherY, double otherZ) {
		return Math.abs(x - otherX) <= SAME && Math.abs(y - otherY) <= SAME
			&& Math.abs(z - otherZ) <= SAME;
	}

	/**
	 * A character put somewhere, or turned.
	 *
	 * One entry for both because they are one gesture: the handles move and turn
	 * with the same grab and commit with the same {@code Place} payload, so
	 * splitting them here would invent a distinction the hand never made.
	 */
	public record Placing(int entityId,
			double fromX, double fromY, double fromZ, float fromYaw,
			double toX, double toY, double toZ, float toYaw) implements Doing {

		/** Whether this gesture actually changed anything worth an entry. */
		public boolean anything() {
			return Math.abs(toX - fromX) > 1e-6 || Math.abs(toY - fromY) > 1e-6
				|| Math.abs(toZ - fromZ) > 1e-6 || Math.abs(toYaw - fromYaw) > 1e-6;
		}

		@Override
		public Component undo() {
			return put(fromX, fromY, fromZ, fromYaw, toX, toY, toZ, toYaw);
		}

		@Override
		public Component redo() {
			return put(toX, toY, toZ, toYaw, fromX, fromY, fromZ, fromYaw);
		}

		/**
		 * Sends the character to one end, having checked it is still at the other.
		 *
		 * The check is what stops this from being a way to quietly overwrite
		 * somebody else's work. If the character is not where this entry left it,
		 * then between then and now something else moved it — another person on the
		 * map, a scene playing, a command — and putting it back would take that
		 * away without either of us seeing it happen.
		 */
		private Component put(double x, double y, double z, float yaw,
				double expectX, double expectY, double expectZ, float expectYaw) {
			Minecraft client = Minecraft.getInstance();
			if (client == null || client.level == null) {
				return Component.translatable("npc_studio.history.no_world");
			}
			Entity who = client.level.getEntity(entityId);
			if (who == null) {
				return Component.translatable("npc_studio.history.gone");
			}
			if (!samePlace(who.getX(), who.getY(), who.getZ(), expectX, expectY, expectZ)
					|| !sameAngle(yawOf(who), expectYaw)) {
				return Component.translatable("npc_studio.history.moved_since");
			}
			ClientPlayNetworking.send(new NpcPayloads.Place(entityId, x, y, z, yaw));
			return null;
		}

		@Override
		public Component what() {
			return Component.translatable(
				Math.abs(toYaw - fromYaw) > 1e-6 && sameSpot()
					? "npc_studio.history.turn" : "npc_studio.history.place");
		}

		private boolean sameSpot() {
			return Math.abs(toX - fromX) < 1e-6 && Math.abs(toY - fromY) < 1e-6
				&& Math.abs(toZ - fromZ) < 1e-6;
		}

		private static float yawOf(Entity who) {
			return who instanceof LivingEntity living ? living.yBodyRot : who.getYRot();
		}
	}

	/**
	 * The map's start moved.
	 *
	 * Included from the first day of this stack rather than later, because it is
	 * the one world action with no handle to drag back: a spawn point put in the
	 * wrong room is corrected by remembering where it used to be, and nobody
	 * remembers three numbers they never read.
	 */
	public record Spawning(BlockPos from, float fromYaw, float fromPitch,
			BlockPos to, float toYaw, float toPitch) implements Doing {

		public boolean anything() {
			return !from.equals(to) || Math.abs(toYaw - fromYaw) > 1e-6
				|| Math.abs(toPitch - fromPitch) > 1e-6;
		}

		@Override
		public Component undo() {
			return put(from, fromYaw, fromPitch, to);
		}

		@Override
		public Component redo() {
			return put(to, toYaw, toPitch, from);
		}

		private Component put(BlockPos where, float yaw, float pitch, BlockPos expect) {
			Minecraft client = Minecraft.getInstance();
			if (client == null || client.level == null) {
				return Component.translatable("npc_studio.history.no_world");
			}
			// The client is told the world spawn by the server itself, so this reads
			// the same value the marker draws rather than a copy of ours.
			LevelData.RespawnData now = client.level.getLevelData().getRespawnData();
			if (!now.pos().equals(expect)) {
				return Component.translatable("npc_studio.history.spawn_moved_since");
			}
			Started.setSpawn(where, yaw, pitch);
			return null;
		}

		@Override
		public Component what() {
			return Component.translatable("npc_studio.history.spawn");
		}
	}
}
