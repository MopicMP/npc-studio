package com.mopicmp.npcstudio.client.scene;

import com.mopicmp.npcstudio.client.model.Gizmo;
import com.mopicmp.npcstudio.client.workspace.Workspace;
import com.mopicmp.npcstudio.net.NpcPayloads;
import com.mopicmp.npcstudio.scene.Channels;
import com.mopicmp.npcstudio.scene.Key;
import com.mopicmp.npcstudio.scene.Scene;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Three arrows and a ring on a character, to stand it where it belongs.
 *
 * <h2>What these move, and why it is not the timeline</h2>
 *
 * The character itself. Putting somebody on the right step of a gangway, or
 * facing the wheel rather than away from it, is something done once and before
 * there is any animation to speak of — and the first version of these handles
 * made it impossible to do at all without opening a scene, placing a cursor and
 * laying down a key. That is a puzzle rather than a tool, and it was reported as
 * one.
 *
 * So a drag moves the entity, which is where a character actually stands and
 * what a world saves. Animating a move is still a key, and the key button in the
 * pose panel writes down wherever the character has ended up — which is the right
 * way round: place first, animate afterwards, and never the reverse.
 *
 * <h2>Why they are drawn where the character is drawn</h2>
 *
 * Because those are two different places. A scene changes the picture and leaves
 * the entity standing where it was, so handles placed from the entity sat behind
 * at the old position the moment anything was keyed — and the arrows appeared not
 * to follow what they had just moved. See {@link Placed}.
 *
 * <h2>Why a drag is measured on the screen and not in the world</h2>
 *
 * The obvious way is to cast a ray and meet the axis, and it is the wrong way:
 * the ray and the axis nearly never meet, so it becomes a nearest-approach that
 * goes unstable exactly when the axis points at the camera — which is when
 * somebody is most likely to be pulling it. So the axis is projected onto the
 * screen and the mouse is measured along that. An axis pointing at the camera
 * projects to nothing and refuses to move, which is the honest answer to "how far
 * along a line you cannot see did you mean to drag".
 *
 * The measuring stick is frozen when the button goes down, because the arrow
 * travels with what it is moving: measuring each small step against a direction
 * that is itself moving is a feedback loop, and it shows up as the thing creeping
 * away during a long pull. Both lessons are the modelling gizmo's.
 */
public final class MoveHandles {

	/** How long an arrow is, as a share of half the frame's height. The gizmo's own. */
	private static final double SPAN = 0.17;

	/** How near the mouse must come, in interface pixels. The gizmo's own. */
	private static final double GRAB = 9;

	/** How many straight pieces the turning ring is walked as. */
	private static final int ROUND = 32;

	/** Which axis the ring is: after the three arrows. */
	private static final int TURN = 3;

	private static final int[] COLOUR = { 0xFFFF5555, 0xFF55DD66, 0xFF5599FF, 0xFFCE93D8 };
	private static final int LIT = 0xFFFFD54A;

	/** How long the head of an arrow is and how wide, in interface pixels. */
	private static final int HEAD = 10;
	private static final int WING = 4;

	private MoveHandles() { }

	/** One arrow: where it starts and where it points. */
	private record Arm(int axis, Vec3 from, Vec3 to) { }

	/**
	 * Whether the handles are showing at all.
	 *
	 * Not while a bone is chosen. The rings on a limb and the arrows on the whole
	 * character are two different subjects, and showing both means the screen has
	 * two answers to "what does a drag do here" — which is what was reported. The
	 * way back is the first row of the bone list, which chooses no bone.
	 */
	private static boolean showing() {
		if (!com.mopicmp.npcstudio.client.workspace.WorkspaceScreen.embedded()) return false;
		if (!BoneHandles.bone().isEmpty()) return false;
		var chosen = Workspace.selection();
		return chosen instanceof net.minecraft.world.entity.LivingEntity
			|| SceneCameras.isCamera(chosen);
	}

	/** Where the handles sit and how big they are drawn. */
	private static Vec3 middle() {
		Entity who = Workspace.selection();
		Placed placed = Placed.drawn(who);
		return placed == null ? null : placed.at();
	}

