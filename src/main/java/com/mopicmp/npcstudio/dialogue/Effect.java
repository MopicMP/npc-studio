package com.mopicmp.npcstudio.dialogue;

/**
 * Something the engine asks the world to do.
 *
 * The engine never touches the world itself — it returns a list of these and
 * the server carries them out. That is what keeps it testable: a test can run a
 * whole conversation and then assert on the effects that came out, without a
 * server, an inventory, or a single Minecraft class.
 *
 * It also keeps the order honest. Effects come out in the order they were
 * reached, so replaying them replays the conversation.
 */
public sealed interface Effect {

	/**
	 * Whether carrying this out needs a character to carry it out.
	 *
	 * <h2>Why the language answers this and not the editor</h2>
	 *
	 * Because it is a fact about what the runtime does, and it was being guessed at in two
	 * other places: the editor offered every verb on every tab, and a document nobody
	 * carries — a place, a player, a thing — quietly logged "that is about a character, and
	 * there is none" for a third of what it offered. A list that offers what cannot work is
	 * a list that teaches the wrong thing about the language.
	 *
	 * Answering here makes it one switch the compiler keeps honest: a new verb cannot be
	 * added without saying which half it is in.
	 *
	 * <h2>The two that are here for a reason worth writing down</h2>
	 *
	 * A sound and a saved building are both about the world rather than about anybody, and
	 * both answer yes — because that is what the runtime does today: neither has an arm of
	 * its own, so both go to the character and a place gets nothing. Marked truthfully
	 * rather than aspirationally, so that the editor hides exactly what does not work. Two
	 * arms in {@code DialogueRuntime.apply} would move them across, and that is the whole
	 * of the debt.
	 */
	default boolean needsCharacter() {
		return switch (this) {
			// The world, the player, and the screen. None of these looks at a body.
			case RunCommand _, GiveItem _, TakeItem _, Wall _, PutBlock _, Send _,
					Portrait _, Hold _, Show _, Unshow _ -> false;
			// A property may be given to either, and the verb says which.
			case Trait trait -> trait.whose() == Trait.Whose.CHARACTER;
			// Her body, her legs, her face, her weapon.
			case PlayAnimation _, Express _, WalkTo _, Halt _, Guard _, LookAt _, Appear _,
					Strike _, Fire _, Follow _, Arrived _, GoHome _ -> true;
			// See above: about the world, and about a character until they have arms of
			// their own where the effects are carried out.
			case PlaySound _, PlaceStructure _ -> true;
		};
	}

	/** Runs a command as the server, with the player as context. */
	record RunCommand(String command) implements Effect { }

	record GiveItem(String item, int count) implements Effect { }

	record TakeItem(String item, int count) implements Effect { }

	/**
	 * Places a saved structure.
	 *
	 * The anchor is a named point rather than coordinates, so a scene can be
	 * copied to another part of the map without editing every action in it.
	 * `overTicks` spreads the placement out — a building that appears one block
	 * at a time is the point of the feature; one that blinks into existence is
	 * just a command block with extra steps.
	 */
	record PlaceStructure(String structure, String anchor, int overTicks) implements Effect { }

	record PlaySound(String sound, float volume, float pitch) implements Effect { }

	/**
	 * Starts an animation on the NPC that is speaking, for a while.
	 *
	 * <h2>Why there is a duration at all</h2>
	 *
	 * Because without one an animation never finishes. A looping emote loops for
	 * the rest of the session; a non-looping one is worse, because its playhead
	 * stops at the last keyframe and stays there — a character told to bow stayed
	 * bowed until somebody told it to do something else. Both read as a bug and
	 * neither was one: nothing had ever been asked to end.
	 *
	 * @param ticks how long to hold it, in ticks. Above zero, the character goes
	 *              back to standing or walking afterwards; zero or below means it
	 *              keeps going until something else takes over, which is what a
	 *              guard permanently at attention wants.
	 */
	record PlayAnimation(String animation, int ticks) implements Effect { }

