package com.mopicmp.npcstudio.client.server;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mopicmp.npcstudio.client.editor.FlatButton;
import com.mopicmp.npcstudio.client.workspace.Icon;
import com.mopicmp.npcstudio.client.workspace.IconTextButton;
import com.mopicmp.npcstudio.server.CoreCatalog;
import com.mopicmp.npcstudio.server.Machine;
import com.mopicmp.npcstudio.server.ManagedServer;
import com.mopicmp.npcstudio.server.PropertiesFile;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * One server: what it is on the left, what it is doing on the right.
 *
 * <pre>
 * ┌ ‹ ──────────────── name ─────────────────────────────────┐
 * │ ┌───────────┐ │ ┌console┬settings┐   [copy] [save] [clear]│
 * │ │  picture  │ ├─┴───────┴────────┴──────────────────────┬─┤
 * │ └───────────┘ │                                         │ │
 * │ name          │   whichever tab                         │ │
 * │ [__________]  │                                         │ │
 * │ message       │                                         │ │
 * │ [__________]  │                                         │ │
 * │ core          │                                         │ │
 * │ [vanilla ▾]   │                                         │ │
 * │ address       │                                         │ │
 * │ memory ‹ 2 ›  │                                         │ │
 * │ [start]       │                                         │ │
 * └───────────────┴─────────────────────────────────────────┴─┘
 * </pre>
 *
 * Rules the layout keeps, each one learned by breaking it. A label goes above
 * its field, never beside it. A field is as wide as its column. There is no save
 * button — a setting is written a moment after it stops being typed. And a
 * number is a {@link NumberField}, which can be dragged as well as typed.
 */
public class ServerManagerScreen extends Screen {

	private static final int CANVAS = 0xFF101318;
	private static final int PANEL = 0xFF161A20;
	private static final int CARD = 0xFF141920;
	private static final int BAR = 0xFF12161C;
	private static final int EDGE = 0xFF2C333D;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int ACCENT = 0xFF4FC3F7;
	private static final int GOOD = 0xFF66BB6A;
	private static final int WARN = 0xFFFFB74D;

	private static final int TOP = 22;
	private static final int PAD = 8;
	private static final int FIELD = 18;
	private static final int LABEL = 10;
	private static final int GROUP = 8;
	private static final int LINE = 10;
	private static final int HEADER = 18;
	private static final int TABS = 20;

	/** How long a field is left alone before what is in it is written. */
	private static final long SETTLE_MS = 700;

	private enum Tab {
		CONSOLE(Icon.CONSOLE, "npc_studio.server.tab.console"),
		CONTENT(Icon.ASSETS, "npc_studio.server.tab.content"),
		WORLDS(Icon.ENVIRONMENT, "npc_studio.server.tab.worlds"),
		PLAYERS(Icon.CHARACTER, "npc_studio.server.tab.players"),
		RIGHTS(Icon.HANDLES, "npc_studio.server.tab.rights"),
		CONFIGS(Icon.FILE, "npc_studio.server.tab.configs"),
		SETTINGS(Icon.SETTINGS, "npc_studio.server.tab.settings");

		final Icon icon;
		final String label;

		Tab(Icon icon, String label) {
			this.icon = icon;
			this.label = label;
		}
	}

	/** One line of the settings tab: a heading, or a setting, with its place. */
	private record Placed(String group, ServerSettings setting, int x, int y, int width) {
		boolean heading() {
			return setting == null;
		}
	}

	/** The panel behind one group's rows. */
	private record Card(int x, int y, int width, int height) {
	}

	private final Screen back;
	private final String id;
	private final ServerManager manager = ServerManager.get();
	private final Dropdown dropdown = new Dropdown();
	private final Confirm confirm = new Confirm();
	private final MotdEditor motdEditor = new MotdEditor();

	private ManagedServer server;
	private Tab tab = Tab.CONSOLE;

	private final Map<String, String> values = new LinkedHashMap<>();
	private final Map<String, String> edits = new LinkedHashMap<>();
	private boolean propertiesRead;
	private String nameEdit;
	private int memoryEdit;
	private long lastEdit;
	private long saidAt;

	private EditBox command;
	private EditBox motd;
	private ServerManager.State shown = ServerManager.State.DOWN;
	private String commandText = "";
	private int consoleScroll;
	private int settingsScroll;
	private String note = "";

	/** Which console lines are picked out, as positions in the whole console. */
	private int pickedFrom = -1;
	private int pickedTo = -1;
	private boolean picking;

	private int left;
	private int contentLeft;
	private int contentTop;
	private int iconSize;
	private int motdFrameY;
	private final List<Framed> framed = new ArrayList<>();
	private final List<Placed> placed = new ArrayList<>();
	private final List<Card> cards = new ArrayList<>();
	private int settingsHeight;

	/** The heading over the client's own mods, which carries a button on its line. */
	private static final int CLIENT_HEADER = 24;

	/** How tall one row of the content tab is, card and gap together. */
	private static final int ADDON_ROW = 34;

	/** The picture in a content row, and the room it takes. */
	private static final int TILE = 24;

	/** The two halves of the content tab. Browsing first: it is where things come from. */
	private enum Half {
		BROWSE("npc_studio.server.content.browse"),
		INSTALLED("npc_studio.server.content.installed");

		final String label;

		Half(String label) {
			this.label = label;
		}
	}

	private Half half = Half.INSTALLED;

	private List<com.mopicmp.npcstudio.server.Addon> addons = List.of();

	/** How the installed list is ordered, and which of it is shown. */
	private String installedOrder = "name";
	private String installedShow = "all";

	/**
	 * The jars, and the current world's datapacks beside them.
	 *
	 * A datapack is not a jar and does not live in the same folder, but it is a
	 * third thing people install and they look for it in the same place. So it
	 * appears in the same list, saying which world it belongs to, and the row
	 * knows what it is when the time comes to switch it off or remove it.
	 */
	private List<com.mopicmp.npcstudio.server.Addon> installedNow() {
		if (installedShow.equals("mods") || installedShow.equals("plugins")) return addons;
		List<com.mopicmp.npcstudio.server.Addon> all = new ArrayList<>(addons);
		for (DataPacks.Pack pack : installedPacks) {
			all.add(new com.mopicmp.npcstudio.server.Addon(pack.name(), "", pack.name(),
				Component.translatable(pack.on()
					? "npc_studio.server.packs.is_on"
					: "npc_studio.server.packs.is_off", manager.currentWorld()).getString(),
				com.mopicmp.npcstudio.server.Addon.Kind.DATAPACK,
				com.mopicmp.npcstudio.server.Addon.Side.SERVER_ONLY, List.of(), false));
		}
		return all;
	}

	private List<DataPacks.Pack> installedPacks = List.of();

	/**
	 * A line of the installed list: either a heading or a thing.
	 *
	 * One list rather than three loops that agree by hand. The drawing, the
	 * placing of the buttons and the press that opens a page all walk this, so a
	 * heading cannot be drawn in one of them and forgotten in the other two —
	 * which is how a press ends up removing the row below the one under the mouse.
	 */
	private record Line(String heading, com.mopicmp.npcstudio.server.Addon addon) {

		int tall() {
			return addon == null ? KIND_HEAD : ADDON_ROW;
		}
	}

	/**
	 * How tall a heading over one kind is.
	 *
	 * Taller than a plain line of text because there is a rule under it, and a
	 * rule drawn at the height of a line of text is a rule through the tails of
	 * its letters. Text at the top, rule at the bottom, and the gap is the point.
	 */
	private static final int KIND_HEAD = 26;

	private List<Line> installedLines() {
		List<Line> lines = new ArrayList<>();
		com.mopicmp.npcstudio.server.Addon.Kind last = null;
		for (var addon : showingAddons()) {
			// Headings only when everything is shown together; with one kind
			// filtered out of three, a heading over the whole list says nothing
			// the button above it does not already say.
			if (installedShow.equals("all") && addon.kind() != last) {
				last = addon.kind();
				lines.add(new Line("npc_studio.server.installed.kind."
					+ last.name().toLowerCase(java.util.Locale.ROOT), null));
			}
			lines.add(new Line(null, addon));
		}
		return lines;
	}

	/** Which thing is at this height, or nothing when the heading is. */
	private com.mopicmp.npcstudio.server.Addon addonAt(double mouseY) {
		int y = installedTop;
		for (Line line : installedLines()) {
			if (mouseY >= y && mouseY < y + line.tall()) return line.addon();
			y += line.tall();
		}
		return null;
	}

	/**
	 * The installed list as it is being shown: filtered, then ordered.
	 *
	 * Worked out in one place and used by the drawing, the buttons and the
	 * press-to-open, because three copies of "which row is which" is three ways
	 * for a press to remove the wrong thing.
	 */
	private List<com.mopicmp.npcstudio.server.Addon> showingAddons() {
		List<com.mopicmp.npcstudio.server.Addon> showing = new ArrayList<>();
		for (var addon : installedNow()) {
			boolean keep = switch (installedShow) {
				case "mods" -> addon.kind() == com.mopicmp.npcstudio.server.Addon.Kind.MOD;
				case "plugins" -> addon.kind() == com.mopicmp.npcstudio.server.Addon.Kind.PLUGIN;
				case "datapacks" ->
					addon.kind() == com.mopicmp.npcstudio.server.Addon.Kind.DATAPACK;
				// The one worth having a filter for at all: the file that is on the
				// wrong side is the one stopping the server, and in a folder of
				// forty it is not the one you happen to look at.
				case "wrong" -> addon.wrongSide();
				default -> true;
			};
			if (keep) showing.add(addon);
		}
		java.util.Comparator<com.mopicmp.npcstudio.server.Addon> byKind = java.util.Comparator
			.comparingInt(each -> each.kind().ordinal());
		java.util.Comparator<com.mopicmp.npcstudio.server.Addon> order = switch (installedOrder) {
			case "kind" -> java.util.Comparator
				.comparing((com.mopicmp.npcstudio.server.Addon each) -> each.kind().name())
				.thenComparing(each -> each.label().toLowerCase(java.util.Locale.ROOT));
			case "version" -> java.util.Comparator
				.comparing((com.mopicmp.npcstudio.server.Addon each) -> each.version());
			case "file" -> java.util.Comparator
				.comparing((com.mopicmp.npcstudio.server.Addon each) -> each.file()
					.toLowerCase(java.util.Locale.ROOT));
			default -> java.util.Comparator
				.comparing((com.mopicmp.npcstudio.server.Addon each) -> each.label()
					.toLowerCase(java.util.Locale.ROOT));
		};
		showing.sort(order);
		return showing;
	}

	/** Where the client's own mods begin, so a press can find one. */
	private int clientModsTop;
	private boolean addonsRead;
	private int contentScroll;
	private int contentHeight;
	private Map<String, String> fromCatalogue = Map.of();

	private EditBox query;
	private String queryText = "";
	private final List<com.mopicmp.npcstudio.server.Catalogue.Found> results = new ArrayList<>();
	private boolean searched;
	private boolean searching;
	private boolean moreToFind;

	/**
	 * How many pages may be taken without anybody asking.
	 *
	 * Enough to fill the tallest window twice over, and no more: filling a screen
	 * is a courtesy, downloading a catalogue is somebody else's bandwidth.
	 */
	private static final int MOST_PAGES = 4;

	private int pagesTaken;
	private int browseScroll;
	private String installing = "";
	private String order = "relevance";

	/**
	 * What went wrong here, said here.
	 *
	 * Separate from {@link #note} because of where that one is drawn: a line in
	 * the column on the left, under the fields, above two buttons. A refusal from
	 * a catalogue is three sentences long and belongs beside the empty list it
	 * explains — put in the column it was clipped, and twice now the answer to
	 * "why did nothing happen" was a message nobody could read.
	 */
	private String trouble = "";

	/**
	 * When the search box was last typed in, and what has been asked for.
	 *
	 * A search on every keystroke is a request per letter to somebody else's
	 * service, and the answers arrive out of order — so "fab" can be overtaken by
	 * "fa" and the list ends up showing the wrong thing. Waiting for a pause
	 * costs nothing anybody notices and asks once.
	 */
	private long typedAt;
	private String asked = "";
	private boolean queryFocused;

	/** How long a still keyboard means "that is the whole word". */
	private static final long SETTLE_SEARCH_MS = 450;

	/**
	 * Where the next page begins, in the catalogue's own counting.
	 *
	 * Not the number of rows on screen: things that cannot run on a server are
	 * dropped from the page after it arrives, so the two numbers differ, and
	 * asking for "everything after the twelve I kept" fetches eight of them
	 * again. That is what put Fabric API in the list ten times.
	 */
	private int nextOffset;

	/** How much of the browse list is below the bottom of the window, in pixels. */
	private int browseHeight;

	/**
	 * Where the rows begin: under the search row, and under the key strip when a
	 * source has one.
	 */
	private int listTop(int top) {
		return top + FIELD + 8 + (wantsKey() && !needsKeyNow() ? 22 : 0);
	}

	/** The same, remembered, so a press can work out which row it landed on. */
	private int browseListTop;

	private int pointerX = -1;
	private int pointerY = -1;

	/** Where the installed rows begin, so a press can find which one it landed on. */
	private int installedTop;

	/** Which side of the catalogue is being looked at: plugins or mods. */
	private String kind = "";
	private String source = "modrinth";
	private boolean motdWasFocused;
	private boolean motdWasOpen;
	private boolean motdDismissed;

	/** A field and the frame drawn behind it, since the field draws none itself. */
	private record Framed(EditBox box, int x, int y, int width, int height) {
	}

	public ServerManagerScreen(Screen back, String id) {
		super(Component.empty());
		this.back = back;
		this.id = id;
	}

	@Override
	protected void init() {
		server = manager.servers().stream()
			.filter(each -> each.id.equals(id)).findFirst().orElse(null);
		if (server == null) {
			minecraft.setScreenAndShow(back);
			return;
		}
		if (manager.watched() == null || !manager.watched().id.equals(id)) {
			manager.watch(server);
			readProperties();
		}
		if (nameEdit == null) nameEdit = server.name;
		if (memoryEdit == 0) memoryEdit = server.memoryMb;

		left = Math.clamp(width / 3, 160, 230);
		contentLeft = left + 1;
		contentTop = TOP + TABS + 4;
		framed.clear();
		submits.clear();

		addRenderableWidget(new IconTextButton(4, 3, 16, 16, Icon.CLOSED,
			Component.translatable("npc_studio.server.back"), TEXT_DIM, this::onClose));

		column();
		tabs();
		command = null;
		if (configTextOpen) {
			// The editor is a view in its own right rather than a corner of the
			// configs tab, because the settings tab opens it too. Whichever tab it
			// was opened from stands down while it is up: a document with unsaved
			// typing in it is not something to lay a list out behind.
			configRows.clear();
			settingRows.clear();
			configTextWidgets(contentTop + 4, height - PAD);
		} else {
			switch (tab) {
				case CONSOLE -> consoleWidgets();
				case CONTENT -> contentWidgets();
				case WORLDS -> worldWidgets();
				case PLAYERS -> playerWidgets();
				case RIGHTS -> rightsWidgets();
				case CONFIGS -> configWidgets();
				case SETTINGS -> settingsWidgets();
			}
		}

		// The window for a new world goes on last and over everything, and the
		// tab behind it is built as usual. It used to be built *instead* of the
		// tab, which is why the picture, the fields and their text disappeared
		// the moment the window opened: they are widgets, and widgets that are
		// not placed do not draw. Modality is a rule about presses, not about
		// whether the screen still exists behind the thing in front of it.
		windowParts.clear();
		windowFrames.clear();
		if (windowUp() && (tab == Tab.WORLDS || window == Window.PRIVILEGE)) {
			windowFrom = children().size();
			switch (window) {
				case PLACE -> placeWindow(null);
				case PACKS -> packWindow(null);
				case RENAME -> renameWindow(null);
				case PRIVILEGE -> privilegeWindow(null);
				default -> worldWindow(null);
			}
		} else {
			windowFrom = -1;
		}
	}

	/**
	 * Where the window's own controls start in the list of widgets.
	 *
	 * Everything from here on belongs to the window in front; everything before
	 * it is the tab behind, which is drawn, dimmed and deaf.
	 */
	private int windowFrom = -1;

	/** Whether a press at this point is the window's to take. */
	private boolean insideWindow(double mouseX, double mouseY) {
		for (int at = Math.max(0, windowFrom); at < children().size(); at++) {
			if (children().get(at).isMouseOver(mouseX, mouseY)) return true;
		}
		return mouseX >= windowX && mouseX < windowX + WINDOW
			&& mouseY >= windowY && mouseY < windowY + windowTall;
	}

	private int windowX;
	private int windowY;
	private int windowTall;

	/**
	 * The window's own controls, held apart from everything else that is drawn.
	 *
	 * They go in as children — so they take presses and typing — but not as
	 * renderables, because the screen draws every renderable in one pass and the
	 * scrim has to come between the tab and the window. Drawn by hand, after it.
	 */
	private final List<AbstractWidget> windowParts = new ArrayList<>();
	private final List<Framed> windowFrames = new ArrayList<>();

	private <T extends AbstractWidget> T put(T widget) {
		if (windowFrom < 0) return addRenderableWidget(widget);
		addWidget(widget);
		windowParts.add(widget);
		return widget;
	}

	/** The window, over everything the screen has already drawn. */
	private void drawWindow(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		switch (window) {
			case PLACE -> placeWindow(graphics);
			case PACKS -> packWindow(graphics);
			case RENAME -> renameWindow(graphics);
			case PRIVILEGE -> privilegeWindow(graphics);
			default -> worldWindow(graphics);
		}
		for (Framed each : windowFrames) {
			Field.frame(graphics, each.x(), each.y(), each.width(), each.height(),
				each.box().isFocused(), each.box().isActive());
		}
		for (AbstractWidget each : windowParts) {
			each.extractRenderState(graphics, mouseX, mouseY, delta);
		}
	}

	private void rebuild() {
		if (command != null) commandText = command.getValue();
		dropdown.close();
		clearWidgets();
		init();
	}

	// ------------------------------------------------------------------ the column

	private int iconFor(int across) {
		int rest = GROUP + (LABEL + FIELD + GROUP) * 3 + LABEL + LINE + 6
			+ (LABEL + FIELD + GROUP) * 2 + PAD + 24 * 2;
		return Math.clamp(height - TOP - 6 - rest, 40, across);
	}

	private void column() {
		int across = left - PAD * 2;
		int x = PAD;
		iconSize = iconFor(across);
		// The two at the foot go in first, so that everything above them can ask
		// where they start and stop before reaching them.
		columnButtons(x, across);

		addRenderableWidget(new Picture(x, TOP + 6, iconSize));
		int y = TOP + 6 + iconSize + GROUP;

		EditBox name = Field.make(font, x, y + LABEL, across, FIELD,
			Component.translatable("npc_studio.server.name"));
		name.setMaxLength(48);
		name.setValue(nameEdit);
		name.setResponder(typed -> {
			nameEdit = typed;
			touched();
		});
		addRenderableWidget(name);
		framed.add(new Framed(name, x, y + LABEL, across, FIELD));
		y += LABEL + FIELD + GROUP;

		motdFrameY = y + LABEL;
		motd = Field.make(font, x, motdFrameY, across, FIELD,
			Component.translatable("npc_studio.server.motd"));
		motd.setMaxLength(200);
		motd.setValue(edits.getOrDefault("motd", values.getOrDefault("motd", "")));
		motd.setResponder(typed -> {
			edits.put("motd", typed);
			touched();
		});
		addRenderableWidget(motd);
		framed.add(new Framed(motd, x, motdFrameY, across, FIELD));
		y += LABEL + FIELD + GROUP;

		int coreY = y + LABEL;
		addRenderableWidget(new FlatButton(x, coreY, across, FIELD,
			Component.literal(server.core + " " + server.gameVersion + "  ▾"), ACCENT,
			() -> openCores(x, coreY + FIELD, across)));
		y += LABEL + FIELD + GROUP;

		addRenderableWidget(new IconTextButton(x, y + LABEL + LINE, across, 14, Icon.ADDRESS,
			Component.translatable("npc_studio.server.copy_address"), TEXT_DIM, () -> {
				minecraft.keyboardHandler.setClipboard(address());
				say(Component.translatable("npc_studio.server.copied").getString());
			}));
		y += LABEL + LINE + 14 + GROUP;

		// Memory, in the unit people say out loud. Megabytes are what the JVM
		// takes and are nobody's idea of an amount of memory.
		// Everything the machine has, and no lower ceiling of ours. Asking for
		// more than there is fails at start-up rather than quietly — and that
		// failure is now caught and shown as a window, which is the honest way
		// round: the choice is theirs and the consequence is explained when it
		// arrives, rather than the choice being taken away in advance.
		double most = Math.max(1, Machine.totalMemoryMb() / 1024.0);
		NumberField memory = new NumberField(x, y + LABEL, across, FIELD,
			Component.translatable("npc_studio.server.memory"),
			memoryEdit / 1024.0, 0.5, Math.round(most * 2) / 2.0, 0.5, 1,
			" " + Component.translatable("npc_studio.server.gigabytes").getString(),
			chosen -> {
				memoryEdit = (int) Math.round(chosen * 1024);
				touched();
			});
		memory.active = manager.state() == ServerManager.State.DOWN;
		addRenderableWidget(memory);

		// How much of the machine, in the two forms a machine understands. There
		// is no setting anywhere that means "forty per cent of the processor";
		// there is "on how many cores" and "who yields first", and between them
		// they do the job. Both are read when the process starts, so both are
		// grey while it is running.
		y += LABEL + FIELD + GROUP;
		// Only when there is room above the buttons. The window can be short, the
		// picture stops shrinking at forty pixels, and past that something has to
		// give — better a row that is not there than a row underneath "stop".
		machineRow = y + LABEL + FIELD <= buttonsFrom() - 4;
		int half = (across - 6) / 2;
		int allCores = Machine.cores();
		if (!machineRow) return;
		NumberField cores = new NumberField(x, y + LABEL, half, FIELD,
			Component.translatable("npc_studio.server.cores"),
			server.cores == 0 ? allCores : server.cores, 1, allCores, 1, 0,
			"", chosen -> {
				int wanted = (int) Math.round(chosen);
				server.cores = wanted >= allCores ? 0 : wanted;
				touched();
			});
		cores.active = manager.state() == ServerManager.State.DOWN;
		addRenderableWidget(cores);

		final int priorityX = x + half + 6;
		final int priorityWide = across - half - 6;
		final int priorityY = y + LABEL;
		FlatButton priority = new FlatButton(priorityX, priorityY, priorityWide, FIELD,
			Component.literal(Component.translatable("npc_studio.server.priority."
				+ server.priority.toLowerCase(java.util.Locale.ROOT)).getString() + "  ▾"),
			ACCENT, () -> {
				List<Dropdown.Option> options = new ArrayList<>();
				for (String each : new String[] {"NORMAL", "BELOW", "LOW"}) {
					options.add(new Dropdown.Option(each, Component.translatable(
						"npc_studio.server.priority." + each.toLowerCase(java.util.Locale.ROOT))));
				}
				dropdown.open(priorityX, priorityY + FIELD, priorityWide, height, options,
					server.priority, picked -> {
						server.priority = picked;
						touched();
						rebuild();
					});
			});
		priority.active = manager.state() == ServerManager.State.DOWN;
		addRenderableWidget(priority);
	}

	/** Where the buttons at the foot of the column begin, which the fields respect. */
	private int buttonsFrom() {
		return height - PAD - 20 - (manager.ready() ? 24 : 0);
	}

	/** Whether the cores and the priority fitted in this window at all. */
	private boolean machineRow;

	private void columnButtons(int x, int across) {
		int bottom = height - PAD - 20;
		if (manager.ready()) {
			addRenderableWidget(new IconTextButton(x, bottom, across, 20, Icon.PLAY,
				Component.translatable("npc_studio.server.join"), ACCENT, this::join));
			bottom -= 24;
		}
		addRenderableWidget(power(x, bottom, across));
		// Remembered so that the line of news above has somewhere to stop. It used
		// to be written from where the fields end downwards, over whatever was
		// there — and what was there is the two buttons, so the one message that
		// mattered, the reason a join was refused, was drawn underneath them.
		buttonsTop = bottom;
	}

	private int buttonsTop;

	private String myName() {
		return minecraft.getUser().getName();
	}

	/**
	 * A picture, which is also the way to change it.
	 *
	 * Two of them now — the server's own and each world's — and one class, because
	 * the second copy of the hover caption is the second place for the two to stop
	 * behaving alike. What differs between them is the file behind it and what
	 * pressing it does, so those are what it takes.
	 */
	private final class Picture extends AbstractWidget {

		/** Below this a picture is too small to write on, and gets an icon instead. */
		private static final int ROOM_FOR_WORDS = 56;

		private final java.util.function.Supplier<Identifier> file;
		private final Icon blank;
		private final Runnable choose;

		Picture(int x, int y, int size) {
			this(x, y, size, Component.translatable("npc_studio.server.icon"),
				() -> ServerIcon.of(server), Icon.SERVER, () -> {
					String said = ServerIconPick.choose(server.path());
					ServerIcon.forget(server.id);
					if (!said.isEmpty()) say(said);
				});
		}

		Picture(int x, int y, int size, Component label,
				java.util.function.Supplier<Identifier> file, Icon blank, Runnable choose) {
			super(x, y, size, size, label);
			this.file = file;
			this.blank = blank;
			this.choose = choose;
			if (size < ROOM_FOR_WORDS) {
				setTooltip(net.minecraft.client.gui.components.Tooltip.create(label));
			}
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor graphics,
				int mouseX, int mouseY, float delta) {
			graphics.fill(getX() - 1, getY() - 1, getX() + width + 1, getY() + height + 1,
				isHovered() ? ACCENT : EDGE);
			Identifier picture = file.get();
			if (picture != null) {
				graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, picture,
					getX(), getY(), 0, 0, width, height, 64, 64, 64, 64);
			} else {
				graphics.fill(getX(), getY(), getX() + width, getY() + height, 0xFF0C0F13);
				blank.draw(graphics, getX() + (width - Icon.SIZE) / 2,
					getY() + (height - Icon.SIZE) / 2, TEXT_DIM);
			}
			if (!isHovered()) return;

			// Under the mouse the picture darkens and says what pressing it does,
			// in the middle and larger than the labels around it — a caption over
			// a picture is not a label beside a field, and reading it should not
			// be work.
			graphics.fill(getX(), getY(), getX() + width, getY() + height, 0xB0000000);

			// Unless there is no room for words. On the picture in a list row the
			// caption was three lines of broken text spilling over the name beside
			// it: at thirty-two pixels a sentence does not fit and pretending it
			// does is worse than not saying it. One icon says the same thing, and
			// the words are there for anybody who waits for the tooltip.
			if (width < ROOM_FOR_WORDS) {
				Icon.IMAGE.draw(graphics, getX() + (width - Icon.SIZE) / 2,
					getY() + (height - Icon.SIZE) / 2, 0xFFFFFFFF);
				return;
			}
			var font = net.minecraft.client.Minecraft.getInstance().font;
			var pose = graphics.pose();
			float scale = Math.min(1.8f, width / 70f + 0.9f);
			pose.pushMatrix();
			pose.translate(getX() + width / 2f, getY() + height / 2f);
			pose.scale(scale, scale);
			List<net.minecraft.util.FormattedCharSequence> lines =
				font.split(getMessage(), (int) (width / scale) - 6);
			int y = -(lines.size() * 9) / 2;
			for (var line : lines) {
				graphics.text(font, line, -font.width(line) / 2, y, 0xFFFFFFFF);
				y += 9;
			}
			pose.popMatrix();
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			choose.run();
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}

	private IconTextButton power(int x, int y, int across) {
		return switch (manager.state()) {
			case DOWN -> new IconTextButton(x, y, across, 20, Icon.POWER,
				Component.translatable("npc_studio.server.start"), GOOD,
				() -> {
					applyMemory();
					manager.start(server);
					rebuild();
				});
			case STARTING -> quiet(x, y, across, Icon.POWER, "npc_studio.server.starting", WARN);
			case UP -> new IconTextButton(x, y, across, 20, Icon.STOP,
				Component.translatable("npc_studio.server.stop"), WARN,
				() -> {
					manager.stop(server);
					rebuild();
				});
			case STOPPING -> quiet(x, y, across, Icon.STOP, "npc_studio.server.stopping", TEXT_DIM);
		};
	}

	private IconTextButton quiet(int x, int y, int across, Icon icon, String key, int colour) {
		IconTextButton button = new IconTextButton(x, y, across, 20, icon,
			Component.translatable(key), colour, () -> {
			});
		button.active = false;
		return button;
	}

	// ------------------------------------------------------------------ the core

	private void openCores(int x, int y, int across) {
		if (manager.state() != ServerManager.State.DOWN) {
			say(Component.translatable("npc_studio.server.stop_first").getString());
			return;
		}
		List<Dropdown.Option> options = new ArrayList<>();
		for (CoreCatalog.Core core : manager.catalog().cores()) {
			options.add(new Dropdown.Option(core.id(), Component.literal(core.name())));
		}
		dropdown.open(x, y, across, height, options, server.core,
			picked -> manager.catalog().byId(picked)
				.ifPresent(core -> openVersions(core, x, y, across)));
	}

	private void openVersions(CoreCatalog.Core core, int x, int y, int across) {
		say(Component.translatable("npc_studio.server.create.asking").getString());
		manager.versions(core, versions -> {
			note = "";
			List<Dropdown.Option> options = new ArrayList<>();
			for (String version : versions) options.add(Dropdown.Option.of(version));
			dropdown.open(x, y, across, height, options, server.gameVersion,
				version -> askAboutCore(core, version));
		}, this::say);
	}

	private void askAboutCore(CoreCatalog.Core core, String version) {
		if (core.id().equals(server.core) && version.equals(server.gameVersion)) return;
		confirm.ask(
			Component.translatable("npc_studio.server.core_warning.title"),
			Component.translatable("npc_studio.server.core_warning.body",
				server.core + " " + server.gameVersion, core.name() + " " + version),
			Component.translatable("npc_studio.server.core_warning.yes"),
			Component.translatable("npc_studio.server.core_warning.no"),
			() -> manager.reinstall(server, core, version,
				said -> note = said,
				() -> {
					say(Component.translatable("npc_studio.server.changed_core").getString());
					rebuild();
				},
				failed -> {
					say(failed);
					rebuild();
				}));
	}

	// ------------------------------------------------------------------ the rest of the column

	private String address() {
		return "127.0.0.1:" + server.port;
	}

	private boolean signedIn() {
		return minecraft.getUser().getXuid().filter(each -> !each.isBlank()).isPresent();
	}

	/**
	 * Go in, or say plainly why not and offer the one thing that fixes it.
	 *
	 * This was a line of small print at the bottom of the column, and the column
	 * ends in two buttons — so the message was drawn behind them and the join
	 * simply did nothing. A refusal with a reason nobody can read is a fault, not
	 * a message, and the reason here is one somebody can act on: the server is
	 * checking accounts with Mojang and this game is not signed in to one.
	 *
	 * The offer is the honest fix rather than a workaround. Turning the check off
	 * on a server that lives on this machine and is reached at 127.0.0.1 costs
	 * nothing real; on one that other people can reach it costs a great deal, and
	 * the window says so rather than only saying "yes" and "no".
	 */
	private void join() {
		boolean checksAccounts = Boolean.parseBoolean(
			edits.getOrDefault("online-mode", values.getOrDefault("online-mode", "true")));
		if (checksAccounts && !signedIn()) {
			confirm.ask(
				Component.translatable("npc_studio.server.no_session.title"),
				Component.translatable("npc_studio.server.no_session.body"),
				Component.translatable("npc_studio.server.no_session.yes"),
				Component.translatable("npc_studio.server.no_session.no"),
				() -> {
					edits.put("online-mode", "false");
					saveSettled();
					// And the thing that goes wrong afterwards, fixed here because
					// here is where it is caused. A server that checks accounts
					// knows people by the uuid Mojang keeps; one that does not
					// makes a uuid up from the name. Operators written under the
					// first rule do not match under the second — which is why
					// somebody who had given themselves op, turned the check off,
					// and come back was not an operator any more. Their names are
					// still in the file, so the file can simply be rewritten to
					// the names' new identities.
					manager.opsToOffline(server, moved -> {
						say(Component.translatable(manager.running()
							? "npc_studio.server.no_session.restart"
							: "npc_studio.server.no_session.done").getString());
						if (moved > 0) {
							say(Component.translatable("npc_studio.server.no_session.ops",
								moved).getString());
						}
					}, said -> say(said));
				});
			return;
		}
		String where = address();
		ServerData data = new ServerData(
			server.name.isBlank() ? server.id : server.name, where, ServerData.Type.OTHER);
		ConnectScreen.startConnecting(this, minecraft,
			ServerAddress.parseString(where), data, false, null);
	}

	// ------------------------------------------------------------------ tabs

	/**
	 * The tabs, drawn as one strip rather than as buttons that happen to be next
	 * to each other. Touching, with a hairline between them, a filled one for the
	 * tab you are on and a line under it: five separate rectangles read as five
	 * separate controls.
	 */
	private void tabs() {
		// Flush against the divider and as tall as the strip they sit in. They were
		// a row of buttons floating inside a bar, which reads as a row of buttons;
		// touching the line above and the line below is what makes a tab a tab.
		int x = contentLeft;
		for (Tab which : Tab.values()) {
			Component label = Component.translatable(which.label);
			int across = IconTextButton.wide(label);
			addRenderableWidget(new TabButton(x, TOP - 4, across, TABS + 1, which, label));
			x += across;
		}

		if (tab != Tab.CONSOLE) return;
		int right = width - PAD;
		for (Object[] each : new Object[][] {
			{Icon.REMOVE, "npc_studio.server.console_clear", (Runnable) this::clearConsole},
			{Icon.SAVE, "npc_studio.server.console_save", (Runnable) this::saveConsole},
			{Icon.COPY, "npc_studio.server.console_copy", (Runnable) this::copyConsole}}) {
			right -= IconTextButton.FOLDED + 3;
			addRenderableWidget(new IconTextButton(right, TOP - 1, IconTextButton.FOLDED,
				IconTextButton.FOLDED, (Icon) each[0],
				Component.translatable((String) each[1]), TEXT_DIM, (Runnable) each[2]));
		}
	}

	private final class TabButton extends AbstractWidget {

		private final Tab which;

		TabButton(int x, int y, int width, int height, Tab which, Component label) {
			super(x, y, width, height, label);
			this.which = which;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor graphics,
				int mouseX, int mouseY, float delta) {
			boolean here = which == tab;
			boolean lit = isHovered();
			graphics.fill(getX(), getY(), getX() + width, getY() + height,
				here ? PANEL : lit ? 0xFF171C23 : BAR);
			graphics.fill(getX() + width - 1, getY() + 3, getX() + width, getY() + height - 3, EDGE);
			if (here) {
				graphics.fill(getX(), getY() + height - 2, getX() + width, getY() + height, ACCENT);
			}
			var font = net.minecraft.client.Minecraft.getInstance().font;
			int ink = here ? TEXT : lit ? ACCENT : TEXT_DIM;
			which.icon.draw(graphics, getX() + 6, getY() + (height - Icon.SIZE) / 2, ink);
			graphics.text(font, getMessage(), getX() + 6 + Icon.SIZE + 4,
				getY() + (height - 8) / 2, ink);
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			goTo(which);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}

	/**
	 * Move to a tab, asking first if the editor has something unwritten in it.
	 *
	 * Leaving by a tab is leaving. Half an hour of typing in the editor exists
	 * nowhere but that box, and a tab is the one way out of it that used not to
	 * notice — the button and Escape both asked, and the tab strip took the
	 * document away without a word.
	 */
	private void goTo(Tab which) {
		if (which == tab && !configTextOpen) return;
		if (configTextOpen && !configLoading && !configTextNow.equals(configTextWas)) {
			confirm.ask(
				Component.translatable("npc_studio.server.configs.drop_title"),
				Component.translatable("npc_studio.server.configs.drop_body", editingName),
				Component.translatable("npc_studio.server.configs.drop_yes"),
				Component.translatable("npc_studio.server.no_session.no"),
				() -> {
					configTextOpen = false;
					configText = null;
					editingPath = null;
					tab = which;
					rebuild();
				});
			return;
		}
		configTextOpen = false;
		configText = null;
		editingPath = null;
		tab = which;
		rebuild();
	}

	// ------------------------------------------------------------------ console

	private void consoleWidgets() {
		int y = height - FIELD - PAD;
		int sendWidth = 24;
		int x = contentLeft + PAD;
		int across = width - PAD - x - sendWidth - 4;

		command = Field.make(font, x, y, across, FIELD,
			Component.translatable("npc_studio.server.command"));
		command.setMaxLength(1400);
		command.setValue(commandText);
		command.setEditable(manager.ready());
		addRenderableWidget(command);
		framed.add(new Framed(command, x, y, across, FIELD));

		IconTextButton send = new IconTextButton(x + across + 4, y, sendWidth, FIELD,
			Icon.SEND, Component.translatable("npc_studio.server.send"), ACCENT, this::sendCommand);
		send.active = manager.ready();
		addRenderableWidget(send);
	}

	private void sendCommand() {
		if (command == null || !manager.ready()) return;
		String typed = command.getValue().trim();
		if (typed.isEmpty()) return;
		manager.send(server, typed);
		command.setValue("");
		commandText = "";
		consoleScroll = 0;
	}

	private int consoleBottom() {
		return height - FIELD - PAD * 2;
	}

	private int consoleFits() {
		return Math.max(1, (consoleBottom() - contentTop - 4) / LINE);
	}

	/** Which console line the mouse is over, as a position in the whole console. */
	private int lineAt(double mouseY) {
		List<String> console = manager.console();
		int fits = consoleFits();
		int last = console.size() - consoleScroll;
		int first = Math.max(0, last - fits);
		int row = (int) ((mouseY - contentTop - 2) / LINE);
		int at = first + row;
		return at >= first && at < last ? at : -1;
	}

	private String pickedText() {
		List<String> console = manager.console();
		if (pickedFrom < 0 || pickedTo < 0) return String.join("\n", console);
		int from = Math.min(pickedFrom, pickedTo);
		int to = Math.max(pickedFrom, pickedTo);
		StringBuilder out = new StringBuilder();
		for (int at = from; at <= to && at < console.size(); at++) {
			if (at >= 0) out.append(console.get(at)).append('\n');
		}
		return out.toString();
	}

	private void copyConsole() {
		String text = pickedText();
		if (text.isBlank()) return;
		minecraft.keyboardHandler.setClipboard(text);
		say(Component.translatable("npc_studio.server.console_copied").getString());
	}

	/** Writes what is in the console to a file somebody chooses. */
	private void saveConsole() {
		String said = ConsoleFile.save(server, manager.console());
		if (!said.isEmpty()) say(said);
	}

	private void clearConsole() {
		manager.clearConsole();
		pickedFrom = -1;
		pickedTo = -1;
	}

	// ------------------------------------------------------------------ content

	/**
	 * What is installed, read from the jars rather than asked of the server.
	 *
	 * Which matters most exactly when the server will not start, because the
	 * thing stopping it is usually in this list — and a list that can only be
	 * seen while the server is up would be missing then.
	 */
	/**
	 * Read what is installed, and what came from where, and only then redraw.
	 *
	 * Both, and the redraw after both, because the browser's button depends on
	 * the two together: with the list back but the note not, an installed thing
	 * still says "install" — which is exactly what it did.
	 */
	private void readAddons() {
		ManagedServer which = server;
		manager.dataPacks(which, manager.currentWorld(), found -> {
			if (server == which) installedPacks = found;
		});
		addonsRead = false;
		boolean[] arrived = {false, false};
		manager.addons(which, found -> {
			if (server != which) return;
			addons = found;
			addonsRead = true;
			arrived[0] = true;
			if (arrived[1] && tab == Tab.CONTENT) rebuild();
		});
		manager.index(which, index -> {
			if (server != which) return;
			fromCatalogue = index;
			arrived[1] = true;
			if (arrived[0] && tab == Tab.CONTENT) rebuild();
		});
	}

	/** The strip of two, and the row of buttons that belongs to whichever is on. */
	private void contentWidgets() {
		int x = contentLeft + PAD;
		int y = contentTop + 4;

		int at = contentLeft;
		for (Half which : Half.values()) {
			Component label = Component.translatable(which.label);
			int across = font.width(label) + 24;
			addRenderableWidget(new SubTab(at, y - 4, across, 18, which == half, label, () -> {
				half = which;
				// Leaving the browser leaves whatever page was open in it: coming
				// back to a project you last looked at three tabs ago is a surprise.
				closeProject();
			}));
			at += across;
		}

		y += 22;
		if (half == Half.INSTALLED) installedWidgets(y);
		else browseWidgets(y);
	}

	private void installedWidgets(int top) {
		if (!addonsRead) {
			readAddons();
			return;
		}
		int x = contentLeft + PAD;
		int right = width - PAD;

		addRenderableWidget(new IconTextButton(x, top, 110, 18, Icon.ADD,
			Component.translatable("npc_studio.server.content.add"), GOOD, this::addAddons));
		addRenderableWidget(new IconTextButton(x + 114, top, 90, 18, Icon.FOLDER,
			Component.translatable("npc_studio.server.folder"), TEXT_DIM,
			() -> net.minecraft.util.Util.getPlatform()
				.openPath(com.mopicmp.npcstudio.server.Addons.folder(server))));

		// The same two controls the browser has, for the same reason: a folder
		// with forty things in it is a folder somebody is looking for one thing
		// in. On the right, so that the two lists read the same way round.
		int sortWidth = 128;
		int showWidth = 96;
		int sortX = right - sortWidth - showWidth - 4;
		addRenderableWidget(new FlatButton(sortX, top, sortWidth, 18,
			Component.literal(Component.translatable("npc_studio.server.installed.by." + installedOrder)
				.getString() + "  ▾"), ACCENT,
			() -> {
				List<Dropdown.Option> options = new ArrayList<>();
				for (String each : new String[] {"name", "kind", "version", "file"}) {
					options.add(new Dropdown.Option(each,
						Component.translatable("npc_studio.server.installed.by." + each)));
				}
				dropdown.open(sortX, top + 18, sortWidth, height, options, installedOrder,
					picked -> {
						installedOrder = picked;
						rebuild();
					});
			}));
		int showX = sortX + sortWidth + 4;
		addRenderableWidget(new FlatButton(showX, top, showWidth, 18,
			Component.literal(Component.translatable("npc_studio.server.installed.show." + installedShow)
				.getString() + "  ▾"),
			installedShow.equals("all") ? ACCENT : WARN,
			() -> {
				List<Dropdown.Option> options = new ArrayList<>();
				for (String each : new String[] {"all", "mods", "plugins", "datapacks", "wrong"}) {
					options.add(new Dropdown.Option(each,
						Component.translatable("npc_studio.server.installed.show." + each)));
				}
				dropdown.open(showX, top + 18, showWidth, height, options, installedShow,
					picked -> {
						installedShow = picked;
						contentScroll = 0;
						rebuild();
					});
			}));

		Component remove = Component.translatable("npc_studio.server.content.remove");
		int y = top + 24 - contentScroll;
		int bottom = height - PAD;
		// The line that says there is nothing here takes a row, in the layout as
		// well as in the drawing — the two counting differently is what put two
		// sentences on top of each other.
		List<Line> lines = installedLines();
		if (lines.isEmpty()) y += HEADER;
		for (Line line : lines) {
			com.mopicmp.npcstudio.server.Addon addon = line.addon();
			if (addon == null) {
				y += line.tall();
				continue;
			}
			// The one on its way out loses its button first: a control on a row
			// that is fading is a control somebody can press twice.
			int step = shut("jar:" + addon.file(), ADDON_ROW);
			if (y >= top + 22 && y + ADDON_ROW <= bottom && step == ADDON_ROW) {
				if (addon.kind() == com.mopicmp.npcstudio.server.Addon.Kind.DATAPACK) {
					// Not the same "remove" at all: a datapack lives in a world, is
					// switched on in that world's own data, and taking it out is the
					// world's business. So the row opens the window that does that
					// rather than pretending to be a jar in the mods folder.
					Component settings = Component.translatable("npc_studio.server.packs.open");
					addRenderableWidget(new Pill(right - 4 - Pill.wide(settings), y + 6,
						Icon.ASSETS, settings, ACCENT, () -> openPacksOfCurrent()));
				} else {
					addRenderableWidget(new Pill(right - 4 - Pill.wide(remove), y + 6, Icon.REMOVE,
						remove, WARN, () -> removeInstalled(addon.file())));
				}
			}
			y += ADDON_ROW;
		}

		// And under them, what this game is running that the server could run too.
		clientModsY = y;
		if (takesMods()) {
			List<ClientMods.Mod> waiting = clientMods();
			if (!waiting.isEmpty() && y >= top && y + CLIENT_HEADER <= bottom) {
				// On the heading's own line, because it is the heading's verb: a
				// modded client and a fresh server almost always want the same
				// list, and doing it one row at a time is the same work done
				// twenty times.
				Component all = Component.translatable("npc_studio.server.content.all_to_server",
					waiting.size());
				addRenderableWidget(new Pill(right - 4 - Pill.wide(all), y, Icon.ADD, all, GOOD,
					() -> putAllOnServer(waiting)));
			}
		}
		// Taller than a plain heading, because there is a button on this line: a
		// heading is eight pixels of text and a button is eighteen, and using the
		// heading's height put the button through the row below it.
		y += CLIENT_HEADER;
		if (takesMods()) {
			Component put = Component.translatable("npc_studio.server.content.to_server");
			List<ClientMods.Mod> mine = clientMods();
			for (ClientMods.Mod mod : mine) {
				if (y >= top + 22 && y + ADDON_ROW <= bottom) {
					addRenderableWidget(new Pill(right - 4 - Pill.wide(put), y + 6, Icon.ADD,
						put, GOOD, () -> putOnServer(mod)));
				}
				y += ADDON_ROW;
			}
			if (mine.isEmpty()) y += HEADER;
		} else {
			y += HEADER;
		}
		contentHeight = Math.max(0, y + contentScroll + PAD - bottom);
	}

	/**
	 * A small button with a word on it, for the end of a row.
	 *
	 * The list had a full-width one at first, which read as the row rather than
	 * as a thing to press in it — a button as wide as a paragraph is a banner.
	 * This one is as wide as its word and no wider.
	 */
	private final class Pill extends AbstractWidget {

		private final Icon icon;
		private final int accent;
		private final Runnable onPress;

		/**
		 * How wide this button has to be for its word to fit inside it.
		 *
		 * Asked before it is placed rather than guessed at seventy pixels: the
		 * words are translated, and a number that fits "remove" does not fit
		 * «поставить».
		 */
		static int wide(Component label) {
			return 5 + Icon.SIZE + 3 + net.minecraft.client.Minecraft.getInstance().font
				.width(label) + 6;
		}

		Pill(int x, int y, Icon icon, Component label, int accent, Runnable onPress) {
			super(x, y, wide(label), 18, label);
			this.icon = icon;
			this.accent = accent;
			this.onPress = onPress;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor graphics,
				int mouseX, int mouseY, float delta) {
			boolean lit = isHovered() && active;
			graphics.fill(getX(), getY(), getX() + width, getY() + height,
				lit ? accent : 0xFF232A34);
			graphics.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1,
				lit ? 0xFF232A34 : 0xFF1B2028);
			var font = net.minecraft.client.Minecraft.getInstance().font;
			int ink = active ? (lit ? accent : TEXT) : TEXT_DIM;
			icon.draw(graphics, getX() + 5, getY() + 1, ink);
			graphics.text(font, getMessage(), getX() + 5 + Icon.SIZE + 3, getY() + 5, ink);
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			if (active) onPress.run();
		}

		/**
		 * Enter and space press it, as they do on every other button anywhere.
		 *
		 * These widgets are built on the plain one rather than on the game's own
		 * button, which draws itself and nothing else — so keyboard use had to be
		 * written in, and it was not. Tab reached a control and then there was no
		 * way to use it.
		 */
		@Override
		public boolean keyPressed(KeyEvent event) {
			if (active && pressing(event)) {
				onPress.run();
				return true;
			}
			return super.keyPressed(event);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}

	// ------------------------------------------------------------------ browsing

	/** What this server's own core takes: {@code plugins} or {@code mods}. */
	private String ownKind() {
		return com.mopicmp.npcstudio.server.Addons.kindFor(server.core)
			== com.mopicmp.npcstudio.server.Addon.Kind.PLUGIN ? "plugins" : "mods";
	}

	private String kind() {
		return kind.isBlank() ? ownKind() : kind;
	}

	/**
	 * Which loader the catalogue is asked about.
	 *
	 * The server's own when looking at what it takes, and a stand-in for the
	 * other side when looking at the other side — because there is no such thing
	 * as searching for "plugins" in general: the catalogue tells them apart by
	 * the loader and by nothing else.
	 */
	/**
	 * Where an install lands: the mods folder, or a world's datapacks.
	 *
	 * Null means "wherever this catalogue would normally put it", which is the
	 * answer for everything that is a jar.
	 */
	private java.nio.file.Path intoFolder() {
		String where = landingNow();
		if (where.startsWith("pack:")) {
			return DataPacks.folder(server, where.substring("pack:".length()));
		}
		// Into plugins/ on a core that takes mods, where the bridge mod will look
		// for it. Into mods/ it would be a file nothing ever opens.
		if (where.equals("plugins") && !ownKind().equals("plugins")) {
			return server.path().resolve("plugins");
		}
		if (where.equals("mods") && ownKind().equals("plugins")) {
			return server.path().resolve("mods");
		}
		return null;
	}

	/**
	 * Where an install will land: {@code mods}, {@code plugins}, {@code pack:<world>}.
	 *
	 * Chosen, not deduced. This began as a line of text stating where the file was
	 * going, which answered the question and then refused to do anything about it —
	 * and a project exists on more than one shelf often enough for the answer to be
	 * the wrong one: Guns++ is a mod and a datapack, and which of them somebody
	 * wants is not something the shelf they searched from can know.
	 *
	 * <p>Blank means nobody has said, and then the shelf decides — which is right
	 * nearly always and is why it is the default rather than a question.
	 */
	private String landing = "";

	private String landingNow() {
		if (!landing.isBlank()) return landing;
		if (kind().equals("datapacks")) return "pack:" + manager.currentWorld();
		return kind().equals("plugins") ? "plugins" : ownKind();
	}

	/** What that destination is called, short enough for a button. */
	private Component landingSays(String where) {
		if (where.startsWith("pack:")) {
			return Component.translatable("npc_studio.server.browse.into_datapacks",
				where.substring("pack:".length()));
		}
		// The bridge is part of the choice rather than a warning after it: a plugin
		// on a Fabric server is a file the server ignores until that mod is there,
		// and knowing it afterwards is knowing it too late.
		if (where.equals("plugins") && !ownKind().equals("plugins")) {
			return Component.translatable("npc_studio.server.browse.into_plugins_bridge");
		}
		return Component.translatable("npc_studio.server.browse.into_" + where);
	}

	/**
	 * Every folder an install could go to on this server.
	 *
	 * Both jar folders always, whichever the core takes, and a datapack folder for
	 * each world — because a server with three worlds has three answers to "where
	 * do datapacks go" and only the person looking knows which one they meant.
	 */
	private List<Dropdown.Option> landings() {
		List<Dropdown.Option> options = new ArrayList<>();
		options.add(new Dropdown.Option("mods", landingSays("mods")));
		options.add(new Dropdown.Option("plugins", landingSays("plugins")));
		List<String> names = new ArrayList<>();
		for (var world : worlds) names.add(world.name());
		if (names.isEmpty()) names.add(manager.currentWorld());
		for (String world : names) {
			options.add(new Dropdown.Option("pack:" + world, landingSays("pack:" + world)));
		}
		return options;
	}

	private String loader() {
		if (kind().equals("datapacks")) return com.mopicmp.npcstudio.server.Catalogue.DATAPACK;
		String own = com.mopicmp.npcstudio.server.Catalogue.loaderFor(server.core);
		if (kind().equals(ownKind())) return own;
		return kind().equals("plugins") ? "paper" : "fabric";
	}

	private void chooseSource(String picked) {
		var chosen = manager.catalog().source(picked).orElse(null);
		if (chosen == null) return;
		if (!chosen.usable()) {
			// Named rather than hidden, and the reason said out loud: a shop that
			// is simply not on the shelf teaches nothing.
			say(Component.translatable("npc_studio.server.browse.not_open",
				chosen.name()).getString());
			rebuild();
			return;
		}
		source = picked;
		opened = null;
		page = null;
		pageLoading = false;
		results.clear();
		nextOffset = 0;
		browseScroll = 0;
		searched = false;
		keyTyped = "";
		keyEditing = false;
		keyAsked = false;
		keyShown = "";
		keyScroll = 0;
		trouble = "";
		// A source that wants a key of its own does not get searched until it has
		// one; asking anyway would spend the trip to find out what is already known
		// and come back with somebody else's error message.
		if (chosen.needsKey() && !manager.hasKey(picked)) {
			rebuild();
			return;
		}
		search(true);
	}

	// ------------------------------------------------------------------ the key

	/** Whether the field for a key is showing, which it is until one is kept. */
	private boolean keyEditing;
	private String keyTyped = "";
	private String keyShown = "";
	private boolean keyAsked;
	private EditBox keyBox;

	private boolean wantsKey() {
		var here = manager.catalog().source(source).orElse(null);
		return here != null && here.needsKey();
	}

	private boolean needsKeyNow() {
		return wantsKey() && (keyEditing || !manager.hasKey(source));
	}

	/** Somewhere to write when this goes wrong. Plain text, so it can be copied. */
	private static final String SUPPORT = "mopicmpsupport@gmail.com";

	/** How far down the key panel has been pushed, when it is taller than the window. */
	private int keyScroll;

	/** How much of it is below the bottom. */
	private int keyHeight;

	/**
	 * Every place in the key panel, worked out once and read twice.
	 *
	 * The panel is placed by one method and drawn by another, and almost every
	 * position in it depends on how tall a wrapped paragraph came out. Two methods
	 * doing that arithmetic separately is two methods that agree until the window
	 * is resized — so it is done here and both of them are told the answer.
	 */
	private record KeyPlaces(int cardX, int card, int x, int inner, int cardTop, int titleY,
			int howY, int getY, int getWidth, int fieldY, int fieldWidth, int cancelY,
			int troubleY, int whereY, int termsY, int mailY, int cardBottom) {
	}

	/** How wide the card may be. Past this a paragraph becomes a line on a page. */
	private static final int KEY_CARD = 460;

	private static final int KEY_PAD = 16;

	private KeyPlaces keyPlaces(int top) {
		var here = manager.catalog().source(source).orElse(null);
		String name = here == null ? source : here.name();
		int area = width - contentLeft - PAD * 2;
		int card = Math.max(200, Math.min(KEY_CARD, area));
		int cardX = contentLeft + PAD + Math.max(0, (area - card) / 2);
		int x = cardX + KEY_PAD;
		int inner = card - KEY_PAD * 2;

		int cardTop = top + FIELD + 10 - keyScroll;
		int titleY = cardTop + KEY_PAD;
		int howY = titleY + 16;
		Component how = Component.translatable("npc_studio.server.key.how", name);
		int getY = howY + font.wordWrapHeight(how, inner) + 10;
		int getWidth = here == null || here.keys().isBlank() ? 0
			: IconTextButton.wide(Component.translatable("npc_studio.server.key.open", name));
		int fieldY = getY + (getWidth > 0 ? 26 : 0);
		int fieldWidth = inner - Pill.wide(Component.translatable("npc_studio.server.key.keep")) - 6;
		int cancelY = fieldY + FIELD + 8;
		int troubleY = cancelY + (manager.hasKey(source) ? 24 : 0);
		int whereY = troubleY + (trouble.isEmpty() ? 0
			: font.wordWrapHeight(Component.literal(trouble), inner) + 10);
		int termsY = whereY + font.wordWrapHeight(
			Component.translatable("npc_studio.server.key.where"), inner) + 10;
		int mailY = termsY + font.wordWrapHeight(
			Component.translatable("npc_studio.server.key.terms"), inner) + 10;
		int cardBottom = mailY + 12 + 18 + KEY_PAD;

		return new KeyPlaces(cardX, card, x, inner, cardTop, titleY, howY, getY, getWidth,
			fieldY, fieldWidth, cancelY, troubleY, whereY, termsY, mailY, cardBottom);
	}

	/**
	 * The panel that stands in for the list until a key is given.
	 *
	 * It says four things, in this order: what is needed, where to get it, what
	 * happens to it afterwards, and who is answerable for it. The last two are not
	 * decoration — somebody being asked to paste a credential into a game mod is
	 * owed a straight account of where it will live and of what is not being
	 * promised, and a mod that gives neither has earned the suspicion.
	 */
	private void keyWidgets(int top) {
		var here = manager.catalog().source(source).orElse(null);
		if (here == null) return;
		KeyPlaces at = keyPlaces(top);
		keyHeight = Math.max(0, at.cardBottom() + keyScroll - (height - PAD));

		if (at.getWidth() > 0) {
			addRenderableWidget(new IconTextButton(at.x(), at.getY(), at.getWidth(), 18,
				Icon.ADDRESS, Component.translatable("npc_studio.server.key.open", here.name()),
				here.colour(),
				() -> net.minecraft.util.Util.getPlatform().openUri(here.keys())));
		}

		keyBox = Field.make(font, at.x(), at.fieldY(), at.fieldWidth(), FIELD,
			Component.translatable("npc_studio.server.key.field"));
		keyBox.setMaxLength(256);
		keyBox.setValue(keyTyped);
		keyBox.setResponder(typed -> keyTyped = typed);
		// Drawn as stars while it is being typed as well as afterwards. Pasting
		// does not need to be read back, and a key on screen is a key in whatever
		// happens to be recording the screen.
		keyBox.addFormatter((text, position) ->
			Component.literal("*".repeat(text.length())).getVisualOrderText());
		addRenderableWidget(keyBox);
		framed.add(new Framed(keyBox, at.x(), at.fieldY(), at.fieldWidth(), FIELD));

		addRenderableWidget(new Pill(at.x() + at.fieldWidth() + 6, at.fieldY(), Icon.SAVE,
			Component.translatable("npc_studio.server.key.keep"), GOOD, this::keepKey));

		if (manager.hasKey(source)) {
			addRenderableWidget(new Pill(at.x(), at.cancelY(), Icon.CLOSED,
				Component.translatable("npc_studio.server.key.cancel"), TEXT_DIM, () -> {
					keyEditing = false;
					keyTyped = "";
					rebuild();
				}));
		}

		// The address is a button rather than a line of text because nothing in
		// this screen can be selected with the mouse: an address somebody has to
		// copy out by hand and retype is an address they will get wrong.
		addRenderableWidget(new Pill(at.x(), at.mailY() + 12, Icon.COPY,
			Component.translatable("npc_studio.server.key.copy_mail"), ACCENT, () -> {
				minecraft.keyboardHandler.setClipboard(SUPPORT);
				say(Component.translatable("npc_studio.server.key.mail_copied").getString());
			}));
	}

	private void keepKey() {
		String typed = keyTyped.trim();
		if (typed.isEmpty()) {
			say(Component.translatable("npc_studio.server.key.empty").getString());
			return;
		}
		String which = source;
		manager.putKey(which, typed, () -> {
			keyTyped = "";
			keyEditing = false;
			keyAsked = false;
			keyShown = "";
			trouble = "";
			say(Component.translatable("npc_studio.server.key.kept").getString());
			if (source.equals(which)) search(true);
			rebuild();
		}, failed -> {
			trouble = failed;
			rebuild();
		});
	}

	private void forgetKey() {
		String which = source;
		manager.dropKey(which, () -> {
			keyShown = "";
			keyAsked = false;
			keyEditing = false;
			results.clear();
			searched = false;
			say(Component.translatable("npc_studio.server.key.dropped").getString());
			rebuild();
		}, this::say);
	}

	/**
	 * How much room the masked key needs, measured from what a mask always is.
	 *
	 * Not from the mask that is on screen: it is fetched from a file on a worker
	 * thread and is an empty string at the moment the buttons are placed. So the
	 * buttons were laid out against nothing, and then the key appeared underneath
	 * them. A fixed shape has a fixed width, and this is it.
	 */
	private int maskedWidth() {
		return font.width(com.mopicmp.npcstudio.server.ApiKeys.masked("0000000000000000"));
	}

	/** The strip that says a key is in place, without saying what it is. */
	private void keyStrip(int top) {
		int x = contentLeft + PAD;
		if (!keyAsked) {
			keyAsked = true;
			String which = source;
			manager.maskedKey(which, masked -> {
				if (source.equals(which)) keyShown = masked;
			});
		}
		int at = x + font.width(Component.translatable("npc_studio.server.key.in_place")
			.getString() + "  ") + maskedWidth() + 12;
		addRenderableWidget(new Pill(at, top, Icon.SETTINGS,
			Component.translatable("npc_studio.server.key.change"), ACCENT, () -> {
				keyEditing = true;
				keyTyped = "";
				rebuild();
			}));
		addRenderableWidget(new Pill(at + Pill.wide(
			Component.translatable("npc_studio.server.key.change")) + 4, top, Icon.REMOVE,
			Component.translatable("npc_studio.server.key.forget"), WARN, this::forgetKey));
	}

	/**
	 * The places worth offering for this core, and only those.
	 *
	 * A site that does not stock the kind of thing this server takes is not a
	 * choice, it is a search that always comes back empty — and an empty answer
	 * reads as "there is nothing like that" rather than "you are asking in the
	 * wrong shop". Which sites stock what is in the catalogue, not here.
	 */
	private List<CoreCatalog.Source> sourcesHere() {
		List<CoreCatalog.Source> offered = new ArrayList<>();
		for (var each : manager.catalog().sources()) {
			if (each.has(ownKind())) offered.add(each);
		}
		return offered;
	}

	/**
	 * Whether there is another shelf to look at, which there usually is not.
	 *
	 * It is worth showing only when the core takes mods <em>and</em> the site has
	 * both shelves. On a Paper server nothing but plugins can be installed, so a
	 * button offering to look at mods offers a list where every row refuses; and a
	 * site with one shelf has nothing to switch to.
	 */
	private boolean canChooseKind() {
		// Always, now: every core reads datapacks, whatever else it takes, so
		// there is always a second shelf to look at even on a Paper server.
		return true;
	}

	/** The shelves worth offering here: this core's own, the other one, datapacks. */
	private List<String> kindsHere() {
		List<String> kinds = new ArrayList<>();
		kinds.add(ownKind());
		var here = manager.catalog().source(source).orElse(null);
		if (ownKind().equals("mods") && here != null && here.bothKinds()) kinds.add("plugins");
		kinds.add("datapacks");
		return kinds;
	}

	private void browseWidgets(int top) {
		if (loader().isBlank()) return;
		int x = contentLeft + PAD;
		int right = width - PAD;

		// A core changed underneath the browser can leave it pointed at a site that
		// no longer applies. Moved rather than refused: the person pressed nothing
		// wrong.
		List<CoreCatalog.Source> offered = sourcesHere();
		if (!offered.isEmpty() && offered.stream().noneMatch(each -> each.id().equals(source))) {
			source = offered.getFirst().id();
			results.clear();
			searched = false;
		}
		if (!canChooseKind() && !kind.equals("datapacks")) kind = "";

		if (opened != null) {
			projectWidgets(top);
			return;
		}

		Component look = Component.translatable("npc_studio.server.browse.search");
		int sortWidth = 116;
		int widest = 0;
		for (String each : kindsHere()) {
			widest = Math.max(widest, font.width(
				Component.translatable("npc_studio.server.browse.kind." + each)) + 24);
		}
		final int kindWidth = widest;
		int sourceWidth = 96;
		int across = Math.max(90, Math.min(300,
			right - x - Pill.wide(look) - sortWidth - kindWidth - sourceWidth - 20));
		query = Field.make(font, x, top, across, FIELD,
			Component.translatable("npc_studio.server.browse.query"));
		query.setMaxLength(80);
		query.setValue(queryText);
		query.setResponder(typed -> {
			if (typed.equals(queryText)) return;
			queryText = typed;
			// Not searched here. A request per keystroke is a request per keystroke
			// to somebody else's service, and the answers come back out of order.
			// The clock is started instead and read in tick().
			typedAt = net.minecraft.util.Util.getMillis();
		});
		addRenderableWidget(query);
		framed.add(new Framed(query, x, top, across, FIELD));
		// Typing survives the rebuild that a search causes. Without this, the
		// first letter searched and the field lost the keyboard, so the second
		// letter went nowhere.
		if (queryFocused) {
			setFocused(query);
			query.setFocused(true);
			query.moveCursorToEnd(false);
			queryFocused = false;
		}

		addRenderableWidget(new Pill(x + across + 4, top, Icon.SEARCH, look, ACCENT,
			() -> search(true)));

		// The order the catalogue sorts by. A dropdown rather than a row of five
		// words: it is one answer out of five, which is what a dropdown is for.
		int sortX = x + across + 8 + Pill.wide(look);
		addRenderableWidget(new FlatButton(sortX, top, sortWidth, FIELD,
			Component.literal(Component.translatable("npc_studio.server.order." + order)
				.getString() + "  ▾"), ACCENT,
			() -> {
				List<Dropdown.Option> options = new ArrayList<>();
				for (String each : com.mopicmp.npcstudio.server.Catalogue.ORDERS) {
					options.add(new Dropdown.Option(each,
						Component.translatable("npc_studio.server.order." + each)));
				}
				dropdown.open(sortX, top + FIELD, sortWidth, height, options, order, picked -> {
					order = picked;
					search(true);
				});
			}));

		// What to look for: the plugins side of the catalogue or the mods side.
		// Only where there is a second side — see canChooseKind.
		int kindX = sortX + sortWidth + 4;
		if (kindWidth > 0) {
			addRenderableWidget(new FlatButton(kindX, top, kindWidth, FIELD,
				Component.literal(Component.translatable("npc_studio.server.browse.kind." + kind())
					.getString() + "  ▾"),
				kind().equals(ownKind()) ? ACCENT : WARN,
				() -> {
					List<Dropdown.Option> options = new ArrayList<>();
					for (String each : kindsHere()) {
						options.add(new Dropdown.Option(each,
							Component.translatable("npc_studio.server.browse.kind." + each)));
					}
					dropdown.open(kindX, top + FIELD, kindWidth, height, options, kind(), picked -> {
						kind = picked;
						landing = "";
						search(true);
					});
				}));
		}

		// And where to look. The colours are the sites' own, so the button reads
		// as the place it goes to rather than as another blue word.
		int sourceX = kindX + (kindWidth > 0 ? kindWidth + 4 : 0);
		var here = manager.catalog().source(source).orElse(null);
		addRenderableWidget(new FlatButton(sourceX, top, sourceWidth, FIELD,
			Component.literal((here == null ? source : here.name()) + "  ▾"),
			here == null ? TEXT_DIM : here.colour(),
			() -> {
				List<Dropdown.Option> options = new ArrayList<>();
				for (var each : sourcesHere()) {
					options.add(new Dropdown.Option(each.id(),
						Component.literal(each.name()).withStyle(
							style -> style.withColor(each.colour() & 0xFFFFFF))));
				}
				dropdown.open(sourceX, top + FIELD, sourceWidth, height, options, source,
					this::chooseSource);
			}));

		// A source that wants a key of its own shows the way to give it one
		// instead of a list, and the list keeps its place underneath rather than
		// becoming a screen of its own.
		if (needsKeyNow()) {
			keyWidgets(top);
			return;
		}
		if (wantsKey()) keyStrip(top + FIELD + 6);

		if (!searched) search(true);

		int listTop = listTop(top);
		browseListTop = listTop;
		int bottom = height - PAD;
		browseHeight = Math.max(0, results.size() * ADDON_ROW - (bottom - listTop));
		int y = listTop - browseScroll;
		for (var each : results) {
			// Only rows that are wholly inside the view get a button. Widgets are
			// not clipped the way drawing is, so a row half under the search field
			// used to show its button beside a description that was not there yet.
			if (y >= listTop - 2 && y + ADDON_ROW <= bottom) {
				String installed = installedFile(each);
				boolean busy = installing.equals(each.id());
				Component label = installed != null
					? Component.translatable("npc_studio.server.content.remove")
					: Component.translatable(busy
						? "npc_studio.server.browse.installing"
						: "npc_studio.server.browse.install");
				Pill pill = installed != null
					? new Pill(right - 4 - Pill.wide(label), y + 6, Icon.REMOVE, label, WARN,
						() -> removeInstalled(installed))
					: new Pill(right - 4 - Pill.wide(label), y + 6, Icon.SAVE, label,
						busy ? TEXT_DIM : GOOD, () -> install(each));
				pill.active = !busy && installing.isEmpty();
				addRenderableWidget(pill);
			}
			y += ADDON_ROW;
		}
	}

	// ------------------------------------------------------------------ one project

	/** The row somebody pressed, and what came back about it. */
	private com.mopicmp.npcstudio.server.Catalogue.Found opened;
	private com.mopicmp.npcstudio.server.Catalogue.Details page;
	private boolean pageLoading;
	private int pageScroll;
	private int pageHeight;

	/** The description as blocks, read once rather than on every frame. */
	private List<com.mopicmp.npcstudio.server.Markup.Block> blocks;

	private final Map<String, List<com.mopicmp.npcstudio.server.Markup.Block>> changelogs =
		new LinkedHashMap<>();

	private final Map<String, Integer> noteHeights = new LinkedHashMap<>();
	private int noteWidth = -1;

	/** The address under the mouse this frame, or null. Set while drawing. */
	private String hoveredLink;

	/**
	 * Open an address from somebody else's description, after saying which.
	 *
	 * The same rule the game itself keeps for links in chat, and for the same
	 * reason: the words are chosen by a stranger and the address is not shown
	 * beside them. "Boosty" can be written over anything. So the window says the
	 * whole address and offers to copy it instead, and nothing opens until
	 * somebody has read it.
	 */
	private void openLink(String url) {
		if (url == null || url.isBlank()) return;
		confirm.ask(
			Component.translatable("npc_studio.server.link.title"),
			Component.translatable("npc_studio.server.link.body", url),
			Component.translatable("npc_studio.server.link.open"),
			Component.translatable("npc_studio.server.link.no"),
			() -> net.minecraft.util.Util.getPlatform().openUri(url));
	}

	/**
	 * How wide the page's column may be.
	 *
	 * A measure, not a limit on the window. Text set across the whole of a wide
	 * panel is text the eye loses its place in on the way back to the next line,
	 * and a screenshot stretched to a metre is not more readable for it.
	 */
	private static final int PAGE = 620;

	private static final int PAGE_PAD = 12;
	private static final int PAGE_GAP = 8;

	/** The project's own picture at the top of its page. */
	private static final int PAGE_ICON = 48;

	private void openProject(com.mopicmp.npcstudio.server.Catalogue.Found what) {
		opened = what;
		// Each page starts from where its own shelf would put it, rather than from
		// what was chosen on the last one.
		landing = "";
		// The worlds, for the list of datapack folders — this tab may be the first
		// one opened, and then nothing has ever asked for them.
		if (worlds.isEmpty()) readWorlds();
		page = null;
		blocks = null;
		changelogs.clear();
		noteHeights.clear();
		artSeen.clear();
		aboutWidth = -1;
		leaf = Leaf.ABOUT;
		pageLoading = true;
		pageScroll = 0;
		trouble = "";
		rebuild();
		String which = source;
		manager.details(server, what, source, loader(), got -> {
			if (opened != what || !source.equals(which)) return;
			page = got;
			pageLoading = false;
			rebuild();
		}, failed -> {
			if (opened != what) return;
			pageLoading = false;
			trouble = failed;
			rebuild();
		});
	}

	/**
	 * Open the page of something already installed.
	 *
	 * Only for a jar this mod put there: the note kept beside them says which
	 * project each came from, and a jar somebody dropped in by hand has no such
	 * note and no page to open. That is a fact about the file rather than a
	 * shortcoming, so the row simply does nothing rather than apologising.
	 */
	private void openInstalled(com.mopicmp.npcstudio.server.Addon addon) {
		String recorded = fromCatalogue.get(addon.file());
		if (recorded != null && !recorded.isBlank()) {
			int colon = recorded.indexOf(':');
			openKnown(colon < 0 ? "modrinth" : recorded.substring(0, colon),
				colon < 0 ? recorded : recorded.substring(colon + 1), addon.label());
			return;
		}
		// No note beside it, so ask the catalogue what the file itself is. This is
		// the case for anything somebody dropped into the folder by hand, which is
		// most of what is in a folder that has been used for a while.
		byName(com.mopicmp.npcstudio.server.Addons.folder(server).resolve(addon.file()),
			addon.label());
	}

	/** The same for one of this client's own mods, which never has a note. */
	private void openClientMod(ClientMods.Mod mod) {
		byName(mod.jar(), mod.name());
	}

	/**
	 * Find a jar in the catalogue by what it is, and open its page.
	 *
	 * By checksum, which is the only exact way: the project, the file and the mod
	 * inside it routinely have three different names, and matching any of them
	 * against each other is guessing. A file the catalogue has never seen gets a
	 * line saying so rather than a wrong page.
	 */
	private void byName(java.nio.file.Path jar, String label) {
		say(Component.translatable("npc_studio.server.content.looking_up").getString());
		String site = source;
		manager.projectOf(jar, site, id -> {
			if (id.isEmpty()) {
				say(Component.translatable("npc_studio.server.content.not_in_catalogue",
					label).getString());
				return;
			}
			openKnown(site, id, label);
		});
	}

	private void openKnown(String site, String id, String label) {
		if (manager.catalog().source(site).isEmpty()) return;
		source = site;
		cameFromInstalled = true;
		half = Half.BROWSE;
		say("");
		// A stub: everything on the page comes from the page itself, and these are
		// only what is shown while it is on its way.
		openProject(new com.mopicmp.npcstudio.server.Catalogue.Found(
			id, "", label, "", 0, "unknown", "unknown", List.of(), ""));
	}

	/** Whether the page was opened from the installed list, so back goes there. */
	private boolean cameFromInstalled;

	private void closeProject() {
		if (cameFromInstalled) {
			cameFromInstalled = false;
			half = Half.INSTALLED;
		}
		opened = null;
		page = null;
		blocks = null;
		changelogs.clear();
		pageLoading = false;
		hoveredLink = null;
		trouble = "";
		rebuild();
	}

	/**
	 * The four things a project page has to say, each with room to say it.
	 *
	 * They were one column before, and a description four screens long meant the
	 * version list was four screens down — so nobody scrolled to it, which is the
	 * same as it not being there.
	 */
	private enum Leaf {
		ABOUT("npc_studio.server.project.leaf.about"),
		PICTURES("npc_studio.server.project.leaf.pictures"),
		VERSIONS("npc_studio.server.project.leaf.versions"),
		CHANGES("npc_studio.server.project.leaf.changes");

		final String label;

		Leaf(String label) {
			this.label = label;
		}
	}

	private Leaf leaf = Leaf.ABOUT;

	private void projectWidgets(int top) {
		// REWIND rather than the chevron turned round: the mirrored draw put a
		// negative scale on the pose, and whatever the new pipeline does with one,
		// it is not drawing — the button had a hole where its icon should be.
		addRenderableWidget(new Pill(contentLeft + PAD, top, Icon.REWIND,
			Component.translatable("npc_studio.server.project.back"), TEXT_DIM,
			this::closeProject));
		pageBody(top + 24, null);
	}

	/** Where the leaf strip is, so both passes put the buttons in one place. */
	private void leafWidgets(int colX, int y, int col) {
		int at = colX;
		for (Leaf which : Leaf.values()) {
			Component label = Component.translatable(which.label);
			int across = font.width(label) + 18;
			addRenderableWidget(new LeafButton(at, y, across, 16, which, label));
			at += across + 2;
		}
	}

	private final class LeafButton extends AbstractWidget {

		private final Leaf which;

		LeafButton(int x, int y, int width, int height, Leaf which, Component label) {
			super(x, y, width, height, label);
			this.which = which;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor graphics,
				int mouseX, int mouseY, float delta) {
			boolean here = which == leaf;
			boolean lit = isHoveredOrFocused();
			graphics.fill(getX(), getY(), getX() + width, getY() + height,
				here ? 0xFF1B2028 : lit ? 0xFF171C23 : 0xFF12161C);
			graphics.fill(getX(), getY() + height - 2, getX() + width, getY() + height,
				here ? ACCENT : EDGE);
			var font = net.minecraft.client.Minecraft.getInstance().font;
			graphics.text(font, getMessage(), getX() + (width - font.width(getMessage())) / 2,
				getY() + 4, here ? TEXT : lit ? ACCENT : TEXT_DIM);
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			leaf = which;
			pageScroll = 0;
			rebuild();
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			if (!pressing(event)) return super.keyPressed(event);
			leaf = which;
			pageScroll = 0;
			rebuild();
			return true;
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}

	/** Whether a key press on a focused control means "press it". */
	static boolean pressing(KeyEvent event) {
		return event.key() == GLFW_KEY_ENTER || event.key() == GLFW_KEY_KP_ENTER
			|| event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
	}

	private void drawProject(GuiGraphicsExtractor graphics, int top) {
		graphics.enableScissor(contentLeft, top + 22, width, height - PAD);
		pageBody(top + 24, graphics);
		graphics.disableScissor();
		scrollbar(graphics, top + 22, height - PAD, pageScroll, pageHeight);
	}

	/**
	 * The project page, laid out and drawn by one method.
	 *
	 * <b>One method on purpose.</b> Everything else in this screen is placed by
	 * one method and drawn by another, and twice that has produced a panel where
	 * the buttons were a few pixels away from the words they belonged to — because
	 * the two copies of the arithmetic agreed until a paragraph wrapped
	 * differently. Here almost every position depends on how tall the thing above
	 * it turned out, including pictures whose height is not known until they
	 * arrive, so two copies would not stay together for a day.
	 *
	 * @param graphics where to draw, or null to place the buttons instead
	 */
	private void pageBody(int top, GuiGraphicsExtractor graphics) {
		boolean draw = graphics != null;
		int area = width - contentLeft - PAD * 2;
		// A column rather than the whole panel. A line of text three hundred
		// characters long is a line the eye loses its place in, and on a wide
		// window the panel is exactly that — so the page keeps a readable measure
		// and stands in the middle of the room it has.
		int col = Math.max(260, Math.min(PAGE, area));
		int colX = contentLeft + PAD + Math.max(0, (area - col) / 2);
		int x = colX + PAGE_PAD;
		int inner = col - PAGE_PAD * 2;
		int y = top - pageScroll;

		if (pageLoading) {
			if (draw) {
				graphics.text(font, Component.translatable("npc_studio.server.project.reading"),
					x, y + 4, TEXT_DIM);
			}
			return;
		}
		if (page == null) {
			if (draw && !trouble.isEmpty()) {
				graphics.textWithWordWrap(font, Component.literal(trouble), x, y + 4, inner, WARN);
			}
			return;
		}

		// ---- who it is: picture, name, author, one line, and what to do with it
		String title = page.title().isBlank() ? opened.title() : page.title();
		String summary = page.summary().isBlank() ? opened.summary() : page.summary();
		int textX = x + PAGE_ICON + 12;
		int textRoom = inner - PAGE_ICON - 12;
		int summaryHeight = summary.isBlank() ? 0
			: font.wordWrapHeight(Component.literal(summary), textRoom);
		int block = Math.max(PAGE_ICON, 16 + summaryHeight + 22);
		int buttonsY = y + PAGE_PAD + block + 4;
		int headerBottom = buttonsY + 18 + PAGE_PAD;

		if (draw) {
			card(graphics, colX, y, col, headerBottom - y);
			String key = "web:" + source + ":" + opened.id();
			if (!opened.icon().isBlank()) manager.fetchIcon(key, opened.icon());
			Identifier icon = ContentIcons.of(key);
			int iconY = y + PAGE_PAD;
			if (icon != null) {
				graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, icon,
					x, iconY, 0, 0, PAGE_ICON, PAGE_ICON, PAGE_ICON, PAGE_ICON,
					PAGE_ICON, PAGE_ICON);
			} else {
				graphics.fill(x, iconY, x + PAGE_ICON, iconY + PAGE_ICON, 0xFF0E1116);
				String letter = title.isBlank() ? "?"
					: title.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
				big(graphics, letter, x + (PAGE_ICON - font.width(letter) * 2) / 2,
					iconY + PAGE_ICON / 2 - 8, 2f, ACCENT);
			}
			big(graphics, shorten(title, (int) (textRoom / 1.4f)), textX, y + PAGE_PAD, 1.4f, TEXT);
			if (!summary.isBlank()) {
				graphics.textWithWordWrap(font, Component.literal(summary),
					textX, y + PAGE_PAD + 16, textRoom, TEXT_DIM);
			}
			int under = y + PAGE_PAD + 16 + summaryHeight + 2;
			// Who made it. People look for this: a name they know is half of the
			// decision, and a name they do not know is the other half.
			if (!page.author().isBlank()) {
				String by = Component.translatable("npc_studio.server.project.by").getString()
					+ " " + page.author();
				graphics.text(font, shorten(by, textRoom), textX, under, ACCENT);
				under += 11;
			}
			// From the page rather than from the row that opened it. A row from the
			// installed list is one this mod put together out of a jar on disk and
			// knows nothing about — so it said zero downloads, for ever, however
			// long anybody waited for a number that was never coming.
			graphics.text(font, downloads(page.downloads() > 0 ? page.downloads()
					: opened.downloads()) + " · "
				+ Component.translatable("npc_studio.server.project.downloads").getString(),
				textX, under, TEXT_DIM);
		}

		String installed = installedFile(opened);
		Component label = installed != null
			? Component.translatable("npc_studio.server.content.remove")
			: Component.translatable("npc_studio.server.browse.install");
		if (!draw) {
			addRenderableWidget(installed != null
				? new Pill(x, buttonsY, Icon.REMOVE, label, WARN, () -> removeInstalled(installed))
				: new Pill(x, buttonsY, Icon.SAVE, label, GOOD, () -> install(opened)));
			int put = x + Pill.wide(label) + 6;
			if (!page.page().isBlank()) {
				Component open = Component.translatable("npc_studio.server.project.open");
				addRenderableWidget(new Pill(put, buttonsY, Icon.ADDRESS, open, ACCENT,
					() -> openLink(page.page())));
				put += Pill.wide(open) + 6;
			}
			// Where it goes, as a choice rather than as a caption. It was a line of
			// text over the button — which stated the answer, could not change it,
			// and on a short summary landed on top of the download count.
			if (installed == null) {
				String where = landingNow();
				Component says = landingSays(where);
				int wide = Math.min(inner - (put - x), font.width(says) + 22);
				int at = put;
				addRenderableWidget(new FlatButton(at, buttonsY, wide, 18,
					Component.literal(shorten(says.getString(), wide - 16) + "  ▾"),
					where.equals("plugins") && !ownKind().equals("plugins") ? WARN : TEXT_DIM,
					() -> dropdown.open(at, buttonsY + 18, Math.max(wide, 200), height,
						landings(), where, picked -> {
							landing = picked;
							rebuild();
						})));
			}
		}
		y = headerBottom + PAGE_GAP;

		// ---- and the small box of facts, which is the thing somebody checks
		// before reading a word: does it fit my version, my loader, and does it do
		// anything on a server at all.
		y += factsCard(colX, y, col, draw ? graphics : null);

		// ---- the four tabs
		if (!draw) leafWidgets(colX, y, col);
		y += 20;

		int used = switch (leaf) {
			case ABOUT -> about(colX, y, col, graphics);
			case PICTURES -> pictures(colX, y, col, graphics);
			case VERSIONS -> versions(colX, y, col, graphics);
			case CHANGES -> changes(colX, y, col, graphics);
		};
		y += used;

		pageHeight = Math.max(0, y + pageScroll + PAD - (height - PAD));
	}

	/**
	 * Versions, loaders and which end of the game it is for, in one small card.
	 *
	 * Three facts and no prose, because they are the three that decide whether the
	 * rest is worth reading. The side is the one worth having in a window like
	 * this: a rendering mod installed on a server is a file the server ignores at
	 * best, and Modrinth is the only one of the two catalogues that says.
	 */
	private int factsCard(int colX, int y, int col, GuiGraphicsExtractor graphics) {
		int x = colX + PAGE_PAD;
		int inner = col - PAGE_PAD * 2;
		String versions = page.gameVersions().isEmpty() ? "—"
			: String.join(", ", page.gameVersions().size() > 8
				? page.gameVersions().subList(0, 8) : page.gameVersions())
				+ (page.gameVersions().size() > 8 ? " …" : "");
		String loaders = page.loaders().isEmpty() ? "—" : String.join(", ", page.loaders());
		String side = side();

		int label = 0;
		for (String key : new String[] {"npc_studio.server.project.for_versions",
			"npc_studio.server.project.for_loaders", "npc_studio.server.project.for_side"}) {
			label = Math.max(label, font.width(Component.translatable(key)) + 8);
		}
		int room = inner - label;
		int tall = PAGE_PAD
			+ font.wordWrapHeight(Component.literal(versions), room) + 3
			+ font.wordWrapHeight(Component.literal(loaders), room) + 3
			+ font.wordWrapHeight(Component.literal(side), room) + PAGE_PAD;

		if (graphics != null) {
			card(graphics, colX, y, col, tall);
			int at = y + PAGE_PAD;
			at = fact(graphics, "npc_studio.server.project.for_versions", versions,
				x, at, label, room, TEXT);
			at = fact(graphics, "npc_studio.server.project.for_loaders", loaders,
				x, at, label, room, TEXT);
			// The colour is the answer as much as the word is: a mod that does
			// nothing on a server should not read like the other two lines.
			fact(graphics, "npc_studio.server.project.for_side", side, x, at, label, room,
				page.sides().serverless() ? WARN : page.sides().known() ? GOOD : TEXT_DIM);
		}
		return tall + PAGE_GAP;
	}

	private int fact(GuiGraphicsExtractor graphics, String key, String value, int x, int y,
			int label, int room, int ink) {
		graphics.text(font, Component.translatable(key), x, y, TEXT_DIM);
		graphics.textWithWordWrap(font, Component.literal(value), x + label, y, room, ink);
		return y + font.wordWrapHeight(Component.literal(value), room) + 3;
	}

	private String side() {
		var sides = page.sides();
		if (!sides.known()) {
			return Component.translatable("npc_studio.server.project.side.unknown").getString();
		}
		if (sides.serverless()) {
			return Component.translatable("npc_studio.server.project.side.client").getString();
		}
		if (sides.client().equals("unsupported")) {
			return Component.translatable("npc_studio.server.project.side.server").getString();
		}
		return Component.translatable("npc_studio.server.project.side.both").getString();
	}

	/** The description, with its headings, its links and its boxes of code. */
	private int about(int colX, int y, int col, GuiGraphicsExtractor graphics) {
		int x = colX + PAGE_PAD;
		int inner = col - PAGE_PAD * 2;
		if (blocks == null) blocks = com.mopicmp.npcstudio.server.Markup.read(page.body());
		if (blocks.isEmpty()) {
			if (graphics != null) {
				card(graphics, colX, y, col, 40);
				graphics.text(font, Component.translatable("npc_studio.server.project.no_words"),
					x, y + PAGE_PAD, TEXT_DIM);
			}
			return 40;
		}
		// Measured once and remembered. Laying out a description means splitting
		// every paragraph into lines, and a long one is two hundred paragraphs —
		// doing that twice a frame, sixty times a second, on the machine this is
		// meant to run on is the kind of cost that shows up as the whole window
		// feeling heavy and never as an error.
		if (aboutWidth != inner) {
			aboutHeight = MarkupView.draw(null, font, blocks, x, y + PAGE_PAD, inner,
				art, -1, -1).height();
			aboutWidth = inner;
		}
		int tall = aboutHeight + PAGE_PAD * 2;
		if (graphics != null) {
			card(graphics, colX, y, col, tall);
			var result = MarkupView.draw(graphics, font, blocks, x, y + PAGE_PAD, inner,
				art, pointerX, pointerY);
			hoveredLink = result.link();
		}
		return tall;
	}

	/** The height of the description at a given width, and the width it was for. */
	private int aboutHeight;
	private int aboutWidth = -1;

	/** Pictures already seen, so that a new one can invalidate the measurement. */
	private final java.util.Set<String> artSeen = new java.util.HashSet<>();

	/** The picture source the description is drawn against. */
	private final MarkupView.Source art = new MarkupView.Source() {
		@Override
		public MarkupView.Art art(String url) {
			return inlineArt(url);
		}

		@Override
		public String unreachable(String url) {
			return manager.unreachablePicture(url);
		}
	};

	/** A picture that lives inside a description rather than in the gallery. */
	private MarkupView.Art inlineArt(String url) {
		String key = "body:" + url.hashCode();
		manager.fetchPicture(key, url);
		Identifier texture = ContentIcons.of(key);
		int[] size = ContentIcons.size(key);
		if (texture == null || size == null) return null;
		// A picture that has just arrived is taller than the box that was standing
		// in for it, so everything below it moves — which means the height that
		// was measured is no longer true.
		if (artSeen.add(url)) aboutWidth = -1;
		return new MarkupView.Art(texture, size[0], size[1]);
	}

	private int pictures(int colX, int y, int col, GuiGraphicsExtractor graphics) {
		int x = colX + PAGE_PAD;
		int inner = col - PAGE_PAD * 2;
		if (page.gallery().isEmpty()) {
			if (graphics != null) {
				card(graphics, colX, y, col, 40);
				graphics.text(font, Component.translatable("npc_studio.server.project.no_pictures"),
					x, y + PAGE_PAD, TEXT_DIM);
			}
			return 40;
		}
		int at = y;
		int shots = 0;
		for (var shot : page.gallery()) {
			if (shots++ >= 12) break;
			String key = "shot:" + source + ":" + opened.id() + ":" + shots;
			manager.fetchPicture(key, shot.url());
			Identifier picture = ContentIcons.of(key);
			int[] size = ContentIcons.size(key);
			int captionHeight = shot.caption().isBlank() ? 0 : 12;
			if (picture == null || size == null || size[0] <= 0) {
				// A box of the right shape rather than nothing, so the page does
				// not jump about while the pictures come in one at a time.
				if (graphics != null) {
					card(graphics, colX, at, col, 140);
					graphics.text(font,
						Component.translatable("npc_studio.server.project.picture"),
						x, at + 66, TEXT_DIM);
				}
				at += 140 + PAGE_GAP;
				continue;
			}
			int tall = Math.max(1, size[1] * inner / size[0]);
			if (graphics != null) {
				card(graphics, colX, at, col, PAGE_PAD * 2 + tall + captionHeight);
				graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, picture,
					x, at + PAGE_PAD, 0, 0, inner, tall, size[0], size[1], size[0], size[1]);
				// The author's own line about their own screenshot, which is
				// usually the sentence that makes the picture mean something.
				if (captionHeight > 0) {
					graphics.text(font, shorten(shot.caption(), inner),
						x, at + PAGE_PAD + tall + 3, TEXT_DIM);
				}
			}
			at += PAGE_PAD * 2 + tall + captionHeight + PAGE_GAP;
		}
		return at - y;
	}

	/**
	 * What it has published, so that "the newest" is a choice rather than a
	 * decision made for somebody at the moment they press.
	 */
	private int versions(int colX, int y, int col, GuiGraphicsExtractor graphics) {
		int x = colX + PAGE_PAD;
		int inner = col - PAGE_PAD * 2;
		// The whole list, not the part that fits: "is there one for my version
		// yet" is the question this tab is opened with, and a list already cut
		// down to the answer cannot be asked it.
		int listed = Math.min(60, page.releases().size());
		int tall = PAGE_PAD + Math.max(1, listed) * 26 + PAGE_PAD - 4;
		if (graphics != null) card(graphics, colX, y, col, tall);

		int rowY = y + PAGE_PAD;
		Component put = Component.translatable("npc_studio.server.project.put");
		for (int at = 0; at < listed; at++) {
			var release = page.releases().get(at);
			boolean fits = release.fits(server.gameVersion);
			if (graphics != null) {
				graphics.fill(x, rowY, x + inner, rowY + 24, 0xFF11151B);
				graphics.fill(x, rowY, x + inner, rowY + 1, EDGE);
				String number = release.number().isBlank() ? release.name() : release.number();
				int tail = Pill.wide(put) + 12;
				String weight = release.size() > 0 ? bytes(release.size()) : "";
				int rightOf = x + inner - tail - font.width(weight) - 8;
				graphics.text(font, shorten(number, rightOf - x - 12), x + 6, rowY + 3,
					fits ? (release.finished() ? TEXT : WARN) : TEXT_DIM);
				// Which versions it is for, under the name. A row that is dim and
				// says nothing else leaves somebody guessing why.
				String versions = release.gameVersions().isEmpty() ? ""
					: String.join(", ", release.gameVersions().size() > 4
						? release.gameVersions().subList(0, 4) : release.gameVersions())
						+ (release.gameVersions().size() > 4 ? " …" : "");
				if (!versions.isEmpty()) {
					graphics.text(font, shorten(versions, rightOf - x - 12), x + 6, rowY + 13,
						fits ? GOOD : TEXT_DIM);
				}
				if (!weight.isEmpty()) {
					graphics.text(font, weight, x + inner - tail - font.width(weight),
						rowY + 8, TEXT_DIM);
				}
				if (!release.finished()) {
					graphics.text(font, release.type(),
						x + inner - tail - font.width(weight) - 8
							- font.width(release.type()) - 6, rowY + 8, WARN);
				}
			} else {
				// The button is there whether or not it fits: somebody choosing a
				// file for another version has a reason, and refusing them here
				// would be deciding on their behalf. It reads as the ordinary
				// action only when the row is one that fits.
				addRenderableWidget(new Pill(x + inner - Pill.wide(put) - 6, rowY + 3, Icon.SAVE,
					put, fits ? GOOD : WARN, () -> putVersion(release)));
			}
			rowY += 26;
		}
		if (listed == 0 && graphics != null) {
			graphics.text(font, Component.translatable("npc_studio.server.project.no_versions"),
				x, y + PAGE_PAD + 6, TEXT_DIM);
		}
		return tall;
	}

	/**
	 * What changed, version by version.
	 *
	 * Modrinth carries these with the version listing, so they cost nothing.
	 * CurseForge keeps each one behind a request of its own, which for thirty
	 * files is thirty requests for a tab somebody may not open — so there it says
	 * where to look instead of quietly being empty.
	 */
	private int changes(int colX, int y, int col, GuiGraphicsExtractor graphics) {
		int x = colX + PAGE_PAD;
		int inner = col - PAGE_PAD * 2;
		int at = y;
		int shown = 0;
		for (var release : page.releases()) {
			if (shown >= 30) break;
			if (release.changelog() == null || release.changelog().isBlank()) continue;
			shown++;
			List<com.mopicmp.npcstudio.server.Markup.Block> notes =
				changelogs.computeIfAbsent(release.id(),
					id -> com.mopicmp.npcstudio.server.Markup.read(release.changelog()));
			String number = release.number().isBlank() ? release.name() : release.number();
			// Measured once per note, for the same reason the description is.
			if (noteWidth != inner) {
				noteHeights.clear();
				noteWidth = inner;
			}
			int noteTop = at + PAGE_PAD + 14;
			int tall = PAGE_PAD + 14 + noteHeights.computeIfAbsent(release.id(),
				id -> MarkupView.draw(null, font, notes, x, noteTop, inner,
					art, -1, -1).height()) + PAGE_PAD - 4;
			if (graphics != null) {
				card(graphics, colX, at, col, tall);
				graphics.text(font, number, x, at + PAGE_PAD, ACCENT);
				var hit = MarkupView.draw(graphics, font, notes, x, at + PAGE_PAD + 14, inner,
					art, pointerX, pointerY);
				if (hit.link() != null) hoveredLink = hit.link();
			}
			at += tall + PAGE_GAP;
		}
		if (shown == 0) {
			if (graphics != null) {
				card(graphics, colX, at, col, 44);
				graphics.textWithWordWrap(font,
					Component.translatable("npc_studio.server.project.no_changes"),
					x, at + PAGE_PAD, inner, TEXT_DIM);
			}
			at += 44;
		}
		return at - y;
	}

	/** The panel every part of the project page sits on. */
	private void card(GuiGraphicsExtractor graphics, int x, int y, int across, int tall) {
		graphics.fill(x, y, x + across, y + tall, CARD);
		graphics.fill(x, y, x + across, y + 1, EDGE);
		graphics.fill(x, y + tall - 1, x + across, y + tall, EDGE);
		graphics.fill(x, y, x + 1, y + tall, EDGE);
		graphics.fill(x + across - 1, y, x + across, y + tall, EDGE);
	}

	/** Text larger than the one size this font has, for the one place that needs it. */
	private void big(GuiGraphicsExtractor graphics, String text, int x, int y, float scale,
			int colour) {
		var pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(scale, scale);
		graphics.text(font, text, 0, 0, colour);
		pose.popMatrix();
	}

	/** A file size as somebody says it, not as a number with seven digits. */
	private static String bytes(long size) {
		if (size >= 1024 * 1024) return Math.round(size / 104857.6) / 10.0 + " MB";
		if (size >= 1024) return size / 1024 + " KB";
		return size + " B";
	}

	private void putVersion(com.mopicmp.npcstudio.server.Catalogue.Release release) {
		if (!installing.isEmpty()) return;
		installing = opened.id();
		rebuild();
		manager.installRelease(server, opened, release, source, intoFolder(), this::say, name -> {
			installing = "";
			say(Component.translatable(manager.running()
				? "npc_studio.server.content.added_restart"
				: "npc_studio.server.content.added").getString() + " — " + name);
			readAddons();
			rebuild();
		}, failed -> {
			installing = "";
			trouble = failed;
			say(Component.translatable("npc_studio.server.browse.failed").getString());
			rebuild();
		});
	}

	/** Whether this server's core takes mods at all — a Paper one does not. */
	private boolean takesMods() {
		return com.mopicmp.npcstudio.server.Addons.kindFor(server.core)
			== com.mopicmp.npcstudio.server.Addon.Kind.MOD;
	}

	/**
	 * The client's mods that are not on the server yet.
	 *
	 * Matched by file name, which is the one thing that is the same on both
	 * sides: the same jar copied across keeps its name, and a mod already there
	 * should not be offered again.
	 */
	private List<ClientMods.Mod> clientMods() {
		List<ClientMods.Mod> offered = new ArrayList<>();
		for (ClientMods.Mod mod : ClientMods.all()) {
			boolean already = false;
			for (var addon : addons) {
				if (addon.file().equals(mod.file())) already = true;
			}
			if (!already) offered.add(mod);
		}
		return offered;
	}

	/** Where the client-mods heading was drawn, so its button can sit on that line. */
	private int clientModsY;

	/**
	 * Copy the whole of this client's mods across, and say how it went once.
	 *
	 * Counted rather than narrated: twenty lines of "added" one after another is
	 * not a report. What failed is named, because that is the part somebody can
	 * act on.
	 */
	private void putAllOnServer(List<ClientMods.Mod> mods) {
		int[] left = {mods.size()};
		int[] added = {0};
		List<String> failed = new ArrayList<>();
		say(Component.translatable("npc_studio.server.content.unpacking").getString());
		for (ClientMods.Mod mod : mods) {
			manager.installAddon(server, mod.jar(), said -> {
				if (said.isEmpty()) added[0]++;
				else failed.add(mod.name() + " — " + said);
				if (--left[0] > 0) return;
				finishedAdding(added[0], failed);
			});
		}
	}

	private void putOnServer(ClientMods.Mod mod) {
		manager.installAddon(server, mod.jar(), said -> {
			say(said.isEmpty()
				? Component.translatable(manager.running()
					? "npc_studio.server.content.added_restart"
					: "npc_studio.server.content.added").getString() + " — " + mod.name()
				: mod.name() + ": " + said);
			readAddons();
		});
	}

	/**
	 * How much of a row's right-hand side is not text.
	 *
	 * Worked out from the widest of the words that can appear there rather than
	 * assumed, because the download count used to be written over the summary
	 * whenever either was longer than the guess.
	 */
	private int rowTail() {
		int widest = 0;
		for (String key : new String[] {
			"npc_studio.server.content.remove",
			"npc_studio.server.browse.install",
			"npc_studio.server.browse.installing"}) {
			widest = Math.max(widest, Pill.wide(Component.translatable(key)));
		}
		return widest + 8;
	}

	/** The jar this project put on the server, or null when it is not there. */
	private String installedFile(com.mopicmp.npcstudio.server.Catalogue.Found what) {
		for (var entry : fromCatalogue.entrySet()) {
			if (!ServerManager.sameProject(entry.getValue(), source, what.id())) continue;
			for (var addon : addons) {
				if (addon.file().equals(entry.getKey())) return addon.file();
			}
		}
		return null;
	}

	private void search(boolean fresh) {
		if (searching) return;
		searching = true;
		searched = true;
		trouble = "";
		asked = queryText;
		typedAt = 0;
		queryFocused = query != null && query.isFocused();
		if (fresh) {
			results.clear();
			browseScroll = 0;
		}
		if (fresh) {
			nextOffset = 0;
			pagesTaken = 0;
		}
		manager.browse(server, queryText, nextOffset, order, loader(), source, page -> {
			searching = false;
			results.addAll(page.hits());
			nextOffset = page.next();
			moreToFind = page.more();
			if (results.isEmpty()) {
				say(Component.translatable("npc_studio.server.browse.nothing").getString());
			}
			rebuild();
			// The next page used to be asked for by scrolling, which cannot happen
			// when twenty rows do not reach the bottom of the window: the list
			// stopped at twenty and looked like the whole of what exists. So a
			// page that does not fill the screen asks for the one after it.
			int fits = Math.max(1, (height - PAD - browseListTop) / ADDON_ROW);
			if (moreToFind && results.size() <= fits && pagesTaken < MOST_PAGES) {
				pagesTaken++;
				search(false);
			}
		}, failed -> {
			searching = false;
			moreToFind = false;
			// Into the panel, where the empty list is. Into the column as well,
			// short, so that somebody looking at the left of the screen knows
			// something happened at all.
			trouble = failed;
			say(Component.translatable("npc_studio.server.browse.failed").getString());
			rebuild();
		});
	}

	/**
	 * Install what is being looked at, into the folder that shelf belongs in.
	 *
	 * This used to refuse anything that was not the core's own kind, which was
	 * right when there were two shelves and wrong now there are three: it refused
	 * every datapack, on every server, with a message about mods and plugins. And
	 * the refusal was mistaken about plugins too — a Fabric server can run them,
	 * given the mod that bridges the two, which is a thing people install on
	 * purpose. So nothing is refused; instead the page says where the file will
	 * go, which is the question somebody actually has.
	 */
	private void install(com.mopicmp.npcstudio.server.Catalogue.Found what) {
		if (!installing.isEmpty()) return;
		installing = what.id();
		rebuild();
		boolean bridged = landingNow().equals("plugins") && !ownKind().equals("plugins");
		manager.install(server, what, source, loader(), intoFolder(), this::say, name -> {
			installing = "";
			say(Component.translatable(manager.running()
				? "npc_studio.server.content.added_restart"
				: "npc_studio.server.content.added").getString() + " — " + name
				// Said in full once it is there. The button could only fit "needs
				// the bridge mod"; this is which mod and what happens without it.
				+ (bridged ? " — " + Component.translatable(
					"npc_studio.server.browse.bridge_note").getString() : ""));
			readAddons();
			rebuild();
		}, failed -> {
			installing = "";
			trouble = failed;
			say(Component.translatable("npc_studio.server.browse.failed").getString());
			rebuild();
		});
	}

	/**
	 * A row on its way out, which closes rather than dims.
	 *
	 * The first attempt faded the row where it stood and then rebuilt the list,
	 * which fixed nothing: the fade was a fifth of a second of dimming followed by
	 * the very jump it was supposed to soften, as thirty-four pixels of list
	 * arrived from below all at once. What the eye needs is not the row going
	 * quietly but the gap closing — so the row's own height goes to nothing, the
	 * rest of the list slides up as it does, and the list is only read again once
	 * the space is already shut.
	 *
	 * The file is deleted straight away either way. This is about what is shown,
	 * not about when the work happens — an animation that delays the work is an
	 * animation that can lose it.
	 */
	private void removeInstalled(String file) {
		shutting = "jar:" + file;
		shuttingAt = net.minecraft.util.Util.getMillis();
		shutDone = false;
		afterShut = this::readAddons;
		rebuild();
		manager.removeAddon(server, file, said -> {
			if (!said.isEmpty()) {
				// It did not go. Stop closing a gap that is not there.
				shutting = "";
				shuttingAt = 0;
				say(said);
				readAddons();
				rebuild();
				return;
			}
			shutDone = true;
			if (shuttingAt == 0) readAddons();
		});
	}

	/** Which row is closing, when it started, and whether the disk has caught up. */
	private String shutting = "";
	private long shuttingAt;
	private boolean shutDone;
	private Runnable afterShut;

	private static final long SHUT_MS = 160;

	/**
	 * How tall a row is this frame: its own height, or what is left of it.
	 *
	 * Asked by the drawing and by the placing both, so that the buttons of the
	 * rows below come to rest exactly where the drawing has been putting them.
	 */
	private int shut(String key, int full) {
		if (shuttingAt == 0 || !shutting.equals(key)) return full;
		float gone = (net.minecraft.util.Util.getMillis() - shuttingAt) / (float) SHUT_MS;
		return Math.max(0, Math.round(full * (1f - Math.clamp(gone, 0f, 1f))));
	}

	/**
	 * Take whatever was chosen: several jars, an archive, or both at once.
	 *
	 * The count is reported rather than each file named, because the interesting
	 * case is a pack with forty mods in it and forty lines of "added" is not a
	 * message. What did <em>not</em> go in is named, though — that is the part
	 * somebody has to do something about.
	 */
	private void addAddons() {
		List<java.nio.file.Path> chosen = AddonPick.choose();
		if (chosen.isEmpty()) return;
		int[] left = {chosen.size()};
		int[] added = {0};
		List<String> skipped = new ArrayList<>();
		say(Component.translatable("npc_studio.server.content.unpacking").getString());

		for (java.nio.file.Path file : chosen) {
			manager.installFile(server, file, this::say, result -> {
				added[0] += result.added().size();
				skipped.addAll(result.skipped());
				if (--left[0] > 0) return;
				finishedAdding(added[0], skipped);
			}, failed -> {
				skipped.add(file.getFileName() + " — " + failed);
				if (--left[0] > 0) return;
				finishedAdding(added[0], skipped);
			});
		}
	}

	private void finishedAdding(int added, List<String> skipped) {
		if (added > 0) {
			say(Component.translatable(manager.running()
				? "npc_studio.server.content.added_many_restart"
				: "npc_studio.server.content.added_many", added).getString());
		} else if (skipped.isEmpty()) {
			say(Component.translatable("npc_studio.server.content.added_none").getString());
		}
		trouble = skipped.isEmpty() ? "" : String.join("\n", skipped);
		readAddons();
	}


	private void drawContent(GuiGraphicsExtractor graphics) {
		int top = contentTop + 4 + 22;
		if (half == Half.INSTALLED) drawInstalled(graphics, top);
		else drawBrowse(graphics, top);
	}

	private void drawInstalled(GuiGraphicsExtractor graphics, int top) {
		int x = contentLeft + PAD;
		int right = width - PAD;
		if (!addonsRead) {
			graphics.text(font, Component.translatable("npc_studio.server.reading"),
				x, top + 4, TEXT_DIM);
			return;
		}

		// No tally over the list. It said "Моды · 3" over a list holding mods, a
		// datapack and this game's own mods, so it was wrong as often as it was
		// right — and the headings inside the list already say what is what.
		graphics.enableScissor(contentLeft, top + 24, width, height);
		int y = top + 24 - contentScroll;
		installedTop = top + 24;
		int tail = rowTail();
		// Said in the flow rather than at a fixed place. It was drawn at a corner
		// of the panel while the heading below moved with the list, so on a server
		// with nothing installed the two words landed on each other and the screen
		// read as "модысерверанеичегоустановлено".
		List<Line> lines = installedLines();
		if (lines.isEmpty()) {
			graphics.text(font, Component.translatable(addons.isEmpty()
				? "npc_studio.server.content.empty"
				: "npc_studio.server.content.none_shown"), x + PAD, y + 4, TEXT_DIM);
			y += HEADER;
		}
		for (Line line : lines) {
			com.mopicmp.npcstudio.server.Addon addon = line.addon();
			if (addon == null) {
				// A heading, which is what makes three kinds in one list read as
				// three kinds rather than as one long muddle. The rule sits at the
				// foot of the whole heading, well clear of the words: it used to be
				// drawn a pixel under them, through the tail of every letter.
				graphics.text(font, Component.translatable(line.heading()),
					x + PAD, y + 8, ACCENT);
				graphics.fill(x + PAD, y + KIND_HEAD - 4, right, y + KIND_HEAD - 3, EDGE);
				y += line.tall();
				continue;
			}
			manager.fetchAddonIcon("jar:" + server.id + ":" + addon.file(),
				com.mopicmp.npcstudio.server.Addons.folder(server).resolve(addon.file()),
				fromCatalogue.get(addon.file()));
			// The version if it says one, the file only when it does not. Both is
			// how "Chunky" ends up under "Chunky-Bukkit-1.5.3.jar 1.5.3", which is
			// the same three things said twice.
			String under = addon.version().isBlank() ? addon.file() : addon.version();
			int step = shut("jar:" + addon.file(), ADDON_ROW);
			if (step <= 0) continue;
			// Lit only when there is a page behind it: a row that lights up and
			// then does nothing is worse than one that never lit up.
			boolean openable = fromCatalogue.containsKey(addon.file());
			boolean under_ = openable && pointerX > contentLeft && pointerX < right
				&& pointerY >= y && pointerY < y + ADDON_ROW - 4
				&& pointerY >= top + 24 && pointerY <= height - PAD;
			if (step < ADDON_ROW) {
				// Clipped to what is left of it, so the row is squeezed out of the
				// list rather than shrunk in place.
				graphics.enableScissor(contentLeft, y, width, y + step);
				row(graphics, x, y, right, tail,
					ContentIcons.of("jar:" + server.id + ":" + addon.file()),
					addon.label(), under, addon.wrongSide() ? WARN : TEXT, false);
				graphics.disableScissor();
				y += step;
				continue;
			}
			row(graphics, x, y, right, tail,
				ContentIcons.of("jar:" + server.id + ":" + addon.file()),
				addon.label(), under, addon.wrongSide() ? WARN : TEXT, under_);
			if (addon.wrongSide()) {
				Icon.WARNING.draw(graphics, right - tail - 20, y + 7, WARN);
			} else if (!addon.described()) {
				graphics.text(font, "?", right - tail - 16, y + 10, TEXT_DIM);
			}
			y += ADDON_ROW;
		}

		// The heading is drawn whether or not there is anything under it, because
		// an absent section reads as a fault: a Paper server cannot take the
		// client's mods, and saying so is the answer to "where are they".
		graphics.text(font, Component.translatable("npc_studio.server.content.client_mods"),
			x + PAD, y + 5, ACCENT);
		y += CLIENT_HEADER;
		if (!takesMods()) {
			graphics.text(font, shorten(Component.translatable(
					"npc_studio.server.content.client_wrong_core", server.core).getString(),
					right - x - PAD * 2),
				x + PAD, y + 2, TEXT_DIM);
		} else {
			List<ClientMods.Mod> mine = clientMods();
			if (mine.isEmpty()) {
				graphics.text(font,
					Component.translatable("npc_studio.server.content.client_none"),
					x + PAD, y + 2, TEXT_DIM);
			}
			clientModsTop = y;
			for (ClientMods.Mod mod : mine) {
				manager.fetchJarIcon("jar:" + mod.file(), mod.jar());
				row(graphics, x, y, right, tail, ContentIcons.of("jar:" + mod.file()),
					mod.name(), mod.version(), TEXT);
				y += ADDON_ROW;
			}
		}
		graphics.disableScissor();
		scrollbar(graphics, top + 24, height - PAD, contentScroll, contentHeight);
	}

	private void drawBrowse(GuiGraphicsExtractor graphics, int top) {
		int x = contentLeft + PAD;
		int right = width - PAD;
		if (loader().isBlank()) {
			graphics.textWithWordWrap(font,
				Component.translatable("npc_studio.server.browse.no_loader", server.core),
				x, top + 4, right - x - PAD, WARN);
			return;
		}
		if (opened != null) {
			drawProject(graphics, top);
			return;
		}
		if (needsKeyNow()) {
			drawKeyPanel(graphics, top);
			return;
		}
		if (wantsKey()) {
			String said = Component.translatable("npc_studio.server.key.in_place").getString();
			graphics.text(font, said, x, top + FIELD + 11, TEXT_DIM);
			graphics.text(font, keyShown, x + font.width(said + "  "), top + FIELD + 11, ACCENT);
		}

		int listTop = listTop(top);
		graphics.enableScissor(contentLeft, listTop - 2, width, height);
		int y = listTop - browseScroll;
		int tail = rowTail();
		for (var each : results) {
			// The site is part of the name a picture is remembered under: the two
			// catalogues number their projects independently, so an id alone would
			// let one site's row show the other's picture.
			String picture = "web:" + source + ":" + each.id();
			if (!each.icon().isBlank()) manager.fetchIcon(picture, each.icon());
			String downloads = downloads(each.downloads());
			// The count sits between the text and the button, and the text is cut
			// to leave room for it — it used to be written over the summary.
			int room = tail + font.width(downloads) + 10;
			boolean under = pointerX > contentLeft && pointerX < right
				&& pointerY >= y && pointerY < y + ADDON_ROW - 4
				&& pointerY >= listTop && pointerY <= height - PAD;
			row(graphics, x, y, right, room, ContentIcons.of(picture),
				each.title(), each.summary(), TEXT, under);
			graphics.text(font, downloads, right - tail - font.width(downloads), y + 12, TEXT_DIM);
			y += ADDON_ROW;
		}
		graphics.disableScissor();
		scrollbar(graphics, listTop, height - PAD, browseScroll, browseHeight);

		if (searching) {
			graphics.text(font, Component.translatable("npc_studio.server.browse.asking"),
				x + PAD, listTop + 4, TEXT_DIM);
		} else if (!trouble.isEmpty() && results.isEmpty()) {
			// Where the rows would have been, in full and wrapped. The list is
			// empty precisely because of this, so there is nothing to draw over.
			graphics.textWithWordWrap(font, Component.literal(trouble),
				x + PAD, listTop + 4, Math.min(460, right - x - PAD * 2), WARN);
		}
	}

	/**
	 * What a key is for, where to get one, and what becomes of it here.
	 *
	 * The third part is the one that is usually left out. Somebody is being asked
	 * to paste a credential into a game mod, and the only thing that makes that a
	 * reasonable request is an account of where it goes — including the part that
	 * cannot be promised, which is that a program running as them could read it.
	 * A claim of safety that is not true is worse than the plain description.
	 */
	private void drawKeyPanel(GuiGraphicsExtractor graphics, int top) {
		var here = manager.catalog().source(source).orElse(null);
		if (here == null) return;
		KeyPlaces at = keyPlaces(top);

		graphics.enableScissor(contentLeft, top + FIELD + 4, width, height - PAD);
		graphics.fill(at.cardX(), at.cardTop(), at.cardX() + at.card(), at.cardBottom(), CARD);
		graphics.fill(at.cardX(), at.cardTop(), at.cardX() + at.card(), at.cardTop() + 1, EDGE);
		graphics.fill(at.cardX(), at.cardBottom() - 1, at.cardX() + at.card(),
			at.cardBottom(), EDGE);
		graphics.fill(at.cardX(), at.cardTop(), at.cardX() + 1, at.cardBottom(), EDGE);
		graphics.fill(at.cardX() + at.card() - 1, at.cardTop(), at.cardX() + at.card(),
			at.cardBottom(), EDGE);
		// A line of the site's own colour along the top, so the card belongs to
		// the button that opened it.
		graphics.fill(at.cardX(), at.cardTop(), at.cardX() + at.card(), at.cardTop() + 2,
			here.colour());

		Component title = Component.translatable("npc_studio.server.key.title", here.name());
		graphics.text(font, title, at.cardX() + (at.card() - font.width(title)) / 2,
			at.titleY(), TEXT);
		graphics.textWithWordWrap(font,
			Component.translatable("npc_studio.server.key.how", here.name()),
			at.x(), at.howY(), at.inner(), TEXT);

		if (!trouble.isEmpty()) {
			graphics.textWithWordWrap(font, Component.literal(trouble),
				at.x(), at.troubleY(), at.inner(), WARN);
		}
		graphics.textWithWordWrap(font, Component.translatable("npc_studio.server.key.where"),
			at.x(), at.whereY(), at.inner(), TEXT_DIM);
		graphics.textWithWordWrap(font, Component.translatable("npc_studio.server.key.terms"),
			at.x(), at.termsY(), at.inner(), TEXT_DIM);
		graphics.text(font, Component.translatable("npc_studio.server.key.support", SUPPORT),
			at.x(), at.mailY(), ACCENT);
		graphics.disableScissor();

		scrollbar(graphics, top + FIELD + 4, height - PAD, keyScroll, keyHeight);
	}

	/**
	 * One row of either list: a picture, a name, a line under it.
	 *
	 * Where there is no picture the first letter of the name is drawn in a tile
	 * of its own instead. A list where some rows have a picture and the rest have
	 * a hole in them looks broken; one where the rest have letters looks made —
	 * and holes are the common case, because the catalogue serves WebP and the
	 * game's decoder does not read it.
	 */
	private void row(GuiGraphicsExtractor graphics, int x, int y, int right, int tail,
			Identifier picture, String title, String under, int ink) {
		row(graphics, x, y, right, tail, picture, title, under, ink, false);
	}

	/**
	 * The same row, knowing whether the mouse is on it.
	 *
	 * The outline is not decoration: a row that opens a page when it is pressed
	 * has to look pressable, and in a list where each row already has a button on
	 * it there is nothing else to say so. It is drawn only in the list where
	 * pressing a row does something.
	 */
	private void row(GuiGraphicsExtractor graphics, int x, int y, int right, int tail,
			Identifier picture, String title, String under, int ink, boolean hover) {
		row(graphics, x, y, right, tail, picture, title, under, ink, hover, 1f);
	}

	private void row(GuiGraphicsExtractor graphics, int x, int y, int right, int tail,
			Identifier picture, String title, String under, int ink, boolean hover, float much) {
		if (much <= 0f) return;
		graphics.fill(x, y, right, y + ADDON_ROW - 4, hover ? 0xFF19202A : CARD);
		int edge = hover ? ACCENT : EDGE;
		graphics.fill(x, y, right, y + 1, edge);
		graphics.fill(x, y + ADDON_ROW - 5, right, y + ADDON_ROW - 4, edge);
		graphics.fill(x, y, x + 1, y + ADDON_ROW - 4, edge);
		graphics.fill(right - 1, y, right, y + ADDON_ROW - 4, edge);

		int tileX = x + 4;
		int tileY = y + 3;
		if (picture != null) {
			graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, picture,
				tileX, tileY, 0, 0, TILE, TILE, TILE, TILE);
		} else if (picture == null) {
			graphics.fill(tileX, tileY, tileX + TILE, tileY + TILE, 0xFF0E1116);
			graphics.fill(tileX, tileY, tileX + TILE, tileY + 1, EDGE);
			String letter = title.isBlank() ? "?" : title.substring(0, 1).toUpperCase(
				java.util.Locale.ROOT);
			graphics.text(font, letter, tileX + (TILE - font.width(letter)) / 2, tileY + 8,
				ACCENT);
		}

		int textX = tileX + TILE + 6;
		int room = Math.max(20, right - textX - tail);
		// Cut with an ellipsis rather than simply cut: a sentence that stops
		// mid-word looks like the sentence, and one that ends in three dots looks
		// like a sentence that continues.
		graphics.text(font, shorten(title, room), textX, y + 6, ink);
		graphics.text(font, shorten(under, room), textX, y + 17, TEXT_DIM);
	}

	// ------------------------------------------------------------------ scrolling

	/**
	 * How far one notch of the wheel moves a list.
	 *
	 * Two rows rather than most of one. The old step was twenty-four pixels
	 * against a thirty-four pixel row, so a notch moved a list by two thirds of an
	 * entry — everything ended up half-cut, and getting down a page took a dozen
	 * of them.
	 */
	private static final int STEP = ADDON_ROW * 2;

	/** The same in a panel with no rows in it. */
	private static final int STEP_PIXELS = 48;

	/** And in the console, which is counted in lines. */
	private static final int STEP_LINES = 6;

	/** The width of the scroll strip, which fits in the margin the lists already leave. */
	private static final int STRIP = 4;

	private int barTop;
	private int barBottom;
	private int barExtra;
	private boolean barDragging;

	/**
	 * The strip down the right that says where in a list you are.
	 *
	 * Not decoration and not only a control: a list that scrolls with no bar gives
	 * no answer at all to "how much of this is there" — which is the first thing
	 * anybody wants to know about a catalogue of four thousand mods. It sits in
	 * the eight pixels of margin the lists already leave, so nothing moved to make
	 * room for it.
	 *
	 * @param offset how far down the content has been moved, in pixels
	 * @param extra  how much of it is below the bottom; nothing is drawn at zero
	 */
	private void scrollbar(GuiGraphicsExtractor graphics, int top, int bottom, int offset,
			int extra) {
		barTop = top;
		barBottom = bottom;
		barExtra = Math.max(0, extra);
		int span = bottom - top;
		if (barExtra <= 0 || span < 24) return;
		int x = width - PAD + 1;
		graphics.fill(x, top, x + STRIP, bottom, 0xFF0E1116);
		int thumb = thumb(span);
		int at = top + (span - thumb) * Math.clamp(offset, 0, barExtra) / barExtra;
		graphics.fill(x, at, x + STRIP, at + thumb, barDragging ? ACCENT : 0xFF3A424D);
	}

	private int thumb(int span) {
		return Math.clamp(span * span / Math.max(1, span + barExtra), 20, Math.max(20, span));
	}

	/** Where a press on the strip puts the list, with the thumb under the mouse. */
	private int offsetAt(double mouseY) {
		int span = barBottom - barTop;
		int thumb = thumb(span);
		int travel = Math.max(1, span - thumb);
		return (int) Math.round((mouseY - barTop - thumb / 2.0) * barExtra / travel);
	}

	private boolean onBar(double mouseX, double mouseY) {
		return barExtra > 0 && mouseX >= width - PAD - 1
			&& mouseY >= barTop && mouseY <= barBottom;
	}

	/**
	 * Move whichever list is showing to a place in it.
	 *
	 * The console is the odd one: it is counted in lines from the bottom, because
	 * a console is a thing you are at the end of. Everything else is pixels from
	 * the top.
	 */
	private void scrollTo(int offset) {
		int wanted = Math.clamp(offset, 0, barExtra);
		switch (tab) {
			case CONSOLE -> {
				int rows = Math.max(0, manager.console().size() - consoleFits());
				consoleScroll = Math.clamp(rows - wanted / LINE, 0, rows);
			}
			case CONTENT -> {
				if (half == Half.INSTALLED) contentScroll = wanted;
				else if (opened != null) pageScroll = wanted;
				else if (needsKeyNow()) keyScroll = wanted;
				else {
					browseScroll = wanted;
					// The same asking-for-more as the wheel does. Dragging the bar
					// to the bottom is the same act, and it used to end at whatever
					// had arrived so far with no way to go further.
					if (browseScroll >= browseHeight - ADDON_ROW * 4 && moreToFind && !searching) {
						search(false);
					}
				}
				rebuild();
			}
			case WORLDS -> {
				worldScroll = wanted;
				rebuild();
			}
			case PLAYERS -> {
				playerScroll = wanted;
				rebuild();
			}
			case RIGHTS -> {
				rightsScroll = wanted;
				rebuild();
			}
			case CONFIGS -> {
				configScroll = wanted;
				rebuild();
			}
			case SETTINGS -> {
				settingsScroll = wanted;
				rebuild();
			}
		}
	}

	/**
	 * Text cut to fit, with three dots where it was cut.
	 *
	 * A description that simply stops mid-word reads as the description; one that
	 * ends in an ellipsis reads as a description that continues.
	 */
	private String shorten(String text, int room) {
		if (text == null || text.isEmpty()) return "";
		if (font.width(text) <= room) return text;
		return font.plainSubstrByWidth(text, Math.max(1, room - font.width("…"))) + "…";
	}

	/** Downloads as a person says them, not as a number with nine digits. */
	private static String downloads(long count) {
		if (count >= 1_000_000) return count / 100_000 / 10.0 + "M";
		if (count >= 1_000) return count / 1_000 + "K";
		return String.valueOf(count);
	}

	// ------------------------------------------------------------------ worlds

	/**
	 * The two halves of the worlds tab.
	 *
	 * Copies get a half of their own rather than a strip under the worlds,
	 * because they are a different question. A world is "which one am I playing";
	 * a copy is "what can I go back to", and that one is asked on a bad day, when
	 * a list of archives should be the whole screen and not a footnote.
	 */
	private enum Ground {
		WORLDS("npc_studio.server.worlds.half.worlds"),
		BACKUPS("npc_studio.server.worlds.half.backups"),
		PLACES("npc_studio.server.worlds.half.places");

		final String label;

		Ground(String label) {
			this.label = label;
		}
	}

	private Ground ground = Ground.WORLDS;

	private List<com.mopicmp.npcstudio.server.Worlds.World> worlds = List.of();
	private List<com.mopicmp.npcstudio.server.Backups.Backup> backups = List.of();
	private boolean worldsRead;
	private int worldScroll;
	private int worldHeight;
	private int worldListTop;

	/** How tall a world card is: two lines and its dimensions, plus the gap. */
	private static final int WORLD_ROW = 46;

	/** How tall a copy's row is. */
	private static final int BACKUP_ROW = 34;

	/**
	 * Which window is in front, if any.
	 *
	 * A field rather than a boolean per window: everything modal in this screen —
	 * what is placed, what takes a press, what Escape means — asks the same
	 * question, and two booleans that must never both be true is a state nobody
	 * remembers to keep.
	 */
	private enum Window {
		NONE,
		WORLD,
		PLACE,
		PACKS,
		RENAME,
		PRIVILEGE
	}

	private Window window = Window.NONE;

	private boolean windowUp() {
		return window != Window.NONE;
	}
	private String newWorldName = "";
	private String newWorldSeed = "";
	private String newWorldType = "minecraft:normal";
	private EditBox worldNameBox;
	private EditBox worldSeedBox;

	/** Which version last wrote each world, by world name. */
	private java.util.Map<String, String> worldVersions = java.util.Map.of();

	private void readWorlds() {
		ManagedServer which = server;
		manager.worldTypes(which, found -> {
			if (server == which) worldTypes = found;
		});
		worldsRead = false;
		boolean[] arrived = {false, false};
		manager.worlds(which, found -> {
			if (server != which) return;
			worlds = found;
			manager.worldVersions(which, found.stream()
				.map(com.mopicmp.npcstudio.server.Worlds.World::name).toList(), versions -> {
					if (server == which) worldVersions = versions;
				});
			arrived[0] = true;
			if (arrived[1] && tab == Tab.WORLDS) {
				worldsRead = true;
				rebuild();
			}
		});
		manager.backups(which, found -> {
			if (server != which) return;
			backups = found;
			readBackupLooks(found);
			arrived[1] = true;
			if (arrived[0] && tab == Tab.WORLDS) {
				worldsRead = true;
				rebuild();
			}
		});
	}

	private void worldWidgets() {
		int y = contentTop + 4;
		int at = contentLeft;
		for (Ground which : Ground.values()) {
			Component label = Component.translatable(which.label);
			int across = font.width(label) + 24;
			addRenderableWidget(new SubTab(at, y - 4, across, 18, which == ground, label, () -> {
				ground = which;
				worldScroll = 0;
				window = Window.NONE;
				rebuild();
			}));
			at += across;
		}
		y += 22;

		if (!worldsRead) {
			readWorlds();
			return;
		}
		switch (ground) {
			case WORLDS -> worldsHalf(y);
			case BACKUPS -> backupsHalf(y);
			case PLACES -> placesHalf(y);
		}
	}

	private void worldsHalf(int top) {
		int x = contentLeft + PAD;

		addRenderableWidget(new IconTextButton(x, top, 130, 18, Icon.ADD,
			Component.translatable("npc_studio.server.worlds.make"), GOOD, () -> {
				window = window == Window.WORLD ? Window.NONE : Window.WORLD;
				if (window == Window.WORLD && newWorldName.isBlank()) newWorldName = suggestName();
				rebuild();
			}));
		addRenderableWidget(new IconTextButton(x + 134, top, 90, 18, Icon.FOLDER,
			Component.translatable("npc_studio.server.folder"), TEXT_DIM,
			() -> net.minecraft.util.Util.getPlatform().openPath(server.path())));

		worldsList(top + 24, null);
	}

	/**
	 * The saves, and whatever is unfolded under them.
	 *
	 * Drawn and placed by one method for the third time in this file, and for the
	 * third time because the two copies drifted: every row here depends on the one
	 * above it, a row is now two heights depending on whether it is open, and the
	 * form above them all is a fourth. Two versions of that arithmetic disagree the
	 * first time either changes — which is precisely how the form came to be drawn
	 * underneath the list.
	 */
	private void worldsList(int top, GuiGraphicsExtractor graphics) {
		boolean draw = graphics != null;
		int x = contentLeft + PAD;
		int right = width - PAD;
		int bottom = height - PAD;

		int y = top;
		worldListTop = y;
		if (draw) graphics.enableScissor(contentLeft, y - 2, width, bottom);
		y -= worldScroll;

		String current = manager.currentWorld();
		Component use = Component.translatable("npc_studio.server.worlds.use");
		Component copy = Component.translatable("npc_studio.server.worlds.copy");
		Component swap = Component.translatable("npc_studio.server.worlds.replace");
		Component fresh = Component.translatable("npc_studio.server.worlds.reset");
		Component drop = Component.translatable("npc_studio.server.content.remove");
		Component packs = Component.translatable("npc_studio.server.packs.title");
		for (var world : worlds) {
			boolean here = world.is(current);
			boolean open = unfolded.contains(world.name());
			int step = shut("world:" + world.name(), WORLD_ROW);
			if (step <= 0) continue;
			// A world on its way out takes its dimensions with it and is drawn
			// clipped, so the list closes over it rather than jumping.
			if (step < WORLD_ROW) {
				if (draw) {
					graphics.enableScissor(contentLeft, y, width, y + step);
					graphics.fill(x, y, right, y + WORLD_ROW - 6, CARD);
					graphics.fill(x, y, right, y + 1, EDGE);
					graphics.text(font, world.name(), x + 8 + FACE + 6, y + 6, TEXT_DIM);
					graphics.disableScissor();
				}
				y += step;
				continue;
			}
			// Widgets cannot be clipped the way drawing can, so a row half off the
			// bottom gets its picture and its buttons on the next scroll and not
			// before. Drawing does not wait: the scissor takes care of that.
			boolean room = y >= worldListTop - 2 && y + WORLD_ROW - 6 <= bottom;

			if (draw) {
				graphics.fill(x, y, right, y + WORLD_ROW - 6, CARD);
				graphics.fill(x, y, right, y + 1, here ? ACCENT : EDGE);
				graphics.fill(x, y + WORLD_ROW - 7, right, y + WORLD_ROW - 6, EDGE);
				graphics.fill(x, y, x + 1, y + WORLD_ROW - 6, EDGE);
				graphics.fill(right - 1, y, right, y + WORLD_ROW - 6, EDGE);

				int text = x + 8 + FACE + 6;
				graphics.text(font, world.name(), text, y + 6, here ? ACCENT : TEXT);
				if (here) {
					graphics.text(font, Component.translatable("npc_studio.server.worlds.current"),
						text + 4 + font.width(world.name()), y + 6, GOOD);
				}
				// What it takes and what is in it: the two facts somebody scrolls a
				// list of saves for.
				String said = bytes(world.size());
				if (!world.dimensions().isEmpty()) {
					said += " · " + count(world.dimensions().size(),
						"npc_studio.server.worlds.dimensions");
				}
				if (world.folders().size() > 1) {
					said += " · " + count(world.folders().size(),
						"npc_studio.server.worlds.folders");
				}
				// The version that last wrote it. Not a detail: a world written by
				// a newer game is the one thing an older server refuses outright,
				// and the refusal, when it comes, is a stack trace at start-up.
				String version = worldVersions.get(world.name());
				if (version != null && !version.isBlank()) said += " · " + version;
				graphics.text(font, said, text, y + 18, TEXT_DIM);
				if (world.played() > 0) {
					graphics.text(font, Component.translatable("npc_studio.server.worlds.played",
						when(java.time.Instant.ofEpochMilli(world.played()))), text, y + 29,
						TEXT_DIM);
				}
			} else if (room) {
				addRenderableWidget(new Picture(x + 8, y + 4, FACE,
					Component.translatable("npc_studio.server.worlds.face"),
					() -> WorldIcon.of(world.folder()), Icon.ENVIRONMENT,
					() -> {
						String said = ServerIconPick.choose(world.folder(), "icon.png",
							Component.translatable("npc_studio.server.worlds.face").getString());
						WorldIcon.forget(world.folder());
						if (!said.isEmpty()) say(said);
					}));

				// The far right of the row belongs to the chevron, so the buttons
				// start left of it rather than under it.
				int put = right - CHEVRON - Pill.wide(drop);
				addRenderableWidget(new Pill(put, y + 11, Icon.REMOVE, drop, WARN,
					() -> dropWorld(world)));
				put -= Pill.wide(packs) + 4;
				addRenderableWidget(new Pill(put, y + 11, Icon.ASSETS, packs, ACCENT,
					() -> openPacks(world)));
				put -= Pill.wide(copy) + 4;
				addRenderableWidget(new Pill(put, y + 11, Icon.SAVE, copy, ACCENT,
					() -> makeCopy(world)));
				if (!here) {
					put -= Pill.wide(use) + 4;
					addRenderableWidget(new Pill(put, y + 11, Icon.CHECK, use, GOOD,
						() -> useWorld(world)));
				}
				// Last, so the two above it get the press when the press is on them:
				// the first child under the mouse takes a click, and this one is as
				// wide as the row.
				addRenderableWidget(new Unfold(x, y, right - x, WORLD_ROW - 6, open,
					Component.translatable("npc_studio.server.worlds.parts"), () -> {
						if (!unfolded.remove(world.name())) unfolded.add(world.name());
						rebuild();
					}));
			}
			y += WORLD_ROW;

			if (!open) continue;
			for (var part : world.dimensions()) {
				int said = right - CHEVRON - Pill.wide(swap) - 4 - Pill.wide(fresh) - 10;
				if (draw) {
					graphics.fill(x + 16, y, right, y + DIM_ROW - 2, 0xFF10141A);
					graphics.fill(x + 16, y + DIM_ROW - 3, right, y + DIM_ROW - 2, EDGE);
					Icon.ENVIRONMENT.draw(graphics, x + 22, y + 2, TEXT_DIM);
					graphics.text(font, dimensionName(part), x + 44, y + 6, TEXT);
					String takes = bytes(part.size());
					graphics.text(font, takes, said - font.width(takes), y + 6, TEXT_DIM);
				} else if (y >= worldListTop - 2 && y + DIM_ROW - 2 <= bottom) {
					addRenderableWidget(new Pill(said + 10, y + 1, Icon.RESET,
						fresh, WARN, () -> resetDimension(world, part)));
					addRenderableWidget(new Pill(said + 14 + Pill.wide(fresh), y + 1, Icon.FOLDER,
						swap, ACCENT, () -> replaceDimension(world, part)));
				}
				y += DIM_ROW;
			}
			y += 6;
		}

		if (draw) {
			graphics.disableScissor();
			scrollbar(graphics, worldListTop, bottom, worldScroll, worldHeight);
			if (worlds.isEmpty()) {
				graphics.text(font, Component.translatable("npc_studio.server.worlds.none"),
					x + PAD, y + 4, TEXT_DIM);
			}
		}
		worldHeight = Math.max(0, y + worldScroll + PAD - bottom);
	}

	/**
	 * A number and the word after it, in the form that number takes.
	 *
	 * «1 измерений» is what a single translated word gives, and it is wrong in the
	 * commonest case there is — a fresh server has exactly one dimension. Three
	 * forms per word, chosen by the Russian rule: one, two-to-four, and the rest,
	 * with the teens going to the last because eleven behaves like five. English
	 * fills the same three keys with two distinct words and loses nothing.
	 */
	private static String count(int number, String key) {
		int last = number % 10;
		int teen = number % 100;
		String form = last == 1 && teen != 11 ? "one"
			: last >= 2 && last <= 4 && (teen < 12 || teen > 14) ? "few" : "many";
		return number + " " + Component.translatable(key + "." + form).getString();
	}

	/** How wide the picture on a row is, and how tall. */
	private static final int FACE = 32;

	/** How tall one dimension's line is, unfolded under its world. */
	private static final int DIM_ROW = 22;

	/** The strip at the right of a row that belongs to the chevron and nothing else. */
	private static final int CHEVRON = 24;

	/** Which worlds are showing their parts. By name, because the list is rebuilt. */
	private final java.util.Set<String> unfolded = new java.util.HashSet<>();

	/**
	 * What to call a dimension, given that most of them have no name of their own.
	 *
	 * The three the game ships with are known and are worth translating. Anything
	 * a mod added is shown exactly as it is written on disk — {@code mymod:moon} —
	 * because that string is the only name it has here, and inventing a prettier
	 * one would stop it matching the folder somebody is looking at.
	 */
	private static Component dimensionName(com.mopicmp.npcstudio.server.Worlds.Dimension part) {
		String name = part.name();
		if (name.startsWith("minecraft:")) {
			String key = "npc_studio.server.worlds.dim." + name.substring("minecraft:".length());
			Component said = Component.translatable(key);
			if (!said.getString().equals(key)) return said;
		}
		return Component.literal(name);
	}

	/**
	 * The window for a world that does not exist yet.
	 *
	 * A window over everything rather than a card pushed into the list, because
	 * that is what it is: while it is up, nothing else on the tab is placed at
	 * all. Drawn and placed by one method, row by row, with the running position
	 * shared between the two — the version that kept two copies of these offsets
	 * drew the form and the list through each other.
	 */
	private void worldWindow(GuiGraphicsExtractor graphics) {
		boolean draw = graphics != null;
		int col = WINDOW - PAGE_PAD * 2;
		int half = (col - 8) / 2;
		boolean own = isCustomType();
		boolean flat = FLAT.equals(newWorldType);
		boolean ownFlat = flat && ownTemplate;

		Component says = Component.translatable(flat
			? "npc_studio.server.worlds.make_says_flat"
			: DEBUG.equals(newWorldType)
				? "npc_studio.server.worlds.make_says_debug"
				: "npc_studio.server.worlds.make_says");
		int tall = PAGE_PAD + 12 + PAGE_PAD
			+ (LABEL + FIELD + 8) * 2
			+ (own ? FIELD + 8 : 0)
			+ (flat ? LABEL + FIELD + 8 : 0)
			+ (ownFlat ? FIELD + 8 : 0)
			+ font.wordWrapHeight(says, col) + 12 + 18 + PAGE_PAD;

		int x = (width - WINDOW) / 2;
		int top = Math.max(TOP, (height - tall) / 2);
		int inner = x + PAGE_PAD;
		windowX = x;
		windowY = top;
		windowTall = tall;

		if (draw) {
			graphics.fill(0, 0, width, height, 0xB0000000);
			graphics.fill(x - 1, top - 1, x + WINDOW + 1, top + tall + 1, EDGE);
			graphics.fill(x, top, x + WINDOW, top + tall, 0xFF1B2028);
			graphics.fill(x, top, x + WINDOW, top + 1, ACCENT);
			graphics.text(font, Component.translatable("npc_studio.server.worlds.make"),
				inner, top + PAGE_PAD, TEXT);
		}

		int y = top + PAGE_PAD + 12 + PAGE_PAD;

		if (draw) {
			label(graphics, "npc_studio.server.worlds.name", inner, y);
			label(graphics, "npc_studio.server.worlds.seed", inner + half + 8, y);
		} else {
			worldNameBox = box(inner, y + LABEL, half, "npc_studio.server.worlds.name",
				newWorldName, 64, typed -> newWorldName = typed);
			worldSeedBox = box(inner + half + 8, y + LABEL, half,
				"npc_studio.server.worlds.seed", newWorldSeed, 64, typed -> newWorldSeed = typed);
		}
		y += LABEL + FIELD + 8;

		int typeY = y + LABEL;
		if (draw) {
			label(graphics, "npc_studio.server.worlds.type", inner, y);
		} else {
			put(new FlatButton(inner, typeY, half, FIELD,
				Component.literal(typeName().getString() + "  ▾"), ACCENT, () -> {
					List<Dropdown.Option> options = new ArrayList<>();
					for (String each : worldTypes) {
						options.add(new Dropdown.Option(each, typeName(each)));
					}
					options.add(new Dropdown.Option(OWN_TYPE,
						Component.translatable("npc_studio.server.worlds.type.own")));
					dropdown.open(inner, typeY + FIELD, half, height, options,
						own ? OWN_TYPE : newWorldType, picked -> {
							newWorldType = picked.equals(OWN_TYPE) ? ownType : picked;
							ownTyping = picked.equals(OWN_TYPE);
							rebuild();
						});
				}));
		}
		y += LABEL + FIELD + 8;

		if (own) {
			if (!draw) {
				worldTypeBox = box(inner, y, col, "npc_studio.server.worlds.type.own_hint",
					ownType, 80, typed -> {
						ownType = typed;
						newWorldType = typed;
					});
			}
			y += FIELD + 8;
		}

		if (flat) {
			int templateY = y + LABEL;
			if (draw) {
				label(graphics, "npc_studio.server.worlds.template", inner, y);
			} else {
				put(new FlatButton(inner, templateY, col, FIELD,
					Component.literal(templateName().getString() + "  ▾"), ACCENT, () -> {
						List<Dropdown.Option> options = new ArrayList<>();
						for (var each : com.mopicmp.npcstudio.server.Flat.PRESETS) {
							options.add(new Dropdown.Option(each.id(), Component.translatable(
								"npc_studio.server.worlds.template." + each.id())));
						}
						options.add(new Dropdown.Option(OWN_TYPE,
							Component.translatable("npc_studio.server.worlds.template.own")));
						dropdown.open(inner, templateY + FIELD, col, height, options,
							ownTemplate ? OWN_TYPE : template, picked -> {
								ownTemplate = picked.equals(OWN_TYPE);
								if (!ownTemplate) template = picked;
								rebuild();
							});
					}));
			}
			y += LABEL + FIELD + 8;
		}

		if (ownFlat) {
			if (!draw) {
				worldLayersBox = box(inner, y, col,
					"npc_studio.server.worlds.template.own_hint", layers, 200,
					typed -> layers = typed);
			}
			y += FIELD + 8;
		}

		if (draw) {
			graphics.textWithWordWrap(font, says, inner, y, col, TEXT_DIM);
			return;
		}

		Component go = Component.translatable("npc_studio.server.worlds.make_go");
		int buttonsY = top + tall - PAGE_PAD - 18;
		Pill make = new Pill(inner, buttonsY, Icon.CHECK, go, GOOD, this::createWorld);
		make.active = readyToMake();
		put(make);
		put(new Pill(inner + Pill.wide(go) + 6, buttonsY,
			Icon.CLOSED, Component.translatable("npc_studio.server.key.cancel"), TEXT_DIM, () -> {
				window = Window.NONE;
				rebuild();
			}));
	}

	/** A field with its frame remembered, which is four lines every time. */
	private EditBox box(int x, int y, int across, String label, String value, int most,
			java.util.function.Consumer<String> onType) {
		EditBox made = Field.make(font, x, y, across, FIELD, Component.translatable(label));
		made.setMaxLength(most);
		made.setValue(value);
		made.setResponder(onType::accept);
		put(made);
		(windowFrom < 0 ? framed : windowFrames).add(new Framed(made, x, y, across, FIELD));
		return made;
	}

	/**
	 * Whether there is enough here to make anything with.
	 *
	 * The type is checked for shape when it is somebody's own, because a blank one
	 * would be written as blank, read as the default and generate a perfectly
	 * ordinary world — for somebody who asked for a modded one and would find out
	 * by walking around in it. The layers are checked by trying to write them: if
	 * they will not turn into settings here they would turn into a server that
	 * refuses to start there.
	 */
	private boolean readyToMake() {
		if (manager.running() || manager.makingWorld()) return false;
		if (!com.mopicmp.npcstudio.server.Worlds.validName(newWorldName.trim())) return false;
		if (isCustomType() && !ownType.trim().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return false;
		return !generatorSettings().equals(BAD_LAYERS);
	}

	/** What goes into {@code generator-settings}, or {@link #BAD_LAYERS} if it cannot. */
	private String generatorSettings() {
		if (!FLAT.equals(newWorldType)) return "";
		if (!ownTemplate) {
			return com.mopicmp.npcstudio.server.Flat.settings(
				com.mopicmp.npcstudio.server.Flat.preset(template));
		}
		try {
			return com.mopicmp.npcstudio.server.Flat.fromWritten(layers, null);
		} catch (IllegalArgumentException wrong) {
			return BAD_LAYERS;
		}
	}

	private static final String BAD_LAYERS = "\u0000";

	/** How wide the window for a new world is. */
	private static final int WINDOW = 380;

	/** What the dropdown calls the entry that is not a preset but a field. */
	private static final String OWN_TYPE = "npc_studio:own";

	private static final String FLAT = "minecraft:flat";
	private static final String DEBUG = "minecraft:debug_all_block_states";

	private String ownType = "";
	private boolean ownTyping;
	private EditBox worldTypeBox;

	/** Which flat template, and the layers somebody wrote instead of one. */
	private String template = "classic_flat";
	private boolean ownTemplate;
	private String layers = "minecraft:bedrock,2*minecraft:dirt,minecraft:grass_block";
	private EditBox worldLayersBox;

	/** Whether the type is one somebody is spelling out rather than one on the list. */
	private boolean isCustomType() {
		return ownTyping || !worldTypes.contains(newWorldType);
	}

	private Component typeName() {
		if (isCustomType()) return Component.translatable("npc_studio.server.worlds.type.own");
		return typeName(newWorldType);
	}

	/**
	 * What to call a type, given that only the game's own have names.
	 *
	 * A mod's preset is shown by its id, exactly as it is written in the jar and
	 * exactly as it goes into the file. Making up a prettier name would be making
	 * up a name for something whose only name that is.
	 */
	private static Component typeName(String type) {
		if (type.startsWith("minecraft:")) {
			String key = "npc_studio.server.worlds.type." + shortType(type);
			Component said = Component.translatable(key);
			if (!said.getString().equals(key)) return said;
		}
		return Component.literal(type);
	}

	private Component templateName() {
		return Component.translatable(ownTemplate
			? "npc_studio.server.worlds.template.own"
			: "npc_studio.server.worlds.template." + template);
	}

	/**
	 * The kinds this server understands, which is not a constant.
	 *
	 * Starts as the game's own and is replaced by what is actually installed the
	 * moment the folder has been read. A fixed list is right for a vanilla server
	 * and wrong for every modded one — Biomes O' Plenty ships its type in its own
	 * jar, and a list that cannot show it is a list that says the mod did not
	 * work.
	 */
	private List<String> worldTypes = com.mopicmp.npcstudio.server.WorldTypes.VANILLA;

	private static String shortType(String type) {
		int colon = type.indexOf(':');
		return colon < 0 ? type : type.substring(colon + 1);
	}

	private String suggestName() {
		for (int at = 2; at < 100; at++) {
			String tried = "world" + at;
			boolean taken = false;
			for (var world : worlds) {
				if (world.is(tried)) taken = true;
			}
			if (!taken) return tried;
		}
		return "world";
	}

	/**
	 * Make the world, which means running the core once.
	 *
	 * The window stays up while it happens and says which part it is at. Half of
	 * this is waiting on a server to generate spawn chunks, and a window that
	 * vanished on the press would leave a minute of nothing at all.
	 */
	private void createWorld() {
		trouble = "";
		String named = newWorldName.trim();
		manager.makeWorld(server, named, newWorldSeed, newWorldType, generatorSettings(),
			said -> rebuild(),
			() -> {
				say(Component.translatable("npc_studio.server.worlds.made", named).getString());
				readWorlds();
				rebuild();
			}, failed -> {
				trouble = failed;
				say(Component.translatable("npc_studio.server.worlds.failed").getString());
				readWorlds();
				rebuild();
			});
		// The window goes away at once and the strip along the top takes over.
		// Making a world is minutes of a server generating chunks; a window that
		// held the screen hostage for that would be a window nobody forgives.
		window = Window.NONE;
		newWorldName = "";
		newWorldSeed = "";
		rebuild();
	}

	/**
	 * Point the server at another world, after saying what that means.
	 *
	 * It is one line in a file and a restart, and the window says so — a button
	 * that implied the world changes now would leave somebody looking at an
	 * unchanged world and wondering what they did wrong.
	 */
	private void useWorld(com.mopicmp.npcstudio.server.Worlds.World world) {
		confirm.ask(
			Component.translatable("npc_studio.server.worlds.use_title"),
			Component.translatable(manager.running()
				? "npc_studio.server.worlds.use_body_running"
				: "npc_studio.server.worlds.use_body", world.name()),
			Component.translatable("npc_studio.server.worlds.use_yes"),
			Component.translatable("npc_studio.server.no_session.no"),
			() -> manager.useWorld(server, world.name(), () -> {
				say(Component.translatable(manager.running()
					? "npc_studio.server.worlds.used_restart"
					: "npc_studio.server.worlds.used", world.name()).getString());
				readWorlds();
				rebuild();
			}, failed -> {
				trouble = failed;
				rebuild();
			}));
	}

	private void makeCopy(com.mopicmp.npcstudio.server.Worlds.World world) {
		trouble = "";
		say(Component.translatable("npc_studio.server.worlds.copying").getString());
		manager.backup(server, world, this::say, made -> {
			say(Component.translatable("npc_studio.server.worlds.copied",
				made.file()).getString());
			readWorlds();
			rebuild();
		}, failed -> {
			trouble = failed;
			say(Component.translatable("npc_studio.server.worlds.failed").getString());
			rebuild();
		});
	}

	private void backupsHalf(int top) {
		int x = contentLeft + PAD;
		int right = width - PAD;

		addRenderableWidget(new IconTextButton(x, top, 90, 18, Icon.FOLDER,
			Component.translatable("npc_studio.server.folder"), TEXT_DIM,
			() -> net.minecraft.util.Util.getPlatform()
				.openPath(com.mopicmp.npcstudio.server.Backups.folder(server))));

		int y = top + 24;
		worldListTop = y;
		int bottom = height - PAD;
		y -= worldScroll;
		Component back = Component.translatable("npc_studio.server.worlds.restore");
		Component drop = Component.translatable("npc_studio.server.content.remove");
		Component name = Component.translatable("npc_studio.server.worlds.rename");
		for (var backup : backups) {
			if (y >= worldListTop - 2 && y + BACKUP_ROW <= bottom) {
				int put = right - 6 - Pill.wide(drop);
				addRenderableWidget(new Pill(put, y + 6, Icon.REMOVE, drop, WARN,
					() -> dropBackup(backup)));
				put -= Pill.wide(back) + 4;
				Pill restore = new Pill(put, y + 6, Icon.RESET, back, ACCENT,
					() -> restore(backup));
				// A restore over a running server is refused anyway; showing it as
				// unavailable is the same answer given a moment earlier.
				restore.active = !manager.running();
				addRenderableWidget(restore);
				put -= Pill.wide(name) + 4;
				addRenderableWidget(new Pill(put, y + 6, Icon.SETTINGS, name, TEXT_DIM,
					() -> openRename(backup)));
			}
			y += BACKUP_ROW;
		}
		worldHeight = Math.max(0, y + worldScroll + PAD - bottom);
	}

	/** What each copy holds: the world's picture, and what version wrote it. */
	private java.util.Map<String, BackupLook.Look> backupLooks = java.util.Map.of();

	private void readBackupLooks(List<com.mopicmp.npcstudio.server.Backups.Backup> found) {
		ManagedServer which = server;
		manager.backupLooks(found, looks -> {
			if (server == which) backupLooks = looks;
		});
	}

	private com.mopicmp.npcstudio.server.Backups.Backup renaming;
	private String renameTo = "";
	private EditBox renameBox;

	private void openRename(com.mopicmp.npcstudio.server.Backups.Backup backup) {
		renaming = backup;
		renameTo = backup.label();
		window = Window.RENAME;
		rebuild();
		if (renameBox != null) setFocused(renameBox);
	}

	/**
	 * Give a copy a name of one's own.
	 *
	 * A window with one field, because the alternative — a box that appears inside
	 * the row — is a box that scrolls away under the mouse while somebody is
	 * typing in it. The window says what the file will be called, so that the
	 * thing this actually does is not a surprise: it renames a file, and the world
	 * and the moment stay in the name whatever is typed here.
	 */
	private void renameWindow(GuiGraphicsExtractor graphics) {
		boolean draw = graphics != null;
		int col = RENAME_WINDOW - PAGE_PAD * 2;
		int tall = PAGE_PAD + 12 + PAGE_PAD + LABEL + FIELD + 8 + 11 + GROUP + 18 + PAGE_PAD;

		int x = (width - RENAME_WINDOW) / 2;
		int top = Math.max(TOP, (height - tall) / 2);
		int inner = x + PAGE_PAD;
		windowX = x;
		windowY = top;
		windowTall = tall;

		if (draw) {
			graphics.fill(0, 0, width, height, 0xB0000000);
			graphics.fill(x - 1, top - 1, x + RENAME_WINDOW + 1, top + tall + 1, EDGE);
			graphics.fill(x, top, x + RENAME_WINDOW, top + tall, 0xFF1B2028);
			graphics.fill(x, top, x + RENAME_WINDOW, top + 1, ACCENT);
			graphics.text(font, Component.translatable("npc_studio.server.worlds.rename_title"),
				inner, top + PAGE_PAD, TEXT);
		}

		int y = top + PAGE_PAD + 12 + PAGE_PAD;
		if (draw) {
			graphics.text(font, Component.translatable("npc_studio.server.worlds.rename_field"),
				inner, y, TEXT_DIM);
			// The file it will be, spelled out. The world and the stamp in front of
			// the words are not decoration: they are how a copy still knows which
			// world it is of after everything else about it has been forgotten.
			String shown = renaming == null ? "" : com.mopicmp.npcstudio.server.Backups
				.folder(server).getFileName() + "/" + fileWouldBe();
			graphics.text(font, shorten(shown, col), inner, y + LABEL + FIELD + 8, TEXT_DIM);
		} else {
			renameBox = Field.make(font, inner, y + LABEL, col, FIELD,
				Component.translatable("npc_studio.server.worlds.rename_field"));
			renameBox.setMaxLength(64);
			renameBox.setValue(renameTo);
			renameBox.setResponder(typed -> renameTo = typed);
			put(renameBox);
			windowFrames.add(new Framed(renameBox, inner, y + LABEL, col, FIELD));

			int buttonsY = top + tall - PAGE_PAD - 18;
			Component keep = Component.translatable("npc_studio.server.worlds.rename_yes");
			put(new Pill(inner, buttonsY, Icon.SAVE, keep, GOOD, this::keepRename));
			Component close = Component.translatable("npc_studio.server.key.cancel");
			put(new Pill(inner + col - Pill.wide(close), buttonsY, Icon.CLOSED, close, TEXT_DIM,
				() -> {
					window = Window.NONE;
					renaming = null;
					rebuild();
				}));
		}
	}

	private static final int RENAME_WINDOW = 380;

	private String newGroup = "";

	/**
	 * Making a privilege, which is one field and one warning.
	 *
	 * A window rather than a row that appears at the end of the list, because a
	 * name here cannot be changed afterwards without a second command, and because
	 * it is the one thing on that tab that makes something rather than changing
	 * something that is already there.
	 */
	private void privilegeWindow(GuiGraphicsExtractor graphics) {
		boolean draw = graphics != null;
		int col = RENAME_WINDOW - PAGE_PAD * 2;
		int tall = PAGE_PAD + 12 + PAGE_PAD + LABEL + FIELD + 8 + 30 + GROUP + 18 + PAGE_PAD;

		int x = (width - RENAME_WINDOW) / 2;
		int top = Math.max(TOP, (height - tall) / 2);
		int inner = x + PAGE_PAD;
		windowX = x;
		windowY = top;
		windowTall = tall;

		int y = top + PAGE_PAD + 12 + PAGE_PAD;
		if (draw) {
			graphics.fill(0, 0, width, height, 0xB0000000);
			graphics.fill(x - 1, top - 1, x + RENAME_WINDOW + 1, top + tall + 1, EDGE);
			graphics.fill(x, top, x + RENAME_WINDOW, top + tall, 0xFF1B2028);
			graphics.fill(x, top, x + RENAME_WINDOW, top + 1, ACCENT);
			graphics.text(font, Component.translatable("npc_studio.server.rights.create_title"),
				inner, top + PAGE_PAD, TEXT);
			graphics.text(font, Component.translatable("npc_studio.server.rights.create_field"),
				inner, y, TEXT_DIM);
			// Why the name is restricted, said where the restriction is met rather
			// than after somebody has broken it.
			graphics.textWithWordWrap(font,
				Component.translatable("npc_studio.server.rights.create_body"),
				inner, y + LABEL + FIELD + 8, col, TEXT_DIM);
		} else {
			EditBox box = Field.make(font, inner, y + LABEL, col, FIELD,
				Component.translatable("npc_studio.server.rights.create_field"));
			box.setHint(Component.translatable("npc_studio.server.rights.create_field"));
			box.setMaxLength(36);
			box.setValue(newGroup);
			box.setResponder(typed -> newGroup = typed);
			put(box);
			windowFrames.add(new Framed(box, inner, y + LABEL, col, FIELD));

			int buttonsY = top + tall - PAGE_PAD - 18;
			Component keep = Component.translatable("npc_studio.server.rights.create");
			put(new Pill(inner, buttonsY, Icon.ADD, keep, GOOD, this::makeGroup));
			Component close = Component.translatable("npc_studio.server.key.cancel");
			put(new Pill(inner + col - Pill.wide(close), buttonsY, Icon.CLOSED, close, TEXT_DIM,
				() -> {
					window = Window.NONE;
					newGroup = "";
					rebuild();
				}));
		}
	}

	private void makeGroup() {
		String name = com.mopicmp.npcstudio.server.Privileges.tidy(newGroup);
		if (!com.mopicmp.npcstudio.server.Privileges.validGroup(name)) {
			rightsTrouble = Component.translatable(
				"npc_studio.server.rights.bad_group").getString();
			window = Window.NONE;
			rebuild();
			return;
		}
		if (rights.group(name) != null) {
			rightsTrouble = Component.translatable(
				"npc_studio.server.rights.group_there", name).getString();
			window = Window.NONE;
			rebuild();
			return;
		}
		window = Window.NONE;
		newGroup = "";
		rightsPicked = name;
		toRights(List.of("lp creategroup " + name));
	}

	/** The name the file would take, worked out the same way the rename does. */
	private String fileWouldBe() {
		if (renaming == null) return "";
		String stamped = renaming.file().endsWith(".zip")
			? renaming.file().substring(0, renaming.file().length() - 4) : renaming.file();
		int space = stamped.indexOf(' ');
		String head = space < 0 ? stamped : stamped.substring(0, space);
		String wanted = renameTo.trim();
		return wanted.isEmpty() ? head + ".zip" : head + " " + wanted + ".zip";
	}

	private void keepRename() {
		var backup = renaming;
		if (backup == null) return;
		window = Window.NONE;
		renaming = null;
		// Straight away, not when the rename comes back: the field is a widget of
		// a window that is no longer up, and a widget nobody draws is a widget
		// somebody can still click on.
		rebuild();
		manager.renameBackup(backup, renameTo, () -> {
			// The picture was remembered under the old file name, and the old file
			// name is gone.
			BackupLook.forget(backup.file());
			say(Component.translatable("npc_studio.server.worlds.renamed").getString());
			readWorlds();
			rebuild();
		}, said -> {
			trouble = said;
			say(said);
			rebuild();
		});
	}

	private void restore(com.mopicmp.npcstudio.server.Backups.Backup backup) {
		confirm.ask(
			Component.translatable("npc_studio.server.worlds.restore_title"),
			Component.translatable("npc_studio.server.worlds.restore_body",
				backup.world(), when(backup.made())),
			Component.translatable("npc_studio.server.worlds.restore_yes"),
			Component.translatable("npc_studio.server.no_session.no"),
			() -> {
				trouble = "";
				manager.restore(server, backup, this::say, () -> {
					say(Component.translatable("npc_studio.server.worlds.restored",
						backup.world()).getString());
					readWorlds();
					rebuild();
				}, failed -> {
					trouble = failed;
					say(Component.translatable("npc_studio.server.worlds.failed").getString());
					rebuild();
				});
			});
	}

	private void dropBackup(com.mopicmp.npcstudio.server.Backups.Backup backup) {
		manager.dropBackup(backup, () -> {
			readWorlds();
			rebuild();
		}, said -> {
			trouble = said;
			rebuild();
		});
	}

	// ---- drawing

	private void drawWorlds(GuiGraphicsExtractor graphics) {
		int x = contentLeft + PAD;
		int right = width - PAD;
		int top = contentTop + 4 + 22;
		if (!worldsRead) {
			graphics.text(font, Component.translatable("npc_studio.server.worlds.reading"),
				x + PAD, top + 4, TEXT_DIM);
			return;
		}
		switch (ground) {
			case WORLDS -> drawWorldsHalf(graphics, top);
			case BACKUPS -> drawBackupsHalf(graphics, top);
			case PLACES -> drawPlacesHalf(graphics, top);
		}

		if (!trouble.isEmpty()) {
			// On a panel of its own along the bottom. A refusal to copy is three
			// sentences and the list behind it is rows of text: written straight
			// over them, neither could be read.
			int room = Math.min(560, right - x - PAD);
			int tall = font.wordWrapHeight(Component.literal(trouble), room - PAD * 2) + PAD * 2;
			int at = height - PAD - tall;
			graphics.fill(x, at, x + room, at + tall, 0xFF15191F);
			graphics.fill(x, at, x + room, at + 1, WARN);
			graphics.textWithWordWrap(font, Component.literal(trouble),
				x + PAD, at + PAD, room - PAD * 2, WARN);
		}
	}

	private void drawWorldsHalf(GuiGraphicsExtractor graphics, int top) {
		worldsList(top + 24, graphics);
	}

	/**
	 * The whole row, as a thing to press, showing that it can be.
	 *
	 * It draws an outline under the mouse and a chevron at its edge, and nothing
	 * else: everything inside it — the picture, the two buttons — is a widget of
	 * its own, added before this one so that a press on any of them is theirs. A
	 * row that opens on a click has to look like a row that opens on a click, or
	 * the dimensions inside it are a secret.
	 */
	private final class Unfold extends AbstractWidget {

		private final boolean open;
		private final Runnable onPress;

		Unfold(int x, int y, int width, int height, boolean open, Component label,
				Runnable onPress) {
			super(x, y, width, height, label);
			this.open = open;
			this.onPress = onPress;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor graphics,
				int mouseX, int mouseY, float delta) {
			(open ? Icon.COLLAPSE : Icon.EXPAND).draw(graphics,
				getX() + width - CHEVRON + 4, getY() + (height - Icon.SIZE) / 2,
				isHovered() ? ACCENT : TEXT_DIM);
			if (!isHovered()) return;
			graphics.fill(getX(), getY(), getX() + width, getY() + 1, ACCENT);
			graphics.fill(getX(), getY() + height - 1, getX() + width, getY() + height, ACCENT);
			graphics.fill(getX(), getY(), getX() + 1, getY() + height, ACCENT);
			graphics.fill(getX() + width - 1, getY(), getX() + width, getY() + height, ACCENT);
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			onPress.run();
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			if (pressing(event)) {
				onPress.run();
				return true;
			}
			return super.keyPressed(event);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}

	/**
	 * Delete a world, which is the only button here that removes months of somebody's time.
	 *
	 * The question names what will be gone and says where the copy will be, and it
	 * says something different when the world is the one the server is set to
	 * play: deleting that one leaves the server to make an empty world of the same
	 * name on its next start, which looks exactly like the old one having been
	 * emptied.
	 */
	private void dropWorld(com.mopicmp.npcstudio.server.Worlds.World world) {
		if (manager.running()) {
			trouble = Component.translatable("npc_studio.server.worlds.drop_running").getString();
			rebuild();
			return;
		}
		boolean here = world.is(manager.currentWorld());
		confirm.ask(
			Component.translatable("npc_studio.server.worlds.drop_title"),
			Component.translatable(here
				? "npc_studio.server.worlds.drop_body_current"
				: "npc_studio.server.worlds.drop_body", world.name(), bytes(world.size())),
			Component.translatable("npc_studio.server.worlds.drop_yes"),
			Component.translatable("npc_studio.server.no_session.no"),
			() -> {
				trouble = "";
				say(Component.translatable("npc_studio.server.worlds.dropping").getString());
				manager.dropWorld(server, world, this::say, () -> {
					say(Component.translatable("npc_studio.server.worlds.dropped",
						world.name()).getString());
					shutting = "world:" + world.name();
					shuttingAt = net.minecraft.util.Util.getMillis();
					shutDone = true;
					afterShut = this::readWorlds;
					rebuild();
				}, failed -> {
					trouble = failed;
					say(Component.translatable("npc_studio.server.worlds.failed").getString());
					rebuild();
				});
			});
	}

	/**
	 * Make a dimension again, which is throwing its chunks away.
	 *
	 * The one thing this tab could not do: a world could be made again and a
	 * dimension inside it could not. It is a different act from making a world —
	 * nobody creates a dimension, the server generates it the first time somebody
	 * walks in — so what is offered is exactly that: delete, and let it happen.
	 */
	private void resetDimension(com.mopicmp.npcstudio.server.Worlds.World world,
			com.mopicmp.npcstudio.server.Worlds.Dimension part) {
		if (manager.running()) {
			trouble = Component.translatable("npc_studio.server.worlds.replace_running")
				.getString();
			rebuild();
			return;
		}
		confirm.ask(
			Component.translatable("npc_studio.server.worlds.reset_title"),
			Component.translatable("npc_studio.server.worlds.reset_body",
				dimensionName(part).getString(), world.name()),
			Component.translatable("npc_studio.server.worlds.reset_yes"),
			Component.translatable("npc_studio.server.no_session.no"),
			() -> {
				trouble = "";
				say(Component.translatable("npc_studio.server.worlds.resetting").getString());
				manager.resetDimension(server, world, part, this::say, () -> {
					say(Component.translatable("npc_studio.server.worlds.reset_done",
						dimensionName(part).getString()).getString());
					readWorlds();
					rebuild();
				}, failed -> {
					trouble = failed;
					say(Component.translatable("npc_studio.server.worlds.failed").getString());
					rebuild();
				});
			});
	}

	/**
	 * Put another dimension in place of one of this world's.
	 *
	 * The folder is chosen first and named in the question, because "replace the
	 * nether" and "replace the nether with <i>this</i>" are different questions,
	 * and only the second one can be answered. Everything that makes it dangerous
	 * is in the question rather than in a message afterwards.
	 */
	private void replaceDimension(com.mopicmp.npcstudio.server.Worlds.World world,
			com.mopicmp.npcstudio.server.Worlds.Dimension part) {
		if (manager.running()) {
			trouble = Component.translatable("npc_studio.server.worlds.replace_running")
				.getString();
			rebuild();
			return;
		}
		java.nio.file.Path source = FolderPick.choose(
			Component.translatable("npc_studio.server.worlds.replace_pick").getString());
		if (source == null) return;

		confirm.ask(
			Component.translatable("npc_studio.server.worlds.replace_title"),
			Component.translatable("npc_studio.server.worlds.replace_body",
				dimensionName(part).getString(), world.name(), source.getFileName().toString()),
			Component.translatable("npc_studio.server.worlds.replace_yes"),
			Component.translatable("npc_studio.server.no_session.no"),
			() -> {
				trouble = "";
				say(Component.translatable("npc_studio.server.worlds.replacing").getString());
				manager.replaceDimension(server, world, part, source, this::say, () -> {
					say(Component.translatable("npc_studio.server.worlds.replaced",
						dimensionName(part).getString()).getString());
					readWorlds();
					rebuild();
				}, failed -> {
					trouble = failed;
					say(Component.translatable("npc_studio.server.worlds.failed").getString());
					rebuild();
				});
			});
	}

	private void drawBackupsHalf(GuiGraphicsExtractor graphics, int top) {
		int x = contentLeft + PAD;
		int right = width - PAD;
		int y = top + 24;
		graphics.enableScissor(contentLeft, y - 2, width, height - PAD);
		y -= worldScroll;
		for (var backup : backups) {
			graphics.fill(x, y, right, y + BACKUP_ROW - 4, CARD);
			graphics.fill(x, y, right, y + 1, EDGE);
			graphics.fill(x, y + BACKUP_ROW - 5, right, y + BACKUP_ROW - 4, EDGE);
			graphics.fill(x, y, x + 1, y + BACKUP_ROW - 4, EDGE);
			graphics.fill(right - 1, y, right, y + BACKUP_ROW - 4, EDGE);

			// The picture out of the archive itself, not out of the world it came
			// from: the copies worth keeping are the ones whose world has moved on.
			var look = backupLooks.get(backup.file());
			int face = BACKUP_ROW - 12;
			int faceY = y + 5;
			Identifier picture = look == null ? null
				: BackupLook.picture(backup.file(), look.icon());
			if (picture != null) {
				graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, picture,
					x + 6, faceY, 0, 0, face, face, 64, 64, 64, 64);
			} else {
				graphics.fill(x + 6, faceY, x + 6 + face, faceY + face, 0xFF0C0F13);
				Icon.ENVIRONMENT.draw(graphics, x + 6 + (face - Icon.SIZE) / 2,
					faceY + (face - Icon.SIZE) / 2, TEXT_DIM);
			}

			int text = x + 6 + face + 6;
			graphics.text(font, backup.title(), text, y + 5, TEXT);
			// The world under the name, because a name of one's own replaces it up
			// there and the world is the one fact a restore turns on.
			String said = backup.label().isBlank() ? "" : backup.world() + " · ";
			said += when(backup.made()) + " · " + bytes(backup.size());
			if (look != null && !look.version().isBlank()) said += " · " + look.version();
			graphics.text(font, said, text, y + 17, TEXT_DIM);
			y += BACKUP_ROW;
		}
		graphics.disableScissor();
		scrollbar(graphics, worldListTop, height - PAD, worldScroll, worldHeight);

		if (backups.isEmpty()) {
			graphics.textWithWordWrap(font,
				Component.translatable("npc_studio.server.worlds.no_copies"),
				x + PAD, y + 4, Math.min(520, right - x - PAD * 2), TEXT_DIM);
		}
	}

	// ------------------------------------------------------------------ places

	/**
	 * The dimensions we made, which live in a datapack in the world.
	 *
	 * Their own half of the tab rather than a section under the worlds, because
	 * they are not worlds: they are parts of one, they are ours, and everything
	 * about them — who may go, what they are made of, the two commands people
	 * type — is a list of settings rather than a folder on disk.
	 */
	private List<com.mopicmp.npcstudio.server.Dimensions.Place> places = List.of();
	private boolean placesRead;

	private void readPlaces() {
		ManagedServer which = server;
		manager.places(which, manager.currentWorld(), found -> {
			if (server != which) return;
			places = found;
			placesRead = true;
			rebuild();
		});
	}

	private void placesHalf(int top) {
		int x = contentLeft + PAD;
		int right = width - PAD;
		if (!placesRead) {
			readPlaces();
			return;
		}

		addRenderableWidget(new IconTextButton(x, top, 150, 18, Icon.ADD,
			Component.translatable("npc_studio.server.places.add"), GOOD, () -> {
				editing = -1;
				placeId = suggestPlaceId();
				placeName = "";
				placeWho = "";
				window = Window.PLACE;
				rebuild();
			}));

		int y = top + 24 + font.wordWrapHeight(
			Component.translatable("npc_studio.server.places.says"),
			Math.min(560, right - x - PAD)) + 8;
		worldListTop = y;
		int bottom = height - PAD;
		y -= worldScroll;
		Component change = Component.translatable("npc_studio.server.places.change");
		Component drop = Component.translatable("npc_studio.server.content.remove");
		Component afresh = Component.translatable("npc_studio.server.worlds.reset");
		for (int at = 0; at < places.size(); at++) {
			var place = places.get(at);
			int index = at;
			if (y >= worldListTop - 2 && y + PLACE_ROW - 6 <= bottom) {
				int put = right - 6 - Pill.wide(drop);
				addRenderableWidget(new Pill(put, y + 8, Icon.REMOVE, drop, WARN,
					() -> dropPlace(index)));
				put -= Pill.wide(change) + 4;
				addRenderableWidget(new Pill(put, y + 8, Icon.SETTINGS, change, ACCENT,
					() -> editPlace(index)));
				put -= Pill.wide(afresh) + 4;
				Pill again = new Pill(put, y + 8, Icon.RESET, afresh, WARN,
					() -> askToRemake(place.id()));
				again.active = !manager.running();
				addRenderableWidget(again);
			}
			y += PLACE_ROW;
		}
		worldHeight = Math.max(0, y + worldScroll + PAD - bottom);
	}

	/** How tall one made dimension's card is: its name, what it is, and its command. */
	private static final int PLACE_ROW = 52;

	private void drawPlacesHalf(GuiGraphicsExtractor graphics, int top) {
		int x = contentLeft + PAD;
		int right = width - PAD;
		int room = Math.min(560, right - x - PAD);
		if (!placesRead) {
			graphics.text(font, Component.translatable("npc_studio.server.worlds.reading"),
				x + PAD, top + 28, TEXT_DIM);
			return;
		}
		graphics.textWithWordWrap(font, Component.translatable("npc_studio.server.places.says"),
			x, top + 24, room, TEXT_DIM);

		int y = worldListTop - worldScroll;
		int bottom = height - PAD;
		graphics.enableScissor(contentLeft, worldListTop - 2, width, bottom);
		for (var place : places) {
			graphics.fill(x, y, right, y + PLACE_ROW - 6, CARD);
			graphics.fill(x, y, right, y + 1, ACCENT);
			graphics.fill(x, y + PLACE_ROW - 7, right, y + PLACE_ROW - 6, EDGE);
			graphics.fill(x, y, x + 1, y + PLACE_ROW - 6, EDGE);
			graphics.fill(right - 1, y, right, y + PLACE_ROW - 6, EDGE);

			String name = com.mopicmp.npcstudio.server.Dimensions.nameOf(place);
			graphics.text(font, name, x + 8, y + 6, TEXT);
			graphics.text(font, place.full(), x + 12 + font.width(name), y + 6, TEXT_DIM);
			graphics.text(font, Component.translatable(
				place.sharing() == com.mopicmp.npcstudio.server.Dimensions.Sharing.SHARED
					? "npc_studio.server.places.shared"
					: "npc_studio.server.places.plots").getString()
				+ " · " + (place.who().isEmpty()
					? Component.translatable("npc_studio.server.places.anyone").getString()
					: count(place.who().size(), "npc_studio.server.places.people")),
				x + 8, y + 18, TEXT_DIM);
			// The two commands, which are the whole of what a builder needs to know.
			graphics.text(font,
				com.mopicmp.npcstudio.server.Dimensions.command(places, place) + "   "
					+ com.mopicmp.npcstudio.server.Dimensions.backCommand(),
				x + 8, y + 31, ACCENT);
			y += PLACE_ROW;
		}
		graphics.disableScissor();
		scrollbar(graphics, worldListTop, bottom, worldScroll, worldHeight);

		if (places.isEmpty()) {
			graphics.textWithWordWrap(font,
				Component.translatable("npc_studio.server.places.none"), x, y + 4, room, TEXT_DIM);
		}
	}

	/** Which one is being changed, or minus one for one that does not exist yet. */
	private int editing = -1;
	private String placeId = "builder";
	private String placeName = "";
	private String placeWho = "";
	private String placeTemplate = "the_void";
	private String placePoint = "0 100 0";
	private com.mopicmp.npcstudio.server.Dimensions.Sharing placeSharing =
		com.mopicmp.npcstudio.server.Dimensions.Sharing.SHARED;
	private com.mopicmp.npcstudio.server.Dimensions.Return placeBack =
		com.mopicmp.npcstudio.server.Dimensions.Return.EXIT;

	private String suggestPlaceId() {
		for (int at = 0; at < 100; at++) {
			String tried = at == 0 ? "builder" : "builder" + (at + 1);
			boolean taken = false;
			for (var place : places) {
				if (place.id().equals(tried)) taken = true;
			}
			if (!taken) return tried;
		}
		return "builder";
	}

	private void editPlace(int at) {
		var place = places.get(at);
		editing = at;
		placeId = place.id();
		placeName = place.name();
		placeWho = String.join(", ", place.who());
		placeTemplate = place.template().isBlank() ? "the_void" : place.template();
		placePoint = place.point()[0] + " " + place.point()[1] + " " + place.point()[2];
		placeSharing = place.sharing();
		placeBack = place.back();
		window = Window.PLACE;
		rebuild();
	}

	private void dropPlace(int at) {
		List<com.mopicmp.npcstudio.server.Dimensions.Place> left = new ArrayList<>(places);
		var gone = left.remove(at);
		confirm.ask(
			Component.translatable("npc_studio.server.places.drop_title"),
			Component.translatable("npc_studio.server.places.drop_body",
				com.mopicmp.npcstudio.server.Dimensions.nameOf(gone), gone.full()),
			Component.translatable("npc_studio.server.places.drop_yes"),
			Component.translatable("npc_studio.server.no_session.no"),
			() -> savePlaces(left, "npc_studio.server.places.dropped"));
	}

	private void savePlaces(List<com.mopicmp.npcstudio.server.Dimensions.Place> wanted,
			String said) {
		trouble = "";
		manager.writePlaces(server, manager.currentWorld(), wanted, () -> {
			places = wanted;
			say(Component.translatable(said).getString());
			// Written, and not yet real. Dimensions are read when the world loads,
			// so this is the one thing here that a restart is not a formality for.
			confirm.tell(
				Component.translatable("npc_studio.server.places.restart_title"),
				Component.translatable("npc_studio.server.places.restart_body"),
				Component.translatable("npc_studio.server.worlds.save_off.ok"));
			readPlaces();
			rebuild();
		}, failed -> {
			trouble = failed;
			say(Component.translatable("npc_studio.server.worlds.failed").getString());
			rebuild();
		});
	}

	/** The names in the field, as names; empty when any of them is not one. */
	private List<String> whoTyped() {
		List<String> names = new ArrayList<>();
		for (String each : placeWho.split("[,\\s]+")) {
			String name = each.trim();
			if (name.isEmpty()) continue;
			if (!com.mopicmp.npcstudio.server.Dimensions.validPlayer(name)) return List.of();
			names.add(name);
		}
		return names;
	}

	private int[] pointTyped() {
		String[] parts = placePoint.trim().split("[,\\s]+");
		if (parts.length != 3) return null;
		int[] at = new int[3];
		try {
			for (int n = 0; n < 3; n++) at[n] = Integer.parseInt(parts[n]);
		} catch (NumberFormatException notANumber) {
			return null;
		}
		return at;
	}

	private boolean readyToPlace() {
		if (!com.mopicmp.npcstudio.server.Dimensions.validId(placeId.trim())) return false;
		if (!placeWho.isBlank() && whoTyped().isEmpty()) return false;
		if (placeBack == com.mopicmp.npcstudio.server.Dimensions.Return.POINT
			&& pointTyped() == null) {
			return false;
		}
		// Two of them with one id would be one dimension with two descriptions,
		// and the file written second would be the only one that counted.
		for (int at = 0; at < places.size(); at++) {
			if (at != editing && places.get(at).id().equals(placeId.trim())) return false;
		}
		return true;
	}

	private void keepPlace() {
		int[] point = pointTyped();
		var made = new com.mopicmp.npcstudio.server.Dimensions.Place(
			placeId.trim(), placeName.trim(), placeTemplate, "", placeSharing, placeBack,
			whoTyped(), point == null ? new int[] {0, 100, 0} : point);
		List<com.mopicmp.npcstudio.server.Dimensions.Place> wanted = new ArrayList<>(places);
		boolean remade = editing >= 0 && !places.get(editing).template().equals(placeTemplate);
		if (editing >= 0) wanted.set(editing, made);
		else wanted.add(made);
		window = Window.NONE;
		savePlaces(wanted, "npc_studio.server.places.kept");
		// Changing what it is made of changes the description and not the ground.
		// Asked rather than done, because the ground may be somebody's work.
		if (remade) askToRemake(made.id());
	}

	/**
	 * Offer to throw the old ground away, having just changed what makes it.
	 *
	 * Two separate things, and the window says which is which: the description is
	 * written already, and the chunks that were generated from the old one are
	 * still there until somebody agrees to lose them.
	 */
	private void askToRemake(String id) {
		confirm.ask(
			Component.translatable("npc_studio.server.places.remake_title"),
			Component.translatable("npc_studio.server.places.remake_body"),
			Component.translatable("npc_studio.server.places.remake_yes"),
			Component.translatable("npc_studio.server.no_session.no"),
			() -> remakePlace(id));
	}

	private void remakePlace(String id) {
		trouble = "";
		say(Component.translatable("npc_studio.server.places.remaking").getString());
		manager.regeneratePlace(server, manager.currentWorld(), id, this::say, () -> {
			say(Component.translatable("npc_studio.server.places.remade").getString());
			readPlaces();
			rebuild();
		}, failed -> {
			trouble = failed;
			say(Component.translatable("npc_studio.server.worlds.failed").getString());
			rebuild();
		});
	}

	/**
	 * The window for a dimension of somebody's own.
	 *
	 * Same shape as the one for a new world, and the same rule: one method places
	 * and draws it, row by row, so that the two cannot come apart.
	 */
	private void placeWindow(GuiGraphicsExtractor graphics) {
		boolean draw = graphics != null;
		int col = WINDOW - PAGE_PAD * 2;
		int half = (col - 8) / 2;
		boolean point = placeBack
			== com.mopicmp.npcstudio.server.Dimensions.Return.POINT;

		Component says = Component.translatable("npc_studio.server.places.window_says");
		int tall = PAGE_PAD + 12 + PAGE_PAD
			+ (LABEL + FIELD + 8) * 4
			+ (point ? FIELD + 8 : 0)
			+ font.wordWrapHeight(says, col) + 12 + 18 + PAGE_PAD;

		int x = (width - WINDOW) / 2;
		int top = Math.max(TOP, (height - tall) / 2);
		int inner = x + PAGE_PAD;
		windowX = x;
		windowY = top;
		windowTall = tall;

		if (draw) {
			graphics.fill(0, 0, width, height, 0xB0000000);
			graphics.fill(x - 1, top - 1, x + WINDOW + 1, top + tall + 1, EDGE);
			graphics.fill(x, top, x + WINDOW, top + tall, 0xFF1B2028);
			graphics.fill(x, top, x + WINDOW, top + 1, ACCENT);
			graphics.text(font, Component.translatable("npc_studio.server.places.add"),
				inner, top + PAGE_PAD, TEXT);
		}

		int y = top + PAGE_PAD + 12 + PAGE_PAD;

		if (draw) {
			label(graphics, "npc_studio.server.places.name", inner, y);
			label(graphics, "npc_studio.server.places.id", inner + half + 8, y);
		} else {
			box(inner, y + LABEL, half, "npc_studio.server.places.name", placeName, 40,
				typed -> placeName = typed);
			box(inner + half + 8, y + LABEL, half, "npc_studio.server.places.id", placeId, 32,
				typed -> placeId = typed);
		}
		y += LABEL + FIELD + 8;

		int madeY = y + LABEL;
		if (draw) {
			label(graphics, "npc_studio.server.places.made_of", inner, y);
		} else {
			put(new FlatButton(inner, madeY, half, FIELD, Component.literal(
				Component.translatable("npc_studio.server.worlds.template." + placeTemplate)
					.getString() + "  ▾"), ACCENT, () -> {
					List<Dropdown.Option> options = new ArrayList<>();
					for (var each : com.mopicmp.npcstudio.server.Flat.PRESETS) {
						options.add(new Dropdown.Option(each.id(), Component.translatable(
							"npc_studio.server.worlds.template." + each.id())));
					}
					dropdown.open(inner, madeY + FIELD, half, height, options, placeTemplate,
						picked -> {
							placeTemplate = picked;
							rebuild();
						});
				}));
			int sharingX = inner + half + 8;
			put(new FlatButton(sharingX, madeY, half, FIELD, Component.literal(
				Component.translatable(placeSharing
					== com.mopicmp.npcstudio.server.Dimensions.Sharing.SHARED
						? "npc_studio.server.places.shared"
						: "npc_studio.server.places.plots").getString() + "  ▾"), ACCENT, () -> {
					List<Dropdown.Option> options = List.of(
						new Dropdown.Option("SHARED",
							Component.translatable("npc_studio.server.places.shared")),
						new Dropdown.Option("PLOTS",
							Component.translatable("npc_studio.server.places.plots")));
					dropdown.open(sharingX, madeY + FIELD, half, height, options,
						placeSharing.name(), picked -> {
							placeSharing = com.mopicmp.npcstudio.server.Dimensions.Sharing
								.valueOf(picked);
							rebuild();
						});
				}));
		}
		if (draw) label(graphics, "npc_studio.server.places.sharing", inner + half + 8, y);
		y += LABEL + FIELD + 8;

		Component me = Component.translatable("npc_studio.server.places.me");
		if (draw) {
			label(graphics, "npc_studio.server.places.who", inner, y);
		} else {
			box(inner, y + LABEL, col - Pill.wide(me) - 6,
				"npc_studio.server.places.who_hint", placeWho, 400, typed -> placeWho = typed);
			// Because the first name anybody needs on that list is their own, and
			// typing it wrong is a command that answers "you cannot trigger this
			// yet" with no hint as to why.
			put(new Pill(inner + col - Pill.wide(me), y + LABEL, Icon.PLAYER_ON, me, ACCENT,
				() -> {
					String mine = myName();
					placeWho = placeWho.isBlank() ? mine
						: placeWho.trim().endsWith(",") ? placeWho + " " + mine
							: placeWho + ", " + mine;
					rebuild();
				}));
		}
		y += LABEL + FIELD + 8;

		int backY = y + LABEL;
		if (draw) {
			label(graphics, "npc_studio.server.places.back", inner, y);
		} else {
			put(new FlatButton(inner, backY, col, FIELD, Component.literal(
				Component.translatable(point
					? "npc_studio.server.places.back_point"
					: "npc_studio.server.places.back_exit").getString() + "  ▾"), ACCENT, () -> {
					List<Dropdown.Option> options = List.of(
						new Dropdown.Option("EXIT",
							Component.translatable("npc_studio.server.places.back_exit")),
						new Dropdown.Option("POINT",
							Component.translatable("npc_studio.server.places.back_point")));
					dropdown.open(inner, backY + FIELD, col, height, options, placeBack.name(),
						picked -> {
							placeBack = com.mopicmp.npcstudio.server.Dimensions.Return
								.valueOf(picked);
							rebuild();
						});
				}));
		}
		y += LABEL + FIELD + 8;

		if (point) {
			if (!draw) {
				box(inner, y, col, "npc_studio.server.places.point_hint", placePoint, 40,
					typed -> placePoint = typed);
			}
			y += FIELD + 8;
		}

		if (draw) {
			graphics.textWithWordWrap(font, says, inner, y, col, TEXT_DIM);
			return;
		}

		Component go = Component.translatable("npc_studio.server.places.keep");
		int buttonsY = top + tall - PAGE_PAD - 18;
		Pill keep = new Pill(inner, buttonsY, Icon.CHECK, go, GOOD, this::keepPlace);
		keep.active = readyToPlace();
		put(keep);
		put(new Pill(inner + Pill.wide(go) + 6, buttonsY, Icon.CLOSED,
			Component.translatable("npc_studio.server.key.cancel"), TEXT_DIM, () -> {
				window = Window.NONE;
				rebuild();
			}));
	}

	// ------------------------------------------------------------------ datapacks

	/**
	 * The datapacks of one world, switched on and off from here.
	 *
	 * A window rather than a tab because they belong to a world rather than to the
	 * server: two worlds on one server have two different sets, and a list that did
	 * not say which world it was about would be a list nobody could trust.
	 *
	 * Which are on is not in the folder. It is in {@code level.dat}, and while the
	 * server is running that file is the server's — so the switch goes over the
	 * command channel when it is up and into the file when it is down, and the
	 * window says which of the two just happened.
	 */
	private String packsOf = "";
	private List<DataPacks.Pack> worldPacks = List.of();
	private boolean packsRead;

	/** The packs of the world the server is set to play, from wherever we are. */
	private void openPacksOfCurrent() {
		packsOf = manager.currentWorld();
		packsRead = false;
		tab = Tab.WORLDS;
		window = Window.PACKS;
		readPacks();
		rebuild();
	}

	private void openPacks(com.mopicmp.npcstudio.server.Worlds.World world) {
		packsOf = world.name();
		packsRead = false;
		window = Window.PACKS;
		readPacks();
		rebuild();
	}

	private void readPacks() {
		ManagedServer which = server;
		String world = packsOf;
		manager.dataPacks(which, world, found -> {
			if (server != which || !packsOf.equals(world)) return;
			worldPacks = found;
			packsRead = true;
			rebuild();
		});
	}

	private void packWindow(GuiGraphicsExtractor graphics) {
		boolean draw = graphics != null;
		int col = PACK_WINDOW - PAGE_PAD * 2;

		Component says = Component.translatable(manager.running()
			? "npc_studio.server.packs.says_running"
			: "npc_studio.server.packs.says");
		int rows = Math.max(1, worldPacks.size());
		int tall = PAGE_PAD + 12 + PAGE_PAD + rows * PACK_ROW + 8
			+ font.wordWrapHeight(says, col) + 12 + 18 + PAGE_PAD;

		int x = (width - PACK_WINDOW) / 2;
		int top = Math.max(TOP, (height - tall) / 2);
		int inner = x + PAGE_PAD;
		windowX = x;
		windowY = top;
		windowTall = tall;

		if (draw) {
			graphics.fill(0, 0, width, height, 0xB0000000);
			graphics.fill(x - 1, top - 1, x + PACK_WINDOW + 1, top + tall + 1, EDGE);
			graphics.fill(x, top, x + PACK_WINDOW, top + tall, 0xFF1B2028);
			graphics.fill(x, top, x + PACK_WINDOW, top + 1, ACCENT);
			graphics.text(font, Component.translatable("npc_studio.server.packs.of", packsOf),
				inner, top + PAGE_PAD, TEXT);
		}

		int y = top + PAGE_PAD + 12 + PAGE_PAD;
		if (!packsRead) {
			if (draw) {
				graphics.text(font, Component.translatable("npc_studio.server.worlds.reading"),
					inner, y, TEXT_DIM);
			}
			return;
		}
		if (worldPacks.isEmpty() && draw) {
			graphics.text(font, Component.translatable("npc_studio.server.packs.none"),
				inner, y + 4, TEXT_DIM);
		}

		Component off = Component.translatable("npc_studio.server.packs.off");
		Component on = Component.translatable("npc_studio.server.packs.on");
		Component remove = Component.translatable("npc_studio.server.content.remove");
		for (var pack : worldPacks) {
			if (draw) {
				graphics.fill(inner, y, inner + col, y + PACK_ROW - 4, 0xFF10141A);
				graphics.fill(inner, y, inner + col, y + 1, pack.on() ? GOOD : EDGE);
				graphics.text(font, shorten(pack.name(), col - 150), inner + 8, y + 5,
					pack.on() ? TEXT : TEXT_DIM);
				graphics.text(font, bytes(pack.size()) + (pack.mine()
					? " · " + Component.translatable("npc_studio.server.packs.ours").getString()
					: ""), inner + 8, y + 16, TEXT_DIM);
			} else {
				int put = inner + col - 6 - Pill.wide(remove);
				Pill kill = new Pill(put, y + 5, Icon.REMOVE, remove, WARN,
					() -> dropPack(pack));
				kill.active = !manager.running();
				put(kill);
				Component label = pack.on() ? off : on;
				put -= Pill.wide(label) + 4;
				put(new Pill(put, y + 5, pack.on() ? Icon.CANCEL : Icon.CHECK, label,
					pack.on() ? WARN : GOOD, () -> setPack(pack, !pack.on())));
			}
			y += PACK_ROW;
		}
		y += 8;

		if (draw) {
			graphics.textWithWordWrap(font, says, inner, y, col, TEXT_DIM);
			return;
		}

		int buttonsY = top + tall - PAGE_PAD - 18;
		Component add = Component.translatable("npc_studio.server.packs.add");
		put(new Pill(inner, buttonsY, Icon.ADD, add, GOOD, this::addPack));
		Component open = Component.translatable("npc_studio.server.folder");
		put(new Pill(inner + Pill.wide(add) + 6, buttonsY, Icon.FOLDER, open, TEXT_DIM,
			() -> net.minecraft.util.Util.getPlatform().openPath(
				DataPacks.folder(server, packsOf))));
		Component close = Component.translatable("npc_studio.server.key.cancel");
		put(new Pill(inner + col - Pill.wide(close), buttonsY, Icon.CLOSED, close, TEXT_DIM,
			() -> {
				window = Window.NONE;
				rebuild();
			}));
	}

	private static final int PACK_WINDOW = 420;
	private static final int PACK_ROW = 30;

	private void setPack(DataPacks.Pack pack, boolean on) {
		manager.setDataPack(server, packsOf, pack.name(), on, () -> {
			say(Component.translatable(manager.running()
				? "npc_studio.server.packs.said"
				: "npc_studio.server.packs.written", pack.name()).getString());
			readPacks();
			rebuild();
		}, said -> {
			trouble = said;
			say(said);
			rebuild();
		});
	}

	private void dropPack(DataPacks.Pack pack) {
		confirm.ask(
			Component.translatable("npc_studio.server.packs.drop_title"),
			Component.translatable("npc_studio.server.packs.drop_body", pack.name(), packsOf),
			Component.translatable("npc_studio.server.packs.drop_yes"),
			Component.translatable("npc_studio.server.no_session.no"),
			() -> manager.dropDataPack(server, pack, () -> {
				say(Component.translatable("npc_studio.server.packs.dropped",
					pack.name()).getString());
				readPacks();
				rebuild();
			}, said -> {
				trouble = said;
				say(said);
				rebuild();
			}));
	}

	private void addPack() {
		List<java.nio.file.Path> chosen = AddonPick.choose();
		java.nio.file.Path from = chosen.isEmpty()
			? FolderPick.choose(
				Component.translatable("npc_studio.server.packs.add").getString())
			: chosen.getFirst();
		if (from == null) return;
		manager.addDataPack(server, packsOf, from, () -> {
			say(Component.translatable("npc_studio.server.packs.added",
				from.getFileName().toString()).getString());
			readPacks();
			rebuild();
		}, said -> {
			trouble = said;
			say(said);
			rebuild();
		});
	}

	/** A moment as somebody says it, in this machine's own time zone. */
	private static String when(java.time.Instant instant) {
		return java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
			.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm",
				java.util.Locale.ROOT));
	}

	/**
	 * A sub-tab, of which there are now two rows in two tabs.
	 *
	 * One widget rather than one per row: they are the same control with a
	 * different list behind them, and a second copy of the drawing is a second
	 * place for the two rows to stop looking alike.
	 */
	private final class SubTab extends AbstractWidget {

		private final boolean here;
		private final Runnable onPress;

		SubTab(int x, int y, int width, int height, boolean here, Component label,
				Runnable onPress) {
			super(x, y, width, height, label);
			this.here = here;
			this.onPress = onPress;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor graphics,
				int mouseX, int mouseY, float delta) {
			boolean lit = isHoveredOrFocused();
			graphics.fill(getX(), getY(), getX() + width, getY() + height,
				here ? PANEL : lit ? 0xFF171C23 : BAR);
			graphics.fill(getX() + width - 1, getY() + 3, getX() + width,
				getY() + height - 3, EDGE);
			if (here) {
				graphics.fill(getX(), getY() + height - 2, getX() + width, getY() + height, ACCENT);
			}
			var font = net.minecraft.client.Minecraft.getInstance().font;
			graphics.text(font, getMessage(),
				getX() + (width - font.width(getMessage())) / 2, getY() + (height - 8) / 2,
				here ? TEXT : lit ? ACCENT : TEXT_DIM);
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			onPress.run();
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			if (!pressing(event)) return super.keyPressed(event);
			onPress.run();
			return true;
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}

	// ------------------------------------------------------------------ settings

	/**
	 * Read the file, and be able to read it again.
	 *
	 * This used to happen once, from {@code init}, and only when the screen had
	 * just been pointed at a different server. So a read that failed — and one
	 * can, easily: the file is rewritten every time the server starts, and a read
	 * caught in the middle of that throws — left the tab saying "reading the
	 * settings file" for as long as the window stayed open. Leaving the screen and
	 * coming back was the only cure, because that is what made it ask again.
	 *
	 * Now the tab asks when it has nothing, exactly like the worlds and the
	 * addons, and the flag below is what keeps that from being twenty questions a
	 * second while the answer is on its way.
	 */
	private boolean readingProperties;

	private void readProperties() {
		if (readingProperties) return;
		readingProperties = true;
		ManagedServer which = server;
		Map<String, String> read = new LinkedHashMap<>();
		manager.onFiles(() -> {
			try {
				boolean there = java.nio.file.Files.exists(which.propertiesPath());
				PropertiesFile file = there
					? PropertiesFile.read(which.propertiesPath())
					: PropertiesFile.empty();
				read.put("motd", there ? file.getOr("motd", which.name) : which.name);
				for (ServerSettings setting : ServerSettings.ALL) {
					read.put(setting.key(), there
						? file.getOr(setting.key(), setting.fallback())
						: setting.fallback());
				}
				// And whatever else is in the file. server.properties is the same
				// file on vanilla, Fabric and Paper — which is why the list looks
				// the same on all three — but a core is free to add a line of its
				// own, and one this window cannot show is one it hides.
				found.clear();
				if (there) {
					for (var entry : file.all().entrySet()) {
						if (KNOWN.contains(entry.getKey())) continue;
						if (PRIVATE.contains(entry.getKey())) continue;
						found.put(entry.getKey(), entry.getValue());
					}
				}
			} catch (java.io.IOException unreadable) {
				throw new RuntimeException(unreadable.getMessage(), unreadable);
			}
		}, () -> {
			readingProperties = false;
			if (server != which) return;
			values.clear();
			values.putAll(read);
			values.putAll(found);
			extra = ServerSettings.own(new java.util.ArrayList<>(found.keySet()));
			propertiesRead = true;
			rebuild();
		}, said -> {
			readingProperties = false;
			say(said);
		});
	}

	/** Lines of the file this window keeps to itself, or shows somewhere else. */
	private static final java.util.Set<String> PRIVATE = java.util.Set.of(
		"rcon.password", "rcon.port", "enable-rcon", "motd", "server-port",
		"level-name", "level-seed", "level-type", "generator-settings", "server-ip");

	private static final java.util.Set<String> KNOWN = ServerSettings.ALL.stream()
		.map(ServerSettings::key).collect(java.util.stream.Collectors.toSet());

	/** Keys found in this server's file that are nobody's idea of standard. */
	private final java.util.Map<String, String> found = new java.util.LinkedHashMap<>();
	private List<ServerSettings> extra = List.of();

	/** The groups, plus one for whatever this core put in the file itself. */
	private List<String> groupsNow() {
		List<String> groups = new ArrayList<>(ServerSettings.groups());
		if (!extra.isEmpty()) groups.add(ServerSettings.EXTRA);
		return groups;
	}

	private List<ServerSettings> settingsNow(String group) {
		return ServerSettings.EXTRA.equals(group) ? extra : ServerSettings.of(group);
	}

	/**
	 * The narrowest a card may be, not the width it will have.
	 *
	 * The number of columns comes from this, and then the columns share the whole
	 * width between them. Fixed-width cards leave a strip of nothing down the
	 * right of a wide window — which is the empty space this replaced.
	 */
	private static final int CARD_MIN = 210;

	/**
	 * The strip above the cards: what all of this is, and the way into it.
	 *
	 * The rows below cover the settings this window knows the shape of. The file
	 * has more than that in it — lines a core added, comments somebody left for
	 * themselves, the order they chose — and until now every one of those ended in
	 * "leave the game and open a text editor". It is the same editor the configs
	 * tab uses, on the one file that has a tab of its own.
	 */
	private static final int SETTINGS_HEAD = 24;

	private static final int CARD_GAP = 10;

	/** Beyond this the fields stop being fields and become lines on a page. */
	private static final int CARD_COLUMNS = 4;

	/**
	 * Lay the groups out in as many columns as there is room for.
	 *
	 * One column down the left of a wide window is a list with a desert beside
	 * it. Groups go into whichever column is shortest, which fills the space
	 * without anybody having to decide where each one belongs — and on a narrow
	 * window it comes out as one column again, unchanged.
	 */
	private void place() {
		placed.clear();
		cards.clear();
		int areaLeft = contentLeft + PAD;
		int areaRight = width - PAD;
		int top = contentTop + 6 + SETTINGS_HEAD;
		int bottom = height - PAD;

		int area = areaRight - areaLeft;
		int columns = Math.clamp((area + CARD_GAP) / (CARD_MIN + CARD_GAP), 1, CARD_COLUMNS);
		int across = (area - (columns - 1) * CARD_GAP) / columns;
		int[] columnY = new int[columns];
		java.util.Arrays.fill(columnY, 0);

		int rowHeight = LABEL + FIELD + GROUP;
		for (String group : groupsNow()) {
			int shortest = 0;
			for (int at = 1; at < columns; at++) {
				if (columnY[at] < columnY[shortest]) shortest = at;
			}
			List<ServerSettings> settings = settingsNow(group);
			int cardHeight = HEADER + settings.size() * rowHeight + 4;
			int x = areaLeft + shortest * (across + CARD_GAP);
			int y = top + columnY[shortest] - settingsScroll;

			cards.add(new Card(x, y, across, cardHeight));
			int rowY = y + HEADER;
			placed.add(new Placed(group, null, x, y, across));
			for (ServerSettings setting : settings) {
				placed.add(new Placed(group, setting, x, rowY, across));
				rowY += rowHeight;
			}
			columnY[shortest] += cardHeight + CARD_GAP;
		}

		int tallest = 0;
		for (int each : columnY) tallest = Math.max(tallest, each);
		settingsHeight = Math.max(0, tallest - (bottom - top));
	}

	/**
	 * Open server.properties itself, in the editor the configs tab uses.
	 *
	 * Named here rather than found in a list, because this file is the one the
	 * configs tab does not show: it has a whole tab to itself, and that tab shows
	 * the settings it recognises. This is the rest of the file.
	 */
	private void editProperties() {
		if (server == null) return;
		editFile(server.propertiesPath(), "server.properties", Tab.SETTINGS);
	}

	private void settingsWidgets() {
		// ---- the strip along the top, laid out as the configs tab lays out its own.
		// Before the read, not after it: a file this window cannot take apart is
		// exactly the file somebody needs to open by hand, and the button that
		// opens it must not be the one thing missing when that happens.
		int right = width - PAD;
		Component text = Component.translatable("npc_studio.server.configs.edit");
		addRenderableWidget(new Pill(right - Pill.wide(text), contentTop + 4, Icon.FILE, text,
			ACCENT, this::editProperties));
		Component folder = Component.translatable("npc_studio.server.folder");
		addRenderableWidget(new Pill(right - Pill.wide(text) - 6 - Pill.wide(folder),
			contentTop + 4, Icon.FOLDER, folder, TEXT_DIM,
			() -> net.minecraft.util.Util.getPlatform().openPath(server.path())));

		if (!propertiesRead) {
			readProperties();
			return;
		}
		place();
		int top = contentTop + 4 + SETTINGS_HEAD;
		int bottom = height - PAD;

		for (Placed line : placed) {
			if (line.heading()) continue;
			ServerSettings setting = line.setting();
			String value = current(setting);
			int x = line.x() + PAD;
			int across = line.width() - PAD * 2;
			int y = line.y() + LABEL;
			// Widgets outside the view are not made at all: one that cannot be
			// seen but can still be clicked is the worst kind of button. Wholly
			// inside, not merely touching — half a field scrolled up under the tab
			// strip was drawn over the tabs, because a widget cannot be clipped
			// the way the drawing under it can.
			if (y < top || y + FIELD > bottom) continue;

			switch (setting.kind()) {
				case NUMBER -> {
					double number = parse(value, setting);
					addRenderableWidget(new NumberField(x, y, across, FIELD,
						Component.translatable(setting.label()), number,
						setting.min(), setting.max(), 1, 0, "",
						chosen -> {
							edits.put(setting.key(), String.valueOf((long) Math.round(chosen)));
							touched();
						}));
				}
				case TEXT -> {
					EditBox box = Field.make(font, x, y, across, FIELD,
						Component.translatable(setting.label()));
					box.setMaxLength(256);
					box.setValue(value);
					box.setResponder(typed -> {
						edits.put(setting.key(), typed);
						touched();
					});
					addRenderableWidget(box);
					framed.add(new Framed(box, x, y, across, FIELD));
				}
				case CHOICE, SWITCH -> {
					int at = y;
					addRenderableWidget(new FlatButton(x, y, across, FIELD,
						Component.literal(value + "  ▾"), ACCENT, () -> {
							List<Dropdown.Option> options = new ArrayList<>();
							for (String choice : setting.choices()) {
								options.add(Dropdown.Option.of(choice));
							}
							dropdown.open(x, at + FIELD, across, height, options, value, picked -> {
								edits.put(setting.key(), picked);
								touched();
								rebuild();
							});
						}));
				}
			}
		}
	}

	// ----------------------------------------------------------------- players

	/**
	 * Everyone the server knows, and what may be done about them.
	 *
	 * <b>Columns, not a score.</b> The plan says this in as many words and it is
	 * worth repeating where the code is: a number saying how trustworthy somebody
	 * is would be a number nobody can check and nobody can argue with, and it would
	 * get people banned for nothing. What is here instead is what the server's own
	 * files say — how long they played, when they were last on, whether they are an
	 * operator, banned, on the whitelist — and sorting on those is what produces
	 * "the regulars" or "the trouble".
	 */
	private List<com.mopicmp.npcstudio.server.Players.Player> players = List.of();
	private boolean playersRead;

	/**
	 * Whether an answer is on its way, kept apart from whether there is one.
	 *
	 * These were one flag, and every press cleared it — so removing a name, or
	 * banning an address, replaced the whole tab with the words "reading the
	 * lists" until the answer came back. From outside that is the window shutting
	 * and opening again on every single action. What is on the screen is a
	 * fraction of a second out of date, which is a better thing to show than
	 * nothing at all.
	 */
	private boolean playersReading;

	/** Whether this server checks its whitelist, which is not who is on it. */
	private boolean whitelistOn;
	private int playerScroll;
	private int playerHeight;
	private int playerListTop;
	private String playerFind = "";
	private String playerOrder = "seen";
	private Whom playerHalf = Whom.EVERYONE;

	/** Which half of the tab: the people on the server now, or everyone. */
	private enum Whom {
		ONLINE("npc_studio.server.players.half.online"),
		EVERYONE("npc_studio.server.players.half.everyone"),
		/**
		 * The three lists the server keeps by hand.
		 *
		 * Their own half rather than marks on the shelf, because they answer a
		 * different question. The shelf answers "who is this person"; these answer
		 * "who did we decide something about" — and the entries are not always
		 * people at all: an address ban is a ban on a household, a school or a
		 * mobile network, and there is nobody on the shelf to show for it.
		 */
		LISTS("npc_studio.server.players.half.lists");

		final String label;

		Whom(String label) {
			this.label = label;
		}
	}

	/**
	 * The shelf, built the way the wardrobe's is.
	 *
	 * Faces in a grid rather than names in a list, and not for the look of it: the
	 * plan asked for this shape from the start, and the reason is that a server's
	 * people are recognised by their faces long before their names are read. The
	 * measurements are the wardrobe's own — a tile a little taller than it is wide,
	 * the name under it, the chosen one marked at top and bottom — so that somebody
	 * who has used that screen already knows this one.
	 */
	private static final int FACE_TILE = 56;
	private static final int FACE_GAP = 6;
	private static final int HEAD = 34;

	/**
	 * A figure's tile, which is a different shape from a face's.
	 *
	 * The wardrobe's proportions, for the wardrobe's reason: a drawn figure
	 * standing at three-quarters needs its height, and five to four is about what
	 * one occupies. A head is square and wants nothing of the sort, so the two
	 * shapes are kept apart rather than one stretched into the other.
	 */
	private static final int BODY_TILE = 62;
	private static final int BODY_PICTURE = 78;

	/** Under either of them, one line for the name. */
	private static final int TILE_LABEL = 12;

	/**
	 * Faces or figures.
	 *
	 * Both are worth having, and they answer different questions. A face is how a
	 * person is recognised, and forty of them fit on a screen at once; a figure is
	 * what somebody actually looks like on the server, which is the reason anybody
	 * opens a skin at all. So it is a switch, not a decision made here on
	 * everybody's behalf.
	 */
	private boolean playerFigures = true;

	private int tileWide() {
		return playerFigures ? BODY_TILE : FACE_TILE;
	}

	/** The picture's own height, without the name under it. */
	private int tilePicture() {
		return playerFigures ? BODY_PICTURE : FACE_TILE;
	}

	private int tileTall() {
		return tilePicture() + TILE_LABEL;
	}

	/** The panel along the foot: who is chosen, and what may be done to them. */
	private static final int PICKED_TALL = 64;

	/** Half of the faces-or-figures switch, which is two of these wide. */
	private static final int SWITCH_HALF = 22;

	/**
	 * A switch with two sides, one of them lit.
	 *
	 * One widget rather than two buttons: the two sides are one question, the lit
	 * side is the answer, and the frame around both is what says so. Pressing the
	 * lit side does nothing, because it is already the answer — a switch that
	 * turns itself off when pressed twice is a switch nobody trusts.
	 */
	private final class Switch extends AbstractWidget {

		Switch(int x, int y) {
			super(x, y, SWITCH_HALF * 2, FIELD,
				Component.translatable("npc_studio.server.players.as_figures"));
			setTooltip(net.minecraft.client.gui.components.Tooltip.create(
				Component.translatable("npc_studio.server.players.shape")));
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor graphics,
				int mouseX, int mouseY, float delta) {
			int middle = getX() + SWITCH_HALF;
			int bottom = getY() + height;
			graphics.fill(getX(), getY(), getX() + width, bottom, 0xFF1B2028);
			// The lit side, and the frame around the whole thing so that the two
			// halves read as one control.
			int litX = playerFigures ? middle : getX();
			graphics.fill(litX, getY(), litX + SWITCH_HALF, bottom, 0xFF27313E);
			graphics.fill(litX, bottom - 2, litX + SWITCH_HALF, bottom, ACCENT);
			graphics.fill(getX(), getY(), getX() + width, getY() + 1, EDGE);
			graphics.fill(getX(), bottom - 1, getX() + width, bottom, EDGE);
			graphics.fill(getX(), getY(), getX() + 1, bottom, EDGE);
			graphics.fill(getX() + width - 1, getY(), getX() + width, bottom, EDGE);
			graphics.fill(middle, getY() + 3, middle + 1, bottom - 3, EDGE);

			boolean overFaces = isHovered() && mouseX < middle;
			boolean overFigures = isHovered() && mouseX >= middle;
			Icon.CHARACTER.draw(graphics, getX() + (SWITCH_HALF - Icon.SIZE) / 2, getY() + 1,
				!playerFigures ? ACCENT : overFaces ? TEXT : TEXT_DIM);
			Icon.BODY.draw(graphics, middle + (SWITCH_HALF - Icon.SIZE) / 2, getY() + 1,
				playerFigures ? ACCENT : overFigures ? TEXT : TEXT_DIM);
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			set(event.x() >= getX() + SWITCH_HALF);
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			// From the keyboard there are no two halves to aim at, so it flips.
			if (!pressing(event)) return super.keyPressed(event);
			set(!playerFigures);
			return true;
		}

		private void set(boolean figures) {
			if (figures == playerFigures) return;
			playerFigures = figures;
			playerScroll = 0;
			rebuild();
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}

	/** Eight quiet colours for the faces this game has no skin for. */
	private static final int[] FACE_TINTS = {
		0xFF3E5266, 0xFF4A5D4E, 0xFF5C4E63, 0xFF66513E,
		0xFF3E6663, 0xFF63414A, 0xFF4B4E6B, 0xFF5A6350};

	private String playerPicked = "";

	private List<com.mopicmp.npcstudio.server.Players.IpBan> ipBans = List.of();
	private String whiteTyped = "";
	private String banTyped = "";
	private String ipTyped = "";

	/** One entry on the lists half, drawn under a heading that places itself. */
	private record Listed(String name, String said, int y) {
	}

	private final List<Listed> listedRows = new ArrayList<>();

	/** A row on the lists half, and the button beside it. */
	private static final int LIST_ROW = 22;

	/**
	 * Which of the three lists are folded away.
	 *
	 * By name rather than by number, so that the state survives the lists being
	 * reordered or a fourth one being added. Kept on the screen rather than saved:
	 * folding is about what somebody is doing this minute.
	 */
	private final java.util.Set<String> foldedLists = new java.util.HashSet<>();

	private boolean openList(String which) {
		return !foldedLists.contains(which);
	}

	/**
	 * Fields whose Enter key means "the button beside me".
	 *
	 * A field with a name typed into it and a button next to it is a form, and
	 * Enter is what hands do at the end of a form without being told. Without
	 * this, typing a name and pressing Enter did nothing whatever, which reads
	 * exactly like the button being broken.
	 */
	private final java.util.Map<EditBox, Runnable> submits = new java.util.HashMap<>();

	private void readPlayers() {
		if (playersReading) return;
		ManagedServer which = server;
		playersReading = true;
		manager.ipBans(which, found -> {
			if (server == which) ipBans = found;
		});
		manager.whitelistOn(which, on -> {
			if (server == which) whitelistOn = on;
		});
		manager.players(which, manager.currentWorld(), found -> {
			playersReading = false;
			if (server != which) return;
			players = found;
			playersRead = true;
			if (tab == Tab.PLAYERS) rebuild();
		});
	}

	/** The people being shown: this half, filtered, then ordered. */
	private List<com.mopicmp.npcstudio.server.Players.Player> showingPlayers() {
		String looking = playerFind.strip().toLowerCase(java.util.Locale.ROOT);
		List<com.mopicmp.npcstudio.server.Players.Player> kept = new ArrayList<>();
		for (var each : players) {
			if (playerHalf == Whom.ONLINE && !each.online()) continue;
			if (!looking.isBlank()
				&& !each.label().toLowerCase(java.util.Locale.ROOT).contains(looking)) {
				continue;
			}
			kept.add(each);
		}
		java.util.Comparator<com.mopicmp.npcstudio.server.Players.Player> order =
			switch (playerOrder) {
				case "played" -> java.util.Comparator.comparingLong(
					com.mopicmp.npcstudio.server.Players.Player::played).reversed();
				case "name" -> java.util.Comparator.comparing(
					com.mopicmp.npcstudio.server.Players.Player::label,
					String.CASE_INSENSITIVE_ORDER);
				default -> java.util.Comparator.comparingLong(
					com.mopicmp.npcstudio.server.Players.Player::seen).reversed();
			};
		// Whoever is on the server now comes first whatever the order is: they are
		// the ones something might have to be done about in the next minute.
		kept.sort(java.util.Comparator.comparing(
			(com.mopicmp.npcstudio.server.Players.Player each) -> !each.online())
			.thenComparing(order));
		return kept;
	}

	/** Room kept for the line that says what went wrong, when there is one. */
	private int troubleTall() {
		return trouble.isBlank() ? 0 : 14;
	}

	private static final String[] PLAYER_ORDERS = {"seen", "played", "name"};

	private com.mopicmp.npcstudio.server.Players.Player pickedPlayer() {
		for (var each : players) {
			if (each.uuid().equals(playerPicked)) return each;
		}
		return null;
	}

	private int playerColumns(int across) {
		return Math.max(1, (across + FACE_GAP) / (tileWide() + FACE_GAP));
	}

	private void playerWidgets() {
		if (!playersRead) {
			readPlayers();
			return;
		}
		int x = contentLeft + PAD;
		int right = width - PAD;
		int top = contentTop + 4;

		int at = contentLeft;
		for (Whom which : Whom.values()) {
			Component label = Component.translatable(which.label);
			int across = font.width(label) + 24;
			addRenderableWidget(new SubTab(at, top - 4, across, 18, which == playerHalf, label,
				() -> {
					playerHalf = which;
					playerScroll = 0;
					// A complaint belongs to the half it happened on, and it also
					// stops the tab refreshing itself until it is gone.
					trouble = "";
					rebuild();
				}));
			at += across;
		}

		// The order, as a dropdown — one answer out of three, which is what a
		// dropdown is for, and what the rest of this window already does.
		int orderWide = 150;
		int orderAt = right - orderWide;
		addRenderableWidget(new FlatButton(orderAt, top - 4, orderWide, FIELD,
			Component.literal(Component.translatable(
				"npc_studio.server.players.order." + playerOrder).getString() + "  ▾"), ACCENT,
			() -> {
				List<Dropdown.Option> options = new ArrayList<>();
				for (String each : PLAYER_ORDERS) {
					options.add(new Dropdown.Option(each,
						Component.translatable("npc_studio.server.players.order." + each)));
				}
				dropdown.open(orderAt, top - 4 + FIELD, orderWide, height, options, playerOrder,
					picked -> {
						playerOrder = picked;
						playerScroll = 0;
						rebuild();
					});
			}));

		if (playerHalf == Whom.LISTS) {
			// Neither the shape of a tile nor an order over people means anything
			// here: this half is three lists of decisions, in the order the server
			// keeps them.
			listWidgets(top + 22 + troubleTall());
			return;
		}

		// Faces or figures: one switch, not two buttons. Two buttons side by side
		// are two things that happen to be next to each other, and nothing about
		// them says one is on and the other is off; a switch is one control with a
		// side lit, which is what this actually is.
		int shapeWide = SWITCH_HALF * 2;
		int shapeAt = orderAt - 8 - shapeWide;
		addRenderableWidget(new Switch(shapeAt, top - 4));

		int findWide = Math.min(200, shapeAt - at - 12);
		if (findWide > 80) {
			Component looking = Component.translatable("npc_studio.server.players.find");
			EditBox find = Field.make(font, shapeAt - 6 - findWide, top - 4, findWide, FIELD,
				looking);
			find.setHint(looking);
			find.setMaxLength(20);
			find.setValue(playerFind);
			find.setResponder(typed -> {
				playerFind = typed;
				playerScroll = 0;
				rebuild();
			});
			addRenderableWidget(find);
			framed.add(new Framed(find, shapeAt - 6 - findWide, top - 4, findWide, FIELD));
		}

		// ---- the shelf
		int y = top + 22 + troubleTall();
		playerListTop = y;
		int bottom = height - PAD - (pickedPlayer() == null ? 0 : PICKED_TALL + 6);
		int across = right - x;
		int columns = playerColumns(across);
		List<com.mopicmp.npcstudio.server.Players.Player> showing = showingPlayers();
		int rows = (showing.size() + columns - 1) / columns;
		int put = y - playerScroll;
		for (int row = 0; row < rows; row++) {
			for (int column = 0; column < columns; column++) {
				int index = row * columns + column;
				if (index >= showing.size()) break;
				var player = showing.get(index);
				// Whole tiles only. A widget cannot be clipped the way drawing can,
				// so half a tile under the panel below would be half a tile that
				// still takes presses.
				if (put >= playerListTop - 2 && put + tileTall() <= bottom) {
					addRenderableWidget(new Face(x + column * (tileWide() + FACE_GAP), put,
						player));
				}
			}
			put += tileTall() + FACE_GAP;
		}
		playerHeight = Math.max(0, put + playerScroll + PAD - bottom);

		// ---- and the panel about the one chosen
		var picked = pickedPlayer();
		if (picked != null) playerPanel(picked, x, height - PAD - PICKED_TALL, right);
	}

	/**
	 * What can be done to the person chosen, along the foot.
	 *
	 * Down here rather than on the tile, and that is what lets the shelf be a
	 * shelf: four buttons on a fifty-pixel tile is not a tile, and four buttons on
	 * every row is the list this replaced. One person at a time, with their facts
	 * beside the buttons, so that "ban" is never pressed next to the wrong name.
	 */
	private void playerPanel(com.mopicmp.npcstudio.server.Players.Player player,
			int x, int y, int right) {
		int put = right - 6;
		int line = y + PICKED_TALL - 26;
		if (player.online()) {
			Component kick = Component.translatable("npc_studio.server.players.kick");
			put -= Pill.wide(kick);
			addRenderableWidget(new Pill(put, line, Icon.CLOSED, kick, WARN,
				() -> askAbout(player, "kick")));
			put -= 6;
		}
		Component ban = Component.translatable(player.banned()
			? "npc_studio.server.players.pardon" : "npc_studio.server.players.ban");
		put -= Pill.wide(ban);
		addRenderableWidget(new Pill(put, line, player.banned() ? Icon.CHECK : Icon.REMOVE,
			ban, player.banned() ? GOOD : WARN,
			() -> askAbout(player, player.banned() ? "pardon" : "ban")));

		// Banning the address as well, which is the other half of banning somebody
		// and a different act: a name ban keeps out an account, an address ban keeps
		// out whatever is behind that address. Only while they are on the server —
		// the address is something the server knows about a live connection and
		// nothing it writes down, so once they leave there is nothing to ban. Said
		// on the button rather than left as a press that quietly fails.
		Component address = Component.translatable("npc_studio.server.players.ban_address");
		// The facts down the left of this panel need their room, and the uuid line
		// is the longest of them. A button that would sit on top of it is left out
		// rather than drawn over it.
		if (!player.banned() && put - Pill.wide(address) - 6 > x + 250) {
			put -= Pill.wide(address) + 6;
			Pill byAddress = new Pill(put, line, Icon.ADDRESS, address, WARN,
				() -> askAddress(player));
			byAddress.active = player.online() && manager.ready();
			byAddress.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
				Component.translatable(player.online() && manager.ready()
					? "npc_studio.server.players.ban_address_hint"
					: "npc_studio.server.players.ban_address_off")));
			addRenderableWidget(byAddress);
		}

		Component white = Component.translatable(player.whitelisted()
			? "npc_studio.server.players.unwhitelist" : "npc_studio.server.players.whitelist");
		put -= Pill.wide(white) + 6;
		addRenderableWidget(new Pill(put, line, player.whitelisted() ? Icon.CANCEL : Icon.ADD,
			white, TEXT_DIM, () -> toPlayer(player,
				player.whitelisted() ? "unwhitelist" : "whitelist", "")));

		// ---- and the privilege, which is a choice rather than a switch
		//
		// It used to be one button meaning "operator, level four", which is the only
		// thing a plain server has. A server with named ranks has as many answers as
		// somebody has made, so the button opens the list of them — a dropdown, not
		// a row of buttons, because it is one answer out of many and that is what a
		// dropdown is for. Operator stays in that list on both kinds of server: it
		// is a different thing from a rank and it does not stop existing because a
		// plugin is installed.
		Component give = Component.translatable("npc_studio.server.players.give");
		put -= Pill.wide(give) + 6;
		int at = put;
		addRenderableWidget(new Pill(at, line, Icon.HANDLES, give,
			player.op() ? GOOD : TEXT_DIM, () -> openPrivileges(player, at, line + FIELD)));
	}

	/**
	 * The privileges this server can give, in one list.
	 *
	 * Two kinds of thing in it, and they are marked apart rather than mixed: the
	 * ranks a permission plugin keeps, and the four operator levels the game itself
	 * has. Somebody made an operator is not in a group and somebody in a group is
	 * not an operator — showing them as one list of alternatives would be a lie
	 * about what pressing them does.
	 */
	private void openPrivileges(com.mopicmp.npcstudio.server.Players.Player player,
			int x, int below) {
		// The ranks have to be in hand before the list is shown, not asked for
		// while it is on screen: a menu that opens with the plugin's answers
		// missing is a menu that says this server has no ranks. So on the first
		// press the server is asked, and the list opens when the answer is here.
		if (withLuckPerms() && !rightsRead && !rightsReading) {
			rightsReading = true;
			ManagedServer which = server;
			manager.privileges(which, found -> {
				rightsReading = false;
				if (server != which) return;
				rights = found;
				rightsRead = true;
				showPrivileges(player, x, below);
			}, said -> {
				rightsReading = false;
				if (server != which) return;
				rightsRead = true;
				trouble = said;
				rebuild();
			});
			return;
		}
		showPrivileges(player, x, below);
	}

	private void showPrivileges(com.mopicmp.npcstudio.server.Players.Player player,
			int x, int below) {
		List<Dropdown.Option> options = new ArrayList<>();
		if (withLuckPerms()) {
			for (var group : rights.groups()) {
				options.add(new Dropdown.Option("group:" + group.name(),
					Component.translatable("npc_studio.server.players.give_group",
						group.title())));
			}
		}
		for (int level : com.mopicmp.npcstudio.server.Privileges.LEVELS) {
			options.add(new Dropdown.Option("op:" + level,
				Component.translatable("npc_studio.server.rights.level." + level)));
		}
		if (player.op()) {
			options.add(new Dropdown.Option("op:0",
				Component.translatable("npc_studio.server.players.deop")));
		}
		int wide = 260;
		dropdown.open(Math.max(contentLeft, x + Pill.wide(Component.translatable(
			"npc_studio.server.players.give")) - wide), below, wide, height, options, "",
			picked -> {
				if (picked.startsWith("op:")) {
					int level = Integer.parseInt(picked.substring(3));
					askLevel(player, level);
					return;
				}
				String group = picked.substring("group:".length());
				trouble = "";
				// Said when the export afterwards shows it, not when the command is
				// sent. LuckPerms answers nothing, so "done" the moment a press
				// happens is a message that would appear just as cheerfully for a
				// command the plugin threw away.
				givenTo = player.label();
				toRights(List.of("lp user " + player.label() + " parent add " + group));
			});
	}

	/**
	 * Making somebody an operator, which is handing over the server.
	 *
	 * Asked, as it always was — the question just has a level in it now, because
	 * level two and level four are not the same offer.
	 */
	private void askLevel(com.mopicmp.npcstudio.server.Players.Player player, int level) {
		if (level <= 0) {
			askAbout(player, "deop");
			return;
		}
		confirm.ask(
			Component.translatable("npc_studio.server.players.op_title"),
			Component.translatable("npc_studio.server.players.op_level_body", player.label(),
				Component.translatable("npc_studio.server.rights.level." + level).getString()),
			Component.translatable("npc_studio.server.players.op"),
			Component.translatable("npc_studio.server.no_session.no"),
			() -> setLevel(player.label(), player.uuid(), level));
	}

	/**
	 * One face on the shelf.
	 *
	 * Its own widget, so that where it is, whether it is chosen and whether the
	 * mouse is on it are one thing rather than three that have to agree. The
	 * marks — on now, operator, whitelisted, banned — are pips at the corners
	 * rather than words, because a word does not fit on a tile and four of them fit
	 * even less; the panel below spells them out for whoever is chosen.
	 */
	private final class Face extends AbstractWidget {

		private final com.mopicmp.npcstudio.server.Players.Player player;

		Face(int x, int y, com.mopicmp.npcstudio.server.Players.Player player) {
			// No tooltip. The name is written under the face and the uuid is on the
			// panel below for whoever is chosen, so the panel that used to follow the
			// mouse said nothing that was not already on the screen — while covering
			// the four tiles under it and moving as fast as the hand did.
			super(x, y, tileWide(), tileTall(), Component.literal(player.label()));
			this.player = player;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor graphics,
				int mouseX, int mouseY, float delta) {
			boolean on = player.uuid().equals(playerPicked);
			int wide = tileWide();
			int picture = tilePicture();
			graphics.fill(getX(), getY(), getX() + wide, getY() + picture,
				on ? 0xFF27313E : isHovered() ? 0xFF232A34 : CARD);
			if (on) {
				graphics.fill(getX(), getY(), getX() + wide, getY() + 2, ACCENT);
				graphics.fill(getX(), getY() + picture - 2, getX() + wide, getY() + picture,
					ACCENT);
			}

			var skin = PlayerHeads.skinOf(player.uuid(), player.name());
			if (skin != null && playerFigures) {
				// The whole person, turned and with a leg forward — the way every
				// site that shows a skin shows one, and the way the wardrobe does,
				// because it is the same figure and the same code.
				com.mopicmp.npcstudio.client.wardrobe.SkinFigure.draw(graphics,
					skin.body().texturePath(), PlayerHeads.slim(skin),
					getX() + 3, getY() + 3, getX() + wide - 3, getY() + picture - 3);
			} else if (skin != null) {
				int headX = getX() + (wide - HEAD) / 2;
				int headY = getY() + (picture - HEAD) / 2 - 1;
				PlayerHeads.draw(graphics, skin.body().texturePath(), headX, headY, HEAD);
			} else {
				// No skin, and on a server that does not check accounts there will
				// not be one — the uuid is made from the name itself. A letter, on a
				// colour of that uuid's own, so that two people are not one grey
				// square.
				int size = Math.min(HEAD, Math.min(wide, picture) - 16);
				int blockX = getX() + (wide - size) / 2;
				int blockY = getY() + (picture - size) / 2 - 1;
				graphics.fill(blockX, blockY, blockX + size, blockY + size, tint());
				String letter = player.label().substring(0, 1)
					.toUpperCase(java.util.Locale.ROOT);
				big(graphics, letter, blockX + (size - font.width(letter) * 2) / 2,
					blockY + size / 2 - 8, 2f, 0xFFE8EDF2);
			}

			int pip = getX() + 4;
			if (player.online()) {
				graphics.fill(pip, getY() + 4, pip + 4, getY() + 8, GOOD);
				pip += 6;
			}
			if (player.op()) {
				graphics.fill(pip, getY() + 4, pip + 4, getY() + 8, ACCENT);
				pip += 6;
			}
			if (player.whitelisted()) {
				graphics.fill(pip, getY() + 4, pip + 4, getY() + 8, 0xFFB39DDB);
			}
			if (player.banned()) {
				graphics.fill(getX() + wide - 8, getY() + 4, getX() + wide - 4, getY() + 8, WARN);
			}

			String label = shorten(player.label(), wide - 4);
			graphics.text(font, label, getX() + wide / 2 - font.width(label) / 2,
				getY() + picture + 2, on ? ACCENT : player.banned() ? WARN : TEXT_DIM);
		}

		/**
		 * A colour of this player's own, taken from their uuid.
		 *
		 * Out of a list rather than computed round a colour wheel: eight muted
		 * colours picked to sit in this window are worth more than a hue that can
		 * land anywhere, and it keeps a drawing class out of the desktop toolkit.
		 */
		private int tint() {
			return FACE_TINTS[Math.floorMod(player.uuid().hashCode(), FACE_TINTS.length)];
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			pick();
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			if (!pressing(event)) return super.keyPressed(event);
			pick();
			return true;
		}

		/**
		 * Choosing, and choosing again to stop.
		 *
		 * Pressing the chosen one a second time puts the panel away, which is how
		 * somebody says "never mind" without a second button for saying it.
		 */
		private void pick() {
			playerPicked = player.uuid().equals(playerPicked) ? "" : player.uuid();
			rebuild();
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}

	/**
	 * The three lists, one under another, each with a way in and a way out.
	 *
	 * Rows rather than tiles, and that is not inconsistency: a whitelist entry is
	 * a decision, not a person — half of them belong to somebody who has never
	 * joined and has no face — and an address ban belongs to nobody at all. What a
	 * row has to carry is the entry, why it is there, and the one button that
	 * undoes it.
	 */
	private void listWidgets(int top) {
		int x = contentLeft + PAD;
		int right = width - PAD;
		int bottom = height - PAD;
		listedRows.clear();
		playerListTop = top;
		int y = top - playerScroll;

		// One source for both the count and the rows. They used to come from two —
		// the file for the number beside the heading, the read list for the rows
		// under it — which is two answers to one question and a heading that can
		// say "1" over nothing at all. It also read three files while laying the
		// screen out, every frame anybody pressed anything.
		List<com.mopicmp.npcstudio.server.Players.Player> white = new ArrayList<>();
		List<com.mopicmp.npcstudio.server.Players.Player> banned = new ArrayList<>();
		for (var player : players) {
			if (player.whitelisted()) white.add(player);
			if (player.banned()) banned.add(player);
		}

		y = listSection(y, x, right, bottom, "white", white.size(), true,
			whiteTyped, typed -> whiteTyped = typed, () -> addByName("whitelist"));
		if (openList("white")) {
			for (var player : white) {
				y = listRow(y, x, right, bottom, player.label(), saidAbout(player),
					() -> toPlayer(player, "unwhitelist", ""));
			}
		}

		y = listSection(y, x, right, bottom, "banned", banned.size(), false,
			banTyped, typed -> banTyped = typed, () -> addByName("ban"));
		if (openList("banned")) {
			for (var player : banned) {
				y = listRow(y, x, right, bottom, player.label(), saidAbout(player),
					() -> toPlayer(player, "pardon", ""));
			}
		}

		y = listSection(y, x, right, bottom, "ips", ipBans.size(), false,
			ipTyped, typed -> ipTyped = typed, () -> addIp(ipTyped));
		if (openList("ips")) {
			for (var ban : ipBans) {
				String said = ban.reason().isBlank() ? ban.source() : ban.reason();
				y = listRow(y, x, right, bottom, ban.ip(), said, () -> dropIpBan(ban.ip()));
			}
		}

		listedTotal = white.size() + banned.size() + ipBans.size();
		playerHeight = Math.max(0, y + playerScroll + PAD - bottom);
	}

	/** How many entries the three lists hold between them, folded or not. */
	private int listedTotal;

	/** Why somebody is on the list they are on, or when they were last here. */
	private String saidAbout(com.mopicmp.npcstudio.server.Players.Player player) {
		if (player.banned() && !player.banReason().isBlank()) return player.banReason();
		return player.everPlayed()
			? Component.translatable("npc_studio.server.players.seen",
				when(java.time.Instant.ofEpochMilli(player.seen()))).getString()
			: Component.translatable("npc_studio.server.players.never").getString();
	}

	/**
	 * A heading, with the way to add something to that list beside it.
	 *
	 * The field is next to the heading rather than at the foot of the section: at
	 * the foot it belongs to whatever row happens to be above it, and with three
	 * lists on one screen that is three chances to put a name on the wrong list.
	 *
	 * <p>The heading is itself the fold: a list somebody is not working on is a
	 * list they should be able to put away, and with three of them one long list
	 * pushes the other two off the bottom of a short window.
	 */
	private int listSection(int y, int x, int right, int bottom, String which, int count,
			boolean enforcing, String typed, java.util.function.Consumer<String> keep,
			Runnable add) {
		if (y >= playerListTop - 2 && y + KIND_HEAD <= bottom) {
			int at = right;
			// The heading keeps its ninety pixels and everything else takes what is
			// left, in order of what the line is for. Nothing is squeezed to a
			// sliver and nothing is placed over anything else: widgets cannot be
			// clipped, so two that overlap are one of them silently eating the
			// other's presses.
			int floor = x + 90;
			// A folded list keeps its heading and loses everything else, the way it
			// loses its rows. Adding to a list that is put away is adding to
			// something nobody can see happen.
			if (openList(which)) {
				Component put = Component.translatable("npc_studio.server.players.list.add");
				int wide = Math.min(160, at - Pill.wide(put) - 6 - floor);
				if (wide >= 70) {
					at -= Pill.wide(put);
					addRenderableWidget(new Pill(at, y + 2, Icon.ADD, put, GOOD, add));
					at -= 6 + wide;
					Component asks = Component.translatable(
						"npc_studio.server.players.list.add_" + which);
					EditBox box = Field.make(font, at, y + 2, wide, FIELD, asks);
					// An empty box with a button beside it says nothing about what
					// goes in it, and there is no room on this line for a label. The
					// hint is the label, and it steps aside the moment anything is
					// typed.
					box.setHint(asks);
					box.setMaxLength(45);
					box.setValue(typed);
					box.setResponder(keep::accept);
					addRenderableWidget(box);
					framed.add(new Framed(box, at, y + 2, wide, FIELD));
					submits.put(box, add);
				}
			}
			if (enforcing && at - 10 - Knob.wide() >= floor) {
				at -= 10 + Knob.wide();
				addRenderableWidget(new Knob(at, y + 2));
			}
			addRenderableWidget(new Fold(x, y, Math.max(40, at - 10 - x), which, count));
		}
		return y + KIND_HEAD + 4;
	}

	private int listRow(int y, int x, int right, int bottom, String name,
			String said, Runnable undo) {
		listedRows.add(new Listed(name, said, y));
		if (y >= playerListTop - 2 && y + LIST_ROW <= bottom) {
			Component off = Component.translatable("npc_studio.server.players.list.remove");
			addRenderableWidget(new Pill(right - Pill.wide(off), y, Icon.REMOVE, off, WARN, undo));
		}
		return y + LIST_ROW;
	}

	/**
	 * A list's heading, which is also the way to fold it away.
	 *
	 * The whole heading takes the press rather than a small arrow beside it: the
	 * arrow says which way the thing goes, and what somebody aims at is the word.
	 */
	private final class Fold extends AbstractWidget {

		private final String which;
		private final int count;

		Fold(int x, int y, int wide, String which, int count) {
			super(x, y, wide, KIND_HEAD - 2, Component.translatable(
				"npc_studio.server.players.list." + which));
			this.which = which;
			this.count = count;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor graphics,
				int mouseX, int mouseY, float delta) {
			boolean open = openList(which);
			int ink = isHovered() ? TEXT : ACCENT;
			arrow(graphics, getX(), getY() + 8, open, ink);
			int at = getX() + 13;
			graphics.text(font, getMessage(), at, getY() + 7, ink);
			at += font.width(getMessage()) + 7;
			graphics.text(font, String.valueOf(count), at, getY() + 7, TEXT_DIM);
			graphics.fill(getX(), getY() + KIND_HEAD - 4, getX() + width,
				getY() + KIND_HEAD - 3, EDGE);
		}

		/**
		 * A triangle out of rows, drawn rather than taken from the icon sheet.
		 *
		 * Eight pixels of the one shape everybody already reads as "there is more
		 * under here": pointing down when the list is open, along when it is shut.
		 */
		private void arrow(GuiGraphicsExtractor graphics, int x, int y, boolean open, int ink) {
			for (int step = 0; step < 4; step++) {
				if (open) {
					graphics.fill(x + step, y + step, x + 8 - step, y + step + 1, ink);
				} else {
					graphics.fill(x + step, y + step, x + step + 1, y + 8 - step, ink);
				}
			}
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			flip();
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			if (!pressing(event)) return super.keyPressed(event);
			flip();
			return true;
		}

		private void flip() {
			if (!foldedLists.remove(which)) foldedLists.add(which);
			playerScroll = 0;
			rebuild();
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}

	/** How long the whitelist switch's track is; the word beside it adds the rest. */
	private static final int KNOB_TRACK = 26;

	/**
	 * Whether the whitelist is checked at all — a switch, on the list itself.
	 *
	 * It is one line of {@code server.properties} and it is also on the settings
	 * tab, and that is not a duplicate: the list and the switch are two halves of
	 * one thing, and a server with nine names on a whitelist it is not checking
	 * lets everybody in. Somebody who has just built the list is standing exactly
	 * here, and sending them to another tab to make it mean anything is how a
	 * server ends up open by accident. Both write the same line, so the two can
	 * never say different things.
	 */
	private final class Knob extends AbstractWidget {

		static int wide() {
			var font = net.minecraft.client.Minecraft.getInstance().font;
			return KNOB_TRACK + 6 + Math.max(
				font.width(Component.translatable("npc_studio.server.players.list.enforced")),
				font.width(Component.translatable("npc_studio.server.players.list.off")));
		}

		Knob(int x, int y) {
			super(x, y, wide(), FIELD, Component.translatable(
				"npc_studio.server.players.list.enforce"));
			setTooltip(net.minecraft.client.gui.components.Tooltip.create(
				Component.translatable("npc_studio.server.players.list.enforce_hint")));
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor graphics,
				int mouseX, int mouseY, float delta) {
			int top = getY() + (height - 12) / 2;
			graphics.fill(getX(), top, getX() + KNOB_TRACK, top + 12,
				whitelistOn ? 0xFF1B3F4E : 0xFF1B2028);
			graphics.fill(getX(), top, getX() + KNOB_TRACK, top + 1, whitelistOn ? ACCENT : EDGE);
			graphics.fill(getX(), top + 11, getX() + KNOB_TRACK, top + 12,
				whitelistOn ? ACCENT : EDGE);
			graphics.fill(getX(), top, getX() + 1, top + 12, whitelistOn ? ACCENT : EDGE);
			graphics.fill(getX() + KNOB_TRACK - 1, top, getX() + KNOB_TRACK, top + 12,
				whitelistOn ? ACCENT : EDGE);
			int knob = whitelistOn ? getX() + KNOB_TRACK - 11 : getX() + 2;
			graphics.fill(knob, top + 2, knob + 9, top + 10,
				whitelistOn ? ACCENT : isHovered() ? TEXT : TEXT_DIM);
			Component said = Component.translatable(whitelistOn
				? "npc_studio.server.players.list.enforced"
				: "npc_studio.server.players.list.off");
			graphics.text(font, said, getX() + KNOB_TRACK + 6, getY() + (height - 8) / 2,
				whitelistOn ? ACCENT : TEXT_DIM);
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			setWhitelist(!whitelistOn);
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			if (!pressing(event)) return super.keyPressed(event);
			setWhitelist(!whitelistOn);
			return true;
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}

	/**
	 * Turn the checking on or off, and leave the settings tab agreeing.
	 *
	 * The switch moves before the answer comes back, because it is the answer to
	 * a press and a switch that waits half a second reads as one that did not
	 * take. If the server refuses, it moves back and says why.
	 */
	private void setWhitelist(boolean on) {
		trouble = "";
		boolean was = whitelistOn;
		whitelistOn = on;
		rebuild();
		manager.enableWhitelist(server, on, () -> {
			// The settings tab reads its values out of the same file. Told here, it
			// does not have to read the file again to stop showing the old answer.
			values.put("white-list", String.valueOf(on));
			edits.remove("white-list");
			readPlayers();
			rebuild();
		}, said -> {
			whitelistOn = was;
			trouble = said;
			say(said);
			rebuild();
		});
	}

	/**
	 * Add somebody to a list by name, whether or not the server has met them.
	 *
	 * The whole reason this half exists: whitelisting somebody who has not joined
	 * yet is the ordinary case, and there is no tile to press for a person who has
	 * never been here.
	 */
	private void addByName(String what) {
		boolean white = "whitelist".equals(what);
		String name = (white ? whiteTyped : banTyped).strip();
		if (!name.matches("[A-Za-z0-9_]{1,16}")) {
			// Said on the tab itself. It used to go only to the note in the corner of
			// the far column, where it is a grey line somebody is not looking at —
			// and a button that refuses without saying so is a button that is broken.
			trouble = Component.translatable("npc_studio.server.players.list.bad_name")
				.getString();
			say(trouble);
			rebuild();
			return;
		}
		if (white) {
			whiteTyped = "";
			toName(name, "whitelist");
			return;
		}
		// Banning asks, whether the name was typed or a face was pressed. It is done
		// to a person, and the panel below the shelf asks for exactly that reason.
		banTyped = "";
		confirm.ask(
			Component.translatable("npc_studio.server.players.ban_title"),
			Component.translatable("npc_studio.server.players.ban_body", name),
			Component.translatable("npc_studio.server.players.ban"),
			Component.translatable("npc_studio.server.no_session.no"),
			() -> toName(name, "ban"));
		rebuild();
	}

	private void toName(String name, String what) {
		trouble = "";
		manager.toPlayer(server, what, name, "", "", () -> {
			say(Component.translatable("npc_studio.server.players.done",
				Component.translatable("npc_studio.server.players." + what).getString(),
				name).getString());
			readPlayers();
			rebuild();
		}, said -> {
			trouble = said;
			say(said);
			rebuild();
		});
	}

	private void addIp(String typed) {
		String ip = typed.strip();
		if (!com.mopicmp.npcstudio.server.Players.validIp(ip)) {
			trouble = Component.translatable("npc_studio.server.players.list.bad_ip").getString();
			say(trouble);
			rebuild();
			return;
		}
		ipTyped = "";
		// An address ban keeps out whoever is behind that address, which on a
		// household or a school is everybody in it. Asked, like the other bans.
		confirm.ask(
			Component.translatable("npc_studio.server.players.ban_ip_title"),
			Component.translatable("npc_studio.server.players.ban_ip_body", ip),
			Component.translatable("npc_studio.server.players.ban"),
			Component.translatable("npc_studio.server.no_session.no"),
			() -> manager.toIp(server, ip, true, () -> {
				say(Component.translatable("npc_studio.server.players.done",
					Component.translatable("npc_studio.server.players.ban").getString(),
					ip).getString());
				readPlayers();
				rebuild();
			}, said -> {
				trouble = said;
				say(said);
				rebuild();
			}));
	}

	private void dropIpBan(String ip) {
		trouble = "";
		manager.toIp(server, ip, false, () -> {
			say(Component.translatable("npc_studio.server.players.done",
				Component.translatable("npc_studio.server.players.pardon").getString(),
				ip).getString());
			readPlayers();
			rebuild();
		}, said -> {
			trouble = said;
			say(said);
			rebuild();
		});
	}

	private void drawLists(GuiGraphicsExtractor graphics) {
		int x = contentLeft + PAD;
		int right = width - PAD;
		// The headings place themselves now, because a heading that folds a list is
		// something to press. What is left here is the entries.
		graphics.enableScissor(contentLeft, playerListTop - 2, width, height - PAD);
		for (Listed row : listedRows) {
			graphics.fill(x, row.y(), right, row.y() + LIST_ROW - 4, CARD);
			graphics.text(font, shorten(row.name(), 180), x + 8, row.y() + 5, TEXT);
			if (!row.said().isBlank()) {
				graphics.text(font, shorten(row.said(), right - x - 300), x + 200,
					row.y() + 5, TEXT_DIM);
			}
		}
		graphics.disableScissor();
		scrollbar(graphics, playerListTop, height - PAD, playerScroll, playerHeight);

		// An empty list is said once, under all three, rather than three times — and
		// not at all when the reason nothing is showing is that they are folded.
		if (listedTotal == 0) {
			graphics.text(font, Component.translatable("npc_studio.server.players.list.empty"),
				x, height - PAD - 14, TEXT_DIM);
		}
	}

	/**
	 * Ask first for the three that are hard to take back.
	 *
	 * Giving somebody operator is handing over the server; banning and kicking are
	 * done to a person. The whitelist is the one that is neither, so it is the one
	 * that happens on the press.
	 */
	/**
	 * Ban the address the chosen player is connected from, having asked.
	 *
	 * Asked with more than the usual force, because this one reaches people who
	 * are not the person being banned: a household, a school, a hall of residence
	 * and a mobile network all share an address, and this is the only button in
	 * the window whose effect lands on somebody who did nothing.
	 */
	private void askAddress(com.mopicmp.npcstudio.server.Players.Player player) {
		confirm.ask(
			Component.translatable("npc_studio.server.players.ban_address_title"),
			Component.translatable("npc_studio.server.players.ban_address_body", player.label()),
			Component.translatable("npc_studio.server.players.ban_address"),
			Component.translatable("npc_studio.server.no_session.no"),
			() -> {
				trouble = "";
				manager.banAddressOf(server, player.label(), "", () -> {
					say(Component.translatable("npc_studio.server.players.done",
						Component.translatable("npc_studio.server.players.ban_address")
							.getString(),
						player.label()).getString());
					readPlayers();
					rebuild();
				}, said -> {
					trouble = said;
					say(said);
					rebuild();
				});
			});
	}

	private void askAbout(com.mopicmp.npcstudio.server.Players.Player player, String what) {
		confirm.ask(
			Component.translatable("npc_studio.server.players." + what + "_title"),
			Component.translatable("npc_studio.server.players." + what + "_body",
				player.label()),
			Component.translatable("npc_studio.server.players." + what),
			Component.translatable("npc_studio.server.no_session.no"),
			() -> toPlayer(player, what, ""));
	}

	private void toPlayer(com.mopicmp.npcstudio.server.Players.Player player, String what,
			String reason) {
		trouble = "";
		manager.toPlayer(server, what, player.label(), player.uuid(), reason, () -> {
			say(Component.translatable("npc_studio.server.players.done",
				Component.translatable("npc_studio.server.players." + what).getString(),
				player.label()).getString());
			readPlayers();
			rebuild();
		}, said -> {
			trouble = said;
			say(said);
			rebuild();
		});
	}

	private void drawPlayers(GuiGraphicsExtractor graphics) {
		int x = contentLeft + PAD;
		int right = width - PAD;
		int top = contentTop + 4;
		if (!playersRead) {
			graphics.text(font, Component.translatable("npc_studio.server.players.reading"),
				x, top + 26, TEXT_DIM);
			return;
		}
		graphics.fill(contentLeft, top + 13, width, top + 14, EDGE);
		if (playersReading) {
			// Once there is something on the screen, reading again is a word in the
			// corner rather than a screen of its own.
			graphics.text(font, Component.translatable("npc_studio.server.players.refreshing"),
				x, top + 18, TEXT_DIM);
		}
		// What went wrong, on the tab it went wrong on. Every other tab in this
		// window draws this line; this one did not, so a press that failed — an
		// address the server refused, a command channel that would not open — looked
		// exactly like a press that did nothing.
		if (!trouble.isBlank()) {
			graphics.textWithWordWrap(font, Component.literal(trouble),
				x, top + 26, right - x, WARN);
		}

		if (playerHalf == Whom.LISTS) {
			drawLists(graphics);
			return;
		}

		List<com.mopicmp.npcstudio.server.Players.Player> showing = showingPlayers();
		if (showing.isEmpty()) {
			graphics.textWithWordWrap(font, Component.translatable(playerHalf == Whom.ONLINE
				? "npc_studio.server.players.nobody_on"
				: "npc_studio.server.players.nobody"),
				x, playerListTop + 4, Math.min(520, right - x), TEXT_DIM);
			return;
		}
		// The tiles draw themselves. What is left is the count, quietly, and the
		// panel about whoever is chosen.
		String counted = Component.translatable("npc_studio.server.players.count",
			showing.size()).getString();
		graphics.text(font, counted, right - font.width(counted), top + 18, TEXT_DIM);

		var picked = pickedPlayer();
		int bottom = height - PAD - (picked == null ? 0 : PICKED_TALL + 6);
		scrollbar(graphics, playerListTop, bottom, playerScroll, playerHeight);
		if (picked != null) {
			drawPlayerPanel(graphics, picked, x, height - PAD - PICKED_TALL, right);
		}
	}

	private void drawPlayerPanel(GuiGraphicsExtractor graphics,
			com.mopicmp.npcstudio.server.Players.Player player, int x, int y, int right) {
		graphics.fill(x, y - 6, right, y - 5, EDGE);
		card(graphics, x, y, right - x, PICKED_TALL);

		Identifier head = PlayerHeads.of(player.uuid(), player.name());
		if (head != null) PlayerHeads.draw(graphics, head, x + 8, y + 8, 32);
		int text = x + (head != null ? 48 : 8);
		big(graphics, player.label(), text, y + 6, 1.2f, player.banned() ? WARN : TEXT);

		// The facts, and every one of them has a file behind it. No score.
		String said = player.online()
			? Component.translatable("npc_studio.server.players.now").getString()
			: player.everPlayed()
				? Component.translatable("npc_studio.server.players.seen",
					when(java.time.Instant.ofEpochMilli(player.seen()))).getString()
				: Component.translatable("npc_studio.server.players.never").getString();
		if (player.minutes() >= 0) {
			said += " · " + Component.translatable("npc_studio.server.players.played",
				played(player.minutes())).getString();
		}
		graphics.text(font, said, text, y + 24, player.online() ? GOOD : TEXT_DIM);

		String marks = "";
		if (player.op()) {
			marks += Component.translatable("npc_studio.server.players.is_op").getString();
		}
		if (player.whitelisted()) {
			marks += (marks.isBlank() ? "" : " · ") + Component.translatable(
				"npc_studio.server.players.is_white").getString();
		}
		if (player.banned()) {
			marks += (marks.isBlank() ? "" : " · ") + Component.translatable(
				"npc_studio.server.players.is_banned").getString()
				+ (player.banReason().isBlank() ? "" : ": " + player.banReason());
		}
		if (!marks.isBlank()) {
			graphics.text(font, shorten(marks, right - text - 20), text, y + 36,
				player.banned() ? WARN : TEXT_DIM);
		}
		// The uuid, always, on a line of its own.
		//
		// It used to be shown *instead* of the marks, so the moment somebody was
		// whitelisted or given operator their uuid disappeared — exactly the people
		// whose uuid gets looked up. It is the one fact on this panel that
		// identifies rather than describes: it is what the ban file holds, what
		// tells two people of the same name apart, and what somebody is here to
		// copy. A mark is a sentence about them; this is who they are.
		graphics.text(font, player.uuid(), text, y + (marks.isBlank() ? 36 : 48), TEXT_DIM);
	}

	/** Minutes as a person says them: an hour and a half, not ninety minutes. */
	private String played(long minutes) {
		if (minutes < 60) {
			return minutes + " " + Component.translatable(
				"npc_studio.server.players.minutes").getString();
		}
		long hours = minutes / 60;
		long rest = minutes % 60;
		String said = hours + " " + Component.translatable(
			"npc_studio.server.players.hours").getString();
		return rest == 0 ? said : said + " " + rest + " " + Component.translatable(
			"npc_studio.server.players.minutes").getString();
	}

	// ------------------------------------------------------------------ rights

	/**
	 * Who may do what, and which thing on this server decides that.
	 *
	 * <b>Two backends and no third thing.</b> A plain server has four operator
	 * levels: no names, no groups, nothing anybody can add to. A server with
	 * LuckPerms has named ranks with weights, prefixes, inheritance and ladders,
	 * and <em>LuckPerms</em> enforces them — this window is a way of looking at
	 * them and asking for changes, not a permission system. Inventing ranks of our
	 * own would be inventing names no server obeys.
	 *
	 * <p>Why nothing here trusts a reply: measured against a live Paper server,
	 * LuckPerms answers <b>nothing</b> over the command channel — not on success,
	 * not on failure, not for a command naming a group that does not exist. So a
	 * change is sent, the server is asked to export, and what appears on the screen
	 * afterwards is what the export said. If a change did not take, the screen
	 * shows it not having taken rather than a cheerful message.
	 */
	private com.mopicmp.npcstudio.server.LuckPermsExport.Data rights =
		com.mopicmp.npcstudio.server.LuckPermsExport.Data.empty();
	private boolean rightsRead;
	private boolean rightsReading;
	private String rightsTrouble = "";
	private String rightsPicked = "";
	private String rightsFind = "";
	private int rightsListScroll;
	private int rightsListHeight;
	private int rightsListTop;
	private int rightsScroll;
	private int rightsHeight;
	private int rightsTop;

	private List<com.mopicmp.npcstudio.server.Ops.Op> operators = List.of();
	private boolean operatorsRead;
	private String opTyped = "";
	private int opLevel = 4;

	/**
	 * What is typed into the three fields, before it is sent.
	 *
	 * Not written as they are typed, unlike the settings tab. Every change here is
	 * a command <em>and</em> a full export, and an export writes lines into the
	 * server console — so a field that saved itself after a pause would put a
	 * paragraph in somebody's console for every word they typed.
	 */
	private String rightsDisplay;
	private String rightsWeight;
	private String rightsPrefix;
	private String nodeTyped = "";

	/** The left pane, which is the list of privileges. */
	private static final int RIGHTS_LIST = 200;

	/** One row in either pane, kept so the drawing knows where the widgets went. */
	private record RightsRow(String heading, String name, String said, int weight, int y) {
	}

	private final List<RightsRow> rightsRows = new ArrayList<>();
	private final List<RightsRow> permissionRows = new ArrayList<>();

	private boolean withLuckPerms() {
		return com.mopicmp.npcstudio.server.Privileges.kindOf(server)
			== com.mopicmp.npcstudio.server.Privileges.Kind.LUCK_PERMS;
	}

	private void readRights() {
		if (rightsReading) return;
		ManagedServer which = server;
		if (!operatorsRead) {
			manager.operators(which, found -> {
				if (server != which) return;
				operators = found;
				operatorsRead = true;
				if (tab == Tab.RIGHTS) rebuild();
			});
		}
		if (!withLuckPerms()) {
			rightsRead = true;
			return;
		}
		rightsReading = true;
		rightsTrouble = "";
		manager.privileges(which, found -> {
			rightsReading = false;
			if (server != which) return;
			rights = found;
			rightsRead = true;
			if (tab == Tab.RIGHTS) rebuild();
		}, said -> {
			rightsReading = false;
			if (server != which) return;
			rightsRead = true;
			rightsTrouble = said;
			if (tab == Tab.RIGHTS) rebuild();
		});
	}

	/** Ask again from the beginning, which is what every change ends with. */
	private void rightsAgain() {
		rightsRead = false;
		operatorsRead = false;
		readRights();
		rebuild();
	}

	private com.mopicmp.npcstudio.server.LuckPermsExport.Group pickedGroup() {
		return rights.group(rightsPicked);
	}

	/** Send commands, then show whatever the server exported afterwards. */
	private void toRights(List<String> commands) {
		rightsTrouble = "";
		rightsReading = true;
		ManagedServer which = server;
		rebuild();
		manager.toPrivileges(which, commands, found -> {
			rightsReading = false;
			if (server != which) return;
			rights = found;
			rightsRead = true;
			forgetRightsEdits();
			if (!givenTo.isBlank()) {
				say(Component.translatable("npc_studio.server.players.done",
					Component.translatable("npc_studio.server.players.give").getString(),
					givenTo).getString());
				givenTo = "";
				readPlayers();
			}
			rebuild();
		}, said -> {
			rightsReading = false;
			if (server != which) return;
			rightsTrouble = said;
			// The same complaint on whichever tab the press came from. Giving
			// somebody a rank is done from the players tab, and that tab does not
			// draw the rights tab's line.
			if (tab == Tab.PLAYERS) trouble = said;
			rebuild();
		});
	}

	/** Who was being given a rank, so the report can wait for the answer. */
	private String givenTo = "";

	private void forgetRightsEdits() {
		rightsDisplay = null;
		rightsWeight = null;
		rightsPrefix = null;
		nodeTyped = "";
	}

	private void rightsWidgets() {
		if (!rightsRead) {
			readRights();
			// A server with no plugin has its answer at once — there is nothing to
			// ask anybody. Carrying straight on saves a frame with nothing on it.
			if (!rightsRead) return;
		}
		rightsRows.clear();
		permissionRows.clear();
		int x = contentLeft + PAD;
		int right = width - PAD;
		int top = contentTop + 4;

		// ---- the strip along the top
		Component again = Component.translatable("npc_studio.server.rights.refresh");
		Pill refresh = new Pill(right - Pill.wide(again), top, Icon.RESET, again, TEXT_DIM,
			this::rightsAgain);
		refresh.active = !rightsReading;
		addRenderableWidget(refresh);

		if (withLuckPerms()) {
			Component made = Component.translatable("npc_studio.server.rights.create");
			Pill create = new Pill(right - Pill.wide(again) - 6 - Pill.wide(made), top,
				Icon.ADD, made, GOOD, this::askNewGroup);
			create.active = manager.ready() && !rightsReading;
			create.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
				Component.translatable(manager.ready()
					? "npc_studio.server.rights.create_hint"
					: "npc_studio.server.rights.stopped")));
			addRenderableWidget(create);
		}

		rightsTop = top + 22 + (rightsTrouble.isBlank() ? 0 : 14);
		if (!withLuckPerms()) {
			operatorWidgets(rightsTop);
			return;
		}
		listOfPrivileges(x, rightsTop);
		groupWidgets(x + RIGHTS_LIST + PAD, right, rightsTop);
	}

	// ---- the left pane

	private void listOfPrivileges(int x, int top) {
		int bottom = height - PAD;
		rightsListTop = top;
		int y = top - rightsListScroll;

		rightsRows.add(new RightsRow("npc_studio.server.rights.groups", null, "", 0, y));
		y += KIND_HEAD;
		for (var group : rights.groups()) {
			rightsRows.add(new RightsRow(null, group.name(), group.title(), group.weight(), y));
			if (y >= top - 2 && y + ADDON_ROW <= bottom) {
				addRenderableWidget(new PrivilegeRow(x, y, RIGHTS_LIST, group.name()));
			}
			y += ADDON_ROW;
		}
		if (rights.groups().isEmpty()) {
			rightsRows.add(new RightsRow(null, null,
				Component.translatable("npc_studio.server.rights.no_groups").getString(), 0, y));
			y += ADDON_ROW;
		}

		// The ladders, under the ranks and read-only.
		//
		// A track is an order over groups, and the useful half of it is not editing
		// that order — it is moving a person along it, which is one button on the
		// players tab. Shown here so that "why is there a staff ladder" has an
		// answer on the screen it is used from.
		if (!rights.tracks().isEmpty()) {
			y += 6;
			rightsRows.add(new RightsRow("npc_studio.server.rights.tracks", null, "", 0, y));
			y += KIND_HEAD;
			for (var track : rights.tracks()) {
				rightsRows.add(new RightsRow(null, track.name(),
					String.join(" → ", track.groups()), -1, y));
				y += ADDON_ROW;
			}
		}
		rightsListHeight = Math.max(0, y + rightsListScroll + PAD - bottom);
	}

	/** One privilege in the left pane. */
	private final class PrivilegeRow extends AbstractWidget {

		private final String name;

		PrivilegeRow(int x, int y, int across, String name) {
			super(x, y, across, ADDON_ROW, Component.literal(name));
			this.name = name;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor graphics,
				int mouseX, int mouseY, float delta) {
			boolean on = name.equals(rightsPicked);
			graphics.fill(getX(), getY(), getX() + width, getY() + height - 2,
				on ? 0xFF27313E : isHovered() ? 0xFF232A34 : CARD);
			if (on) graphics.fill(getX(), getY(), getX() + 2, getY() + height - 2, ACCENT);
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			choose();
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			if (!pressing(event)) return super.keyPressed(event);
			choose();
			return true;
		}

		private void choose() {
			rightsPicked = name;
			rightsScroll = 0;
			rightsFind = "";
			forgetRightsEdits();
			rebuild();
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}

	// ---- the right pane

	/**
	 * The chosen privilege: what it is called, what it inherits, what it can do.
	 *
	 * The three fields at the top are written by a button rather than by a pause,
	 * and that is not timidity. Each one is a command and then a full export, and
	 * an export puts lines in the server console — a field that saved itself while
	 * somebody typed would write a paragraph into their console per word.
	 */
	private void groupWidgets(int x, int right, int top) {
		var group = pickedGroup();
		if (group == null) return;
		int bottom = height - PAD;
		int y = top + 4 - rightsScroll;
		boolean up = manager.ready();

		// ---- name, and the way to take it away
		Component drop = Component.translatable("npc_studio.server.rights.drop");
		if (inside(y, top, bottom, FIELD)) {
			Pill away = new Pill(right - Pill.wide(drop), y, Icon.REMOVE, drop, WARN,
				() -> askDropGroup(group));
			away.active = up && !rightsReading;
			addRenderableWidget(away);
		}
		y += 26;

		// ---- the three things a rank is, side by side
		int across = Math.max(90, (right - x - 12) / 3);
		if (inside(y, top, bottom, LABEL + FIELD)) {
			field(x, y + LABEL, across, "npc_studio.server.rights.display",
				rightsDisplay != null ? rightsDisplay : group.display(),
				typed -> rightsDisplay = typed, 48);
			field(x + across + 6, y + LABEL, across, "npc_studio.server.rights.weight",
				rightsWeight != null ? rightsWeight : String.valueOf(group.weight()),
				typed -> rightsWeight = typed, 6);
			field(x + (across + 6) * 2, y + LABEL, right - x - (across + 6) * 2,
				"npc_studio.server.rights.prefix",
				rightsPrefix != null ? rightsPrefix : group.prefix(),
				typed -> rightsPrefix = typed, 64);
		}
		y += LABEL + FIELD + 6;

		if (inside(y, top, bottom, FIELD)) {
			Component keep = Component.translatable("npc_studio.server.rights.keep");
			Pill write = new Pill(right - Pill.wide(keep), y, Icon.SAVE, keep, GOOD,
				() -> keepGroup(group));
			write.active = up && !rightsReading && changedGroup(group);
			addRenderableWidget(write);
		}
		y += 26;

		// ---- what it inherits
		if (inside(y, top, bottom, KIND_HEAD)) {
			Component add = Component.translatable("npc_studio.server.rights.inherit_add");
			int at = right - Pill.wide(add);
			int under = y + 2 + FIELD;
			Pill inherit = new Pill(at, y + 2, Icon.ADD, add, TEXT_DIM,
				() -> openParents(group, at, under));
			inherit.active = up && !rightsReading;
			addRenderableWidget(inherit);
		}
		rightsRows.add(new RightsRow("npc_studio.server.rights.inherits", null, "", -2, y));
		y += KIND_HEAD + 2;

		for (String parent : group.parents()) {
			if (inside(y, top, bottom, LIST_ROW)) {
				Component off = Component.translatable("npc_studio.server.players.list.remove");
				Pill take = new Pill(right - Pill.wide(off), y, Icon.CANCEL, off, WARN,
					() -> toRights(List.of("lp group " + group.name()
						+ " parent remove " + parent)));
				take.active = up && !rightsReading;
				addRenderableWidget(take);
			}
			permissionRows.add(new RightsRow(null, parent, "", -2, y));
			y += LIST_ROW;
		}
		if (group.parents().isEmpty()) {
			permissionRows.add(new RightsRow(null, null,
				Component.translatable("npc_studio.server.rights.inherits_none").getString(),
				-3, y));
			y += LIST_ROW;
		}
		y += 6;

		// ---- and what it can do
		if (inside(y, top, bottom, KIND_HEAD)) {
			Component add = Component.translatable("npc_studio.server.players.list.add");
			int at = right - Pill.wide(add);
			Pill put = new Pill(at, y + 2, Icon.ADD, add, GOOD, () -> addNode(group));
			put.active = up && !rightsReading;
			addRenderableWidget(put);
			int wide = Math.min(240, at - 6 - (x + 200));
			if (wide >= 80) {
				Component asks = Component.translatable("npc_studio.server.rights.node");
				EditBox box = Field.make(font, at - 6 - wide, y + 2, wide, FIELD, asks);
				box.setHint(asks);
				box.setMaxLength(200);
				box.setValue(nodeTyped);
				box.setResponder(typed -> nodeTyped = typed);
				addRenderableWidget(box);
				framed.add(new Framed(box, at - 6 - wide, y + 2, wide, FIELD));
				submits.put(box, () -> addNode(group));
			}
		}
		rightsRows.add(new RightsRow("npc_studio.server.rights.can", null, "", -2, y));
		y += KIND_HEAD + 2;

		// The filter, only when there is enough to sift — the same rule the configs
		// tab uses, and for the same reason.
		List<com.mopicmp.npcstudio.server.LuckPermsExport.Permission> showing = showingNodes(group);
		if (group.permissions().size() > FIND_FROM && inside(y, top, bottom, FIELD)) {
			Component looking = Component.translatable("npc_studio.server.configs.find");
			EditBox find = Field.make(font, x, y, Math.min(260, right - x), FIELD, looking);
			find.setHint(looking);
			find.setMaxLength(60);
			find.setValue(rightsFind);
			find.setResponder(typed -> {
				rightsFind = typed;
				rightsScroll = 0;
				rebuild();
			});
			addRenderableWidget(find);
			framed.add(new Framed(find, x, y, Math.min(260, right - x), FIELD));
			y += FIELD + 6;
		} else if (group.permissions().size() > FIND_FROM) {
			y += FIELD + 6;
		}

		for (var permission : showing) {
			if (inside(y, top, bottom, LIST_ROW)) {
				Component off = Component.translatable("npc_studio.server.players.list.remove");
				int at = right - Pill.wide(off);
				Pill take = new Pill(at, y, Icon.REMOVE, off, WARN,
					() -> toRights(List.of("lp group " + group.name()
						+ " permission unset " + permission.node())));
				take.active = up && !rightsReading;
				addRenderableWidget(take);

				// The one control that says what this row is: a grant or a denial.
				// A denial is not the absence of a permission — it is a permission
				// taken away from whatever this rank inherits, and the two look the
				// same in a list that only has "remove".
				Component flip = Component.translatable(permission.granted()
					? "npc_studio.server.rights.deny" : "npc_studio.server.rights.allow");
				at -= 6 + Pill.wide(flip);
				Pill turn = new Pill(at, y, permission.granted() ? Icon.CANCEL : Icon.CHECK,
					flip, permission.granted() ? WARN : GOOD,
					() -> toRights(List.of("lp group " + group.name() + " permission set "
						+ permission.node() + " " + !permission.granted())));
				turn.active = up && !rightsReading;
				addRenderableWidget(turn);
			}
			permissionRows.add(new RightsRow(null, permission.node(),
				permission.temporary()
					? Component.translatable("npc_studio.server.rights.until",
						when(java.time.Instant.ofEpochSecond(permission.until()))).getString()
					: "",
				permission.granted() ? 1 : 0, y));
			y += LIST_ROW;
		}
		if (showing.isEmpty()) {
			permissionRows.add(new RightsRow(null, null,
				Component.translatable(group.permissions().isEmpty()
					? "npc_studio.server.rights.can_none"
					: "npc_studio.server.rights.none_found").getString(), -3, y));
			y += LIST_ROW;
		}
		rightsHeight = Math.max(0, y + rightsScroll + PAD - bottom);
	}

	private List<com.mopicmp.npcstudio.server.LuckPermsExport.Permission> showingNodes(
			com.mopicmp.npcstudio.server.LuckPermsExport.Group group) {
		String looking = rightsFind.strip().toLowerCase(java.util.Locale.ROOT);
		if (looking.isBlank()) return group.permissions();
		List<com.mopicmp.npcstudio.server.LuckPermsExport.Permission> kept = new ArrayList<>();
		for (var each : group.permissions()) {
			if (each.node().toLowerCase(java.util.Locale.ROOT).contains(looking)) kept.add(each);
		}
		return kept;
	}

	private boolean inside(int y, int top, int bottom, int tall) {
		return y >= top - 2 && y + tall <= bottom;
	}

	private void field(int x, int y, int across, String label, String value,
			java.util.function.Consumer<String> keep, int most) {
		Component asks = Component.translatable(label);
		EditBox box = Field.make(font, x, y, across, FIELD, asks);
		box.setHint(asks);
		box.setMaxLength(most);
		box.setValue(value);
		box.setResponder(keep::accept);
		addRenderableWidget(box);
		framed.add(new Framed(box, x, y, across, FIELD));
	}

	private boolean changedGroup(com.mopicmp.npcstudio.server.LuckPermsExport.Group group) {
		return rightsDisplay != null && !rightsDisplay.equals(group.display())
			|| rightsWeight != null && !rightsWeight.equals(String.valueOf(group.weight()))
			|| rightsPrefix != null && !rightsPrefix.equals(group.prefix());
	}

	/**
	 * Write the three fields, as three commands and only for what changed.
	 *
	 * A command per field rather than one that does all three, because that is what
	 * LuckPerms has — and sending the two that did not change would rewrite them to
	 * the same value and put two more lines in somebody's log for nothing.
	 */
	private void keepGroup(com.mopicmp.npcstudio.server.LuckPermsExport.Group group) {
		List<String> commands = new ArrayList<>();
		if (rightsDisplay != null && !rightsDisplay.equals(group.display())) {
			String said = rightsDisplay.strip();
			commands.add("lp group " + group.name() + " setdisplayname "
				+ (said.isBlank() ? group.name() : said));
		}
		if (rightsWeight != null && !rightsWeight.equals(String.valueOf(group.weight()))) {
			int weight;
			try {
				weight = Integer.parseInt(rightsWeight.strip());
			} catch (NumberFormatException notANumber) {
				rightsTrouble = Component.translatable(
					"npc_studio.server.rights.bad_weight").getString();
				rebuild();
				return;
			}
			commands.add("lp group " + group.name() + " setweight " + weight);
		}
		if (rightsPrefix != null && !rightsPrefix.equals(group.prefix())) {
			String said = rightsPrefix.strip();
			// A prefix is set at a priority, and the group's own weight is the
			// honest one to use: it is already the number that decides which of two
			// ranks a player wears, so the prefix follows the rank rather than
			// carrying a second, invisible ordering of its own.
			int priority = group.weight();
			if (rightsWeight != null) {
				try {
					priority = Integer.parseInt(rightsWeight.strip());
				} catch (NumberFormatException keepTheOldOne) {
					priority = group.weight();
				}
			}
			commands.add(said.isBlank()
				? "lp group " + group.name() + " meta removeprefix " + priority
				: "lp group " + group.name() + " meta setprefix " + priority + " " + said);
		}
		if (commands.isEmpty()) return;
		toRights(commands);
	}

	private void addNode(com.mopicmp.npcstudio.server.LuckPermsExport.Group group) {
		String node = nodeTyped.strip();
		if (!com.mopicmp.npcstudio.server.Privileges.validNode(node)) {
			rightsTrouble = Component.translatable("npc_studio.server.rights.bad_node").getString();
			rebuild();
			return;
		}
		nodeTyped = "";
		toRights(List.of("lp group " + group.name() + " permission set " + node + " true"));
	}

	private void openParents(com.mopicmp.npcstudio.server.LuckPermsExport.Group group,
			int x, int below) {
		List<Dropdown.Option> options = new ArrayList<>();
		for (var each : rights.groups()) {
			// Not itself, and not one it already has. A rank cannot inherit from
			// itself, and LuckPerms refuses — silently, like everything else.
			if (each.name().equals(group.name()) || group.parents().contains(each.name())) {
				continue;
			}
			options.add(new Dropdown.Option(each.name(), Component.literal(each.title())));
		}
		if (options.isEmpty()) {
			rightsTrouble = Component.translatable(
				"npc_studio.server.rights.nothing_to_inherit").getString();
			rebuild();
			return;
		}
		dropdown.open(x - 120, below, Pill.wide(Component.translatable(
			"npc_studio.server.rights.inherit_add")) + 120, height, options, "",
			picked -> toRights(List.of("lp group " + group.name() + " parent add " + picked)));
	}

	private void askNewGroup() {
		window = Window.PRIVILEGE;
		newGroup = "";
		rebuild();
	}

	private void askDropGroup(com.mopicmp.npcstudio.server.LuckPermsExport.Group group) {
		confirm.ask(
			Component.translatable("npc_studio.server.rights.drop_title"),
			Component.translatable("npc_studio.server.rights.drop_body", group.title()),
			Component.translatable("npc_studio.server.rights.drop"),
			Component.translatable("npc_studio.server.no_session.no"),
			() -> {
				rightsPicked = "";
				toRights(List.of("lp deletegroup " + group.name()));
			});
	}

	// ---- a server with no permission plugin

	/**
	 * The four levels, which is the whole of permissions without a plugin.
	 *
	 * Shown as what each level lets somebody do rather than as a number, because
	 * "level 3" is a number somebody has to go and look up. The descriptions are
	 * this window's reading of the game's own command tree and are labelled as
	 * such — they are not in any file and nothing about them can be measured.
	 */
	private void operatorWidgets(int top) {
		int x = contentLeft + PAD;
		int right = width - PAD;
		int bottom = height - PAD;
		rightsListTop = top;
		int y = top - rightsScroll;

		rightsRows.add(new RightsRow("npc_studio.server.rights.operators", null, "", -2, y));
		y += KIND_HEAD + 2;

		for (var op : operators) {
			if (inside(y, top, bottom, LIST_ROW)) {
				Component off = Component.translatable("npc_studio.server.players.deop");
				int at = right - Pill.wide(off);
				addRenderableWidget(new Pill(at, y, Icon.CANCEL, off, WARN,
					() -> setLevel(op.name(), op.id().toString(), 0)));
				Component level = Component.literal(Component.translatable(
					"npc_studio.server.rights.level." + op.level()).getString() + "  ▾");
				int wide = 200;
				at -= 6 + wide;
				int here = at;
				int line = y;
				addRenderableWidget(new FlatButton(at, y, wide, FIELD, level, ACCENT, () -> {
					List<Dropdown.Option> options = new ArrayList<>();
					for (int each : com.mopicmp.npcstudio.server.Privileges.LEVELS) {
						options.add(new Dropdown.Option(String.valueOf(each),
							Component.translatable("npc_studio.server.rights.level." + each)));
					}
					dropdown.open(here, line + FIELD, wide, height, options,
						String.valueOf(op.level()),
						picked -> setLevel(op.name(), op.id().toString(),
							Integer.parseInt(picked)));
				}));
			}
			permissionRows.add(new RightsRow(null, op.name(),
				op.id().toString(), op.level(), y));
			y += LIST_ROW;
		}
		if (operators.isEmpty()) {
			permissionRows.add(new RightsRow(null, null, Component.translatable(
				"npc_studio.server.rights.no_operators").getString(), -3, y));
			y += LIST_ROW;
		}
		y += 8;

		// ---- and the way to add one
		if (inside(y, top, bottom, KIND_HEAD)) {
			Component add = Component.translatable("npc_studio.server.players.list.add");
			int at = right - Pill.wide(add);
			addRenderableWidget(new Pill(at, y + 2, Icon.ADD, add, GOOD, this::addOperator));
			Component level = Component.literal(Component.translatable(
				"npc_studio.server.rights.level." + opLevel).getString() + "  ▾");
			int wide = 200;
			at -= 6 + wide;
			int here = at;
			int line = y + 2;
			addRenderableWidget(new FlatButton(at, y + 2, wide, FIELD, level, ACCENT, () -> {
				List<Dropdown.Option> options = new ArrayList<>();
				for (int each : com.mopicmp.npcstudio.server.Privileges.LEVELS) {
					options.add(new Dropdown.Option(String.valueOf(each),
						Component.translatable("npc_studio.server.rights.level." + each)));
				}
				dropdown.open(here, line + FIELD, wide, height, options,
					String.valueOf(opLevel), picked -> {
						opLevel = Integer.parseInt(picked);
						rebuild();
					});
			}));
			int nameWide = Math.min(180, at - 6 - x);
			if (nameWide >= 80) {
				Component asks = Component.translatable("npc_studio.server.players.list.add_white");
				EditBox box = Field.make(font, at - 6 - nameWide, y + 2, nameWide, FIELD, asks);
				box.setHint(asks);
				box.setMaxLength(16);
				box.setValue(opTyped);
				box.setResponder(typed -> opTyped = typed);
				addRenderableWidget(box);
				framed.add(new Framed(box, at - 6 - nameWide, y + 2, nameWide, FIELD));
				submits.put(box, this::addOperator);
			}
		}
		rightsRows.add(new RightsRow("npc_studio.server.rights.add_operator", null, "", -2, y));
		y += KIND_HEAD + 8;

		// ---- and what this server has not got
		rightsRows.add(new RightsRow(null, null, "no-plugin", -4, y));
		if (inside(y + 46, top, bottom, FIELD)) {
			Component get = Component.translatable("npc_studio.server.rights.get_plugin");
			addRenderableWidget(new Pill(x, y + 46, Icon.BROWSE, get, ACCENT, () -> {
				// Straight to the catalogue with the search already typed: the
				// answer to "there are no named ranks here" is one plugin away, and
				// making somebody find it themselves is the gap this window exists
				// to close.
				tab = Tab.CONTENT;
				half = Half.BROWSE;
				queryText = "LuckPerms";
				opened = null;
				search(true);
				rebuild();
			}));
		}
		y += 46 + FIELD;
		rightsHeight = Math.max(0, y + rightsScroll + PAD - bottom);
	}

	private void addOperator() {
		String name = opTyped.strip();
		if (!name.matches("[A-Za-z0-9_]{1,16}")) {
			rightsTrouble = Component.translatable(
				"npc_studio.server.players.list.bad_name").getString();
			rebuild();
			return;
		}
		opTyped = "";
		setLevel(name, "", opLevel);
	}

	private void setLevel(String name, String uuid, int level) {
		rightsTrouble = "";
		manager.toOperator(server, name, uuid, level, () -> {
			say(Component.translatable("npc_studio.server.players.done",
				Component.translatable(level <= 0 ? "npc_studio.server.players.deop"
					: "npc_studio.server.players.op").getString(), name).getString());
			operatorsRead = false;
			readRights();
			readPlayers();
			rebuild();
		}, said -> {
			rightsTrouble = said;
			say(said);
			rebuild();
		});
	}

	// ---- drawing

	private void drawRights(GuiGraphicsExtractor graphics) {
		int x = contentLeft + PAD;
		int right = width - PAD;
		int top = contentTop + 4;
		if (!rightsRead) {
			graphics.text(font, Component.translatable("npc_studio.server.rights.reading"),
				x, top + 26, TEXT_DIM);
			return;
		}
		graphics.fill(contentLeft, top + 13, width, top + 14, EDGE);
		String where = Component.translatable(withLuckPerms()
			? "npc_studio.server.rights.by_plugin" : "npc_studio.server.rights.by_levels")
			.getString();
		graphics.text(font, where, x, top + 18, TEXT_DIM);
		if (rightsReading) {
			String again = Component.translatable("npc_studio.server.rights.asking").getString();
			graphics.text(font, again, x + font.width(where) + 12, top + 18, ACCENT);
		}
		if (!rightsTrouble.isBlank()) {
			graphics.textWithWordWrap(font, Component.literal(rightsTrouble),
				x, top + 26, right - x, WARN);
		}

		if (withLuckPerms()) {
			// The left pane and its own strip of scroll.
			graphics.enableScissor(contentLeft, rightsListTop - 2, x + RIGHTS_LIST + 2, height);
			drawRightsRows(graphics, x, x + RIGHTS_LIST, true);
			graphics.disableScissor();
			graphics.fill(x + RIGHTS_LIST + 4, rightsListTop, x + RIGHTS_LIST + 5, height - PAD,
				EDGE);
			graphics.enableScissor(x + RIGHTS_LIST + 6, rightsTop - 2, width, height);
			drawRightsRows(graphics, x + RIGHTS_LIST + PAD, right, false);
			drawGroupCard(graphics, x + RIGHTS_LIST + PAD, right);
			graphics.disableScissor();
			scrollbar(graphics, rightsTop, height - PAD, rightsScroll, rightsHeight);
			return;
		}

		graphics.enableScissor(contentLeft, rightsListTop - 2, width, height - PAD);
		drawRightsRows(graphics, x, right, false);
		drawRightsRows(graphics, x, right, true);
		drawNoPlugin(graphics, x, right);
		graphics.disableScissor();
		scrollbar(graphics, rightsTop, height - PAD, rightsScroll, rightsHeight);
	}

	/** Headings and left-pane rows, or the rows of whichever list is on the right. */
	private void drawRightsRows(GuiGraphicsExtractor graphics, int x, int right, boolean headings) {
		for (RightsRow row : headings ? rightsRows : permissionRows) {
			if (row.heading() != null) {
				graphics.text(font, Component.translatable(row.heading()), x, row.y() + 7, ACCENT);
				graphics.fill(x, row.y() + KIND_HEAD - 4, right, row.y() + KIND_HEAD - 3, EDGE);
				continue;
			}
			if (row.weight() == -4) continue;
			if (row.name() == null) {
				graphics.textWithWordWrap(font, Component.literal(row.said()), x, row.y() + 4,
					Math.min(520, right - x), TEXT_DIM);
				continue;
			}
			if (row.weight() == -1) {
				// A ladder: its name, and the order it goes in.
				graphics.text(font, shorten(row.name(), right - x - 8), x + 4, row.y() + 2,
					TEXT);
				graphics.text(font, shorten(row.said(), right - x - 8), x + 4, row.y() + 12,
					TEXT_DIM);
				continue;
			}
			if (row.weight() == -2) {
				// An inherited rank, or a name in a plain row.
				graphics.fill(x, row.y(), right, row.y() + LIST_ROW - 4, CARD);
				graphics.text(font, row.name(), x + 8, row.y() + 5, TEXT);
				continue;
			}
			if (headings) {
				// A privilege in the left pane: its name, and the weight that
				// decides which of two of them wins.
				boolean on = row.name().equals(rightsPicked);
				graphics.text(font, shorten(row.said(), RIGHTS_LIST - 46), x + 8, row.y() + 6,
					on ? ACCENT : TEXT);
				String weight = String.valueOf(row.weight());
				graphics.text(font, weight, x + RIGHTS_LIST - 10 - font.width(weight),
					row.y() + 6, TEXT_DIM);
				continue;
			}
			// A permission, or an operator with their level.
			graphics.fill(x, row.y(), right, row.y() + LIST_ROW - 4, CARD);
			boolean granted = row.weight() != 0;
			int mark = x + 8;
			graphics.fill(mark, row.y() + 6, mark + 4, row.y() + 10, granted ? GOOD : WARN);
			graphics.text(font, shorten(row.name(), right - x - 300), x + 18, row.y() + 5,
				granted ? TEXT : TEXT_DIM);
			if (!row.said().isBlank()) {
				graphics.text(font, shorten(row.said(), 240), x + 18
					+ Math.min(font.width(row.name()), right - x - 300) + 12, row.y() + 5,
					TEXT_DIM);
			}
		}
	}

	private void drawGroupCard(GuiGraphicsExtractor graphics, int x, int right) {
		var group = pickedGroup();
		if (group == null) {
			graphics.textWithWordWrap(font,
				Component.translatable("npc_studio.server.rights.pick"),
				x, rightsTop + 6, Math.min(520, right - x), TEXT_DIM);
			return;
		}
		int y = rightsTop + 4 - rightsScroll;
		big(graphics, group.title(), x, y + 2, 1.2f, TEXT);
		if (!group.display().isBlank()) {
			graphics.text(font, group.name(), x + (int) (font.width(group.title()) * 1.2f) + 8,
				y + 6, TEXT_DIM);
		}
		y += 26;
		label(graphics, "npc_studio.server.rights.display", x, y);
		int across = Math.max(90, (right - x - 12) / 3);
		label(graphics, "npc_studio.server.rights.weight", x + across + 6, y);
		label(graphics, "npc_studio.server.rights.prefix", x + (across + 6) * 2, y);
	}

	private void drawNoPlugin(GuiGraphicsExtractor graphics, int x, int right) {
		for (RightsRow row : rightsRows) {
			if (row.weight() != -4) continue;
			graphics.textWithWordWrap(font,
				Component.translatable("npc_studio.server.rights.no_plugin"),
				x, row.y(), Math.min(560, right - x), TEXT_DIM);
		}
	}

	// ----------------------------------------------------------------- configs

	/**
	 * The configuration of everything installed, edited without rewriting it.
	 *
	 * Two lists side by side: the files on the left, grouped by what owns them,
	 * and the chosen file's settings on the right. The interesting work is not
	 * here — it is in {@link com.mopicmp.npcstudio.server.ConfigFile}, which reads
	 * a file into values with their exact place in the text and changes nothing
	 * else when one is written back. What this does is show them.
	 *
	 * <p>The label on a row is the comment that was written above the key. In these
	 * formats that comment is the entire documentation: nobody writes a schema for
	 * a plugin nobody has heard of, but everybody writes a line above the setting
	 * saying what it does.
	 */
	private List<com.mopicmp.npcstudio.server.Configs.Group> configGroups = List.of();
	private boolean configsRead;
	private String configPicked = "";
	private com.mopicmp.npcstudio.server.ConfigFile openConfig;
	private boolean configLoading;
	private String configTrouble = "";
	private final Map<String, String> configEdits = new LinkedHashMap<>();
	private String configFind = "";
	private int configListScroll;
	private int configListHeight;
	private int configScroll;
	private int configHeight;
	private int configListTop;
	private int configTop;

	/** How wide the list of files is, and how tall each of its rows. */
	private static final int CONFIG_LIST = 210;
	private static final int CONFIG_ROW = 22;

	/** A setting takes its name, its comment and a control beside them. */
	private static final int CONFIG_SETTING = 34;

	/** How wide the control at the right of a setting is. */
	private static final int CONFIG_CONTROL = 150;

	/**
	 * Where each row of either list ended up.
	 *
	 * Worked out once, in the pass that places the widgets, and read by the pass
	 * that draws. Every list in this screen that computed its rows twice has drifted
	 * apart sooner or later, and a list whose drawing and whose buttons disagree
	 * gives somebody the row below the one they aimed at.
	 */
	private record ConfigRow(String heading, String note,
			com.mopicmp.npcstudio.server.Configs.Entry file, int y) {
	}

	private record SettingRow(String heading, com.mopicmp.npcstudio.server.ConfigFile.Setting
			setting, int y) {
	}

	private final List<ConfigRow> configRows = new ArrayList<>();
	private final List<SettingRow> settingRows = new ArrayList<>();

	private void readConfigs() {
		ManagedServer which = server;
		configsRead = false;
		// The installed list first, when nobody has asked for it yet. Without it
		// every mod's config falls under "the rest": a file named after a mod is
		// only that mod's if there is a mod of that name to compare it with.
		if (!addonsRead) {
			manager.addons(which, found -> {
				if (server != which) return;
				addons = found;
				addonsRead = true;
				if (tab == Tab.CONFIGS) readConfigs();
			});
			return;
		}
		manager.configs(which, addons, found -> {
			if (server != which) return;
			configGroups = found;
			configsRead = true;
			// The first file opens itself. A tab that opens on an empty half looks
			// broken, and there is always a first file worth showing: the core's own.
			if (configPicked.isBlank() && !found.isEmpty() && !found.getFirst().files().isEmpty()) {
				openConfigFile(found.getFirst().files().getFirst());
				return;
			}
			rebuild();
		});
	}

	private void openConfigFile(com.mopicmp.npcstudio.server.Configs.Entry entry) {
		configPicked = entry.name();
		configEdits.clear();
		configScroll = 0;
		configTrouble = "";
		configLoading = true;
		openConfig = null;
		ManagedServer which = server;
		String wanted = entry.name();
		manager.readConfig(entry.path(), file -> {
			if (server != which || !configPicked.equals(wanted)) return;
			openConfig = file;
			configLoading = false;
			rebuild();
		}, said -> {
			if (server != which || !configPicked.equals(wanted)) return;
			configLoading = false;
			configTrouble = said;
			rebuild();
		});
		rebuild();
	}

	/**
	 * The whole file, open in a text box.
	 *
	 * The other half of this tab and not a lesser one: rows of fields cover what a
	 * value <em>is</em>, and a config also has lists, blocks, keys that are not
	 * there yet and formats this does not take apart at all. Every one of those
	 * ends in "edit the file", and until now that meant leaving the game.
	 *
	 * <p>What it edits is the file as it stands on disk, read again when it opens.
	 * Not the copy the rows were built from: those two can differ by everything the
	 * server has written since.
	 */
	private TextEditor configText;
	private String configTextWas = "";
	/**
	 * What is in the box now, kept outside it.
	 *
	 * The screen rebuilds itself for every press, and a rebuilt box is a new box:
	 * without this, changing the size of the window — or any other thing that
	 * causes a rebuild — would put the file back as it was on disk and throw away
	 * everything somebody had typed.
	 */
	private String configTextNow = "";
	private boolean configTextOpen;

	/**
	 * Which file is open in it, what to call it, and which tab it was opened from.
	 *
	 * Held here rather than asked of the configs list every time, because the
	 * editor is no longer that list's own: the settings tab opens
	 * {@code server.properties} in it, and there is no row in the configs list for
	 * that file — it is the one file this window has always had a tab of its own
	 * for.
	 */
	private java.nio.file.Path editingPath;
	private String editingName = "";
	private Tab editingFrom = Tab.CONFIGS;
	private com.mopicmp.npcstudio.server.ConfigFile.Format editingFormat =
		com.mopicmp.npcstudio.server.ConfigFile.Format.PLAIN;

	private void openConfigText() {
		var entry = pickedEntry();
		if (entry == null) return;
		editFile(entry.path(), entry.name(), Tab.CONFIGS);
	}

	/**
	 * Open any file of this server in the editor, from wherever the press came.
	 *
	 * The file on disk, read again as it opens — not the copy the rows above were
	 * built from. Those two differ by everything the server has written since.
	 */
	private void editFile(java.nio.file.Path path, String name, Tab from) {
		editingPath = path;
		editingName = name;
		editingFrom = from;
		configTextOpen = true;
		configTextWas = "";
		configTextNow = "";
		configTrouble = "";
		configLoading = true;
		ManagedServer which = server;
		manager.readConfig(path, file -> {
			if (server != which || !configTextOpen || !path.equals(editingPath)) return;
			editingFormat = file.format();
			// Only the configs tab keeps the taken-apart file: it is what that tab's
			// rows are made of, and overwriting it from here would leave those rows
			// describing a file nobody had chosen.
			if (from == Tab.CONFIGS) openConfig = file;
			configTextWas = file.text();
			configTextNow = file.text();
			configLoading = false;
			rebuild();
		}, said -> {
			if (server != which || !configTextOpen || !path.equals(editingPath)) return;
			configLoading = false;
			configTrouble = said;
			rebuild();
		});
		rebuild();
	}

	private void closeConfigText() {
		configTextOpen = false;
		configText = null;
		editingPath = null;
		rebuild();
	}

	/**
	 * Leave the editor, asking first if there is anything to lose.
	 *
	 * A text editor is the one place in this screen where somebody can have half an
	 * hour of work in front of them that exists nowhere else. Leaving it by the
	 * button or by Escape must not be able to end that quietly.
	 */
	private void leaveConfigText() {
		if (configLoading || configTextNow.equals(configTextWas)) {
			closeConfigText();
			return;
		}
		confirm.ask(
			Component.translatable("npc_studio.server.configs.drop_title"),
			Component.translatable("npc_studio.server.configs.drop_body", editingName),
			Component.translatable("npc_studio.server.configs.drop_yes"),
			Component.translatable("npc_studio.server.no_session.no"),
			this::closeConfigText);
	}

	/**
	 * Write the whole file as it stands in the box.
	 *
	 * This one really does rewrite it — that is what a text editor is — so the
	 * comparison is made first and a file nobody changed is not written at all. A
	 * save that rewrites an untouched file is a modification time somebody will
	 * later use to work out when something broke.
	 */
	private void keepConfigText() {
		if (editingPath == null || configText == null) return;
		String written = configTextNow;
		if (written.equals(configTextWas)) {
			closeConfigText();
			return;
		}
		java.nio.file.Path path = editingPath;
		Tab from = editingFrom;
		manager.writeConfig(path, written, () -> {
			say(Component.translatable("npc_studio.server.configs.saved",
				path.getFileName().toString()).getString());
			configTextOpen = false;
			configText = null;
			editingPath = null;
			if (from == Tab.CONFIGS) {
				configEdits.clear();
				var entry = pickedEntry();
				if (entry != null) {
					openConfigFile(entry);
					return;
				}
			} else {
				// Every field on the settings tab came out of the file that has just
				// been rewritten by hand. Read again rather than left describing what
				// it used to say — and anything half-typed in those fields goes with
				// it, because the file now says something the fields never knew about.
				edits.clear();
				lastEdit = 0;
				propertiesRead = false;
				readProperties();
			}
			rebuild();
		}, said -> {
			configTrouble = said;
			say(said);
			rebuild();
		});
	}

	/**
	 * The editor's own furniture: a title, a box, a foot with the buttons in it.
	 *
	 * Laid out as a window rather than as a text field filling the panel. The first
	 * form was the box and two buttons floating above it, which reads as a debug
	 * screen — nothing said which file was open except a grey word in the far
	 * corner, nothing said what would happen to it, and the two buttons that write
	 * and leave sat where the eye starts rather than where a decision is made.
	 */
	private static final int EDITOR_HEAD = 24;
	private static final int EDITOR_FOOT = 30;

	/**
	 * The two buttons whose answer changes without anything being placed again.
	 *
	 * A widget's {@code active} is read when it is pressed and set when it is made,
	 * and between those two moments somebody types. Both are refreshed in the pass
	 * that draws, which happens every frame — see the note where they are made.
	 */
	private Pill configWrite;
	private Pill configSave;
	private Pill configEdit;

	private void configTextWidgets(int top, int bottom) {
		int x = contentLeft + PAD;
		int right = width - PAD;
		int footY = bottom - EDITOR_FOOT + 6;

		// At the foot and to the right, where a decision is made rather than where
		// reading starts, and the one that writes is the coloured one.
		Component keep = Component.translatable("npc_studio.server.configs.write");
		// Kept hold of, because whether it may be pressed changes while nothing is
		// being placed. It used to be decided here, once, when the file had just
		// been opened and nothing was typed yet — so the button was dead for the
		// whole session and pressing it did nothing at all, while the words in the
		// corner, drawn every frame, said there was something to write.
		configWrite = new Pill(right - Pill.wide(keep), footY, Icon.SAVE, keep, GOOD,
			this::keepConfigText);
		addRenderableWidget(configWrite);

		Component back = Component.translatable("npc_studio.server.configs.back");
		addRenderableWidget(new Pill(right - Pill.wide(keep) - 6 - Pill.wide(back), footY,
			Icon.REWIND, back, TEXT_DIM, this::leaveConfigText));

		if (configLoading) return;
		int y = top + EDITOR_HEAD + 4;
		TextEditor.Kind kind = switch (editingFormat) {
			case YAML -> TextEditor.Kind.YAML;
			case PROPERTIES -> TextEditor.Kind.PROPERTIES;
			case PLAIN -> TextEditor.Kind.PLAIN;
		};
		int across = right - x - 2;
		int tall = Math.max(60, bottom - EDITOR_FOOT - y - 2);
		// The same editor across a rebuild, moved rather than made again: a new one
		// starts at line one with nothing chosen, and the screen rebuilds itself
		// every time anything is pressed.
		if (configText == null || !configText.getValue().equals(configTextNow)) {
			configText = new TextEditor(font, x + 1, y + 1, across, tall, kind,
				typed -> configTextNow = typed);
			configText.setValue(configTextNow);
		} else {
			configText.setX(x + 1);
			configText.setY(y + 1);
			configText.setSize(across, tall);
		}
		addRenderableWidget(configText);
		setFocused(configText);
	}

	private void configWidgets() {
		if (!configsRead) {
			readConfigs();
			return;
		}
		configRows.clear();
		settingRows.clear();

		int x = contentLeft + PAD;
		int right = width - PAD;
		int top = contentTop + 4;
		int bottom = height - PAD;

		// ---- the strip along the top: where the file is, and what to do with it
		var entry = pickedEntry();
		Component folder = Component.translatable("npc_studio.server.folder");
		Pill open = new Pill(right - Pill.wide(folder), top, Icon.FOLDER, folder, TEXT_DIM,
			() -> {
				var which = pickedEntry();
				if (which != null && which.path().getParent() != null) {
					net.minecraft.util.Util.getPlatform().openPath(which.path().getParent());
				}
			});
		open.active = entry != null;
		addRenderableWidget(open);

		// The way into the file itself, beside the way into its folder. It is the
		// only way to reach a list, a wrapped value or a format this does not take
		// apart — and every one of those otherwise ended in "leave the game".
		Component text = Component.translatable("npc_studio.server.configs.edit");
		int textAt = right - Pill.wide(folder) - 6 - Pill.wide(text);
		configEdit = new Pill(textAt, top, Icon.FILE, text, ACCENT, this::openConfigText);
		configEdit.active = entry != null;
		addRenderableWidget(configEdit);

		// Without the number in it: the label is fixed when the button is made, and
		// the count changes with every field somebody touches. The count is drawn
		// beside it instead, every frame, where it is always true.
		Component keep = Component.translatable("npc_studio.server.configs.save");
		int keepAt = textAt - 6 - Pill.wide(keep);
		configSave = new Pill(keepAt, top, Icon.SAVE, keep, GOOD, this::saveConfig);
		addRenderableWidget(configSave);

		// The filter, which a file of eighty-nine settings needs and a file of four
		// does not — so it is only there when there is something to sift.
		if (openConfig != null && openConfig.settings().size() > FIND_FROM) {
			int findWide = Math.min(220, keepAt - (x + CONFIG_LIST + PAD) - 8);
			if (findWide > 80) {
				EditBox find = Field.make(font, x + CONFIG_LIST + PAD, top, findWide, FIELD,
					Component.translatable("npc_studio.server.configs.find"));
				find.setMaxLength(60);
				find.setValue(configFind);
				find.setResponder(typed -> {
					configFind = typed;
					configScroll = 0;
					rebuild();
				});
				addRenderableWidget(find);
				framed.add(new Framed(find, x + CONFIG_LIST + PAD, top, findWide, FIELD));
			}
		}

		// ---- the files, on the left
		int y = top + FIELD + 8;
		configListTop = y;
		// The settings start lower while the server runs, because a line above them
		// says when the change will reach it. Room made for it rather than the line
		// written over the first row: "saved and nothing happened" is the commonest
		// way a settings screen wastes an afternoon, and the answer belongs before
		// the change and not after it.
		configTop = y + (manager.running() ? 13 : 0);
		y -= configListScroll;
		for (var group : configGroups) {
			if (!group.name().isBlank() || group.id().equals("rest")) {
				String heading = group.name().isBlank()
					? Component.translatable("npc_studio.server.configs.rest").getString()
					: group.name();
				configRows.add(new ConfigRow(heading, null, null, y));
				y += KIND_HEAD;
			} else if (group.id().equals("core")) {
				configRows.add(new ConfigRow(Component.translatable(
					"npc_studio.server.configs.core").getString(), null, null, y));
				y += KIND_HEAD;
			}
			// Named with nothing under it, and told why. A plugin writes its
			// settings the first time it runs, so one installed a minute ago has
			// none — and a heading with an empty space under it is the question
			// this line answers before anybody has to ask it.
			if (group.files().isEmpty()) {
				configRows.add(new ConfigRow(null, Component.translatable(
					"npc_studio.server.configs.not_yet").getString(), null, y));
				y += CONFIG_ROW;
			}
			for (var file : group.files()) {
				configRows.add(new ConfigRow(null, null, file, y));
				if (y >= configListTop && y + CONFIG_ROW <= bottom) {
					boolean here = file.name().equals(configPicked);
					addRenderableWidget(new FileRow(x, y, CONFIG_LIST, file, here,
						() -> openConfigFile(file)));
				}
				y += CONFIG_ROW;
			}
		}
		configListHeight = Math.max(0, y + configListScroll + PAD - bottom);

		// ---- the settings of the one that is open, on the right
		int at = x + CONFIG_LIST + PAD;
		int across = right - at;
		if (openConfig == null || across < 200) return;
		int sy = configTop - configScroll;
		String was = null;
		for (var setting : showingSettings()) {
			String group = setting.group();
			if (!group.equals(was)) {
				was = group;
				if (!group.isBlank()) {
					settingRows.add(new SettingRow(group, null, sy));
					sy += KIND_HEAD;
				}
			}
			settingRows.add(new SettingRow(null, setting, sy));
			if (sy >= configTop && sy + CONFIG_SETTING - 4 <= bottom) {
				// Inside the card and halfway down it. Both were wrong at first: the
				// control was hard against the right edge, where it read as hanging
				// off the row, and eight pixels down a thirty-pixel row, which is
				// two pixels of air below and six above.
				settingControl(setting, right - 8 - CONFIG_CONTROL,
					sy + (CONFIG_SETTING - 4 - FIELD) / 2, CONFIG_CONTROL);
			}
			sy += CONFIG_SETTING;
		}
		configHeight = Math.max(0, sy + configScroll + PAD - bottom);
	}

	/** Past this many settings a file is something to search rather than to read. */
	private static final int FIND_FROM = 12;

	private com.mopicmp.npcstudio.server.Configs.Entry pickedEntry() {
		for (var group : configGroups) {
			for (var file : group.files()) {
				if (file.name().equals(configPicked)) return file;
			}
		}
		return null;
	}

	/** The settings of the open file, filtered by what was typed. */
	private List<com.mopicmp.npcstudio.server.ConfigFile.Setting> showingSettings() {
		if (openConfig == null) return List.of();
		if (configFind.isBlank()) return openConfig.settings();
		String looking = configFind.toLowerCase(java.util.Locale.ROOT);
		List<com.mopicmp.npcstudio.server.ConfigFile.Setting> kept = new ArrayList<>();
		for (var setting : openConfig.settings()) {
			if (setting.path().toLowerCase(java.util.Locale.ROOT).contains(looking)
				|| setting.comment().toLowerCase(java.util.Locale.ROOT).contains(looking)) {
				kept.add(setting);
			}
		}
		return kept;
	}

	private String configValue(com.mopicmp.npcstudio.server.ConfigFile.Setting setting) {
		return configEdits.getOrDefault(setting.path(), setting.value());
	}

	/**
	 * The control for one setting, chosen by what the value looks like.
	 *
	 * A switch for true and false, a field for everything else, and nothing at all
	 * for a list — which is honest: a list is edited in the file, and a control
	 * that pretended otherwise would be a control that lost somebody's entries.
	 */
	private void settingControl(com.mopicmp.npcstudio.server.ConfigFile.Setting setting,
			int x, int y, int across) {
		String value = configValue(setting);
		switch (setting.sort()) {
			case FLAG -> {
				boolean on = value.equalsIgnoreCase("true");
				Component label = Component.translatable(on
					? "npc_studio.server.configs.on" : "npc_studio.server.configs.off");
				addRenderableWidget(new Pill(x + across - Pill.wide(label), y,
					on ? Icon.CHECK : Icon.CANCEL, label, on ? GOOD : TEXT_DIM, () -> {
						changeConfig(setting, on ? "false" : "true");
						rebuild();
					}));
			}
			case LIST, BLOCK -> {
				// Nothing to press. The row still says what it is, in the drawing.
			}
			default -> {
				EditBox box = Field.make(font, x, y, across, FIELD,
					Component.literal(setting.name()));
				box.setMaxLength(512);
				box.setValue(value);
				box.setResponder(typed -> changeConfig(setting, typed));
				addRenderableWidget(box);
				framed.add(new Framed(box, x, y, across, FIELD));
			}
		}
	}

	/**
	 * Hold a change until it is saved, and forget it when it is put back.
	 *
	 * Kept rather than written on the spot, unlike the server's own settings: those
	 * are a known list of keys with known limits, and these are somebody else's
	 * file, where a value half typed is a plugin that will not start. So the saving
	 * is a press, and the press says how many changes it is about to make.
	 */
	private void changeConfig(com.mopicmp.npcstudio.server.ConfigFile.Setting setting,
			String value) {
		if (value.equals(setting.value())) configEdits.remove(setting.path());
		else configEdits.put(setting.path(), value);
	}

	private void saveConfig() {
		var entry = pickedEntry();
		if (entry == null || configEdits.isEmpty()) return;
		Map<String, String> writing = new LinkedHashMap<>(configEdits);
		configTrouble = "";
		manager.saveConfig(entry.path(), writing, () -> {
			say(Component.translatable("npc_studio.server.configs.saved",
				entry.path().getFileName().toString()).getString());
			configEdits.clear();
			openConfigFile(entry);
		}, said -> {
			configTrouble = said;
			say(said);
			rebuild();
		});
	}

	/**
	 * A file in the list on the left.
	 *
	 * Its own widget rather than a drawn row with a hit test beside it: the row is
	 * a button, and everything a button needs to know — where it is, whether it is
	 * the open one, whether the mouse is on it — belongs in one place.
	 */
	private final class FileRow extends AbstractWidget {

		private final com.mopicmp.npcstudio.server.Configs.Entry file;
		private final boolean here;
		private final Runnable onPress;

		FileRow(int x, int y, int across,
				com.mopicmp.npcstudio.server.Configs.Entry file, boolean here, Runnable onPress) {
			super(x, y, across, CONFIG_ROW - 2, Component.literal(file.name()));
			this.file = file;
			this.here = here;
			this.onPress = onPress;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor graphics,
				int mouseX, int mouseY, float delta) {
			if (here || isHovered()) {
				graphics.fill(getX(), getY(), getX() + width, getY() + height,
					here ? 0xFF232B36 : 0xFF1B2028);
				graphics.fill(getX(), getY(), getX() + 1, getY() + height, here ? ACCENT : EDGE);
			}
			// The file's own name, not the path: the path is how it is found, the
			// name is how it is spoken about. The folder above it is the heading.
			String name = file.path().getFileName().toString();
			graphics.text(font, shorten(name, width - 12), getX() + 8, getY() + 5,
				here ? TEXT : TEXT_DIM);
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			onPress.run();
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			if (pressing(event)) {
				onPress.run();
				return true;
			}
			return super.keyPressed(event);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}

	private void drawConfigs(GuiGraphicsExtractor graphics) {
		int x = contentLeft + PAD;
		int right = width - PAD;
		int top = contentTop + 4;
		int bottom = height - PAD;
		if (!configsRead) {
			graphics.text(font, Component.translatable("npc_studio.server.configs.reading"),
				x, top + 4, TEXT_DIM);
			return;
		}
		if (configGroups.isEmpty()) {
			graphics.textWithWordWrap(font,
				Component.translatable("npc_studio.server.configs.none"),
				x, top + 4, Math.min(520, right - x), TEXT_DIM);
			return;
		}
		// The same refreshing as in the editor, for the same reason: somebody types
		// in a field and nothing is placed again until they press something.
		if (configEdit != null && pickedEntry() != null) {
			// Not while there are field changes waiting: opening the file would
			// show what is on disk and quietly leave them behind.
			configEdit.active = configEdits.isEmpty();
		}
		if (configSave != null) {
			configSave.active = !configEdits.isEmpty();
			if (!configEdits.isEmpty()) {
				String count = String.valueOf(configEdits.size());
				graphics.text(font, count, configSave.getX() - 4 - font.width(count),
					configSave.getY() + 5, GOOD);
			}
		}

		// ---- the headings between the files. The rows draw themselves.
		graphics.enableScissor(contentLeft, configListTop - 2, x + CONFIG_LIST + 2, height);
		for (ConfigRow row : configRows) {
			if (row.note() != null) {
				graphics.text(font, shorten(row.note(), CONFIG_LIST - 10), x + 8, row.y() + 5,
					TEXT_DIM);
				continue;
			}
			if (row.heading() == null) continue;
			graphics.text(font, shorten(row.heading(), CONFIG_LIST - 8), x, row.y() + 8, ACCENT);
			graphics.fill(x, row.y() + KIND_HEAD - 4, x + CONFIG_LIST,
				row.y() + KIND_HEAD - 3, EDGE);
		}
		graphics.disableScissor();

		// A mark saying this list goes on below. Without it a server with a few
		// plugins looks like a server whose later plugins have no settings at all —
		// the list simply ended at the bottom of the window with nothing to say it
		// had not. Not the draggable bar: that one belongs to the settings, which is
		// the list somebody spends their time in.
		if (configListHeight > 0) {
			int span = bottom - configListTop;
			int thumb = Math.max(18, span * span / (span + configListHeight));
			int at = configListTop + (span - thumb)
				* Math.clamp(configListScroll, 0, configListHeight) / configListHeight;
			int bar = x + CONFIG_LIST - 2;
			graphics.fill(bar, configListTop, bar + 2, bottom, 0xFF0E1116);
			graphics.fill(bar, at, bar + 2, at + thumb, 0xFF3A424D);
		}

		int at = x + CONFIG_LIST + PAD;
		if (right - at < 200) return;
		graphics.fill(at - PAD / 2, configListTop - 2, at - PAD / 2 + 1, bottom, EDGE);
		if (manager.running()) {
			graphics.text(font, shorten(Component.translatable(
				"npc_studio.server.configs.running").getString(), right - at),
				at, configListTop + 1, WARN);
		}

		if (configLoading) {
			graphics.text(font, Component.translatable("npc_studio.server.configs.reading"),
				at, configTop + 4, TEXT_DIM);
			return;
		}
		if (!configTrouble.isBlank()) {
			graphics.textWithWordWrap(font, Component.literal(configTrouble), at, configTop + 4,
				right - at, WARN);
			return;
		}
		if (openConfig == null) return;

		// A file this cannot take apart is not a failure and is not hidden: it is
		// said plainly, with the way to edit it right there.
		if (openConfig.settings().isEmpty()) {
			graphics.textWithWordWrap(font, Component.translatable(
				openConfig.format() == com.mopicmp.npcstudio.server.ConfigFile.Format.PLAIN
					? "npc_studio.server.configs.plain" : "npc_studio.server.configs.unreadable"),
				at, configTop + 4, right - at, TEXT_DIM);
			return;
		}

		graphics.enableScissor(at - 2, configTop - 2, width, height);
		for (SettingRow row : settingRows) {
			int y = row.y();
			if (y + CONFIG_SETTING < configTop - CONFIG_SETTING || y > bottom) continue;
			if (row.heading() != null) {
				graphics.text(font, shorten(row.heading(), right - at), at, y + 8, ACCENT);
				graphics.fill(at, y + KIND_HEAD - 4, right, y + KIND_HEAD - 3, EDGE);
				continue;
			}
			var setting = row.setting();
			boolean changed = configEdits.containsKey(setting.path());
			card(graphics, at, y, right - at, CONFIG_SETTING - 4);
			if (changed) graphics.fill(at, y, at + 1, y + CONFIG_SETTING - 4, GOOD);
			int room = right - at - CONFIG_CONTROL - 24;
			graphics.text(font, shorten(setting.name(), room), at + 8, y + 5,
				changed ? GOOD : TEXT);
			// The comment underneath, which is what this format has instead of a
			// name a person would have chosen.
			String said = setting.comment();
			if (said.isBlank()) {
				said = switch (setting.sort()) {
					case LIST -> Component.translatable("npc_studio.server.configs.list").getString();
					// The value itself, since there is nothing written about it and
					// it is the only thing the row could otherwise say.
					case BLOCK -> Component.translatable("npc_studio.server.configs.block")
						.getString() + " · " + setting.value();
					default -> setting.value();
				};
			}
			graphics.text(font, shorten(said, room), at + 8, y + 17, TEXT_DIM);
			if (!setting.sort().editable()) {
				String mark = Component.translatable("npc_studio.server.configs.in_file")
					.getString();
				graphics.text(font, mark, right - 8 - font.width(mark), y + 11, TEXT_DIM);
			}
		}
		graphics.disableScissor();
		scrollbar(graphics, configTop, bottom, configScroll, configHeight);
	}

	/**
	 * The editor as a window: a titled head, a framed box, a foot that says the rest.
	 *
	 * What each part is for. The head names the file — its own name large, the
	 * folder it sits in beside it small — because in the editor the list it was
	 * chosen from is gone and "which file am I in" must not be a question. The
	 * frame turns a field into a document. The foot says what saving will do,
	 * which is the thing this screen must never leave anybody guessing about:
	 * whether the server will read the change now or at its next start.
	 */
	private void drawConfigText(GuiGraphicsExtractor graphics, int top, int bottom) {
		int x = contentLeft + PAD;
		int right = width - PAD;
		int footY = bottom - EDITOR_FOOT + 6;

		// ---- the head
		String name = editingName;
		String folder = "";
		int slash = name.lastIndexOf('/');
		if (slash > 0) {
			folder = name.substring(0, slash);
			name = name.substring(slash + 1);
		}
		big(graphics, name, x, top + 4, 1.2f, TEXT);
		int after = x + (int) (font.width(name) * 1.2f) + 8;
		if (!folder.isBlank()) {
			graphics.text(font, shorten(folder, right - after - 90), after, top + 8, TEXT_DIM);
		}
		// The same corner says one of two things: what is waiting to be written, or
		// how to write it. Never both, and never nothing.
		boolean changed = !configTextNow.equals(configTextWas);
		if (configWrite != null) configWrite.active = changed && !configLoading;
		String mark = Component.translatable(changed
			? "npc_studio.server.configs.changed" : "npc_studio.server.configs.hint").getString();
		graphics.text(font, mark, right - font.width(mark), top + 8, changed ? GOOD : TEXT_DIM);
		graphics.fill(x, top + EDITOR_HEAD - 4, right, top + EDITOR_HEAD - 3, EDGE);

		// ---- the document
		int boxTop = top + EDITOR_HEAD + 4;
		int boxBottom = bottom - EDITOR_FOOT;
		graphics.fill(x, boxTop, right, boxBottom, 0xFF0C0F13);
		graphics.fill(x, boxTop, right, boxTop + 1, EDGE);
		graphics.fill(x, boxBottom - 1, right, boxBottom, EDGE);
		graphics.fill(x, boxTop, x + 1, boxBottom, EDGE);
		graphics.fill(right - 1, boxTop, right, boxBottom, EDGE);

		if (configLoading) {
			graphics.text(font, Component.translatable("npc_studio.server.configs.reading"),
				x + 8, boxTop + 8, TEXT_DIM);
		}
		if (!configTrouble.isBlank()) {
			graphics.textWithWordWrap(font, Component.literal(configTrouble),
				x + 8, boxTop + 8, right - x - 16, WARN);
		}

		// ---- the foot
		graphics.text(font, Component.translatable(manager.running()
			? "npc_studio.server.configs.running" : "npc_studio.server.configs.at_start"),
			x, footY + 5, manager.running() ? WARN : TEXT_DIM);
		// Where in the file the caret is, in the same words an error message about
		// one of these files uses. Between the note and the buttons, which is the
		// only empty part of the foot.
		if (configText != null && !configLoading) {
			String where = Component.translatable("npc_studio.server.configs.line",
				configText.caretAt(), configText.lineCount()).getString();
			int buttons = right - Pill.wide(Component.translatable("npc_studio.server.configs.write"))
				- 6 - Pill.wide(Component.translatable("npc_studio.server.configs.back"));
			int at = buttons - 10 - font.width(where);
			if (at > x + font.width(Component.translatable(manager.running()
				? "npc_studio.server.configs.running"
				: "npc_studio.server.configs.at_start").getString()) + 12) {
				graphics.text(font, where, at, footY + 5, TEXT_DIM);
			}
		}
	}

	private double parse(String value, ServerSettings setting) {
		try {
			return Double.parseDouble(value.trim());
		} catch (NumberFormatException notANumber) {
			return Double.parseDouble(setting.fallback());
		}
	}

	private String current(ServerSettings setting) {
		return edits.getOrDefault(setting.key(),
			values.getOrDefault(setting.key(), setting.fallback()));
	}

	// ------------------------------------------------------------------ saving

	private void touched() {
		lastEdit = net.minecraft.util.Util.getMillis();
	}

	private void say(String what) {
		note = what == null ? "" : what;
		saidAt = net.minecraft.util.Util.getMillis();
	}

	private void applyMemory() {
		if (memoryEdit > 0) server.memoryMb = memoryEdit;
	}

	/**
	 * Write what has settled.
	 *
	 * There is no save button, so this is the whole of saving: a short pause
	 * after the last change, then whatever is valid goes to the file. Values that
	 * are not valid stay in their boxes and are not written — a half-typed number
	 * is not a decision.
	 */
	private void saveSettled() {
		if (server == null) return;
		ManagedServer which = server;
		Map<String, String> writing = new LinkedHashMap<>();
		String motdEdit = edits.get("motd");
		if (motdEdit != null && !motdEdit.equals(values.get("motd")) && motdEdit.length() <= 200) {
			writing.put("motd", motdEdit);
		}
		for (ServerSettings setting : ServerSettings.ALL) {
			String value = edits.get(setting.key());
			if (value != null && setting.allows(value)
				&& !value.equals(values.get(setting.key()))) {
				writing.put(setting.key(), value);
			}
		}
		boolean renaming = nameEdit != null && !nameEdit.isBlank()
			&& !nameEdit.equals(which.name);
		boolean remembering = memoryEdit > 0 && memoryEdit != which.memoryMb;
		if (writing.isEmpty() && !renaming && !remembering) return;

		String wantedName = nameEdit;
		int wantedMemory = memoryEdit;
		manager.onFiles(() -> {
			try {
				if (!writing.isEmpty()) {
					PropertiesFile file = java.nio.file.Files.exists(which.propertiesPath())
						? PropertiesFile.read(which.propertiesPath())
						: PropertiesFile.empty();
					writing.forEach(file::set);
					file.write(which.propertiesPath());
				}
				if (renaming) which.name = wantedName;
				if (remembering) which.memoryMb = wantedMemory;
				if (renaming || remembering) manager.store().save();
			} catch (java.io.IOException unwritable) {
				throw new RuntimeException(unwritable.getMessage(), unwritable);
			}
		}, () -> {
			if (server != which) return;
			values.putAll(writing);
			say(Component.translatable(manager.running() && !writing.isEmpty()
				? "npc_studio.server.saved_restart"
				: "npc_studio.server.saved").getString());
		}, this::say);
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		// Kept, because the lists are drawn by methods a long way down from here
		// and a row that lights up under the mouse needs to know where it is.
		pointerX = mouseX;
		pointerY = mouseY;
		graphics.fill(0, 0, width, height, CANVAS);
		graphics.fill(0, 0, width, TOP - 4, BAR);
		if (server == null) return;

		String name = server.name.isBlank() ? server.id : server.name;
		graphics.text(font, name, width / 2 - font.width(name) / 2, 5, TEXT);
		if (manager.makingWorld()) drawMaking(graphics, name);

		graphics.fill(0, TOP - 4, left, height, PANEL);
		graphics.fill(left, TOP - 4, left + 1, height, EDGE);
		// The strip the tabs sit in, so they read as one control on a shelf. The
		// tabs fill it exactly, top and bottom, which is what makes them tabs
		// rather than buttons floating in a bar.
		graphics.fill(contentLeft, TOP - 4, width, TOP + TABS - 2, BAR);
		graphics.fill(contentLeft, TOP + TABS - 3, width, TOP + TABS - 2, EDGE);
		// And a second strip under it for the halves, when a tab has them.
		if (tab == Tab.CONTENT || tab == Tab.WORLDS) {
			graphics.fill(contentLeft, contentTop, width, contentTop + 18, BAR);
			graphics.fill(contentLeft, contentTop + 17, width, contentTop + 18, EDGE);
		}

		drawColumn(graphics);

		// Forgotten before the tab draws rather than after: a panel that shows no
		// strip this frame must not be draggable by one left over from the last.
		barExtra = 0;
		if (configTextOpen) {
			drawConfigText(graphics, contentTop + 4, height - PAD);
		} else {
			switch (tab) {
				case CONSOLE -> drawConsole(graphics);
				case CONTENT -> drawContent(graphics);
				case WORLDS -> drawWorlds(graphics);
				case PLAYERS -> drawPlayers(graphics);
				case RIGHTS -> drawRights(graphics);
				case CONFIGS -> drawConfigs(graphics);
				case SETTINGS -> drawSettings(graphics);
			}
		}

		// After the tab, not before it. The frames were drawn first and the window
		// for a new world was drawn over them, so its two fields were a caret and
		// a word hanging in the panel with nothing round them — and the same was
		// true, more quietly, of every field that had anything drawn after it.
		for (Framed each : framed) {
			Field.frame(graphics, each.x(), each.y(), each.width(), each.height(),
				each.box().isFocused(), each.box().isActive());
		}

		super.extractRenderState(graphics, mouseX, mouseY, delta);
		if (windowUp() && (tab == Tab.WORLDS || window == Window.PRIVILEGE)) {
			drawWindow(graphics, mouseX, mouseY, delta);
		}
		dropdown.draw(graphics, font, mouseX, mouseY);
		motdEditor.draw(graphics, font, mouseX, mouseY);
		confirm.draw(graphics, font, width, height, mouseX, mouseY);
	}

	/**
	 * A strip along the top while a world is being made, in every tab.
	 *
	 * The making takes minutes and happens on a thread of its own, so the window
	 * that started it gets out of the way and this stays: three pixels along the
	 * top edge and a few words beside the server's name. Anything larger would be
	 * a panel taking up room in every tab for something nobody has to watch, and
	 * anything smaller would be nothing at all.
	 *
	 * The bar fills when the core is counting the spawn area out loud and slides
	 * when it is not. A bar that pretends to a number it does not have is worse
	 * than one that admits to knowing only that something is happening.
	 */
	private void drawMaking(GuiGraphicsExtractor graphics, String name) {
		int done = manager.madePercent();
		graphics.fill(0, 0, width, MAKING, 0xFF10141A);
		if (done >= 0) {
			graphics.fill(0, 0, width * done / 100, MAKING, ACCENT);
		} else {
			int run = width + SLIDE;
			int at = (int) ((net.minecraft.util.Util.getMillis() / 4) % run) - SLIDE;
			graphics.fill(Math.max(0, at), 0, Math.min(width, at + SLIDE), MAKING, ACCENT);
		}

		String said = manager.makingStep();
		if (said.isEmpty()) return;
		if (done >= 0) said = said + "  " + done + "%";
		int at = width - PAD - font.width(said);
		// Only if it does not reach the name in the middle: two pieces of text
		// through each other say less than one of them alone.
		if (at > width / 2 + font.width(name) / 2 + 12) {
			graphics.text(font, said, at, 5, ACCENT);
		}
	}

	/** How thick the strip along the top is, and how long its sliding part. */
	private static final int MAKING = 3;
	private static final int SLIDE = 90;

	private void drawColumn(GuiGraphicsExtractor graphics) {
		int across = left - PAD * 2;
		int x = PAD;

		int y = TOP + 6 + iconSize + GROUP;
		label(graphics, "npc_studio.server.name", x, y);
		y += LABEL + FIELD + GROUP;
		label(graphics, "npc_studio.server.motd", x, y);
		y += LABEL + FIELD + GROUP;
		label(graphics, "npc_studio.server.core", x, y);
		y += LABEL + FIELD + GROUP;
		label(graphics, "npc_studio.server.address", x, y);
		graphics.text(font, font.plainSubstrByWidth(address(), across), x, y + LABEL, TEXT);
		y += LABEL + LINE + 14 + GROUP;
		label(graphics, "npc_studio.server.memory", x, y);
		if (machineRow) {
			y += LABEL + FIELD + GROUP;
			int half = (across - 6) / 2;
			label(graphics, "npc_studio.server.cores", x, y);
			label(graphics, "npc_studio.server.priority", x + half + 6, y);
		}

		if (!note.isEmpty()) {
			int noteTop = y + LABEL + FIELD + 6;
			int room = buttonsTop - 6 - noteTop;
			if (room >= 9) {
				// Kept inside the gap between the last field and the buttons. On a
				// short window there is not much of a gap, which is why anything a
				// person has to act on goes into a window of its own instead — see
				// the account offer in join().
				graphics.enableScissor(0, noteTop, left, noteTop + room);
				graphics.textWithWordWrap(font, Component.literal(note),
					x, noteTop, across, WARN);
				graphics.disableScissor();
			}
		}
	}

	private void label(GuiGraphicsExtractor graphics, String key, int x, int y) {
		graphics.text(font, Component.translatable(key), x, y, TEXT_DIM);
	}

	private void drawConsole(GuiGraphicsExtractor graphics) {
		int x = contentLeft + PAD;
		int top = contentTop;
		int bottom = consoleBottom();
		int right = width - PAD;
		graphics.fill(x - 2, top, right, bottom, 0xFF0C0F13);
		graphics.fill(x - 2, top, right, top + 1, EDGE);

		List<String> console = manager.console();
		int fits = consoleFits();
		consoleScroll = Math.clamp(consoleScroll, 0, Math.max(0, console.size() - fits));
		int last = console.size() - consoleScroll;
		int first = Math.max(0, last - fits);
		int from = Math.min(pickedFrom, pickedTo);
		int to = Math.max(pickedFrom, pickedTo);

		graphics.enableScissor(x - 2, top, right, bottom);
		int y = top + 2;
		for (int at = first; at < last; at++) {
			String line = console.get(at);
			if (pickedFrom >= 0 && at >= from && at <= to) {
				graphics.fill(x - 2, y - 1, right, y + LINE - 1, 0x502C6E9B);
			}
			int colour = line.startsWith(">") ? ACCENT
				: line.startsWith("---") ? TEXT_DIM
				: line.contains("/WARN") || line.contains("/ERROR") ? WARN
				: TEXT;
			graphics.text(font, font.plainSubstrByWidth(line, right - x - 4), x, y, colour);
			y += LINE;
		}
		graphics.disableScissor();
		// Counted from the top even though the console is scrolled from the
		// bottom: a strip that runs the other way from every other strip is a
		// strip nobody can use.
		int rows = Math.max(0, console.size() - fits);
		scrollbar(graphics, top, bottom, (rows - consoleScroll) * LINE, rows * LINE);

		if (console.isEmpty()) {
			graphics.text(font, Component.translatable(switch (manager.state()) {
				case DOWN -> "npc_studio.server.console_down";
				case STARTING -> "npc_studio.server.starting";
				case STOPPING -> "npc_studio.server.stopping";
				case UP -> "npc_studio.server.console_quiet";
			}), x + 2, top + 4, TEXT_DIM);
		}
	}

	/**
	 * The settings, as cards rather than as a list.
	 *
	 * A flat run of forty labels and forty boxes is a file with a different
	 * typeface. A card per group gives the eye somewhere to stop, and it is the
	 * shape the screen this is modelled on uses for exactly that reason.
	 */
	private void drawSettings(GuiGraphicsExtractor graphics) {
		int x = contentLeft + PAD;
		// What the tab is about, beside the buttons that open it whole. Drawn
		// whether or not the file has been read, because the buttons are there
		// whether or not it has.
		graphics.text(font, "server.properties", x, contentTop + 9, TEXT_DIM);
		graphics.fill(contentLeft, contentTop + SETTINGS_HEAD - 3, width,
			contentTop + SETTINGS_HEAD - 2, EDGE);

		if (!propertiesRead) {
			graphics.text(font, Component.translatable("npc_studio.server.reading"),
				x, contentTop + SETTINGS_HEAD + 6, TEXT_DIM);
			return;
		}

		// Clipped to the area below the head, so a card scrolled up disappears
		// under the strip instead of being drawn over it.
		graphics.enableScissor(contentLeft, contentTop + SETTINGS_HEAD - 2, width, height);
		for (Card card : cards) {
			graphics.fill(card.x(), card.y(), card.x() + card.width(),
				card.y() + card.height(), CARD);
			graphics.fill(card.x(), card.y(), card.x() + card.width(), card.y() + 1, EDGE);
			graphics.fill(card.x(), card.y() + card.height() - 1,
				card.x() + card.width(), card.y() + card.height(), EDGE);
			graphics.fill(card.x(), card.y(), card.x() + 1, card.y() + card.height(), EDGE);
			graphics.fill(card.x() + card.width() - 1, card.y(),
				card.x() + card.width(), card.y() + card.height(), EDGE);
		}

		for (Placed line : placed) {
			if (line.heading()) {
				graphics.text(font, Component.translatable(line.group()),
					line.x() + PAD, line.y() + 5, ACCENT);
				continue;
			}
			ServerSettings setting = line.setting();
			boolean changed = edits.containsKey(setting.key())
				&& !edits.get(setting.key()).equals(values.get(setting.key()));
			graphics.text(font, Component.translatable(setting.label()),
				line.x() + PAD, line.y(), changed ? ACCENT : TEXT_DIM);
		}
		graphics.disableScissor();
		scrollbar(graphics, contentTop + SETTINGS_HEAD, height - PAD, settingsScroll,
			settingsHeight);
	}

	// ------------------------------------------------------------------ input

	@Override
	public void tick() {
		manager.tick();
		if (++sinceLook >= LOOK_EVERY) {
			sinceLook = 0;
			lookAgain();
		}
		if (shown != manager.state()) {
			shown = manager.state();
			rebuild();
		}
		long now = net.minecraft.util.Util.getMillis();
		if (lastEdit > 0 && now - lastEdit > SETTLE_MS) {
			lastEdit = 0;
			saveSettled();
		}
		if (saidAt > 0 && now - saidAt > 6000) {
			note = "";
			saidAt = 0;
		}

		// The keyboard has been still for long enough to mean the word is finished.
		if (typedAt > 0 && now - typedAt > SETTLE_SEARCH_MS && tab == Tab.CONTENT
			&& half == Half.BROWSE && opened == null && !needsKeyNow() && !searching
			&& !queryText.equals(asked)) {
			search(true);
		}

		// A row on its way out. The list is not read again until the gap has shut,
		// so that nothing arrives from below while it is still closing.
		if (shuttingAt > 0 && now - shuttingAt > SHUT_MS) {
			shutting = "";
			shuttingAt = 0;
			if (shutDone) {
				shutDone = false;
				if (afterShut != null) afterShut.run();
			}
			rebuild();
		} else if (shuttingAt > 0) {
			// The cards slide at the speed of the drawing; their buttons only move
			// when the widgets are placed again. Without this the two come apart
			// for a fifth of a second, which is the sort of detail that reads as
			// "this window is held together with tape".
			rebuild();
		}

		String failure = manager.takeFailure();
		if ("save-off".equals(failure)) {
			// It was quieted for a copy and would not be let go again. Everything
			// since is in memory only, and a crash loses it — which is exactly the
			// thing the copy was taken to prevent.
			confirm.tell(
				Component.translatable("npc_studio.server.worlds.save_off.title"),
				Component.translatable("npc_studio.server.worlds.save_off.body"),
				Component.translatable("npc_studio.server.worlds.save_off.ok"));
		}
		if ("memory".equals(failure)) {
			long asked = server == null ? 0 : server.memoryMb;
			confirm.tell(
				Component.translatable("npc_studio.server.no_memory.title"),
				Component.translatable("npc_studio.server.no_memory.body",
					String.format(java.util.Locale.ROOT, "%.1f", asked / 1024.0),
					String.format(java.util.Locale.ROOT, "%.1f",
						Machine.totalMemoryMb() / 1024.0),
					String.format(java.util.Locale.ROOT, "%.1f",
						Machine.recommendedHeapMb() / 1024.0)),
				Component.translatable("npc_studio.server.no_memory.ok"));
		}

		// The formatting window belongs to the message field: it appears beside it
		// while it is being used, goes when the field is left, and stays gone if
		// it was shut by hand until the field is come back to.
		boolean focused = motd != null && motd.isFocused();
		if (focused && !motdWasFocused) motdDismissed = false;
		if (motdWasOpen && !motdEditor.isOpen() && focused) motdDismissed = true;
		motdWasFocused = focused;

		if (!focused) {
			motdEditor.close();
		} else if (!motdDismissed) {
			motdEditor.follow(motd, left + PAD, motdFrameY - 4, height);
		}
		motdWasOpen = motdEditor.isOpen();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (confirm.click(event.x(), event.y())) return true;
		if (dropdown.click(event.x(), event.y())) return true;
		if (motdEditor.click(event.x(), event.y())) return true;

		// While the window for a new world is up, only its own controls answer.
		// Everything else is swallowed rather than passed through to a tab that
		// is sitting behind a scrim.
		if (windowUp()) {
			if (!insideWindow(event.x(), event.y())) return true;
			return super.mouseClicked(event, doubleClick) || true;
		}

		if (onBar(event.x(), event.y())) {
			barDragging = true;
			scrollTo(offsetAt(event.y()));
			return true;
		}

		if (tab == Tab.CONSOLE && event.x() > contentLeft && event.y() > contentTop
			&& event.y() < consoleBottom()) {
			int at = lineAt(event.y());
			pickedFrom = at;
			pickedTo = at;
			picking = at >= 0;
			if (at >= 0) return true;
		}
		// A link in a description, found while it was drawn. Before the widgets,
		// because there are none under it — the text is drawn, not built.
		if (opened != null && hoveredLink != null) {
			openLink(hoveredLink);
			return true;
		}
		if (super.mouseClicked(event, doubleClick)) return true;

		// A row that was not a button press opens the project. Checked after the
		// widgets rather than before, so that pressing "install" on a row installs
		// rather than opening a page and then installing from it.
		if (tab == Tab.CONTENT && half == Half.BROWSE && opened == null && !needsKeyNow()
			&& event.x() > contentLeft && event.x() < width - PAD) {
			int row = (int) ((event.y() - browseListTop + browseScroll) / ADDON_ROW);
			if (event.y() >= browseListTop && row >= 0 && row < results.size()) {
				openProject(results.get(row));
				return true;
			}
		}
		// The same in the installed list, and in the client's own mods under it.
		if (tab == Tab.CONTENT && half == Half.INSTALLED && addonsRead
			&& event.x() > contentLeft && event.x() < width - PAD) {
			var pressed = addonAt(event.y() + contentScroll);
			if (event.y() >= installedTop && pressed != null) {
				openInstalled(pressed);
				return true;
			}
			int mine = (int) ((event.y() - clientModsTop) / ADDON_ROW);
			List<ClientMods.Mod> offered = clientMods();
			if (event.y() >= clientModsTop && mine >= 0 && mine < offered.size()) {
				openClientMod(offered.get(mine));
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (barDragging) {
			scrollTo(offsetAt(event.y()));
			return true;
		}
		if (picking) {
			int at = lineAt(event.y());
			if (at >= 0) pickedTo = at;
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		picking = false;
		barDragging = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
		if (dropdown.isOpen()) return dropdown.scroll(dy);
		if (windowUp()) return true;
		if (mouseX > contentLeft) {
			// The whole of the wheel's movement rather than its direction: a
			// trackpad sends fractions and a flick sends several at once, and
			// throwing that away made both feel like a ratchet.
			double turn = dy == 0 ? 0 : dy;
			if (configTextOpen) {
				// The editor scrolls itself, three lines at a notch, whichever tab
				// it was opened from.
				return super.mouseScrolled(mouseX, mouseY, dx, dy);
			}
			if (tab == Tab.CONSOLE) {
				int rows = Math.max(0, manager.console().size() - consoleFits());
				consoleScroll = Math.clamp(
					consoleScroll + (int) Math.round(turn * STEP_LINES), 0, rows);
			} else if (tab == Tab.CONTENT && half == Half.INSTALLED) {
				contentScroll = Math.clamp(contentScroll - (int) Math.round(turn * STEP),
					0, contentHeight);
				rebuild();
			} else if (tab == Tab.CONTENT && half == Half.BROWSE && opened != null) {
				pageScroll = Math.clamp(pageScroll - (int) Math.round(turn * STEP_PIXELS),
					0, pageHeight);
				rebuild();
			} else if (tab == Tab.CONTENT && half == Half.BROWSE && needsKeyNow()) {
				keyScroll = Math.clamp(keyScroll - (int) Math.round(turn * STEP_PIXELS),
					0, keyHeight);
				rebuild();
			} else if (tab == Tab.CONTENT) {
				browseScroll = Math.clamp(browseScroll - (int) Math.round(turn * STEP),
					0, browseHeight);
				// The next page is asked for well before the bottom rather than at
				// it: asking on the last row means waiting at a dead end, and the
				// answer takes about as long as reading two rows takes.
				if (browseScroll >= browseHeight - ADDON_ROW * 4 && moreToFind && !searching) {
					search(false);
				}
				rebuild();
			} else if (tab == Tab.WORLDS) {
				worldScroll = Math.clamp(worldScroll - (int) Math.round(turn * STEP),
					0, worldHeight);
				rebuild();
			} else if (tab == Tab.PLAYERS) {
				playerScroll = Math.clamp(playerScroll - (int) Math.round(turn * STEP),
					0, playerHeight);
				rebuild();
			} else if (tab == Tab.RIGHTS) {
				// Two lists side by side, and the wheel belongs to whichever the
				// mouse is over — the same rule the configs tab uses, because it is
				// the same shape. Without a plugin there is only one list, and the
				// left third of the window must not scroll a pane that is not there.
				if (withLuckPerms() && mouseX < contentLeft + PAD + RIGHTS_LIST) {
					rightsListScroll = Math.clamp(
						rightsListScroll - (int) Math.round(turn * STEP), 0, rightsListHeight);
				} else {
					rightsScroll = Math.clamp(
						rightsScroll - (int) Math.round(turn * STEP_PIXELS), 0, rightsHeight);
				}
				rebuild();
			} else if (tab == Tab.CONFIGS) {
				// Two lists side by side, and the wheel belongs to whichever the
				// mouse is over. One scrollbar for both would move the list nobody
				// was looking at.
				if (mouseX < contentLeft + PAD + CONFIG_LIST) {
					configListScroll = Math.clamp(
						configListScroll - (int) Math.round(turn * STEP), 0, configListHeight);
				} else {
					configScroll = Math.clamp(
						configScroll - (int) Math.round(turn * STEP_PIXELS), 0, configHeight);
				}
				rebuild();
			} else {
				// In pixels rather than in rows: with cards in columns there is no
				// single row to count.
				settingsScroll = Math.clamp(settingsScroll - (int) Math.round(turn * STEP_PIXELS),
					0, settingsHeight);
				rebuild();
			}
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, dx, dy);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		// Escape closes the project page rather than the whole window: the page is
		// a place you went into, and leaving it should undo one step.
		if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && opened != null) {
			closeProject();
			return true;
		}
		// The same one step back out of the file editor, and with the same question
		// asked when there is something in it that is not on disk.
		if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && configTextOpen) {
			leaveConfigText();
			return true;
		}
		// Ctrl+S, because that is what hands do in a text editor without being
		// asked, and because the button is at the far corner of the screen.
		if (configTextOpen && event.hasControlDown()
			&& event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_S) {
			keepConfigText();
			return true;
		}
		if (windowUp()) {
			// Tab walks the window's own fields and stops at its edge, rather than
			// wandering off into the tab behind it and typing there unseen.
			if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_TAB) {
				stepInsideWindow(event.hasShiftDown());
				return true;
			}
			// Escape puts the window away, unless it is already making something,
			// in which case there is nothing to call off: the core is running.
			if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && !manager.makingWorld()) {
				window = Window.NONE;
				renaming = null;
				rebuild();
				return true;
			}
			// Enter means the window's own verb, and only that window's: it used to
			// mean "make the world" whatever was in front, so pressing it while
			// naming a copy would have started making a world behind it.
			if (event.key() == GLFW_KEY_ENTER || event.key() == GLFW_KEY_KP_ENTER) {
				if (window == Window.RENAME) {
					keepRename();
					return true;
				}
				if (window == Window.PRIVILEGE) {
					makeGroup();
					return true;
				}
				if (window == Window.WORLD && readyToMake()) {
					createWorld();
					return true;
				}
			}
		}
		if ((event.key() == GLFW_KEY_ENTER || event.key() == GLFW_KEY_KP_ENTER)
			&& command != null && command.isFocused()) {
			sendCommand();
			return true;
		}
		// A name typed into a field with a button beside it: Enter is that button.
		// Before the field itself sees the key, because a field does nothing at all
		// with Enter and would swallow it.
		if ((event.key() == GLFW_KEY_ENTER || event.key() == GLFW_KEY_KP_ENTER)
			&& getFocused() instanceof EditBox box && submits.containsKey(box)) {
			submits.get(box).run();
			return true;
		}
		if (tab == Tab.CONSOLE && (command == null || !command.isFocused())) {
			if (event.isCopy()) {
				copyConsole();
				return true;
			}
			if (event.isSelectAll()) {
				pickedFrom = 0;
				pickedTo = manager.console().size() - 1;
				return true;
			}
		}
		return super.keyPressed(event);
	}

	/** Focus to the next of the window's own controls, and no further. */
	private void stepInsideWindow(boolean backwards) {
		List<net.minecraft.client.gui.components.events.GuiEventListener> mine =
			new ArrayList<>();
		for (int at = Math.max(0, windowFrom); at < children().size(); at++) {
			mine.add(children().get(at));
		}
		if (mine.isEmpty()) return;
		int at = mine.indexOf(getFocused());
		int next = at < 0
			? (backwards ? mine.size() - 1 : 0)
			: Math.floorMod(at + (backwards ? -1 : 1), mine.size());
		setFocused(mine.get(next));
	}

	/**
	 * Look again at whatever this tab is showing, and only stir if it moved.
	 *
	 * Everything here was read once and kept: the folder of mods, the list of
	 * worlds, the copies, the datapacks. That is right for a screen nobody else
	 * writes to and wrong for this one — the server writes into the same folders,
	 * so does the person, in another window, with a file manager, and what they
	 * see is what was true when they walked in. "Leave the menu and come back" is
	 * not a workaround anybody should have to learn.
	 *
	 * Two rules keep it from being worse than the problem. Nothing is rebuilt
	 * unless the answer actually differs — a rebuild throws away the widgets, and
	 * doing that every second and a half would fight the mouse. And nothing looks
	 * at all while a window, a dropdown or a field has somebody's attention:
	 * losing what you were typing to a refresh is exactly the kind of help nobody
	 * asked for.
	 */
	private void lookAgain() {
		if (server == null || windowUp() || confirm.isOpen() || dropdown.isOpen()
			|| motdEditor.isOpen()) {
			return;
		}
		if (getFocused() instanceof EditBox) return;
		// Nothing is placed again while a document is open in the editor, whatever
		// tab it belongs to: rebuilding under somebody's hands is how typing is lost.
		if (configTextOpen) return;
		if (!trouble.isEmpty()) return;
		ManagedServer which = server;
		switch (tab) {
			case CONTENT -> {
				if (opened != null || !addonsRead) return;
				manager.addons(which, found -> {
					if (server != which || windowUp()) return;
					if (found.equals(addons)) return;
					addons = found;
					rebuild();
				});
				manager.dataPacks(which, manager.currentWorld(), found -> {
					if (server != which || found.equals(installedPacks)) return;
					installedPacks = found;
					rebuild();
				});
			}
			case WORLDS -> {
				if (!worldsRead) return;
				manager.worlds(which, found -> {
					if (server != which || windowUp() || found.equals(worlds)) return;
					worlds = found;
					rebuild();
				});
				manager.backups(which, found -> {
					if (server != which || windowUp() || found.equals(backups)) return;
					backups = found;
					readBackupLooks(found);
					rebuild();
				});
				if (ground == Ground.PLACES && placesRead) {
					manager.places(which, manager.currentWorld(), found -> {
						if (server != which || windowUp() || same(found, places)) return;
						places = found;
						rebuild();
					});
				}
			}
			case PLAYERS -> {
				if (!playersRead) return;
				manager.players(which, manager.currentWorld(), found -> {
					if (server != which || found.equals(players)) return;
					players = found;
					rebuild();
				});
			}
			case RIGHTS -> {
				// Nothing. Asked for on purpose only, and this is the one tab where
				// that is a decision rather than thrift: reading it means asking
				// LuckPerms to export, and every export writes half a dozen lines
				// into the server console. A tab that refreshed itself twice a
				// second would fill somebody's console with our own housekeeping —
				// which is the complaint this window has already had twice.
			}
			case CONFIGS -> {
				// The list of files, which grows when a plugin is installed and
				// again the first time one of them runs. Never the open file: it
				// holds changes nobody has saved, and re-reading it would be
				// throwing away somebody's typing to show them the same file.
				if (!configsRead || !configEdits.isEmpty()) return;
				manager.configs(which, addons, found -> {
					if (server != which || !configEdits.isEmpty() || found.equals(configGroups)) {
						return;
					}
					configGroups = found;
					rebuild();
				});
			}
			case SETTINGS -> {
				// Only when there is nothing of theirs waiting to be written: a
				// list that reloads under a half-made change is a list that
				// throws the change away.
				if (!propertiesRead || !edits.isEmpty() || lastEdit > 0) return;
				readProperties();
			}
			default -> {
				// The console looks after itself, twice a second, and has done
				// since the beginning.
			}
		}
	}

	/**
	 * Whether two lists of made dimensions say the same thing.
	 *
	 * Written out rather than {@code equals}, because a place holds its point as
	 * an array and two equal arrays are not equal — so the plain comparison would
	 * say "changed" every time and rebuild the screen for ever.
	 */
	private static boolean same(List<com.mopicmp.npcstudio.server.Dimensions.Place> one,
			List<com.mopicmp.npcstudio.server.Dimensions.Place> two) {
		if (one.size() != two.size()) return false;
		for (int at = 0; at < one.size(); at++) {
			var here = one.get(at);
			var there = two.get(at);
			if (!here.id().equals(there.id()) || !here.name().equals(there.name())
				|| !here.template().equals(there.template())
				|| !here.layers().equals(there.layers())
				|| here.sharing() != there.sharing() || here.back() != there.back()
				|| !here.who().equals(there.who())
				|| !java.util.Arrays.equals(here.point(), there.point())) {
				return false;
			}
		}
		return true;
	}

	/** How often to look: often enough to feel live, rarely enough to be free. */
	private static final int LOOK_EVERY = 30;

	private int sinceLook;

	@Override
	public void onClose() {
		saveSettled();
		manager.watch(null);
		minecraft.setScreenAndShow(back);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
