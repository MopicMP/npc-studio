package com.mopicmp.npcstudio.client.workspace.panel;

import com.mopicmp.npcstudio.client.workspace.Under;
import com.mopicmp.npcstudio.client.workspace.Workspace;
import com.mopicmp.npcstudio.client.workspace.WorkspaceCamera;
import com.mopicmp.npcstudio.client.workspace.WorkspacePanel;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The world, and the hole in the interface that lets it be seen.
 *
 * This panel paints almost nothing. The world was drawn before any of the
 * interface was, so leaving a rectangle unpainted is the whole of how the
 * viewport works — there is no render of the world into a texture and no second
 * scene kept in step with the first. What you are looking at is the world
 * everyone else is standing in.
 *
 * The mouse is never captured. That was decided rather than inherited: panels
 * need the cursor, and a mode where the cursor disappears is a mode you can be
 * in by accident.
 *
 * So: left selects, right looks about and flies with the letter keys, middle
 * orbits whatever is selected, shift and middle pans, and the wheel comes in
 * and out. Right and middle are different on purpose — orbiting is for judging
 * one character and looking about is for crossing a scene, and a camera that
 * only orbits leaves you circling whatever you last clicked on.
 */
public class ViewportPanel extends WorkspacePanel {

	private static final int GLFW_LEFT = 0;
	private static final int GLFW_RIGHT = 1;
	private static final int GLFW_MIDDLE = 2;
	private static final int GLFW_SHIFT = 0x0001;

	/** The keys that fly the camera, as the window reports them. */
	private static final int KEY_W = 87;
	private static final int KEY_A = 65;
	private static final int KEY_S = 83;
	private static final int KEY_D = 68;
	private static final int KEY_Q = 81;
	private static final int KEY_E = 69;
	private static final int KEY_SPACE = 32;
	private static final int KEY_LEFT_SHIFT = 340;
	private static final int KEY_LEFT_CONTROL = 341;
	private static final int KEY_F = 70;
	private static final int KEY_1 = 49;
	private static final int KEY_2 = 50;
	private static final int KEY_DELETE = 261;
	private static final int KEY_RIGHT_SHIFT = 344;
	private static final int KEY_RIGHT_CONTROL = 345;

	/** How far a pick ray reaches. Beyond this nothing is close enough to mean. */
	private static final double REACH = 96;

	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;

	private boolean orbiting;
	private boolean panning;
	private boolean looking;

	/** Whether a right drag is aiming the scene's camera rather than the workspace's. */
	private boolean aiming;

	@Override
	public String id() {
		return "viewport";
	}

	@Override
	public boolean transparent() {
		return true;
	}

	/**
	 * The one panel without a cross, because it is the world rather than a panel.
	 *
	 * Everything else is summoned over this and dismissed again. Shutting this
	 * would leave the workspace drawing nothing at all — which is what the hide
	 * button already does, deliberately and reversibly.
	 */
	@Override
	public boolean closable() {
		return false;
	}

	@Override
	public int minimumWidth() {
		return 160;
	}

	@Override
	public int minimumHeight() {
		return 120;
	}

