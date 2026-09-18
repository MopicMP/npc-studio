package com.mopicmp.npcstudio.client.server;

import java.util.ArrayList;
import java.util.List;

import com.mopicmp.npcstudio.client.editor.FlatButton;
import com.mopicmp.npcstudio.client.workspace.Icon;
import com.mopicmp.npcstudio.client.workspace.IconTextButton;
import com.mopicmp.npcstudio.server.CoreCatalog;
import com.mopicmp.npcstudio.server.ServerCreation;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Four questions, each one a label above a control the width of the form.
 *
 * The two that are asked properly are the core and the version, because those
 * are the two that are awkward to change afterwards — everything else on a
 * server can be changed from the manager in a moment. Both are chosen from a
 * list rather than cycled through: there are three cores and a hundred versions,
 * and a button that steps through a hundred of anything is not a control.
 */
public class CreateServerScreen extends Screen {

	private static final int CANVAS = 0xFF101318;
	private static final int BAR = 0xFF12161C;
	private static final int EDGE = 0xFF2C333D;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;
	private static final int GOOD = 0xFF66BB6A;
	private static final int WARN = 0xFFFFB74D;

	private static final int TOP = 22;
	private static final int FORM = 240;
	private static final int FIELD = 18;
	private static final int LABEL = 10;
	private static final int GROUP = 10;

	private static final int[] MEMORY = {1024, 2048, 3072, 4096};

	private final Screen back;
	private final ServerManager manager = ServerManager.get();
	private final Dropdown dropdown = new Dropdown();

	private String nameText = "";
	private String portText = "25565";
	private int core = -1;
	private int memory = defaultMemory();

	/**
	 * Which core is offered first.
	 *
	 * Paper, where it is in the catalogue. Not a preference: it is the same game
	 * from a player's side and several times cheaper to run, and on a machine
	 * that is also drawing the game that is the difference between playable and
	 * not. Vanilla stays one press away for anybody who wants exactly vanilla.
	 */
	private int defaultCore() {
		List<CoreCatalog.Core> cores = cores();
		for (int at = 0; at < cores.size(); at++) {
			if (cores.get(at).id().equals("paper")) return at;
		}
		return 0;
	}

	/**
	 * The largest of the offered sizes this machine can actually spare.
	 *
	 * Not the middle one. A server given more memory than the machine has left
	 * does not run better, it runs on the page file — which is what turned a
	 * server made here into one its only player was thrown off.
	 */
	private static int defaultMemory() {
		int room = com.mopicmp.npcstudio.server.Machine.recommendedHeapMb();
		int chosen = 0;
		for (int at = 0; at < MEMORY.length; at++) {
			if (MEMORY[at] <= room) chosen = at;
		}
		return chosen;
	}

	private List<String> versions = List.of();
	private String version = "";
	private boolean asking;
	private boolean asked;
	private String note = "";
	private boolean working;

	public CreateServerScreen(Screen back) {
		super(Component.translatable("npc_studio.server.create.title"));
		this.back = back;
	}

	private List<CoreCatalog.Core> cores() {
		return manager.catalog().cores();
	}

	@Override
	protected void init() {
		addRenderableWidget(new IconTextButton(4, 3, 16, 16, Icon.CLOSED,
			Component.translatable("npc_studio.server.back"), TEXT_DIM, this::onClose));
		if (cores().isEmpty()) {
			note = Component.translatable("npc_studio.server.create.no_cores").getString();
			return;
		}
		if (core < 0) core = defaultCore();
		if (!asked) ask();

		int x = width / 2 - FORM / 2;
		int y = TOP + 12;

		EditBox name = new EditBox(font, x, y + LABEL, FORM, FIELD,
			Component.translatable("npc_studio.server.create.name"));
		name.setMaxLength(48);
		name.setValue(nameText);
		name.setResponder(typed -> nameText = typed);
		addRenderableWidget(name);
		y += LABEL + FIELD + GROUP;

		int coreY = y + LABEL;
		addRenderableWidget(new FlatButton(x, coreY, FORM, FIELD,
			Component.literal(cores().get(core).name() + "  ▾"), ACCENT, () -> {
				List<Dropdown.Option> options = new ArrayList<>();
				for (CoreCatalog.Core each : cores()) {
					options.add(new Dropdown.Option(each.id(), Component.literal(each.name())));
				}
				dropdown.open(x, coreY + FIELD, FORM, height, options, cores().get(core).id(),
					picked -> {
						for (int at = 0; at < cores().size(); at++) {
							if (cores().get(at).id().equals(picked)) core = at;
						}
						versions = List.of();
						version = "";
						asked = false;
						rebuild();
					});
			}));
		y += LABEL + FIELD + GROUP;

		int versionY = y + LABEL;
		FlatButton pickVersion = new FlatButton(x, versionY, FORM, FIELD,
			Component.literal(version.isBlank()
				? Component.translatable(asking
					? "npc_studio.server.create.asking"
					: "npc_studio.server.create.no_versions").getString()
				: version + "  ▾"),
			version.isBlank() ? TEXT_DIM : ACCENT, () -> {
				if (versions.isEmpty()) {
					// Nothing to choose from is a reason to ask again, not a dead
					// button — the list may have failed to arrive once.
					asked = false;
					rebuild();
					return;
				}
				List<Dropdown.Option> options = new ArrayList<>();
				for (String each : versions) options.add(Dropdown.Option.of(each));
				dropdown.open(x, versionY + FIELD, FORM, height, options, version, picked -> {
					version = picked;
					rebuild();
				});
			});
		addRenderableWidget(pickVersion);
		y += LABEL + FIELD + GROUP;

		EditBox port = new EditBox(font, x, y + LABEL, FORM, FIELD,
			Component.translatable("npc_studio.server.create.port"));
		port.setMaxLength(5);
		port.setValue(portText);
		port.setResponder(typed -> portText = typed);
		addRenderableWidget(port);
		y += LABEL + FIELD + GROUP;

		int memoryY = y + LABEL;
		addRenderableWidget(new FlatButton(x, memoryY, FORM, FIELD,
			Component.literal(gigabytes(MEMORY[memory]) + "  ▾"), ACCENT, () -> {
				// Every size, including ones this machine will struggle with. The
				// suggestion is the one that fits; the choice is not ours to take
				// away, and asking for too much now fails with a window that says
				// so rather than with silence.
				List<Dropdown.Option> options = new ArrayList<>();
				for (int each : MEMORY) options.add(Dropdown.Option.of(gigabytes(each)));
				dropdown.open(x, memoryY + FIELD, FORM, height, options,
					gigabytes(MEMORY[memory]), picked -> {
						for (int at = 0; at < MEMORY.length; at++) {
							if (gigabytes(MEMORY[at]).equals(picked)) memory = at;
						}
						rebuild();
					});
			}));
		y += LABEL + FIELD + GROUP + 6;

		IconTextButton make = new IconTextButton(x, y, FORM, 20, Icon.CHECK,
			Component.translatable("npc_studio.server.create.make"),
			ready() ? GOOD : TEXT_DIM, this::make);
		make.active = ready();
		addRenderableWidget(make);
	}

