package com.mopicmp.npcstudio.dialogue;

/**
 * A value a dialogue variable can hold.
 *
 * Three types and no more: a number, a piece of text, a flag. The temptation is
 * to add lists, objects, arithmetic — and then the condition language stops
 * being something a validator can check and starts being a programming language
 * nobody asked for. A map maker who needs arithmetic has command blocks; what
 * they need from us is that a broken dialogue is caught before a player walks
 * into it.
 *
 * Comparison between different types never throws. A dialogue is data written by
 * hand, so mismatched types are a question of when, not if, and an exception
 * thrown mid-conversation would leave the player stuck in a screen. Instead the
 * comparison simply fails, and the validator reports the mismatch beforehand.
 */
public sealed interface Value {

	record Num(double value) implements Value { }

	record Text(String value) implements Value { }

	record Flag(boolean value) implements Value { }

	static Value of(double v) { return new Num(v); }

	static Value of(String v) { return new Text(v); }

	static Value of(boolean v) { return new Flag(v); }

	/** The type name, used in validator messages. */
	default String typeName() {
		return switch (this) {
			case Num _ -> "number";
			case Text _ -> "text";
			case Flag _ -> "flag";
		};
	}

	/**
	 * Orders two values of the same type.
	 *
	 * @return the comparison, or empty when the types differ — see the note above
	 *         on why this is not an exception
	 */
	default java.util.OptionalInt compareTo(Value other) {
		if (this instanceof Num a && other instanceof Num b) {
			return java.util.OptionalInt.of(Double.compare(a.value(), b.value()));
		}
		if (this instanceof Text a && other instanceof Text b) {
			return java.util.OptionalInt.of(a.value().compareTo(b.value()));
		}
		if (this instanceof Flag a && other instanceof Flag b) {
			return java.util.OptionalInt.of(Boolean.compare(a.value(), b.value()));
		}
		return java.util.OptionalInt.empty();
	}

	/** The value a variable holds before anything has been written to it. */
	static Value defaultFor(String type) {
		return switch (type) {
			case "number" -> new Num(0);
			case "text" -> new Text("");
			case "flag" -> new Flag(false);
			default -> throw new IllegalArgumentException("unknown variable type: " + type);
		};
	}
}
