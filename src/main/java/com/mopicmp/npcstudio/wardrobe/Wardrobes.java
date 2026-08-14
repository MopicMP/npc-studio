package com.mopicmp.npcstudio.wardrobe;

import java.util.ArrayList;
import java.util.List;

import com.mopicmp.npcstudio.entity.NpcEntity;
import com.mopicmp.npcstudio.entity.Outfit;
import com.mopicmp.npcstudio.net.WardrobePayloads;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The server's side of the wardrobe: one library, and the rules for touching it.
 *
 * The library is the world's, so it is opened once when the world opens and
 * lives as long as it does. Everything that changes it comes through here, and
 * every check that matters is here rather than in the screen — the screen is the
 * half that can be replaced.
 */
public final class Wardrobes {

	private static SkinLibrary library;

	private Wardrobes() { }

	public static void open(MinecraftServer server) {
		library = SkinLibrary.of(server);
	}

	public static void close() {
		library = null;
	}

	public static SkinLibrary library() {
		return library;
	}

	/**
	 * Whether somebody may rearrange the world's costumes.
	 *
	 * The same rule as everywhere else in this mod: building is a creative-mode
	 * activity. Worth being firm about here in particular, because this is shared
	 * property — one person's mistake is everybody's afternoon.
	 */
	private static boolean allowed(ServerPlayer player) {
		if (library == null) {
			// Should never happen, and if it does the player deserves to be told
			// rather than to press a button that quietly does nothing for ever.
			player.sendOverlayMessage(Component.literal("The wardrobe did not open with this world."));
			return false;
		}
		if (player.isCreative()) return true;
		player.sendOverlayMessage(Component.literal("The wardrobe is a creative-mode thing."));
		return false;
	}

	public static void send(ServerPlayer player) {
		if (library == null) return;
		List<WardrobePayloads.Costume> costumes = new ArrayList<>();
		for (SkinLibrary.Entry entry : library.entries()) {
			costumes.add(describe(entry));
		}
		ServerPlayNetworking.send(player, new WardrobePayloads.Library(costumes));
		ServerPlayNetworking.send(player, new WardrobePayloads.Versions(library.history()));
	}

	/**
	 * Describes a costume without opening its picture.
	 *
	 * The width and whether it has eyes are read from the file's header, which is
	 * a few dozen bytes rather than a few thousand — so a screen can say "blinking
	 * will work on this one" before anything has been downloaded.
	 */
	private static WardrobePayloads.Costume describe(SkinLibrary.Entry entry) {
		byte[] png = library.picture(entry.fingerprint());
		int width = png == null ? 64 : SkinBytes.widthOf(png);
		return new WardrobePayloads.Costume(entry.id(), entry.label(), entry.category(),
			entry.group(), entry.fingerprint(), width, png != null);
	}

	public static void sendPicture(ServerPlayer player, String fingerprint) {
		if (library == null) return;
		// Only ever a name we already know. A fingerprint from the network is not
		// allowed to become a path — that is how a request for a costume turns
		// into a request for somebody's server files.
		boolean known = library.entries().stream()
			.anyMatch(entry -> entry.fingerprint().equals(fingerprint));
		if (!known) return;

		byte[] png = library.picture(fingerprint);
		if (png == null) return;

		int count = Math.max(1, (png.length + WardrobePayloads.PART - 1) / WardrobePayloads.PART);
		for (int i = 0; i < count; i++) {
			int from = i * WardrobePayloads.PART;
			int to = Math.min(png.length, from + WardrobePayloads.PART);
			ServerPlayNetworking.send(player, new WardrobePayloads.Picture(
				fingerprint, i, count, java.util.Arrays.copyOfRange(png, from, to)));
		}
	}

	/**
	 * A piece of a skin somebody is uploading.
	 *
	 * <h2>What is guarded here and why</h2>
	 *
	 * Everything about this arrives from a client, so every number in it is a
	 * stranger's choice. The piece count decides how much is kept on the heap, the
	 * index decides where it is written, and the transfer's name decides which heap
	 * it goes on — so all three are checked before any of them is used, and the
	 * whole is refused if it would come to more than a skin ever is.
	 *
	 * One transfer at a time per player. That is not a limitation anybody will
	 * notice — the screen sends the pieces of one file before starting the next —
	 * and it means a client cannot fill a server's memory by starting a thousand
	 * uploads and finishing none.
	 */
	public static void addPart(ServerPlayer player, WardrobePayloads.AddPart part) {
		if (!allowed(player)) return;

		int count = part.count();
		if (count < 1 || (long) count * WardrobePayloads.PART > SkinBytes.LARGEST + WardrobePayloads.PART) {
			player.sendOverlayMessage(Component.literal("that file is too big to be a skin"));
			return;
		}
		if (part.index() < 0 || part.index() >= count) return;

		Upload upload = uploads.get(player.getUUID());
		if (upload == null || !upload.name.equals(part.upload()) || upload.pieces.length != count) {
			// A new name means a new file: whatever was half sent before it is
			// dropped rather than merged with it.
			upload = new Upload(part.upload(), count);
			uploads.put(player.getUUID(), upload);
		}
		upload.pieces[part.index()] = part.part();

		int have = 0;
		int size = 0;
		for (byte[] piece : upload.pieces) {
			if (piece == null) continue;
			have++;
			size += piece.length;
		}
		if (have < count) return;

		uploads.remove(player.getUUID());
		byte[] png = new byte[size];
		int at = 0;
		for (byte[] piece : upload.pieces) {
			System.arraycopy(piece, 0, png, at, piece.length);
			at += piece.length;
		}

		String refused = SkinBytes.refuse(png);
		if (refused != null) {
			player.sendOverlayMessage(Component.literal(refused));
			return;
		}
		library.add(part.label().isBlank() ? "costume" : part.label().trim(),
			part.category().trim(), part.group().trim(), png);
		send(player);
	}

