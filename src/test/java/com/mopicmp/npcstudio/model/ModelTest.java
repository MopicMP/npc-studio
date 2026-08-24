package com.mopicmp.npcstudio.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The model, checked without starting the game.
 *
 * This is the part worth testing: it is pure, it is where an editor's mistakes
 * become a file somebody has saved, and it is the one layer of the modelling
 * work that a compiler cannot hold an opinion about.
 */
class ModelTest {

	@Nested
	@DisplayName("the tree")
	class Tree {

		@Test
		@DisplayName("removing a bone takes what hung off it, rather than setting it adrift")
		void removingABoneTakesItsChildren() {
			Model model = Model.empty("ship")
				.plus(Bone.named("arm").under("root"))
				.plus(Bone.named("hand").under("arm"))
				.plus(Bone.named("mast").under("root"));

			Model left = model.without("arm");

			assertNull(left.bone("arm"));
			// The hand is the point. Left behind it would become a root, which does
			// not leave it where it was — it springs to the middle of the model.
			assertNull(left.bone("hand"), "the hand went with the arm");
			assertNotNull(left.bone("mast"), "and nothing else did");
		}

		@Test
		@DisplayName("a bone cannot be hung from its own child")
		void loopsAreRefused() {
			Model model = Model.empty("ship")
				.plus(Bone.named("arm").under("root"))
				.plus(Bone.named("hand").under("arm"));

			assertTrue(model.wouldLoop("arm", "hand"), "that is a circle");
			assertTrue(model.wouldLoop("arm", "arm"), "and so is itself");
			assertFalse(model.wouldLoop("hand", "root"), "this one is ordinary");
		}

		@Test
		@DisplayName("a second bone of the same name is renamed rather than refused")
		void duplicateNamesAreMadeUnique() {
			Model model = Model.empty("ship").plus(Bone.named("mast")).plus(Bone.named("mast"));

			assertEquals(3, model.bones().size());
			assertNotNull(model.bone("mast"));
			assertNotNull(model.bone("mast2"), "two bones with one name make a parent ambiguous");
		}

		@Test
		@DisplayName("children are worked out from the parent each bone names")
		void childrenFollowTheNamedParent() {
			Model model = Model.empty("ship")
				.plus(Bone.named("arm").under("root"))
				.plus(Bone.named("mast").under("root"));

			assertEquals(2, model.childrenOf("root").size());
			assertEquals(1, model.childrenOf("").size(), "root itself hangs from the model");
		}
	}

	@Nested
	@DisplayName("a box")
	class Boxes {

		@Test
		@DisplayName("moving keeps the size and leaves the pivot where it was")
		void movingKeepsSizeAndPivot() {
			Cube cube = new Cube(0, 0, 0, 4, 8, 2, 1, 1, 1, 0, 0, 0, 0, 0, 0, "");

			Cube moved = cube.movedTo(10, 0, 0);

			assertEquals(4, moved.sizeX());
			assertEquals(8, moved.sizeY());
			assertEquals(2, moved.sizeZ());
			// A door dragged along the wall still swings on its hinge.
			assertEquals(1, moved.pivotX());
		}

		@Test
		@DisplayName("a box cannot be given a negative size")
		void sizeStaysPositive() {
			Cube flattened = Cube.unit().sized(-3, 2, 2);

			assertEquals(0, flattened.sizeX(), "corners that cross over are a box inside out");
			assertEquals(2, flattened.sizeY());
		}

		@Test
		@DisplayName("one face can be pulled without the far side following it")
		void pullingOneFaceLeavesTheOther() {
			Cube cube = new Cube(2, 0, 0, 6, 8, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, "");

			// The near face pulled towards the camera: what the arrow that points at
			// you does. The far side must stay, or the box appears to run away from
			// the handle being pulled.
			Cube pulled = cube.corners(cube.fromX() - 3, cube.fromY(), cube.fromZ(),
				cube.toX(), cube.toY(), cube.toZ());

			assertEquals(-1, pulled.fromX());
			assertEquals(6, pulled.toX(), "the far face did not move");
			assertEquals(7, pulled.sizeX());
		}

