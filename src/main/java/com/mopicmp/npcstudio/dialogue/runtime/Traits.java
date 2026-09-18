package com.mopicmp.npcstudio.dialogue.runtime;

import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.dialogue.Effect;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

/**
 * Giving somebody a property they did not have, and taking it back.
 *
 * <h2>Why this is one class and not forty</h2>
 *
 * Because the game keeps the forty. How high you jump, how far you reach, how fast you
 * mine, how hard you land, how big you are, how much gravity there is — all of them are
 * attributes, all applied on the server, all synchronised by the game without being
 * asked, and none of them needing a line of client code.
 *
 * So the thing that sounded like a piece of work per ability is one verb for nearly all
 * of it. What is left over — double jump and its kind — the game has no word for, and
 * those are real work each.
 *
 * <h2>Why the handle matters more than the number</h2>
 *
 * A modifier goes on under an id and comes off by the same id. That id is built from
 * the name the author gave the verb, which means one scene can grant a boon and another
 * scene in another document can take it away.
 *
 * It also means the author can always find it again. A modifier survives a restart,
 * lives in the player's own saved data, and nothing anywhere in the game shows it — so
 * one applied under a name nobody remembers is a permanent invisible change to somebody
 * with no way back. That is the same shape as a wall left standing, and it is why the
 * name is asked for rather than derived.
 */
public final class Traits {

	private Traits() { }

	/**
	 * Everything this mod has put on somebody lives under one namespace.
	 *
	 * So that they can be told apart from the game's own and from other mods' — which
	 * matters for the sweep a standing rule will need, and matters now for being able
	 * to say honestly which of these are ours.
	 */
	public static final String OURS = NpcStudio.MOD_ID;

	/**
	 * Standing properties are filed apart from granted ones, and it matters.
	 *
	 * A property granted by a verb is meant to last: the boon stays until something
	 * takes it away, which is what a boon is. A property from a standing rule is
	 * derived — it is on because a condition holds — so when the rule is deleted from
	 * the document there is nobody left to take it off, and it would sit on every
	 * player who ever had it, for ever, with nothing anywhere to say why.
	 *
	 * That is the wall left standing again, so there is a sweep for it. The sweep can
	 * only be safe if it can tell the two apart, which is what these two prefixes are
	 * for: it removes the derived ones no live rule names, and never touches a boon.
	 */
	private static final String GRANTED = "trait/";
	private static final String DERIVED = "standing/";

	/** The id a named trait goes on under. */
	public static Identifier handle(String name) {
		return Identifier.fromNamespaceAndPath(OURS, GRANTED + tidy(name));
	}

	/** The id a standing property goes on under, which the sweep may take off again. */
	public static Identifier standingHandle(String document, String name) {
		// Named by the document as well, because two documents may perfectly well both
		// grant a boon called "прыжок" and neither should be able to remove the other's.
		// A granted trait is not named this way on purpose: taking one away from another
		// document is exactly what a boon needs.
		return Identifier.fromNamespaceAndPath(OURS, DERIVED + tidy(document) + "/" + tidy(name));
	}

	/**
	 * A name cut down to what an id may hold.
	 *
	 * Cut rather than refused, because the name is something a person typed in a field
	 * — and refusing it would mean an author whose only mistake was writing in their own
	 * language, in a mod written in two.
	 */
	private static String tidy(String name) {
		StringBuilder clean = new StringBuilder();
		for (char letter : name.toLowerCase().toCharArray()) {
			clean.append("abcdefghijklmnopqrstuvwxyz0123456789_.-".indexOf(letter) >= 0
				? letter : '_');
		}
		return clean.isEmpty() ? "unnamed" : clean.toString();
	}

	/**
	 * Puts a trait on, or takes it off.
	 *
	 * <h2>Why it is always removed first</h2>
	 *
	 * Because the game refuses a second modifier under an id that is already there, and
	 * refuses it quietly. A graph that granted the same boon twice — walked into the
	 * room again, said yes a second time — would have kept the first amount for ever
	 * while every field in the editor said the new one.
	 */
	public static void put(LivingEntity who, Effect.Trait trait) {
		Holder<Attribute> attribute = attributeOf(trait.attribute());
		if (attribute == null) return;

		AttributeInstance instance = who.getAttribute(attribute);
		if (instance == null) {
			// The attribute exists but this creature has not got it — a player has no
			// follow range, a bat has no reach. Not an error worth stopping a scene
			// over, but worth saying, because from outside it looks exactly like a verb
			// that did nothing.
			NpcStudio.LOGGER.warn("\"{}\" has no {}; the trait \"{}\" was not applied.",
				who.getName().getString(), trait.attribute(), trait.name());
			return;
		}

		Identifier id = handle(trait.name());
		instance.removeModifier(id);
		if (!trait.on()) return;
		instance.addPermanentModifier(new AttributeModifier(id, trait.amount(), operation(trait.how())));
	}

