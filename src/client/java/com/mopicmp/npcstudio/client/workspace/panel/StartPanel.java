package com.mopicmp.npcstudio.client.workspace.panel;

import java.util.ArrayList;
import java.util.List;

import com.mopicmp.npcstudio.client.editor.FlatButton;
import com.mopicmp.npcstudio.client.map.MapPack;
import com.mopicmp.npcstudio.client.map.StartOptions;
import com.mopicmp.npcstudio.client.map.Started;
import com.mopicmp.npcstudio.client.scene.Shaders;
import com.mopicmp.npcstudio.client.server.Dropdown;
import com.mopicmp.npcstudio.client.server.NumberField;
import com.mopicmp.npcstudio.client.workspace.Property;
import com.mopicmp.npcstudio.client.workspace.WorkspacePanel;
import com.mopicmp.npcstudio.map.Bound;
import com.mopicmp.npcstudio.map.Firmness;
import com.mopicmp.npcstudio.map.MapStart;
import com.mopicmp.npcstudio.map.Setting;
import com.mopicmp.npcstudio.net.MapPayloads;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelData;

/**
 * Where the map starts and what it looks like when it does.
 *
 * <h2>Why one panel for two things</h2>
 *
 * Because they are one thing from where the author is standing: the first ten
 * seconds of somebody else's first visit. The point decides what they are looking
 * at and the settings decide how far and how brightly they see it, and both are
 * judged the same way — by standing on the spot and looking.
 *
 * <h2>What the panel refuses to guess</h2>
 *
 * Whether the point will be honoured. It says which of the three things is true —
 * adventure mode, a radius of nought, or a scatter — and it says it from what the
 * server answered rather than from the settings it can see. That distinction is
 * the reason this is worth a panel: the rule that decides is not the one most
 * people would name, and it is not visible from here.
 */
public class StartPanel extends WorkspacePanel {

	private static final int PAD = 6;
	private static final int LINE = 11;
	private static final int ROW = 18;
	private static final int TALL = 14;

	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;
	private static final int HEADING = 0xFF6B7683;
	private static final int ACCENT = 0xFF4FC3F7;
	private static final int GOOD = 0xFF81C784;
	private static final int WARN = 0xFFE57373;
	private static final int EDGE = 0xFF3A424D;

	private final Dropdown dropdown = new Dropdown();

	/** What the widgets were built for, so a change of the list rebuilds them. */
	private String builtFor = "";

	private int settingsTop;
	private int lookTop;

	@Override
	public String id() {
		return "start";
	}

	@Override
	public Property edits() {
		return Property.WORLD;
	}

	@Override
	public int minimumWidth() {
		return 190;
	}

	@Override
	public int minimumHeight() {
		return 120;
	}

	@Override
	public void opened() {
		Started.please();
	}

	/** The point the last question was about, so the same one is not asked twice. */
	private BlockPos asked;

	@Override
	public void tick() {
		String now = shape();
		if (!now.equals(builtFor)) rebuild();
		askIfStale();
	}

	/**
	 * Asks again when the point has moved and the answer is about the old one.
	 *
	 * It moves without going through this panel: {@code /setworldspawn}, a second
	 * person editing, a bed. The server broadcasts the new point to every client by
	 * itself, so the first marker follows it — but the landing spot is ours to ask
	 * for, and without this it would go on describing a point nobody is using.
	 */
	private void askIfStale() {
		if (minecraft.level == null) return;
		BlockPos pos = minecraft.level.getLevelData().getRespawnData().pos();
		MapPayloads.Spawn report = Started.spawn();
		if (report != null && report.pos().equals(pos)) return;
		if (pos.equals(asked)) return;
		asked = pos;
		Started.please();
	}