	/**
	 * Whether the scene already says where this participant stands.
	 *
	 * The one thing that decides what a drag means, and it decides it from the
	 * document rather than from a mode anybody has to remember being in. Nothing
	 * keyed: the arrows move the character, which is what placing one is and what
	 * was missing. Something keyed: the character is animated, moving the entity
	 * underneath would be invisible, so the arrows go on animating it.
	 *
	 * The way from the first to the second is the key button in the pose panel,
	 * which writes down wherever the character has been stood.
	 */
	private static boolean animated() {
		Scene scene = Playing.scene();
		String role = BoneHandles.role();
		if (scene == null || role.isEmpty()) return false;
		// A camera is never anywhere but in the document. There is no entity on any
		// server to move, and moving the client's copy would be a shot that looked
		// right until the scene was reopened.
		if (SceneCameras.isCamera(Workspace.selection())) return true;
		for (var track : scene.tracksOf(role)) {
			String channel = track.channel();
			if (channel.equals(Channels.X) || channel.equals(Channels.Y)
				|| channel.equals(Channels.Z) || channel.equals(Channels.YAW)) {
				return true;
			}
		}
		return false;
	}

	private static String channelOf(int axis) {
		return switch (axis) {
			case 0 -> Channels.X;
			case 1 -> Channels.Y;
			default -> Channels.Z;
		};
	}

	private static void key(String channel, float value) {
		Scene scene = Playing.scene();
		String role = BoneHandles.role();
		if (scene == null || role.isEmpty()) return;
		Scenes.keep(Playing.openName(),
			scene.keyed(role, channel, Key.at(Playing.head().tick(), value)));
	}

	private static double length(Vec3 middle) {
		var camera = Minecraft.getInstance().gameRenderer.mainCamera();
		double away = camera.position().distanceTo(middle);
		return away * Math.tan(Math.toRadians(camera.getFov()) / 2) * SPAN * Gizmo.sizing();
	}

	/**
	 * The three arrows, along the world's own axes.
	 *
	 * The world's rather than the character's, because what they edit is a place in
	 * the world. An arrow that pointed along the character's own facing would swing
	 * round every time it was turned, and would be editing a pair of numbers rather
	 * than the one it is drawn on.
	 */
	private static java.util.List<Arm> arms() {
		if (!showing()) return java.util.List.of();
		Vec3 foot = middle();
		if (foot == null) return java.util.List.of();
		double length = length(foot);
		if (length <= 0) return java.util.List.of();

		return java.util.List.of(
			new Arm(0, foot, foot.add(length, 0, 0)),
			new Arm(1, foot, foot.add(0, length, 0)),
			new Arm(2, foot, foot.add(0, 0, length)));
	}

	/** The turning ring: flat on the ground, round the character's own feet. */
	private static Vec3[] ring() {
		if (!showing()) return null;
		Vec3 foot = middle();
		if (foot == null) return null;
		double radius = length(foot) * 0.8;
		if (radius <= 0) return null;

		Vec3[] round = new Vec3[ROUND];
		for (int step = 0; step < ROUND; step++) {
			double angle = step * 2 * Math.PI / ROUND;
			round[step] = foot.add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
		}
		return round;
	}

	// ------------------------------------------------------------------- the drag

	private static int held = -1;
	private static int hovered = -1;
	private static double fromX;
	private static double fromY;
	private static double dirX;
	private static double dirY;
	private static double perPixel;
	private static Vec3 began;
	private static double lastAngle;
	private static float beganYaw;
	private static double spun;
	private static boolean moved;
	private static boolean keying;

	public static boolean holding() {
		return held >= 0;
	}

	/** Notices which handle the mouse is over. Display coordinates. */
	public static void hover(double mouseX, double mouseY) {
		hovered = held >= 0 ? held : nearest(mouseX, mouseY);
	}

	private static int nearest(double mouseX, double mouseY) {
		int closest = -1;
		double best = GRAB;
		for (Arm arm : arms()) {
			double[] from = Gizmo.onScreen(arm.from());
			double[] to = Gizmo.onScreen(arm.to());
			if (from == null || to == null) continue;
			double near = toSegment(mouseX, mouseY, from, to);
			if (near >= best) continue;
			best = near;
			closest = arm.axis();
		}

		Vec3[] round = ring();
		if (round != null) {
			double near = toRing(round, mouseX, mouseY);
			if (near < best) closest = TURN;
		}
		return closest;
	}

