package com.mopicmp.npcstudio.client.workspace.panel;

import java.util.ArrayList;
import java.util.List;

import com.mopicmp.npcstudio.client.editor.FlatSlider;
import com.mopicmp.npcstudio.client.scene.Playing;
import com.mopicmp.npcstudio.client.scene.Posing;
import com.mopicmp.npcstudio.client.scene.Scenes;
import com.mopicmp.npcstudio.client.workspace.Icon;
import com.mopicmp.npcstudio.client.workspace.Workspace;
import com.mopicmp.npcstudio.client.workspace.WorkspacePanel;
import com.mopicmp.npcstudio.entity.NpcEntity;
import com.mopicmp.npcstudio.model.Bone;
import com.mopicmp.npcstudio.model.Model;
import com.mopicmp.npcstudio.scene.Channels;
import com.mopicmp.npcstudio.scene.Key;
import com.mopicmp.npcstudio.scene.Role;
import com.mopicmp.npcstudio.scene.Scene;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Turning one bone of one participant, at one moment.
 *
 * <h2>Why the numbers are not kept here</h2>
 *
 * Every slider reads the scene and writes the scene. There is no copy in this
 * panel of what a bone is turned to, which is what stops the panel and the
 * document from disagreeing the moment anything else changes one — the same rule
 * the build sliders follow, learned the same way.
 *
 * <h2>What a slider does when you let go of it</h2>
 *
 * Nothing, because it already happened. Moving a slider puts a key down at the
 * cursor, and the scene saves itself half a second later; there is no apply and
 * there is not going to be one. The consequence worth knowing is that the cursor
 * is part of every edit: turning an arm at tick 40 and turning it again at tick
 * 80 makes two keys and a movement between them, which is the whole of how a
 * pose becomes an animation.
 *
 * <h2>Nought means level, not "as it is now"</h2>
 *
 * A bone the scene says nothing about reads as nought here, and nought is where
 * the bone rests rather than where it happens to be this frame. So keying a bone
 * that is mid-stride takes it to its resting angle rather than freezing the
 * stride. That is the honest reading — a scene is the authored answer and the
 * walk is what happens when nobody has authored one — and it is said out loud
 * here because it is the one thing about this panel that can surprise somebody.
 */
public class PosePanel extends WorkspacePanel {

	private static final int ROW = 16;
	private static final int HEAD = 30;
	private static final int BUTTON = 16;

	/** The height of a group's own heading. */
	private static final int LABEL = 11;

	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;
	private static final int GOOD = 0xFF66BB6A;
	private static final int WARN = 0xFFE57373;
	private static final int PANEL = 0xFF161A20;

	/**
	 * The colour of the sliders that move a bone rather than turn it.
	 *
	 * Told apart by colour rather than only by a heading, because the two groups
	 * are three boxes labelled X, Y and Z followed by three more boxes labelled X,
	 * Y and Z — and a heading is the first thing that stops being read.
	 */
	private static final int MOVED = 0xFF9575CD;

	/**
	 * The three turns, in the colours of the three rings in the viewport.
	 *
	 * So that the panel and the world say the same thing. They did not: the rings
	 * have been red, green and blue since they were drawn and all three sliders
	 * were one colour, so learning that the red one is the swing meant learning it
	 * twice and from two places.
	 */
	private static final int[] AXIS = { 0xFFFF5555, 0xFF55DD66, 0xFF5599FF };

	/** The colour of the one slider that bends a limb rather than placing it. */
	private static final int FOLDED = 0xFF4DB6AC;

	/**
	 * How far a limb may be folded, in degrees.
	 *
	 * Taken from the fold's own ceiling rather than written down again. Past it the
	 * inside of the crook has more surface than there is room for and the drawing
	 * begins to pass through itself, so the fold clamps there anyway — a slider
	 * that went further would have a dead end on it.
	 */
	private static float mostFold() {
		return (float) Math.toDegrees(com.mopicmp.npcstudio.client.emote.Folding.MOST);
	}

	private boolean choosing;

