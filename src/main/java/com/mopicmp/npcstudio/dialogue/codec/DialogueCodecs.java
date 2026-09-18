package com.mopicmp.npcstudio.dialogue.codec;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mopicmp.npcstudio.dialogue.Area;
import com.mopicmp.npcstudio.dialogue.Condition;
import com.mopicmp.npcstudio.dialogue.Dialogue;
import com.mopicmp.npcstudio.dialogue.Effect;
import com.mopicmp.npcstudio.dialogue.Mark;
import com.mopicmp.npcstudio.dialogue.Node;
import com.mopicmp.npcstudio.dialogue.Presentation;
import com.mopicmp.npcstudio.dialogue.Route;
import com.mopicmp.npcstudio.dialogue.Scope;
import com.mopicmp.npcstudio.dialogue.Trigger;
import com.mopicmp.npcstudio.dialogue.Value;

/**
 * Reading and writing dialogues as JSON.
 *
 * Kept out of the engine package on purpose. The engine has no Minecraft
 * imports and must keep it that way, since that is what lets a whole
 * conversation be tested in milliseconds without a game. Codecs are the seam:
 * they know about serialisation, the engine knows about conversations, and
 * neither has to know about the other.
 *
 * Nothing here depends on Minecraft either — {@code com.mojang.serialization}
 * is a standalone library — so these can be tested the same cheap way.
 *
 * The shapes are chosen for someone writing them by hand. A value is written as
 * itself, so {@code 10}, {@code "hello"} and {@code true} are a number, a piece
 * of text and a flag; nobody should have to wrap a number in an object naming
 * its type.
 */
public final class DialogueCodecs {

	private DialogueCodecs() { }

	// ------------------------------------------------------------ values

	/**
	 * A value written as itself.
	 *
	 * Order matters: booleans are tried before numbers, and numbers before text,
	 * because a permissive codec earlier in the chain would swallow everything
	 * after it.
	 */
	public static final Codec<Value> VALUE = Codec.either(
			Codec.BOOL,
			Codec.either(Codec.DOUBLE, Codec.STRING))
		.xmap(
			either -> either.map(
				Value::of,
				inner -> inner.map(Value::of, Value::of)),
			value -> switch (value) {
				case Value.Flag(boolean v) -> com.mojang.datafixers.util.Either.left(v);
				case Value.Num(double v) -> com.mojang.datafixers.util.Either.right(
					com.mojang.datafixers.util.Either.left(v));
				case Value.Text(String v) -> com.mojang.datafixers.util.Either.right(
					com.mojang.datafixers.util.Either.right(v));
			});

	public static final Codec<Scope> SCOPE = lowercaseEnum(Scope.class, Scope.values());

	public static final Codec<Presentation> PRESENTATION =
		lowercaseEnum(Presentation.class, Presentation.values());

	/** Which edge of the screen a portrait stands against. */
	public static final Codec<Effect.Portrait.Side> SIDE =
		lowercaseEnum(Effect.Portrait.Side.class, Effect.Portrait.Side.values());

	/** Whose head is drawn beside a line. */
	public static final Codec<Node.Line.Face> FACE =
		lowercaseEnum(Node.Line.Face.class, Node.Line.Face.values());

	public static final Codec<Condition.Op> OP = lowercaseEnum(Condition.Op.class, Condition.Op.values());

	public static final Codec<Node.Homing> HOMING =
		lowercaseEnum(Node.Homing.class, Node.Homing.values());

	public static final Codec<Route.Gait> GAIT =
		lowercaseEnum(Route.Gait.class, Route.Gait.values());

	/**
	 * An enum written in lower case.
	 *
	 * Written by hand rather than through {@code StringRepresentable} so the
	 * engine's enums stay free of Minecraft interfaces. The error message names
	 * what was allowed, because "not a valid Scope" tells the writer nothing.
	 */
	private static <E extends Enum<E>> Codec<E> lowercaseEnum(Class<E> type, E[] values) {
		return Codec.STRING.comapFlatMap(
			name -> {
				for (E value : values) {
					if (value.name().equalsIgnoreCase(name)) {
						return com.mojang.serialization.DataResult.success(value);
					}
				}
				StringBuilder allowed = new StringBuilder();
				for (E value : values) {
					if (!allowed.isEmpty()) allowed.append(", ");
					allowed.append(value.name().toLowerCase());
				}
				return com.mojang.serialization.DataResult.error(() ->
					"unknown " + type.getSimpleName().toLowerCase() + " \"" + name + "\"; expected one of: " + allowed);
			},
			value -> value.name().toLowerCase());
	}

	// -------------------------------------------------------- conditions

	/**
	 * Conditions nest inside one another, so the codec has to refer to itself.
	 *
	 * {@code Codec.recursive} exists for exactly this and ties the knot lazily;
	 * writing it as a plain static field would deadlock during class loading.
	 */
	public static final Codec<Condition> CONDITION = Codec.recursive("condition", self ->
		Codec.STRING.dispatch("type", DialogueCodecs::conditionType, name -> conditionCodec(name, self)));

	private static String conditionType(Condition condition) {
		return switch (condition) {
			case Condition.Always _ -> "always";
			case Condition.Not _ -> "not";
			case Condition.All _ -> "all";
			case Condition.Any _ -> "any";
			case Condition.Compare _ -> "compare";
			case Condition.HasItem _ -> "has_item";
			case Condition.Visited _ -> "visited";
			case Condition.Inside _ -> "inside";
			case Condition.Block _ -> "block";
			case Condition.Holding _ -> "holding";
		};
	}

