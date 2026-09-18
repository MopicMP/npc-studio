package com.mopicmp.npcstudio.dialogue;

import java.util.List;

import com.mopicmp.npcstudio.dialogue.text.Words;

/**
 * One step of a graph.
 *
 * The split is by what the engine has to do, not by what the writer is saying.
 * Some kinds stop and wait; the rest run through in one go. That difference is
 * the whole reason the engine can promise a graph never hangs: a cycle is only
 * fatal if it contains no node that waits, and that is a property of the graph
 * the validator can check.
 *
 * <h2>Four of these were never about talking</h2>
 *
 * `Set`, `Branch`, `Act` and `End` are assignment, branching, doing and
 * returning. They are a programming language that happened to be written for
 * conversations, and calling the file a dialogue engine hid that for a while.
 * `Line` and `Choice` are the two that are really about a conversation, and what
 * they have in common with the rest is only that they wait.
 *
 * So what turns this into a language for behaviour is not new machinery. It is
 * admitting that <em>waiting for the player</em> is one reason to wait among
 * several — see {@link Every} and {@link Until}.
 */
public sealed interface Node {

	String id();

	/**
	 * True when the engine stops here rather than running on.
	 *
	 * Was called {@code waitsForPlayer}, which was true of every node that
	 * answered yes right up until it was not. The validator relies on this to
	 * tell a loop that is a conversation going round from a loop that is a
	 * freeze, and that reasoning holds whatever the graph is waiting for.
	 */
	boolean waits();