	/**
	 * Sets the face the speaking NPC makes, for a while.
	 *
	 * <h2>Why an author says this rather than the mod working it out</h2>
	 *
	 * It was going to be derived from whichever animation was playing, which would
	 * have cost nothing — the animation is already synchronised. Then the pack was
	 * counted: of seven hundred and twelve animations, <b>four</b> are named after
	 * a mood. A rule reading names would have fired once in a blue moon and at the
	 * wrong moment.
	 *
	 * So it is authored, like the line it belongs to. Which is the better answer
	 * anyway: the same words can be said kindly or bitterly, and only the person
	 * writing them knows which.
	 *
	 * @param expression one of {@link com.mopicmp.npcstudio.entity.Expression}, by
	 *                   name. An unknown one is an ordinary face rather than an
	 *                   error — a conversation must not stop over a typo.
	 * @param ticks      how long to hold it. Zero or below holds it until
	 *                   something else changes it, the same as an animation.
	 */
	record Express(String expression, int ticks) implements Effect { }

	// ------------------------------------------------------------- the verbs

	/**
	 * Walk to a {@link Mark}.
	 *
	 * <h2>Why this does not wait</h2>
	 *
	 * It orders a walk and returns at once, and the graph waits for the arrival
	 * itself — {@code until walking is false}. That is not a shortcut: it is what
	 * lets a character do something else on the way. A walk that blocked the
	 * graph until it finished would be a character who cannot look at anything,
	 * change her mind, or notice she is being shot at while crossing a courtyard.
	 *
	 * @param pace nought to one. Below half is a stroll to go and see something;
	 *             one is a run. The number is the same one the legs already
	 *             work in, so an author can ask for anything in between.
	 */
	record WalkTo(String mark, float pace) implements Effect { }

	/** Stop where she is. The walk is abandoned, not paused. */
	record Halt() implements Effect { }

	/**
	 * Walk a whole path, point by point, and remember whose order it was.
	 *
	 * <h2>Why an author never picks this one</h2>
	 *
	 * Because a route needs somewhere to be drawn, and that is {@link Node.Walk}.
	 * This is what the node says to the body, and it is here rather than being a
	 * method call for the reason everything else here is: the engine does not touch
	 * the world, it hands out orders and something else carries them out. A route
	 * that reached the legs any other way would be the one instruction in the
	 * language that cannot be watched going past in a test.
	 *
	 * <h2>Why the order has a name</h2>
	 *
	 * So the node can ask about its own. The same character may be sent along
	 * several routes by several parts of a graph, and "are you walking" cannot tell
	 * them apart — a node that took somebody else's walk for its own would move on
	 * the moment an unrelated one finished. The name is the node's id, which is
	 * unique in a document by construction.
	 *
	 * Sending the same order twice is not an error and not a restart: it is what a
	 * node does on every tick it is standing at, and the body recognises the order
	 * it is already carrying out.
	 */
	record Follow(String order, Route route) implements Effect { }

	/**
	 * The graph has taken notice that a route finished.
	 *
	 * <h2>Why arriving has to be collected rather than just observed</h2>
	 *
	 * Because a finished route stays finished. A patrol is a route whose exit leads
	 * back to itself, so the node is entered again a moment later — and it would
	 * find the same arrival still lying there, pass straight through it, and go
	 * round again without a step being taken. The graph would spin at full speed
	 * inside one tick.
	 *
	 * So the arrival is a thing that can be picked up once. Not a character
	 * arriving anywhere — she did that already — but the graph saying it has had
	 * that answer, which is the difference between a latch and a light.
	 */
	record Arrived(String order) implements Effect { }

	/**
	 * Go back to where you were placed, and stand as you were placed.
	 *
	 * <h2>Why the facing travels with it</h2>
	 *
	 * Because arriving is not the same as being back. A character who walks home and
	 * stops facing whichever way she happened to approach is standing in the right
	 * square looking at a wall, which reads as broken rather than as finished — and
	 * it was reported that way before this existed.
	 *
	 * @param walking true to walk there like anybody else, false to be simply there
	 *                again. The second is for a scene watched more than once, where
	 *                the journey back is a minute nobody asked to see.
	 */
	record GoHome(boolean walking) implements Effect { }

