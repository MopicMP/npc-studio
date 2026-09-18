package com.mopicmp.npcstudio.client.workspace.panel;

import java.util.ArrayList;
import java.util.List;

import com.mopicmp.npcstudio.client.workspace.Workspace;
import com.mopicmp.npcstudio.client.workspace.WorkspaceCamera;
import com.mopicmp.npcstudio.client.workspace.WorkspacePanel;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Who is in the scene.
 *
 * The counterpart to selection living in one place: once every panel on the
 * right answers to whoever is selected, there has to be somewhere to select
 * from other than the viewport. Clicking a character in the world is fine until
 * the character is behind the ship.
 */
public class ScenePanel extends WorkspacePanel {

	private static final int ROW = 13;

	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;
	private static final int ROW_ON = 0xFF27313E;
	private static final int ROW_HOVER = 0xFF232A34;

	private int scroll;

	@Override
	public String id() {
		return "scene";
	}

	/**
	 * Everything in the scene, people first and then the things.
	 *
	 * One list rather than two panels, because what somebody is doing here is
	 * choosing a participant, and a wheel that has to turn while a sailor's hands
	 * are on it is a participant in exactly the sense the sailor is. Kept in that
	 * order so the two do not shuffle together — a list where the wheel sits
	 * between two sailors reads as a list of sailors with a mistake in it.
	 */
	private List<Entity> everything() {
		List<Entity> found = new ArrayList<>(Workspace.cast());
		found.addAll(Workspace.things());
		// The camera last, because it is not in the world the way the others are: it
		// exists only while the open scene has one, and it goes away with the scene.
		// It belongs in the list all the same — it is a thing you select and pose, and
		// the list is where you go when what you want is behind the ship.
		var camera = com.mopicmp.npcstudio.client.scene.SceneCameras.camera();
		if (camera != null) found.add(camera);
		return found;
	}

	private static String nameOf(Entity thing) {
		if (thing instanceof com.mopicmp.npcstudio.entity.ModelObject object) {
			return object.model().isEmpty() ? "модель" : object.model();
		}
		return thing.getName().getString();
	}

	@Override
	protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		List<Entity> here = everything();
		if (here.isEmpty()) {
			graphics.text(font, Component.translatable("npc_studio.scene.empty"), 6, 6, TEXT_DIM);
			return;
		}

		scroll = Mth.clamp(scroll, 0, Math.max(0, here.size() * ROW - height));
		int hovered = rowAt(mouseX, mouseY, here.size());
		int people = Workspace.cast().size();