	/** The list as the widgets see it: which settings, in which order. */
	private String shape() {
		StringBuilder said = new StringBuilder();
		for (MapStart.Entry entry : Started.start().settings()) {
			said.append(entry.what().getSerializedName())
				.append(entry.firmness().getSerializedName())
				.append(entry.bound().getSerializedName())
				.append(' ');
		}
		// The shader too: choosing one changes the button's own label, and a label is
		// built rather than drawn.
		// And the skin, for the same reason as the shader: it is a label on a button,
		// and a label is built rather than drawn.
		return said.append('|').append(Started.start().shaderPack())
			.append('|').append(Started.start().playerSkin()).toString();
	}

	// --------------------------------------------------------------------- widgets

	@Override
	protected void build() {
		builtFor = shape();
		int inner = width - PAD * 2;
		if (inner < 60) return;

		int y = PAD + LINE * 4 + 2;
		add(new FlatButton(PAD, y, inner, TALL,
			Component.translatable("npc_studio.start.here"), ACCENT, this::putHere));

		settingsTop = y + TALL + 8;

		int at = settingsTop + LINE + 2;
		for (MapStart.Entry entry : Started.start().settings()) {
			buildRow(entry, at, inner);
			at += ROW + LINE + 2;
		}

		if (Started.start().settings().size() < Setting.values().length) {
			int below = at + TALL;
			add(new FlatButton(PAD, at, inner, TALL,
				Component.translatable("npc_studio.start.add"), EDGE, () -> offerAdd(below)));
			at += TALL;
		}

		lookTop = at + 8;
		buildLook(lookTop, inner);
	}

	/**
	 * The pack and the shader: the two things a map dresses itself in.
	 *
	 * Neither is a setting of ours and that is the point of the section. The pack
	 * is a file the game finds on its own, so all that is offered is the folder to
	 * put it in. The shader is somebody else's mod and somebody else's frame rate,
	 * so all that is stored is a name — recommending it is the strongest thing a
	 * map has any business doing with it.
	 */
	private void buildLook(int top, int inner) {
		int y = top + LINE + 2;

		if (MapPack.where() != null) {
			add(new FlatButton(PAD, y, inner, TALL,
				Component.translatable("npc_studio.start.pack_folder"), EDGE, () -> {
					java.nio.file.Path folder = MapPack.folder();
					if (folder != null) net.minecraft.util.Util.getPlatform().openPath(folder);
				}));
			y += TALL + 4;
		}

		if (Shaders.installed()) {
			int below = y + TALL;
			String pack = Started.start().shaderPack();
			add(new FlatButton(PAD, y, inner, TALL, Component.literal(pack.isEmpty()
					? Component.translatable("npc_studio.start.shader_none").getString() : pack),
				EDGE, () -> offerShader(below)));
			y += TALL + 4;
		}

		// The skin everybody playing this map wears. Here rather than beside a
		// dialogue because it is true all the time — see MapStart.withPlayerSkin,
		// where that argument is written down.
		skinTop = y;
		int under = y + TALL;
		add(new FlatButton(PAD, y, inner, TALL, wornLabel(), EDGE, () -> offerSkin(under)));
	}

	/** Where the skin button sits, so the heading above it lands in the right place. */
	private int skinTop;

	/**
	 * What the skin button says: the costume's own name, or that there is none.
	 *
	 * By fingerprint, so the name has to be looked up — and may not be there, because
	 * the library is only sent to somebody who has opened the wardrobe. Saying the
	 * fingerprint in that case is honest and useless in equal measure, so it says
	 * "a picture" instead: the setting is real, this client simply cannot name it.
	 */
	private Component wornLabel() {
		String mark = Started.start().playerSkin();
		if (mark.isEmpty()) return Component.translatable("npc_studio.start.skin_own");
		for (var costume : com.mopicmp.npcstudio.client.wardrobe.Costumes.all()) {
			if (costume.fingerprint().equals(mark)) return Component.literal(costume.label());
		}
		return Component.translatable("npc_studio.start.skin_unnamed");
	}