	/**
	 * Takes hold of a handle. True when one was under the mouse.
	 *
	 * Everything the drag will be measured against is frozen here: where the mouse
	 * was, which way the handle runs on the screen, how much world a screen pixel
	 * is worth along it, and what the character's place already was.
	 */
	public static boolean grab(double mouseX, double mouseY) {
		int axis = nearest(mouseX, mouseY);
		if (axis < 0) return false;

		Entity who = Workspace.selection();
		if (who == null) return false;
		keying = animated();
		// Where the drag starts from is where the handle is drawn, which for an
		// animated participant is the scene's answer and not the entity's.
		Placed start = keying ? Placed.atCursor(who, BoneHandles.role()) : Placed.of(who);
		began = start.at();
		beganYaw = start.yaw();
		moved = false;

		if (axis == TURN) {
			double[] centre = Gizmo.onScreen(middle());
			if (centre == null) return false;
			// Too near the middle and the angle is noise: a hand that shakes by a
			// pixel would spin the character.
			if (Math.hypot(mouseX - centre[0], mouseY - centre[1]) < 6) return false;
			held = TURN;
			spun = 0;
			lastAngle = Math.toDegrees(Math.atan2(mouseY - centre[1], mouseX - centre[0]));
			return true;
		}

		for (Arm arm : arms()) {
			if (arm.axis() != axis) continue;
			double[] from = Gizmo.onScreen(arm.from());
			double[] to = Gizmo.onScreen(arm.to());
			if (from == null || to == null) return false;

			double acrossX = to[0] - from[0];
			double acrossY = to[1] - from[1];
			double onScreen = Math.hypot(acrossX, acrossY);
			// An arrow pointing at the camera is a dot, and a dot has no direction to
			// drag along. Refusing is right: any answer would be invented.
			if (onScreen < 1) return false;

			held = axis;
			fromX = mouseX;
			fromY = mouseY;
			dirX = acrossX / onScreen;
			dirY = acrossY / onScreen;
			perPixel = arm.from().distanceTo(arm.to()) / onScreen;
			return true;
		}
		return false;
	}

	/**
	 * Moves or turns the character by however far the mouse has gone.
	 *
	 * Put on this client's copy of the entity at once and told to the server when
	 * the button comes up. A packet a frame would be a packet a frame; waiting for
	 * one to come back before the character moves would be a handle that lags a
	 * tenth of a second behind the hand holding it.
	 */
	public static void drag(double mouseX, double mouseY) {
		if (held < 0) return;
		Entity who = Workspace.selection();
		if (who == null) return;
		moved = true;

		if (held == TURN) {
			double[] centre = Gizmo.onScreen(middle());
			if (centre == null) return;
			double now = Math.toDegrees(Math.atan2(mouseY - centre[1], mouseX - centre[0]));
			double step = now - lastAngle;
			while (step > 180) step -= 360;
			while (step < -180) step += 360;
			lastAngle = now;
			spun += step;
			if (keying) key(Channels.YAW, beganYaw + (float) spun);
			else turn(who, beganYaw + (float) spun);
			return;
		}

		double along = (mouseX - fromX) * dirX + (mouseY - fromY) * dirY;
		double by = along * perPixel;
		Vec3 to = switch (held) {
			case 0 -> began.add(by, 0, 0);
			case 1 -> began.add(0, by, 0);
			default -> began.add(0, 0, by);
		};
		if (keying) {
			key(channelOf(held), (float) switch (held) {
				case 0 -> to.x;
				case 1 -> to.y;
				default -> to.z;
			});
			return;
		}
		who.setPos(to.x, to.y, to.z);
		who.xOld = to.x;
		who.yOld = to.y;
		who.zOld = to.z;
	}

	/** Lets go, and tells the server where the character ended up. */
	public static void release() {
		if (held < 0) return;
		held = -1;
		Entity who = Workspace.selection();
		// Only when the entity itself was moved. A drag that keyed the scene has
		// already been written down by the scene's own saving, and telling the server
		// to teleport a character that never moved would undo the animation.
		if (moved && !keying && who != null) {
			ClientPlayNetworking.send(new NpcPayloads.Place(who.getId(),
				who.getX(), who.getY(), who.getZ(), yawOf(who)));

			// One entry per gesture, made here rather than while dragging: this is the
			// moment both ends are known, and `began` was already being kept and
			// thrown away. A record per frame would make undo mean "back one pixel".
			//
			// Not for a drag that keyed the scene. That went into the document, which
			// saves itself and has its own idea of what a step back is; telling the
			// server to teleport the character instead would undo the animation.
			var placing = new com.mopicmp.npcstudio.client.edit.Doings.Placing(who.getId(),
				began.x, began.y, began.z, beganYaw,
				who.getX(), who.getY(), who.getZ(), yawOf(who));
			if (placing.anything()) com.mopicmp.npcstudio.client.edit.History.did(placing);
		}
		moved = false;
	}