	/**
	 * Which bone is being posed, kept where the handles keep it.
	 *
	 * One value, not two. The rings in the viewport and the sliders here are two
	 * views of the same choice, and a panel holding its own copy is a panel that
	 * disagrees with the world the first time either is used.
	 */
	private String bone() {
		return com.mopicmp.npcstudio.client.scene.BoneHandles.bone();
	}

	/** What the sliders were built for, so they are rebuilt when it changes. */
	private String builtFor = "";
	private String builtIn = "";

	@Override
	public String id() {
		return "pose";
	}

	@Override
	public int minimumWidth() {
		return 200;
	}

	@Override
	public int minimumHeight() {
		// Two groups of three, their headings, and the row that chooses the bone.
		return HEAD + LABEL * 2 + ROW * 7 + 6;
	}

	// ------------------------------------------------------------ what is posed

	/**
	 * The part being posed, or empty when nothing is.
	 *
	 * Whoever is selected in the workspace, if they are in the open scene. Two
	 * conditions rather than one because they fail differently and each has its own
	 * thing to say: nobody selected is answered by clicking somebody, and somebody
	 * selected who is not in the scene is answered by the mark in the objects list.
	 */
	private String part() {
		Scene scene = Playing.scene();
		if (scene == null) return "";

		NpcEntity npc = Workspace.selectedNpc();
		if (npc != null) return roleFor(scene, npc.getUUID().toString());

		var chosen = Workspace.selection();
		return chosen == null ? "" : roleFor(scene, chosen.getUUID().toString());
	}

	private static String roleFor(Scene scene, String id) {
		for (Role role : scene.cast()) {
			if (role.bound().equals(id)) return role.name();
		}
		return "";
	}

	/**
	 * The bones this participant has.
	 *
	 * A character has the six the vanilla model has. An object has whatever
	 * somebody drew, which is the case that matters for a ship's wheel — the wheel
	 * is a bone of the model and turning it is the whole trick.
	 */
	/**
	 * The list as it is shown: nothing, and then the bones.
	 *
	 * The first row chooses no bone, and it is not decoration. Choosing one hides
	 * the arrows that move the whole character — two subjects on one screen is two
	 * answers to "what does a drag do here" — so without a way back the only escape
	 * from a bone was to select somebody else and select yourself again.
	 */
	private List<String> rows() {
		List<String> named = new ArrayList<>();
		named.add("");
		named.addAll(bones());
		return named;
	}

	private List<String> bones() {
		var chosen = Workspace.selection();
		if (chosen instanceof com.mopicmp.npcstudio.entity.ModelObject object) {
			Model model = com.mopicmp.npcstudio.client.model.ModelStore.get(object.model());
			if (model == null) return List.of();
			List<String> named = new ArrayList<>();
			for (Bone one : model.bones()) named.add(one.name());
			return named;
		}
		return Posing.BONES;
	}

	// --------------------------------------------------------------- the sliders

