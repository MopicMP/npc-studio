package com.mopicmp.npcstudio.puppet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A guess at which pictures are the same part of a figure wearing different faces.
 *
 * <h2>Why a guess is the right shape for this</h2>
 *
 * Because there is nothing to derive it from. A set of layer pictures arrives with no
 * positions in it — measured, and it is not an accident of one game: a layer is trimmed
 * to its own edges when it is exported, and where it sat is left behind in whatever
 * drew it. So somebody has to say where each layer goes.
 *
 * What makes that bearable is that they should not have to say it once per <em>file</em>.
 * Eleven pairs of eyes go in the same place; the work is one placement, not eleven. So
 * the only question is how the eleven are recognised as one another, and the answer has
 * to work on names nobody agreed on in advance.
 *
 * <h2>The rule, and why it is this one</h2>
 *
 * <b>A slot is the name with its trailing digits taken off.</b> {@code eyes01} and
 * {@code eyes02} are one slot; {@code eyes} and {@code tear} are two.
 *
 * It is the one convention that is not a convention: numbering the variants is what
 * people do without being asked, in every set of anything. Counted on a real set of
 * 4208 layer pictures, 1882 of them end in a number.
 *
 * Everything else was worse in a way that matters. Grouping by a shared prefix put the
 * eyes and the tears in one slot, because both are parts of a head — and two things that
 * are worn <em>together</em> must never be variants of each other, or one of them
 * becomes unreachable. Being wrong in the other direction is harmless: a name nobody
 * numbered is a slot of its own, which is exactly what an unrecognised picture should
 * be.
 *
 * <h2>It is offered, not applied</h2>
 *
 * The person doing the placing sees the grouping and moves pictures between slots. This
 * class exists so that they start from something nearly right rather than from four
 * thousand loose pictures — not so that they are told what their names mean.
 */
public final class Slots {

	private Slots() { }

	/**
	 * The slot a picture's name belongs to.
	 *
	 * A name that is <em>only</em> digits keeps them: dropping them would leave nothing,
	 * and a slot with an empty name is a slot nobody can point at. Frame sequences named
	 * {@code 000, 001, 002} are exactly that case, and they are one slot — which happens
	 * to be right, since that is what a sequence is.
	 */
	public static String of(String name) {
		if (name == null || name.isEmpty()) return "";
		int end = name.length();
		while (end > 0 && Character.isDigit(name.charAt(end - 1))) end--;
		return end == 0 ? name : name.substring(0, end);
	}

	/**
	 * Whether two pictures are variants of one another.
	 *
	 * Asked as a question of its own because it reads better at the call site than two
	 * calls and an equals, and because it is the thing being decided.
	 */
	public static boolean sameSlot(String one, String other) {
		return of(one).equals(of(other));
	}

	/**
	 * Names grouped into slots, keeping the order they arrived in.
	 *
	 * Order matters twice. The slots come out in the order their first picture was seen,
	 * which is the order the folder was read in — so a person looking at the list sees
	 * their own arrangement rather than an alphabet. And the variants inside a slot keep
	 * their order too, so {@code eyes01} is first and stays first.
	 */
	public static Map<String, List<String>> group(List<String> names) {
		Map<String, List<String>> found = new LinkedHashMap<>();
		if (names == null) return found;
		for (String name : names) {
			if (name == null || name.isEmpty()) continue;
			found.computeIfAbsent(of(name), _ -> new ArrayList<>()).add(name);
		}
		return found;
	}
}