		@Test
		@DisplayName("a face dragged past the far side turns the box round rather than stopping")
		void aFaceMayCrossOver() {
			Cube cube = new Cube(0, 0, 0, 4, 4, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, "oak");

			Cube crossed = cube.corners(10, 0, 0, 4, 4, 4);

			assertEquals(4, crossed.fromX());
			assertEquals(10, crossed.toX());
			assertEquals("oak", crossed.block(), "the material is not part of the geometry");
		}
	}

	@Nested
	@DisplayName("shapes made of boxes")
	class Shapes {

		@Test
		@DisplayName("a ball is a shell, not a solid lump")
		void aSphereIsHollow() {
			List<Cube> made = Primitives.sphere(8, "oak");

			assertFalse(made.isEmpty());
			// Every box is out at the surface. A solid ball of this size is five
			// hundred boxes and four hundred of them are never seen.
			for (Cube one : made) {
				double away = Math.sqrt(
					Math.pow(one.fromX() + Primitives.CELL / 2, 2)
					+ Math.pow(one.fromY() + Primitives.CELL / 2, 2)
					+ Math.pow(one.fromZ() + Primitives.CELL / 2, 2));
				assertTrue(away > 8 - Primitives.CELL * 1.6,
					"a box well inside the shell is a box nobody will ever see");
				assertTrue(away <= 8.001, "and none of it reaches past the radius asked for");
			}
		}

		@Test
		@DisplayName("a ring lies flat and is open in the middle")
		void aRingIsARing() {
			List<Cube> made = Primitives.ring(8, 2, "oak");

			assertFalse(made.isEmpty());
			boolean anyInTheMiddle = false;
			for (Cube one : made) {
				assertEquals(2, one.sizeY(), "flat is what a rim is");
				double away = Math.hypot(one.fromX() + Primitives.CELL / 2,
					one.fromZ() + Primitives.CELL / 2);
				if (away < 4) anyInTheMiddle = true;
			}
			assertFalse(anyInTheMiddle, "the hole is the point of a ring");
		}

		@Test
		@DisplayName("a cone narrows all the way up and stands on its base")
		void aConeNarrows() {
			List<Cube> made = Primitives.cone(8, 16, "oak");

			float lowest = Float.MAX_VALUE;
			float widestLow = 0;
			float widestHigh = 0;
			for (Cube one : made) {
				lowest = Math.min(lowest, one.fromY());
				float away = (float) Math.hypot(one.fromX(), one.fromZ());
				if (one.fromY() < 2) widestLow = Math.max(widestLow, away);
				if (one.fromY() > 12) widestHigh = Math.max(widestHigh, away);
			}

			assertEquals(0, lowest, "it stands on the origin rather than through it");
			assertTrue(widestHigh < widestLow, "the top is narrower than the bottom");
		}
	}

	@Nested
	@DisplayName("the file")
	class Files {

		@Test
		@DisplayName("a Blockbench project survives being read and written back")
		void blockbenchRoundTrips() {
			Model model = new Model("ship", 128, 64, List.of(
				new Bone("hull", "", 1, 2, 3, 0, 15, 0, List.of(
					new Cube(0, 0, 0, 16, 4, 6, 1, 2, 3, 0, 0, 0, 0.25f, 8, 16, "minecraft:oak_planks"))),
				new Bone("mast", "hull", 8, 4, 3, 0, 0, 0, List.of(
					new Cube(7, 4, 2, 9, 20, 4, 8, 4, 3, 0, 0, 5, 0, 0, 0, "")))));

			Model back = ModelIO.readBlockbench(ModelIO.writeBlockbench(model));

			assertEquals(128, back.textureWidth());
			assertEquals(2, back.bones().size());
			assertEquals(2, back.cubeCount());

			Bone mast = back.bone("mast");
			assertNotNull(mast);
			assertEquals("hull", mast.parent(), "the tree came back as a tree");
			assertEquals(5, mast.cubes().get(0).rotZ());

			Cube hull = back.bone("hull").cubes().get(0);
			assertEquals(16, hull.sizeX());
			assertEquals(0.25f, hull.inflate(), "the one thing that stops two faces fighting");
			assertEquals(8, hull.u());
			// The block a box wears travels in the file, so a model that has been
			// round through Blockbench comes back still made of the right materials.
			assertEquals("minecraft:oak_planks", hull.block());
		}