	@Override
	protected void build() {
		String role = part();
		builtFor = role;
		builtIn = bone();
		if (role.isEmpty() || bone().isEmpty()) return;

		String[] labels = named();
		String[] turns = { Channels.TURN_X, Channels.TURN_Y, Channels.TURN_Z };
		String[] shifts = { Channels.SHIFT_X, Channels.SHIFT_Y, Channels.SHIFT_Z };
		int wide = Math.max(40, width - 12);

		for (int i = 0; i < turns.length; i++) {
			String channel = Channels.of(bone(), turns[i]);
			add(new FlatSlider(6, turnsTop() + i * ROW, wide, ROW - 4, labels[i],
				-180, 180, 1, AXIS[i],
				() -> valueOf(channel),
				turned -> put(channel, turned.floatValue())));
		}

		// And the fold, which is a fourth thing a limb can be doing and is not a
		// turn: the limb curves at its middle rather than swinging at its end. The
		// drawing for it has been in the mod since the emotes arrived; only a way
		// for a scene to ask for one was missing.
		String folding = Channels.of(bone(), Channels.BEND);
		add(new FlatSlider(6, turnsTop() + turns.length * ROW, wide, ROW - 4,
			Component.translatable("npc_studio.pose.bend").getString(),
			-mostFold(), mostFold(), 1, FOLDED,
			() -> valueOf(folding),
			bent -> put(folding, bent.floatValue())));

		// And where it sits, which the format has always carried and nothing has
		// ever been able to set. A bone's own offset from where the model puts it,
		// in the model's pixels: see {@link Posing#apply}, which has been applying
		// these since the day the channels were named.
		//
		// The starting point is the bone's own rest position rather than nought, so
		// that the number in the box is where the bone <em>is</em> — a shift channel
		// replaces the part's offset rather than adding to it, so nought would mean
		// "at the character's middle" and would fling every arm into its chest the
		// moment somebody touched the slider.
		for (int i = 0; i < shifts.length; i++) {
			String channel = Channels.of(bone(), shifts[i]);
			int axis = i;
			add(new FlatSlider(6, shiftsTop() + i * ROW, wide, ROW - 4, ACROSS[i],
				-32, 32, 0.25, MOVED,
				() -> valueOf(channel, restOf(axis)),
				moved -> put(channel, moved.floatValue())));
		}
	}

	/** The three axes, as directions rather than as letters. */
	private static final String[] ACROSS = { "X", "Y", "Z" };

	/**
	 * What this bone's three turns are called.
	 *
	 * <h2>Why they are not X, Y and Z</h2>
	 *
	 * Because on a limb those three letters describe nothing anybody wants. An arm
	 * hangs down the model's own Y, so turning it about Y spins it about its own
	 * length — a twist, not a movement — while X and Z both lift the hand, one
	 * forwards and one out to the side. Told only the letters, that reads as two
	 * controls doing the same thing and a third doing something useless, which is
	 * exactly how it was reported.
	 *
	 * Nothing about the geometry is wrong and nothing about it can be fixed: a
	 * rotation about the axis a limb lies along is a twist, on any rig, in any
	 * program. What can be fixed is being told which is which, so the names say
	 * what the movement is and the colours match the rings it is drawn as.
	 *
	 * A bone of somebody's own model keeps the letters. We know its name and
	 * nothing else about it — a ship's wheel has no shoulder to swing from — and
	 * inventing a word for what its Y does would be worse than the letter.
	 */
	private String[] named() {
		var chosen = Workspace.selection();
		if (chosen instanceof com.mopicmp.npcstudio.entity.ModelObject) return ACROSS;
		String kind = switch (bone()) {
			case "head" -> "head";
			case "body" -> "body";
			case "rightArm", "leftArm", "rightLeg", "leftLeg" -> "limb";
			default -> "";
		};
		if (kind.isEmpty()) return ACROSS;
		return new String[] {
			Component.translatable("npc_studio.pose." + kind + ".x").getString(),
			Component.translatable("npc_studio.pose." + kind + ".y").getString(),
			Component.translatable("npc_studio.pose." + kind + ".z").getString() };
	}

	private int turnsTop() {
		return HEAD + LABEL;
	}

	private int shiftsTop() {
		return turnsTop() + 4 * ROW + LABEL;
	}

	/**
	 * Where this bone rests, in the model's own pixels.
	 *
	 * What a shift slider reads when the scene says nothing — which is not nought.
	 * A shift channel <em>replaces</em> a part's offset rather than adding to it,
	 * so nought is the middle of the character's chest and every bone would leap
	 * there the moment a slider was touched.
	 */
	private float restOf(int axis) {
		var chosen = Workspace.selection();
		if (chosen instanceof com.mopicmp.npcstudio.entity.ModelObject) return 0;
		float[] at = Posing.restOf(bone());
		return at == null ? 0 : at[axis];
	}

	@Override
	public void tick() {
		// The sliders capture a channel, so they belong to one bone of one
		// participant. Rebuilt when either moves, rather than being asked to work out
		// what they are for every frame.
		if (!part().equals(builtFor) || !bone().equals(builtIn)) rebuild();
	}