	/**
	 * A spoken line.
	 *
	 * @param speaker whose name and portrait are shown; empty means the NPC itself
	 * @param text    the line, in stretches that may be drawn differently
	 * @param mode    how it is shown; see {@link Presentation}
	 * @param animation animation to play on the NPC, or null
	 * @param next    the node after this one
	 */
	/**
	 * @param lasts how long to leave this line up, in ticks, or nought to let the
	 *              document decide. See {@link #lasts()}.
	 */
	record Line(String id, String speaker, Words text, Presentation mode,
			String animation, String next, int lasts, Face face, String nameColour)
			implements Node {

		/**
		 * The six-part form, which is every line written before a line could say this.
		 */
		public Line(String id, String speaker, Words text, Presentation mode,
				String animation, String next) {
			this(id, speaker, text, mode, animation, next, USES_DOCUMENT);
		}

		/** The seven-part form, from before a line could say whose face is beside it. */
		public Line(String id, String speaker, Words text, Presentation mode,
				String animation, String next, int lasts) {
			this(id, speaker, text, mode, animation, next, lasts, Face.SPEAKER, "");
		}

		/**
		 * Whose head is drawn beside the line.
		 *
		 * <h2>Why this is not read off the name</h2>
		 *
		 * Because a name is a word somebody typed and a face is an entity in the world,
		 * and the moment they were the same field there was no way to write a line the
		 * player speaks. Every line so far shows the character being talked to, and it
		 * has to go on doing that with nothing said — which is what {@code SPEAKER}
		 * means and why it is the default.
		 */
		public enum Face {
			/** The character this conversation is with, which is nobody in a zone. */
			SPEAKER,
			/** The player being spoken to, wearing whatever skin they are wearing. */
			PLAYER,
			/** No face at all: a voice from off, a narrator, a room. */
			NONE
		}

		/**
		 * What colour the name is drawn in, or empty to let the document decide.
		 *
		 * The same shape as {@link #lasts}, and for the same reason: every line written
		 * so far says nothing about this, and saying nothing has to go on meaning "the
		 * usual" rather than becoming "black". The document's own answer is in
		 * {@code Dialogue.voices}, keyed by the name — so a character is one colour
		 * everywhere without it being typed on every line, and a line that wants to
		 * whisper can still overrule it here.
		 */
		public String nameColour() {
			return nameColour == null ? "" : nameColour;
		}

		/**
		 * How long this line stays up before it takes itself away.
		 *
		 * <h2>Why one line ever wants its own answer</h2>
		 *
		 * Because the last line before something happens is a different kind of line.
		 * "Follow me" is not waiting to be read and clicked past; it is waiting for the
		 * scene to move, and the scene moves whether or not anybody clicks. Left to the
		 * document's rule — go away after ten seconds of nobody touching it, or never —
		 * it either vanishes mid-walk or sits on the screen for the rest of the scene.
		 * Reported as the second: the line never left.
		 *
		 * <h2>Why nought means "the document decides"</h2>
		 *
		 * Because that is what every line written so far means, and it has to go on
		 * meaning it. A line that says nothing about this is not asking for anything;
		 * it is not asking.
		 */
		public static final int USES_DOCUMENT = 0;

		public Line {
			lasts = Math.max(USES_DOCUMENT, lasts);
			face = face == null ? Face.SPEAKER : face;
			nameColour = nameColour == null ? "" : nameColour;
		}

		/**
		 * A line nobody has decorated, which is most of them.
		 *
		 * Here so that writing one stays as short as it was — {@code new Line(id,
		 * "", "Привет.", …)} — and so that the several dozen places that build a
		 * plain line did not all have to learn about {@link Words} on the day
		 * colour arrived.
		 */
		public Line(String id, String speaker, String text, Presentation mode,
				String animation, String next) {
			this(id, speaker, Words.of(text), mode, animation, next);
		}

		/**
		 * One field changed and the other eight carried over.
		 *
		 * <h2>Why these exist rather than the constructor being called each time</h2>
		 *
		 * Because a line has nine parts now and the editor changes them one at a time,
		 * from eleven places. Every one of those places wrote out the whole
		 * constructor — and several wrote out the <em>short</em> one, the form kept for
		 * reading old files, which quietly resets how long the line lasts. So renaming
		 * a node, or dragging a wire out of it, or opening its text window and saving,
		 * each threw away a setting somebody had chosen, with nothing anywhere saying
		 * so.
		 *
		 * That is not carelessness; it is a mistake the shape invited, eleven times,
		 * and it would be invited again on the tenth field. So the shape changed.
		 */
		public Line withSpeaker(String now) {
			return new Line(id, now, text, mode, animation, next, lasts, face, nameColour);
		}

		public Line withText(Words now) {
			return new Line(id, speaker, now, mode, animation, next, lasts, face, nameColour);
		}

		public Line withMode(Presentation now) {
			return new Line(id, speaker, text, now, animation, next, lasts, face, nameColour);
		}

		public Line withAnimation(String now) {
			return new Line(id, speaker, text, mode, now, next, lasts, face, nameColour);
		}

		public Line withNext(String now) {
			return new Line(id, speaker, text, mode, animation, now, lasts, face, nameColour);
		}

		public Line withLasts(int now) {
			return new Line(id, speaker, text, mode, animation, next, now, face, nameColour);
		}

		public Line withFace(Face now) {
			return new Line(id, speaker, text, mode, animation, next, lasts, now, nameColour);
		}

		public Line withNameColour(String now) {
			return new Line(id, speaker, text, mode, animation, next, lasts, face, now);
		}

		public Line withId(String now) {
			return new Line(now, speaker, text, mode, animation, next, lasts, face, nameColour);
		}

		@Override public boolean waits() { return true; }
	}