	/**
	 * The costumes this world holds, with "their own skin" first.
	 *
	 * A dropdown rather than a list of buttons, which is the rule here and the right
	 * shape besides: a wardrobe on a built map runs to dozens, and dozens of buttons
	 * is a panel nobody can reach the bottom of.
	 */
	private void offerSkin(int below) {
		com.mopicmp.npcstudio.client.wardrobe.Costumes.refresh();
		List<Dropdown.Option> options = new ArrayList<>();
		options.add(new Dropdown.Option("",
			Component.translatable("npc_studio.start.skin_own")));
		var seen = new java.util.HashSet<String>();
		for (var costume : com.mopicmp.npcstudio.client.wardrobe.Costumes.all()) {
			// By fingerprint, so two costumes that are the same picture are one row.
			// They are one file on disk for the same reason, and offering the picture
			// twice under two names is a choice that makes no difference.
			if (costume.fingerprint().isEmpty() || !seen.add(costume.fingerprint())) continue;
			options.add(new Dropdown.Option(costume.fingerprint(),
				Component.literal(costume.label())));
		}
		dropdown.open(PAD, below, width - PAD * 2, height, options,
			Started.start().playerSkin(),
			chosen -> put(Started.start().withPlayerSkin(chosen)));
	}

	/**
	 * One setting: a name with a way to remove it, then its three controls.
	 *
	 * Two lines rather than one because three controls and a name do not fit across
	 * a docked panel at any width somebody would actually give it, and a row that
	 * only works when the panel is wide is a row that is usually broken.
	 */
	private void buildRow(MapStart.Entry entry, int top, int inner) {
		Setting what = entry.what();

		add(new FlatButton(PAD + inner - TALL, top, TALL, TALL,
			Component.literal("×"), EDGE, () -> put(Started.start().without(what))));

		int y = top + LINE + 1;
		int gap = 3;
		int number = 44;
		int rest = inner - number - gap * 2;
		int firm = rest * 5 / 9;
		int bound = rest - firm;

		add(new FlatButton(PAD, y, firm, TALL,
			Component.translatable(entry.firmness().key()), EDGE,
			() -> offerFirmness(entry, PAD, y + TALL)));

		add(new FlatButton(PAD + firm + gap, y, bound, TALL,
			Component.translatable(entry.bound().key()), EDGE,
			() -> offerBound(entry, PAD + firm + gap, y + TALL)));

		add(new NumberField(PAD + firm + bound + gap * 2, y, number, TALL,
			Component.translatable(what.key()), entry.value(),
			what.least(), what.most(), what.step(), what.decimals(), "",
			value -> put(Started.start().with(new MapStart.Entry(
				what, value, entry.firmness(), entry.bound())))));
	}

	/**
	 * An edit, without rebuilding the widgets that caused it.
	 *
	 * Rebuilding here was the obvious thing and it breaks dragging: a number field
	 * reports every step of a drag, and throwing the field away on the first step
	 * leaves the mouse dragging an object that no longer exists. {@link #tick()}
	 * notices the changes that actually alter the layout — a setting added, removed
	 * or given a different firmness — and a changed value is not one of them.
	 */
	private void put(MapStart next) {
		if (!mayEdit()) return;
		Started.change(next);
	}

	private void putHere() {
		if (!mayEdit() || minecraft.level == null) return;
		LevelData.RespawnData was = minecraft.level.getLevelData().getRespawnData();
		BlockPos to = minecraft.player.blockPosition();
		float yaw = minecraft.player.getYRot();
		float pitch = minecraft.player.getXRot();

		// Written down before it is done, because afterwards there is nothing on
		// screen that remembers where the point used to be — and three numbers
		// nobody read are three numbers nobody can put back.
		var spawning = new com.mopicmp.npcstudio.client.edit.Doings.Spawning(
			was.pos(), was.yaw(), was.pitch(), to, yaw, pitch);
		if (spawning.anything()) com.mopicmp.npcstudio.client.edit.History.did(spawning);

		Started.setSpawn(to, yaw, pitch);
	}

	/**
	 * Whether this player is allowed to change the map's start.
	 *
	 * The server decides and refuses on its own, so this is not the guard — it is
	 * the reason the guard is never reached. Without it a player in survival could
	 * set a setting to "held" on their own client, have the server throw the edit
	 * away, and be left holding their own options with no way to let go short of
	 * leaving the world.
	 */
	private boolean mayEdit() {
		return minecraft.player != null && minecraft.player.isCreative();
	}