	@Override
	protected void over(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		// Nothing of ours while the film is being written. The frame is this panel's
		// own rectangle now, so a ring, an arrow or an outline drawn here is a ring in
		// the film — which is exactly what hiding the panels used to prevent, and what
		// the crop has to prevent in its place.
		if (com.mopicmp.npcstudio.client.scene.Capture.running()) return;
		// Over the world and over this panel's own readout, so a ring inside a
		// character is still a ring. The mouse arrives in panel coordinates and the
		// handles think in the display's, which is where the origin comes in.
		com.mopicmp.npcstudio.client.scene.BoneHandles.hover(
			originX() + mouseX, originY() + mouseY);
		com.mopicmp.npcstudio.client.scene.MoveHandles.hover(
			originX() + mouseX, originY() + mouseY);

		// Which limb a click would take, outlined before it is taken. Not while a
		// ring is being held: mid-turn the answer changes under the mouse and the
		// outline would flicker between the bone being turned and whatever swings
		// past it.
		String role = com.mopicmp.npcstudio.client.scene.BoneHandles.role();
		Entity who = Workspace.selection();
		underMouse = com.mopicmp.npcstudio.client.scene.BoneHandles.holding()
			? "" : boneAt(mouseX, mouseY);
		if (!role.isEmpty()) {
			String chosen = com.mopicmp.npcstudio.client.scene.BoneHandles.bone();
			if (!chosen.isEmpty() && !chosen.equals(underMouse)) {
				com.mopicmp.npcstudio.client.scene.BoneHandles.outline(graphics,
					originX(), originY(), who, role, chosen,
					com.mopicmp.npcstudio.client.scene.BoneHandles.CHOSEN);
			}
			com.mopicmp.npcstudio.client.scene.BoneHandles.outline(graphics,
				originX(), originY(), who, role, underMouse,
				com.mopicmp.npcstudio.client.scene.BoneHandles.UNDER_MOUSE);
		}
		// Under the handles, because it is the subject and they are the instrument.
		// A camera is the one participant with no body of its own, so without this it
		// is an invisible point that can only be found by name — and an arrow drawn on
		// nothing is a handle for something you cannot see.
		com.mopicmp.npcstudio.client.scene.CameraMarker.draw(graphics, originX(), originY());
		com.mopicmp.npcstudio.client.map.StartMarker.draw(graphics, originX(), originY());
		com.mopicmp.npcstudio.client.map.SpotMarkers.draw(graphics, originX(), originY());
		// A route being drawn, in the viewport that is drawing it. Nothing is shown
		// unless this panel is the one placing points — a route belongs to one node
		// of one graph, and a world with every route in it at once is a world of
		// lines nobody can trace back to anything.
		if (com.mopicmp.npcstudio.client.map.Routing.inScene()) {
			com.mopicmp.npcstudio.client.map.Routing.draw(graphics, font,
				originX(), originY(), ground(mouseX, mouseY).at());
			com.mopicmp.npcstudio.client.map.Routing.hint(graphics, font, 0, width);
		}
		com.mopicmp.npcstudio.client.scene.BoneHandles.draw(graphics, originX(), originY());
		com.mopicmp.npcstudio.client.scene.MoveHandles.draw(graphics, originX(), originY());

		// Last of everything, because it is the one thing here that is asked for
		// rather than shown: handles and outlines describe the world, and this covers
		// it on purpose.
		ring.draw(graphics, font, mouseX, mouseY);
		world.draw(graphics, font, mouseX, mouseY);
		com.mopicmp.npcstudio.client.map.Naming.draw(graphics, font, width, height);
	}

	/** Which limb the mouse is over, if it is over the character being posed. */
	private String underMouse = "";

	/**
	 * The bone under a point of this panel, or empty.
	 *
	 * Only on whoever is already selected. A click on somebody else is a click on
	 * somebody else — it selects them — and answering with one of <em>their</em>
	 * bones would mean the selection and the bone came from two different
	 * characters for one frame.
	 */
	protected String boneAt(double localX, double localY) {
		Entity who = Workspace.selection();
		String role = com.mopicmp.npcstudio.client.scene.BoneHandles.role();
		if (who == null || role.isEmpty()) return "";
		Vec3[] line = ray(localX, localY);
		if (line == null) return "";
		return com.mopicmp.npcstudio.client.scene.BonePicking.at(who, role, line[0], line[1]);
	}

	@Override
	protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		steer();
		if (com.mopicmp.npcstudio.client.scene.Capture.running()) return;

		// A readout rather than a toolbar. What is useful to know at a glance is
		// what is selected and how far off the camera is; everything else that
		// could go here is a thing to click, and things to click belong in panels
		// where they are not sitting on top of the work.
		// The keys, said out loud and permanently, exactly as the modelling viewport
		// says its own. A key nobody has been told about is a key that does not
		// exist, and there is no other place in this window where somebody would go
		// looking — the workspace has no help screen and should not grow one for six
		// letters.
		graphics.text(font, Component.translatable("npc_studio.viewport.keys"),
			6, height - 32, TEXT_DIM);

		Entity chosen = Workspace.selection();
		String who = chosen == null
			? Component.translatable("npc_studio.viewport.nothing").getString()
			: chosen.getName().getString();
		graphics.text(font, Component.literal(who), 6, height - 22, chosen == null ? TEXT_DIM : ACCENT);