	/**
	 * Fill one of this document's boxes so that nobody can walk through it, or empty
	 * it again.
	 *
	 * <h2>Why up and down are one word with a flag</h2>
	 *
	 * Because they must name the same box, and two records naming boxes are two
	 * places for the name to be wrong in. It is also how it reads on the canvas: the
	 * two boxes of the graph that raise and drop one wall carry the same word, and
	 * the difference between them is the one thing that differs.
	 *
	 * <h2>What is actually put there</h2>
	 *
	 * Blocks that stop a body and are not seen. How it <em>looks</em> — a shimmer, a
	 * thicket, a door of light — is a separate question with a separate answer, and
	 * keeping them apart means the wall can be made to work before anybody decides
	 * what it should look like.
	 *
	 * <h2>The half that is dangerous</h2>
	 *
	 * Not this one. Putting a wall up is a handful of blocks; the risk is all in
	 * taking it down, because a wall that outlives its conversation is an invisible
	 * wall in somebody's corridor that nobody can see in order to fix. So what is
	 * raised belongs to the conversation that raised it and comes down with it,
	 * however it ends — see the runtime, which already knows how conversations end.
	 *
	 * @param area a name from the document's own boxes; see {@link Area}
	 * @param up   true to fill it, false to give the passage back
	 */
	record Wall(String area, boolean up) implements Effect { }

	/**
	 * Puts a block somewhere, in a state written the game's own way.
	 *
	 * <h2>Why this is not a wall</h2>
	 *
	 * A wall is a volume raised for the length of a scene and taken down when the scene
	 * ends, and the taking down is most of what it is. This is one block, put there on
	 * purpose, and meant to stay — the fire is out because somebody put it out.
	 *
	 * So there is no sweep and no memory of what was there before, and both of those
	 * are the point rather than a shortcut. A verb that quietly restored the world when
	 * a conversation ended could not put a fire out at all.
	 *
	 * <h2>What it is for</h2>
	 *
	 * The thing vanilla will not let a bare hand do. A campfire needs a flint and steel
	 * to light and a shovel to put out, so "use it to toggle it" is not a rule the game
	 * has — and giving the player that rule is the plainest example there is of the
	 * whole idea: the graph is where a map's own rules live.
	 *
	 * @param mark  where — including {@link Mark#IT}, the block the rule fired about
	 * @param block what to put there, e.g. {@code minecraft:campfire[lit=false]}
	 */
	record PutBlock(String mark, String block) implements Effect { }

	/**
	 * Stands a thing in the air, or changes the one already standing there.
	 *
	 * <h2>Why the same verb puts one up and changes it</h2>
	 *
	 * Because a menu is a thing that changes. Six labels go up when somebody walks in, and
	 * the one under the cursor goes yellow — and if putting up and changing were two verbs,
	 * every graph that does the second would have to know whether it had already done the
	 * first. Told the same name twice, this changes what is standing there rather than
	 * hanging a seventh label through the sixth.
	 *
	 * That makes it idempotent, which is what lets a graph be re-entered. A location laid
	 * out again for the next visitor runs the same nodes and gets the same room, not two
	 * rooms in the same place.
	 *
	 * <h2>What the name is, and how far it reaches</h2>
	 *
	 * A word the author chooses, and the only handle there is: {@link Unshow} takes one
	 * down by it and {@link Node.Pressed} waits on one by it. It reaches as far as the
	 * place the thing is standing in — one lesson's copy, or the map — so two people in two
	 * copies of the same room each have their own {@code menu.play} and neither can see or
	 * change the other's.
	 *
	 * @param name  what this thing is called, within its own place
	 * @param what  the thing itself; see {@link Shown}
	 * @param where the point it stands at, in either of the two forms a place is written in
	 */
	record Show(String name, Shown what, Route.Point where) implements Effect { }

	/**
	 * Takes a shown thing away.
	 *
	 * Named rather than "take away whatever is here", so a menu can be cleared one row at
	 * a time and so a graph cannot delete something another graph put up. A name nothing
	 * answers for takes nothing away and is not an error: the ordinary way to end a scene
	 * is to clear everything it might have shown, including the parts it never reached.
	 */
	record Unshow(String name) implements Effect { }