	private static MapCodec<? extends Condition> conditionCodec(String type, Codec<Condition> self) {
		return switch (type) {
			case "always" -> MapCodec.unit(new Condition.Always());
			case "not" -> self.fieldOf("condition")
				.xmap(Condition.Not::new, Condition.Not::inner);
			case "all" -> self.listOf().fieldOf("conditions")
				.xmap(Condition.All::new, Condition.All::parts);
			case "any" -> self.listOf().fieldOf("conditions")
				.xmap(Condition.Any::new, Condition.Any::parts);
			case "compare" -> RecordCodecBuilder.<Condition.Compare>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("variable").forGetter(Condition.Compare::variable),
				SCOPE.optionalFieldOf("scope", Scope.PLAYER).forGetter(Condition.Compare::scope),
				OP.fieldOf("op").forGetter(Condition.Compare::op),
				VALUE.fieldOf("value").forGetter(Condition.Compare::value)
			).apply(instance, Condition.Compare::new));
			case "has_item" -> RecordCodecBuilder.<Condition.HasItem>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("item").forGetter(Condition.HasItem::item),
				Codec.INT.optionalFieldOf("count", 1).forGetter(Condition.HasItem::count)
			).apply(instance, Condition.HasItem::new));
			case "visited" -> Codec.STRING.fieldOf("node")
				.xmap(Condition.Visited::new, Condition.Visited::node);
			case "inside" -> Codec.STRING.fieldOf("area")
				.xmap(Condition.Inside::new, Condition.Inside::area);
			// The place and the block, both plain strings — the second in Minecraft's own
			// words, so that what to type here can be read off the debug screen rather
			// than out of our documentation.
			// An empty hand is written as air, because that is what the game calls it and
			// because a field whose emptiness meant a different question is the mistake
			// this document has already made twice.
			case "holding" -> Codec.STRING.fieldOf("item")
				.xmap(Condition.Holding::new, Condition.Holding::item);
			case "block" -> RecordCodecBuilder.<Condition.Block>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("mark").forGetter(Condition.Block::mark),
				Codec.STRING.fieldOf("block").forGetter(Condition.Block::block)
			).apply(instance, Condition.Block::new));
			default -> MapCodec.unit(new Condition.Always())
				.validate(_ -> com.mojang.serialization.DataResult.error(() ->
					"unknown condition type \"" + type + "\""));
		};
	}

	// ----------------------------------------------------------- effects

	public static final Codec<Effect> EFFECT =
		Codec.STRING.dispatch("type", DialogueCodecs::effectType, DialogueCodecs::effectCodec);

	private static String effectType(Effect effect) {
		return switch (effect) {
			case Effect.RunCommand _ -> "run_command";
			case Effect.GiveItem _ -> "give_item";
			case Effect.TakeItem _ -> "take_item";
			case Effect.PlaceStructure _ -> "place_structure";
			case Effect.PlaySound _ -> "play_sound";
			case Effect.PlayAnimation _ -> "play_animation";
			case Effect.Express _ -> "express";
			case Effect.WalkTo _ -> "walk_to";
			case Effect.Follow _ -> "follow";
			case Effect.Arrived _ -> "arrived";
			case Effect.GoHome _ -> "go_home";
			case Effect.Wall _ -> "wall";
			case Effect.Halt _ -> "halt";
			case Effect.Guard _ -> "guard";
			case Effect.Trait _ -> "trait";
			case Effect.PutBlock _ -> "put_block";
			case Effect.Show _ -> "show";
			case Effect.Unshow _ -> "unshow";
			case Effect.LookAt _ -> "look_at";
			case Effect.Appear _ -> "appear";
			case Effect.Hold _ -> "hold_player";
			case Effect.Send _ -> "send_player";
			case Effect.Portrait _ -> "portrait";
			case Effect.Fire _ -> "fire";
			case Effect.Strike _ -> "strike";
		};
	}

	private static MapCodec<? extends Effect> effectCodec(String type) {
		return switch (type) {
			case "run_command" -> Codec.STRING.fieldOf("command")
				.xmap(Effect.RunCommand::new, Effect.RunCommand::command);
			case "give_item" -> RecordCodecBuilder.<Effect.GiveItem>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("item").forGetter(Effect.GiveItem::item),
				Codec.INT.optionalFieldOf("count", 1).forGetter(Effect.GiveItem::count)
			).apply(instance, Effect.GiveItem::new));
			case "take_item" -> RecordCodecBuilder.<Effect.TakeItem>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("item").forGetter(Effect.TakeItem::item),
				Codec.INT.optionalFieldOf("count", 1).forGetter(Effect.TakeItem::count)
			).apply(instance, Effect.TakeItem::new));
			case "place_structure" -> RecordCodecBuilder.<Effect.PlaceStructure>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("structure").forGetter(Effect.PlaceStructure::structure),
				Codec.STRING.optionalFieldOf("anchor", "").forGetter(Effect.PlaceStructure::anchor),
				Codec.INT.optionalFieldOf("over_ticks", 0).forGetter(Effect.PlaceStructure::overTicks)
			).apply(instance, Effect.PlaceStructure::new));
			case "play_sound" -> RecordCodecBuilder.<Effect.PlaySound>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("sound").forGetter(Effect.PlaySound::sound),
				Codec.FLOAT.optionalFieldOf("volume", 1.0f).forGetter(Effect.PlaySound::volume),
				Codec.FLOAT.optionalFieldOf("pitch", 1.0f).forGetter(Effect.PlaySound::pitch)
			).apply(instance, Effect.PlaySound::new));
			case "play_animation" -> RecordCodecBuilder.<Effect.PlayAnimation>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("animation").forGetter(Effect.PlayAnimation::animation),
				// Absent means "until something else takes over", because that is what
				// every dialogue written before this field existed actually did. A more
				// helpful default would quietly change how those files behave, and a
				// file that plays differently after an update is worse than one that
				// needs a number filled in.
				Codec.INT.optionalFieldOf("ticks", 0).forGetter(Effect.PlayAnimation::ticks)
			).apply(instance, Effect.PlayAnimation::new));
			case "express" -> RecordCodecBuilder.<Effect.Express>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("expression").forGetter(Effect.Express::expression),
				// Held until something else changes it, absent, for the same reason an
				// animation is: that is what an author who did not think about it meant.
				Codec.INT.optionalFieldOf("ticks", 0).forGetter(Effect.Express::ticks)
			).apply(instance, Effect.Express::new));
			case "walk_to" -> RecordCodecBuilder.<Effect.WalkTo>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("mark").forGetter(Effect.WalkTo::mark),
				// A stroll by default. Somebody who did not say how fast meant
				// "go there", and a character who sprints at everything reads as
				// panicking rather than as walking.
				Codec.FLOAT.optionalFieldOf("pace", 0.45f).forGetter(Effect.WalkTo::pace)
			).apply(instance, Effect.WalkTo::new));
			// Neither of these is ever written by hand — they are what a route node
			// says to a body, and the node is where the route is drawn. They are saved
			// anyway, because a verb the file cannot carry is a verb that vanishes out
			// of any graph that happens to hold one, which is the exact failure the
			// round-trip test exists to catch.
			case "follow" -> RecordCodecBuilder.<Effect.Follow>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("order").forGetter(Effect.Follow::order),
				ROUTE.fieldOf("route").forGetter(Effect.Follow::route)
			).apply(instance, Effect.Follow::new));
			case "arrived" -> Codec.STRING.fieldOf("order")
				.xmap(Effect.Arrived::new, Effect.Arrived::order);
			case "go_home" -> Codec.BOOL.fieldOf("walking")
				.xmap(Effect.GoHome::new, Effect.GoHome::walking);
			case "wall" -> RecordCodecBuilder.<Effect.Wall>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("area").forGetter(Effect.Wall::area),
				// No default. Which way a wall is being moved is the whole of what this
				// verb says, and a missing flag guessed as "up" would leave a passage
				// sealed by a file that had merely lost a line.
				Codec.BOOL.fieldOf("up").forGetter(Effect.Wall::up)
			).apply(instance, Effect.Wall::new));
			case "halt" -> MapCodec.unit(new Effect.Halt());
			// Where and what. Both required: a verb that puts nothing somewhere, or
			// something nowhere, is a verb that does nothing and looks like it works.
			case "put_block" -> RecordCodecBuilder.<Effect.PutBlock>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("mark").forGetter(Effect.PutBlock::mark),
				Codec.STRING.fieldOf("block").forGetter(Effect.PutBlock::block)
			).apply(instance, Effect.PutBlock::new));
			// The name first, for the same reason a property's is: it is the handle
			// everything else uses, and the field somebody has to be able to find again.
			// The thing and the place are both required — a hologram of nothing, or one
			// nowhere, is a verb that does nothing and looks like it works.
			case "show" -> RecordCodecBuilder.<Effect.Show>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("name").forGetter(Effect.Show::name),
				SHOWN.fieldOf("what").forGetter(Effect.Show::what),
				POINT.fieldOf("where").forGetter(Effect.Show::where)
			).apply(instance, Effect.Show::new));
			case "unshow" -> Codec.STRING.fieldOf("name")
				.xmap(Effect.Unshow::new, Effect.Unshow::name);
			// The handle first, because it is the field that matters most and the one
			// somebody has to be able to find again. Everything else optional with the
			// commonest answer: add this much, to the player, on.
			case "trait" -> RecordCodecBuilder.<Effect.Trait>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("name").forGetter(Effect.Trait::name),
				Codec.STRING.fieldOf("attribute").forGetter(Effect.Trait::attribute),
				lowercaseEnum(Effect.Trait.How.class, Effect.Trait.How.values())
					.optionalFieldOf("how", Effect.Trait.How.ADD).forGetter(Effect.Trait::how),
				Codec.DOUBLE.optionalFieldOf("amount", 0.0).forGetter(Effect.Trait::amount),
				Codec.BOOL.optionalFieldOf("on", true).forGetter(Effect.Trait::on),
				lowercaseEnum(Effect.Trait.Whose.class, Effect.Trait.Whose.values())
					.optionalFieldOf("whose", Effect.Trait.Whose.PLAYER)
					.forGetter(Effect.Trait::whose)
			).apply(instance, Effect.Trait::new));
			case "guard" -> Codec.BOOL.fieldOf("up")
				.xmap(Effect.Guard::new, Effect.Guard::up);
			// One point, written the same two ways a route's points are. Its own field
			// name rather than "mark", because it is not one: a mark names a thing and
			// this names a piece of ground that may have nothing on it at all.
			case "send_player" -> POINT.fieldOf("where")
				.xmap(Effect.Send::new, Effect.Send::where);
			case "portrait" -> RecordCodecBuilder.<Effect.Portrait>mapCodec(instance -> instance.group(
				// Empty is a value: it means take down whatever is up. So the field is
				// optional and its absence is the same thing, rather than a refusal.
				Codec.STRING.optionalFieldOf("picture", "").forGetter(Effect.Portrait::picture),
				SIDE.optionalFieldOf("side", Effect.Portrait.Side.RIGHT)
					.forGetter(Effect.Portrait::side),
				Codec.BOOL.optionalFieldOf("mirrored", false).forGetter(Effect.Portrait::mirrored),
				// A figure assembled from a layout sheet, instead of one whole picture.
				// Optional and absent by default, so every act written before figures
				// existed reads exactly as it did and writes back the same file.
				Codec.STRING.optionalFieldOf("figure", "").forGetter(Effect.Portrait::figure),
				Codec.unboundedMap(Codec.STRING, Codec.STRING)
					.optionalFieldOf("wearing", java.util.Map.of())
					.forGetter(Effect.Portrait::choice)
			).apply(instance, Effect.Portrait::new));
			case "hold_player" -> Codec.BOOL.fieldOf("up")
				.xmap(Effect.Hold::new, Effect.Hold::up);
			case "appear" -> POINT.fieldOf("where")
				.xmap(Effect.Appear::new, Effect.Appear::where);
			case "look_at" -> Codec.STRING.fieldOf("mark")
				.xmap(Effect.LookAt::new, Effect.LookAt::mark);
			case "fire" -> Codec.STRING.fieldOf("mark")
				.xmap(Effect.Fire::new, Effect.Fire::mark);
			case "strike" -> Codec.STRING.fieldOf("mark")
				.xmap(Effect.Strike::new, Effect.Strike::mark);
			default -> MapCodec.unit(new Effect.PlayAnimation("none", 0))
				.validate(_ -> com.mojang.serialization.DataResult.error(() ->
					"unknown effect type \"" + type + "\""));
		};
	}

	// ------------------------------------------------------------- routes

	/**
	 * One point of a route: where it is, and how long to stand there.
	 *
	 * The place is three numbers in a row rather than three named fields, which is
	 * how a position is written everywhere else in a Minecraft file and how anybody
	 * reading this one will expect to find it. A route is the one part of a graph
	 * likely to be read as a column of numbers, and {@code [214, 71, -88]} is a
	 * column; {@code {"x": 214, "y": 71, "z": -88}} is three lines of punctuation.
	 *
	 * {@code stay} is absent when it is nought, which is almost always. A file full
	 * of {@code "stay": 0} would be a file where the one point that does wait is
	 * invisible among the ones that do not.
	 */
	/**
	 * Three numbers, refused as data rather than trusted as an array.
	 *
	 * Checked here because the alternative is an index out of bounds thrown while a
	 * world is loading, which takes the save down over one mistyped line in one
	 * graph. A codec that says what was wrong leaves the rest of the map alone.
	 */
	private static final Codec<List<Integer>> THREE = Codec.INT.listOf().validate(at ->
		at.size() == 3 ? com.mojang.serialization.DataResult.success(at)
			: com.mojang.serialization.DataResult.error(() ->
				"a point is three numbers and this has " + at.size()));

	/**
	 * One point of a route, told apart by which key it uses.
	 *
	 * <pre>
	 * {"go": [4, 0, 2]}      four forward, level, two to the left of the anchor
	 * {"at": [214, 71, -88]} that block of this world
	 * </pre>
	 *
	 * By the key rather than by a {@code "type"} beside it, because these are the one
	 * part of a graph anybody is likely to read as a column and edit by hand, and a
	 * column of two-word rows reads. The name is also the whole of the difference:
	 * {@code go} is an instruction and {@code at} is a place, which is exactly what
	 * the two forms are.
	 *
	 * Three numbers in a row rather than named fields for the same reason — it is how
	 * a position is written everywhere else in a Minecraft file, and
	 * {@code {"x": 214, "y": 71, "z": -88}} is three lines of punctuation.
	 *
	 * {@code stay} is absent when it is nought, which is almost always. A file full
	 * of {@code "stay": 0} would be a file where the one point that does wait is
	 * invisible among the ones that do not.
	 */
	/**
	 * A thing standing in the air.
	 *
	 * The words go through the same codec a spoken line's do, which is what makes a
	 * hologram writable in the window every line is written in — and what keeps a plain
	 * label a bare string in the file rather than a list of one run.
	 */
	public static final Codec<com.mopicmp.npcstudio.dialogue.Shown> SHOWN =
		RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.optionalFieldOf("kind", "text")
				.forGetter(com.mopicmp.npcstudio.dialogue.Shown::kindName),
			WordsCodec.WORDS.optionalFieldOf("what",
					com.mopicmp.npcstudio.dialogue.text.Words.EMPTY)
				.forGetter(com.mopicmp.npcstudio.dialogue.Shown::what),
			Codec.FLOAT.optionalFieldOf("size", 1.0f)
				.forGetter(com.mopicmp.npcstudio.dialogue.Shown::size),
			// Absent is words in the air, which is what people picture when they say
			// hologram — so it is what a file written by hand gets.
			Codec.BOOL.optionalFieldOf("plaque", false)
				.forGetter(com.mopicmp.npcstudio.dialogue.Shown::plaque)
		).apply(instance, (kind, what, size, plaque) ->
			new com.mopicmp.npcstudio.dialogue.Shown(
				com.mopicmp.npcstudio.dialogue.Shown.kindOf(kind), what, size, plaque)));

	public static final Codec<Route.Point> POINT = Codec.either(
			RecordCodecBuilder.<Route.Point.Go>create(instance -> instance.group(
				THREE.fieldOf("go").forGetter(
					go -> List.of(go.forward(), go.up(), go.left())),
				Codec.INT.optionalFieldOf("stay", 0).forGetter(Route.Point.Go::stay)
			).apply(instance, (go, stay) ->
				new Route.Point.Go(go.get(0), go.get(1), go.get(2), stay))),
			RecordCodecBuilder.<Route.Point.At>create(instance -> instance.group(
				THREE.fieldOf("at").forGetter(
					at -> List.of(at.x(), at.y(), at.z())),
				Codec.INT.optionalFieldOf("stay", 0).forGetter(Route.Point.At::stay)
			).apply(instance, (at, stay) ->
				new Route.Point.At(at.get(0), at.get(1), at.get(2), stay))))
		.xmap(either -> either.map(go -> (Route.Point) go, at -> (Route.Point) at),
			point -> point instanceof Route.Point.Go go
				? com.mojang.datafixers.util.Either.left(go)
				: com.mojang.datafixers.util.Either.right((Route.Point.At) point));

	/**
	 * A box, as its two corners.
	 *
	 * The corners are {@link #POINT}s, so a box is written in the same two forms a
	 * route is and mixes them the same way. Nothing here says which corner is which
	 * way round — that is worked out when the box is asked about, because two clicks
	 * come in whatever order somebody clicked them.
	 */
	public static final Codec<Area> AREA = RecordCodecBuilder.create(instance -> instance.group(
		POINT.fieldOf("first").forGetter(Area::first),
		POINT.fieldOf("second").forGetter(Area::second),
		// As with a route: this only says what a corner moved from now on will be
		// written as. The corners already down carry their own form in their own key.
		lowercaseEnum(Route.From.class, Route.From.values())
			.optionalFieldOf("from", Route.From.CHARACTER).forGetter(Area::from)
	).apply(instance, Area::new));

	public static final Codec<Route.From> WRITTEN_FROM =
		lowercaseEnum(Route.From.class, Route.From.values());

	public static final Codec<Route> ROUTE = RecordCodecBuilder.create(instance -> instance.group(
		POINT.listOf().optionalFieldOf("points", List.of()).forGetter(Route::points),
		GAIT.optionalFieldOf("gait", Route.Gait.WALK).forGetter(Route::gait),
		// A stroll by default, the same number a plain walk order defaults to. A
		// character who sprints everywhere reads as panicking rather than as walking.
		Codec.FLOAT.optionalFieldOf("pace", Route.STROLL).forGetter(Route::pace),
		// Only says what a fresh point will be written as. Points already down carry
		// their own form in their own key, so this can change without moving anything.
		WRITTEN_FROM.optionalFieldOf("from", Route.From.CHARACTER).forGetter(Route::from)
	).apply(instance, Route::new));

	// ------------------------------------------------------------- nodes

	private static final Codec<Node.Option> OPTION = RecordCodecBuilder.create(instance -> instance.group(
		WordsCodec.WORDS.fieldOf("label").forGetter(Node.Option::label),
		Codec.STRING.optionalFieldOf("colour").forGetter(o -> Optional.ofNullable(o.colour())),
		CONDITION.optionalFieldOf("condition", new Condition.Always()).forGetter(Node.Option::condition),
		Codec.STRING.fieldOf("next").forGetter(Node.Option::next)
	).apply(instance, (label, colour, condition, next) ->
		new Node.Option(label, colour.orElse(null), condition, next)));

	private static final Codec<Node.Arm> ARM = RecordCodecBuilder.create(instance -> instance.group(
		CONDITION.fieldOf("condition").forGetter(Node.Arm::condition),
		Codec.STRING.fieldOf("next").forGetter(Node.Arm::next)
	).apply(instance, Node.Arm::new));

	/** One thing waited on by a {@link Node.Pressed}: what is pressed, and where it leads. */
	private static final Codec<Node.Press> PRESS = RecordCodecBuilder.create(instance -> instance.group(
		Codec.STRING.fieldOf("shown").forGetter(Node.Press::shown),
		Codec.STRING.fieldOf("next").forGetter(Node.Press::next)
	).apply(instance, Node.Press::new));

	/**
	 * A node, dispatched on its type.
	 *
	 * The id is part of the node rather than only being the key of the map it
	 * sits in, so a node still knows its own name after it has been pulled out —
	 * which is what lets validator messages point at somewhere useful.
	 */
	public static final Codec<Node> NODE =
		Codec.STRING.dispatch("type", DialogueCodecs::nodeType, DialogueCodecs::nodeCodec);

	private static String nodeType(Node node) {
		return switch (node) {
			case Node.Line _ -> "line";
			case Node.Choice _ -> "choice";
			case Node.Set _ -> "set";
			case Node.Forget _ -> "forget";
			case Node.Branch _ -> "branch";
			case Node.Chance _ -> "chance";
			case Node.Act _ -> "act";
			case Node.End _ -> "end";
			case Node.Comment _ -> "comment";
			case Node.Every _ -> "every";
			case Node.Until _ -> "until";
			case Node.Pressed _ -> "pressed";
			case Node.Walk _ -> "walk";
			case Node.Do _ -> "do";
			case Node.Stop _ -> "stop";
		};
	}

	private static MapCodec<? extends Node> nodeCodec(String type) {
		return switch (type) {
			case "line" -> RecordCodecBuilder.<Node.Line>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("id").forGetter(Node.Line::id),
				Codec.STRING.optionalFieldOf("speaker", "").forGetter(Node.Line::speaker),
				WordsCodec.WORDS.fieldOf("text").forGetter(Node.Line::text),
				PRESENTATION.optionalFieldOf("mode", Presentation.SUBTITLE).forGetter(Node.Line::mode),
				Codec.STRING.optionalFieldOf("animation").forGetter(l -> Optional.ofNullable(l.animation())),
				Codec.STRING.fieldOf("next").forGetter(Node.Line::next),
				// How long this one stays up. Absent means "as the document says", which
				// is what every line written before this field existed means and has to
				// go on meaning.
				Codec.INT.optionalFieldOf("lasts", Node.Line.USES_DOCUMENT)
					.forGetter(Node.Line::lasts),
				// Whose head is beside it. Absent is the character being talked to, which
				// is what every line so far shows and must go on showing.
				FACE.optionalFieldOf("face", Node.Line.Face.SPEAKER).forGetter(Node.Line::face),
				// And what colour the name is, absent meaning the document's own answer —
				// see Dialogue.voices, which is where a character's colour is said once.
				Codec.STRING.optionalFieldOf("name_colour", "").forGetter(Node.Line::nameColour)
			).apply(instance, (id, speaker, text, mode, animation, next, lasts, face, colour) ->
				new Node.Line(id, speaker, text, mode, animation.orElse(null), next, lasts,
					face, colour)));
			case "chance" -> RecordCodecBuilder.<Node.Chance>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("id").forGetter(Node.Chance::id),
				Codec.STRING.listOf().fieldOf("ways").forGetter(Node.Chance::ways)
			).apply(instance, Node.Chance::new));
			case "choice" -> RecordCodecBuilder.<Node.Choice>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("id").forGetter(Node.Choice::id),
				Codec.STRING.optionalFieldOf("speaker", "").forGetter(Node.Choice::speaker),
				WordsCodec.WORDS.optionalFieldOf("prompt", com.mopicmp.npcstudio.dialogue.text.Words.EMPTY)
					.forGetter(Node.Choice::prompt),
				PRESENTATION.optionalFieldOf("mode", Presentation.FULLSCREEN).forGetter(Node.Choice::mode),
				OPTION.listOf().fieldOf("options").forGetter(Node.Choice::options)
			).apply(instance, Node.Choice::new));
			case "set" -> RecordCodecBuilder.<Node.Set>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("id").forGetter(Node.Set::id),
				Codec.STRING.fieldOf("variable").forGetter(Node.Set::variable),
				SCOPE.optionalFieldOf("scope", Scope.PLAYER).forGetter(Node.Set::scope),
				VALUE.fieldOf("value").forGetter(Node.Set::value),
				// Absent means "put", which is what every file written so far meant and
				// has to go on meaning.
				lowercaseEnum(Node.Set.Change.class, Node.Set.Change.values())
					.optionalFieldOf("how", Node.Set.Change.PUT).forGetter(Node.Set::how),
				Codec.STRING.fieldOf("next").forGetter(Node.Set::next)
			).apply(instance, Node.Set::new));
			// Everything but the id and the way out is optional, and the defaults are the
			// common case written down: forget what this document declares, and forget
			// where it has been. A file that says only "forget" and where to go next is
			// the reset somebody actually wanted.
			case "forget" -> RecordCodecBuilder.<Node.Forget>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("id").forGetter(Node.Forget::id),
				Codec.BOOL.optionalFieldOf("everything", true).forGetter(Node.Forget::everything),
				Codec.STRING.listOf().optionalFieldOf("variables", java.util.List.of())
					.forGetter(Node.Forget::variables),
				SCOPE.optionalFieldOf("scope", Scope.PLAYER).forGetter(Node.Forget::scope),
				Codec.BOOL.optionalFieldOf("visited", true).forGetter(Node.Forget::visited),
				Codec.STRING.fieldOf("next").forGetter(Node.Forget::next)
			).apply(instance, Node.Forget::new));
			case "branch" -> RecordCodecBuilder.<Node.Branch>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("id").forGetter(Node.Branch::id),
				ARM.listOf().fieldOf("arms").forGetter(Node.Branch::arms),
				Codec.STRING.fieldOf("otherwise").forGetter(Node.Branch::otherwise)
			).apply(instance, Node.Branch::new));
			case "act" -> RecordCodecBuilder.<Node.Act>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("id").forGetter(Node.Act::id),
				EFFECT.fieldOf("effect").forGetter(Node.Act::effect),
				Codec.STRING.fieldOf("next").forGetter(Node.Act::next)
			).apply(instance, Node.Act::new));
			// The homing is optional and defaults to standing there, because every
			// graph written before it existed ends that way — and teaching all of them
			// to walk off would change scenes nobody has touched.
			case "end" -> RecordCodecBuilder.<Node.End>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("id").forGetter(Node.End::id),
				HOMING.optionalFieldOf("then", Node.Homing.STAY).forGetter(Node.End::homing)
			).apply(instance, Node.End::new));
			// Writing on the canvas. Every field but the id is optional, so a comment
			// somebody typed and left at its default size is three words in the file.
			case "comment" -> RecordCodecBuilder.<Node.Comment>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("id").forGetter(Node.Comment::id),
				Codec.STRING.optionalFieldOf("text", "").forGetter(Node.Comment::text),
				Codec.INT.optionalFieldOf("wide", Node.Comment.ONE_BOX)
					.forGetter(Node.Comment::wide),
				Codec.INT.optionalFieldOf("rows", 3).forGetter(Node.Comment::rows),
				Codec.STRING.listOf().optionalFieldOf("about", List.of())
					.forGetter(Node.Comment::about)
			).apply(instance, Node.Comment::new));
			case "every" -> RecordCodecBuilder.<Node.Every>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("id").forGetter(Node.Every::id),
				// No default. How long to stand still is the whole content of this
				// node, and a graph that forgot to say is a graph whose author meant
				// something they did not write.
				Codec.INT.fieldOf("ticks").forGetter(Node.Every::ticks),
				Codec.STRING.fieldOf("next").forGetter(Node.Every::next)
			).apply(instance, Node.Every::new));
			case "do" -> RecordCodecBuilder.<Node.Do>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("id").forGetter(Node.Do::id),
				Codec.STRING.fieldOf("segment").forGetter(Node.Do::segment),
				// Nothing in particular by default: plenty of skills - looking busy,
				// taking cover - are not about anybody.
				Codec.STRING.optionalFieldOf("target", Mark.NOTHING).forGetter(Node.Do::target),
				Codec.unboundedMap(Codec.STRING, VALUE).optionalFieldOf("with", Map.of())
					.forGetter(Node.Do::with),
				Codec.STRING.fieldOf("next").forGetter(Node.Do::next)
			).apply(instance, Node.Do::new));
			case "stop" -> RecordCodecBuilder.<Node.Stop>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("id").forGetter(Node.Stop::id),
				Codec.STRING.fieldOf("segment").forGetter(Node.Stop::segment),
				Codec.STRING.fieldOf("next").forGetter(Node.Stop::next)
			).apply(instance, Node.Stop::new));
			case "until" -> RecordCodecBuilder.<Node.Until>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("id").forGetter(Node.Until::id),
				CONDITION.fieldOf("condition").forGetter(Node.Until::condition),
				Codec.STRING.fieldOf("next").forGetter(Node.Until::next)
			).apply(instance, Node.Until::new));
			// A list rather than a map of name to node, though a map is what it is. Order
			// is what the reader sees: the wires leave this box in the order the rows are
			// written, and a map would sort them by name behind the author's back.
			case "pressed" -> RecordCodecBuilder.<Node.Pressed>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("id").forGetter(Node.Pressed::id),
				PRESS.listOf().optionalFieldOf("presses", List.of())
					.forGetter(Node.Pressed::presses)
			).apply(instance, Node.Pressed::new));
			// The route is a field of its own rather than being spread across the node,
			// so that the same value goes to the legs as was drawn on the map.
			case "walk" -> RecordCodecBuilder.<Node.Walk>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("id").forGetter(Node.Walk::id),
				ROUTE.optionalFieldOf("route", Route.NOWHERE).forGetter(Node.Walk::route),
				Codec.STRING.fieldOf("next").forGetter(Node.Walk::next)
			).apply(instance, Node.Walk::new));
			default -> MapCodec.unit(new Node.End("?"))
				.validate(_ -> com.mojang.serialization.DataResult.error(() ->
					"unknown node type \"" + type + "\""));
		};
	}

	// --------------------------------------------------------- dialogues

	/**
	 * How a document's lines are shown.
	 *
	 * Every field optional and defaulted to what the code used to do, so a file
	 * written before this existed reads back as the same conversation shown the same
	 * way — and a document nobody has touched writes nothing at all, because the
	 * whole record is dropped when it equals the ordinary one. A format that grows a
	 * block of defaults into every file it has ever seen is a format that makes every
	 * old map look changed.
	 */
	public static final Codec<com.mopicmp.npcstudio.dialogue.Manner> MANNER =
		RecordCodecBuilder.create(instance -> instance.group(
			Codec.FLOAT.optionalFieldOf("width",
					com.mopicmp.npcstudio.dialogue.Manner.ORDINARY.width())
				.forGetter(com.mopicmp.npcstudio.dialogue.Manner::width),
			Codec.INT.optionalFieldOf("lines",
					com.mopicmp.npcstudio.dialogue.Manner.ORDINARY.lines())
				.forGetter(com.mopicmp.npcstudio.dialogue.Manner::lines),
			Codec.BOOL.optionalFieldOf("holds_player",
					com.mopicmp.npcstudio.dialogue.Manner.ORDINARY.holdsPlayer())
				.forGetter(com.mopicmp.npcstudio.dialogue.Manner::holdsPlayer),
			Codec.DOUBLE.optionalFieldOf("range",
					com.mopicmp.npcstudio.dialogue.Manner.ORDINARY.range())
				.forGetter(com.mopicmp.npcstudio.dialogue.Manner::range),
			Codec.INT.optionalFieldOf("idle_ticks",
					com.mopicmp.npcstudio.dialogue.Manner.ORDINARY.idleTicks())
				.forGetter(com.mopicmp.npcstudio.dialogue.Manner::idleTicks),
			Codec.FLOAT.optionalFieldOf("pace",
					com.mopicmp.npcstudio.dialogue.Manner.ORDINARY.pace())
				.forGetter(com.mopicmp.npcstudio.dialogue.Manner::pace),
			Codec.BOOL.optionalFieldOf("breathes",
					com.mopicmp.npcstudio.dialogue.Manner.ORDINARY.breathes())
				.forGetter(com.mopicmp.npcstudio.dialogue.Manner::breathes)
		).apply(instance, com.mopicmp.npcstudio.dialogue.Manner::new));

	/**
	 * A whole dialogue.
	 *
	 * Nodes are written as a list, not as a map keyed by id: JSON objects have no
	 * guaranteed order, and a writer reading their own file wants the
	 * conversation in the order they wrote it. The map is rebuilt on load.
	 *
	 * {@code format} is required with no default. A file without it came from
	 * somewhere we cannot reason about, and guessing its version is how a
	 * migration corrupts data instead of refusing it.
	 */
	/**
	 * Where a node sits on the canvas.
	 *
	 * Two numbers under the node's own name. Written only for the nodes somebody has
	 * actually moved, so a graph nobody has arranged carries nothing at all and goes
	 * on being laid out by the editor — which is what the absence has always meant
	 * and has to go on meaning.
	 */
	public static final Codec<com.mopicmp.npcstudio.dialogue.Pin> PIN =
		RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.fieldOf("x").forGetter(com.mopicmp.npcstudio.dialogue.Pin::x),
			Codec.INT.fieldOf("y").forGetter(com.mopicmp.npcstudio.dialogue.Pin::y)
		).apply(instance, com.mopicmp.npcstudio.dialogue.Pin::new));

	/**
	 * What sets a trigger off, as its own small tagged shape.
	 *
	 * Tagged rather than a flat bag of fields, because half the combinations of a flat
	 * bag are not causes at all — "watching a block, filed under a box, naming no
	 * block". A sealed family cannot say those, and the compiler names every switch
	 * that has to learn a new one.
	 */
	public static final Codec<Trigger.Cause> CAUSE =
		Codec.STRING.dispatch("on", DialogueCodecs::causeName, DialogueCodecs::causeCodec);

	private static String causeName(Trigger.Cause cause) {
		return switch (cause) {
			case Trigger.Cause.Inside _ -> "inside";
			case Trigger.Cause.Became _ -> "became";
			case Trigger.Cause.Used _ -> "used";
			case Trigger.Cause.UsedAny _ -> "used_any";
			case Trigger.Cause.Knack _ -> "knack";
			case Trigger.Cause.UsedItem _ -> "used_item";
		};
	}

	private static MapCodec<? extends Trigger.Cause> causeCodec(String name) {
		return switch (name) {
			case "inside" -> Codec.STRING.fieldOf("area")
				.xmap(Trigger.Cause.Inside::new, Trigger.Cause.Inside::area);
			case "became" -> RecordCodecBuilder.<Trigger.Cause.Became>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("place").forGetter(Trigger.Cause.Became::place),
				Codec.STRING.fieldOf("block").forGetter(Trigger.Cause.Became::block)
			).apply(instance, Trigger.Cause.Became::new));
			// The state is optional here and required just above, and the asymmetry is
			// real: a rule answering a click may be about the block whatever state it is
			// in, while waiting for nothing in particular is not a thing.
			case "used" -> RecordCodecBuilder.<Trigger.Cause.Used>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("place").forGetter(Trigger.Cause.Used::place),
				Codec.STRING.optionalFieldOf("block", "").forGetter(Trigger.Cause.Used::block)
			).apply(instance, Trigger.Cause.Used::new));
			case "used_any" -> Codec.STRING.fieldOf("block")
				.xmap(Trigger.Cause.UsedAny::new, Trigger.Cause.UsedAny::block);
			// One of this mod's own abilities being used, which is what lets a graph
			// charge for one — and something that costs nothing is a setting, not an
			// ability.
			case "knack" -> Codec.STRING.fieldOf("knack")
				.xmap(Trigger.Cause.Knack::new, Trigger.Cause.Knack::knack);
			// A kind of item rather than a place, because an item has no place: it is
			// wherever somebody carried it, which is the whole point of one.
			case "used_item" -> Codec.STRING.fieldOf("item")
				.xmap(Trigger.Cause.UsedItem::new, Trigger.Cause.UsedItem::item);
			default -> MapCodec.unit(new Trigger.Cause.Inside(""))
				.validate(_ -> com.mojang.serialization.DataResult.error(() ->
					"unknown trigger cause " + name));
		};
	}

	/** One trigger written out whole, which is the form the list holds. */
	private static final Codec<Trigger> TRIGGER_LONG =
		RecordCodecBuilder.create(instance -> instance.group(
			CAUSE.fieldOf("on").forGetter(Trigger::cause),
			CONDITION.optionalFieldOf("who", new Condition.Always()).forGetter(Trigger::who),
			Codec.STRING.fieldOf("starts").forGetter(Trigger::wayIn),
			lowercaseEnum(Trigger.Again.class, Trigger.Again.values())
				.optionalFieldOf("again", Trigger.Again.EVERY_TIME).forGetter(Trigger::again),
			lowercaseEnum(Trigger.Manner.class, Trigger.Manner.values())
				.optionalFieldOf("manner", Trigger.Manner.SCENE).forGetter(Trigger::manner),
			Codec.BOOL.optionalFieldOf("instead", true).forGetter(Trigger::instead)
		).apply(instance, Trigger::new));

	/** The old shape: a box name to either a bare way in or a whole trigger. */
	private static final Codec<Map<String, com.mojang.datafixers.util.Either<String, Trigger>>> TRIGGER_MAP =
		Codec.unboundedMap(Codec.STRING, Codec.either(Codec.STRING, TRIGGER_LONG));

	/**
	 * Every trigger a document has, in both shapes the file has ever used.
	 *
	 * <h2>Why the old map is still read and still written</h2>
	 *
	 * It was {@code box -> way in}, and there are such files in world saves that are
	 * not ours to rewrite. Read as a map, each entry is a box that starts something,
	 * which is exactly what it always meant.
	 *
	 * Written back the same way whenever nothing more has been said, too — and that is
	 * not tidiness. The round-trip test compares a document against itself through the
	 * file, and a format that fattened every old trigger on the first save would show up
	 * as a diff in somebody's map for a field they never touched.
	 *
	 * <h2>Why it had to stop being only a map</h2>
	 *
	 * Because a rule about a <em>kind</em> of block has no name to be filed under, and
	 * two rules about the same kind would have collided under one key. A list has no
	 * such trouble, and it lets one place carry two rules — "if it is alight, put it
	 * out" and "if it is out, light it" — which is how those are naturally written.
	 */
	public static final Codec<java.util.List<Trigger>> TRIGGERS =
		Codec.either(TRIGGER_MAP, TRIGGER_LONG.listOf())
			.xmap(either -> either.map(DialogueCodecs::fromOldMap, whole -> whole),
				DialogueCodecs::asEitherShape);

	private static java.util.List<Trigger> fromOldMap(Map<String, com.mojang.datafixers.util.Either<String, Trigger>> written) {
		var made = new java.util.ArrayList<Trigger>();
		for (var entry : written.entrySet()) {
			made.add(entry.getValue().map(
				wayIn -> Trigger.of(entry.getKey(), wayIn),
				// A long form under a key: the key was the subject, so it is put back on
				// the cause it belongs to rather than quietly lost.
				whole -> whole.cause() instanceof Trigger.Cause.Inside(String area) && area.isEmpty()
					? whole.caused(new Trigger.Cause.Inside(entry.getKey()))
					: whole));
		}
		return java.util.List.copyOf(made);
	}

	/**
	 * The old map when every trigger still fits in it, otherwise the list.
	 *
	 * A trigger stops fitting as soon as it is about a block, is a rule, asks who, or
	 * shares its box with another — because the map cannot say any of those, and writing
	 * one out in it would quietly lose what somebody wrote.
	 */
	private static com.mojang.datafixers.util.Either<Map<String, com.mojang.datafixers.util.Either<String, Trigger>>, java.util.List<Trigger>> asEitherShape(
			java.util.List<Trigger> triggers) {
		var made = new java.util.LinkedHashMap<String, com.mojang.datafixers.util.Either<String, Trigger>>();
		for (Trigger trigger : triggers) {
			if (!(trigger.cause() instanceof Trigger.Cause.Inside(String area))
					|| made.containsKey(area)) {
				return com.mojang.datafixers.util.Either.right(triggers);
			}
			made.put(area, trigger.again() == Trigger.Again.EVERY_TIME
					&& trigger.manner() == Trigger.Manner.SCENE
					&& trigger.instead()
					&& trigger.who() instanceof Condition.Always
				? com.mojang.datafixers.util.Either.left(trigger.wayIn())
				: com.mojang.datafixers.util.Either.right(trigger));
		}
		return com.mojang.datafixers.util.Either.left(made);
	}

	/** One standing property: a handle, a condition, and what the game is to be told. */
	public static final Codec<com.mopicmp.npcstudio.dialogue.Standing> STANDING =
		RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf("name")
				.forGetter(com.mopicmp.npcstudio.dialogue.Standing::name),
			// Absent is "all the time", which is the plainest form and the one most of
			// them take — and writing `{"type": "always"}` out for every one of those
			// would be noise in a file people read.
			CONDITION.optionalFieldOf("when", new Condition.Always())
				.forGetter(com.mopicmp.npcstudio.dialogue.Standing::when),
			Codec.STRING.fieldOf("attribute")
				.forGetter(com.mopicmp.npcstudio.dialogue.Standing::attribute),
			lowercaseEnum(Effect.Trait.How.class, Effect.Trait.How.values())
				.optionalFieldOf("how", Effect.Trait.How.ADD)
				.forGetter(com.mopicmp.npcstudio.dialogue.Standing::how),
			Codec.DOUBLE.optionalFieldOf("amount", 0.0)
				.forGetter(com.mopicmp.npcstudio.dialogue.Standing::amount)
		).apply(instance, com.mopicmp.npcstudio.dialogue.Standing::new));

	/** One gauge: what to read, how to draw it, where, and while what. */
	public static final Codec<com.mopicmp.npcstudio.dialogue.Gauge> GAUGE =
		RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf("label")
				.forGetter(com.mopicmp.npcstudio.dialogue.Gauge::label),
			Codec.STRING.fieldOf("variable")
				.forGetter(com.mopicmp.npcstudio.dialogue.Gauge::variable),
			SCOPE.optionalFieldOf("scope", Scope.PLAYER)
				.forGetter(com.mopicmp.npcstudio.dialogue.Gauge::scope),
			lowercaseEnum(com.mopicmp.npcstudio.dialogue.Gauge.Look.class,
					com.mopicmp.npcstudio.dialogue.Gauge.Look.values())
				.optionalFieldOf("look", com.mopicmp.npcstudio.dialogue.Gauge.Look.BAR)
				.forGetter(com.mopicmp.npcstudio.dialogue.Gauge::look),
			Codec.DOUBLE.optionalFieldOf("most", 0.0)
				.forGetter(com.mopicmp.npcstudio.dialogue.Gauge::most),
			lowercaseEnum(com.mopicmp.npcstudio.dialogue.Gauge.Corner.class,
					com.mopicmp.npcstudio.dialogue.Gauge.Corner.values())
				.optionalFieldOf("corner", com.mopicmp.npcstudio.dialogue.Gauge.Corner.TOP_LEFT)
				.forGetter(com.mopicmp.npcstudio.dialogue.Gauge::corner),
			Codec.STRING.optionalFieldOf("colour", "")
				.forGetter(com.mopicmp.npcstudio.dialogue.Gauge::colour),
			// Absent is "all the time", the same shortening a standing property gets and
			// for the same reason: writing it out on every one would be noise in a file
			// people read.
			CONDITION.optionalFieldOf("when", new Condition.Always())
				.forGetter(com.mopicmp.npcstudio.dialogue.Gauge::when)
		).apply(instance, com.mopicmp.npcstudio.dialogue.Gauge::new));

	/**
	 * What a document of kind {@code location} says about its own ground.
	 *
	 * Every field optional, including the region: a location somebody has named but not
	 * yet built anything for is a location in progress, and refusing to read it back
	 * would mean the editor could not save the half of it that exists.
	 *
	 * The default for {@code may_edit} is written out rather than left to the record's
	 * own constructor, because a missing field and a field saying "never" have to mean
	 * the same thing here — and the one that decides whether a guest can rewrite a
	 * lesson is a bad place to let those drift apart.
	 */
	public static final Codec<com.mopicmp.npcstudio.dialogue.Location> LOCATION =
		RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.optionalFieldOf("region", "")
				.forGetter(com.mopicmp.npcstudio.dialogue.Location::region),
			// Absent is "rebuilt every visit", which is what a location is. The hub is
			// the exception and says so in its own file.
			Codec.BOOL.optionalFieldOf("resets", true)
				.forGetter(com.mopicmp.npcstudio.dialogue.Location::resets),
			CONDITION.optionalFieldOf("may_edit",
					new Condition.Not(new Condition.Always()))
				.forGetter(com.mopicmp.npcstudio.dialogue.Location::mayEdit),
			// By the names the game uses. Unbounded here and bounded where it arrives
			// from a client, which is the only place an unbounded map is a danger.
			Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("rules", Map.of())
				.forGetter(com.mopicmp.npcstudio.dialogue.Location::rules)
		).apply(instance, com.mopicmp.npcstudio.dialogue.Location::new));

	public static final Codec<Dialogue> DIALOGUE = RecordCodecBuilder.create(instance -> instance.group(
		Codec.STRING.fieldOf("id").forGetter(Dialogue::id),
		Codec.INT.fieldOf("format").forGetter(Dialogue::formatVersion),
		Codec.STRING.fieldOf("start").forGetter(Dialogue::start),
		Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("variables", Map.of())
			.forGetter(Dialogue::variableTypes),
		// The named ways in, by the node each begins at. Optional, because a graph
		// without any is the ordinary case and every file written before segments
		// existed has none — and absent has to keep meaning that, for ever.
		//
		// This field was missed when segments were added, and the round-trip test
		// found it: a graph saved to a world came back with its nodes and none of
		// its skills. That is the same loss the editor had already caused once,
		// one layer further down, and it would have happened again on the next
		// save anybody made.
		Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("segments", Map.of())
			.forGetter(Dialogue::segments),
		// The boxes drawn on the map, by name. Optional for the same reason and with
		// the same promise: a graph that draws nothing is the ordinary case, every
		// file written before boxes existed has none, and absent has to go on meaning
		// that for ever.
		Codec.unboundedMap(Codec.STRING, AREA).optionalFieldOf("areas", Map.of())
			.forGetter(Dialogue::areas),
		MANNER.optionalFieldOf("showing", com.mopicmp.npcstudio.dialogue.Manner.ORDINARY)
			.forGetter(Dialogue::manner),
		Codec.unboundedMap(Codec.STRING, PIN).optionalFieldOf("places", Map.of())
			.forGetter(Dialogue::places),
		// Box to trigger: what walking into a place starts. Absent is the ordinary
		// case, because a document is a conversation and only some are also a place.
		TRIGGERS.optionalFieldOf("triggers", java.util.List.of())
			.forGetter(Dialogue::triggers),
		// Name to colour. Absent is every document written before names could be
		// coloured, and absent has to go on meaning "the usual" rather than "black".
		Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("voices", Map.of())
			.forGetter(Dialogue::voices),
		// Absent is a conversation, which is what every document written before this
		// field was — and absent has to go on meaning that for ever.
		lowercaseEnum(Dialogue.Kind.class, Dialogue.Kind.values())
			.optionalFieldOf("kind", Dialogue.Kind.SCENE).forGetter(Dialogue::kind),
		// Properties that are simply true while something holds. Absent is a document
		// with none, which is every document written before this and most after it.
		STANDING.listOf().optionalFieldOf("standing", List.of()).forGetter(Dialogue::standing),
		// What the player is shown of all this. Absent is a document that shows nothing,
		// which is every document written before this and most after it.
		GAUGE.listOf().optionalFieldOf("gauges", List.of()).forGetter(Dialogue::gauges),
		// Only a location has one, so absent is every other document there will ever be.
		LOCATION.optionalFieldOf("location").forGetter(Dialogue::location),
		// Where this document lives. Absent is the map's own, which is where an author
		// works and what every document written before locations existed belongs to.
		Codec.STRING.optionalFieldOf("within", "").forGetter(Dialogue::within),
		NODE.listOf().fieldOf("nodes").forGetter(d -> List.copyOf(d.nodes().values()))
	).apply(instance, (id, format, start, variables, segments, areas, manner, places,
			triggers, voices, kind, standing, gauges, location, within, nodes) -> {
		java.util.LinkedHashMap<String, Node> byId = new java.util.LinkedHashMap<>();
		for (Node node : nodes) byId.put(node.id(), node);
		return new Dialogue(id, format, start, byId, variables, segments, areas, manner,
			places, triggers, voices, kind, standing, gauges, location, within);
	}));
}