		// Which view this is, when it is not the workspace's own. Without it, a
		// viewport that has stopped answering the letter keys looks broken rather
		// than looks like a camera — and the distance readout underneath is measuring
		// something that is no longer on the screen.
		if (com.mopicmp.npcstudio.client.scene.SceneCameras.through()) {
			graphics.text(font, Component.translatable("npc_studio.viewport.through"),
				6, height - 12, 0xFF66BB6A);
			return;
		}
		graphics.text(font, Component.literal(String.format("%.1f m", WorkspaceCamera.distance())),
			6, height - 12, TEXT_DIM);
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (!inside(event.x(), event.y())) return false;

		// The open menu first, always. It is drawn over the world, so a click aimed
		// at it that reached the world instead would pick whatever the entry happens
		// to be covering — which is the character the menu is about.
		if (ring.isOpen()) {
			ring.click(event.x(), event.y());
			return true;
		}
		if (world.isOpen()) {
			world.click(event.x(), event.y());
			return true;
		}

		if (event.button() == GLFW_RIGHT) {
			// Where the press landed, so that letting go without having travelled can
			// be told from a drag. The threshold is the one the number fields already
			// use for the same distinction — a click that wanders by a pixel is still
			// a click, and a hand that has moved three is aiming.
			pressX = event.x();
			pressY = event.y();
			mayMenu = true;
			// Looking about rather than orbiting: the right button is how you get
			// from one end of a scene to the other, and orbiting is how you get
			// stuck circling whatever you last clicked on.
			//
			// And through the scene's camera it is the same gesture aiming the same
			// picture — it is only that the picture belongs to the camera now, so the
			// drag is written into the scene instead of into the workspace's view.
			looking = steerable();
			aiming = !steerable();
			return true;
		}
		if (event.button() == GLFW_MIDDLE) {
			boolean shifted = (event.modifiers() & GLFW_SHIFT) != 0;
			orbiting = steerable() && !shifted;
			panning = steerable() && shifted;
			return true;
		}
		if (event.button() == GLFW_LEFT) {
			// Drawing a route takes this button whole, and takes it before anything
			// else here. While the mode is on there is nothing in this panel worth
			// clicking instead — the whole of it is putting points on the ground —
			// and a click that selected a character mid-route would look exactly like
			// a point that failed to appear.
			//
			// Only this button. The right one flies the camera, and a route longer
			// than the view is drawn by flying along it; taking that away to gain a
			// second way of removing a point would trade the useful half of the mode
			// for the convenient half.
			if (com.mopicmp.npcstudio.client.map.Routing.inScene()) {
				Under on = ground(event.x(), event.y());
				if (on.at() != null) {
					if (com.mopicmp.npcstudio.client.map.Routing.drawingBox()) {
						// A corner. Shift does not remove one — there is nothing to remove
						// until both are down, and a half-drawn box is undone by walking away.
						com.mopicmp.npcstudio.client.map.Routing.putCorner(on.at());
					} else if (com.mopicmp.npcstudio.client.map.Routing.movingHome()) {
						// Facing where the viewport's camera is looking, which is the same
						// gesture as out in the world: which way she stands is said by
						// looking that way, and here the camera is the looking.
						com.mopicmp.npcstudio.client.map.Routing.putHome(on.at(),
							com.mopicmp.npcstudio.client.workspace.WorkspaceCamera.yaw());
					} else if ((event.modifiers() & GLFW_SHIFT) != 0) {
						com.mopicmp.npcstudio.client.map.Routing.remove(on.at());
					} else {
						com.mopicmp.npcstudio.client.map.Routing.put(on.at());
					}
				}
				return true;
			}
			// A handle first, always. A ring drawn over a character is a ring as far
			// as the person clicking is concerned, and letting the pick underneath it
			// win would mean the only way to grab a bone is to aim at the part of the
			// ring that happens to be off the body.
			if (com.mopicmp.npcstudio.client.scene.BoneHandles.grab(
					originX() + event.x(), originY() + event.y())) {
				return true;
			}
			if (com.mopicmp.npcstudio.client.scene.MoveHandles.grab(
					originX() + event.x(), originY() + event.y())) {
				return true;
			}
			// Then a limb of whoever is already selected, which is how a bone is
			// chosen: the arm you want to lift is on the screen, and the only way to
			// say so used to be reading six names in a panel and working out which of
			// them faces you. A click that lands on nobody's limb falls through to
			// choosing a character, so nothing that used to work has been taken away.
			String limb = boneAt(event.x(), event.y());
			if (!limb.isEmpty()) {
				com.mopicmp.npcstudio.client.scene.BoneHandles.pose(limb);
				return true;
			}
			Entity hit = pick(event.x(), event.y());
			chose(hit);
			if (doubleClick && hit != null) WorkspaceCamera.frame(hit);
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (mayMenu && Math.hypot(event.x() - pressX, event.y() - pressY) > SLOP) {
			mayMenu = false;
		}
		if (com.mopicmp.npcstudio.client.scene.BoneHandles.holding()) {
			com.mopicmp.npcstudio.client.scene.BoneHandles.drag(
				originX() + event.x(), originY() + event.y());
			return true;
		}
		if (com.mopicmp.npcstudio.client.scene.MoveHandles.holding()) {
			com.mopicmp.npcstudio.client.scene.MoveHandles.drag(
				originX() + event.x(), originY() + event.y());
			return true;
		}
		if (aiming) {
			com.mopicmp.npcstudio.client.scene.SceneCameras.look(dragX, dragY);
			return true;
		}
		if (looking) {
			WorkspaceCamera.look(dragX, dragY);
			return true;
		}
		if (orbiting) {
			WorkspaceCamera.orbit(dragX, dragY);
			return true;
		}
		if (panning) {
			WorkspaceCamera.pan(dragX, dragY);
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		boolean was = orbiting || panning || looking || aiming
			|| com.mopicmp.npcstudio.client.scene.BoneHandles.holding()
			|| com.mopicmp.npcstudio.client.scene.MoveHandles.holding();
		com.mopicmp.npcstudio.client.scene.BoneHandles.release();
		com.mopicmp.npcstudio.client.scene.MoveHandles.release();
		orbiting = false;
		panning = false;
		looking = false;
		aiming = false;

		if (mayMenu && event.button() == GLFW_RIGHT) {
			mayMenu = false;
			openWorldMenu(event.x(), event.y());
			return true;
		}
		return was;
	}

	// ------------------------------------------------------- the menu in the world

	/** Travel before a press stops being a click. The number fields' own. */
	private static final int SLOP = 3;

	private double pressX;
	private double pressY;
	private boolean mayMenu;

	private final com.mopicmp.npcstudio.client.workspace.Ring ring =
		new com.mopicmp.npcstudio.client.workspace.Ring();

	/**
	 * The way out when a subject has grown past what a ring can hold.
	 *
	 * Kept rather than deleted, and it is not a hedge. Eight is the ceiling the
	 * ring exists to impose, so exceeding it has to do something — and quietly
	 * dropping the ninth would make the ceiling a lie. Falling back to a column is
	 * visible, works, and is meant to look like what it is: a sign that this
	 * subject is doing too much.
	 */
	private final com.mopicmp.npcstudio.client.workspace.Menu world =
		new com.mopicmp.npcstudio.client.workspace.Menu();

	/**
	 * Opens what is on offer for whatever the cursor is over.
	 *
	 * A ring while the subject fits in one, a column when it does not. The choice
	 * is made here rather than inside either of them because it is a statement
	 * about the subject, not about drawing: a subject that needs a column has
	 * grown past what one gesture should be asked to hold.
	 */
	private void openWorldMenu(double localX, double localY) {
		Under under = under(localX, localY);
		if (under.nothing()) {
			// Silence here would be indistinguishable from the gesture being broken.
			say(net.minecraft.network.chat.Component.translatable("npc_studio.world.nothing"));
			return;
		}
		var entries = com.mopicmp.npcstudio.client.workspace.WorldActions.forSubject(under);
		if (entries.isEmpty()) {
			// Something is there and there is nothing to do to it — a cow, a boat, a
			// dropped apple. Silence here is the same fault as silence over empty air,
			// and it is the one the ring's own notes warn about: a gesture that
			// sometimes does nothing is a gesture people stop using.
			say(net.minecraft.network.chat.Component.translatable("npc_studio.world.nothing"));
			return;
		}

		if (entries.size() <= com.mopicmp.npcstudio.client.workspace.Ring.MOST) {
			ring.open((int) localX, (int) localY, entries, 0, 0, width, height);
		} else {
			world.open(font, (int) localX, (int) localY, entries, 0, 0, width, height);
		}
	}

	@Override
	public boolean escape() {
		if (com.mopicmp.npcstudio.client.map.Naming.isOpen()) {
			com.mopicmp.npcstudio.client.map.Naming.cancel();
			return true;
		}
		// Done drawing the route, and back to the node it belongs to. The same key
		// ends it out in the world, so the way out of the mode is one key wherever it
		// was entered — the two halves differ in what they are good for and must not
		// differ in how they are left.
		if (com.mopicmp.npcstudio.client.map.Routing.inScene()) {
			com.mopicmp.npcstudio.client.map.Routing.finish();
			return true;
		}
		if (!ring.isOpen() && !world.isOpen()) return false;
		ring.close();
		world.close();
		return true;
	}

	@Override
	public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
		if (com.mopicmp.npcstudio.client.map.Naming.charTyped(event.codepoint())) return true;
		return super.charTyped(event);
	}

