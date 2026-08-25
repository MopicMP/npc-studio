package com.mopicmp.npcstudio.client.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.mopicmp.npcstudio.dialogue.Condition;
import com.mopicmp.npcstudio.dialogue.Effect;
import com.mopicmp.npcstudio.dialogue.Scope;
import com.mopicmp.npcstudio.dialogue.Value;
import com.mopicmp.npcstudio.dialogue.Node;
import com.mopicmp.npcstudio.dialogue.Presentation;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * The selected node's fields, as a panel over the canvas.
 *
 * It used to be a separate screen, which meant the graph disappeared the moment
 * you went to change anything in it — so you edited a node without being able
 * to see what it connected to, which is most of what you need to know while
 * editing it.
 *
 * Living inside the canvas screen also means the changes land as they are
 * typed. There is no "Done" to forget to press and no half-saved state to
 * reconcile: the panel edits the graph directly, and the graph is what gets
 * sent when Save is pressed.
 */
public class NodePanel {

	static final int WIDTH = 210;
	private static final int PADDING = 8;
	private static final int ROW = 30;

	private static final int BACKGROUND = 0xF01A1F26;
	private static final int EDGE = 0xFF2C333D;
	private static final int TEXT = 0xFFECEFF1;
	private static final int TEXT_DIM = 0xFF8A99A6;

	private final GraphEditorScreen screen;
	private final EditorState state;
	private final int index;
	private final List<Label> labels = new ArrayList<>();

	private record Label(String text, int x, int y) { }

	/** How tall the fields came out, measured from the top the panel was given. */
	private int contentBottom;

	public int contentHeight() {
		return contentBottom + 34;
	}

	public NodePanel(GraphEditorScreen screen, EditorState state, int index) {
		this.screen = screen;
		this.state = state;
		this.index = index;
	}

	public int index() {
		return index;
	}