	/**
	 * Put the player somewhere else, at once.
	 *
	 * <h2>Why this is separate from the one that moves her</h2>
	 *
	 * Because they move different people, and a verb that moved "whoever" would have
	 * to be told which every time — which is a field that is wrong half the time by
	 * construction. Two verbs, each about one subject, and the panel says which.
	 *
	 * <h2>What it is measured from</h2>
	 *
	 * The same two forms every place in this mod is written in: steps from where the
	 * character was placed, or coordinates of this world. A conversation with nobody
	 * in it — one a zone started rather than a click — has no character to measure
	 * from, so a scene like that writes coordinates and the editor says so.
	 */
	record Send(Route.Point where) implements Effect { }

	/**
	 * Puts a picture beside the scene, changes it, or takes it away.
	 *
	 * <h2>Why a verb and not a field on the line</h2>
	 *
	 * Because a portrait outlives the line that raised it. In the game this is taken
	 * from, the figure stands at the edge of the screen for the whole conversation and
	 * changes expression while it goes — it does not appear and vanish with each
	 * sentence. Written as a field of a line, every line of a scene would have to
	 * repeat which picture is still up, and the first one somebody forgot would be a
	 * portrait blinking out mid-sentence.
	 *
	 * So it is state the scene sets, the same shape as a wall: raise it, change it,
	 * drop it. And like a wall, it comes down when the conversation ends whether or
	 * not the author remembered — a picture left standing over somebody's screen is
	 * the same class of fault as a barrier left standing in their corridor.
	 *
	 * <h2>What belongs to the picture and what belongs to the showing</h2>
	 *
	 * The picture is a fingerprint on a shelf of the world. Which side it stands on
	 * and whether it is mirrored are not facts about the picture — the same figure
	 * faces into the scene from either edge — so they are said here, by whoever is
	 * staging it.
	 *
	 * @param picture  the fingerprint of a picture in the world's portrait shelf, or
	 *                 empty to take down whatever is showing
	 * @param side     which edge it stands against
	 * @param mirrored whether it is flipped, so one drawing serves both edges
	 */
	record Portrait(String picture, Side side, boolean mirrored,
			String figure, java.util.Map<String, String> choice) implements Effect {

		/**
		 * Which edge of the screen a portrait stands against.
		 *
		 * Two, and not coordinates. A figure in a scene is on the left or on the right;
		 * an offset in pixels would turn staging a conversation into laying out a page,
		 * and it would be wrong on every screen but the one it was typed on.
		 */
		public enum Side { LEFT, RIGHT }

		/**
		 * The three-part form: one whole picture, which is what this verb first meant.
		 *
		 * Kept because it is still the right answer for a great many pictures. An event
		 * card, a photograph, anything drawn in one piece — there is nothing to assemble,
		 * and making somebody build a one-slot figure to show it would be ceremony.
		 */
		public Portrait(String picture, Side side, boolean mirrored) {
			this(picture, side, mirrored, "", java.util.Map.of());
		}

		public Portrait {
			picture = picture == null ? "" : picture;
			side = side == null ? Side.RIGHT : side;
			figure = figure == null ? "" : figure;
			// Ordered and copied, so that a document saved twice is the same file twice.
			// Map.copyOf loses the order it was written in, and the round-trip test
			// compares files rather than maps — see the note on Dialogue's own nodes,
			// where the same thing was learned.
			choice = java.util.Collections.unmodifiableMap(
				new java.util.LinkedHashMap<>(choice == null ? java.util.Map.of() : choice));
		}

		/** Nothing showing. Empty rather than null, so "none" is a value like any other. */
		public static final Portrait NONE = new Portrait("", Side.RIGHT, false);

		/**
		 * Whether there is anything to put on the screen.
		 *
		 * Either a picture or a figure. Never both — the picker sets one and clears the
		 * other — but the question is asked as "is there anything" rather than "which
		 * one", because that is what every caller actually wants to know.
		 */
		public boolean showing() {
			return !picture.isEmpty() || !figure.isEmpty();
		}

		/** Whether this is a figure to be assembled rather than a picture to be shown. */
		public boolean assembled() {
			return !figure.isEmpty();
		}

		public Portrait wearing(String slot, String part) {
			java.util.Map<String, String> now = new java.util.LinkedHashMap<>(choice);
			if (part == null || part.isEmpty()) now.remove(slot);
			else now.put(slot, part);
			return new Portrait(picture, side, mirrored, figure, now);
		}
	}