	/**
	 * A question with options.
	 *
	 * Nothing moves until the player picks one — that is what makes Esc a
	 * bookmark rather than a way to skip past a decision.
	 */
	record Choice(String id, String speaker, Words prompt, Presentation mode,
			List<Option> options) implements Node {

		/**
		 * A question is never staged full screen, whatever it was written as.
		 *
		 * <h2>Why a staging is taken away rather than left to be chosen</h2>
		 *
		 * Because the one it named was a card in the top-left corner, and it was
		 * reported twice: a question appeared there, escape took it away, and the bar
		 * picked the same question straight back up — one moment, two dialogues, and
		 * the corner one is the one nobody wanted.
		 *
		 * The bar can hold a question properly now. It could not when the corner card
		 * was written — there was no way to pick an answer without opening chat first —
		 * and that, not the layout, was the whole argument for taking the screen. The
		 * answers are number keys today.
		 *
		 * <h2>Why here and not at the drawing</h2>
		 *
		 * Because a setting whose value is quietly ignored somewhere else is the worst
		 * of the three options: the panel would go on saying "full screen" about a
		 * question shown in the bar. Rewritten on the way in, the document says what
		 * happens, the panel shows what the document says, and the drawing does what the
		 * panel shows. It costs one field of one node, in the one case where the value
		 * was never honoured again.
		 *
		 * A question inside a cutscene is untouched: there the screen is already taken
		 * and there is no bar to put it in, which is the card's remaining job.
		 */
		public Choice {
			if (mode == Presentation.FULLSCREEN) mode = Presentation.SUBTITLE;
		}

		/** A question nobody has decorated. See {@link Line#Line}. */
		public Choice(String id, String speaker, String prompt, Presentation mode,
				List<Option> options) {
			this(id, speaker, Words.of(prompt), mode, options);
		}

		/** Without a speaker named, a question is asked by nobody in particular. */
		public Choice(String id, String prompt, Presentation mode, List<Option> options) {
			this(id, "", prompt, mode, options);
		}
		/**
		 * A question defaults to the bar.
		 *
		 * <h2>Why this changed</h2>
		 *
		 * It used to take over the screen, and the argument was that being asked
		 * something is a stop: you have to read the answers and pick one, and doing
		 * that from a strip above the hotbar while the world carries on is the harder
		 * version of the same moment.
		 *
		 * The premise was true when it was written and is not now. Picking from the bar
		 * was harder because there was no way to pick at all — no cursor without a
		 * screen, and the answers could only be reached by opening the chat window
		 * first. It is a number key now, which is easier than aiming at a small
		 * rectangle, and the number is printed beside each answer.
		 *
		 * What is left is the ordinary preference: a question in the bar leaves the
		 * world where it is, and taking the screen away is a thing to do on purpose.
		 * A writer who wants the card says so, and the card is still there.
		 */
		public Choice(String id, String prompt, List<Option> options) {
			this(id, "", prompt, Presentation.SUBTITLE, options);
		}

		@Override public boolean waits() { return true; }
	}

	/**
	 * Writes a variable, then moves on.
	 *
	 * <h2>Why it can add as well as put</h2>
	 *
	 * Because a great many quests are counting, and until now the language could not
	 * count at all: this node wrote a literal, so {@code n = n + 1} was not expressible
	 * anywhere. "Light the fire three times" needed three separate flags and three
	 * separate branches, and so did every other task shaped like it.
	 *
	 * One field rather than a second kind of node. Putting and adding are the same act
	 * on the same variable in the same place, and telling them apart by which node was
	 * dragged out of the menu would have meant two nodes that look alike and read
	 * alike, one of which quietly cannot count.
	 */
	record Set(String id, String variable, Scope scope, Value value, Change how, String next)
			implements Node {

		/** What the value does to what is already there. */
		public enum Change {
			/** Whatever was there is gone. The only thing this node could do before. */
			PUT,
			/**
			 * Added to what is there — which is only meaningful for numbers.
			 *
			 * Text is not joined and flags are not counted. Both are things somebody
			 * could mean, and both are things somebody could mean differently, so
			 * neither is guessed at: the validator refuses adding to anything that is
			 * not declared a number, and the engine leaves it alone if one gets past.
			 */
			ADD
		}

		/** The old five-part form, which every file and every graph written so far uses. */
		public Set(String id, String variable, Scope scope, Value value, String next) {
			this(id, variable, scope, value, Change.PUT, next);
		}

		@Override public boolean waits() { return false; }
	}