	private double valueOf(String channel) {
		return valueOf(channel, 0);
	}

	private double valueOf(String channel, float unsaid) {
		Scene scene = Playing.scene();
		String role = part();
		if (scene == null || role.isEmpty()) return unsaid;
		return scene.valueAt(role, channel, Playing.head().at(), unsaid);
	}

	private void put(String channel, float degrees) {
		Scene scene = Playing.scene();
		String role = part();
		if (scene == null || role.isEmpty()) return;
		Scenes.keep(Playing.openName(),
			scene.keyed(role, channel, Key.at(Playing.head().tick(), degrees)));
	}

	/**
	 * Keys all three turns of this bone where they already are.
	 *
	 * The way to make a bone hold still. Without it the only way to key a bone is
	 * to move it, so a pose that has to stay put for two seconds cannot be said —
	 * and "stays put" is most of what a character in a scene is doing.
	 */
	private void keyAll() {
		Scene scene = Playing.scene();
		String role = part();
		if (scene == null || role.isEmpty()) return;

		int at = Playing.head().tick();
		Scene changed = scene;

		// Where the character is standing and which way it faces, always — that is
		// what makes placing a character and animating one two separate acts rather
		// than one confusing one. The arrows in the viewport move the character; this
		// is what writes wherever it has ended up into the scene.
		var who = Workspace.selection();
		if (who != null) {
			var placed = com.mopicmp.npcstudio.client.scene.Placed.of(who);
			changed = changed.keyed(role, Channels.X, Key.at(at, (float) placed.at().x));
			changed = changed.keyed(role, Channels.Y, Key.at(at, (float) placed.at().y));
			changed = changed.keyed(role, Channels.Z, Key.at(at, (float) placed.at().z));
			changed = changed.keyed(role, Channels.YAW, Key.at(at, placed.yaw()));
		}

		if (!bone().isEmpty()) {
			for (String field : MOVEMENTS) {
				String channel = Channels.of(bone(), field);
				float unsaid = restFor(field);
				changed = changed.keyed(role, channel,
					Key.at(at, changed.valueAt(role, channel, at, unsaid)));
			}
		}
		Scenes.keep(Playing.openName(), changed);
	}

	/** Everything one bone can be told to do: how it is turned, and where it sits. */
	private static final String[] MOVEMENTS = {
		Channels.TURN_X, Channels.TURN_Y, Channels.TURN_Z,
		Channels.SHIFT_X, Channels.SHIFT_Y, Channels.SHIFT_Z,
		Channels.BEND };

	/** What a channel reads as when the scene says nothing about it. */
	private float restFor(String field) {
		return switch (field) {
			case Channels.SHIFT_X -> restOf(0);
			case Channels.SHIFT_Y -> restOf(1);
			case Channels.SHIFT_Z -> restOf(2);
			default -> 0;
		};
	}

	private void clearAll() {
		Scene scene = Playing.scene();
		String role = part();
		if (scene == null || role.isEmpty()) return;

		int at = Playing.head().tick();
		Scene changed = scene;
		if (bone().isEmpty()) {
			for (String channel : new String[] {
					Channels.X, Channels.Y, Channels.Z, Channels.YAW }) {
				changed = changed.unkeyed(role, channel, at);
			}
		} else {
			for (String field : MOVEMENTS) {
				changed = changed.unkeyed(role, Channels.of(bone(), field), at);
			}
		}
		Scenes.keep(Playing.openName(), changed);
	}

	// --------------------------------------------------------------- the drawing

	@Override
	protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		String role = part();
		if (role.isEmpty()) {
			graphics.text(font, Component.translatable(Playing.scene() == null
				? "npc_studio.pose.no_scene" : "npc_studio.pose.not_cast"), 6, 6, TEXT_DIM);
			return;
		}

		// The role is whatever the author called the part, so it is unbounded and the
		// tick beside it is not. When they meet, the tick wins: it is the answer to
		// "where am I", which the role does not help with, and it is four characters.
		String when = Playing.head().tick() + Component.translatable("npc_studio.pose.tick").getString();
		int end = width - 6 - font.width(when);
		graphics.text(font, Component.literal(shortened(role, end - 6 - 4)), 6, 5, ACCENT);
		graphics.text(font, Component.literal(when), end, 5, TEXT_DIM);