	/**
	 * Settles every standing property of every document onto one player.
	 *
	 * <h2>Why this is a whole pass and not a nudge</h2>
	 *
	 * Because the answer to "is it on" can change without anything happening: a
	 * condition may read a variable, a box the player is standing in, or what is in
	 * their hand, and only the first of those goes through the engine. So the honest
	 * thing is to ask all of them and put each where the answer says.
	 *
	 * It is cheap because putting a modifier that is already there under the same id
	 * with the same number is the same modifier, and taking off one that is not there
	 * is nothing. What it costs is evaluating the conditions, and there are as many of
	 * those as somebody wrote.
	 *
	 * <h2>Why the sweep is part of it</h2>
	 *
	 * A property whose rule has been deleted has nobody left to take it off. Every
	 * derived id we did not just put on is therefore removed — which is safe precisely
	 * because derived ids are filed apart from granted ones, and a boon granted by a
	 * verb is not ours to sweep.
	 */
	public static java.util.List<String> settle(LivingEntity who, java.util.List<Wanted> rules) {
		java.util.Set<Identifier> keeping = new java.util.HashSet<>();
		java.util.List<String> knacks = new java.util.ArrayList<>();
		for (Wanted rule : rules) {
			// Ours rather than the game's: there is no modifier to apply, only a name to
			// hand to the client, which is where a jump happens. An author has no
			// business knowing which of the two a property is — see Knack.
			if (com.mopicmp.npcstudio.dialogue.Knack.is(rule.standing().attribute())) {
				if (rule.on() && !knacks.contains(rule.standing().attribute())) {
					knacks.add(rule.standing().attribute());
				}
				continue;
			}
			Holder<Attribute> attribute = attributeOf(rule.standing().attribute());
			if (attribute == null) continue;
			AttributeInstance instance = who.getAttribute(attribute);
			if (instance == null) continue;

			Identifier id = standingHandle(rule.document(), rule.standing().name());
			instance.removeModifier(id);
			if (!rule.on()) continue;
			instance.addPermanentModifier(new AttributeModifier(id, rule.standing().amount(),
				operation(rule.standing().how())));
			keeping.add(id);
		}
		sweep(who, keeping);
		return java.util.List.copyOf(knacks);
	}

	/** One standing rule, already decided, with the document it belongs to. */
	public record Wanted(String document, com.mopicmp.npcstudio.dialogue.Standing standing,
		boolean on) { }

	/**
	 * Takes off every derived property this mod has left on somebody that nothing wants.
	 *
	 * Run with what was just put on, so a rule deleted from a document — or a document
	 * deleted from the world — stops leaving a permanent invisible change behind. The
	 * same job {@code Walls} does for blocks, and for the same reason: what is put down
	 * by a rule has to be picked up by the rule going away.
	 */
	private static void sweep(LivingEntity who, java.util.Set<Identifier> keeping) {
		for (var reference : BuiltInRegistries.ATTRIBUTE.listElements().toList()) {
			@SuppressWarnings("unchecked")
			Holder<Attribute> attribute = (Holder<Attribute>) reference;
			AttributeInstance instance = who.getAttribute(attribute);
			if (instance == null) continue;
			for (AttributeModifier modifier : java.util.List.copyOf(instance.getModifiers())) {
				Identifier id = modifier.id();
				if (!OURS.equals(id.getNamespace())) continue;
				if (!id.getPath().startsWith(DERIVED)) continue;
				if (keeping.contains(id)) continue;
				instance.removeModifier(id);
			}
		}
	}

	/** Takes off everything this document ever named, for one person. */
	public static void clear(LivingEntity who, Iterable<String> names) {
		for (String name : names) {
			Identifier id = handle(name);
			for (var reference : BuiltInRegistries.ATTRIBUTE.listElements().toList()) {
				@SuppressWarnings("unchecked")
				Holder<Attribute> attribute = (Holder<Attribute>) reference;
				AttributeInstance instance = who.getAttribute(attribute);
				if (instance != null) instance.removeModifier(id);
			}
		}
	}

	/**
	 * The attribute a name means, or null.
	 *
	 * Null rather than a throw, and logged once by the caller: a misspelt attribute is
	 * a verb that silently does nothing, which is the failure this project keeps
	 * building doors for. The validator catches it before it is saved; this is the same
	 * answer for a file written by hand.
	 */
	@SuppressWarnings("unchecked")
	public static Holder<Attribute> attributeOf(String name) {
		if (name == null || name.isEmpty()) return null;
		Identifier id = Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name);
		if (id == null) return null;
		return BuiltInRegistries.ATTRIBUTE.get(id).map(reference -> (Holder<Attribute>) reference)
			.orElse(null);
	}

	/** Whether a name is one the game knows, for the validator and the editor. */
	public static boolean known(String name) {
		return attributeOf(name) != null;
	}

	private static AttributeModifier.Operation operation(Effect.Trait.How how) {
		return switch (how) {
			case ADD -> AttributeModifier.Operation.ADD_VALUE;
			case TIMES_BASE -> AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
			case TIMES_ALL -> AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
		};
	}
}