	private void say(net.minecraft.network.chat.Component what) {
		if (minecraft.player != null) minecraft.player.sendOverlayMessage(what);
	}

	/**
	 * What the cursor is over, in the order the left button already uses.
	 *
	 * Matching that order is the point rather than a convenience: the menu has to
	 * be about the thing a click would have taken, or pointing means two different
	 * things depending on which button is used to point.
	 */
	private Under under(double localX, double localY) {
		String limb = boneAt(localX, localY);
		if (!limb.isEmpty()) return Under.bone(Workspace.selection(), limb);

		Entity hit = pick(localX, localY);
		if (hit instanceof com.mopicmp.npcstudio.entity.SceneCamera) return Under.camera(hit);
		if (hit instanceof com.mopicmp.npcstudio.entity.ModelObject) return Under.object(hit);
		if (hit != null) return Under.character(hit);

		return ground(localX, localY);
	}

	/** The block the ray meets, and the exact point on it. */
	private Under ground(double localX, double localY) {
		if (minecraft.level == null || minecraft.player == null) return Under.NOTHING;
		Vec3[] line = ray(localX, localY);
		if (line == null) return Under.NOTHING;

		var hit = minecraft.level.clip(new net.minecraft.world.level.ClipContext(
			line[0], line[1],
			net.minecraft.world.level.ClipContext.Block.OUTLINE,
			net.minecraft.world.level.ClipContext.Fluid.NONE,
			minecraft.player));
		if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return Under.NOTHING;
		return Under.ground(hit.getBlockPos(), hit.getLocation());
	}