	// ------------------------------------------------------------------- the lists

	private void offerFirmness(MapStart.Entry entry, int x, int below) {
		List<Dropdown.Option> options = new ArrayList<>();
		for (Firmness firmness : Firmness.values()) {
			options.add(new Dropdown.Option(firmness.getSerializedName(),
				Component.translatable(firmness.key())));
		}
		dropdown.open(x, below, 90, height, options, entry.firmness().getSerializedName(),
			chosen -> put(Started.start().with(new MapStart.Entry(entry.what(), entry.value(),
				Firmness.valueOf(chosen.toUpperCase(java.util.Locale.ROOT)), entry.bound()))));
	}

	private void offerBound(MapStart.Entry entry, int x, int below) {
		List<Dropdown.Option> options = new ArrayList<>();
		for (Bound bound : Bound.values()) {
			options.add(new Dropdown.Option(bound.getSerializedName(),
				Component.translatable(bound.key())));
		}
		dropdown.open(x, below, 80, height, options, entry.bound().getSerializedName(),
			chosen -> put(Started.start().with(new MapStart.Entry(entry.what(), entry.value(),
				entry.firmness(), Bound.valueOf(chosen.toUpperCase(java.util.Locale.ROOT))))));
	}

	private void offerAdd(int below) {
		List<Dropdown.Option> options = new ArrayList<>();
		for (Setting setting : Setting.values()) {
			if (Started.start().entry(setting) != null) continue;
			options.add(new Dropdown.Option(setting.getSerializedName(),
				Component.translatable(setting.key())));
		}
		if (options.isEmpty()) return;
		dropdown.open(PAD, below, width - PAD * 2, height, options, "", chosen -> {
			Setting setting = Setting.named(chosen);
			// A setting starts at what the player currently has rather than at the
			// game's default: the author adds it having just looked at the world
			// through it, and the number they are looking through is the answer they
			// meant far more often than 0.5 is.
			if (setting != null) {
				put(Started.start().with(new MapStart.Entry(setting,
					StartOptions.read(setting), Firmness.SUGGESTED, Bound.EXACT)));
			}
		});
	}

	/**
	 * Which shader pack the map suggests, out of the ones installed here.
	 *
	 * Only a name is stored, and nothing turns it on. Shaders cost more frames than
	 * everything else on this panel put together, and a map that switches one on
	 * for somebody is a map that runs at four frames a second on a machine the
	 * author never saw. The empty name is a real choice — "no suggestion" — and is
	 * offered first.
	 */
	private void offerShader(int below) {
		List<Dropdown.Option> options = new ArrayList<>();
		options.add(new Dropdown.Option("",
			Component.translatable("npc_studio.start.shader_none")));
		for (String pack : Shaders.packs()) {
			if (!pack.isEmpty()) options.add(Dropdown.Option.of(pack));
		}
		dropdown.open(PAD, below, width - PAD * 2, height, options,
			Started.start().shaderPack(),
			chosen -> put(Started.start().withShader(chosen)));
	}

	// -------------------------------------------------------------------- drawing

	@Override
	protected void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		// The same threshold the widgets are built against. Two different ones would
		// mean a width where the headings are drawn against a layout nothing was
		// placed into, and the settings list would appear at last frame's height.
		if (width - PAD * 2 < 60) return;

		graphics.text(font, Component.translatable("npc_studio.start.point"), PAD, PAD, HEADING);
		drawSpawn(graphics);

		graphics.text(font, Component.translatable("npc_studio.start.settings"),
			PAD, settingsTop, HEADING);

		int at = settingsTop + LINE + 2;
		for (MapStart.Entry entry : Started.start().settings()) {
			graphics.text(font, Component.translatable(entry.what().key()), PAD, at, TEXT);
			at += ROW + LINE + 2;
		}