		for (int i = 0; i < here.size(); i++) {
			int top = i * ROW - scroll;
			if (top + ROW < 0 || top > height) continue;
			Entity thing = here.get(i);
			boolean on = thing.getId() == Workspace.selected();
			if (on || i == hovered) graphics.fill(0, top, width, top + ROW, on ? ROW_ON : ROW_HOVER);

			// A hairline where the people end and the things begin. A heading would
			// be a row that cannot be clicked in a list where every row can be.
			if (i == people && people > 0) graphics.fill(0, top, width, top + 1, 0xFF2C333D);

			// The distance says which of two identically named characters this is,
			// which a name on its own cannot — so the distance is the half that must
			// survive when the row is too narrow for both.
			//
			// It used to be drawn over the top of whatever the name had reached, because
			// the name was drawn at full length and the distance right-aligned into the
			// same row. Two characters called the same thing, which is exactly the case
			// this column exists for, produced two rows of overlapping letters.
			String away = String.format("%.0f", distanceTo(thing));
			int end = width - MARK - 10 - font.width(away);
			graphics.text(font, Component.literal(shortened(nameOf(thing), end - 6 - 4)),
				6, top + 3, on ? ACCENT : TEXT);
			graphics.text(font, Component.literal(away), end, top + 3, TEXT_DIM);
			drawMark(graphics, thing, top, i == hovered);
		}
	}

	/** How much of a row's right-hand end belongs to the scene mark. */
	private static final int MARK = 9;

	/*
	 * The cutting lives on WorkspacePanel. It was written here first and then the
	 * same fault turned up in the placed-objects list, which is the same picture with
	 * different nouns — so it is one answer in one place rather than two that can come
	 * to disagree about where an ellipsis goes.
	 */

	/**
	 * Whether this character is in the open scene, and the way to change that.
	 *
	 * A filled square rather than a button with a word on it. There is one of
	 * these per row and the rows are the list — a column of buttons saying "add"
	 * would be a wall of the same word, and the thing anybody wants to read down
	 * that column is which of these characters the scene knows about.
	 */
	private void drawMark(GuiGraphicsExtractor graphics, Entity thing, int top, boolean hovered) {
		if (com.mopicmp.npcstudio.client.scene.Playing.scene() == null) return;
		boolean inIt = partOf(thing) != null;
		int x = width - MARK - 4;
		int y = top + (ROW - MARK) / 2;
		graphics.fill(x, y, x + MARK, y + MARK, inIt ? ACCENT : 0xFF2C333D);
		if (!inIt && hovered) graphics.fill(x + 3, y + 3, x + MARK - 3, y + MARK - 3, TEXT_DIM);
	}

	private com.mopicmp.npcstudio.scene.Role partOf(Entity thing) {
		var scene = com.mopicmp.npcstudio.client.scene.Playing.scene();
		if (scene == null) return null;
		String id = thing.getUUID().toString();
		for (var role : scene.cast()) {
			if (role.bound().equals(id)) return role;
		}
		return null;
	}

	/**
	 * Puts a participant into the open scene, or takes it out again.
	 *
	 * The part is named after the thing, because that is what somebody will look
	 * for in the timeline, and made unique because two characters called "матрос"
	 * are two parts. Taking one out takes its tracks with it — that is the scene's
	 * own rule and it is the honest one, since a part nobody plays is still a row
	 * and rows nobody wants are what makes a timeline unreadable.
	 */
	private void castOrDrop(Entity thing) {
		var scene = com.mopicmp.npcstudio.client.scene.Playing.scene();
		if (scene == null) return;
		String open = com.mopicmp.npcstudio.client.scene.Playing.openName();

		var already = partOf(thing);
		if (already != null) {
			com.mopicmp.npcstudio.client.scene.Scenes.keep(open,
				scene.withoutRole(already.name()));
			return;
		}
		// A camera that is not in the scene does not exist to be put into one: the
		// entity is made from the part and disappears with it. So the mark on its row
		// only ever removes, and this is the guard that stops a stale row from making
		// a second part of the wrong kind bound to a camera.
		if (com.mopicmp.npcstudio.client.scene.SceneCameras.isCamera(thing)) return;

		String wanted = nameOf(thing);
		String name = wanted;
		for (int n = 2; scene.role(name) != null; n++) name = wanted + " " + n;
		var kind = thing instanceof com.mopicmp.npcstudio.entity.ModelObject
			? com.mopicmp.npcstudio.scene.Role.Kind.OBJECT
			: com.mopicmp.npcstudio.scene.Role.Kind.CHARACTER;
		com.mopicmp.npcstudio.client.scene.Scenes.keep(open, scene.with(
			com.mopicmp.npcstudio.scene.Role.of(name, kind)
				.boundTo(thing.getUUID().toString())));
	}

	private double distanceTo(Entity thing) {
		return minecraft.player == null ? 0 : minecraft.player.distanceTo(thing);
	}

	private int rowAt(double mouseX, double mouseY, int rows) {
		if (!inside(mouseX, mouseY)) return -1;
		int row = (int) ((mouseY + scroll) / ROW);
		return row >= 0 && row < rows ? row : -1;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		List<Entity> here = everything();
		int row = rowAt(event.x(), event.y(), here.size());
		if (row < 0) return false;
		Entity thing = here.get(row);
		// The mark at the end of the row is its own target: clicking it is about the
		// scene, clicking the rest of the row is about which character is being
		// looked at, and one of those must not do the other by accident.
		if (event.x() >= width - MARK - 4) {
			castOrDrop(thing);
			return true;
		}
		Workspace.select(thing.getId());
		// A double click takes the camera there, because "which one is that" is
		// answered by looking at it and finding it by hand is the chore.
		if (doubleClick) WorkspaceCamera.frame(thing);
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amountX, double amountY) {
		scroll -= (int) (amountY * ROW);
		return true;
	}
}