	/**
	 * When the last frame was, so flying is measured in seconds rather than ticks.
	 *
	 * It used to move a fixed step per tick, twenty times a second, which is not
	 * a slow camera — it is a camera that arrives in twenty separate places. The
	 * frame rate is several times that, so most frames showed no movement at all
	 * and the rest showed a jump.
	 */
	private long lastFrame;

	/** Blocks a second, and the same again on top of it while control is held. */
	private static final double SPEED = 7;

	/**
	 * Flies on the letter keys, once a frame.
	 *
	 * While the right button is held, or while this panel is the one keys are going
	 * to. The second is what makes the viewport feel like a viewport: walking a
	 * camera round a scene is a thing you do continuously, and having to keep a
	 * button pressed to do it means one hand is spoken for the whole time.
	 *
	 * The focus test is not a formality. Those letters belong to whatever text box
	 * somebody is typing in two panels over, and a workspace where naming a model
	 * flies the camera across the room would be worse than one that does not fly.
	 */
	protected final void steer() {
		long now = System.nanoTime();
		double seconds = lastFrame == 0 ? 0 : Math.min(0.1, (now - lastFrame) / 1_000_000_000.0);
		lastFrame = now;
		if ((!looking && !aiming && !focused()) || seconds <= 0) return;

		var window = minecraft.getWindow();

		// Not while a modifier is down. Shift and control are what make a drag finer
		// and what start every shortcut, so a camera that also answers to them slides
		// the view out from under whatever is being aimed at: holding shift to pick a
		// size sank the camera, and control and D — duplicate — walked it sideways.
		// Down is Q, which it always was; shift was only ever a second way to say it.
		if (held(window, KEY_LEFT_SHIFT) > 0 || held(window, KEY_RIGHT_SHIFT) > 0
			|| held(window, KEY_LEFT_CONTROL) > 0 || held(window, KEY_RIGHT_CONTROL) > 0) {
			return;
		}
		if (!flyable()) return;

		double ahead = held(window, KEY_W) - held(window, KEY_S);
		double across = held(window, KEY_D) - held(window, KEY_A);
		double up = held(window, KEY_SPACE) + held(window, KEY_E) - held(window, KEY_Q);
		if (steerable()) {
			WorkspaceCamera.fly(ahead, across, up, SPEED * seconds);
			return;
		}
		// Through the camera, the same keys move the camera. Which is the whole of
		// what "settle the shot from inside it" means: fly to where the picture looks
		// right, and the picture is what you were flying.
		com.mopicmp.npcstudio.client.scene.SceneCameras.fly(
			ahead, across, up, SPEED * seconds);
	}

