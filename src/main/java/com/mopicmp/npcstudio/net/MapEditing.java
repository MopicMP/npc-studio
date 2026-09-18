package com.mopicmp.npcstudio.net;

import com.mopicmp.npcstudio.map.MapStart;
import com.mopicmp.npcstudio.map.WorldStart;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelData;

/**
 * The server's side of a map's start: the spawn point and the settings.
 *
 * Same rule as scenes — the checks live here rather than in the panel, because
 * the panel is the half that can be replaced by anything at all.
 */
public final class MapEditing {

	private MapEditing() { }

	private static boolean allowed(ServerPlayer player) {
		if (player.isCreative()) return true;
		player.sendOverlayMessage(Component.translatable("npc_studio.start.creative_only"));
		return false;
	}

	/** Everything a client wants on arrival: what to set, and where the spawn is. */
	public static void send(ServerPlayer player) {
		if (!(player.level() instanceof ServerLevel level)) return;
		ServerPlayNetworking.send(player, new MapPayloads.Start(WorldStart.of(level).start()));
		report(player);
	}

	/**
	 * Where the spawn is, and where a player would actually land on it.
	 *
	 * <h2>Why the landing spot is asked of the game rather than worked out</h2>
	 *
	 * {@link PlayerSpawnFinder#findSpawn} is the method that places a real player,
	 * so asking it is the only way to get an answer that cannot be wrong in a way
	 * the real thing is not. Reimplementing the rules here would produce a marker
	 * that agrees with our reading of them, which is exactly the sort of test that
	 * proves nothing.
	 *
	 * It is a search over chunks and it is not free, so it happens when somebody
	 * asks — opening the panel, moving the point — and never on a timer.
	 */
	public static void report(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (server == null) return;

		LevelData.RespawnData spawn = server.overworld().getRespawnData();
		ServerLevel level = server.getLevel(spawn.dimension());
		// A spawn in a dimension this world no longer has. Not an error worth a
		// message — say where it points and that nothing was found for it.
		if (level == null) {
			ServerPlayNetworking.send(player, new MapPayloads.Spawn(
				spawn.pos(), spawn.yaw(), spawn.pitch(),
				net.minecraft.world.phys.Vec3.atBottomCenterOf(spawn.pos()), -1, false));
			return;
		}

		boolean adventure = server.getWorldData().getGameType() == GameType.ADVENTURE;
		int radius = Math.max(0, level.getGameRules().get(GameRules.RESPAWN_RADIUS));

		PlayerSpawnFinder.findSpawn(level, spawn.pos()).thenAccept(landing ->
			server.execute(() -> {
				if (player.hasDisconnected()) return;
				ServerPlayNetworking.send(player, new MapPayloads.Spawn(
					spawn.pos(), spawn.yaw(), spawn.pitch(), landing, radius, adventure));
			}));
	}

	/**
	 * Puts the spawn where somebody asked, in the world's own level data.
	 *
	 * Written to vanilla rather than to a record of ours, so the map starts in the
	 * right place when it is opened without this mod. The server broadcasts the
	 * change to every client by itself — {@code MinecraftServer.setRespawnData}
	 * sends {@code ClientboundSetDefaultSpawnPositionPacket} — so the marker needs
	 * no sync of ours to follow it.
	 *
	 * The two angles are wrapped and clamped exactly as {@code /setworldspawn}
	 * does. A yaw of a thousand degrees is a number a client can send and the game
	 * will store; the command's own handling is the definition of what is meant.
	 */
	public static void setSpawn(ServerPlayer player, MapPayloads.SetSpawn sent) {
		if (!allowed(player)) return;
		if (!(player.level() instanceof ServerLevel level)) return;

		BlockPos pos = sent.pos();
		if (!level.isInWorldBounds(pos)) {
			player.sendOverlayMessage(Component.translatable("npc_studio.start.spawn_outside"));
			return;
		}

		level.setRespawnData(LevelData.RespawnData.of(level.dimension(), pos,
			Mth.wrapDegrees(sent.yaw()), Mth.clamp(sent.pitch(), -90.0f, 90.0f)));
		report(player);
	}

	/**
	 * A whole new set of start settings from the person editing them.
	 *
	 * Sent on to everybody, not only written down. Somebody standing on the map
	 * while it is being built should be looking at the settings as they are now:
	 * the point of editing brightness beside the viewport is seeing the brightness
	 * change, and a change only the author sees is a change nobody can judge.
	 */
	public static void change(ServerPlayer player, MapStart wanted) {
		if (!allowed(player)) return;
		if (!(player.level() instanceof ServerLevel level)) return;

		WorldStart.of(level).set(wanted);
		MinecraftServer server = player.level().getServer();
		if (server == null) return;
		for (ServerPlayer other : server.getPlayerList().getPlayers()) {
			ServerPlayNetworking.send(other, new MapPayloads.Start(wanted));
		}
	}
}