	/**
	 * Puts back what this document remembers, so the scene can be played again.
	 *
	 * <h2>Why this is not just a row of `set` nodes</h2>
	 *
	 * Half of it is, and that half was always possible: writing false over a flag is a
	 * reset of that flag. What no number of `set` nodes can do is forget where the
	 * player has <em>been</em> — and "has this node been visited" is a condition the
	 * language has, so a scene reset without it is a scene that replays with half its
	 * branches already decided. Asked for as replaying a dialogue from the start; that
	 * is the half that was missing.
	 *
	 * The other reason is upkeep. An errand with three flags resets in three nodes
	 * today and in four next week, and the fourth is the one somebody forgets — the
	 * fault then being a scene that mostly replays, which is far harder to see than
	 * one that does not replay at all.
	 *
	 * <h2>Why "everything" is a field and not an empty list</h2>
	 *
	 * It was the empty list, briefly, and the test written to describe that caught what
	 * it costs: unticking the last name in the panel would have flipped the node from
	 * "these two" to "all of them" without a word. A meaning that changes because a
	 * list happened to empty is exactly the quiet rewrite this project keeps meeting.
	 *
	 * Told apart, the list also survives the switch: turn "everything" on to try it,
	 * turn it back off, and the names that were ticked are still ticked.
	 *
	 * "Everything this document declares" is the wanted default, and it stays a
	 * standing instruction rather than a list — a list spelled out is a list that goes
	 * stale the moment a variable is added, and the one added last is the one that then
	 * fails to reset.
	 *
	 * <h2>What it deliberately cannot reach</h2>
	 *
	 * What a character knows about herself. That is kept on her rather than in the
	 * world's book, twenty guards sharing one script each keep their own half of it,
	 * and a node that wiped it would wipe it for whichever one happened to be talking
	 * — which is not something anybody could have meant.
	 */
	record Forget(String id, boolean everything, List<String> variables, Scope scope,
			boolean visited, String next) implements Node {

		public Forget {
			variables = List.copyOf(variables);
		}

		/** A fresh one: everything this document remembers, including where it has been. */
		public Forget(String id, String next) {
			this(id, true, List.of(), Scope.PLAYER, true, next);
		}

		/** The names it actually puts back, which is nothing at all when it names none. */
		public List<String> naming() {
			return everything ? List.of() : variables;
		}

		@Override public boolean waits() { return false; }
	}

	/** Takes the first branch whose condition holds, otherwise `otherwise`. */
	record Branch(String id, List<Arm> arms, String otherwise) implements Node {
		@Override public boolean waits() { return false; }
	}

	/**
	 * A fork decided by a throw rather than by a question.
	 *
	 * <h2>Why a node of its own and not four texts in one line</h2>
	 *
	 * Asked for as "this character says one of four lines" — which four texts in a
	 * line node would answer, and answer only that. A fork answers it as well and
	 * answers everything else shaped like it: which of three idle animations she
	 * plays, which of two ambushes the corridor springs, which greeting a guard uses
	 * this time. Randomness is not a property of speech; it is a way of choosing, and
	 * the thing that chooses in this language is a node with several ways out.
	 *
	 * The price is honest and worth naming: four lines is five nodes rather than one.
	 *
	 * <h2>Why the ways are equally likely</h2>
	 *
	 * Because weights are a second thing to get wrong and nobody has wanted them yet.
	 * A writer who needs "rarely" can put the rare way behind a second throw, which
	 * costs a node and reads correctly — and if that turns out to be the common case
	 * the weights can be added here without moving anything, since a list of ways is
	 * what a list of weighted ways starts as.
	 *
	 * @param ways where it may go, at least one; empty is caught by the validator
	 */
	record Chance(String id, List<String> ways) implements Node {

		/** As many ways as one throw may have. Past this it is a table, not a fork. */
		public static final int MOST = 16;

		public Chance {
			ways = List.copyOf(ways);
		}

		/**
		 * Which way this throw goes.
		 *
		 * Told the throw rather than making one, so that the engine stays a function of
		 * what it was handed — see {@link Condition.World#roll}.
		 */
		public String chosen(int roll) {
			if (ways.isEmpty()) return null;
			return ways.get(Math.floorMod(roll, ways.size()));
		}

		@Override public boolean waits() { return false; }
	}

	/** Does something to the world, then moves on. */
	record Act(String id, Effect effect, String next) implements Node {
		@Override public boolean waits() { return false; }
	}

	/**
	 * The conversation is over.
	 *
	 * @param homing what she does with herself afterwards; see {@link Homing}
	 */
	record End(String id, Homing homing) implements Node {

		/** An ending nobody has said anything about, which is most of them. */
		public End(String id) {
			this(id, Homing.STAY);
		}

		@Override public boolean waits() { return false; }
	}

