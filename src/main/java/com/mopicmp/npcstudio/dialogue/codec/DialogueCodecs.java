package com.mopicmp.npcstudio.dialogue.codec;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mopicmp.npcstudio.dialogue.Condition;
import com.mopicmp.npcstudio.dialogue.Dialogue;
import com.mopicmp.npcstudio.dialogue.Effect;
import com.mopicmp.npcstudio.dialogue.Node;
import com.mopicmp.npcstudio.dialogue.Presentation;
import com.mopicmp.npcstudio.dialogue.Scope;
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

	public static final Codec<Condition.Op> OP = lowercaseEnum(Condition.Op.class, Condition.Op.values());

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
			default -> MapCodec.unit(new Effect.PlayAnimation("none", 0))
				.validate(_ -> com.mojang.serialization.DataResult.error(() ->
					"unknown effect type \"" + type + "\""));
		};
	}

	// ------------------------------------------------------------- nodes

	private static final Codec<Node.Option> OPTION = RecordCodecBuilder.create(instance -> instance.group(
		Codec.STRING.fieldOf("label").forGetter(Node.Option::label),
		Codec.STRING.optionalFieldOf("colour").forGetter(o -> Optional.ofNullable(o.colour())),
		CONDITION.optionalFieldOf("condition", new Condition.Always()).forGetter(Node.Option::condition),
		Codec.STRING.fieldOf("next").forGetter(Node.Option::next)
	).apply(instance, (label, colour, condition, next) ->
		new Node.Option(label, colour.orElse(null), condition, next)));

	private static final Codec<Node.Arm> ARM = RecordCodecBuilder.create(instance -> instance.group(
		CONDITION.fieldOf("condition").forGetter(Node.Arm::condition),
		Codec.STRING.fieldOf("next").forGetter(Node.Arm::next)
	).apply(instance, Node.Arm::new));

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
			case Node.Branch _ -> "branch";
			case Node.Act _ -> "act";
			case Node.End _ -> "end";
			case Node.Every _ -> "every";
			case Node.Until _ -> "until";
		};
	}

	private static MapCodec<? extends Node> nodeCodec(String type) {
		return switch (type) {
			case "line" -> RecordCodecBuilder.<Node.Line>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("id").forGetter(Node.Line::id),
				Codec.STRING.optionalFieldOf("speaker", "").forGetter(Node.Line::speaker),
				Codec.STRING.fieldOf("text").forGetter(Node.Line::text),
				PRESENTATION.optionalFieldOf("mode", Presentation.SUBTITLE).forGetter(Node.Line::mode),
				Codec.STRING.optionalFieldOf("animation").forGetter(l -> Optional.ofNullable(l.animation())),
				Codec.STRING.fieldOf("next").forGetter(Node.Line::next)
			).apply(instance, (id, speaker, text, mode, animation, next) ->
				new Node.Line(id, speaker, text, mode, animation.orElse(null), next)));
			case "choice" -> RecordCodecBuilder.<Node.Choice>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("id").forGetter(Node.Choice::id),
				Codec.STRING.optionalFieldOf("speaker", "").forGetter(Node.Choice::speaker),
				Codec.STRING.optionalFieldOf("prompt", "").forGetter(Node.Choice::prompt),
				PRESENTATION.optionalFieldOf("mode", Presentation.FULLSCREEN).forGetter(Node.Choice::mode),
				OPTION.listOf().fieldOf("options").forGetter(Node.Choice::options)
			).apply(instance, Node.Choice::new));
			case "set" -> RecordCodecBuilder.<Node.Set>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("id").forGetter(Node.Set::id),
				Codec.STRING.fieldOf("variable").forGetter(Node.Set::variable),
				SCOPE.optionalFieldOf("scope", Scope.PLAYER).forGetter(Node.Set::scope),
				VALUE.fieldOf("value").forGetter(Node.Set::value),
				Codec.STRING.fieldOf("next").forGetter(Node.Set::next)
			).apply(instance, Node.Set::new));
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
			case "end" -> Codec.STRING.fieldOf("id").xmap(Node.End::new, Node.End::id);
			case "every" -> RecordCodecBuilder.<Node.Every>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("id").forGetter(Node.Every::id),
				// No default. How long to stand still is the whole content of this
				// node, and a graph that forgot to say is a graph whose author meant
				// something they did not write.
				Codec.INT.fieldOf("ticks").forGetter(Node.Every::ticks),
				Codec.STRING.fieldOf("next").forGetter(Node.Every::next)
			).apply(instance, Node.Every::new));
			case "until" -> RecordCodecBuilder.<Node.Until>mapCodec(instance -> instance.group(
				Codec.STRING.fieldOf("id").forGetter(Node.Until::id),
				CONDITION.fieldOf("condition").forGetter(Node.Until::condition),
				Codec.STRING.fieldOf("next").forGetter(Node.Until::next)
			).apply(instance, Node.Until::new));
			default -> MapCodec.unit(new Node.End("?"))
				.validate(_ -> com.mojang.serialization.DataResult.error(() ->
					"unknown node type \"" + type + "\""));
		};
	}

	// --------------------------------------------------------- dialogues

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
	public static final Codec<Dialogue> DIALOGUE = RecordCodecBuilder.create(instance -> instance.group(
		Codec.STRING.fieldOf("id").forGetter(Dialogue::id),
		Codec.INT.fieldOf("format").forGetter(Dialogue::formatVersion),
		Codec.STRING.fieldOf("start").forGetter(Dialogue::start),
		Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("variables", Map.of())
			.forGetter(Dialogue::variableTypes),
		NODE.listOf().fieldOf("nodes").forGetter(d -> List.copyOf(d.nodes().values()))
	).apply(instance, (id, format, start, variables, nodes) -> {
		java.util.LinkedHashMap<String, Node> byId = new java.util.LinkedHashMap<>();
		for (Node node : nodes) byId.put(node.id(), node);
		return new Dialogue(id, format, start, byId, variables);
	}));
}
