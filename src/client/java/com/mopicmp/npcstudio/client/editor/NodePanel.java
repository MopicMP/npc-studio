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

	// The frames. Faint fills rather than strong ones: they are there to say what
	// belongs with what, and a box that shouts is a box that competes with the fields
	// inside it. The arm is warmer than the test so that the nesting reads without
	// being counted, and "otherwise" is cooler than both because it is not in the
	// order — see the branch arm of build.
	private static final int ARM_FILL = 0x1A4FC3F7;
	private static final int ARM_EDGE = 0xFF3C5A6B;
	private static final int TEST_FILL = 0x18000000;
	private static final int TEST_EDGE = 0xFF33414D;
	private static final int ELSE_FILL = 0x14607D8B;
	private static final int ELSE_EDGE = 0xFF37424C;
	/** The gap a carried frame would drop into. */
	private static final int DROP = 0xFF66BB6A;

	private final GraphEditorScreen screen;
	private final EditorState state;
	private final int index;
	private final List<Label> labels = new ArrayList<>();

	/**
	 * A row that shows a fact instead of taking a value: framed like a field, but dead.
	 *
	 * There was no such thing here, so "where it hangs" was bare text floating between two
	 * framed rows — reported as having no formatting at all, and it did not. A panel where
	 * some rows are boxes and one is loose text reads as a row that failed to draw.
	 *
	 * Dead on purpose rather than an unfocusable field: the coordinates are not typed, they
	 * are pointed at with the two buttons underneath, and a box somebody can put a cursor
	 * in is a box they will try to type in.
	 */
	private record Framed(String text, int x, int y, int wide) { }

	private final List<Framed> framed = new ArrayList<>();

	private record Label(String text, int x, int y) { }

	/**
	 * Words that are read again every frame, because what they say can change.
	 *
	 * Every other label in this panel describes the document, which only changes
	 * when somebody edits it — and an edit rebuilds the panel. Where the character
	 * calls home is not like that: it is a fact about her, it is moved by a packet
	 * that comes back a tick or two later, and she can walk out of range while the
	 * panel is open. Held as a fixed string it was answering with whatever was true
	 * at the moment the node was selected.
	 */
	private record Reading(java.util.function.Supplier<String> words, int x, int y) { }

	private final List<Reading> readings = new ArrayList<>();

	/**
	 * A box drawn behind the fields, saying what belongs with what.
	 *
	 * <h2>Why this exists</h2>
	 *
	 * A branch used to be one column of buttons with a caption every so often, and
	 * where one exit ended and the next began could only be worked out by counting.
	 * Reported as exactly that — and the fault it hides is the worst one a branch has,
	 * because the arms are tried in order and the first that holds takes it, so an arm
	 * in the wrong place is true, reached by nobody, and looks perfectly correct.
	 *
	 * <h2>Why it is drawn rather than built out of widgets</h2>
	 *
	 * Because it is not a control. {@link #draw} runs before the screen renders the
	 * widgets, so a rectangle recorded here lands behind them with no clipping, no
	 * z-order and nothing to click through.
	 */
	private record Frame(int x, int y, int w, int h, int fill, int edge) { }

	private final List<Frame> frames = new ArrayList<>();

	/**
	 * Somewhere a frame can be picked up by, and what it would move.
	 *
	 * {@code test} is -1 for a whole arm. The pair is enough to name everything that
	 * can be dragged in this panel, and keeping it to a pair is deliberate: a grip
	 * that could mean several things is a grip that has to be explained.
	 */
	private record Grip(int x, int y, int w, int h, int arm, int test) { }

	private final List<Grip> grips = new ArrayList<>();

	/** Where a frame ended up, so that a drop can be told which gap it landed in. */
	private record Slot(int arm, int test, int top, int bottom) {
		int middle() { return (top + bottom) / 2; }
	}

	private final List<Slot> slots = new ArrayList<>();

	/** What is being carried, or null — which is nearly always. */
	private Grip carrying;

	/** Where the pointer is while carrying, in the same coordinates as the slots. */
	private int carryAt;

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
		framed.clear();
		readings.clear();
		frames.clear();
		grips.clear();
		slots.clear();
		measuring = font;
		Node node = state.nodes().get(index);
		int x = left + PADDING;
		int y = top + 40;
		int fieldWidth = WIDTH - PADDING * 2;

		add.accept(field(font, x, y, fieldWidth, say("npc_studio.node.name"), node.id(), value -> rename(value), add));
		y += ROW;

		switch (node) {
			case Node.Line line -> {
				add.accept(field(font, x, y, fieldWidth, say("npc_studio.node.speaker"), line.speaker(),
					value -> put(withSpeaker(current(Node.Line.class), value)), add));
				y += ROW;
				// The plain field and the button beside it are two ways at the same words,
				// and both stay. The field is faster for a typo and cannot give a line a
				// colour; the window can do everything and is a trip. Making the field
				// read-only the moment a line was decorated would punish somebody for
				// having used the feature.
				add.accept(field(font, x, y, fieldWidth - 22, say("npc_studio.node.line"), line.text().plain(),
					value -> put(withText(current(Node.Line.class), value)), add));
				add.accept(new FlatButton(x + fieldWidth - 18, y, 18, 18,
					Component.literal("✎"), 0xFF4FC3F7, () -> screen.write(say("npc_studio.node.line"),
						current(Node.Line.class).text(), words -> {
							Node.Line now = current(Node.Line.class);
							put(now.withText(words));
						})));
				y += ROW;

				labels.add(new Label(say("npc_studio.node.shown_as"), x, y - 10));
				add.accept(new FlatButton(x, y, fieldWidth, 18,
					modeName(line.mode()), 0xFF4FC3F7, () -> {
						Node.Line now = current(Node.Line.class);
						put(now.withMode(cycle(now.mode())));
						screen.refreshPanel();
					}));
				y += ROW;

				// How long this one stays up. Wanted for the last line before something
				// happens: "follow me" is not waiting to be read and clicked past, it is
				// waiting for the scene to move — and left to the document's rule it
				// either vanishes mid-walk or never leaves at all.
				labels.add(new Label(say("npc_studio.node.lasts"), x, y - 10));
				int atY = y;
				add.accept(new FlatButton(x, y, fieldWidth, 18,
					line.lasts() == Node.Line.USES_DOCUMENT
						? Component.translatable("npc_studio.node.lasts_document")
						: Component.translatable("npc_studio.node.lasts_of", line.lasts() / 20),
					0xFF4FC3F7,
					() -> screen.pick(x, atY + 18, fieldWidth, lastsChoices())));
				y += ROW;

				// Whose head is beside the line. A line the player speaks names nobody
				// in the world, so it cannot be read off the entity the packet carries
				// and has to be said here.
				labels.add(new Label(say("npc_studio.node.face"), x, y - 10));
				int faceY = y;
				add.accept(new FlatButton(x, y, fieldWidth, 18,
					Component.translatable("npc_studio.node.face_"
						+ line.face().name().toLowerCase()), 0xFF4FC3F7,
					() -> screen.pick(x, faceY + 18, fieldWidth, faceChoices())));
				y += ROW;

				// And what colour the name is drawn in. Empty is not a colour: it means
				// the document's own answer for this name, which is where a character's
				// colour is said once instead of on every line she has.
				labels.add(new Label(say("npc_studio.node.name_colour"), x, y - 10));
				int tintY = y;
				add.accept(new FlatButton(x, y, fieldWidth, 18,
					line.nameColour().isEmpty()
						? Component.translatable("npc_studio.node.name_colour_document")
						: Component.translatable("npc_studio.colour." + line.nameColour()),
					com.mopicmp.npcstudio.dialogue.text.Tint.of(line.nameColour()),
					() -> screen.pick(x, tintY + 18, fieldWidth, nameColourChoices())));
				y += ROW;

				// A button rather than a text box. Typing the name of a gesture means
				// knowing it already, and getting it slightly wrong means an NPC that
				// silently does nothing.
				labels.add(new Label(say("npc_studio.node.gesture"), x, y - 10));
				String gesture = line.animation() == null ? "" : line.animation();
				var known = com.mopicmp.npcstudio.client.entity.AnimationCatalogue.byId(gesture);
				add.accept(new FlatButton(x, y, fieldWidth, 18,
					Component.literal(known != null ? known.name() : say("npc_studio.node.none")), 0xFFFF8A65,
					() -> screen.pickAnimation(gesture, picked -> {
						Node.Line now = current(Node.Line.class);
						put(now.withAnimation(picked.isEmpty() ? null : picked));
					})));
				y += ROW;
			}
			case Node.Choice choice -> {
				add.accept(field(font, x, y, fieldWidth, say("npc_studio.node.speaker"), choice.speaker(),
					value -> {
						Node.Choice now = current(Node.Choice.class);
						put(new Node.Choice(now.id(), value, now.prompt(), now.mode(), now.options()));
					}, add));
				y += ROW;
				add.accept(field(font, x, y, fieldWidth - 22, say("npc_studio.node.question"), choice.prompt().plain(),
					value -> {
						Node.Choice now = current(Node.Choice.class);
						put(new Node.Choice(now.id(), now.speaker(), now.prompt().reworded(value),
							now.mode(), now.options()));
					}, add));
				add.accept(new FlatButton(x + fieldWidth - 18, y, 18, 18,
					Component.literal("✎"), 0xFFBA68C8, () -> screen.write(say("npc_studio.node.question"),
						current(Node.Choice.class).prompt(), words -> {
							Node.Choice now = current(Node.Choice.class);
							put(new Node.Choice(now.id(), now.speaker(), words, now.mode(),
								now.options()));
						})));
				y += ROW;

				List<Node.Option> options = choice.options();
				for (int i = 0; i < options.size(); i++) {
					int slot = i;
					add.accept(field(font, x, y, fieldWidth - 40, say("npc_studio.node.answer_n", i + 1),
						options.get(i).label().plain(), value -> replaceOption(slot, value), add));
					add.accept(new FlatButton(x + fieldWidth - 36, y, 18, 18,
						Component.literal("✎"), 0xFFBA68C8, () -> screen.write(
							say("npc_studio.node.answer_n", slot + 1),
							current(Node.Choice.class).options().get(slot).label(),
							words -> dressOption(slot, words))));
					add.accept(new FlatButton(x + fieldWidth - 18, y, 18, 18,
						Component.literal("x"), 0xFFEF5350, () -> {
							removeOption(slot);
							screen.refreshPanel();
						}));
					y += ROW;
				}

				add.accept(new FlatButton(x, y, 90, 18, Component.translatable("npc_studio.node.add_answer"), 0xFF66BB6A, () -> {
					addOption();
					screen.refreshPanel();
				}));
				y += ROW;

				// A question lives in the bar or inside a cutscene, and the full screen
				// is not on offer — see Node.Choice's own note for why it was taken off.
				// Left on the cycle it would be a button that appears to do nothing:
				// pressing it would land on a staging the node refuses and come straight
				// back to the bar.
				labels.add(new Label(say("npc_studio.node.shown_as"), x, y - 10));
				add.accept(new FlatButton(x, y, fieldWidth, 18,
					modeName(choice.mode()), 0xFFBA68C8, () -> {
						Node.Choice now = current(Node.Choice.class);
						put(new Node.Choice(now.id(), now.speaker(), now.prompt(),
							otherStaging(now.mode()), now.options()));
						screen.refreshPanel();
					}));
				y += ROW;
			}
			case Node.Act act -> {
				labels.add(new Label(say("npc_studio.node.does_what"), x, y - 10));
				int at = y;
				add.accept(new FlatButton(x, y, fieldWidth, 18,
					Component.literal(effectName(act.effect()) + "  \u25be"), 0xFFFF8A65,
					() -> screen.pick(x, at + 18, fieldWidth, effectChoices())));
				y += ROW;
				y = effectFields(font, act, x, y, fieldWidth, add);
			}
			case Node.Set set -> {
				// The same pairing as on a comparison, and for the same reason: this is
				// the writing end of the trap. A flag set under "player" here and read
				// under "world" over there is two variables with one name, and the only
				// symptom is a scene that goes the wrong way.
				labels.add(new Label(say("npc_studio.node.belongs_to"), x, y - 10));
				int atList = y;
				add.accept(new FlatButton(x, y, fieldWidth, 18,
					Component.literal(set.variable().isEmpty()
						? say("npc_studio.node.no_variable")
						: set.variable() + " · " + nameOf(set.scope()) + "  ▾"),
					0xFF66BB6A,
					() -> screen.pick(x, atList + 18, fieldWidth, variableChoices(
						set.variable(), set.scope(), typeOf(set.value()), true,
						(picked, where) -> {
							Node.Set now = current(Node.Set.class);
							put(new Node.Set(now.id(), picked, where, now.value(), now.how(),
								now.next()));
						}))));
				y += ROW;

				add.accept(field(font, x, y, fieldWidth, say("npc_studio.node.variable"), set.variable(),
					value -> {
						Node.Set now = current(Node.Set.class);
						put(new Node.Set(now.id(), value, now.scope(), now.value(), now.how(),
							now.next()));
					}, add));
				y += ROW;

				// One box for the value, whatever its type. Typing `true` gives a flag
				// and `7` a number, the same way the file is written — a type picker
				// beside it would be a second place to say the same thing, and the two
				// would eventually disagree.
				// Put it there, or add it to what is there. One button rather than a
				// second kind of node: it is the same act on the same variable, and
				// counting was simply not expressible before — which is why a task like
				// "do this three times" needed three flags and three branches.
				add.accept(new FlatButton(x, y, fieldWidth, 18,
					Component.translatable(set.how() == Node.Set.Change.ADD
						? "npc_studio.node.set_adds" : "npc_studio.node.set_puts"),
					set.how() == Node.Set.Change.ADD ? 0xFFFFCA28 : 0xFF4FC3F7, () -> {
						Node.Set now = current(Node.Set.class);
						put(new Node.Set(now.id(), now.variable(), now.scope(), now.value(),
							now.how() == Node.Set.Change.ADD
								? Node.Set.Change.PUT : Node.Set.Change.ADD,
							now.next()));
						screen.refreshPanel();
					}));
				y += ROW;

				add.accept(field(font, x, y, fieldWidth, say(set.how() == Node.Set.Change.ADD
						? "npc_studio.node.set_by" : "npc_studio.node.set_to"), show(set.value()),
					value -> {
						Node.Set now = current(Node.Set.class);
						put(new Node.Set(now.id(), now.variable(), now.scope(), parse(value),
							now.how(), now.next()));
					}, add));
				y += ROW;
			}
			case Node.Do call -> {
				// Both fields are pickers, and that is the whole point of them. A skill
				// named by typing is a skill misspelt sooner or later, and a call to a
				// skill that is not there leaves a character standing perfectly still —
				// which from outside is a fight that never started. Twice now.
				labels.add(new Label(say("npc_studio.node.which_skill"), x, y - 10));
				add.accept(new FlatButton(x, y, fieldWidth, 18,
					Component.literal(call.segment().isEmpty() ? "—" : call.segment()),
					0xFF9575CD, () -> {
						Node.Do now = current(Node.Do.class);
						put(new Node.Do(now.id(), nextSkill(now.segment()), now.target(),
							now.with(), now.next()));
						screen.refreshPanel();
					}));
				y += ROW;

				labels.add(new Label(say("npc_studio.node.about_whom"), x, y - 10));
				add.accept(new FlatButton(x, y, fieldWidth, 18,
					Component.literal(markName(call.target())), 0xFF66BB6A, () -> {
						Node.Do now = current(Node.Do.class);
						put(new Node.Do(now.id(), now.segment(), nextMark(now.target()),
							now.with(), now.next()));
						screen.refreshPanel();
					}));
				y += ROW;

				if (!call.with().isEmpty()) {
					labels.add(new Label(say("npc_studio.node.also_told",
						String.join(", ", call.with().keySet())),
						x, y));
					y += 12;
				}
			}
			case Node.Stop stop -> {
				// The same list, because a stop that does not name exactly what the call
				// named cancels nothing at all — and says nothing about having failed.
				labels.add(new Label(say("npc_studio.node.stop_which"), x, y - 10));
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
				add.accept(field(font, x, y, fieldWidth, say("npc_studio.node.wait_ticks"),
					String.valueOf(every.ticks()), value -> {
						Node.Every now = current(Node.Every.class);
						put(new Node.Every(now.id(), Math.max(0, number(value, now.ticks())),
							now.next()));
					}, add));
				y += ROW;
			}
			case Node.Until until -> {
				labels.add(new Label(say("npc_studio.node.stands_until"), x, y - 10));
				y += 2;
				y = conditionFields(font, until.condition(), x, y, fieldWidth, add,
					made -> {
						Node.Until now = current(Node.Until.class);
						put(new Node.Until(now.id(), made, now.next()));
					});
			}
			// What this document remembers, put back. The panel is two questions,
			// because there are only two: which names, and whether where the player has
			// been counts as something remembered. It does — see Node.Forget.
			case Node.Forget forget -> {
				labels.add(new Label(say("npc_studio.node.forget_what"), x, y - 10));
				add.accept(new FlatButton(x, y, fieldWidth, 18,
					Component.translatable(forget.everything()
						? "npc_studio.node.forget_all" : "npc_studio.node.forget_named"),
					0xFF66BB6A, () -> {
						Node.Forget now = current(Node.Forget.class);
						// Only the switch moves. What is ticked stays ticked underneath
						// it, so trying "everything" and going back is not a way to lose
						// the list somebody built.
						put(new Node.Forget(now.id(), !now.everything(), now.variables(),
							now.scope(), now.visited(), now.next()));
						screen.refreshPanel();
					}));
				y += ROW;

				if (!forget.everything()) {
					int atNames = y;
					add.accept(new FlatButton(x, y, fieldWidth, 18,
						Component.literal(forget.naming().isEmpty()
							? say("npc_studio.node.forget_none")
							: String.join(", ", forget.naming()) + "  ▾"),
						0xFFBA68C8,
						() -> screen.pick(x, atNames + 18, fieldWidth, forgetChoices(forget))));
					y += ROW;
				}

				labels.add(new Label(say("npc_studio.node.belongs_to"), x, y - 10));
				int atScope = y;
				add.accept(new FlatButton(x, y, fieldWidth, 18,
					Component.literal(nameOf(forget.scope()) + "  ▾"), 0xFF66BB6A,
					() -> screen.pick(x, atScope + 18, fieldWidth, scopeChoices(forget.scope(),
						where -> {
							Node.Forget now = current(Node.Forget.class);
							put(new Node.Forget(now.id(), now.everything(), now.variables(),
								where, now.visited(), now.next()));
						}))));
				y += ROW;

				// Where the player has been. Its own switch and not folded into the
				// names, because it is the half that no number of `set` nodes could do
				// and the half people do not know they need until a replayed scene skips
				// a branch that it already decided the first time round.
				add.accept(new FlatButton(x, y, fieldWidth, 18,
					Component.translatable(forget.visited()
						? "npc_studio.node.forget_visited_yes" : "npc_studio.node.forget_visited_no"),
					forget.visited() ? 0xFF4FC3F7 : 0xFF546E7A, () -> {
						Node.Forget now = current(Node.Forget.class);
						put(new Node.Forget(now.id(), now.everything(), now.variables(),
							now.scope(), !now.visited(), now.next()));
						screen.refreshPanel();
					}));
				y += ROW;
			}
			// The one node where the order of what is on the panel is the order the
			// graph runs in. Everywhere else a list of fields is a list of settings;
			// here it is the sequence the arms are tried in, first one that holds takes
			// it — so the arms are framed, numbered, and can be picked up and moved.
			case Node.Branch branch -> {
				List<Node.Arm> arms = branch.arms();
				for (int i = 0; i < arms.size(); i++) {
					int slot = i;
					int mark = frames.size();
					int armTop = y;

					// The number is not decoration. It is the order, and the order is
					// what decides which arm ever answers — so it is redrawn from the
					// arm's place in the list every time, and moving an arm renames it.
					labels.add(new Label(say("npc_studio.node.exit_if", i + 1), x + 14, armTop + 5));
					add.accept(new FlatButton(x + fieldWidth - 18, armTop + 2, 16, 14,
						Component.literal("x"), 0xFFEF5350, () -> {
							Node.Branch was = current(Node.Branch.class);
							List<Node.Arm> now = new java.util.ArrayList<>(was.arms());
							if (slot < now.size()) now.remove(slot);
							put(new Node.Branch(was.id(), List.copyOf(now), was.otherwise()));
							screen.refreshPanel();
						}));
					// Everything along the header bar except the cross is somewhere to
					// take hold of: a grip you have to find is a grip nobody finds.
					grips.add(new Grip(x, armTop, fieldWidth - 20, 18, slot, -1));
					y = armTop + 22;

					y = conditionFields(font, arms.get(slot).condition(), x + 4, y, fieldWidth - 8,
						add, made -> {
							Node.Branch was = current(Node.Branch.class);
							List<Node.Arm> now = new java.util.ArrayList<>(was.arms());
							if (slot >= now.size()) return;
							now.set(slot, new Node.Arm(made, now.get(slot).next()));
							put(new Node.Branch(was.id(), List.copyOf(now), was.otherwise()));
						}, slot);

					// Where this arm goes, inside the arm's own frame.
					//
					// It was nowhere on this panel at all until now: an exit is a port on
					// the box, and the wire is dragged across the canvas. Which means
					// answering "where does the third one lead" involved counting ports
					// down the side of a box — while the thing that decides whether the
					// third one is ever reached was over here.
					String goes = arms.get(slot).next();
					int leadsAt = y;
					labels.add(new Label(say("npc_studio.node.leads_to"), x + 10, y - 10));
					add.accept(new FlatButton(x + 10, y, fieldWidth - 20, 16,
						Component.literal(goes + "  ▾"), 0xFF4FC3F7,
						() -> screen.pick(x + 10, leadsAt + 16, fieldWidth - 20,
							nodeChoices(goes, picked -> {
								Node.Branch was = current(Node.Branch.class);
								List<Node.Arm> now = new java.util.ArrayList<>(was.arms());
								if (slot >= now.size()) return;
								now.set(slot, new Node.Arm(now.get(slot).condition(), picked));
								put(new Node.Branch(was.id(), List.copyOf(now), was.otherwise()));
							}))));
					y += 22;

					frames.add(mark, new Frame(x, armTop, fieldWidth, y - armTop, ARM_FILL, ARM_EDGE));
					slots.add(new Slot(slot, -1, armTop, y));
					y += 8;
				}

				// The way out when nothing held.
				//
				// The same frame as an arm, because it is one more way out of the same
				// node and hiding that helped nobody. Dimmed and without a grip, because
				// it is not in the order: it is what is left when the order runs out, so
				// there is no place it could be moved to.
				int elseTop = y;
				labels.add(new Label(say("npc_studio.node.otherwise"), x + 8, elseTop + 5));
				int elseAt = elseTop + 22;
				add.accept(new FlatButton(x + 10, elseAt, fieldWidth - 20, 16,
					Component.literal(branch.otherwise() + "  ▾"), 0xFF78909C,
					() -> screen.pick(x + 10, elseAt + 16, fieldWidth - 20,
						nodeChoices(branch.otherwise(), picked -> {
							Node.Branch was = current(Node.Branch.class);
							put(new Node.Branch(was.id(), was.arms(), picked));
						}))));
				y = elseAt + 22;
				frames.add(new Frame(x, elseTop, fieldWidth, y - elseTop, ELSE_FILL, ELSE_EDGE));
				y += 8;

				add.accept(new FlatButton(x, y, 90, 18, Component.translatable("npc_studio.node.add_exit"),
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
			// A fork with nothing to set but how many ways out it has. There is no
			// condition to write and no weight to choose, so the whole panel is a count
			// — which is the point of the node: the graph decides, not the author.
			case Node.Chance chance -> {
				labels.add(new Label(say("npc_studio.node.ways", chance.ways().size()), x, y - 10));
				y += 6;
				add.accept(new FlatButton(x, y, 90, 18,
					Component.translatable("npc_studio.node.add_way"), 0xFF66BB6A, () -> {
						Node.Chance was = current(Node.Chance.class);
						if (was.ways().size() >= Node.Chance.MOST) return;
						List<String> now = new java.util.ArrayList<>(was.ways());
						// Leading back to the fork itself, the same as every fresh wire in
						// this editor: a way that goes nowhere would be a null the graph
						// walks into, and one that goes somewhere real is a guess.
						now.add(was.id());
						put(new Node.Chance(was.id(), now));
						screen.refreshPanel();
					}));
				add.accept(new FlatButton(x + 96, y, 90, 18,
					Component.translatable("npc_studio.node.drop_way"), 0xFFEF5350, () -> {
						Node.Chance was = current(Node.Chance.class);
						// Two is the fewest a fork can have and still be one. Below that it
						// is a node that pretends to choose, which reads as broken to
						// everybody including the person who made it.
						if (was.ways().size() <= 2) return;
						List<String> now = new java.util.ArrayList<>(was.ways());
						now.removeLast();
						put(new Node.Chance(was.id(), now));
						screen.refreshPanel();
					}));
				y += ROW;
			}
			// A wait with one row per thing it is listening for. Each row is a name chosen
			// from what this document shows, because a name typed wrong here is a button
			// that is pressed and does nothing — and the wires out of the box are drawn
			// against these rows, so the row is also the way out.
			case Node.Pressed waiting -> {
				labels.add(new Label(say("npc_studio.node.presses", waiting.presses().size()),
					x, y - 10));
				y += 6;
				for (int i = 0; i < waiting.presses().size(); i++) {
					int which = i;
					Node.Press press = waiting.presses().get(i);
					int atRow = y;
					add.accept(new FlatButton(x, y, fieldWidth - 24, 18,
						Component.literal((press.shown().isEmpty()
							? say("npc_studio.node.trait_unnamed") : press.shown()) + "  ▾"),
						0xFF90A4AE,
						() -> screen.pick(x, atRow + 18, fieldWidth - 24, pressChoices(which))));
					add.accept(new FlatButton(x + fieldWidth - 20, y, 20, 18,
						Component.literal("×"), 0xFFEF5350, () -> {
							Node.Pressed was = current(Node.Pressed.class);
							if (was.presses().size() <= 1) return;
							List<Node.Press> now = new java.util.ArrayList<>(was.presses());
							now.remove(which);
							put(new Node.Pressed(was.id(), now));
							screen.refreshPanel();
						}));
					y += ROW;
				}
				add.accept(new FlatButton(x, y, 120, 18,
					Component.translatable("npc_studio.node.add_press"), 0xFF66BB6A, () -> {
						Node.Pressed was = current(Node.Pressed.class);
						List<Node.Press> now = new java.util.ArrayList<>(was.presses());
						// Leading back to the wait itself, like every other fresh wire here.
						now.add(new Node.Press("", was.id()));
						put(new Node.Pressed(was.id(), now));
						screen.refreshPanel();
					}));
				y += ROW;
			}
			case Node.Walk walk -> y = routeFields(font, walk, x, y, fieldWidth, add);
			case Node.End end -> {
				// The one thing an ending can be asked. It lives here rather than on the
				// character because it is a fact about the scene: the same guard, placed
				// twice, might be sent back to her post after one errand and left where
				// she stands after another.
				labels.add(new Label(say("npc_studio.node.and_then_she"), x, y - 10));
				add.accept(new FlatButton(x, y, fieldWidth, 18,
					Component.translatable("npc_studio.end.then."
						+ end.homing().name().toLowerCase()), 0xFF78909C, () -> {
						Node.End now = current(Node.End.class);
						Node.Homing[] all = Node.Homing.values();
						put(new Node.End(now.id(),
							all[(now.homing().ordinal() + 1) % all.length]));
						screen.refreshPanel();
					}));
				y += ROW;
				if (end.homing() != Node.Homing.STAY) {
					// Where home actually is, in numbers, rather than a claim about where
					// it ought to be. The claim was "home is where she was placed", and for
					// a character placed before homes were kept it is untrue: hers was
					// settled on her first tick, which is wherever her graph had already
					// walked her. Reported as "she does not find the point where I put
					// her", and the panel had been agreeing that all was well.
					y = homeRow(x, y, fieldWidth, add, end.id());
				}
			}
			case Node.Comment comment -> {
				// The writing itself, then how big a card to put it on, then which nodes
				// it is about. In that order because that is the order somebody does it:
				// you write the thing first and only then wonder whether it fits.
				labels.add(new Label(say("npc_studio.node.note"), x, y - 10));
				add.accept(new FlatButton(x, y, fieldWidth, 18,
					comment.text().isBlank()
						? Component.translatable("npc_studio.node.note_empty")
						: Component.literal(shortenPlain(comment.text())),
					0xFFB08A5A,
					() -> screen.write(say("npc_studio.node.note"),
						com.mopicmp.npcstudio.dialogue.text.Words.of(
							current(Node.Comment.class).text()),
						words -> put(current(Node.Comment.class).reworded(words.plain())))));
				y += ROW;

				// Sliders rather than a cycle of sizes: this is adjusted while looking at
				// the card behind the panel, and a slider is the control you hold and watch.
				add.accept(new FlatSlider(x, y, fieldWidth, 18, say("npc_studio.node.note_wide"),
					Node.Comment.NARROWEST, Node.Comment.WIDEST, 8, 0xFFB08A5A,
					() -> current(Node.Comment.class).wide(),
					picked -> {
						Node.Comment was = current(Node.Comment.class);
						put(was.sized(picked.intValue(), was.rows()));
					}));
				y += ROW;
				add.accept(new FlatSlider(x, y, fieldWidth, 18, say("npc_studio.node.note_rows"),
					Node.Comment.FEWEST_ROWS, Node.Comment.MOST_ROWS, 1, 0xFFB08A5A,
					() -> current(Node.Comment.class).rows(),
					picked -> {
						Node.Comment was = current(Node.Comment.class);
						put(was.sized(was.wide(), picked.intValue()));
					}));
				y += ROW;

				// What it is about. A list rather than one, because one comment about two
				// nodes is the case that made this a node at all — and none is a note
				// about the graph as a whole, which is why nothing here is required.
				labels.add(new Label(say("npc_studio.node.note_about"), x, y - 10));
				int aboutY = y;
				add.accept(new FlatButton(x, y, fieldWidth, 18,
					comment.about().isEmpty()
						? Component.translatable("npc_studio.node.note_about_none")
						: Component.literal(String.join(", ", comment.about())),
					0xFFB08A5A,
					() -> screen.pick(x, aboutY + 18, fieldWidth, aboutChoices())));
				y += ROW;
			}
			default -> labels.add(new Label(say("npc_studio.node.nothing"), x, y));
		}

		// Where the fields ran out, so whoever is showing this panel knows whether
		// it fits and by how much it does not.
		contentBottom = y - top;

		// A beginning, or a way in — whichever this document can actually have. A place, a
		// player and a thing are carried by nobody: they have no beginning to reach, and
		// pressing "make this the start" on one used to leave a document the validator
		// refused to save with no way in the window to undo it. What those want is a way
		// in, which is the same gesture about the thing they do have.
		boolean carried = state.kind() == com.mopicmp.npcstudio.dialogue.Dialogue.Kind.SCENE;
		add.accept(new FlatButton(x, top + height - 26, 90, 18,
			Component.translatable(carried
				? "npc_studio.node.make_start" : "npc_studio.node.make_way_in"),
			0xFFFFCA28, () -> {
				String at = state.nodes().get(index).id();
				if (carried) state.start(at);
				else state.segment(at, at);
			}));
		add.accept(new FlatButton(x + 96, top + height - 26, 74, 18,
			Component.translatable("npc_studio.node.delete"), 0xFFEF5350, screen::deleteSelected));
	}

	/**
	 * A route: where it goes, and how she goes along it.
	 *
	 * <h2>Why two ways out to the world and not one</h2>
	 *
	 * Because they are two different jobs and both were asked for. Sent into the
	 * game you walk the path yourself, which is the only way to find out whether it
	 * can be walked — that there is a door, that the stair is climbable, that the
	 * gap at the end is not two blocks. Left in the workspace you fly its camera
	 * over the ground, which is how a long path is laid out without going round it
	 * first. Neither is the other one done badly.
	 *
	 * <h2>Why the points are listed here when they are placed out there</h2>
	 *
	 * Removing needs both. In the world you take away the one you are looking at,
	 * which is right when you can see it and useless for the fourth of twelve on the
	 * far side of a wall. The list is the other half: it is the only place the order
	 * is visible as an order, and the order is most of what a route is.
	 */
	private int routeFields(Font font, Node.Walk walk, int x, int y, int width,
			Consumer<AbstractWidget> add) {
		com.mopicmp.npcstudio.dialogue.Route route = walk.route();

		labels.add(new Label(say("npc_studio.node.draw_path"), x, y - 10));
		int half = (width - 4) / 2;
		add.accept(new FlatButton(x, y, half, 18,
			Component.translatable("npc_studio.route.in_world"), 0xFF9CCC65,
			() -> com.mopicmp.npcstudio.client.map.Routing.start(state, walk.id(), false)));
		add.accept(new FlatButton(x + half + 4, y, width - half - 4, 18,
			Component.translatable("npc_studio.route.in_scene"), 0xFF4FC3F7,
			() -> com.mopicmp.npcstudio.client.map.Routing.start(state, walk.id(), true)));
		y += ROW;

		// Walks or runs, which after this is a statement about the animation and not
		// about the speed. The two used to be the same dial: the animation was read
		// off how fast the body was actually moving, so choosing "runs" and then
		// slowing her below about half pace put her back to walking with the switch
		// still saying otherwise. See Route.Gait.
		boolean running = route.gait() == com.mopicmp.npcstudio.dialogue.Route.Gait.RUN;
		labels.add(new Label(say("npc_studio.node.goes_at"), x, y - 10));
		add.accept(new FlatButton(x, y, width, 18,
			Component.translatable(running ? "npc_studio.route.runs" : "npc_studio.route.walks"),
			0xFF9CCC65, () -> {
				Node.Walk now = current(Node.Walk.class);
				put(new Node.Walk(now.id(), now.route().withGait(running
					? com.mopicmp.npcstudio.dialogue.Route.Gait.WALK
					: com.mopicmp.npcstudio.dialogue.Route.Gait.RUN), now.next()));
				screen.refreshPanel();
			}));
		y += ROW;

		// And how fast, separately, because that is now a separate thing. The floor
		// is the slowest a body reads as moving rather than as sliding.
		add.accept(new FlatSlider(x, y, width, 18, say("npc_studio.node.speed"),
			com.mopicmp.npcstudio.dialogue.Route.CRAWL,
			com.mopicmp.npcstudio.dialogue.Route.DASH, 0.05, 0xFF9CCC65,
			() -> current(Node.Walk.class).route().pace(),
			value -> {
				Node.Walk now = current(Node.Walk.class);
				put(new Node.Walk(now.id(), now.route().withPace(value.floatValue()), now.next()));
			}));
		y += ROW;

		// What a fresh point is written as. Steps by default, because a route written
		// in coordinates dies the day the building it goes round is pasted somewhere
		// else — which is how large maps are actually built, not a rare case.
		boolean stepping = route.from()
			== com.mopicmp.npcstudio.dialogue.Route.From.CHARACTER;
		labels.add(new Label(say("npc_studio.node.points_as"), x, y - 10));
		add.accept(new FlatButton(x, y, width, 18,
			Component.translatable(stepping
				? "npc_studio.route.from_character" : "npc_studio.route.from_world"),
			0xFF4FC3F7, () -> switchForm(stepping)));
		y += ROW;

		// Where the steps are measured from, in the panel as well as in the world. A
		// route of steps hangs off this, and an anchor in the wrong place moves the
		// whole round without changing a single number in the list below.
		if (stepping) {
			y = homeRow(x, y, width, add, walk.id());
		}

		if (route.isEmpty()) {
			// Said rather than left blank. A fresh route node is empty by design — there
			// is no point in the world it would be less wrong to guess at than none —
			// and an empty panel would read as a node that is broken rather than as one
			// that has not been drawn yet.
			labels.add(new Label(say("npc_studio.node.no_points"), x, y));
			return y + 14;
		}

		labels.add(new Label(say("npc_studio.node.points_count", route.size()), x, y));
		y += 12;
		for (int i = 0; i < route.size(); i++) {
			int slot = i;
			labels.add(new Label((i + 1) + " · " + said(route.at(i)), x, y + 3));
			add.accept(new FlatButton(x + width - 16, y, 16, 14,
				Component.literal("x"), 0xFFEF5350, () -> {
					Node.Walk now = current(Node.Walk.class);
					put(new Node.Walk(now.id(), now.route().without(slot), now.next()));
					screen.refreshPanel();
				}));
			y += ROW_SMALL;
		}
		return y;
	}

	/** A row of the point list: a number, a place and a cross, and nothing else. */
	private static final int ROW_SMALL = 15;

	/**
	 * Where home is, and the two ways to move it — as one row rather than as two.
	 *
	 * <h2>The gap, which was not one</h2>
	 *
	 * The reading was drawn at {@code y} and the buttons' caption ten pixels above
	 * their own top, so fourteen between them put the caption four pixels <em>into</em>
	 * the line above it: two sentences in the same row of pixels, which is what was
	 * reported as the text running together. Every caption in this panel is drawn ten
	 * above its widget, so a line of words needs a clear twenty-two before the next
	 * row starts — nine for itself, three of air, ten for the caption to come.
	 *
	 * <h2>And why the two of them are one method</h2>
	 *
	 * Because they were written out twice, at the ending and at the route, and only
	 * one of the two copies would have been fixed.
	 */
	private int homeRow(int x, int y, int width, Consumer<AbstractWidget> add, String node) {
		readings.add(new Reading(this::homeSaid, x, y));
		return homeButtons(x, y + SAID, width, add, node);
	}

	/** A line of words, and the room the row after it needs to clear it. */
	private static final int SAID = 22;

	/**
	 * Where an {@code appear} puts her, in words.
	 *
	 * Said as it is written rather than turned into coordinates: a place written as
	 * steps has no coordinates until a character is standing somewhere, and showing a
	 * pair of numbers worked out against whoever happens to be nearest would be a row
	 * that changes when nobody has edited anything.
	 */
	private static String spotSaid(com.mopicmp.npcstudio.dialogue.Route.Point where) {
		return switch (where) {
			case com.mopicmp.npcstudio.dialogue.Route.Point.At(int x, int y, int z, int _) ->
				say("npc_studio.node.appear_at_world", x, y, z);
			case com.mopicmp.npcstudio.dialogue.Route.Point.Go(int f, int up, int left, int _) ->
				f == 0 && up == 0 && left == 0
					? say("npc_studio.node.appear_nowhere")
					: say("npc_studio.node.appear_steps", f, up, left);
		};
	}

	/**
	 * The two ways out to move where a character belongs.
	 *
	 * The same pair the route itself offers, and the same two jobs: into the world to
	 * stand where she should stand, or into the scene to put it from above. A home is
	 * one place rather than a shape, so one click finishes it.
	 *
	 * Offered from both the ending and the route because both are about it — the
	 * ending sends her back to it and every step of the route is measured from it —
	 * and an author who finds it wrong is looking at one or the other at that moment.
	 */
	private int homeButtons(int x, int y, int width, Consumer<AbstractWidget> add,
			String node) {
		labels.add(new Label(say("npc_studio.node.move_home"), x, y - 10));
		int half = (width - 4) / 2;
		add.accept(new FlatButton(x, y, half, 18,
			Component.translatable("npc_studio.route.in_world"), 0xFFFFB74D,
			() -> com.mopicmp.npcstudio.client.map.Routing.startHome(state, node, false)));
		add.accept(new FlatButton(x + half + 4, y, width - half - 4, 18,
			Component.translatable("npc_studio.route.in_scene"), 0xFFFFB74D,
			() -> com.mopicmp.npcstudio.client.map.Routing.startHome(state, node, true)));
		return y + ROW;
	}

	/**
	 * Where the character this graph belongs to calls home, in numbers.
	 *
	 * Read off the character rather than described, because the description was the
	 * bug: it said home is where she was placed, which is what it means and not
	 * always what it holds.
	 *
	 * Asked about this document rather than about whatever is being routed. It used
	 * to go through the placing mode, which only knows a document while somebody is
	 * out in the world putting points down — so with the editor simply open the
	 * answer was always "nobody here to ask", whoever was standing in front of you.
	 * That is one line saying the anchor is missing on a panel whose numbers all
	 * hang off the anchor.
	 */
	private String homeSaid() {
		var npc = com.mopicmp.npcstudio.client.map.Routing.anchorFor(state);
		// One sentence rather than two. There used to be a second line further down
		// saying steps cannot be measured, which is the same fact told twice — and
		// the two could disagree, because they asked at different moments.
		if (npc == null) return say("npc_studio.node.home_none");
		var at = npc.home();
		return say("npc_studio.node.home_at",
			(long) Math.floor(at.x), (long) Math.floor(at.y), (long) Math.floor(at.z),
			npc.homeFacing().name().toLowerCase(java.util.Locale.ROOT));
	}

	/**
	 * One point in words, in whichever form it was written.
	 *
	 * Steps read as steps and places read as places, in the same list, because the
	 * two mix and pretending otherwise would mean showing one of them as the other.
	 * Nought is left out — "4 forward" is the whole of what a step along one axis
	 * is, and "4 forward, 0 up, 0 left" is the same fact buried.
	 */
	private static String said(com.mopicmp.npcstudio.dialogue.Route.Point point) {
		return switch (point) {
			case com.mopicmp.npcstudio.dialogue.Route.Point.At(int x, int y, int z, int _) ->
				x + " " + y + " " + z;
			case com.mopicmp.npcstudio.dialogue.Route.Point.Go(
					int forward, int up, int left, int _) -> {
				StringBuilder said = new StringBuilder();
				if (forward != 0) said.append(word(Math.abs(forward), forward > 0
					? "npc_studio.route.forward" : "npc_studio.route.back"));
				if (left != 0) said.append(word(Math.abs(left), left > 0
					? "npc_studio.route.left" : "npc_studio.route.right"));
				if (up != 0) said.append(word(Math.abs(up), up > 0
					? "npc_studio.route.up" : "npc_studio.route.down"));
				// A step of nothing at all, which is the anchor's own square. Worth
				// saying rather than showing as an empty row: it is a real point and a
				// perfectly reasonable one to start a round on.
				yield said.isEmpty()
					? Component.translatable("npc_studio.route.here").getString()
					: said.toString().trim();
			}
		};
	}

	/**
	 * A word of the interface, in whatever language the player is running.
	 *
	 * <h2>Why the panel says this and not the words themselves</h2>
	 *
	 * Every caption here was an English string in the source. That is fine while the
	 * only person reading them wrote them, and it is exactly wrong for the thing this
	 * panel is: the door into block programming for somebody building a map, who was
	 * never promised English. The whole workspace around it is already translated,
	 * so the graph editor was the one room in the building with the signs in another
	 * language.
	 *
	 * A string rather than a {@code Component}, because the two things that draw
	 * these — the label list and the field's own caption — both hold plain text, and
	 * changing that is a wider change than the one being made here.
	 */
	private static String say(String key) {
		return Component.translatable(key).getString();
	}

	private static String say(String key, Object... with) {
		return Component.translatable(key, with).getString();
	}

	private static String word(int many, String key) {
		return Component.translatable(key, many).getString() + " ";
	}

	/**
	 * Which box, cycled through the ones this document has drawn.
	 *
	 * The same shape as the mark picker and for the same reason: a short list of
	 * names that already exist beats a field somebody can spell wrong. An empty
	 * document says so rather than showing an empty button — a button reading
	 * nothing looks broken, and "no boxes drawn yet" is a sentence somebody can act
	 * on.
	 */
	private int areaButton(int x, int y, int width, String label, String area,
			Consumer<AbstractWidget> add,
			java.util.function.Function<String, Effect> made) {
		labels.add(new Label(label, x, y - 10));
		int at = y;
		add.accept(new FlatButton(x, y, width, 18,
			Component.literal(areaSaid(area) + "  ▾"), 0xFFFF8A65,
			() -> screen.pick(x, at + 18, width, areaChoices(area, picked -> {
				Node.Act now = current(Node.Act.class);
				put(new Node.Act(now.id(), made.apply(picked), now.next()));
			}))));
		return y + ROW;
	}

	/** Which box is named here, as a line of words rather than as an empty button. */
	private String areaSaid(String area) {
		if (state.areas().isEmpty()) return say("npc_studio.node.area_none");
		if (area == null || area.isEmpty()) return say("npc_studio.node.area_unchosen");
		return state.areas().containsKey(area) ? area
			: say("npc_studio.node.area_missing", area);
	}

	/**
	 * The boxes to choose from, and a way to draw one from here.
	 *
	 * <h2>Why the drawing is offered in this list</h2>
	 *
	 * Because this is where somebody finds out they need a box. They are writing the
	 * wait that springs the trap, the field says there is not one box drawn, and the
	 * place that draws them is a button in a row along the bottom of a different part
	 * of the window. Asked outright: "how do I draw this box for until?"
	 *
	 * A list whose only entry is "there are none" is a dead end. One more line and it
	 * is a door, and the node it was opened from is named at once — so the box comes
	 * back already chosen rather than needing to be found again in a second list.
	 */
	private List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> areaChoices(
			String area, Consumer<String> onPick) {
		List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> rows = new ArrayList<>();
		for (String name : state.areas().keySet()) {
			rows.add(new com.mopicmp.npcstudio.client.workspace.Menu.Entry(
				null, Component.literal(name), name.equals(area), () -> {
					onPick.accept(name);
					screen.refreshPanel();
				}));
		}
		// Out to the world, with a fresh name, and named in this node the moment the
		// second corner lands — see Routing.startBox, which writes nothing until then.
		String fresh = state.freshArea();
		rows.add(new com.mopicmp.npcstudio.client.workspace.Menu.Entry(
			com.mopicmp.npcstudio.client.workspace.Icon.ADD,
			Component.translatable("npc_studio.node.area_draw"), false, () -> {
				onPick.accept(fresh);
				com.mopicmp.npcstudio.client.map.Routing.startBox(state, fresh, false);
			}));
		rows.add(new com.mopicmp.npcstudio.client.workspace.Menu.Entry(
			com.mopicmp.npcstudio.client.workspace.Icon.ADD,
			Component.translatable("npc_studio.node.area_draw_scene"), false, () -> {
				onPick.accept(fresh);
				com.mopicmp.npcstudio.client.map.Routing.startBox(state, fresh, true);
			}));
		return rows;
	}

	/**
	 * Rewrites every point in the other form, over the same ground.
	 *
	 * Over the same ground is the whole requirement. A switch that changed how a
	 * route is written and moved it at the same time is a switch nobody would dare
	 * press twice — so it is refused outright when there is nobody to measure
	 * against, rather than done against a guess.
	 */
	private void switchForm(boolean stepping) {
		var to = stepping ? com.mopicmp.npcstudio.dialogue.Route.From.WORLD
			: com.mopicmp.npcstudio.dialogue.Route.From.CHARACTER;
		Node.Walk now = current(Node.Walk.class);
		// About this document, not about whatever is being drawn on. Asked the other
		// way it was null whenever nobody was out placing points — which is always,
		// here — so this button refused every route that had anything in it.
		var npc = com.mopicmp.npcstudio.client.map.Routing.anchorFor(state);

		if (npc == null) {
			// Nothing to measure from. An empty route can still change form — there is
			// nothing to move — and one with points in it cannot, and says why.
			if (!now.route().isEmpty()) {
				com.mopicmp.npcstudio.client.map.Routing.say(
					Component.translatable("npc_studio.route.no_anchor"));
				return;
			}
			put(new Node.Walk(now.id(), now.route().writtenFrom(to), now.next()));
			screen.refreshPanel();
			return;
		}

		var home = npc.home();
		put(new Node.Walk(now.id(), now.route().allWrittenFrom(to,
			net.minecraft.util.Mth.floor(home.x),
			net.minecraft.util.Mth.floor(home.y),
			net.minecraft.util.Mth.floor(home.z),
			npc.homeFacing()), now.next()));
		screen.refreshPanel();
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
			// A property somebody gets. The handle is typed because it is this
			// document's own word for the boon; the property is chosen because the game
			// keeps exactly forty of them and a misspelt one does nothing for ever.
			case Effect.Trait trait -> {
				add.accept(field(font, x, y, width, say("npc_studio.node.trait_name"),
					trait.name(), value -> put(new Node.Act(current(Node.Act.class).id(),
						new Effect.Trait(value, trait.attribute(), trait.how(), trait.amount(),
							trait.on(), trait.whose()),
						current(Node.Act.class).next())), add));
				y += ROW;

				labels.add(new Label(say("npc_studio.node.trait_what"), x, y - 10));
				int atWhat = y;
				add.accept(new FlatButton(x, y, width, 18,
					Component.literal(shortAttribute(trait.attribute()) + "  ▾"), 0xFFBA68C8,
					() -> screen.pick(x, atWhat + 18, width, attributeChoices(trait))));
				y += ROW;

				// Off keeps the number. Turning a boon off is about the handle, not about
				// the amount, and a node that had to be emptied to be switched off is a
				// node that cannot be switched back on.
				add.accept(new FlatButton(x, y, width, 18,
					Component.translatable(trait.on()
						? "npc_studio.node.trait_on" : "npc_studio.node.trait_off"),
					trait.on() ? 0xFF66BB6A : 0xFF8A99A6,
					() -> {
						put(new Node.Act(current(Node.Act.class).id(),
							trait.turned(!trait.on()), current(Node.Act.class).next()));
						screen.refreshPanel();
					}));
				y += ROW;

				if (trait.on()) {
					int atHow = y;
					add.accept(new FlatButton(x, y, 88, 18,
						Component.translatable(howKey(trait.how())), 0xFF4FC3F7,
						() -> screen.pick(x, atHow + 18, 88, howChoices(trait))));
					add.accept(field(font, x + 92, y, width - 92, say("npc_studio.node.trait_amount"),
						String.valueOf(trait.amount()),
						value -> put(new Node.Act(current(Node.Act.class).id(),
							new Effect.Trait(trait.name(), trait.attribute(), trait.how(),
								decimal(value, trait.amount()), true, trait.whose()),
							current(Node.Act.class).next())), add));
					y += ROW;
				}

				labels.add(new Label(say("npc_studio.node.trait_whose"), x, y - 10));
				add.accept(new FlatButton(x, y, width, 18,
					Component.translatable(trait.whose() == Effect.Trait.Whose.PLAYER
						? "npc_studio.node.trait_player" : "npc_studio.node.trait_character"),
					0xFF66BB6A, () -> {
						put(new Node.Act(current(Node.Act.class).id(),
							new Effect.Trait(trait.name(), trait.attribute(), trait.how(),
								trait.amount(), trait.on(),
								trait.whose() == Effect.Trait.Whose.PLAYER
									? Effect.Trait.Whose.CHARACTER : Effect.Trait.Whose.PLAYER),
							current(Node.Act.class).next()));
						screen.refreshPanel();
					}));
				y += ROW;
			}
			// Where and what. The same pair the block test asks about, and deliberately
			// the same two controls: reading the world and changing it are two halves of
			// one thought, and a rule usually holds both.
			case Effect.PutBlock(String mark, String block) -> {
				labels.add(new Label(say("npc_studio.node.put_where"), x, y - 10));
				int atMark = y;
				add.accept(new FlatButton(x, y, width, 18,
					Component.literal(markSaid(mark) + "  ▾"), 0xFF9575CD,
					() -> screen.pick(x, atMark + 18, width, putMarkChoices(mark, block))));
				y += ROW;

				add.accept(field(font, x, y, width, say("npc_studio.node.which_block"), block,
					value -> put(new Node.Act(current(Node.Act.class).id(),
						new Effect.PutBlock(mark, value), current(Node.Act.class).next())), add));
				y += ROW;
			}
			// A thing standing in the air. The name first, because it is the handle
			// everything else in the graph reaches it by; then what it is, which decides
			// what the rest of the rows are worth showing.
			case Effect.Show(String name, com.mopicmp.npcstudio.dialogue.Shown what,
					com.mopicmp.npcstudio.dialogue.Route.Point where) -> {
				add.accept(field(font, x, y, width, say("npc_studio.node.shown_name"), name,
					value -> put(new Node.Act(current(Node.Act.class).id(),
						new Effect.Show(value, what, where), current(Node.Act.class).next())),
					add));
				y += ROW;

				labels.add(new Label(say("npc_studio.node.shown_kind"), x, y - 10));
				int atKind = y;
				add.accept(new FlatButton(x, y, width, 18,
					Component.translatable("npc_studio.shown.kind_" + what.kindName()).copy()
						.append("  ▾"), 0xFFFF8A65,
					() -> screen.pick(x, atKind + 18, width, shownKindChoices(what))));
				y += ROW;

				// What it says, or which block, or which item. One row, because it is one
				// question — the label above it is what changes.
				//
				// Words get the pencil beside them, which opens the window every spoken
				// line is written in. There is no colour row here and there must not be
				// one: this mod has a way of dressing text already, it can colour one word
				// of a label rather than all of it, and a second vocabulary for the same
				// idea is one to keep in step and one to get wrong.
				boolean words = what.isText();
				add.accept(field(font, x, y, words ? width - 22 : width,
					say(switch (what.kind()) {
						case TEXT -> "npc_studio.node.shown_says";
						case BLOCK -> "npc_studio.node.which_block";
						case ITEM -> "npc_studio.node.shown_item";
					}),
					what.plain().replace("\n", "\\n"),
					value -> put(new Node.Act(current(Node.Act.class).id(),
						new Effect.Show(name, what.saying(value.replace("\\n", "\n")), where),
						current(Node.Act.class).next())), add));
				if (words) {
					add.accept(new FlatButton(x + width - 18, y, 18, 18,
						Component.literal("✎"), 0xFFFF8A65,
						() -> screen.write(say("npc_studio.node.shown_says"),
							((Effect.Show) current(Node.Act.class).effect()).what().what(),
							written -> {
								Node.Act now = current(Node.Act.class);
								Effect.Show was = (Effect.Show) now.effect();
								put(new Node.Act(now.id(), new Effect.Show(was.name(),
									was.what().saying(written), was.where()), now.next()));
							})));
				}
				y += ROW;

				// Where it hangs, in a row of its own and pointed at in the world with the
				// same pair of buttons every other place in this editor is chosen with.
				y = saidRow(x, y, width, say("npc_studio.node.shown_where"), spotSaid(where));
				int half = (width - 4) / 2;
				add.accept(new FlatButton(x, y, half, 18,
					Component.translatable("npc_studio.route.in_world"), 0xFFFFB74D,
					() -> com.mopicmp.npcstudio.client.map.Routing.startSpot(
						state, current(Node.Act.class).id(), false)));
				add.accept(new FlatButton(x + half + 4, y, width - half - 4, 18,
					Component.translatable("npc_studio.route.in_scene"), 0xFFFFB74D,
					() -> com.mopicmp.npcstudio.client.map.Routing.startSpot(
						state, current(Node.Act.class).id(), true)));
				y += ROW;

				add.accept(field(font, x, y, width, say("npc_studio.node.shown_size"),
					String.valueOf(what.size()),
					value -> put(new Node.Act(current(Node.Act.class).id(),
						new Effect.Show(name,
							what.looking(decimal(value, what.size()), what.plaque()), where),
						current(Node.Act.class).next())), add));
				y += ROW;

				// The one setting that is only about words. Left out entirely for a block
				// or an item rather than greyed: a plaque behind a hologram of a diamond is
				// a row that means nothing and will be tried once by everybody.
				if (words) {
					add.accept(new FlatButton(x, y, width, 18,
						Component.translatable(what.plaque()
							? "npc_studio.shown.plaque_on" : "npc_studio.shown.plaque_off"),
						0xFFFF8A65, () -> {
							Node.Act now = current(Node.Act.class);
							Effect.Show was = (Effect.Show) now.effect();
							put(new Node.Act(now.id(), new Effect.Show(was.name(),
								was.what().looking(was.what().size(), !was.what().plaque()),
								was.where()), now.next()));
							screen.refreshPanel();
						}));
					y += ROW;
				}
			}
			// Taking one down. One field, and it is the name — offered from what this
			// document shows rather than typed, because a name typed wrong takes nothing
			// away and says nothing about it.
			case Effect.Unshow(String name) -> {
				labels.add(new Label(say("npc_studio.node.unshow_name"), x, y - 10));
				int atName = y;
				add.accept(new FlatButton(x, y, width, 18,
					Component.literal((name.isEmpty()
						? say("npc_studio.node.trait_unnamed") : name) + "  ▾"), 0xFFFF8A65,
					() -> screen.pick(x, atName + 18, width, shownNameChoices(name,
						picked -> new Effect.Unshow(picked)))));
				y += ROW;
			}
			case Effect.Portrait showing -> {
				// What is being shown: one whole picture off the shelf, or a figure
				// assembled from a layout sheet. A name from the world rather than a file
				// path either way — the world travels with the map, and a path would be a
				// picture on the author's machine and nowhere else.
				labels.add(new Label(say("npc_studio.node.portrait"), x, y - 10));
				int atY = y;
				add.accept(new FlatButton(x, y, width, 18, portraitName(showing), 0xFFBA68C8,
					() -> screen.pick(x, atY + 18, width, portraitChoices())));
				y += ROW;

				// One row per slot of the figure, each a list of what may fill it. This is
				// the "Lona, cloak, sad" the whole layout business was for: three names and
				// not one coordinate anywhere in the graph.
				if (showing.assembled()) y = figureFields(showing, x, y, width, add);

				// The side and the flip are about the showing rather than the picture:
				// one drawing faces into the scene from either edge. Hidden while there
				// is no picture, because they would be settings for nothing.
				if (showing.showing()) {
					Effect.Portrait.Side side = showing.side();
					boolean mirrored = showing.mirrored();
					labels.add(new Label(say("npc_studio.node.portrait_side"), x, y - 10));
					add.accept(new FlatButton(x, y, width / 2 - 2, 18,
						Component.translatable("npc_studio.node.portrait_"
							+ side.name().toLowerCase()), 0xFFBA68C8, () -> {
							Node.Act was = current(Node.Act.class);
							var had = (Effect.Portrait) was.effect();
							// Rebuilt through every field rather than the short form: the
							// short one means "one picture, no figure", so using it here
							// would quietly throw the figure away on a click that was only
							// meant to change which edge she stands against.
							put(new Node.Act(was.id(), new Effect.Portrait(had.picture(),
								had.side() == Effect.Portrait.Side.LEFT
									? Effect.Portrait.Side.RIGHT : Effect.Portrait.Side.LEFT,
								had.mirrored(), had.figure(), had.choice()), was.next()));
							screen.refreshPanel();
						}));
					add.accept(new FlatButton(x + width / 2 + 2, y, width / 2 - 2, 18,
						Component.translatable(mirrored
							? "npc_studio.node.portrait_flipped" : "npc_studio.node.portrait_asis"),
						0xFFBA68C8, () -> {
							Node.Act was = current(Node.Act.class);
							var had = (Effect.Portrait) was.effect();
							put(new Node.Act(was.id(), new Effect.Portrait(had.picture(),
								had.side(), !had.mirrored(), had.figure(), had.choice()),
								was.next()));
							screen.refreshPanel();
						}));
					y += ROW;
				}
			}
			case Effect.PlayAnimation(String animation, int ticks) -> {
				labels.add(new Label(say("npc_studio.node.animation"), x, y - 10));
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

				add.accept(field(font, x, y, width, say("npc_studio.node.how_long_ticks"), String.valueOf(ticks), value -> {
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
				add.accept(field(font, x, y, width, say("npc_studio.node.item"), item, value -> {
					Node.Act now = current(Node.Act.class);
					put(new Node.Act(now.id(), new Effect.GiveItem(value, count), now.next()));
				}, add));
				y += ROW;
				add.accept(field(font, x, y, width, say("npc_studio.node.how_many"), String.valueOf(count), value -> {
					Node.Act now = current(Node.Act.class);
					put(new Node.Act(now.id(), new Effect.GiveItem(item, number(value, count)), now.next()));
				}, add));
				y += ROW;
			}
			case Effect.TakeItem(String item, int count) -> {
				add.accept(field(font, x, y, width, say("npc_studio.node.item"), item, value -> {
					Node.Act now = current(Node.Act.class);
					put(new Node.Act(now.id(), new Effect.TakeItem(value, count), now.next()));
				}, add));
				y += ROW;
				add.accept(field(font, x, y, width, say("npc_studio.node.how_many"), String.valueOf(count), value -> {
					Node.Act now = current(Node.Act.class);
					put(new Node.Act(now.id(), new Effect.TakeItem(item, number(value, count)), now.next()));
				}, add));
				y += ROW;
			}
			case Effect.RunCommand(String command) -> {
				add.accept(field(font, x, y, width, say("npc_studio.node.command"), command, value -> {
					Node.Act now = current(Node.Act.class);
					put(new Node.Act(now.id(), new Effect.RunCommand(value), now.next()));
				}, add));
				y += ROW;
			}
			case Effect.PlaySound(String sound, float volume, float pitch) -> {
				add.accept(field(font, x, y, width, say("npc_studio.node.sound"), sound, value -> {
					Node.Act now = current(Node.Act.class);
					put(new Node.Act(now.id(), new Effect.PlaySound(value, volume, pitch), now.next()));
				}, add));
				y += ROW;
			}
			case Effect.PlaceStructure(String structure, String anchor, int ticks) -> {
				add.accept(field(font, x, y, width, say("npc_studio.node.structure"), structure, value -> {
					Node.Act now = current(Node.Act.class);
					put(new Node.Act(now.id(), new Effect.PlaceStructure(value, anchor, ticks), now.next()));
				}, add));
				y += ROW;
				add.accept(field(font, x, y, width, say("npc_studio.node.over_ticks"), String.valueOf(ticks), value -> {
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
				labels.add(new Label(say("npc_studio.node.expression"), x, y - 10));
				var mood = com.mopicmp.npcstudio.entity.Expression.named(expression);
				add.accept(new FlatButton(x, y, width, 18,
					Component.literal(moodName(mood)), 0xFFFF8A65,
					() -> {
						Node.Act now = current(Node.Act.class);
						put(new Node.Act(now.id(),
							new Effect.Express(nextMood(mood).name(), ticks), now.next()));
					}));
				y += ROW;
				add.accept(field(font, x, y, width, say("npc_studio.node.face_ticks"),
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
				y = markButton(x, y, width, say("npc_studio.node.walk_to"), mark, add,
					picked -> new Effect.WalkTo(picked, pace));
				labels.add(new Label(say("npc_studio.node.how_fast"), x, y - 10));
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
			case Effect.Wall(String area, boolean up) -> {
				// Which box, from the ones this document has drawn. A picker rather than
				// a typed name for the same reason a mark is picked: a name typed wrong
				// is a wall that quietly never appears, and a trap that does not spring
				// is the hardest kind of broken to notice.
				y = areaButton(x, y, width, say("npc_studio.node.which_box"), area, add,
					picked -> new Effect.Wall(picked, up));
				labels.add(new Label(say("npc_studio.node.and_then_it"), x, y - 10));
				add.accept(new FlatButton(x, y, width, 18,
					Component.translatable(up
						? "npc_studio.node.wall_up" : "npc_studio.node.wall_down"),
					0xFFFF8A65, () -> {
						Node.Act now = current(Node.Act.class);
						Effect.Wall was = (Effect.Wall) now.effect();
						put(new Node.Act(now.id(), new Effect.Wall(was.area(), !was.up()), now.next()));
						screen.refreshPanel();
					}));
				y += ROW;
			}
			case Effect.LookAt(String mark) ->
				y = markButton(x, y, width, say("npc_studio.node.look_at"), mark, add, Effect.LookAt::new);
			case Effect.Send(com.mopicmp.npcstudio.dialogue.Route.Point where) -> {
				// The same row as the one that moves her, because it is the same question
				// asked about the other person — and the label is the whole difference.
				y = saidRow(x, y, width, say("npc_studio.node.send_at"), spotSaid(where));
				int half = (width - 4) / 2;
				add.accept(new FlatButton(x, y, half, 18,
					Component.translatable("npc_studio.route.in_world"), 0xFFFFB74D,
					() -> com.mopicmp.npcstudio.client.map.Routing.startSpot(
						state, current(Node.Act.class).id(), false)));
				add.accept(new FlatButton(x + half + 4, y, width - half - 4, 18,
					Component.translatable("npc_studio.route.in_scene"), 0xFFFFB74D,
					() -> com.mopicmp.npcstudio.client.map.Routing.startSpot(
						state, current(Node.Act.class).id(), true)));
				y += ROW;
			}
			case Effect.Appear(com.mopicmp.npcstudio.dialogue.Route.Point where) -> {
				// Where she ends up, in words, so the row says something before anybody
				// has been out to point at the ground. A place written as steps has no
				// coordinates to show, which is the whole reason it survives a scene
				// being pasted somewhere else — so it says the steps.
				y = saidRow(x, y, width, say("npc_studio.node.appear_at"), spotSaid(where));
				int half = (width - 4) / 2;
				// The same pair a route and a home already offer, for the same two jobs:
				// walk the ground yourself to stand where she should stand, or fly the
				// scene camera to put it from above.
				add.accept(new FlatButton(x, y, half, 18,
					Component.translatable("npc_studio.route.in_world"), 0xFFFFB74D,
					() -> com.mopicmp.npcstudio.client.map.Routing.startSpot(
						state, current(Node.Act.class).id(), false)));
				add.accept(new FlatButton(x + half + 4, y, width - half - 4, 18,
					Component.translatable("npc_studio.route.in_scene"), 0xFFFFB74D,
					() -> com.mopicmp.npcstudio.client.map.Routing.startSpot(
						state, current(Node.Act.class).id(), true)));
				y += ROW;
			}
			case Effect.Fire(String mark) ->
				y = markButton(x, y, width, say("npc_studio.node.shoot_at"), mark, add, Effect.Fire::new);
			case Effect.Strike(String mark) ->
				y = markButton(x, y, width, say("npc_studio.node.swing_at"), mark, add, Effect.Strike::new);
			case Effect.Halt _ -> {
				// Nothing to fill in. Said out loud rather than left blank, because an
				// empty form reads as one that failed to load.
				labels.add(new Label(say("npc_studio.node.halt_says"), x, y - 10));
			}
			case Effect.Hold(boolean up) -> {
				// A switch rather than two verbs, because the two must name the same
				// thing and the difference between them is the one thing that differs —
				// the same shape the wall uses, for the same reason.
				labels.add(new Label(say("npc_studio.node.and_player"), x, y - 10));
				add.accept(new FlatButton(x, y, width, 18,
					Component.translatable(up
						? "npc_studio.node.hold_up" : "npc_studio.node.hold_down"),
					0xFF4FC3F7, () -> {
						Node.Act now = current(Node.Act.class);
						put(new Node.Act(now.id(), new Effect.Hold(!up), now.next()));
						screen.refreshPanel();
					}));
				y += ROW;
			}
			case Effect.Guard(boolean up) -> {
				labels.add(new Label(say("npc_studio.node.carries_as"), x, y - 10));
				add.accept(new FlatButton(x, y, width, 18,
					Component.translatable(up ? "npc_studio.node.guard_up" : "npc_studio.node.guard_down"),
					0xFF9575CD, () -> {
						Node.Act now = current(Node.Act.class);
						put(new Node.Act(now.id(), new Effect.Guard(!up), now.next()));
						screen.refreshPanel();
					}));
				y += ROW;
			}
			// A route has no fields here because it has no fields anywhere on a panel:
			// its content is places in the world, and they are placed by pointing at
			// them. A node holding one arrived by being edited outside the editor, and
			// the honest thing to show is where to go and change it.
			case Effect.Follow _, Effect.Arrived _, Effect.GoHome _ -> {
				labels.add(new Label(say("npc_studio.node.set_elsewhere"), x, y - 10));
				y += 12;
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
		return conditionFields(font, condition, x, y, width, add, onChange, -1);
	}

	/**
	 * The same, framed — and, when the condition belongs to an arm of a branch, with
	 * somewhere to take hold of each test.
	 *
	 * @param arm which arm of the branch this is, or -1 when the condition belongs to
	 *            a node that has only one and there is nothing to reorder
	 */
	private int conditionFields(Font font, Condition condition, int x, int y, int width,
			Consumer<AbstractWidget> add, Consumer<Condition> onChange, int arm) {
		ConditionRows rows = ConditionRows.read(condition);
		if (rows == null) {
			labels.add(new Label(say("npc_studio.node.too_deep"), x, y));
			labels.add(new Label(say("npc_studio.node.too_deep_2"), x, y + 10));
			return y + CROW + 4;
		}

		// The join, said in words. Only worth a button once there is something for
		// it to join: with one test, "all of" and "any of" mean the same thing, and a
		// control that changes nothing is a control that teaches nothing.
		if (rows.rows().size() > 1) {
			add.accept(new FlatButton(x, y, width, 16,
				Component.translatable(rows.all() ? "npc_studio.node.all_of" : "npc_studio.node.any_of"), 0xFF4FC3F7,
				() -> change(condition, onChange, was -> was.joinedBy(!was.all()))));
			y += CROW;
		}

		for (int i = 0; i < rows.rows().size(); i++) {
			int slot = i;
			ConditionRows.Row row = rows.rows().get(i);

			// The frame goes in ahead of anything the row draws inside it, so that it
			// paints behind them. Its height is not known yet \u2014 it is put in at this
			// mark once the row has finished and said how tall it turned out.
			int mark = frames.size();
			int rowTop = y;
			// A gutter down the left for the grip, and the fields inside that.
			int cx = x + 12;
			int cw = width - 18;

			// Turning a test round, what kind of test it is, and getting rid of it.
			add.accept(new FlatButton(cx, y, 18, 16, Component.literal(row.not() ? "\u00ac" : " "),
				row.not() ? 0xFFEF5350 : 0xFF546E7A,
				() -> change(condition, onChange, was -> was.withRow(slot,
					new ConditionRows.Row(!was.rows().get(slot).not(),
						was.rows().get(slot).leaf())))));
			add.accept(new FlatButton(cx + 20, y, cw - 40, 16,
				Component.literal(leafName(row.leaf())), 0xFFBA68C8,
				() -> change(condition, onChange, was -> was.withRow(slot,
					new ConditionRows.Row(was.rows().get(slot).not(),
						nextLeaf(was.rows().get(slot).leaf()))))));
			add.accept(new FlatButton(cx + cw - 18, y, 18, 16, Component.literal("x"),
				0xFFEF5350, () -> change(condition, onChange, was -> was.without(slot))));
			y += CROW;

			y = leafFields(font, row.leaf(), cx, y, cw, add,
				made -> change(condition, onChange, was -> was.withRow(slot,
					new ConditionRows.Row(was.rows().get(slot).not(), made))));
			y += 4;

			frames.add(mark, new Frame(x, rowTop - 3, width, y - rowTop, TEST_FILL, TEST_EDGE));
			if (arm >= 0) {
				grips.add(new Grip(x, rowTop - 3, 12, y - rowTop, arm, slot));
				slots.add(new Slot(arm, slot, rowTop - 3, y - 3));
			}
			y += 4;
		}

		// Lined up with the tests rather than with the frame, so it reads as belonging to
		// the list it adds to instead of to the group as a whole.
		add.accept(new FlatButton(x + 12, y, 90, 16,
			Component.translatable("npc_studio.node.add_test"), 0xFF66BB6A,
			() -> change(condition, onChange, was -> was.plus(new ConditionRows.Row(false,
				new Condition.Compare(Sense.KNOWN.get(0), Scope.SENSE, Condition.Op.EQ,
					Value.of(true)))))));
		// Sixteen for the button and ten for whatever caption comes next, which draws
		// itself ten pixels above its own field. At a bare row height that caption
		// landed four pixels inside this button — which is how "ведёт в" came out
		// written across "+ Проверка".
		return y + 16 + 12;
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
			case Condition.Compare compare -> say(compare.scope() == Scope.SENSE
				? "npc_studio.test.sense" : "npc_studio.test.variable");
			case Condition.HasItem _ -> say("npc_studio.test.item");
			case Condition.Visited _ -> say("npc_studio.test.visited");
			case Condition.Inside _ -> say("npc_studio.test.inside");
			case Condition.Block _ -> say("npc_studio.test.block");
			case Condition.Holding _ -> say("npc_studio.test.holding");
			default -> say("npc_studio.test.unknown");
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
			case Condition.Visited _ -> new Condition.Inside("");
			// The place is left empty rather than guessed at, the same as the box above
			// it. The block, though, is written in: an empty description is the one
			// state of this test that reads false for ever with nothing to say why, and
			// a campfire is both the commonest thing anybody watches and a working
			// example of the syntax to edit rather than look up.
			case Condition.Inside _ ->
				new Condition.Block("", "minecraft:campfire[lit=true]");
			// Air, which is an empty hand — the state a rule about doing something by
			// hand wants, and the one nobody would think to type.
			case Condition.Block _ -> new Condition.Holding("minecraft:air");
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
					//
					// Opened as a list rather than stepped through, and for the same reason
					// the verb of an act now is: there are sixteen readings, and a button
					// that shows the next one is a button pressed fifteen times.
					int senseAt = y;
					add.accept(new FlatButton(x, y, width, 16,
						Component.literal(compare.variable() + "  \u25be"), 0xFF9575CD,
						() -> screen.pick(x, senseAt + 16, width, senseChoices(compare, onChange))));
					y += CROW;
				} else {
					// The name and the scope together, because apart they are two halves
					// of one fact and choosing them separately is how they end up
					// disagreeing. The field underneath stays: a list is how you reach a
					// name that exists, and typing is how a name comes to exist at all.
					int atList = y;
					add.accept(new FlatButton(x, y, width, 16,
						Component.literal(compare.variable().isEmpty()
							? say("npc_studio.node.no_variable")
							: compare.variable() + " · " + nameOf(compare.scope()) + "  ▾"),
						0xFF66BB6A,
						() -> screen.pick(x, atList + 16, width, variableChoices(
							compare.variable(), compare.scope(), typeOf(compare.value()), false,
							(picked, where) -> onChange.accept(new Condition.Compare(
								picked, where, compare.op(), compare.value()))))));
					y += CROW;
					add.accept(field(font, x, y + 10, width, say("npc_studio.node.which_variable"), compare.variable(),
						value -> onChange.accept(new Condition.Compare(value, compare.scope(),
							compare.op(), compare.value())), add));
					y += CROW + 10;
				}

				// Ten pixels of caption room, like every other field row here, and not
				// because it is tidier. The value field draws its caption ten pixels above
				// itself; at a bare CROW that caption landed four pixels *inside* the
				// button on the row above, which is what was reported as the text climbing
				// onto the fields.
				add.accept(new FlatButton(x, y + 10, 54, 16, Component.literal(opName(compare.op())),
					0xFF4FC3F7, () -> onChange.accept(new Condition.Compare(compare.variable(),
						compare.scope(), nextOp(compare.op()), compare.value()))));
				add.accept(field(font, x + 58, y + 10, width - 58, say("npc_studio.node.value"),
					show(compare.value()),
					value -> onChange.accept(new Condition.Compare(compare.variable(),
						compare.scope(), compare.op(), parse(value))), add));
				y += CROW + 10;
			}
			case Condition.HasItem(String item, int count) -> {
				add.accept(field(font, x, y + 10, width, say("npc_studio.node.which_item"), item,
					value -> onChange.accept(new Condition.HasItem(value, count)), add));
				y += CROW + 10;
				add.accept(field(font, x, y + 10, width, say("npc_studio.node.how_many"), String.valueOf(count),
					value -> onChange.accept(new Condition.HasItem(item,
						Math.max(1, number(value, count)))), add));
				y += CROW + 10;
			}
			case Condition.Visited(String node) -> {
				add.accept(field(font, x, y + 10, width, say("npc_studio.node.which_node"), node,
					value -> onChange.accept(new Condition.Visited(value)), add));
				y += CROW + 10;
			}
			case Condition.Inside(String area) -> {
				// The same list the wall uses, over the same boxes, because they name the
				// same things — and a trigger naming one box while the wall names another
				// is the mistake this whole arrangement exists to make hard.
				int boxAt = y;
				add.accept(new FlatButton(x, y, width, 16,
					Component.literal(areaSaid(area) + "  ▾"), 0xFF9575CD,
					() -> screen.pick(x, boxAt + 16, width,
						areaChoices(area, picked -> onChange.accept(new Condition.Inside(picked))))));
				y += CROW;
			}
			case Condition.Holding(String item) -> {
				add.accept(field(font, x, y + 10, width, say("npc_studio.node.which_held"),
					item, value -> onChange.accept(new Condition.Holding(value)), add));
				y += CROW + 10;
			}
			case Condition.Block(String mark, String block) -> {
				// The place, from the same list every other place comes from.
				int markAt = y;
				add.accept(new FlatButton(x, y, width, 16,
					Component.literal((mark.isEmpty()
						? say("npc_studio.node.place_unchosen")
						: com.mopicmp.npcstudio.dialogue.Mark.place(mark) == null
							? mark : com.mopicmp.npcstudio.dialogue.Mark.place(mark)) + "  ▾"),
					0xFF9575CD,
					() -> screen.pick(x, markAt + 16, width, placeChoices(mark,
						picked -> onChange.accept(new Condition.Block(picked, block))))));
				y += CROW;

				// And the block, typed, because it is Minecraft's own words for one and
				// there are a few thousand of them. A list would either be that long or
				// would be a shortlist somebody's block is not on.
				add.accept(field(font, x, y + 10, width, say("npc_studio.node.which_block"),
					block, value -> onChange.accept(new Condition.Block(mark, value)), add));
				y += CROW + 10;
			}
			default -> labels.add(new Label(say("npc_studio.node.nothing"), x, y));
		}
		return y;
	}

	/**
	 * Where a block goes: a marked place, or whatever the rule fired about.
	 *
	 * The second is first in the list because it is what a rule nearly always means.
	 * "If what they clicked is alight, put it out" is the plainest thing anybody writes
	 * with this verb, and it names no place at all — the block is the one under the
	 * cursor, and until {@link com.mopicmp.npcstudio.dialogue.Mark#IT} existed the
	 * sentence could not be written.
	 */
	private List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> putMarkChoices(
			String mark, String block) {
		List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> rows = new ArrayList<>();
		rows.add(new com.mopicmp.npcstudio.client.workspace.Menu.Entry(
			null, Component.translatable("npc_studio.node.put_it"),
			com.mopicmp.npcstudio.dialogue.Mark.IT.equals(mark), () -> {
				put(new Node.Act(current(Node.Act.class).id(),
					new Effect.PutBlock(com.mopicmp.npcstudio.dialogue.Mark.IT, block),
					current(Node.Act.class).next()));
				screen.refreshPanel();
			}));
		for (var spot : com.mopicmp.npcstudio.client.map.Spots.all()) {
			String at = com.mopicmp.npcstudio.dialogue.Mark.at(spot.name());
			rows.add(new com.mopicmp.npcstudio.client.workspace.Menu.Entry(
				null, Component.literal(spot.name()), at.equals(mark), () -> {
					put(new Node.Act(current(Node.Act.class).id(),
						new Effect.PutBlock(at, block), current(Node.Act.class).next()));
					screen.refreshPanel();
				}));
		}
		return rows;
	}

	/**
	 * Words, a block or an item — the one choice inside the verb that shows a thing.
	 *
	 * A list rather than a button that cycles, because the three are not on a scale and
	 * because changing it changes which rows below it exist. A cycle through three things
	 * that each rebuild the form is a form that jumps twice on the way to the one wanted.
	 */
	private List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> shownKindChoices(
			com.mopicmp.npcstudio.dialogue.Shown what) {
		List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> rows = new ArrayList<>();
		for (var kind : com.mopicmp.npcstudio.dialogue.Shown.Kind.values()) {
			rows.add(new com.mopicmp.npcstudio.client.workspace.Menu.Entry(
				null, Component.translatable("npc_studio.shown.kind_"
					+ kind.name().toLowerCase(java.util.Locale.ROOT)),
				kind == what.kind(), () -> {
					Node.Act now = current(Node.Act.class);
					Effect.Show was = (Effect.Show) now.effect();
					// What it is keeps whatever was typed in it. A block id left in the
					// field after somebody looks at what words would do is a great deal
					// less annoying than having to type it again.
					put(new Node.Act(now.id(),
						new Effect.Show(was.name(), was.what().being(kind), was.where()),
						now.next()));
					screen.refreshPanel();
				}));
		}
		return rows;
	}

	/**
	 * Every name this document shows something under.
	 *
	 * Offered rather than typed wherever a name is being pointed at rather than made: a
	 * name typed wrong takes nothing down and waits for nothing, and both of those fail by
	 * doing nothing at all. The list is short by construction — it is the holograms of one
	 * document — and a document that shows nothing yet says so.
	 */
	private List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> shownNameChoices(
			String now, java.util.function.Function<String, Effect> make) {
		List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> rows = new ArrayList<>();
		for (String name : shownNames()) {
			rows.add(new com.mopicmp.npcstudio.client.workspace.Menu.Entry(
				null, Component.literal(name), name.equals(now), () -> {
					Node.Act was = current(Node.Act.class);
					put(new Node.Act(was.id(), make.apply(name), was.next()));
					screen.refreshPanel();
				}));
		}
		if (rows.isEmpty()) {
			rows.add(com.mopicmp.npcstudio.client.workspace.Menu.Entry.of(
				Component.translatable("npc_studio.shown.none_shown"), () -> { }));
		}
		return rows;
	}

	/** What one row of a wait may be listening for: any name this document shows. */
	private List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> pressChoices(int which) {
		List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> rows = new ArrayList<>();
		Node.Pressed waiting = current(Node.Pressed.class);
		String now = which < waiting.presses().size() ? waiting.presses().get(which).shown() : "";
		for (String name : shownNames()) {
			rows.add(new com.mopicmp.npcstudio.client.workspace.Menu.Entry(
				null, Component.literal(name), name.equals(now), () -> {
					Node.Pressed was = current(Node.Pressed.class);
					if (which >= was.presses().size()) return;
					List<Node.Press> presses = new java.util.ArrayList<>(was.presses());
					presses.set(which, new Node.Press(name, presses.get(which).next()));
					put(new Node.Pressed(was.id(), presses));
					screen.refreshPanel();
				}));
		}
		if (rows.isEmpty()) {
			rows.add(com.mopicmp.npcstudio.client.workspace.Menu.Entry.of(
				Component.translatable("npc_studio.shown.none_shown"), () -> { }));
		}
		return rows;
	}

	/** The names this document hangs things under, in the order the nodes were made. */
	private List<String> shownNames() {
		List<String> names = new ArrayList<>();
		for (Node node : state.nodes()) {
			if (node instanceof Node.Act act && act.effect() instanceof Effect.Show show
					&& !show.name().isEmpty() && !names.contains(show.name())) {
				names.add(show.name());
			}
		}
		return names;
	}

	/** A mark in the fewest words that still say which one. */
	private static String markSaid(String mark) {
		if (mark == null || mark.isEmpty()) return say("npc_studio.node.place_unchosen");
		if (com.mopicmp.npcstudio.dialogue.Mark.IT.equals(mark)) {
			return say("npc_studio.node.put_it");
		}
		String place = com.mopicmp.npcstudio.dialogue.Mark.place(mark);
		return place == null ? mark : place;
	}

	/**
	 * The places this map has, as marks.
	 *
	 * Points rather than boxes: a block is at one place, and a box is a volume. They
	 * are drawn and named in the same tool and are kept apart here for the same reason
	 * the language keeps them apart — "the fire" is somewhere, "the clearing" is
	 * somewhere you can be inside.
	 */
	private List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> placeChoices(
			String mark, Consumer<String> onPick) {
		List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> rows = new ArrayList<>();
		String now = com.mopicmp.npcstudio.dialogue.Mark.place(mark);
		for (var spot : com.mopicmp.npcstudio.client.map.Spots.all()) {
			String place = spot.name();
			rows.add(new com.mopicmp.npcstudio.client.workspace.Menu.Entry(
				null, Component.literal(place), place.equals(now), () -> {
					onPick.accept(com.mopicmp.npcstudio.dialogue.Mark.at(place));
					screen.refreshPanel();
				}));
		}
		if (rows.isEmpty()) {
			rows.add(com.mopicmp.npcstudio.client.workspace.Menu.Entry.of(
				Component.translatable("npc_studio.node.place_none"), () -> { }));
		}
		return rows;
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

	/**
	 * Every reading a character has, as a list to choose from.
	 *
	 * Named by their own ids rather than by words, and that is deliberate: the id is
	 * what goes in the file and what somebody reads back in a graph a month later, so
	 * a prettier label here would be a second name for the same thing and a second
	 * thing to look up.
	 */
	private List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> senseChoices(
			Condition.Compare compare, Consumer<Condition> onChange) {
		List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> rows = new ArrayList<>();
		for (String name : Sense.KNOWN) {
			rows.add(new com.mopicmp.npcstudio.client.workspace.Menu.Entry(
				null, Component.literal(name), name.equals(compare.variable()),
				() -> onChange.accept(new Condition.Compare(name, Scope.SENSE,
					compare.op(), compare.value()))));
		}
		return rows;
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

	/**
	 * Everything a verb may be pointed at: the relative marks, then this map's places.
	 *
	 * The places come second on purpose. The relative ones work in any world and are
	 * what most graphs want; a place is the specific answer, and a list that puts
	 * twenty doorways in front of "the nearest player" would make the common case
	 * the long one.
	 */
	private static java.util.List<String> pointable() {
		var all = new java.util.ArrayList<>(com.mopicmp.npcstudio.dialogue.Mark.KNOWN);
		for (var spot : com.mopicmp.npcstudio.client.map.Spots.all()) {
			all.add(com.mopicmp.npcstudio.dialogue.Mark.at(spot.name()));
		}
		return all;
	}

	private static String nextMark(String mark) {
		var all = pointable();
		// A mark naming a place that has since been taken away is not in the list, and
		// pressing the button should get out of it rather than sit on it. Starting over
		// from the first is the way out, and it is where a fresh one starts anyway.
		int at = all.indexOf(mark);
		return at < 0 ? all.get(0) : all.get((at + 1) % all.size());
	}

	/** What an author calls each mark, rather than what the file calls it. */
	private static String markName(String mark) {
		String place = com.mopicmp.npcstudio.dialogue.Mark.place(mark);
		// A place is called what it was called when it was put down. Not dressed up
		// with a word in front of it: the author chose that name standing in the world
		// looking at the thing, and it is already a phrase.
		if (place != null) {
			return com.mopicmp.npcstudio.client.map.Spots.has(place)
				? place : place + " (nowhere on this map)";
		}
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
		if (pace < 0.35f) return say("npc_studio.pace.creeping");
		if (pace < 0.6f) return say("npc_studio.pace.walking");
		if (pace < 0.9f) return say("npc_studio.pace.brisk");
		return say("npc_studio.pace.running");
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
			case NEUTRAL -> say("npc_studio.mood.neutral");
			case HAPPY -> say("npc_studio.mood.happy");
			case SAD -> say("npc_studio.mood.sad");
			case ANGRY -> say("npc_studio.mood.angry");
			case SURPRISED -> say("npc_studio.mood.surprised");
			case SCARED -> say("npc_studio.mood.scared");
			case THINKING -> say("npc_studio.mood.thinking");
			case SLEEPY -> say("npc_studio.mood.sleepy");
			case WINK -> say("npc_studio.mood.wink");
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
		if (ticks <= 0) return say("npc_studio.node.hold_forever");
		String said = say("npc_studio.node.seconds",
			String.format(java.util.Locale.ROOT, "%.1f", ticks / 20f));
		int own = com.mopicmp.npcstudio.client.entity.NpcGestures.lengthOf(animation);
		if (own <= 0) return said;
		if (ticks < own) return say("npc_studio.node.shorter_than", said, own);
		if (ticks > own) return say("npc_studio.node.longer_than", said, own);
		return say("npc_studio.node.exactly", said);
	}

	/**
	 * Every verb an {@code act} can be, as a list to choose from.
	 *
	 * <h2>Why a list and not the button that stepped through them</h2>
	 *
	 * Because there are sixteen. Stepping is a fine control for four of anything —
	 * you press it, you see the answer, and going one too far costs three more
	 * presses. At sixteen it is a puzzle: you press the same button up to fifteen
	 * times, read the label each time to find out whether you have gone past, and
	 * have no way back except round again. Reported exactly that way.
	 *
	 * <h2>The order, which is the only grouping there is</h2>
	 *
	 * By what the verb is about, because that is how somebody looks for one: what she
	 * does with herself, where she goes, fighting, the player, the world. The list
	 * has no headings — the widget has no notion of one — so the order carries it,
	 * and an order that groups is worth more than an alphabet that does not.
	 *
	 * <h2>Two are missing on purpose</h2>
	 *
	 * Walking a route and taking notice of arriving. Both are written by the walk
	 * node and both carry a path, and a path is drawn in the world — so an {@code
	 * act} holding one would be a route with no way to edit it. They are still named
	 * by {@link #effectName}, so a file that happens to hold one shows something
	 * honest rather than throwing while the panel is being drawn.
	 */
	/**
	 * How long a line stays up: the document's answer, or one of a few lengths.
	 *
	 * Lengths rather than a box to type ticks into. Nobody thinks in ticks, and the
	 * difference between four seconds and four and a half is not a decision anybody
	 * can make — offering it is offering a choice that cannot be got right.
	 */
	/** Whose head is drawn beside this line, as the three answers there are. */
	/**
	 * The pictures on the world's portrait shelf, with "none" first.
	 *
	 * "None" is not an absence here: it is the verb that takes a portrait down, which
	 * is a thing a scene has to be able to say. So it is the first row rather than a
	 * missing one, and choosing it leaves an act node that means "clear the screen".
	 */
	private List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> portraitChoices() {
		com.mopicmp.npcstudio.client.wardrobe.PortraitShelf.refresh();
		com.mopicmp.npcstudio.client.puppet.PuppetSheets.refresh();
		var was = (Effect.Portrait) current(Node.Act.class).effect();
		String now = was.picture();
		var rows = new ArrayList<com.mopicmp.npcstudio.client.workspace.Menu.Entry>();
		rows.add(new com.mopicmp.npcstudio.client.workspace.Menu.Entry(null,
			Component.translatable("npc_studio.node.portrait_none"),
			!was.showing(), () -> putPortrait("")));
		// The figures first, because a figure is the thing somebody made on purpose and
		// the loose pictures are everything the shelf happens to hold. On a shelf of four
		// thousand parts, putting the shelf first would bury the eight things a map uses.
		for (var figure : com.mopicmp.npcstudio.client.puppet.PuppetSheets.all()) {
			rows.add(new com.mopicmp.npcstudio.client.workspace.Menu.Entry(null,
				Component.literal(figure.name()), figure.name().equals(was.figure()),
				() -> putFigure(figure.name())));
		}
		for (var each : com.mopicmp.npcstudio.client.wardrobe.PortraitShelf.all()) {
			rows.add(new com.mopicmp.npcstudio.client.workspace.Menu.Entry(null,
				Component.literal(each.label()), each.fingerprint().equals(now),
				() -> putPortrait(each.fingerprint())));
		}
		// The way to the shelf itself, from the one place somebody is standing when
		// they find out it is empty. A window reachable only from somewhere else is a
		// window nobody finds on the day they need it.
		rows.add(new com.mopicmp.npcstudio.client.workspace.Menu.Entry(null,
			Component.translatable("npc_studio.node.portrait_manage"), false,
			() -> {
				// Comes back to the editor when the editor is a screen of its own, and
				// to the world when it is a panel of the workspace — which is exactly
				// what closing the workspace would have done anyway.
				var back = com.mopicmp.npcstudio.client.workspace.WorkspaceScreen.embedded()
					? null : screen;
				net.minecraft.client.Minecraft.getInstance().setScreenAndShow(
					new com.mopicmp.npcstudio.client.wardrobe.PortraitsScreen(back));
			}));
		// And the way to assembling one, for the same reason: this is where somebody is
		// standing when they find out that what they have is parts of a person.
		rows.add(new com.mopicmp.npcstudio.client.workspace.Menu.Entry(null,
			Component.translatable("npc_studio.node.figure_manage"), false,
			() -> {
				var back = com.mopicmp.npcstudio.client.workspace.WorkspaceScreen.embedded()
					? null : screen;
				net.minecraft.client.Minecraft.getInstance().setScreenAndShow(
					new com.mopicmp.npcstudio.client.puppet.LayoutScreen(back, null));
			}));
		return rows;
	}

	private void putPortrait(String fingerprint) {
		Node.Act was = current(Node.Act.class);
		var had = (Effect.Portrait) was.effect();
		// A picture clears the figure, and choosing a figure clears the picture. They are
		// two answers to one question, and a record holding both would leave the drawing
		// to decide which — a choice nobody made, taken somewhere nobody would look.
		put(new Node.Act(was.id(),
			new Effect.Portrait(fingerprint, had.side(), had.mirrored()), was.next()));
		screen.refreshPanel();
	}

	/**
	 * A figure chosen, dressed in the first of everything.
	 *
	 * Not left bare. A figure that appears as nothing at all teaches nobody what they
	 * have just picked, and a sheet is mostly body and clothes that are mostly wanted —
	 * so taking pieces off is the shorter journey than putting them on. See
	 * {@code Puppet.everything}, where the same argument is made about the record.
	 */
	private void putFigure(String figure) {
		Node.Act was = current(Node.Act.class);
		var had = (Effect.Portrait) was.effect();
		var sheet = com.mopicmp.npcstudio.client.puppet.PuppetSheets.named(figure);
		put(new Node.Act(was.id(), new Effect.Portrait("", had.side(), had.mirrored(),
			figure, sheet == null ? java.util.Map.of() : sheet.everything()), was.next()));
		screen.refreshPanel();
	}

	private void wear(String slot, String part) {
		Node.Act was = current(Node.Act.class);
		var had = (Effect.Portrait) was.effect();
		put(new Node.Act(was.id(), had.wearing(slot, part), was.next()));
		screen.refreshPanel();
	}

	/** What the button at the top of a portrait act says it is showing. */
	private static Component portraitName(Effect.Portrait showing) {
		if (showing.assembled()) return Component.literal(showing.figure());
		if (showing.picture().isEmpty()) {
			return Component.translatable("npc_studio.node.portrait_none");
		}
		return Component.literal(
			com.mopicmp.npcstudio.client.wardrobe.PortraitShelf.labelOf(showing.picture()));
	}

	/**
	 * A row for each slot of the chosen figure, saying what is worn there.
	 *
	 * <h2>Why every slot and not only the ones being worn</h2>
	 *
	 * Because a slot nobody has named shows nothing, and a row is the only place that
	 * fact is visible. Listing only what is worn would make an empty slot invisible and
	 * unreachable at once — somebody wanting the hood back would have no row to put it
	 * in, and nothing on the panel would say a hood existed.
	 *
	 * A sheet the client has not heard of yet gets no rows and says so. It is a moment,
	 * not a state: the list is asked for when the picker opens.
	 */
	private int figureFields(Effect.Portrait showing, int x, int y, int width,
			Consumer<AbstractWidget> add) {
		var sheet = com.mopicmp.npcstudio.client.puppet.PuppetSheets.named(showing.figure());
		if (sheet == null) {
			labels.add(new Label(say("npc_studio.node.figure_unknown"), x, y));
			return y + ROW;
		}
		for (var slot : sheet.slots()) {
			String worn = showing.choice().get(slot.name());
			labels.add(new Label(slot.name(), x, y - 10));
			int atY = y;
			add.accept(new FlatButton(x, y, width, 18,
				worn == null || worn.isEmpty()
					? Component.translatable("npc_studio.node.figure_nothing")
					: Component.literal(worn),
				0xFFBA68C8,
				() -> screen.pick(x, atY + 18, width, partChoices(slot, worn))));
			y += ROW;
		}
		return y;
	}

	/**
	 * Every other node, ticked when this comment is about it.
	 *
	 * A menu of ticks rather than a picker that closes on one answer: a comment is
	 * usually about one node and sometimes about three, and having to reopen the list
	 * for each is the difference between a feature people use and one they do not.
	 */
	private List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> aboutChoices() {
		var rows = new ArrayList<com.mopicmp.npcstudio.client.workspace.Menu.Entry>();
		Node.Comment now = current(Node.Comment.class);
		for (Node other : state.nodes()) {
			// Not itself. A comment about itself is a line from a card to the same card,
			// which is a dot and a puzzle.
			if (other.id().equals(now.id())) continue;
			boolean on = now.about().contains(other.id());
			rows.add(new com.mopicmp.npcstudio.client.workspace.Menu.Entry(null,
				Component.literal(other.id()), on,
				() -> put(current(Node.Comment.class).about(other.id(), !on))));
		}
		return rows;
	}

	/** A comment's own writing, on one line, for the button that opens it. */
	private static String shortenPlain(String text) {
		String one = text.replace("\n", " ");
		return one.length() <= 22 ? one : one.substring(0, 21) + "…";
	}

	/** What may fill one slot, and the emptiness that is always one of the answers. */
	private List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> partChoices(
			com.mopicmp.npcstudio.puppet.Puppet.Slot slot, String worn) {
		var rows = new ArrayList<com.mopicmp.npcstudio.client.workspace.Menu.Entry>();
		rows.add(new com.mopicmp.npcstudio.client.workspace.Menu.Entry(null,
			Component.translatable("npc_studio.node.figure_nothing"),
			worn == null || worn.isEmpty(), () -> wear(slot.name(), "")));
		for (var part : slot.parts()) {
			rows.add(new com.mopicmp.npcstudio.client.workspace.Menu.Entry(null,
				Component.literal(part.label()), part.label().equals(worn),
				() -> wear(slot.name(), part.label())));
		}
		return rows;
	}

	private List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> faceChoices() {
		var now = current(Node.Line.class).face();
		var rows = new ArrayList<com.mopicmp.npcstudio.client.workspace.Menu.Entry>();
		for (var face : Node.Line.Face.values()) {
			rows.add(new com.mopicmp.npcstudio.client.workspace.Menu.Entry(null,
				Component.translatable("npc_studio.node.face_" + face.name().toLowerCase()),
				face == now, () -> {
					put(current(Node.Line.class).withFace(face));
					screen.refreshPanel();
				}));
		}
		return rows;
	}

	/**
	 * What colour this line's name is, with "as the document says" at the top.
	 *
	 * Offered from the one list the drawing reads — see {@link
	 * com.mopicmp.npcstudio.dialogue.text.Tint}. A picker built from its own list is
	 * a picker that can offer a colour nothing knows how to draw, which is how a
	 * colour set in the text window came to change nothing at all.
	 */
	private List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> nameColourChoices() {
		String now = current(Node.Line.class).nameColour();
		var rows = new ArrayList<com.mopicmp.npcstudio.client.workspace.Menu.Entry>();
		rows.add(new com.mopicmp.npcstudio.client.workspace.Menu.Entry(null,
			Component.translatable("npc_studio.node.name_colour_document"), now.isEmpty(),
			() -> {
				put(current(Node.Line.class).withNameColour(""));
				screen.refreshPanel();
			}));
		for (String colour : com.mopicmp.npcstudio.dialogue.text.Tint.NAMES) {
			rows.add(new com.mopicmp.npcstudio.client.workspace.Menu.Entry(null,
				Component.translatable("npc_studio.colour." + colour)
					.withColor(com.mopicmp.npcstudio.dialogue.text.Tint.of(colour) & 0xFFFFFF),
				colour.equals(now), () -> {
					put(current(Node.Line.class).withNameColour(colour));
					screen.refreshPanel();
				}));
		}
		return rows;
	}

	private List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> lastsChoices() {
		int now = current(Node.Line.class).lasts();
		var rows = new ArrayList<com.mopicmp.npcstudio.client.workspace.Menu.Entry>();
		rows.add(lastsRow(Component.translatable("npc_studio.node.lasts_document"),
			now == Node.Line.USES_DOCUMENT, Node.Line.USES_DOCUMENT));
		for (int seconds : new int[] { 2, 3, 4, 6, 8, 12, 20 }) {
			int ticks = seconds * 20;
			rows.add(lastsRow(Component.translatable("npc_studio.node.lasts_of", seconds),
				now == ticks, ticks));
		}
		return rows;
	}

	private com.mopicmp.npcstudio.client.workspace.Menu.Entry lastsRow(
			Component said, boolean marked, int ticks) {
		return new com.mopicmp.npcstudio.client.workspace.Menu.Entry(null, said, marked, () -> {
			Node.Line was = current(Node.Line.class);
			put(was.withLasts(ticks));
			screen.refreshPanel();
		});
	}

	private List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> effectChoices() {
		Effect now = current(Node.Act.class).effect();
		List<Effect> offered = List.of(
			// What she does with herself.
			new Effect.PlayAnimation("wave",
				com.mopicmp.npcstudio.client.entity.NpcGestures.lengthOf("wave")),
			new Effect.Express(com.mopicmp.npcstudio.entity.Expression.HAPPY.name(), 60),
			new Effect.Guard(true),
			// Where she goes.
			new Effect.WalkTo(com.mopicmp.npcstudio.dialogue.Mark.LEAD, 0.45f),
			new Effect.LookAt(com.mopicmp.npcstudio.dialogue.Mark.PLAYER),
			new Effect.Halt(),
			new Effect.GoHome(true),
			// Nowhere by default, which is where she already is, so a fresh one moves
			// nobody until somebody says where. A guessed place is a place the author
			// has to find and undo.
			new Effect.Appear(new com.mopicmp.npcstudio.dialogue.Route.Point.Go(0, 0, 0)),
			// Fighting.
			new Effect.Strike(com.mopicmp.npcstudio.dialogue.Mark.KIN),
			new Effect.Fire(com.mopicmp.npcstudio.dialogue.Mark.PLAYER),
			// The player.
			// The player. Standing still is about them rather than about her, which is
			// why it sits here and not among the verbs about her body.
			new Effect.Hold(true),
			new Effect.Send(new com.mopicmp.npcstudio.dialogue.Route.Point.Go(0, 0, 0)),
			// The screen. Offered showing nothing, which is the verb that clears one —
			// picking a picture is the next click and there may not be a shelf yet.
			Effect.Portrait.NONE,
			// A property given to somebody. Sampled with a jump because it is the one
			// everybody tries first, and because a sample that does nothing teaches
			// that the verb does nothing.
			new Effect.Trait("", "minecraft:jump_strength", Effect.Trait.How.TIMES_BASE,
				0.5, true, Effect.Trait.Whose.PLAYER),
			new Effect.GiveItem("minecraft:bread", 1),
			new Effect.TakeItem("minecraft:emerald", 1),
			// The world.
			new Effect.Wall("", true),
			// Sampled as putting a fire out on whatever the rule fired about, which is
			// the example the whole verb exists for and a working line to edit.
			new Effect.PutBlock(com.mopicmp.npcstudio.dialogue.Mark.IT,
				"minecraft:campfire[lit=false]"),
			new Effect.PlaceStructure("", "npc", 40),
			// Things standing in the air. Sampled with this node's own name already in
			// them, because the name is the field nothing works without and a blank one
			// is what the validator refuses — a verb chosen from a list should not arrive
			// broken.
			new Effect.Show(current(Node.Act.class).id(),
				com.mopicmp.npcstudio.dialogue.Shown.of(
					Component.translatable("npc_studio.shown.fresh").getString()),
				new com.mopicmp.npcstudio.dialogue.Route.Point.Go(2, 1, 0)),
			new Effect.Unshow(""),
			new Effect.PlaySound("minecraft:block.note_block.bell", 1f, 1f),
			new Effect.RunCommand("say hello"));

		List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> rows = new ArrayList<>();
		for (Effect each : offered) {
			if (!offerable(each)) continue;
			// Marked by kind rather than by equality: the entry stands for "a wall",
			// not for "a wall over this box with these settings", and comparing the
			// whole value would leave the list with nothing marked the moment somebody
			// filled a field in.
			boolean itIs = each.getClass() == now.getClass();
			rows.add(new com.mopicmp.npcstudio.client.workspace.Menu.Entry(
				null, Component.literal(effectName(each)), itIs, () -> {
					// The one in force is left exactly as it is. Rebuilding it from the
					// sample would throw away the fields somebody has filled in for the
					// sake of choosing what it already was.
					if (itIs) return;
					Node.Act was = current(Node.Act.class);
					put(new Node.Act(was.id(), each, was.next()));
					screen.refreshPanel();
				}));
		}
		return rows;
	}

	/**
	 * Whether this verb is offered in the document in hand.
	 *
	 * Two rules, both about what the document is:
	 *
	 * <ul>
	 * <li>A document nobody carries — a place, a player, a thing — is not offered the verbs
	 *     that need a character. There is no body in the room, and the runtime says so in
	 *     the log for every one of them; see {@link Effect#needsCharacter}.</li>
	 * <li>Things standing in the air belong to a place. See {@code
	 *     GraphEditorScreen.PLACE_KINDS} for why the tabs are allowed to mean something.</li>
	 * </ul>
	 *
	 * A verb already in a node is always shown, whatever the tab — hiding the row that says
	 * what a node does would leave somebody looking at a node they cannot read.
	 */
	private boolean offerable(Effect effect) {
		if (current(Node.Act.class).effect().getClass() == effect.getClass()) return true;
		boolean scene = state.kind() == com.mopicmp.npcstudio.dialogue.Dialogue.Kind.SCENE;
		if (effect.needsCharacter()) return scene;
		if (effect instanceof Effect.Show || effect instanceof Effect.Unshow) {
			return state.kind() == com.mopicmp.npcstudio.dialogue.Dialogue.Kind.LOCATION;
		}
		return true;
	}

	static String effectName(Effect effect) {
		return switch (effect) {
			case Effect.PlayAnimation _ -> say("npc_studio.effect.animation");
			case Effect.GiveItem _ -> say("npc_studio.effect.give");
			case Effect.TakeItem _ -> say("npc_studio.effect.take");
			case Effect.RunCommand _ -> say("npc_studio.effect.command");
			case Effect.PlaySound _ -> say("npc_studio.effect.sound");
			case Effect.PlaceStructure _ -> say("npc_studio.effect.structure");
			case Effect.Express _ -> say("npc_studio.effect.face");
			case Effect.WalkTo _ -> say("npc_studio.effect.walk");
			case Effect.Halt _ -> say("npc_studio.effect.halt");
			case Effect.LookAt _ -> say("npc_studio.effect.look");
			case Effect.Appear _ -> say("npc_studio.effect.appear");
			case Effect.Send _ -> say("npc_studio.effect.send");
			case Effect.Portrait portrait -> say(portrait.showing()
				? "npc_studio.effect.portrait" : "npc_studio.effect.portrait_none");
			case Effect.Hold hold -> say(hold.up()
				? "npc_studio.effect.hold_up" : "npc_studio.effect.hold_down");
			case Effect.Fire _ -> say("npc_studio.effect.fire");
			case Effect.Strike _ -> say("npc_studio.effect.strike");
			case Effect.Guard _ -> say("npc_studio.effect.guard");
			case Effect.PutBlock put -> say("npc_studio.effect.put_block",
				put.block().isEmpty() ? say("npc_studio.node.trait_unchosen") : put.block());
			// Named after what it is called rather than after what it says, because the
			// name is what the rest of the graph points at: the box on the canvas has to
			// be findable from the wait that is listening for it.
			case Effect.Show show -> say("npc_studio.effect.show",
				show.name().isEmpty() ? say("npc_studio.node.trait_unnamed") : show.name());
			case Effect.Unshow unshow -> say("npc_studio.effect.unshow",
				unshow.name().isEmpty() ? say("npc_studio.node.trait_unnamed") : unshow.name());
			case Effect.Trait trait -> say(trait.on()
				? "npc_studio.effect.trait_on" : "npc_studio.effect.trait_off",
				trait.name().isEmpty() ? say("npc_studio.node.trait_unnamed") : trait.name());
			case Effect.Wall wall -> say(wall.up()
				? "npc_studio.effect.seal" : "npc_studio.effect.open");
			// Neither is on the cycle below, and neither should be: a route needs
			// somewhere to be drawn, and that is a node of its own. They are named here
			// so that a file which happens to hold one shows something honest rather
			// than throwing while the panel is being drawn.
			case Effect.Follow follow -> say("npc_studio.effect.route", follow.route().size());
			case Effect.Arrived _ -> say("npc_studio.effect.arrived");
			case Effect.GoHome _ -> say("npc_studio.effect.home");
		};
	}

	/*
	 * There was a `nextEffect` here, which stepped from one verb to the next.
	 *
	 * It is gone rather than kept beside the list, because two ways of choosing the
	 * same thing is two orders to keep in step — and the order in the list is the
	 * grouping, which a cycle cannot express at all. See effectChoices.
	 */

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

	/** A number with a point in it, or what it was when somebody is still typing. */
	private static float decimal(String text, float fallback) {
		try {
			return Float.parseFloat(text.trim().replace(',', '.'));
		} catch (NumberFormatException notANumber) {
			return fallback;
		}
	}

	private static int number(String text, int fallback) {
		try {
			return Integer.parseInt(text.trim());
		} catch (NumberFormatException notANumber) {
			return fallback;
		}
	}

	/**
	 * One line to type in, in the editor's own style.
	 *
	 * It was the game's own box until now — a sunken slot with a hard white border,
	 * sitting among a dozen flat rectangles and reading as a piece of another
	 * program pasted in. See {@link FlatField}, which is the same argument
	 * {@link FlatButton} already made about buttons.
	 *
	 * The caption is drawn above rather than inside. A placeholder in the slot and a
	 * real value in the slot look alike at a glance, which is the wrong thing for a
	 * column of fields where half of them are usually empty.
	 */
	private FlatField field(Font font, int x, int y, int width, String label, String value,
			Consumer<String> onChange, Consumer<AbstractWidget> add) {
		labels.add(new Label(label, x, y - 10));
		FlatField box = new FlatField(x, y, width, 18, Component.literal(label),
			0xFF4FC3F7, onChange);
		box.setMaxLength(512);
		box.setValue(value);
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
		return line.withSpeaker(speaker);
	}

	/**
	 * The same line with different words, keeping the drawing of what did not change.
	 *
	 * The field beside the graph is a plain box and will stay one whatever the text
	 * editor grows into — so it has to replace the words without being a way of
	 * quietly stripping the colours off a line somebody spent time on.
	 */
	private static Node.Line withText(Node.Line line, String text) {
		return line.withText(line.text().reworded(text));
	}

	/**
	 * What a staging is called, in the language the editor is being read in.
	 *
	 * It was the enum constant, lowercased: subtitle, fullscreen, cutscene. Three
	 * English words on a Russian panel, and none of them says what it does — a person
	 * pressing the button was cycling through words rather than choosing a staging.
	 */
	private static Component modeName(Presentation mode) {
		return Component.translatable("npc_studio.mode." + mode.name().toLowerCase());
	}

	private static Presentation cycle(Presentation mode) {
		Presentation[] all = Presentation.values();
		return all[(mode.ordinal() + 1) % all.length];
	}

	/**
	 * The other way a question may be staged: the bar, or a cutscene.
	 *
	 * Two of them, so a cycle is a toggle. Written as a swap rather than as the
	 * general cycle with one value skipped, because "skip the one the record will
	 * refuse anyway" is a rule in two places that has to agree with itself.
	 */
	private static Presentation otherStaging(Presentation mode) {
		return mode == Presentation.CUTSCENE ? Presentation.SUBTITLE : Presentation.CUTSCENE;
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
			case PLAYER -> say("npc_studio.scope.player");
			case WORLD -> say("npc_studio.scope.world");
			case CHARACTER -> say("npc_studio.scope.character");
			case SENSE -> say("npc_studio.scope.sense");
			case GIVEN -> say("npc_studio.scope.given");
		};
	}

	/**
	 * What a value would be declared as.
	 *
	 * So that a variable made from the list takes the type of whatever the row is
	 * already comparing against or setting. Guessed rather than asked for, because the
	 * row has already said it: somebody writing `true` into a fresh name has told the
	 * editor it is a flag, and asking again is asking them to agree with themselves.
	 */
	private static String typeOf(Value value) {
		return switch (value) {
			case Value.Num _ -> "number";
			case Value.Text _ -> "text";
			case Value.Flag _ -> "flag";
		};
	}

	private static Node renamed(Node node, String id) {
		return switch (node) {
			case Node.Comment comment ->
				new Node.Comment(id, comment.text(), comment.wide(), comment.rows(),
					comment.about());
			case Node.Line line -> line.withId(id);
			case Node.Choice choice -> new Node.Choice(id, choice.speaker(), choice.prompt(), choice.mode(), choice.options());
			case Node.Set set -> new Node.Set(id, set.variable(), set.scope(), set.value(),
				set.how(), set.next());
			case Node.Forget forget -> new Node.Forget(id, forget.everything(),
				forget.variables(), forget.scope(), forget.visited(), forget.next());
			case Node.Branch branch -> new Node.Branch(id, branch.arms(), branch.otherwise());
			case Node.Act act -> new Node.Act(id, act.effect(), act.next());
			case Node.End end -> new Node.End(id, end.homing());
			case Node.Every every -> new Node.Every(id, every.ticks(), every.next());
			case Node.Until until -> new Node.Until(id, until.condition(), until.next());
			case Node.Pressed waiting -> new Node.Pressed(id, waiting.presses());
			case Node.Do call ->
				new Node.Do(id, call.segment(), call.target(), call.with(), call.next());
			case Node.Stop stop -> new Node.Stop(id, stop.segment(), stop.next());
			case Node.Walk walk -> new Node.Walk(id, walk.route(), walk.next());
			case Node.Chance chance -> new Node.Chance(id, chance.ways());
		};
	}

	private static Node retarget(Node node, String from, String to) {
		return switch (node) {
			// A comment has no way out, but it does point at the nodes it is about — and
			// those are node ids like any other, so a rename has to walk them or the
			// writing quietly detaches from what it explains.
			case Node.Comment comment -> comment.about().contains(from)
				? comment.about(from, false).about(to, true) : comment;
			case Node.Line line -> line.next().equals(from) ? line.withNext(to) : line;
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
				? new Node.Set(set.id(), set.variable(), set.scope(), set.value(), set.how(), to)
				: set;
			case Node.Forget forget -> forget.next().equals(from)
				? new Node.Forget(forget.id(), forget.everything(), forget.variables(),
					forget.scope(), forget.visited(), to) : forget;
			case Node.Act act -> act.next().equals(from)
				? new Node.Act(act.id(), act.effect(), to) : act;
			case Node.Every every -> every.next().equals(from)
				? new Node.Every(every.id(), every.ticks(), to) : every;
			case Node.Until until -> until.next().equals(from)
				? new Node.Until(until.id(), until.condition(), to) : until;
			// Several ways out, each named after what is pressed to take it, so a rename
			// has to walk all of them the same way a fork's arms are walked.
			case Node.Pressed waiting -> new Node.Pressed(waiting.id(),
				waiting.presses().stream()
					.map(press -> press.next().equals(from)
						? new Node.Press(press.shown(), to) : press)
					.toList());
			case Node.Do call -> call.next().equals(from)
				? new Node.Do(call.id(), call.segment(), call.target(), call.with(), to) : call;
			case Node.Stop stop -> stop.next().equals(from)
				? new Node.Stop(stop.id(), stop.segment(), to) : stop;
			case Node.Walk walk -> walk.next().equals(from)
				? new Node.Walk(walk.id(), walk.route(), to) : walk;
			case Node.Branch branch -> new Node.Branch(branch.id(),
				branch.arms().stream()
					.map(arm -> arm.next().equals(from) ? new Node.Arm(arm.condition(), to) : arm)
					.toList(),
				branch.otherwise().equals(from) ? to : branch.otherwise());
			case Node.Chance chance -> new Node.Chance(chance.id(),
				chance.ways().stream().map(way -> way.equals(from) ? to : way).toList());
			case Node.End _ -> node;
		};
	}

	private void replaceOption(int slot, String label) {
		Node.Choice choice = current(Node.Choice.class);
		List<Node.Option> options = new ArrayList<>(choice.options());
		if (slot >= options.size()) return;
		Node.Option old = options.get(slot);
		options.set(slot, new Node.Option(old.label().reworded(label), old.colour(),
			old.condition(), old.next()));
		put(new Node.Choice(choice.id(), choice.speaker(), choice.prompt(), choice.mode(), options));
	}

	/** Replaces an answer's words wholesale, as the text window gives them back. */
	private void dressOption(int slot, com.mopicmp.npcstudio.dialogue.text.Words words) {
		Node.Choice choice = current(Node.Choice.class);
		List<Node.Option> options = new ArrayList<>(choice.options());
		if (slot >= options.size()) return;
		Node.Option old = options.get(slot);
		options.set(slot, new Node.Option(words, old.colour(), old.condition(), old.next()));
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

	// ------------------------------------------------------------ variables

	/**
	 * One row of the variable list.
	 *
	 * @param mine whether this document declares or uses it — which is not the same
	 *             question as whether anybody else does, and both answers are worth
	 *             having. A name in this document and in two others is one variable,
	 *             one row, and worth saying that the two others share it.
	 */
	private record Choice(String name, Scope scope, String type, boolean mine, String where) { }

	/** How wide the list will let itself get, so a row can be cut to fit rather than run off. */
	private static final int LIST_ROOM = 200;

	/** The font the panel was last built with, for measuring a list opened from a button. */
	private Font measuring;

	/**
	 * The text cut to fit, at a word boundary where there is one.
	 *
	 * The menu grows to its longest entry and then stops at a ceiling — it does not
	 * shorten what is in it. So a row wider than that ceiling ran off the side of the
	 * box, which is where the document names in the list were disappearing to.
	 */
	private String fit(String words, int room) {
		if (measuring == null || width(words) <= room) return words;
		int cut = words.length();
		while (cut > 1 && width(words.substring(0, cut) + "…") > room) cut--;
		return words.substring(0, cut) + "…";
	}

	private int width(String words) {
		return measuring == null ? words.length() * 6 : measuring.width(words);
	}

	/**
	 * The list a variable is chosen from, rather than remembered and retyped.
	 *
	 * <h2>Why the scope is part of the entry and not a button beside it</h2>
	 *
	 * Because a name on its own does not name a variable. Written under "player" and
	 * read under "world" it is two variables that look like one — both declared, both
	 * valid, the branch simply never fires, and there is nothing anywhere to notice.
	 * Chosen as a pair, that is not a mistake that can be made silently: the row you
	 * pick says which of the two you meant.
	 *
	 * <h2>Why picking a name from another document declares it here</h2>
	 *
	 * Because otherwise the document will not save at all — the validator refuses a
	 * graph that reads an undeclared name, and the editor holds the save back while it
	 * does. Offering a name and then refusing the document for having taken it up would
	 * be the editor handing somebody a fault.
	 *
	 * @param type what a freshly made variable should be, taken from the value this row
	 *             is already comparing against or setting
	 * @param writing true where the variable is written rather than read, which rules
	 *                out the scopes that are read-only
	 */
	private List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> variableChoices(
			String name, Scope scope, String type, boolean writing,
			java.util.function.BiConsumer<String, Scope> onPick) {

		// One map and not two. A name this document declares and another one uses as
		// well is one variable — the store is keyed by the player, not by the document —
		// so it is one row. Kept apart, it came out twice, both marked, reading as two
		// things that happened to be spelled the same: the precise confusion the list
		// was built to end.
		java.util.Map<String, Choice> found = new java.util.LinkedHashMap<>();

		for (var known : KnownVariables.all()) {
			Scope was = scopeOf(known.scope(), scope);
			if (writing && (was == Scope.SENSE || was == Scope.GIVEN)) continue;
			boolean own = known.document().equals(state.id());
			String key = known.name() + " " + was;
			Choice already = found.get(key);
			if (already == null) {
				found.put(key, new Choice(known.name(), was, known.type(), own,
					own ? "" : known.document()));
				continue;
			}
			String where = own || already.where().contains(known.document())
				? already.where()
				: already.where().isEmpty() ? known.document()
					: already.where() + ", " + known.document();
			found.put(key, new Choice(known.name(), was, already.type(),
				already.mine() || own, where));
		}
		// And what this document has declared since it was last saved, which the server
		// cannot know about yet. Under the scope this row already uses, because a
		// declaration says what a name holds and never says where it is kept.
		for (var declared : state.variables().entrySet()) {
			found.putIfAbsent(declared.getKey() + " " + scope,
				new Choice(declared.getKey(), scope, declared.getValue(), true, ""));
		}

		List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> rows = new ArrayList<>();
		List<Choice> sorted = new ArrayList<>(found.values());
		// This document's first, then everybody else's, and by name inside each. Reaching
		// for a name on purpose and reaching for one by accident look identical at the
		// moment of the click, and only one of them is meant.
		sorted.sort(java.util.Comparator.comparing((Choice one) -> one.mine() ? 0 : 1)
			.thenComparing(Choice::name)
			.thenComparing(one -> one.scope().name()));
		for (Choice choice : sorted) rows.add(variableRow(choice, name, scope, onPick));

		// Making one, in each of the three places a variable can be kept. The name is
		// whatever is already typed in the field below, when that is something this
		// document has not declared — which is the ordinary way round: the name gets
		// typed, the editor says it is not declared, and this is the door to the fix.
		String making = name.isEmpty() || state.variables().containsKey(name) ? freshVariable() : name;
		for (Scope where : List.of(Scope.PLAYER, Scope.WORLD, Scope.CHARACTER)) {
			rows.add(new com.mopicmp.npcstudio.client.workspace.Menu.Entry(
				com.mopicmp.npcstudio.client.workspace.Icon.ADD,
				Component.literal(say("npc_studio.node.declare_here", making, nameOf(where))),
				false, () -> {
					state.variable(making, type);
					onPick.accept(making, where);
					screen.refreshPanel();
				}));
		}
		return rows;
	}

	private com.mopicmp.npcstudio.client.workspace.Menu.Entry variableRow(Choice choice,
			String name, Scope scope, java.util.function.BiConsumer<String, Scope> onPick) {
		// The name first and cut last. What the row is for is the name and the scope;
		// the type and the documents are there to be glanced at, so they are what gives
		// way when there is not room for everything.
		String words = choice.name() + " · " + nameOf(choice.scope());
		String tail = "   " + typeWord(choice.type());
		if (!choice.where().isEmpty()) tail += "   (" + choice.where() + ")";
		words = fit(words, LIST_ROOM) + fit(tail, Math.max(24, LIST_ROOM - width(words)));
		return new com.mopicmp.npcstudio.client.workspace.Menu.Entry(
			null, Component.literal(words),
			choice.name().equals(name) && choice.scope() == scope,
			() -> {
				// Declared here on the way in. A name offered by the list and then
				// refused by the validator for not being declared would be the editor
				// handing somebody a fault; and the type comes from wherever it is
				// already declared, so the two documents cannot disagree about it.
				if (!state.variables().containsKey(choice.name())) {
					state.variable(choice.name(), choice.type());
				}
				onPick.accept(choice.name(), choice.scope());
				screen.refreshPanel();
			});
	}

	/**
	 * Which of the document's names this reset covers, ticked one at a time.
	 *
	 * A tick list rather than a field of names separated by commas, because a name
	 * typed here that does not match a declaration is a name that resets nothing, and
	 * it would look exactly like one that does.
	 */
	private List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> forgetChoices(
			Node.Forget forget) {
		List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> rows = new ArrayList<>();
		for (String name : state.variables().keySet()) {
			boolean on = forget.naming().contains(name);
			rows.add(new com.mopicmp.npcstudio.client.workspace.Menu.Entry(
				null, Component.literal(name + "   " + typeWord(state.variables().get(name))),
				on, () -> {
					Node.Forget was = current(Node.Forget.class);
					List<String> now = new ArrayList<>(was.variables());
					if (on) now.remove(name);
					else now.add(name);
					put(new Node.Forget(was.id(), was.everything(), List.copyOf(now),
						was.scope(), was.visited(), was.next()));
					screen.refreshPanel();
				}));
		}
		return rows;
	}

	/** The three places a variable can be kept, as a list rather than a button to press three times. */
	private List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> scopeChoices(
			Scope now, Consumer<Scope> onPick) {
		List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> rows = new ArrayList<>();
		for (Scope where : List.of(Scope.PLAYER, Scope.WORLD, Scope.CHARACTER)) {
			rows.add(new com.mopicmp.npcstudio.client.workspace.Menu.Entry(
				null, Component.literal(nameOf(where)), where == now, () -> {
					onPick.accept(where);
					screen.refreshPanel();
				}));
		}
		return rows;
	}

	/**
	 * The forty properties the game keeps, as a list.
	 *
	 * Chosen and not typed. A misspelt attribute is a verb that silently does nothing
	 * for ever — the failure this project keeps building doors for — and there is no
	 * point in a field when the set of right answers is finite, known, and short enough
	 * to scroll.
	 *
	 * Read out of the game's own registry rather than written down here, so a property
	 * added by the game or by another mod is offered without this list being touched.
	 */
	private List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> attributeChoices(
			Effect.Trait trait) {
		List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> rows = new ArrayList<>();
		List<String> names = new ArrayList<>();
		for (var attribute : net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.keySet()) {
			names.add(attribute.toString());
		}
		names.sort(String::compareTo);
		for (String name : names) {
			rows.add(new com.mopicmp.npcstudio.client.workspace.Menu.Entry(
				null, Component.literal(shortAttribute(name)), name.equals(trait.attribute()),
				() -> {
					put(new Node.Act(current(Node.Act.class).id(),
						new Effect.Trait(trait.name(), name, trait.how(), trait.amount(),
							trait.on(), trait.whose()),
						current(Node.Act.class).next()));
					screen.refreshPanel();
				}));
		}
		return rows;
	}

	/**
	 * The three ways the game reads the number, as a list rather than a button pressed
	 * three times.
	 *
	 * All three are offered because they mean genuinely different things: +2 hearts is
	 * not the same wish as half again as fast, and neither is twice everything
	 * including whatever else is already helping.
	 */
	private List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> howChoices(
			Effect.Trait trait) {
		List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> rows = new ArrayList<>();
		for (Effect.Trait.How how : Effect.Trait.How.values()) {
			rows.add(new com.mopicmp.npcstudio.client.workspace.Menu.Entry(
				null, Component.translatable(howKey(how)), how == trait.how(), () -> {
					put(new Node.Act(current(Node.Act.class).id(),
						new Effect.Trait(trait.name(), trait.attribute(), how, trait.amount(),
							trait.on(), trait.whose()),
						current(Node.Act.class).next()));
					screen.refreshPanel();
				}));
		}
		return rows;
	}

	private static String howKey(Effect.Trait.How how) {
		return switch (how) {
			case ADD -> "npc_studio.node.trait_add";
			case TIMES_BASE -> "npc_studio.node.trait_times_base";
			case TIMES_ALL -> "npc_studio.node.trait_times_all";
		};
	}

	/** The last part of an attribute's name, which is the part anybody reads. */
	private static String shortAttribute(String name) {
		if (name == null || name.isEmpty()) return say("npc_studio.node.trait_unchosen");
		int colon = name.indexOf(':');
		return colon < 0 ? name : name.substring(colon + 1);
	}

	/** A number typed into a field, or what was there when it is not one. */
	private static double decimal(String typed, double was) {
		try {
			return Double.parseDouble(typed.trim().replace(',', '.'));
		} catch (NumberFormatException notANumber) {
			return was;
		}
	}

	/** The scope a row was sent with, or the one this field already uses when it had none. */
	private static Scope scopeOf(String written, Scope fallback) {
		if (written == null || written.isEmpty()) return fallback;
		try {
			return Scope.valueOf(written);
		} catch (IllegalArgumentException unknown) {
			return fallback;
		}
	}

	private static String typeWord(String type) {
		return switch (type) {
			case "flag" -> say("npc_studio.graph.vars.type_flag");
			case "number" -> say("npc_studio.graph.vars.type_number");
			case "text" -> say("npc_studio.graph.vars.type_text");
			default -> type;
		};
	}

	/** A name nothing in this document has taken. */
	private String freshVariable() {
		for (int i = 1; i < 1000; i++) {
			String tried = "var" + i;
			if (!state.variables().containsKey(tried)) return tried;
		}
		return "var";
	}

	// ------------------------------------------------------------ carrying

	/**
	 * Takes hold of an arm or a test, if the pointer is on a grip.
	 *
	 * Offered the click before the widgets are — see the screen — because a grip
	 * shares its bar with the cross that deletes the arm, and a grip that only works
	 * where no button is would be a grip with holes in it.
	 */
	public boolean grab(double mx, double my) {
		for (Grip grip : grips) {
			if (mx >= grip.x() && mx < grip.x() + grip.w()
					&& my >= grip.y() && my < grip.y() + grip.h()) {
				carrying = grip;
				carryAt = (int) my;
				return true;
			}
		}
		return false;
	}

	public boolean carrying() {
		return carrying != null;
	}

	public void carry(double my) {
		carryAt = (int) my;
	}

	/**
	 * Puts down what was being carried, wherever the pointer ended up.
	 *
	 * <h2>Why the drop is worked out from where things were drawn</h2>
	 *
	 * Rather than from a count of rows and a row height. The panel's rows are not one
	 * height — a comparison against a variable is three lines tall and one against an
	 * item is two — so arithmetic on a row height would put a carried test in the
	 * wrong gap for exactly the graphs that have enough in them to need reordering.
	 * The slots are where the frames actually came out, so the answer is right by
	 * construction.
	 */
	public void drop() {
		Grip held = carrying;
		carrying = null;
		if (held == null) return;
		if (!(state.nodes().get(index) instanceof Node.Branch branch)) return;

		if (held.test() < 0) {
			int to = armAt(carryAt);
			if (to == held.arm()) return;
			List<Node.Arm> arms = new java.util.ArrayList<>(branch.arms());
			if (held.arm() >= arms.size()) return;
			Node.Arm carried = arms.remove(held.arm());
			arms.add(Math.max(0, Math.min(to, arms.size())), carried);
			put(new Node.Branch(branch.id(), List.copyOf(arms), branch.otherwise()));
			screen.refreshPanel();
			return;
		}

		// A test. It may land in another arm, and that is deliberate: it is the same
		// movement, and a rule that a test may only be reordered where it already is
		// would have to be explained to somebody who has just watched it not happen.
		int intoArm = armHolding(carryAt);
		if (intoArm < 0) return;
		int into = testAt(intoArm, carryAt);
		if (intoArm == held.arm() && (into == held.test() || into == held.test() + 1)) return;
		moveTest(branch, held.arm(), held.test(), intoArm, into);
	}

	/** Which gap between whole arms the pointer is in. */
	private int armAt(int at) {
		int to = 0;
		for (Slot slot : slots) {
			if (slot.test() >= 0) continue;
			if (at > slot.middle()) to = slot.arm() + 1;
		}
		return to;
	}

	/** Which arm the pointer is inside, or the nearest one when it is past the end. */
	private int armHolding(int at) {
		int nearest = -1;
		for (Slot slot : slots) {
			if (slot.test() >= 0) continue;
			if (at >= slot.top() && at < slot.bottom()) return slot.arm();
			if (nearest < 0 || at >= slot.top()) nearest = slot.arm();
		}
		return nearest;
	}

	/** Which gap between the tests of one arm the pointer is in. */
	private int testAt(int arm, int at) {
		int to = 0;
		for (Slot slot : slots) {
			if (slot.arm() != arm || slot.test() < 0) continue;
			if (at > slot.middle()) to = slot.test() + 1;
		}
		return to;
	}

	/**
	 * Moves one test out of one arm's condition and into another's.
	 *
	 * Both ends go through {@link ConditionRows}, which refuses anything nested
	 * deeper than a list of tests. A refusal here means one of the two conditions was
	 * written by hand into a shape this panel does not edit — so nothing moves, which
	 * is the same answer the rest of the panel gives about such a condition.
	 */
	private void moveTest(Node.Branch branch, int fromArm, int fromTest, int toArm, int toTest) {
		List<Node.Arm> arms = new java.util.ArrayList<>(branch.arms());
		if (fromArm >= arms.size() || toArm >= arms.size()) return;

		ConditionRows out = ConditionRows.read(arms.get(fromArm).condition());
		if (out == null || fromTest >= out.rows().size()) return;

		if (fromArm == toArm) {
			// Taken out before it is put back, so the place it is going is counted in
			// the list it is going into rather than in the one it left.
			ConditionRows moved = out.moved(fromTest, toTest > fromTest ? toTest - 1 : toTest);
			arms.set(fromArm, new Node.Arm(moved.write(), arms.get(fromArm).next()));
		} else {
			ConditionRows in = ConditionRows.read(arms.get(toArm).condition());
			if (in == null) return;
			ConditionRows.Row carried = out.rows().get(fromTest);
			arms.set(fromArm, new Node.Arm(out.without(fromTest).write(), arms.get(fromArm).next()));
			arms.set(toArm, new Node.Arm(in.plusAt(toTest, carried).write(), arms.get(toArm).next()));
		}
		put(new Node.Branch(branch.id(), List.copyOf(arms), branch.otherwise()));
		screen.refreshPanel();
	}

	/**
	 * Every node an exit may lead to.
	 *
	 * Comments are left out. One is a note pinned beside the graph rather than a
	 * place the conversation can be — an arm leading to one would end the scene
	 * without a word, which is a way of failing that nothing anywhere would explain.
	 */
	private List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> nodeChoices(
			String now, Consumer<String> onPick) {
		List<com.mopicmp.npcstudio.client.workspace.Menu.Entry> rows = new ArrayList<>();
		for (Node other : state.nodes()) {
			if (other instanceof Node.Comment) continue;
			String id = other.id();
			rows.add(new com.mopicmp.npcstudio.client.workspace.Menu.Entry(
				null, Component.literal(id), id.equals(now), () -> {
					onPick.accept(id);
					screen.refreshPanel();
				}));
		}
		return rows;
	}

	// ------------------------------------------------------------ drawing

	public void draw(GuiGraphicsExtractor graphics, Font font, int left, int top, int height) {
		graphics.fill(left, top, left + WIDTH, top + height, BACKGROUND);
		graphics.fill(left, top, left + 1, top + height, EDGE);

		Node node = state.nodes().get(index);
		graphics.text(font, Component.literal(GraphEditorScreen.titleOf(node)),
			left + PADDING, top + 10, GraphEditorScreen.colourOf(node));
		// The word "selected" used to sit in this corner. It said nothing the panel did
		// not already say by existing, and the corner is wanted for the cross that
		// closes the panel — which was the one thing it had no way of doing at all. The
		// cross belongs to the screen rather than to this, so that it stays put while
		// the fields under it scroll; see GraphEditorScreen.init.
		// A rule under the heading. Without it the node's type sat directly on the
		// first field's label and read as though it belonged to that field.
		graphics.fill(left + PADDING, top + 24, left + WIDTH - PADDING, top + 25, EDGE);

		// Behind the fields, and in the order they were recorded — an arm's frame is
		// put in ahead of the frames of the tests inside it, so the nesting comes out
		// of the list order rather than out of a sort every frame.
		for (Frame frame : frames) {
			outline(graphics, frame.x(), frame.y(), frame.w(), frame.h(), frame.fill(), frame.edge());
		}
		// The grips: three short rules down the left of whatever can be picked up. Drawn
		// rather than made into buttons, because pressing one does nothing — it is the
		// dragging that means something, and a button that ignores a click teaches that
		// clicks here are ignored.
		for (Grip grip : grips) {
			int gx = grip.x() + 4;
			int gy = grip.y() + Math.max(2, grip.h() / 2 - 4);
			for (int line = 0; line < 3; line++) {
				graphics.fill(gx, gy + line * 3, gx + 4, gy + line * 3 + 1,
					carrying != null ? 0xFF66BB6A : 0xFF6B7A87);
			}
		}
		if (carrying != null) drawDrop(graphics, left);

		for (Framed box : framed) {
			outline(graphics, box.x(), box.y(), box.wide(), 18, 0xFF12161B, 0xFF2C333D);
			graphics.text(font, Component.literal(box.text()), box.x() + 5, box.y() + 5, TEXT);
		}
		for (Label label : labels) {
			graphics.text(font, Component.literal(label.text()), label.x(), label.y(),
				label.text().equals(say("npc_studio.node.nothing_to_set")) ? TEXT_DIM : TEXT);
		}
		for (Reading reading : readings) {
			graphics.text(font, Component.literal(reading.words().get()),
				reading.x(), reading.y(), TEXT);
		}
	}

	/**
	 * A caption and, under it, a framed row showing a fact nobody types.
	 *
	 * @return where the next row goes
	 */
	private int saidRow(int x, int y, int width, String caption, String said) {
		labels.add(new Label(caption, x, y - 10));
		framed.add(new Framed(said, x, y, width));
		return y + ROW;
	}

	/** A filled box with a one-pixel edge, which is all a frame is. */
	private static void outline(GuiGraphicsExtractor graphics, int x, int y, int w, int h,
			int fill, int edge) {
		graphics.fill(x, y, x + w, y + h, fill);
		graphics.fill(x, y, x + w, y + 1, edge);
		graphics.fill(x, y + h - 1, x + w, y + h, edge);
		graphics.fill(x, y, x + 1, y + h, edge);
		graphics.fill(x + w - 1, y, x + w, y + h, edge);
	}

	/**
	 * The gap the carried frame would land in.
	 *
	 * Shown while dragging rather than only afterwards, because the question somebody
	 * has in the middle of the movement is "before this one or after it" — and an
	 * answer that arrives once the mouse is released is an answer that has to be
	 * undone half the time.
	 */
	private void drawDrop(GuiGraphicsExtractor graphics, int left) {
		int at;
		if (carrying.test() < 0) {
			at = gapBefore(armAt(carryAt), -1);
		} else {
			int arm = armHolding(carryAt);
			if (arm < 0) return;
			at = gapBefore(testAt(arm, carryAt), arm);
		}
		if (at == Integer.MIN_VALUE) return;
		graphics.fill(left + PADDING, at - 1, left + WIDTH - PADDING, at + 1, DROP);
	}

	/**
	 * Where the given gap is on the screen.
	 *
	 * @param arm the arm whose tests are being counted, or -1 to count whole arms
	 */
	private int gapBefore(int gap, int arm) {
		int after = Integer.MIN_VALUE;
		for (Slot slot : slots) {
			boolean mine = arm < 0 ? slot.test() < 0 : slot.arm() == arm && slot.test() >= 0;
			if (!mine) continue;
			int which = arm < 0 ? slot.arm() : slot.test();
			if (which == gap) return slot.top();
			// Past the last one, so the gap is under whatever came before it.
			if (which == gap - 1) after = slot.bottom();
		}
		return after;
	}
}