	/**
	 * Keep the player still, or let them go again.
	 *
	 * <h2>Why the graph has to be able to say this</h2>
	 *
	 * It could not, and the report is what that cost. Holding was a property of the
	 * document — hold while a line is on screen — so a character who said her piece
	 * and walked off left the line up while she walked, and the player stood rooted
	 * to the spot watching her go. Nothing was broken. The rule was "held while a
	 * line is showing", and a line was showing.
	 *
	 * "A line is on screen" and "this is a moment to be still for" are different
	 * facts that only coincide in the simplest scene, and pulling them apart is what
	 * a verb is for: held from here to there, said by the person writing the scene.
	 *
	 * <h2>What holds it up when the graph forgets</h2>
	 *
	 * Everything, because this is the one thing here that can take the game away from
	 * somebody. The conversation ending hands it back however it ends; nothing holds
	 * anybody past a minute whatever any graph says; and the player can let themselves
	 * out by hand and is told on screen how. See {@code Holding}.
	 *
	 * @param up true to take the movement keys, false to give them back
	 */
	record Hold(boolean up) implements Effect { }

	/**
	 * Be somewhere else, at once, without walking there.
	 *
	 * <h2>Why this is a verb of its own and not "walk there very fast"</h2>
	 *
	 * Because it is a different thing to write and a different thing to watch. What it
	 * is for is the moment a character goes round a corner and is not there any more:
	 * she has to arrive somewhere the player cannot see her get to, and no speed of
	 * walking does that — the walking is exactly what must not be seen.
	 *
	 * <h2>Why a point and not a {@link Mark}</h2>
	 *
	 * Because a mark is a thing — a person, a noise, a named place — and this is a
	 * piece of ground that may be nothing at all: the far side of a wall, a dark
	 * doorway, the middle of a room. It is the same kind of answer a route's points
	 * are, so it is the same kind of value, written the same two ways: steps from
	 * where the character was placed, or coordinates of this world.
	 *
	 * Steps are the ordinary form for the reason they are on a route — the whole
	 * argument is on {@link Route}, and it applies here word for word. A scene built
	 * off to one side of the map and pasted in keeps working.
	 *
	 * <h2>What it does not do</h2>
	 *
	 * Hide anything. Whether the player can see her go is the scene's business, and
	 * the answer is a wall or a corner rather than a flag on this — which is why the
	 * thing that makes it work is the map and not the mod.
	 */
	record Appear(Route.Point where) implements Effect { }

	/**
	 * Carry yourself like somebody expecting a fight, or stop.
	 *
	 * <h2>Why this is a verb and not something java works out</h2>
	 *
	 * Because being on guard is a decision, and java has no business making it.
	 * A character can be armed and relaxed, or unarmed and squaring up; guessing
	 * from the weapon would make both impossible to write.
	 *
	 * What java does supply is <em>how</em> — which stance, which walk, which run,
	 * for whatever is in her hands. That is the same split as everywhere else: the
	 * graph decides what this is, the manner of doing it comes from the weapon, and
	 * either can be overruled.
	 *
	 * It is the other half of the wooden fight. A blow with phases still looks like
	 * nothing if the character between blows is standing the way she stands in a
	 * queue, and walks towards her enemy the way she walks to a shop.
	 */
	record Guard(boolean up) implements Effect { }

