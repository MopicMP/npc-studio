package com.mopicmp.npcstudio.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That the expressions are distinguishable, restrained, and never break a scene.
 *
 * There is no authority to check a face against — an expression is a judgement —
 * so these ask the two questions that do have answers. Is it the right way
 * round: does anger narrow the eyes and fear widen them, rather than the reverse?
 * And is it small enough: on a face eight pixels tall, "sad" and "unwell" are
 * about a pixel apart.
 */
class ExpressionTest {

	/**
	 * Fear widens the eyes and anger narrows them, not the other way about.
	 *
	 * The one mistake here that would be visible to everybody and obvious to
	 * nobody writing the code: wide eyes are fear, narrow eyes are a glare, and
	 * getting them the wrong way round makes a villain look frightened of the
	 * player it is threatening.
	 */
	@Test
	@DisplayName("anger narrows the pupils and fear widens them")
	void angerAndFearAreTheRightWayRound() {
		assertTrue(Expression.ANGRY.pupils() < 0f,
			"a glare is narrowed eyes: " + Expression.ANGRY.pupils());
		assertTrue(Expression.SCARED.pupils() > 0.5f,
			"fear is wide eyes: " + Expression.SCARED.pupils());
		assertTrue(Expression.SURPRISED.pupils() > 0.5f, "and so is surprise");

		assertTrue(Expression.ANGRY.lids() > 0.2f, "anger brings the lids down");
		assertTrue(Expression.SURPRISED.lids() < 0f, "surprise opens them wider than usual");
	}

	/** Sadness looks down and thinking looks up, which is most of what they are. */
	@Test
	@DisplayName("sadness looks down and thinking looks away upwards")
	void theGazeGoesTheRightWay() {
		assertTrue(Expression.SAD.look() < -0.3f, "sad eyes are lowered");
		assertTrue(Expression.SLEEPY.look() < 0f, "and so are sleepy ones");
		assertTrue(Expression.THINKING.look() > 0.3f, "thinking looks up and away");
	}

	/** Fright blinks fast; grief and anger blink slowly. */
	@Test
	@DisplayName("fear blinks quickly and grief slowly")
	void theBlinkingFitsTheFace() {
		assertTrue(Expression.SCARED.blinks() > 1.2f, "a frightened character blinks fast");
		assertTrue(Expression.SAD.blinks() < 1f, "a grieving one slowly");
		assertTrue(Expression.SURPRISED.blinks() < 0.5f,
			"and a startled one hardly at all — surprise is a face that forgets to blink");
	}

	/**
	 * Every expression is restrained.
	 *
	 * The failure this guards is not a wrong number but an enthusiastic one. An
	 * expression legible in a screenshot is usually far too much in motion, and
	 * there is very little room on this face between an emotion and an illness.
	 */
	@Test
	@DisplayName("no expression takes more than a third of the face's travel")
	void everyExpressionIsRestrained() {
		for (Expression expression : Expression.values()) {
			assertTrue(Math.abs(expression.lids()) <= 0.55f,
				expression + " moves the lids by " + expression.lids());
			assertTrue(Math.abs(expression.pupils()) <= 1f,
				expression + " moves the pupils by " + expression.pupils());
			assertTrue(Math.abs(expression.look()) <= 1f,
				expression + " moves the gaze by " + expression.look());
			assertTrue(expression.blinks() > 0f && expression.blinks() <= 2f,
				expression + " blinks at " + expression.blinks());
			assertTrue(Math.abs(expression.browRaise()) <= 1f,
				expression + " lifts the brows by " + expression.browRaise());
			assertTrue(Math.abs(expression.browSlant()) <= 1f,
				expression + " slants the brows by " + expression.browSlant());
		}
	}