	/**
	 * Builds the widgets.
	 *
	 * Called again after anything structural — an answer added or removed —
	 * because the panel's shape follows the node rather than being fixed.
	 */
	public void build(Font font, int left, int top, int height, Consumer<AbstractWidget> add) {
		labels.clear();
		Node node = state.nodes().get(index);
		int x = left + PADDING;
		int y = top + 40;
		int fieldWidth = WIDTH - PADDING * 2;

		add.accept(field(font, x, y, fieldWidth, "name", node.id(), value -> rename(value), add));
		y += ROW;

		switch (node) {
			case Node.Line line -> {
				add.accept(field(font, x, y, fieldWidth, "speaker", line.speaker(),
					value -> put(withSpeaker(current(Node.Line.class), value)), add));
				y += ROW;
				add.accept(field(font, x, y, fieldWidth, "line", line.text(),
					value -> put(withText(current(Node.Line.class), value)), add));
				y += ROW;

				labels.add(new Label("shown as", x, y - 10));
				add.accept(new FlatButton(x, y, fieldWidth, 18,
					Component.literal(line.mode().name().toLowerCase()), 0xFF4FC3F7, () -> {
						Node.Line now = current(Node.Line.class);
						put(new Node.Line(now.id(), now.speaker(), now.text(), cycle(now.mode()),
							now.animation(), now.next()));
						screen.refreshPanel();
					}));
				y += ROW;

				// A button rather than a text box. Typing the name of a gesture means
				// knowing it already, and getting it slightly wrong means an NPC that
				// silently does nothing.
				labels.add(new Label("gesture", x, y - 10));
				String gesture = line.animation() == null ? "" : line.animation();
				var known = com.mopicmp.npcstudio.client.entity.AnimationCatalogue.byId(gesture);
				add.accept(new FlatButton(x, y, fieldWidth, 18,
					Component.literal(known != null ? known.name() : "none"), 0xFFFF8A65,
					() -> screen.pickAnimation(gesture, picked -> {
						Node.Line now = current(Node.Line.class);
						put(new Node.Line(now.id(), now.speaker(), now.text(), now.mode(),
							picked.isEmpty() ? null : picked, now.next()));
					})));
				y += ROW;
			}
			case Node.Choice choice -> {
				add.accept(field(font, x, y, fieldWidth, "speaker", choice.speaker(),
					value -> {
						Node.Choice now = current(Node.Choice.class);
						put(new Node.Choice(now.id(), value, now.prompt(), now.mode(), now.options()));
					}, add));
				y += ROW;
				add.accept(field(font, x, y, fieldWidth, "question", choice.prompt(),
					value -> {
						Node.Choice now = current(Node.Choice.class);
						put(new Node.Choice(now.id(), now.speaker(), value, now.mode(), now.options()));
					}, add));
				y += ROW;

				List<Node.Option> options = choice.options();
				for (int i = 0; i < options.size(); i++) {
					int slot = i;
					add.accept(field(font, x, y, fieldWidth - 22, "answer " + (i + 1),
						options.get(i).label(), value -> replaceOption(slot, value), add));
					add.accept(new FlatButton(x + fieldWidth - 18, y, 18, 18,
						Component.literal("x"), 0xFFEF5350, () -> {
							removeOption(slot);
							screen.refreshPanel();
						}));
					y += ROW;
				}

				add.accept(new FlatButton(x, y, 90, 18, Component.literal("+ answer"), 0xFF66BB6A, () -> {
					addOption();
					screen.refreshPanel();
				}));
				y += ROW;

				// A question takes over the screen by default, but a quick aside
				// should be able to stay in the bar — so the writer can say which.
				labels.add(new Label("shown as", x, y - 10));
				add.accept(new FlatButton(x, y, fieldWidth, 18,
					Component.literal(choice.mode().name().toLowerCase()), 0xFFBA68C8, () -> {
						Node.Choice now = current(Node.Choice.class);
						put(new Node.Choice(now.id(), now.speaker(), now.prompt(), cycle(now.mode()), now.options()));
						screen.refreshPanel();
					}));
				y += ROW;
			}
			case Node.Act act -> {
				labels.add(new Label("does what", x, y - 10));
				add.accept(new FlatButton(x, y, fieldWidth, 18,
					Component.literal(effectName(act.effect())), 0xFFFF8A65, () -> {
						Node.Act now = current(Node.Act.class);
						put(new Node.Act(now.id(), nextEffect(now.effect()), now.next()));
						screen.refreshPanel();
					}));
				y += ROW;
				y = effectFields(font, act, x, y, fieldWidth, add);
			}
			case Node.Set set -> {
				add.accept(field(font, x, y, fieldWidth, "variable", set.variable(),
					value -> {
						Node.Set now = current(Node.Set.class);
						put(new Node.Set(now.id(), value, now.scope(), now.value(), now.next()));
					}, add));
				y += ROW;

				labels.add(new Label("belongs to", x, y - 10));
				add.accept(new FlatButton(x, y, fieldWidth, 18,
					Component.literal(set.scope() == Scope.PLAYER ? "the player" : "the world"),
					0xFF66BB6A, () -> {
						Node.Set now = current(Node.Set.class);
						put(new Node.Set(now.id(), now.variable(),
							now.scope() == Scope.PLAYER ? Scope.WORLD : Scope.PLAYER,
							now.value(), now.next()));
						screen.refreshPanel();
					}));
				y += ROW;

				// One box for the value, whatever its type. Typing `true` gives a flag
				// and `7` a number, the same way the file is written — a type picker
				// beside it would be a second place to say the same thing, and the two
				// would eventually disagree.
				add.accept(field(font, x, y, fieldWidth, "set to", show(set.value()),
					value -> {
						Node.Set now = current(Node.Set.class);
						put(new Node.Set(now.id(), now.variable(), now.scope(), parse(value), now.next()));
					}, add));
				y += ROW;
			}
			default -> {
				// `branch` is the one left. Its arms are conditions, and a condition
				// editor is a bigger piece of work than a panel — until it exists,
				// saying so is better than an empty box that looks broken.
				labels.add(new Label("conditions are file-only for now", x, y));
			}
		}

		// Where the fields ran out, so whoever is showing this panel knows whether
		// it fits and by how much it does not.
		contentBottom = y - top;

		add.accept(new FlatButton(x, top + height - 26, 90, 18,
			Component.literal("make start"), 0xFFFFCA28, () -> {
				state.start(state.nodes().get(index).id());
			}));
		add.accept(new FlatButton(x + 96, top + height - 26, 74, 18,
			Component.literal("delete"), 0xFFEF5350, screen::deleteSelected));
	}