	/**
	 * What a character does when her graph has nothing left to say.
	 *
	 * <h2>Why this hangs on the ending rather than on the character</h2>
	 *
	 * Because it is a fact about the scene, not about the body. The same guard,
	 * placed twice, might be sent back to her post at the end of one errand and left
	 * where she stands at the end of another — and a tick-box on the character could
	 * only say one thing for both.
	 *
	 * It is also visible where it happens. A character who walks off at the end of a
	 * conversation for reasons written in a settings window somewhere is a character
	 * whose behaviour cannot be read off the graph, which is the whole thing the
	 * graph is for.
	 *
	 * <h2>Why standing still is the default</h2>
	 *
	 * Every graph written before this existed ends by standing there, and quietly
	 * teaching all of them to walk away would change scenes nobody touched.
	 */
	enum Homing {
		/** Stays where she is. */
		STAY,
		/**
		 * Walks back to where she was placed, and stands the way she was placed.
		 *
		 * The facing matters as much as the place. A character who walks home and
		 * stops with her nose against the wall she happened to approach is the
		 * complaint this was written for; going home means standing there as she did.
		 */
		WALK,
		/**
		 * Is simply there again.
		 *
		 * For a scene being reset rather than played — a rehearsal, a demonstration,
		 * anything watched more than once — where the walk back is a minute nobody
		 * wanted to watch.
		 */
		TELEPORT
	}

	/**
	 * Stands here for a while, then moves on.
	 *
	 * The timer, and the reason a behaviour graph does not need a loop construct:
	 * a graph that ends by leading back to its own start is a character who goes
	 * on living, and this is what stops that from being a frozen server. You
	 * decided the occasion belongs to whoever places the node, and this is the
	 * cheapest occasion there is.
	 *
	 * @param ticks how long to stand here; nought means until the next tick
	 */
	record Every(String id, int ticks, String next) implements Node {
		@Override public boolean waits() { return true; }
	}

	/**
	 * Stands here until something is true.
	 *
	 * <h2>Why there is no event node, and no event bus</h2>
	 *
	 * Because an event is a question you ask often. A graph that waits for a
	 * player to come within ten blocks and one that subscribes to a
	 * somebody-came-close event do the same thing, except that the second needs
	 * an event to be invented, published, subscribed and — the part that actually
	 * bites — unsubscribed when the character dies mid-wait.
	 *
	 * The cost is that the condition is tested every tick. That cost is visible
	 * in the graph, which is exactly where you wanted it: a character who watches
	 * for something expensive is a character somebody can see watching for
	 * something expensive.
	 */
	record Until(String id, Condition condition, String next) implements Node {
		@Override public boolean waits() { return true; }
	}

	/**
	 * Stands here until somebody presses one of the things this graph has shown.
	 *
	 * <h2>Why waiting and branching are one node</h2>
	 *
	 * Because they are one act. A menu of six labels written as a wait and then a fork
	 * would be a wait that has to record which label it heard, a variable to record it in,
	 * a fork of six arms comparing that variable, and a name for the variable that means
	 * nothing to anybody. The same argument {@link Walk} makes about the two nodes it
	 * replaces: what is saved is not typing, it is a piece of bookkeeping the author would
	 * have had to invent and keep in step.
	 *
	 * It is also what makes the menu readable on the canvas. Six wires leave this box, one
	 * per label, each named after the thing that is pressed to take it.
	 *
	 * <h2>Why a press is not a trigger</h2>
	 *
	 * A trigger starts a graph. This is a graph that is already running and is standing
	 * still on purpose — which is the difference between "clicking that opens the shop"
	 * and "the shop is open and waiting for you to choose". A menu is the second, and
	 * written as triggers it would be one document per button, each of them knowing where
	 * the menu was in the middle of being.
	 *
	 * <h2>What happens to a press nothing here is waiting for</h2>
	 *
	 * Nothing, and the graph goes on waiting. A press is delivered to every graph the
	 * presser has running, because a person may be standing in a room with two of them,
	 * and the one that is not listening for that name must not be moved by it.
	 *
	 * @param presses what is waited on, in the order they were added
	 */
	record Pressed(String id, List<Press> presses) implements Node {

		@Override public boolean waits() { return true; }

		/** Where a press of this name leads, or null when this node is not waiting on it. */
		public String next(String shown) {
			for (Press press : presses) {
				if (press.shown().equals(shown)) return press.next();
			}
			return null;
		}
	}