	/**
	 * Gives somebody a property they did not have, or takes it back.
	 *
	 * <h2>Why one verb and not one verb per ability</h2>
	 *
	 * Because the game already keeps forty of them and hands them out itself: how high
	 * you jump, how far you can reach, how fast you mine, how hard you land, how big you
	 * are, how much gravity there is. They are applied on the server, the game
	 * synchronises them without being asked, and none of it needs a line of client code.
	 *
	 * So "give the player a property they have not got" — which sounded like a piece of
	 * work per ability — is one verb for nearly all of it. What is left over is the
	 * handful the game has no word for, double jump being the obvious one, and those are
	 * real work each.
	 *
	 * <h2>Why the author names it</h2>
	 *
	 * A modifier has to be taken off by the same handle it went on by. Naming it here
	 * means one scene can grant a boon and a different scene, in a different document,
	 * can take it away — which is what a boon is. Derived from the node it was set at,
	 * that would be impossible, and two nodes granting the same thing would fight.
	 *
	 * The name is also the only thing standing between an author and a modifier they can
	 * never remove: it survives a restart, sits in the player's own data, and nothing
	 * anywhere shows it. Handles are cheap; a permanent invisible effect is not.
	 *
	 * <h2>Why it can be taken off with the amount still filled in</h2>
	 *
	 * Because turning it off is about the handle and not about the number, and a node
	 * that had to be emptied to be switched off is a node that cannot be switched back
	 * on. The same shape as a wall, which knows its box whether it is up or down.
	 *
	 * @param name      the handle, this document's own word for it
	 * @param attribute what the game calls it, e.g. {@code minecraft:jump_strength}
	 * @param amount    how much, read by {@link How}
	 * @param on        false takes it off again
	 * @param whose     the player, or the character being spoken to
	 */
	record Trait(String name, String attribute, How how, double amount, boolean on, Whose whose)
			implements Effect {

		/**
		 * How the number is read, in the game's own three ways.
		 *
		 * All three are offered because they mean genuinely different things and the
		 * game uses all three itself: +2 hearts is not the same wish as "half again as
		 * fast", and neither is "twice everything, including whatever else is helping".
		 * Hiding two of them would mean the day somebody wanted one, there would be
		 * nothing to say but "not supported".
		 */
		public enum How {
			/** Added to the number as it stands. */
			ADD,
			/** A share of the base, added: 0.5 is half again, before anything else. */
			TIMES_BASE,
			/** A share of the total, added last, on top of everything else helping. */
			TIMES_ALL
		}

		/** Who gets it. Both are living things with the same forty properties. */
		public enum Whose { PLAYER, CHARACTER }

		public Trait {
			name = name == null ? "" : name;
			attribute = attribute == null ? "" : attribute;
			how = how == null ? How.ADD : how;
			whose = whose == null ? Whose.PLAYER : whose;
		}

		/** The same handle, put on or taken off. */
		public Trait turned(boolean now) {
			return new Trait(name, attribute, how, amount, now, whose);
		}
	}

	/**
	 * Turn to face a {@link Mark}, and keep facing it.
	 *
	 * Held rather than done once, because looking at somebody is a state and not
	 * an act — a character told to look at you and then left alone should still
	 * be looking at you a second later. {@link Mark#NOTHING} lets go.
	 *
	 * <h2>What this overrules</h2>
	 *
	 * The watching reflex, which turns her head towards whatever she notices. A
	 * graph saying where to look is an author saying it, and an author outranks a
	 * reflex — otherwise a scripted stare would be broken by a door closing
	 * somewhere behind the camera.
	 */
	record LookAt(String mark) implements Effect { }

	/**
	 * Aim at a {@link Mark} and shoot whatever is in her hand.
	 *
	 * She keeps aiming for as long as the weapon takes to ready, rather than
	 * pointing once at the moment of the order: a bow takes a second to draw, and
	 * anything worth shooting at has moved by then.
	 *
	 * Whether there is ammunition, how long the draw is worth, whether the item
	 * can be fired at all — none of that is here. It belongs to the weapon and is
	 * already answered by it; this only says at whom.
	 */
	record Fire(String mark) implements Effect { }

	/**
	 * Swing at a {@link Mark}, and hurt it if it is within reach.
	 *
	 * <h2>Why the swing happens whether or not it lands</h2>
	 *
	 * Because a miss is a thing that happened. A character who only moves her arm
	 * when the blow connects is a character who stands perfectly still while
	 * failing to reach you, which reads as being ignored rather than as being
	 * fought.
	 *
	 * How often to swing is not here. The graph says, with a timer, the same way
	 * it says everything else about rhythm - and the game's own invulnerability
	 * after a hit means a graph that asks too often gets the same result as one
	 * that asks sensibly, rather than a blender.
	 */
	record Strike(String mark) implements Effect { }
}