	/**
	 * The fields a particular effect needs.
	 *
	 * Each effect asks for different things, so the form follows the effect
	 * rather than showing every field an `act` could ever have with most of them
	 * greyed out.
	 */
	private int effectFields(Font font, Node.Act act, int x, int y, int width,
			Consumer<AbstractWidget> add) {
		switch (act.effect()) {
			case Effect.PlayAnimation(String animation, int ticks) -> {
				labels.add(new Label("animation", x, y - 10));
				var known = com.mopicmp.npcstudio.client.entity.AnimationCatalogue.byId(animation);
				add.accept(new FlatButton(x, y, width, 18,
					Component.literal(known != null ? known.name()
						: animation.isEmpty() ? "— none —" : animation), 0xFFFF8A65,
					() -> screen.pickAnimation(animation, picked -> {
						Node.Act now = current(Node.Act.class);
						put(new Node.Act(now.id(),
							// Empty means "stop whatever is playing", which is a real thing
							// to want. It used to be quietly turned into a wave — a
							// placeholder from before the picker existed — so choosing
							// "no animation" set a wave instead.
							//
							// Choosing an animation also fills in its own length, because
							// that is what somebody who picks "bow" means by picking it.
							// Only when nothing was set yet: a length typed on purpose is
							// not to be overwritten by changing one's mind about which
							// animation it belongs to.
							new Effect.PlayAnimation(picked, ticks > 0 ? ticks
								: com.mopicmp.npcstudio.client.entity.NpcGestures.lengthOf(picked)),
							now.next()));
					})));
				y += ROW;

				add.accept(field(font, x, y, width, "how long, ticks", String.valueOf(ticks), value -> {
					Node.Act now = current(Node.Act.class);
					put(new Node.Act(now.id(),
						new Effect.PlayAnimation(animation, number(value, ticks)), now.next()));
				}, add));
				// Ticks are what the file stores and what every other duration in this
				// panel is written in, so ticks are what the field takes. The reading
				// underneath is for the person: nobody thinks in fortieths of a bow.
				labels.add(new Label(durationHint(animation, ticks), x, y + 20));
				y += ROW;
			}
			case Effect.GiveItem(String item, int count) -> {
				add.accept(field(font, x, y, width, "item", item, value -> {
					Node.Act now = current(Node.Act.class);
					put(new Node.Act(now.id(), new Effect.GiveItem(value, count), now.next()));
				}, add));
				y += ROW;
				add.accept(field(font, x, y, width, "how many", String.valueOf(count), value -> {
					Node.Act now = current(Node.Act.class);
					put(new Node.Act(now.id(), new Effect.GiveItem(item, number(value, count)), now.next()));
				}, add));
				y += ROW;
			}
			case Effect.TakeItem(String item, int count) -> {
				add.accept(field(font, x, y, width, "item", item, value -> {
					Node.Act now = current(Node.Act.class);
					put(new Node.Act(now.id(), new Effect.TakeItem(value, count), now.next()));
				}, add));
				y += ROW;
				add.accept(field(font, x, y, width, "how many", String.valueOf(count), value -> {
					Node.Act now = current(Node.Act.class);
					put(new Node.Act(now.id(), new Effect.TakeItem(item, number(value, count)), now.next()));
				}, add));
				y += ROW;
			}
			case Effect.RunCommand(String command) -> {
				add.accept(field(font, x, y, width, "command", command, value -> {
					Node.Act now = current(Node.Act.class);
					put(new Node.Act(now.id(), new Effect.RunCommand(value), now.next()));
				}, add));
				y += ROW;
			}
			case Effect.PlaySound(String sound, float volume, float pitch) -> {
				add.accept(field(font, x, y, width, "sound", sound, value -> {
					Node.Act now = current(Node.Act.class);
					put(new Node.Act(now.id(), new Effect.PlaySound(value, volume, pitch), now.next()));
				}, add));
				y += ROW;
			}
			case Effect.PlaceStructure(String structure, String anchor, int ticks) -> {
				add.accept(field(font, x, y, width, "structure", structure, value -> {
					Node.Act now = current(Node.Act.class);
					put(new Node.Act(now.id(), new Effect.PlaceStructure(value, anchor, ticks), now.next()));
				}, add));
				y += ROW;
				add.accept(field(font, x, y, width, "over how many ticks", String.valueOf(ticks), value -> {
					Node.Act now = current(Node.Act.class);
					put(new Node.Act(now.id(),
						new Effect.PlaceStructure(structure, anchor, number(value, ticks)), now.next()));
				}, add));
				y += ROW;
			}
			case Effect.Express(String expression, int ticks) -> {
				// A button that cycles rather than a typed name, because there are nine
				// of them and every one an author might type wrong is a face that
				// quietly does not happen.
				labels.add(new Label("expression", x, y - 10));
				var mood = com.mopicmp.npcstudio.entity.Expression.named(expression);
				add.accept(new FlatButton(x, y, width, 18,
					Component.literal(moodName(mood)), 0xFFFF8A65,
					() -> {
						Node.Act now = current(Node.Act.class);
						put(new Node.Act(now.id(),
							new Effect.Express(nextMood(mood).name(), ticks), now.next()));
					}));
				y += ROW;
				add.accept(field(font, x, y, width, "how many ticks (0 — держать)",
					String.valueOf(ticks), value -> {
						Node.Act now = current(Node.Act.class);
						put(new Node.Act(now.id(),
							new Effect.Express(expression, number(value, ticks)), now.next()));
					}, add));
				y += ROW;
			}
		}
		return y;
	}