		if (Started.start().settings().isEmpty()) {
			graphics.text(font, Component.translatable("npc_studio.start.none"),
				PAD, settingsTop + LINE + 2, TEXT_DIM);
		}

		drawLook(graphics);
	}

	/**
	 * The pack and the shader, and what is true about each of them right now.
	 *
	 * The pack's state rides on the heading line, right-aligned, rather than taking
	 * a line of its own: it is three words that change between two values, and a
	 * whole row for that pushes the shader off the bottom of a docked panel.
	 */
	private void drawLook(GuiGraphicsExtractor graphics) {
		graphics.text(font, Component.translatable("npc_studio.start.look"),
			PAD, lookTop, HEADING);

		boolean local = MapPack.where() != null;
		// On a server the folder is on a machine this client cannot see. Said as a
		// fact rather than left blank, because a missing section reads as a bug.
		Component state = !local ? Component.translatable("npc_studio.start.pack_remote")
			: MapPack.there() ? Component.translatable("npc_studio.start.pack_there")
			: Component.translatable("npc_studio.start.pack_missing");
		graphics.text(font, state,
			width - PAD - font.width(state), lookTop,
			local && MapPack.there() ? GOOD : TEXT_DIM);

		int y = lookTop + LINE + 2 + (local ? TALL + 4 : 0);
		if (!Shaders.installed()) {
			graphics.text(font, Component.translatable("npc_studio.shaders.none"), PAD, y, TEXT_DIM);
		}
		// The one label here that is not optional. A button whose only word is a
		// costume's name says nothing about what wearing it would mean.
		graphics.text(font, Component.translatable("npc_studio.start.skin"),
			PAD, skinTop - LINE + 1, TEXT_DIM);
	}

	/** The point, the look, and what the server says will really happen on it. */
	private void drawSpawn(GuiGraphicsExtractor graphics) {
		int y = PAD + LINE;
		if (minecraft.level == null) return;
		LevelData.RespawnData spawn = minecraft.level.getLevelData().getRespawnData();
		BlockPos pos = spawn.pos();

		graphics.text(font, Component.literal(
			pos.getX() + "  " + pos.getY() + "  " + pos.getZ()), PAD, y, TEXT);
		graphics.text(font, Component.literal(
			Math.round(spawn.yaw()) + "°  /  " + Math.round(spawn.pitch()) + "°"),
			PAD, y + LINE, TEXT_DIM);

		MapPayloads.Spawn report = Started.spawn();
		int said = y + LINE * 2;
		if (report == null || !report.pos().equals(pos)) {
			graphics.text(font, Component.translatable("npc_studio.start.asking"),
				PAD, said, TEXT_DIM);
			return;
		}

		// Three different answers, and which one is true is the thing worth knowing.
		if (report.adventure()) {
			graphics.text(font, Component.translatable("npc_studio.start.exact"), PAD, said, GOOD);
		} else if (report.radius() > 0) {
			graphics.text(font, Component.translatable("npc_studio.start.scatter", report.radius()),
				PAD, said, WARN);
		} else {
			BlockPos lands = BlockPos.containing(report.landing());
			graphics.text(font, lands.equals(pos)
					? Component.translatable("npc_studio.start.stands")
					: Component.translatable("npc_studio.start.moved",
						lands.getX() + " " + lands.getY() + " " + lands.getZ()),
				PAD, said, lands.equals(pos) ? GOOD : WARN);
		}
	}

	@Override
	protected void over(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		dropdown.draw(graphics, font, mouseX, mouseY);
	}

	// -------------------------------------------------------------------- the mouse

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// The list first, always. A list drawn over a button and clicked after it is a
		// list whose entries belong to whatever is underneath them.
		if (dropdown.isOpen()) {
			dropdown.click(event.x(), event.y());
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amountX, double amountY) {
		if (dropdown.isOpen() && dropdown.scroll(amountY)) return true;
		return super.mouseScrolled(mouseX, mouseY, amountX, amountY);
	}

	@Override
	public void closed() {
		dropdown.close();
	}
}