		drawBoneChoice(graphics, mouseX, mouseY);
		if (bone().isEmpty()) {
			graphics.text(font, Component.translatable("npc_studio.pose.pick_bone"),
				6, HEAD + 4, TEXT_DIM);
			return;
		}
		graphics.text(font, Component.translatable("npc_studio.pose.turn"),
			6, turnsTop() - LABEL + 1, TEXT_DIM);
		graphics.text(font, Component.translatable("npc_studio.pose.shift"),
			6, shiftsTop() - LABEL + 1, TEXT_DIM);
	}

	/**
	 * The open list, drawn after the sliders rather than before them.
	 *
	 * A panel paints itself and its widgets are drawn on top, which is right for
	 * everything a panel paints except a thing that hangs over them. The list was
	 * painted in {@link #draw} and the three sliders went straight over it: what
	 * you saw was half a bone's name behind a number box, and picking a bone meant
	 * clicking a row you could not read.
	 */
	@Override
	protected void over(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		if (choosing && !part().isEmpty()) drawBones(graphics, mouseX, mouseY);
	}

	private void drawBoneChoice(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int top = 16;
		int right = width - 6;
		Icon.CHECK.draw(graphics, right - BUTTON, top, GOOD);
		Icon.REMOVE.draw(graphics, right - BUTTON * 2 - 2, top, WARN);

		boolean over = mouseY >= top && mouseY < top + 12 && mouseX < right - BUTTON * 2 - 6;
		String said = bone().isEmpty()
			? Component.translatable("npc_studio.pose.bone").getString() : bone();
		graphics.text(font, Component.literal("▾ " + said), 6, top + 2,
			over || choosing ? ACCENT : TEXT);
	}

	/**
	 * The bones, as a list that hangs over the panel.
	 *
	 * A list rather than a row of buttons, because how many bones there are is not
	 * known in advance — a character has six and a ship has whatever somebody drew
	 * — and a control that grows sideways runs off the panel.
	 */
	private void drawBones(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		List<String> named = rows();
		int wide = Math.max(90, width - 12);
		int top = 28;
		graphics.fill(5, top - 1, 7 + wide, top + named.size() * 12 + 1, 0xFF2C333D);
		graphics.fill(6, top, 6 + wide, top + named.size() * 12, PANEL);

		for (int i = 0; i < named.size(); i++) {
			int y = top + i * 12;
			boolean over = mouseX >= 6 && mouseX < 6 + wide && mouseY >= y && mouseY < y + 12;
			boolean on = named.get(i).equals(bone());
			if (over || on) graphics.fill(7, y, 5 + wide, y + 12, on ? 0xFF27313E : 0xFF232A34);
			Component said = named.get(i).isEmpty()
				? Component.translatable("npc_studio.pose.whole") : Component.literal(named.get(i));
			graphics.text(font, said, 10, y + 2,
				named.get(i).isEmpty() ? TEXT_DIM : on ? ACCENT : TEXT);
		}
	}

	// ----------------------------------------------------------------- the input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (!inside(event.x(), event.y())) return false;

		if (choosing) {
			List<String> named = rows();
			int wide = Math.max(90, width - 12);
			int row = (int) ((event.y() - 28) / 12);
			if (event.x() >= 6 && event.x() < 6 + wide && row >= 0 && row < named.size()) {
				com.mopicmp.npcstudio.client.scene.BoneHandles.pose(named.get(row));
				rebuild();
			}
			choosing = false;
			return true;
		}

		int top = 16;
		if (event.y() >= top && event.y() < top + BUTTON) {
			int right = width - 6;
			if (event.x() >= right - BUTTON) {
				keyAll();
				return true;
			}
			if (event.x() >= right - BUTTON * 2 - 2) {
				clearAll();
				return true;
			}
			choosing = true;
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}
}