	/** The expressions in the order the button walks through them. */
	private static com.mopicmp.npcstudio.entity.Expression nextMood(
			com.mopicmp.npcstudio.entity.Expression mood) {
		var all = com.mopicmp.npcstudio.entity.Expression.values();
		return all[(mood.ordinal() + 1) % all.length];
	}

	/** What an author calls each face, rather than what the format calls it. */
	private static String moodName(com.mopicmp.npcstudio.entity.Expression mood) {
		return switch (mood) {
			case NEUTRAL -> "обычное лицо";
			case HAPPY -> "радость";
			case SAD -> "грусть";
			case ANGRY -> "злость";
			case SURPRISED -> "удивление";
			case SCARED -> "испуг";
			case THINKING -> "задумчивость";
			case SLEEPY -> "сонность";
			case WINK -> "подмигнуть";
		};
	}

	/**
	 * What a tick count actually means, said in words underneath the field.
	 *
	 * Two things worth saying and neither of them obvious from a number. Zero
	 * does not mean "no time" — it means the character holds the pose until
	 * something else takes over, which is a useful thing to ask for and a
	 * bewildering thing to get by accident. And the animation's own length is
	 * shown, so that "shorter than it is" and "longer than it is" are visible
	 * choices rather than discoveries.
	 */
	private static String durationHint(String animation, int ticks) {
		if (ticks <= 0) return "0 — держать, пока не сменят";
		String said = String.format(java.util.Locale.ROOT, "%.1f с", ticks / 20f);
		int own = com.mopicmp.npcstudio.client.entity.NpcGestures.lengthOf(animation);
		if (own <= 0) return said;
		if (ticks < own) return said + " · короче самой анимации (" + own + ")";
		if (ticks > own) return said + " · дольше самой анимации (" + own + ")";
		return said + " · ровно её длина";
	}

	static String effectName(Effect effect) {
		return switch (effect) {
			case Effect.PlayAnimation _ -> "play an animation";
			case Effect.GiveItem _ -> "give an item";
			case Effect.TakeItem _ -> "take an item";
			case Effect.RunCommand _ -> "run a command";
			case Effect.PlaySound _ -> "play a sound";
			case Effect.PlaceStructure _ -> "place a structure";
			case Effect.Express _ -> "make a face";
		};
	}