		@Test
		@DisplayName("a box the tree never mentions is kept rather than quietly lost")
		void looseBoxesSurvive() {
			JsonObject json = JsonParser.parseString("""
				{
				  "name": "odd",
				  "resolution": { "width": 64, "height": 64 },
				  "elements": [
				    { "uuid": "a", "from": [0,0,0], "to": [1,1,1] },
				    { "uuid": "b", "from": [2,0,0], "to": [3,1,1] }
				  ],
				  "outliner": [
				    { "name": "kept", "children": ["a"] }
				  ]
				}
				""").getAsJsonObject();

			Model model = ModelIO.readBlockbench(json);

			assertEquals(2, model.cubeCount(), "the unfiled box is still a box somebody drew");
			assertEquals(1, model.bone("kept").cubes().size());
		}

		@Test
		@DisplayName("a file missing the usual fields reads as a model, not as an error")
		void missingFieldsDegradeGently() {
			JsonObject json = JsonParser.parseString("""
				{ "elements": [ { "uuid": "a", "from": [0,0,0], "to": [2,2,2] } ],
				  "outliner": [ { "name": "bone", "children": ["a"] } ] }
				""").getAsJsonObject();

			Model model = ModelIO.readBlockbench(json);

			assertEquals(Model.DEFAULT_TEXTURE, model.textureWidth());
			// No rotation written means no rotation, which is what three zeroes say.
			assertEquals(0, model.bone("bone").rotY());
			assertEquals(1, model.cubeCount());
		}
	}

	@Nested
	@DisplayName("where the boxes end up")
	class Placing {

		private static final float CLOSE = 0.001f;

		@Test
		@DisplayName("a box in an unturned bone stays exactly where it was drawn")
		void nothingMovesWhenNothingTurns() {
			Model model = new Model("m", 64, 64, List.of(
				new Bone("root", "", 0, 0, 0, 0, 0, 0, List.of(
					new Cube(2, 3, 4, 4, 5, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, "")))));

			List<ModelPose.Placed> placed = ModelPose.place(model);
			float[] at = ModelPose.apply(placed.get(0).matrix(), 2, 3, 4);

			assertEquals(2, at[0], CLOSE);
			assertEquals(3, at[1], CLOSE);
			assertEquals(4, at[2], CLOSE);
		}

		@Test
		@DisplayName("a bone turns its boxes about its own pivot, not about the origin")
		void rotationHappensAtThePivot() {
			// A quarter turn about Y at the pivot. The pivot itself cannot move —
			// that is what makes it the pivot, and leaving out the move back is the
			// mistake that makes a model collapse towards the middle when angled.
			Model model = new Model("m", 64, 64, List.of(
				new Bone("root", "", 8, 0, 8, 0, 90, 0, List.of(
					new Cube(8, 0, 8, 9, 1, 9, 8, 0, 8, 0, 0, 0, 0, 0, 0, "")))));

			float[] matrix = ModelPose.place(model).get(0).matrix();
			float[] pivot = ModelPose.apply(matrix, 8, 0, 8);
			float[] ahead = ModelPose.apply(matrix, 8, 0, 12);

			assertEquals(8, pivot[0], CLOSE, "the pivot is the one point that stays");
			assertEquals(8, pivot[2], CLOSE);
			// Four along Z, turned a quarter about Y, comes out four along X.
			assertEquals(12, ahead[0], CLOSE);
			assertEquals(8, ahead[2], CLOSE);
		}

		@Test
		@DisplayName("a child bone carries its parent's turn as well as its own")
		void rotationsCompose() {
			Model model = new Model("m", 64, 64, List.of(
				new Bone("arm", "", 0, 0, 0, 0, 90, 0, List.of()),
				new Bone("hand", "arm", 0, 0, 0, 0, 90, 0, List.of(
					new Cube(0, 0, 1, 1, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, "")))));

			float[] matrix = ModelPose.place(model).get(0).matrix();
			float[] at = ModelPose.apply(matrix, 0, 0, 1);

			// Two quarter turns the same way is a half turn: one along Z ends up
			// one along minus Z. A hand that only knew its own turn would be at +X.
			assertEquals(0, at[0], CLOSE, "the two turns added rather than replaced");
			assertEquals(-1, at[2], CLOSE);
		}