	/**
	 * Walks a path somebody drew on the map, and waits until it is walked.
	 *
	 * <h2>Why this is one node and not the two it replaces</h2>
	 *
	 * A route could already be written: order a walk, wait for it to end, order the
	 * next. Two nodes and a wire per point, and the wires cross, and a route of a
	 * dozen points is two dozen boxes that say almost nothing between them.
	 *
	 * It was also subtly wrong, and the fault is worth writing down because it is
	 * the reason this waits for itself rather than for {@link Sense#WALKING}. Effects
	 * are carried out <em>after</em> a step finishes, so an {@code act} that orders a
	 * walk followed by an {@code until} that waits for walking to stop runs both in
	 * one step: the order has not reached the legs yet, she is not walking, and the
	 * wait is over before it began. Written the obvious way it simply fell through
	 * every point of the route in one tick and left her standing at the last one.
	 *
	 * <h2>How it waits without remembering anything</h2>
	 *
	 * The bookmark has nowhere to keep a "which point am I on", and this deliberately
	 * does not add one — see {@link Every}, which moves the bookmark past itself to
	 * avoid the same thing. The count lives where the walking does, on the character,
	 * and the node asks two questions about it: is she walking this order, and has she
	 * finished it. Three answers come out of two questions, which is exactly enough:
	 * not started, going, arrived.
	 *
	 * That makes the node idempotent, which matters more than it sounds. A character
	 * knocked off her patrol by something else finds the order missing on the next
	 * tick and is sent along it again — she goes back to her round rather than
	 * standing where she was interrupted.
	 *
	 * @param route where she goes and how; see {@link Route}
	 */
	record Walk(String id, Route route, String next) implements Node {
		@Override public boolean waits() { return true; }
	}

	/**
	 * Sets a segment of the brain running, and carries straight on.
	 *
	 * <h2>Why calling does not wait</h2>
	 *
	 * Because a conversation that starts a fight has to be able to end while the
	 * fight goes on. Under a call that waited, the last line would be spoken only
	 * once somebody had won, which is not a thing anybody would ever write on
	 * purpose.
	 *
	 * So a segment runs alongside whatever called it. The caller keeps its own
	 * turn every tick and can stop the segment whenever it likes — see
	 * {@link Stop} — which is the same bargain as {@link Effect.WalkTo}: an order
	 * goes out, and watching for the end of it is the caller's own business.
	 *
	 * <h2>What the segment is told</h2>
	 *
	 * A {@link Mark} to act on, which it reads back as {@link Mark#TARGET}, and
	 * any number of named values, which it reads in {@link Scope#GIVEN}. That is
	 * what makes one "how to fight" serve a duel, a brawl and a guard post: the
	 * skill is the same and the circumstances are handed in.
	 */
	record Do(String id, String segment, String target, java.util.Map<String, Value> with,
			String next) implements Node {

		public Do(String id, String segment, String target, String next) {
			this(id, segment, target, java.util.Map.of(), next);
		}

		@Override public boolean waits() { return false; }
	}

	/**
	 * Stops a running segment, if that is the one running.
	 *
	 * Named rather than "stop whatever is going on", so that a scenario cannot
	 * accidentally cancel something it did not start.
	 */
	record Stop(String id, String segment, String next) implements Node {
		@Override public boolean waits() { return false; }
	}