	/** Cycles through the kinds, keeping nothing: the fields have no counterpart. */
	private static Effect nextEffect(Effect effect) {
		return switch (effect) {
			case Effect.PlayAnimation _ -> new Effect.GiveItem("minecraft:bread", 1);
			case Effect.GiveItem _ -> new Effect.TakeItem("minecraft:emerald", 1);
			case Effect.TakeItem _ -> new Effect.RunCommand("say hello");
			case Effect.RunCommand _ -> new Effect.PlaySound("minecraft:block.note_block.bell", 1f, 1f);
			case Effect.PlaySound _ -> new Effect.PlaceStructure("", "npc", 40);
			case Effect.PlaceStructure _ ->
				new Effect.Express(com.mopicmp.npcstudio.entity.Expression.HAPPY.name(), 60);
			case Effect.Express _ -> new Effect.PlayAnimation("wave",
				com.mopicmp.npcstudio.client.entity.NpcGestures.lengthOf("wave"));
		};
	}

	/** A value written the way the file writes it: bare, and read back by its shape. */
	private static String show(Value value) {
		return switch (value) {
			case Value.Flag(boolean v) -> String.valueOf(v);
			case Value.Num(double v) -> v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
			case Value.Text(String v) -> v;
		};
	}

	private static Value parse(String text) {
		if (text.equalsIgnoreCase("true")) return Value.of(true);
		if (text.equalsIgnoreCase("false")) return Value.of(false);
		try {
			return Value.of(Double.parseDouble(text));
		} catch (NumberFormatException notANumber) {
			return Value.of(text);
		}
	}

	private static int number(String text, int fallback) {
		try {
			return Integer.parseInt(text.trim());
		} catch (NumberFormatException notANumber) {
			return fallback;
		}
	}

	private EditBox field(Font font, int x, int y, int width, String label, String value,
			Consumer<String> onChange, Consumer<AbstractWidget> add) {
		labels.add(new Label(label, x, y - 10));
		EditBox box = new EditBox(font, x, y, width, 18, Component.literal(label));
		box.setMaxLength(512);
		box.setValue(value);
		box.setSuggestion(value.isEmpty() ? label : "");
		box.setResponder(text -> {
			box.setSuggestion(text.isEmpty() ? label : "");
			onChange.accept(text);
		});
		return box;
	}

	// -------------------------------------------------- editing the node

	private <T extends Node> T current(Class<T> type) {
		return type.cast(state.nodes().get(index));
	}

	private void put(Node node) {
		state.replace(index, node);
	}

	private static Node.Line withSpeaker(Node.Line line, String speaker) {
		return new Node.Line(line.id(), speaker, line.text(), line.mode(), line.animation(), line.next());
	}

	private static Node.Line withText(Node.Line line, String text) {
		return new Node.Line(line.id(), line.speaker(), text, line.mode(), line.animation(), line.next());
	}

	private static Presentation cycle(Presentation mode) {
		Presentation[] all = Presentation.values();
		return all[(mode.ordinal() + 1) % all.length];
	}

	/**
	 * Renames the node and every transition that pointed at it.
	 *
	 * Without carrying the references across, a rename would be a delete and a
	 * create — and since this happens on every keystroke, the graph would come
	 * apart while the name was still being typed.
	 */
	private void rename(String raw) {
		String to = raw.trim();
		if (to.isEmpty()) return;
		Node node = state.nodes().get(index);
		String from = node.id();
		if (to.equals(from)) return;
		if (state.nodeIds().contains(to)) return;

		List<Node> nodes = state.nodes();
		for (int i = 0; i < nodes.size(); i++) {
			if (i == index) continue;
			nodes.set(i, retarget(nodes.get(i), from, to));
		}
		state.replace(index, renamed(node, to));
		if (state.start().equals(from)) state.start(to);
		screen.renamed(from, to);
	}

	private static Node renamed(Node node, String id) {
		return switch (node) {
			case Node.Line line -> new Node.Line(id, line.speaker(), line.text(), line.mode(),
				line.animation(), line.next());
			case Node.Choice choice -> new Node.Choice(id, choice.speaker(), choice.prompt(), choice.mode(), choice.options());
			case Node.Set set -> new Node.Set(id, set.variable(), set.scope(), set.value(), set.next());
			case Node.Branch branch -> new Node.Branch(id, branch.arms(), branch.otherwise());
			case Node.Act act -> new Node.Act(id, act.effect(), act.next());
			case Node.End _ -> new Node.End(id);
			case Node.Every every -> new Node.Every(id, every.ticks(), every.next());
			case Node.Until until -> new Node.Until(id, until.condition(), until.next());
		};
	}