	/**
	 * Whether the camera may fly at all just now.
	 *
	 * A mode that is in the middle of something says no. Dragging a handle and
	 * flying at the same time is two things fighting over the same picture, and the
	 * one that loses is the one being aimed at.
	 */
	protected boolean flyable() {
		// Not while a ring is being turned. Flying and turning at the same time are
		// two things fighting over one picture, and the one that loses is the bone
		// being aimed at.
		//
		// Looking through the scene's camera used to say no here as well, on the
		// grounds that flying the workspace's camera behind a picture that does not
		// move is worse than not flying. That was the right refusal to the wrong
		// question: the answer is not to stop flying, it is to fly the camera. See
		// {@link #steer}.
		return !com.mopicmp.npcstudio.client.scene.BoneHandles.holding();
	}

	private static int held(com.mojang.blaze3d.platform.Window window, int key) {
		return com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, key) ? 1 : 0;
	}

	/**
	 * Whether moving the workspace's own camera would be visible.
	 *
	 * It is not, while the view is being taken through the scene's camera. Every
	 * way of moving the view answers to this rather than only the letter keys,
	 * because a drag that changes nothing on screen and then jumps the picture when
	 * the camera is handed back is worse than one that is simply refused.
	 */
	private boolean steerable() {
		return !com.mopicmp.npcstudio.client.scene.SceneCameras.through();
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amountX, double amountY) {
		if (!inside(mouseX, mouseY)) return false;
		if (steerable()) {
			WorkspaceCamera.zoom(amountY);
			return true;
		}
		// Looking through the camera, so the wheel is the lens rather than the
		// distance. It is the same gesture meaning the same thing — go closer to the
		// subject — and it is the one setting of a camera that can only honestly be
		// chosen with the frame in front of you.
		com.mopicmp.npcstudio.client.scene.SceneCameras.zoom(amountY);
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		// Anything being typed comes first, and takes every key it knows. F would
		// otherwise frame the selection in the middle of somebody spelling a name.
		if (com.mopicmp.npcstudio.client.map.Naming.keyPressed(event.key())) return true;
		if (com.mopicmp.npcstudio.client.map.Naming.isOpen()) return true;

		// F for the selection, as in every editor that has ever had a viewport.
		if (event.key() == KEY_F) {
			if (steerable()) WorkspaceCamera.frame(Workspace.selection());
			return true;
		}
		// One, two, three for the tools — which is not a choice made here. The
		// modelling window has taught these since it existed, and says so in its own
		// help line. Binding G and R for the same three acts, because another editor
		// uses those letters, would mean the same gesture has two names depending on
		// which window it is done in — the exact drift being merged out of this
		// workspace everywhere else this week.
		//
		// The modelling viewport keeps its own handling of these and never reaches
		// here for them; these are for the world.
		if (event.key() == KEY_1) {
			com.mopicmp.npcstudio.client.model.Gizmo.tool(
				com.mopicmp.npcstudio.client.model.Gizmo.Tool.MOVE);
			return true;
		}
		if (event.key() == KEY_2) {
			com.mopicmp.npcstudio.client.model.Gizmo.tool(
				com.mopicmp.npcstudio.client.model.Gizmo.Tool.ROTATE);
			return true;
		}
		if (event.key() == KEY_DELETE) {
			com.mopicmp.npcstudio.client.workspace.WorldActions.remove(Workspace.selection());
			return true;
		}
		return false;
	}

	/**
	 * What a click on this viewport may land on.
	 *
	 * Everything but the player, here. A viewport in another mode answers
	 * differently — the modelling one takes placed models and leaves characters
	 * alone, because a character standing in the way of a mast should not become
	 * the thing the window is about.
	 */
	protected boolean pickable(Entity entity) {
		return entity != minecraft.player;
	}

	/** What being clicked means. Selecting, unless a mode says otherwise. */
	protected void chose(Entity picked) {
		Workspace.select(picked == null ? -1 : picked.getId());
	}

	// ----------------------------------------------------------------- picking

	/**
	 * What is under the mouse, by casting a ray the way the game would.
	 *
	 * Through the window rather than through the panel: the world fills the whole
	 * frame and our rectangle is only the part of it left visible, so a ray built
	 * from panel coordinates would point somewhere else entirely — off by however
	 * wide the panels beside it happen to be.
	 */
	protected Vec3[] ray(double localX, double localY) {
		var window = minecraft.getWindow();
		double acrossGui = window.getGuiScaledWidth();
		double downGui = window.getGuiScaledHeight();
		if (acrossGui <= 0 || downGui <= 0) return null;

		double ndcX = (originX() + localX) / acrossGui * 2 - 1;
		double ndcY = 1 - (originY() + localY) / downGui * 2;

		// The frame's aspect, not the interface's. The interface is measured in
		// whole scaled units and rounds down, so at most scales its shape is a
		// little different from the picture behind it — and a ray built from the
		// wrong shape misses by more the further it is from the middle, which is
		// exactly the "sometimes it selects, sometimes it does not" that was seen.
		double aspect = (double) window.getWidth() / Math.max(1, window.getHeight());
		double half = Math.tan(Math.toRadians(fieldOfView()) / 2);

		// From the camera the frame was actually drawn with, rather than from our
		// own arithmetic about where it ought to be. During an eased move to a new
		// subject the two disagree, and clicking mid-flight picked nothing.
		//
		// Its own three directions as well as its own place, and that is not
		// tidiness: while the view is being taken through the scene's camera, the
		// workspace's camera is still turning wherever it was left, so a ray built
		// from its basis would start at the shot and point somewhere else entirely.
		// The projection that draws the handles has always read the frame's camera —
		// see {@link Gizmo#onScreen} — and picking has to be the same ray.
		var camera = minecraft.gameRenderer.mainCamera();
		Vec3 from = camera.position();
		Vec3 ahead = vector(camera.forwardVector());
		// Right is left negated: the camera hands out a left because that is what a
		// view matrix is built from, and everything to do with a mouse wants the other.
		Vec3 side = vector(camera.leftVector()).scale(-1);
		Vec3 above = vector(camera.upVector());

		Vec3 direction = ahead
			.add(side.scale(ndcX * half * aspect))
			.add(above.scale(ndcY * half))
			.normalize();
		return new Vec3[] { from, from.add(direction.scale(REACH)) };
	}

	private Entity pick(double localX, double localY) {
		if (minecraft.level == null) return null;
		Vec3[] line = ray(localX, localY);
		if (line == null) return null;
		Vec3 from = line[0];
		Vec3 to = line[1];

		Entity nearest = null;
		double nearestAway = Double.MAX_VALUE;
		for (Entity entity : minecraft.level.entitiesForRendering()) {
			if (!pickable(entity)) continue;
			// Against what the thing is made of rather than the box round it. On a
			// placed model those differ by the whole of the ship: the box is a slab
			// from the keel to the masthead, so a click anywhere near it entered the
			// slab before it reached anything at all — and a character standing on
			// the deck is inside that slab and could never win. See {@link Picking}.
			double away = com.mopicmp.npcstudio.client.workspace.Picking.reach(
				entity instanceof com.mopicmp.npcstudio.entity.ModelObject object
					? object.collidersInWorld() : null,
				entity.getBoundingBox(), GRACE, from, to);
			if (Double.isNaN(away) || away >= nearestAway) continue;
			nearestAway = away;
			nearest = entity;
		}
		return nearest;
	}

	/**
	 * How much slack a plain bounding box is given.
	 *
	 * A character is thinner than it looks and clicking one from across a room
	 * otherwise takes several tries. Only the box gets it; something built out of
	 * parts is as big as it looks.
	 */
	private static final double GRACE = 0.15;

	private static Vec3 vector(org.joml.Vector3fc of) {
		return new Vec3(of.x(), of.y(), of.z());
	}

	/**
	 * The vertical angle the frame was drawn with.
	 *
	 * The camera's own, not the setting. The setting is only the starting point:
	 * the game widens it for speed and narrows it for a spyglass, and a scene's
	 * camera replaces it outright — so a ray built from the setting while the
	 * picture was drawn with something else points somewhere the person clicking
	 * was not looking. That used to be a rare disagreement and became a permanent
	 * one the moment a camera could carry its own lens.
	 */
	private double fieldOfView() {
		return minecraft.gameRenderer.mainCamera().getFov();
	}
}