	/**
	 * Turns the character, body and head together.
	 *
	 * A body turned round with the head left behind is not a character looking over
	 * its shoulder — it is one that will snap straight the next time anything else
	 * touches it.
	 */
	private static void turn(Entity who, float yaw) {
		who.setYRot(yaw);
		who.yRotO = yaw;
		if (who instanceof LivingEntity living) {
			living.yBodyRot = yaw;
			living.yBodyRotO = yaw;
			living.yHeadRot = yaw;
			living.yHeadRotO = yaw;
		}
	}

	private static float yawOf(Entity who) {
		return who instanceof LivingEntity living ? living.yBodyRot : who.getYRot();
	}

	// ---------------------------------------------------------------- the drawing

	/** The handles, drawn over the world so that one inside a character is still one. */
	public static void draw(GuiGraphicsExtractor graphics, int originX, int originY) {
		int lit = held >= 0 ? held : hovered;

		Vec3[] round = ring();
		if (round != null) {
			int colour = lit == TURN ? LIT : COLOUR[TURN];
			for (int step = 0; step < round.length; step++) {
				double[] from = at(round[step], originX, originY);
				double[] to = at(round[(step + 1) % round.length], originX, originY);
				if (from != null && to != null) segment(graphics, from, to, colour);
			}
		}

		for (Arm arm : arms()) {
			double[] from = at(arm.from(), originX, originY);
			double[] to = at(arm.to(), originX, originY);
			if (from == null || to == null) continue;
			int colour = arm.axis() == lit ? LIT : COLOUR[arm.axis()];
			segment(graphics, from, to, colour);
			head(graphics, from, to, colour);
		}
	}

	/**
	 * The point of an arrow: a triangle, and that is not decoration.
	 *
	 * It was a square, and a square on the end of a stick is what every editor
	 * anybody has used draws for <em>resizing</em> — so the arrows read as size
	 * handles and were reported as such before they were ever dragged. A shape is
	 * the whole of what a flat handle can say about what it does.
	 */
	private static void head(GuiGraphicsExtractor graphics, double[] from, double[] to,
			int colour) {
		double dx = to[0] - from[0];
		double dy = to[1] - from[1];
		double length = Math.hypot(dx, dy);
		if (length < 1) return;
		double alongX = dx / length;
		double alongY = dy / length;

		// Filled a row at a time from the point backwards, widening as it goes.
		for (int step = 0; step <= HEAD; step++) {
			double half = WING * (double) step / HEAD;
			double x = to[0] - alongX * step;
			double y = to[1] - alongY * step;
			double acrossX = -alongY * half;
			double acrossY = alongX * half;
			segment(graphics, new double[] { x - acrossX, y - acrossY },
				new double[] { x + acrossX, y + acrossY }, colour);
		}
	}

	private static double[] at(Vec3 point, int originX, int originY) {
		return Wireframe.at(point, originX, originY);
	}

	/** A dark line with a bright one on top, so it reads over anything. */
	private static void segment(GuiGraphicsExtractor graphics, double[] from, double[] to,
			int colour) {
		Wireframe.segment(graphics, from, to, colour);
	}

	private static double toRing(Vec3[] round, double mouseX, double mouseY) {
		double nearest = Double.MAX_VALUE;
		for (int step = 0; step < round.length; step++) {
			double[] from = Gizmo.onScreen(round[step]);
			double[] to = Gizmo.onScreen(round[(step + 1) % round.length]);
			if (from == null || to == null) continue;
			nearest = Math.min(nearest, toSegment(mouseX, mouseY, from, to));
		}
		return nearest;
	}

	private static double toSegment(double x, double y, double[] from, double[] to) {
		double dx = to[0] - from[0];
		double dy = to[1] - from[1];
		double lengthSquared = dx * dx + dy * dy;
		if (lengthSquared < 1e-6) return Math.hypot(x - from[0], y - from[1]);
		double t = Math.clamp(((x - from[0]) * dx + (y - from[1]) * dy) / lengthSquared, 0, 1);
		return Math.hypot(x - (from[0] + dx * t), y - (from[1] + dy * t));
	}
}
