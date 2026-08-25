package com.mopicmp.npcstudio.client.workspace;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.network.chat.Component;

import com.mopicmp.npcstudio.client.workspace.panel.AnimationPanel;
import com.mopicmp.npcstudio.client.workspace.panel.AssetsPanel;
import com.mopicmp.npcstudio.client.workspace.panel.BenchPanel;
import com.mopicmp.npcstudio.client.workspace.panel.BodyPanel;
import com.mopicmp.npcstudio.client.workspace.panel.CameraPanel;
import com.mopicmp.npcstudio.client.workspace.panel.CaptionPanel;
import com.mopicmp.npcstudio.client.workspace.panel.CharacterPanel;
import com.mopicmp.npcstudio.client.workspace.panel.CreditsPanel;
import com.mopicmp.npcstudio.client.workspace.panel.EnvironmentPanel;
import com.mopicmp.npcstudio.client.workspace.panel.GraphPanel;
import com.mopicmp.npcstudio.client.workspace.panel.MusicPanel;
import com.mopicmp.npcstudio.client.workspace.panel.PosePanel;
import com.mopicmp.npcstudio.client.workspace.panel.ScenePanel;
import com.mopicmp.npcstudio.client.workspace.panel.ShadersPanel;
import com.mopicmp.npcstudio.client.workspace.panel.TimelinePanel;
import com.mopicmp.npcstudio.client.workspace.panel.ViewportPanel;
import com.mopicmp.npcstudio.client.workspace.panel.WardrobePanel;

/**
 * Every panel the workspace knows how to make.
 *
 * One list, because two things need it and they must not drift: the dropdown
 * that offers a panel somebody has closed, and the saved layout, which stores
 * ids and has to be able to turn one back into a panel. A layout written before
 * a panel existed names ids that are not here, and a layout written after names
 * ids it does not — both cases are ordinary and neither is an error.
 */
public final class Panels {

	private record Kind(String id, Supplier<WorkspacePanel> make, String home) { }

	private static final List<Kind> KNOWN = List.of(
		new Kind("viewport", ViewportPanel::new, "top"),
		new Kind("scene", ScenePanel::new, "left"),
		new Kind("assets", AssetsPanel::new, "left"),
		new Kind("character", CharacterPanel::new, "right"),
		new Kind("animation", AnimationPanel::new, "right"),
		new Kind("wardrobe", WardrobePanel::new, "right"),
		new Kind("body", BodyPanel::new, "right"),
		new Kind("pose", PosePanel::new, "right"),
		new Kind("camera", CameraPanel::new, "right"),
		new Kind("captions", CaptionPanel::new, "right"),
		new Kind("music", MusicPanel::new, "right"),
		new Kind("environment", EnvironmentPanel::new, "right"),
		new Kind("shaders", ShadersPanel::new, "right"),
		new Kind("graph", GraphPanel::new, "bottom"),
		new Kind("timeline", TimelinePanel::new, "bottom"),
		new Kind("credits", CreditsPanel::new, "float"),
		// Scaffolding, and the only entry here that is meant to be deleted rather
		// than grown. See BenchPanel: when block programming can order what it
		// orders, this line and that file go together.
		new Kind("bench", BenchPanel::new, "right"));

	private Panels() { }

	/** What a panel is called, for a menu that offers to open it. */
	public static Component titleOf(String id) {
		return Component.translatable("npc_studio.panel." + id);
	}

	public static List<String> known() {
		return KNOWN.stream().map(Kind::id).toList();
	}

	public static WorkspacePanel make(String id) {
		for (Kind kind : KNOWN) {
			if (kind.id().equals(id)) return kind.make().get();
		}
		return null;
	}

	/** Which side a panel goes to when it is asked for and has no place yet. */
	public static String homeOf(String id) {
		for (Kind kind : KNOWN) {
			if (kind.id().equals(id)) return kind.home();
		}
		return "float";
	}
}