		@Test
		@DisplayName("a box's own turn happens inside its bone's")
		void cubeRotationIsNested() {
			Model model = new Model("m", 64, 64, List.of(
				new Bone("root", "", 0, 0, 0, 0, 90, 0, List.of(
					new Cube(0, 0, 0, 1, 1, 1, 0, 0, 0, 0, 90, 0, 0, 0, 0, "")))));

			float[] matrix = ModelPose.place(model).get(0).matrix();
			float[] at = ModelPose.apply(matrix, 0, 0, 1);

			assertEquals(0, at[0], CLOSE);
			assertEquals(-1, at[2], CLOSE, "bone and box turns compose, they do not fight");
		}

		@Test
		@DisplayName("a placed box knows which bone and which slot it came from")
		void everyPlacedBoxKnowsWhereItCameFrom() {
			Model model = Model.empty("ship");
			Bone root = model.bone("root");
			model = model.replacing(root.plus(Cube.unit()).plus(Cube.unit()))
				.plus(Bone.named("mast").under("root"));
			model = model.replacing(model.bone("mast").plus(Cube.unit()));

			List<ModelPose.Placed> placed = ModelPose.place(model);

			// A flat list is what a renderer wants and a return address is what a
			// click wants; without it a box can be drawn but never chosen.
			assertEquals(3, placed.size());
			assertEquals("root", placed.get(0).bone());
			assertEquals(0, placed.get(0).index());
			assertEquals(1, placed.get(1).index());
			assertEquals("mast", placed.get(2).bone());
			assertEquals(0, placed.get(2).index(), "the index counts within its own bone");
		}

		@Test
		@DisplayName("the model's bounds cover a turned box's real reach, not its unturned one")
		void boundsCountATurnedBox() {
			// A flat plate, stood on its side by a quarter turn about Z. Unturned it
			// reaches sixteen across and two up; turned, the other way round.
			Cube plate = new Cube(0, 0, 0, 16, 2, 4, 0, 0, 0, 0, 0, 90, 0, 0, 0, "");
			Model model = new Model("m", 64, 64, List.of(
				new Bone("root", "", 0, 0, 0, 0, 0, 0, List.of(plate))));

			float[] box = ModelPose.bounds(model);

			assertEquals(2, box[3] - box[0], CLOSE, "it is two wide once it is on its side");
			assertEquals(16, box[4] - box[1], CLOSE, "and sixteen tall");
		}

		@Test
		@DisplayName("a turn is enclosed once, not twice")
		void turningIsEnclosedOnce() {
			// A plank sixteen long and two wide, and the model turned an eighth of a
			// turn. Enclosing it turned gives the width across the diagonal of the
			// plank; enclosing it upright and turning the result afterwards gives the
			// diagonal of a box that was already sixteen by sixteen — nearly twice as
			// much, out of nowhere, which is what reached past the model.
			Cube plank = new Cube(0, 0, 0, 16, 2, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, "");
			Model model = new Model("m", 64, 64, List.of(
				new Bone("root", "", 0, 0, 0, 0, 0, 0, List.of(plank))));

			float[] turned = ModelPose.bounds(model, 45);
			float straight = 16 * (float) Math.sqrt(2) / 2 + 2 * (float) Math.sqrt(2) / 2;

			assertEquals(straight, turned[3] - turned[0], 0.01f);
			assertEquals(straight, turned[5] - turned[2], 0.01f);
		}

		@Test
		@DisplayName("each box of the model gets its own box, not one round all of them")
		void everyBoxIsItsOwnCollider() {
			// Two boxes with a gap between them: a hull and a mast, in miniature. One
			// box round both would call the air between them solid.
			Model model = new Model("m", 64, 64, List.of(
				new Bone("root", "", 0, 0, 0, 0, 0, 0, List.of(
					new Cube(0, 0, 0, 4, 4, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, ""),
					new Cube(20, 0, 0, 24, 4, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, "")))));

			List<float[]> boxes = ModelPose.boxes(model, 0);

			assertEquals(2, boxes.size());
			assertEquals(4, boxes.get(0)[3], CLOSE);
			assertEquals(20, boxes.get(1)[0], CLOSE, "the second starts where it starts");
			assertEquals(24, ModelPose.bounds(model)[3], CLOSE, "the union still covers both");
		}

