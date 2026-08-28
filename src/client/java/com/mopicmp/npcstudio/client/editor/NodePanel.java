package com.mopicmp.npcstudio.client.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.mopicmp.npcstudio.dialogue.Condition;
import com.mopicmp.npcstudio.dialogue.Effect;
import com.mopicmp.npcstudio.dialogue.Scope;
import com.mopicmp.npcstudio.dialogue.Sense;
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
					Component.literal(nameOf(set.scope())),
					0xFF66BB6A, () -> {
						Node.Set now = current(Node.Set.class);
						put(new Node.Set(now.id(), now.variable(), nextWritable(now.scope()),
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
			case Node.Do call -> {
				// Both fields are pickers, and that is the whole point of them. A skill
				// named by typing is a skill misspelt sooner or later, and a call to a
				// skill that is not there leaves a character standing perfectly still —
				// which from outside is a fight that never started. Twice now.
				labels.add(new Label("which skill", x, y - 10));
				add.accept(new FlatButton(x, y, fieldWidth, 18,
					Component.literal(call.segment().isEmpty() ? "—" : call.segment()),
					0xFF9575CD, () -> {
						Node.Do now = current(Node.Do.class);
						put(new Node.Do(now.id(), nextSkill(now.segment()), now.target(),
							now.with(), now.next()));
						screen.refreshPanel();
					}));
				y += ROW;

				labels.add(new Label("about whom", x, y - 10));
				add.accept(new FlatButton(x, y, fieldWidth, 18,
					Component.literal(markName(call.target())), 0xFF66BB6A, () -> {
						Node.Do now = current(Node.Do.class);
						put(new Node.Do(now.id(), now.segment(), nextMark(now.target()),
							now.with(), now.next()));
						screen.refreshPanel();
					}));
				y += ROW;

				if (!call.with().isEmpty()) {
					labels.add(new Label("also told: " + String.join(", ", call.with().keySet()),
						x, y));
					y += 12;
				}
			}
			case Node.Stop stop -> {
				// The same list, because a stop that does not name exactly what the call
				// named cancels nothing at all — and says nothing about having failed.
				labels.add(new Label("stop which skill", x, y - 10));
				add.accept(new FlatButton(x, y, fieldWidth, 18,
					Component.literal(stop.segment().isEmpty() ? "—" : stop.segment()),
					0xFF9575CD, () -> {
						Node.Stop now = current(Node.Stop.class);
						put(new Node.Stop(now.id(), nextSkill(now.segment()), now.next()));
						screen.refreshPanel();
					}));
				y += ROW;
			}
			case Node.Every every -> {
				// No condition in this one at all — it is a number, and it had been
				// filed with the conditions and left unreachable for that reason.
				add.accept(field(font, x, y, fieldWidth, "wait this many ticks",
					String.valueOf(every.ticks()), value -> {
						Node.Every now = current(Node.Every.class);
						put(new Node.Every(now.id(), Math.max(0, number(value, now.ticks())),
							now.next()));
					}, add));
				y += ROW;
			}
			case Node.Until until -> {
				labels.add(new Label("stands here until", x, y - 10));
				y += 2;
				y = conditionFields(font, until.condition(), x, y, fieldWidth, add,
					made -> {
						Node.Until now = current(Node.Until.class);
						put(new Node.Until(now.id(), made, now.next()));
					});
			}
			case Node.Branch branch -> {
				List<Node.Arm> arms = branch.arms();
				for (int i = 0; i < arms.size(); i++) {
					int slot = i;
					labels.add(new Label("go by exit " + (i + 1) + " if", x, y - 10));
					add.accept(new FlatButton(x + fieldWidth - 18, y - 12, 18, 14,
						Component.literal("x"), 0xFFEF5350, () -> {
							Node.Branch was = current(Node.Branch.class);
							List<Node.Arm> now = new java.util.ArrayList<>(was.arms());
							now.remove(slot);
							put(new Node.Branch(was.id(), List.copyOf(now), was.otherwise()));
							screen.refreshPanel();
						}));
					y += 4;
					y = conditionFields(font, arms.get(slot).condition(), x, y, fieldWidth, add,
						made -> {
							Node.Branch was = current(Node.Branch.class);
							List<Node.Arm> now = new java.util.ArrayList<>(was.arms());
							now.set(slot, new Node.Arm(made, now.get(slot).next()));
							put(new Node.Branch(was.id(), List.copyOf(now), was.otherwise()));
						});
					y += 6;
				}
				add.accept(new FlatButton(x, y, 90, 18, Component.literal("+ exit"),
					0xFF66BB6A, () -> {
						Node.Branch was = current(Node.Branch.class);
						List<Node.Arm> now = new java.util.ArrayList<>(was.arms());
						// Leading where the branch already leads when nothing matched, so
						// a fresh exit goes somewhere real rather than nowhere.
						now.add(new Node.Arm(new Condition.Always(), was.otherwise()));
						put(new Node.Branch(was.id(), List.copyOf(now), was.otherwise()));
						screen.refreshPanel();
					}));
				y += ROW;
			}
			default -> labels.add(new Label("nothing to fill in", x, y));
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

			// The verbs. All four take a mark, and a mark is picked from a list
			// rather than typed: there are four of them and every one an author can
			// spell wrong is a character who quietly does nothing.
			case Effect.WalkTo(String mark, float pace) -> {
				y = markButton(x, y, width, "walk to", mark, add,
					picked -> new Effect.WalkTo(picked, pace));
				labels.add(new Label("how fast", x, y - 10));
				add.accept(new FlatButton(x, y, width, 18,
					Component.literal(paceName(pace)), 0xFFFF8A65, () -> {
						Node.Act now = current(Node.Act.class);
						Effect.WalkTo was = (Effect.WalkTo) now.effect();
						put(new Node.Act(now.id(),
							new Effect.WalkTo(was.mark(), nextPace(was.pace())), now.next()));
						screen.refreshPanel();
					}));
				y += ROW;
			}
			case Effect.LookAt(String mark) ->
				y = markButton(x, y, width, "look at", mark, add, Effect.LookAt::new);
			case Effect.Fire(String mark) ->
				y = markButton(x, y, width, "shoot at", mark, add, Effect.Fire::new);
			case Effect.Strike(String mark) ->
				y = markButton(x, y, width, "swing at", mark, add, Effect.Strike::new);
			case Effect.Halt _ -> {
				// Nothing to fill in. Said out loud rather than left blank, because an
				// empty form reads as one that failed to load.
				labels.add(new Label("she stops where she is", x, y - 10));
			}
			case Effect.Guard(boolean up) -> {
				labels.add(new Label("carries herself as", x, y - 10));
				add.accept(new FlatButton(x, y, width, 18,
					Component.literal(up ? "somebody expecting a fight" : "usual"),
					0xFF9575CD, () -> {
						Node.Act now = current(Node.Act.class);
						put(new Node.Act(now.id(), new Effect.Guard(!up), now.next()));
						screen.refreshPanel();
					}));
				y += ROW;
			}
		}
		return y;
	}

	/** How tall one line of the condition editor is. Tighter than a field row. */
	private static final int CROW = 22;

	/**
	 * The rows a condition is made of, as widgets.
	 *
	 * <h2>What it refuses to show</h2>
	 *
	 * Anything nested deeper than a list of tests — see {@link ConditionRows}. It
	 * says so and changes nothing, which is the whole point: showing an
	 * approximation and then saving it would rewrite what somebody meant without a
	 * word about it, and that is the bug this project keeps meeting.
	 *
	 * @param onChange handed the condition the rows now mean, whenever one changes
	 * @return where the fields ran out
	 */
	private int conditionFields(Font font, Condition condition, int x, int y, int width,
			Consumer<AbstractWidget> add, Consumer<Condition> onChange) {
		ConditionRows rows = ConditionRows.read(condition);
		if (rows == null) {
			labels.add(new Label("nested deeper than this panel shows —", x, y));
			labels.add(new Label("edit it in the file", x, y + 10));
			return y + CROW + 4;
		}

		// The join, said in words. Only worth a button once there is something for
		// it to join: with one test, "all of" and "any of" mean the same thing, and a
		// control that changes nothing is a control that teaches nothing.
		if (rows.rows().size() > 1) {
			add.accept(new FlatButton(x, y, width, 16,
				Component.literal(rows.all() ? "all of these" : "any of these"), 0xFF4FC3F7,
				() -> change(condition, onChange, was -> was.joinedBy(!was.all()))));
			y += CROW;
		}

		for (int i = 0; i < rows.rows().size(); i++) {
			int slot = i;
			ConditionRows.Row row = rows.rows().get(i);

			// Turning a test round, what kind of test it is, and getting rid of it.
			add.accept(new FlatButton(x, y, 18, 16, Component.literal(row.not() ? "\u00ac" : " "),
				row.not() ? 0xFFEF5350 : 0xFF546E7A,
				() -> change(condition, onChange, was -> was.withRow(slot,
					new ConditionRows.Row(!was.rows().get(slot).not(),
						was.rows().get(slot).leaf())))));
			add.accept(new FlatButton(x + 20, y, width - 40, 16,
				Component.literal(leafName(row.leaf())), 0xFFBA68C8,
				() -> change(condition, onChange, was -> was.withRow(slot,
					new ConditionRows.Row(was.rows().get(slot).not(),
						nextLeaf(was.rows().get(slot).leaf()))))));
			add.accept(new FlatButton(x + width - 18, y, 18, 16, Component.literal("x"),
				0xFFEF5350, () -> change(condition, onChange, was -> was.without(slot))));
			y += CROW;

			y = leafFields(font, row.leaf(), x, y, width, add,
				made -> change(condition, onChange, was -> was.withRow(slot,
					new ConditionRows.Row(was.rows().get(slot).not(), made))));
			y += 4;
		}

		add.accept(new FlatButton(x, y, 90, 16, Component.literal("+ test"), 0xFF66BB6A,
			() -> change(condition, onChange, was -> was.plus(new ConditionRows.Row(false,
				new Condition.Compare(Sense.KNOWN.get(0), Scope.SENSE, Condition.Op.EQ,
					Value.of(true)))))));
		return y + CROW;
	}

	/**
	 * Edits the rows and hands back what they now mean.
	 *
	 * The rows are read again rather than closed over. A button holding the rows it
	 * was built with would undo whatever happened in between — the stale-copy edit,
	 * which shows up as clicks being ignored at random.
	 */
	private void change(Condition condition, Consumer<Condition> onChange,
			java.util.function.UnaryOperator<ConditionRows> edit) {
		ConditionRows rows = ConditionRows.read(condition);
		if (rows == null) return;
		onChange.accept(edit.apply(rows).write());
		screen.refreshPanel();
	}

	/** What one test says, in a few words. */
	private static String leafName(Condition leaf) {
		return switch (leaf) {
			case Condition.Compare compare -> compare.scope() == Scope.SENSE
				? "what she senses" : "a variable";
			case Condition.HasItem _ -> "the player is carrying";
			case Condition.Visited _ -> "has already been through";
			default -> "?";
		};
	}

	/**
	 * The next kind of test, keeping nothing from the last.
	 *
	 * The three ask about different things — a reading, an inventory, a place
	 * somebody has been — so there is nothing to carry across. Carrying a name from
	 * one to another would offer a sense as an item id, which reads as a mistake
	 * somebody made rather than one the editor made for them.
	 */
	private static Condition nextLeaf(Condition leaf) {
		return switch (leaf) {
			case Condition.Compare compare when compare.scope() == Scope.SENSE ->
				new Condition.Compare("", Scope.PLAYER, Condition.Op.EQ, Value.of(true));
			case Condition.Compare _ -> new Condition.HasItem("minecraft:stone", 1);
			case Condition.HasItem _ -> new Condition.Visited("");
			default -> new Condition.Compare(Sense.KNOWN.get(0), Scope.SENSE,
				Condition.Op.EQ, Value.of(true));
		};
	}

	/** The fields one test needs, which differ by what it asks about. */
	private int leafFields(Font font, Condition leaf, int x, int y, int width,
			Consumer<AbstractWidget> add, Consumer<Condition> onChange) {
		switch (leaf) {
			case Condition.Compare compare -> {
				if (compare.scope() == Scope.SENSE) {
					// A fixed list, so it is chosen rather than typed. A sense misspelt
					// reads false for ever, which looks exactly like a character who has
					// decided not to act — and nothing anywhere would say otherwise.
					add.accept(new FlatButton(x, y, width, 16,
						Component.literal(compare.variable()), 0xFF9575CD,
						() -> onChange.accept(new Condition.Compare(nextSense(compare.variable()),
							Scope.SENSE, compare.op(), compare.value()))));
					y += CROW;
				} else {
					add.accept(new FlatButton(x, y, width, 16,
						Component.literal(nameOf(compare.scope())), 0xFF66BB6A,
						() -> onChange.accept(new Condition.Compare(compare.variable(),
							nextReadable(compare.scope()), compare.op(), compare.value()))));
					y += CROW;
					add.accept(field(font, x, y + 10, width, "which variable", compare.variable(),
						value -> onChange.accept(new Condition.Compare(value, compare.scope(),
							compare.op(), compare.value())), add));
					y += CROW + 10;
				}

				add.accept(new FlatButton(x, y, 54, 16, Component.literal(opName(compare.op())),
					0xFF4FC3F7, () -> onChange.accept(new Condition.Compare(compare.variable(),
						compare.scope(), nextOp(compare.op()), compare.value()))));
				add.accept(field(font, x + 58, y, width - 58, "value", show(compare.value()),
					value -> onChange.accept(new Condition.Compare(compare.variable(),
						compare.scope(), compare.op(), parse(value))), add));
				y += CROW;
			}
			case Condition.HasItem(String item, int count) -> {
				add.accept(field(font, x, y + 10, width, "which item", item,
					value -> onChange.accept(new Condition.HasItem(value, count)), add));
				y += CROW + 10;
				add.accept(field(font, x, y + 10, width, "how many", String.valueOf(count),
					value -> onChange.accept(new Condition.HasItem(item,
						Math.max(1, number(value, count)))), add));
				y += CROW + 10;
			}
			case Condition.Visited(String node) -> {
				add.accept(field(font, x, y + 10, width, "which node", node,
					value -> onChange.accept(new Condition.Visited(value)), add));
				y += CROW + 10;
			}
			default -> labels.add(new Label("nothing to fill in", x, y));
		}
		return y;
	}

	private static String opName(Condition.Op op) {
		return switch (op) {
			case EQ -> "is";
			case NE -> "is not";
			case LT -> "<";
			case LE -> "\u2264";
			case GT -> ">";
			case GE -> "\u2265";
		};
	}

	private static Condition.Op nextOp(Condition.Op op) {
		var all = Condition.Op.values();
		return all[(op.ordinal() + 1) % all.length];
	}

	private static String nextSense(String name) {
		int at = Sense.KNOWN.indexOf(name);
		return Sense.KNOWN.get((at + 1) % Sense.KNOWN.size());
	}

	/**
	 * The next scope a condition may <em>read</em>, which is not the same list.
	 *
	 * Writing has three; reading has five, because a sense and what a call handed in
	 * can both be asked about and neither can be set. Sharing one list between the
	 * two would put half the language out of reach of the editor.
	 */
	private static Scope nextReadable(Scope scope) {
		return switch (scope) {
			case PLAYER -> Scope.WORLD;
			case WORLD -> Scope.CHARACTER;
			case CHARACTER -> Scope.GIVEN;
			case GIVEN, SENSE -> Scope.PLAYER;
		};
	}

	/** A button that walks through the marks, since there are only ever a few. */
	private int markButton(int x, int y, int width, String label, String mark,
			Consumer<AbstractWidget> add, java.util.function.Function<String, Effect> rebuild) {
		labels.add(new Label(label, x, y - 10));
		add.accept(new FlatButton(x, y, width, 18,
			Component.literal(markName(mark)), 0xFFFF8A65, () -> {
				Node.Act now = current(Node.Act.class);
				put(new Node.Act(now.id(), rebuild.apply(nextMark(mark)), now.next()));
				screen.refreshPanel();
			}));
		return y + ROW;
	}

	private static String nextMark(String mark) {
		var all = com.mopicmp.npcstudio.dialogue.Mark.KNOWN;
        int at = all.indexOf(mark);
		return all.get((at + 1) % all.size());
	}

	/** What an author calls each mark, rather than what the file calls it. */
	private static String markName(String mark) {
		return switch (mark) {
			case com.mopicmp.npcstudio.dialogue.Mark.LEAD -> "what she has noticed";
			case com.mopicmp.npcstudio.dialogue.Mark.PLAYER -> "the nearest player";
			case com.mopicmp.npcstudio.dialogue.Mark.KIN -> "another with the same brain";
			case com.mopicmp.npcstudio.dialogue.Mark.POST -> "where she was posted";
			case com.mopicmp.npcstudio.dialogue.Mark.NOTHING -> "nothing";
			default -> mark;
		};
	}

	/**
	 * Four paces rather than a slider.
	 *
	 * The number is continuous and the choice is not: nobody means 0.63, they
	 * mean "hurrying". A slider would invite fiddling with a difference nobody
	 * can see.
	 */
	private static float nextPace(float pace) {
		if (pace < 0.35f) return 0.45f;
		if (pace < 0.6f) return 0.75f;
		if (pace < 0.9f) return 1f;
		return 0.25f;
	}

	private static String paceName(float pace) {
		if (pace < 0.35f) return "creeping";
		if (pace < 0.6f) return "walking";
		if (pace < 0.9f) return "briskly";
		return "running";
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
			case Effect.WalkTo _ -> "walk somewhere";
			case Effect.Halt _ -> "stop walking";
			case Effect.LookAt _ -> "look at something";
			case Effect.Fire _ -> "shoot at something";
			case Effect.Strike _ -> "swing at something";
			case Effect.Guard _ -> "square up, or stand easy";
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
			case Effect.Express _ -> new Effect.WalkTo(
				com.mopicmp.npcstudio.dialogue.Mark.LEAD, 0.45f);
			case Effect.WalkTo _ -> new Effect.Halt();
			case Effect.Halt _ -> new Effect.LookAt(com.mopicmp.npcstudio.dialogue.Mark.PLAYER);
			case Effect.LookAt _ -> new Effect.Fire(com.mopicmp.npcstudio.dialogue.Mark.PLAYER);
			case Effect.Fire _ -> new Effect.Strike(com.mopicmp.npcstudio.dialogue.Mark.KIN);
			case Effect.Strike _ -> new Effect.Guard(true);
			case Effect.Guard _ -> new Effect.PlayAnimation("wave",
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

	/**
	 * The next skill round the list of every skill anything can be called into.
	 *
	 * A button that cycles rather than a menu that opens, matching how the scope
	 * and the effect are already chosen here. The list is this document's own
	 * skills followed by everybody else's, each carrying the document it lives in.
	 *
	 * An empty list leaves the name alone, which is honest: there is nothing to
	 * offer, and blanking what somebody already typed would be a worse answer than
	 * doing nothing.
	 */
	private String nextSkill(String now) {
		java.util.List<String> all = DialogueNames.skillsFor(state.id(), state.segments().keySet());
		if (all.isEmpty()) return now;
		int at = all.indexOf(now);
		return all.get((at + 1) % all.size());
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

	/**
	 * Who a variable belongs to, in words rather than in enum names.
	 *
	 * A sense is here for completeness only — it cannot be reached from the button
	 * below, because a sense cannot be written. Leaving it out of this switch
	 * instead would mean a graph that somehow held one showed as "the world", which
	 * is the more dangerous kind of wrong: it reads as correct.
	 */
	private static String nameOf(Scope scope) {
		return switch (scope) {
			case PLAYER -> "the player";
			case WORLD -> "the world";
			case CHARACTER -> "this character";
			case SENSE -> "what she senses (cannot be set)";
			case GIVEN -> "what she was asked to do (cannot be set)";
		};
	}

	/**
	 * The next scope a `set` node may write to, cycling.
	 *
	 * <h2>Why this is not a two-way toggle any more</h2>
	 *
	 * It was, when there were two scopes, and it was written as "player, else
	 * world". A third scope turned that into a quiet corruption: a node belonging
	 * to the character read as "the world" — because it was not PLAYER — and one
	 * click moved it to PLAYER, changing which box the graph writes to without
	 * anybody having asked for it.
	 */
	private static Scope nextWritable(Scope scope) {
		return switch (scope) {
			case PLAYER -> Scope.WORLD;
			case WORLD -> Scope.CHARACTER;
			// Including the way out of a scope that should never have got here.
			case CHARACTER, SENSE, GIVEN -> Scope.PLAYER;
		};
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
			case Node.Do call ->
				new Node.Do(id, call.segment(), call.target(), call.with(), call.next());
			case Node.Stop stop -> new Node.Stop(id, stop.segment(), stop.next());
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
			case Node.Do call -> call.next().equals(from)
				? new Node.Do(call.id(), call.segment(), call.target(), call.with(), to) : call;
			case Node.Stop stop -> stop.next().equals(from)
				? new Node.Stop(stop.id(), stop.segment(), to) : stop;
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