	/** A skin arriving a piece at a time, and who is sending it. */
	private static final class Upload {
		private final String name;
		private final byte[][] pieces;

		private Upload(String name, int count) {
			this.name = name;
			this.pieces = new byte[count][];
		}
	}

	private static final java.util.Map<java.util.UUID, Upload> uploads =
		new java.util.concurrent.ConcurrentHashMap<>();

	/** Dropped when a player leaves, so a transfer nobody finished is not kept. */
	public static void forget(ServerPlayer player) {
		uploads.remove(player.getUUID());
	}

	public static void edit(ServerPlayer player, WardrobePayloads.Edit order) {
		if (!allowed(player)) return;

		switch (order.verb()) {
			case ADD -> {
				String refused = SkinBytes.refuse(order.pixels());
				if (refused != null) {
					player.sendOverlayMessage(Component.literal(refused));
					return;
				}
				library.add(order.label().isBlank() ? "costume" : order.label().trim(),
					order.category().trim(), order.group().trim(), order.pixels());
			}
			case REMOVE -> library.remove(order.ids());
			case COPY -> library.copy(order.ids(), order.category().trim(), order.group().trim());
			case REFILE -> library.refile(order.ids(), order.category().trim(), order.group().trim());
			case RENAME -> {
				if (!order.ids().isEmpty()) library.rename(order.ids().get(0), order.label().trim());
			}
			case DROP_CATEGORY -> library.dropCategory(order.category().trim(), order.group().trim());
			case RESTORE -> {
				if (!library.restore(order.label())) {
					player.sendOverlayMessage(Component.literal("No such version."));
				}
			}
		}
		send(player);
	}

	/**
	 * Writes a character's current build back onto the costume it is wearing.
	 *
	 * The costume is the one the server remembers dressing it in, never one the
	 * client names. That is the same rule as everywhere else here: a name off the
	 * wire is somebody's choice, and this one decides which shared record twenty
	 * other characters will inherit.
	 */
	public static void keepShape(ServerPlayer player, NpcEntity npc) {
		if (!allowed(player)) return;
		if (npc.costumeId().isEmpty()) {
			player.sendOverlayMessage(Component.literal("This character is not wearing a costume."));
			return;
		}
		library.reshape(npc.costumeId(), npc.bodyShape());
		send(player);
	}

	/** Puts a character's build back to whatever its costume says. */
	public static void restoreShape(ServerPlayer player, NpcEntity npc) {
		if (!allowed(player)) return;
		library.find(npc.costumeId())
			.ifPresent(entry -> npc.setBodyShape(entry.shape()));
	}

	/** Dresses a character in something the library holds. */
	/**
	 * Records where a costume's face keeps its eyes.
	 *
	 * Kept on the costume rather than on whoever marked it, so that marking a face
	 * once serves every character who ever wears it — and so the answer lands in
	 * the world's own {@code wardrobe.json}, where it can be read back and
	 * measured against.
	 */
	public static void markEyes(ServerPlayer player,
			com.mopicmp.npcstudio.net.WardrobePayloads.MarkEyes payload) {
		if (!allowed(player)) return;
		if (library.find(payload.costumeId()).isEmpty()) {
			player.sendOverlayMessage(Component.literal("No such costume in this world's wardrobe."));
			return;
		}
		library.relook(payload.costumeId(),
			com.mopicmp.npcstudio.entity.FaceMask.decode(payload.mask(), payload.byHand()));
		send(player);
	}

	public static void wear(ServerPlayer player, int entityId, String costumeId) {
		if (!allowed(player)) return;
		if (!(player.level().getEntity(entityId) instanceof NpcEntity npc)) {
			player.sendOverlayMessage(Component.literal("That character is not here any more."));
			return;
		}
		if (npc.distanceToSqr(player) > 64.0) {
			player.sendOverlayMessage(Component.literal("That NPC is not here."));
			return;
		}

		var found = library.find(costumeId);
		if (found.isEmpty()) {
			// Silent before, which is the worst way to fail: the button appears to
			// work, nothing happens, and there is nothing for anybody to report.
			player.sendOverlayMessage(Component.literal("No such costume in this world's wardrobe."));
			return;
		}
		found.ifPresent(entry -> {
			byte[] png = library.picture(entry.fingerprint());
			if (png == null) {
				player.sendOverlayMessage(Component.literal("That costume's picture is missing."));
				return;
			}
			// The face comes with the clothes, exactly as the build does — and it has
			// to travel, because a client that is only watching has no wardrobe to
			// look anything up in and would otherwise blink on its own guess.
			npc.dressIn(Outfit.picture(entry.label(), png).looking(entry.eyes()));
			// The build comes with the clothes. Copied rather than linked: every
			// client that can see the character has to draw this, and only the ones
			// editing have the wardrobe to look it up in.
			npc.setBodyShape(entry.shape());
			npc.setCostumeId(entry.id());
			// Everyone who can see the character needs the picture, not only whoever
			// chose it.
			for (ServerPlayer nearby : player.level().getServer().getPlayerList().getPlayers()) {
				if (nearby.level() == npc.level() && nearby.distanceToSqr(npc) < 128 * 128) {
					ServerPlayNetworking.send(nearby,
						new com.mopicmp.npcstudio.net.NpcPayloads.SkinFor(entityId, png));
				}
			}
		});
	}
}