	/** Memory as people say it, not as the JVM takes it. */
	private static String gigabytes(int megabytes) {
		String unit = Component.translatable("npc_studio.server.gigabytes").getString();
		double whole = megabytes / 1024.0;
		return (whole == Math.rint(whole)
			? String.valueOf((int) whole)
			: String.format(java.util.Locale.ROOT, "%.1f", whole)) + " " + unit;
	}

	private void rebuild() {
		dropdown.close();
		clearWidgets();
		init();
	}

	private void ask() {
		asked = true;
		asking = true;
		CoreCatalog.Core which = cores().get(core);
		manager.versions(which, got -> {
			asking = false;
			if (core >= cores().size() || !cores().get(core).id().equals(which.id())) return;
			versions = got;
			version = got.isEmpty() ? "" : got.getFirst();
			note = "";
			rebuild();
		}, failed -> {
			asking = false;
			note = failed;
			rebuild();
		});
	}

	private boolean ready() {
		return !working && !nameText.isBlank() && !version.isBlank() && port() > 0;
	}

	private int port() {
		try {
			int number = Integer.parseInt(portText.trim());
			return number >= 1 && number <= 65535 ? number : 0;
		} catch (NumberFormatException notANumber) {
			return 0;
		}
	}

	/** The agreement first, so nobody waits for a download before being asked. */
	private void make() {
		if (!ready()) return;
		ServerCreation.Request request = new ServerCreation.Request(
			nameText.trim(), cores().get(core).id(), version, port(), MEMORY[memory], false, true);

		minecraft.setScreenAndShow(new EulaScreen(this, () -> {
			working = true;
			note = Component.translatable("npc_studio.server.create.working").getString();
			rebuild();
			manager.create(request.agreed(),
				said -> note = said,
				server -> {
					working = false;
					manager.reload();
					// Straight into the server that was just made: creating one is
					// not the end of anything, it is the start of setting it up.
					minecraft.setScreenAndShow(new ServerManagerScreen(back, server.id));
				},
				failed -> {
					working = false;
					note = failed;
					rebuild();
				});
		}));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, CANVAS);
		graphics.fill(0, 0, width, TOP - 4, BAR);
		graphics.fill(0, TOP - 5, width, TOP - 4, EDGE);
		graphics.text(font, title, width / 2 - font.width(title) / 2, 5, TEXT);

		if (cores().isEmpty()) {
			graphics.text(font, note, width / 2 - font.width(note) / 2, height / 2, WARN);
			super.extractRenderState(graphics, mouseX, mouseY, delta);
			return;
		}

		int x = width / 2 - FORM / 2;
		int y = TOP + 12;
		for (String key : new String[] {
			"npc_studio.server.create.name",
			"npc_studio.server.create.core",
			"npc_studio.server.create.version",
			"npc_studio.server.create.port",
			"npc_studio.server.create.memory"}) {
			graphics.text(font, Component.translatable(key), x, y, TEXT_DIM);
			y += LABEL + FIELD + GROUP;
		}

		if (!note.isEmpty()) {
			graphics.textWithWordWrap(font, Component.literal(note),
				x, y + 26, FORM, working ? ACCENT : WARN);
		} else if (com.mopicmp.npcstudio.server.Machine.tight()) {
			// Said before the server exists rather than discovered by being thrown
			// off it. The numbers are this machine's, not a general caution.
			long gigabytes = Math.max(1,
				com.mopicmp.npcstudio.server.Machine.totalMemoryMb() / 1024);
			graphics.textWithWordWrap(font,
				Component.translatable("npc_studio.server.tight_machine",
					com.mopicmp.npcstudio.server.Machine.cores(), gigabytes),
				x, y + 26, FORM, WARN);
		}

		super.extractRenderState(graphics, mouseX, mouseY, delta);
		dropdown.draw(graphics, font, mouseX, mouseY);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (dropdown.click(event.x(), event.y())) return true;
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
		if (dropdown.isOpen()) return dropdown.scroll(dy);
		return super.mouseScrolled(mouseX, mouseY, dx, dy);
	}

	@Override
	public void onClose() {
		minecraft.setScreenAndShow(back);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