	private static Node retarget(Node node, String from, String to) {
		return switch (node) {
			case Node.Line line -> line.next().equals(from)
				? new Node.Line(line.id(), line.speaker(), line.text(), line.mode(), line.animation(), to)
				: line;
			case Node.Choice choice -> new Node.Choice(choice.id(), choice.speaker(), choice.prompt(), choice.mode(),
				choice.options().stream()
					.map(o -> o.next().equals(from)
						? new Node.Option(o.label(), o.colour(), o.condition(), to)
						: o)
					.toList());
			// Every other kind that points somewhere. These fell through a `default`
			// before, which meant renaming a node quietly broke every `set`, `act`
			// and `branch` wired to it — the wire stayed pointing at a name that no
			// longer existed, and the graph only failed later, when somebody walked
			// down that path.
			case Node.Set set -> set.next().equals(from)
				? new Node.Set(set.id(), set.variable(), set.scope(), set.value(), to) : set;
			case Node.Act act -> act.next().equals(from)
				? new Node.Act(act.id(), act.effect(), to) : act;
			case Node.Every every -> every.next().equals(from)
				? new Node.Every(every.id(), every.ticks(), to) : every;
			case Node.Until until -> until.next().equals(from)
				? new Node.Until(until.id(), until.condition(), to) : until;
			case Node.Branch branch -> new Node.Branch(branch.id(),
				branch.arms().stream()
					.map(arm -> arm.next().equals(from) ? new Node.Arm(arm.condition(), to) : arm)
					.toList(),
				branch.otherwise().equals(from) ? to : branch.otherwise());
			case Node.End _ -> node;
		};
	}

	private void replaceOption(int slot, String label) {
		Node.Choice choice = current(Node.Choice.class);
		List<Node.Option> options = new ArrayList<>(choice.options());
		if (slot >= options.size()) return;
		Node.Option old = options.get(slot);
		options.set(slot, new Node.Option(label, old.colour(), old.condition(), old.next()));
		put(new Node.Choice(choice.id(), choice.speaker(), choice.prompt(), choice.mode(), options));
	}

	private void addOption() {
		Node.Choice choice = current(Node.Choice.class);
		List<Node.Option> options = new ArrayList<>(choice.options());
		options.add(new Node.Option("…", null, new Condition.Always(), choice.id()));
		put(new Node.Choice(choice.id(), choice.speaker(), choice.prompt(), choice.mode(), options));
	}

	private void removeOption(int slot) {
		Node.Choice choice = current(Node.Choice.class);
		List<Node.Option> options = new ArrayList<>(choice.options());
		if (slot < options.size()) options.remove(slot);
		put(new Node.Choice(choice.id(), choice.speaker(), choice.prompt(), choice.mode(), options));
	}

	// ------------------------------------------------------------ drawing

	public void draw(GuiGraphicsExtractor graphics, Font font, int left, int top, int height) {
		graphics.fill(left, top, left + WIDTH, top + height, BACKGROUND);
		graphics.fill(left, top, left + 1, top + height, EDGE);

		Node node = state.nodes().get(index);
		graphics.text(font, Component.literal(GraphEditorScreen.titleOf(node)),
			left + PADDING, top + 10, GraphEditorScreen.colourOf(node));
		graphics.text(font, Component.literal("selected"),
			left + WIDTH - PADDING - font.width("selected"), top + 10, TEXT_DIM);
		// A rule under the heading. Without it the node's type sat directly on the
		// first field's label and read as though it belonged to that field.
		graphics.fill(left + PADDING, top + 24, left + WIDTH - PADDING, top + 25, EDGE);

		for (Label label : labels) {
			graphics.text(font, Component.literal(label.text()), label.x(), label.y(),
				label.text().equals("nothing to set") ? TEXT_DIM : TEXT);
		}
	}
}