	/**
	 * The brows go the way a person's do.
	 *
	 * The whole of an expression on a face this size, and the easiest thing to get
	 * backwards: surprise lifts, anger lowers, and sadness lifts the <em>inner</em>
	 * ends while anger pulls the same ends down. Inverted, an angry character looks
	 * startled and a grieving one looks cross — both of which are recognisable
	 * faces, so nothing would look broken and everything would be wrong.
	 */
	@Test
	@DisplayName("brows go up in surprise, down in anger, and inward-up in sadness")
	void theBrowsGoTheRightWay() {
		assertTrue(Expression.SURPRISED.browRaise() > 0.5f,
			"surprise is brows straight up: " + Expression.SURPRISED.browRaise());
		assertTrue(Expression.ANGRY.browRaise() < -0.3f,
			"anger brings them down: " + Expression.ANGRY.browRaise());

		assertTrue(Expression.SAD.browSlant() > 0.5f,
			"the inner ends up is what sadness is: " + Expression.SAD.browSlant());
		assertTrue(Expression.ANGRY.browSlant() < -0.3f,
			"and the knot of a frown is the same ends pulled down");

		// Fear is surprise with worry in it: the same lift, but slanted.
		assertTrue(Expression.SCARED.browRaise() > 0.5f && Expression.SCARED.browSlant() > 0.2f,
			"fear lifts the brows and pulls them together");
		assertEquals(0f, Expression.SURPRISED.browSlant(), 1e-5,
			"where plain surprise lifts them level — that is the difference between the two");
	}

	/**
	 * Only doubt and the wink are lopsided.
	 *
	 * A one-sided expression is a strong effect and a rare one. Anger on one side of
	 * a face is not half-anger, it is a twitch.
	 */
	@Test
	@DisplayName("only the raised eyebrow and the wink use one brow")
	void onlyDoubtIsLopsided() {
		for (Expression expression : Expression.values()) {
			boolean lopsided = expression == Expression.THINKING || expression == Expression.WINK;
			assertEquals(lopsided, expression.browOdd(),
				expression + " disagrees about whether it is one-sided");
		}
		assertTrue(Expression.THINKING.browRaise() > 0.3f,
			"and a raised eyebrow has to actually be raised");
	}

	/** No two expressions are the same face under different names. */
	@Test
	@DisplayName("every expression is a different face")
	void noTwoAreAlike() {
		Expression[] all = Expression.values();
		for (int i = 0; i < all.length; i++) {
			for (int j = i + 1; j < all.length; j++) {
				boolean same = all[i].lids() == all[j].lids()
					&& all[i].pupils() == all[j].pupils()
					&& all[i].look() == all[j].look()
					&& all[i].blinks() == all[j].blinks()
					&& all[i].winks() == all[j].winks();
				assertTrue(!same, all[i] + " and " + all[j] + " are the same face");
			}
		}
	}

	/** Doing nothing is a real answer, and it is the default. */
	@Test
	@DisplayName("neutral changes nothing about a face")
	void neutralIsNothing() {
		assertEquals(0f, Expression.NEUTRAL.lids(), 1e-5);
		assertEquals(0f, Expression.NEUTRAL.pupils(), 1e-5);
		assertEquals(0f, Expression.NEUTRAL.look(), 1e-5);
		assertEquals(1f, Expression.NEUTRAL.blinks(), 1e-5);
		assertEquals(0f, Expression.NEUTRAL.browRaise(), 1e-5);
		assertEquals(0f, Expression.NEUTRAL.browSlant(), 1e-5);
		assertTrue(!Expression.NEUTRAL.shows());
		assertTrue(Expression.ANGRY.shows());
	}

	/**
	 * A name nobody recognises is an ordinary face, not a broken conversation.
	 *
	 * A dialogue written against a later version of the mod, or simply with a typo
	 * in it, must carry on. The cost of the wrong face is nothing; the cost of a
	 * conversation that stops is the whole scene.
	 */
	@Test
	@DisplayName("an unknown name is an ordinary face rather than an error")
	void unknownNamesAreHarmless() {
		assertSame(Expression.NEUTRAL, Expression.named("smouldering"));
		assertSame(Expression.NEUTRAL, Expression.named(""));
		assertSame(Expression.NEUTRAL, Expression.named(null));
		assertSame(Expression.NEUTRAL, Expression.named("   "));
	}

	/** And a name that is recognised is recognised however it was typed. */
	@Test
	@DisplayName("names are read as an author would write them")
	void namesAreForgiving() {
		assertSame(Expression.ANGRY, Expression.named("angry"));
		assertSame(Expression.ANGRY, Expression.named("ANGRY"));
		assertSame(Expression.ANGRY, Expression.named(" Angry "));
		for (Expression expression : Expression.values()) {
			assertSame(expression, Expression.named(expression.name()));
		}
	}

	/** The wink is the only one that is a movement rather than a face. */
	@Test
	@DisplayName("only the wink is a movement rather than a face held")
	void onlyTheWinkWinks() {
		for (Expression expression : Expression.values()) {
			assertEquals(expression == Expression.WINK, expression.winks(),
				expression + " disagrees about whether it is a wink");
		}
	}
}