	/**
	 * Writing on the canvas, about one node, or several, or none.
	 *
	 * <h2>Why this is a node after all</h2>
	 *
	 * It was twice not one — kept as text hanging off a node id, on the argument that a
	 * node is a place the conversation can be and a comment is never anywhere. The
	 * argument was sound and it was answering the wrong question.
	 *
	 * What was actually being asked for is everything an editor gives a node: drag it
	 * where you want it, click it to select it, add it from the same menu as everything
	 * else, have <b>several</b> of them about one node, have <b>one</b> of them about
	 * two nodes. Every one of those comes free from being a node and would have had to
	 * be built again, differently, for a thing that was not. Reported three times before
	 * it landed, which is two more than it should have taken.
	 *
	 * The purity that was being defended costs one arm in each switch over the kinds of
	 * node. That is the whole price, and it is smaller than the second selection
	 * mechanism the other way needed.
	 *
	 * <h2>What keeps it out of the conversation</h2>
	 *
	 * It has no way out — {@link #exits} gives nothing — so nothing can flow through it,
	 * and the validator knows not to ask it to reach an ending or to be reachable
	 * itself. A graph is the same graph with every comment deleted, which is the one
	 * property a comment must never lose.
	 *
	 * @param about the nodes it is written about. Lines are drawn to each. Empty is a
	 *              note about the graph as a whole, which is a real thing to want and
	 *              is why this is a list rather than one id.
	 */
	record Comment(String id, String text, int wide, int rows, List<String> about)
			implements Node {

		/** As long as the writing may be. See {@link #wide} for what makes this the bound. */
		public static final int LONGEST = 1024;

		/** The width of an ordinary node box, which is what one card across reads as. */
		public static final int ONE_BOX = 118;

		public static final int NARROWEST = ONE_BOX / 2;
		public static final int WIDEST = ONE_BOX * 4;
		public static final int FEWEST_ROWS = 1;
		public static final int MOST_ROWS = 16;

		/** A fresh one: one box across, three lines down, about nothing yet. */
		public Comment(String id) {
			this(id, "", ONE_BOX, 3, List.of());
		}

		public Comment {
			text = text == null ? "" : text;
			if (text.length() > LONGEST) text = text.substring(0, LONGEST);
			// Brought inside rather than refused. These arrive from a file somebody else
			// wrote, and a card the size of the canvas or of nothing is a number to
			// correct, not a document to throw out.
			wide = Math.clamp(wide, NARROWEST, WIDEST);
			rows = Math.clamp(rows, FEWEST_ROWS, MOST_ROWS);
			about = List.copyOf(about == null ? List.of() : about);
		}

		public Comment reworded(String now) {
			return new Comment(id, now, wide, rows, about);
		}

		public Comment sized(int nowWide, int nowRows) {
			return new Comment(id, text, nowWide, nowRows, about);
		}

		/** Adds or removes a node this is about; naming one twice does not double it. */
		public Comment about(String node, boolean yes) {
			var now = new java.util.ArrayList<>(about);
			now.remove(node);
			if (yes) now.add(node);
			return new Comment(id, text, wide, rows, now);
		}

		@Override public boolean waits() { return false; }
	}

	/**
	 * One option of a {@link Choice}.
	 *
	 * <p>The {@code colour} beside the decorated label is not a second way of
	 * saying the same thing. It colours the answer <em>as a button</em> — the
	 * whole row the player moves over and clicks — while a look inside the label
	 * colours the words. An answer can be a red button with one word in it picked
	 * out white.
	 */
	record Option(Words label, String colour, Condition condition, String next) {

		public Option(String label, String colour, Condition condition, String next) {
			this(Words.of(label), colour, condition, next);
		}

		public Option(String label, String next) {
			this(Words.of(label), null, new Condition.Always(), next);
		}

		public Option(Words label, String next) {
			this(label, null, new Condition.Always(), next);
		}
	}

	/** One arm of a {@link Branch}. */
	record Arm(Condition condition, String next) { }

	/** One thing a {@link Pressed} is waiting to be pressed, and where that leads. */
	record Press(String shown, String next) { }

	/** Every node this one can lead to. */
	default List<String> exits() {
		return switch (this) {
			case Line line -> List.of(line.next());
			case Choice choice -> choice.options().stream().map(Option::next).toList();
			case Set set -> List.of(set.next());
			case Forget forget -> List.of(forget.next());
			case Act act -> List.of(act.next());
			case Branch branch -> {
				var out = new java.util.ArrayList<String>(branch.arms().stream().map(Arm::next).toList());
				out.add(branch.otherwise());
				yield List.copyOf(out);
			}
			case Chance chance -> chance.ways();
			case Walk walk -> List.of(walk.next());
			case Do doing -> List.of(doing.next());
			case Stop stop -> List.of(stop.next());
			case Every every -> List.of(every.next());
			case Until until -> List.of(until.next());
			case Pressed pressed -> pressed.presses().stream().map(Press::next).toList();
			case End _ -> List.of();
			// Nothing, and that is what keeps a comment out of the conversation: with no
			// way out nothing can flow through it, and a graph is the same graph with
			// every comment deleted.
			case Comment _ -> List.of();
		};
	}

	/** Whether this node is writing about the graph rather than part of it. */
	default boolean isWriting() {
		return this instanceof Comment;
	}
}