		@Test
		@DisplayName("a turned box becomes a staircase; an upright one stays one box")
		void turnedBoxesAreCutUp() {
			Cube plank = new Cube(0, 0, 0, 16, 2, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, "");
			Model straight = new Model("m", 64, 64, List.of(
				new Bone("root", "", 0, 0, 0, 0, 0, 0, List.of(plank))));
			Model turned = new Model("m", 64, 64, List.of(
				new Bone("root", "", 0, 0, 0, 0, 45, 0, List.of(plank))));

			assertEquals(1, ModelPose.boxes(straight, 0).size(),
				"square to the world, the upright box round it is it");

			List<float[]> pieces = ModelPose.boxes(turned, 0);
			assertTrue(pieces.size() > 4, "at an angle it has to be approached in steps");

			// Each step is close to what it stands for: the point of cutting it up is
			// that no single piece claims much air. One box round the whole turned
			// plank would be about thirteen across.
			for (float[] piece : pieces) {
				assertTrue(piece[3] - piece[0] < 4,
					"a piece claims about itself, not the room the whole thing sweeps");
			}
		}

		@Test
		@DisplayName("cutting a turned box up does not change how much room it takes")
		void cuttingKeepsTheOutside() {
			Cube plank = new Cube(0, 0, 0, 16, 2, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, "");
			Model turned = new Model("m", 64, 64, List.of(
				new Bone("root", "", 0, 0, 0, 0, 45, 0, List.of(plank))));

			float[] whole = ModelPose.bounds(turned, 0);
			float across = 16 * (float) Math.sqrt(2) / 2 + 2 * (float) Math.sqrt(2) / 2;

			assertEquals(across, whole[3] - whole[0], 0.01f, "the pieces still cover the corners");
		}

		@Test
		@DisplayName("a box knows its real shape before anybody cuts it up")
		void solidsCarryTheTurn() {
			Cube plank = new Cube(0, 0, 0, 16, 2, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, "");
			Model turned = new Model("m", 64, 64, List.of(
				new Bone("root", "", 0, 0, 0, 0, 45, 0, List.of(plank))));

			List<ModelPose.Solid> solids = ModelPose.solids(turned, 0);

			// One box in, one shape out — the cutting-up is a later step and the real
			// shape is still there for anything that can use it. This is the door that
			// a separating-axis test would come through.
			assertEquals(1, solids.size());
			ModelPose.Solid one = solids.get(0);
			assertEquals(8, one.half()[0], CLOSE, "half its length, whatever angle it is at");
			assertEquals(1, one.half()[1], CLOSE);

			// Its own X now points diagonally, which is the whole of what "turned" means.
			float half = (float) Math.sqrt(2) / 2;
			assertEquals(half, Math.abs(one.axes()[0][0]), 0.01f);
			assertEquals(half, Math.abs(one.axes()[0][2]), 0.01f);
		}

		@Test
		@DisplayName("a model with no boxes has no size, rather than an inside-out one")
		void boundsOfNothing() {
			float[] box = ModelPose.bounds(Model.empty("bare"));

			// Zeroes, not a box from positive infinity to negative infinity. Both
			// callers — how far to keep drawing, and how big a thing is to walk into
			// — want "nothing", and neither should have to check.
			for (float number : box) assertEquals(0, number);
		}

		@Test
		@DisplayName("every box of the model is placed")
		void everyBoxIsPlaced() {
			Model model = new Model("m", 64, 64, List.of(
				new Bone("root", "", 0, 0, 0, 0, 0, 0, List.of(Cube.unit(), Cube.unit())),
				new Bone("child", "root", 0, 0, 0, 0, 0, 0, List.of(Cube.unit()))));

			assertEquals(3, ModelPose.place(model).size());
		}
	}
}
