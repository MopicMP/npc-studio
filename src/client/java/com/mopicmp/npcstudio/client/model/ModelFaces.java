package com.mopicmp.npcstudio.client.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mopicmp.npcstudio.NpcStudio;
import com.mopicmp.npcstudio.model.Cube;
import com.mopicmp.npcstudio.model.Model;
import com.mopicmp.npcstudio.model.ModelPose;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

/**
 * Draws a model as six faces per box, each wearing a block's texture.
 *
 * <h2>Why the faces are written out rather than left to a model part</h2>
 *
 * Because {@code ModelPart} can only unwrap a box the way vanilla models are
 * unwrapped: the six faces laid out in a cross on one sheet, each getting its
 * own patch. That is right for a character drawn by hand and useless here,
 * where the whole idea is that every face wears the same sixteen-by-sixteen
 * block texture. There is no box UV that says "all six faces, all of it" — so
 * the faces are written out, and each one is given the whole texture.
 *
 * That is also what makes the base version worth having: nobody draws a PNG and
 * nobody lays out a UV map. A mast is a spruce log because somebody said so.
 *
 * <h2>The texture, and where this is rough</h2>
 *
 * A block's texture is taken to be {@code textures/block/<name>.png}. That is
 * true for the blocks people build out of — planks, stone, bricks, concrete,
 * wool, terracotta — and not true for the awkward ones: a log has separate
 * sides and a top, a grass block has three. Those come out as the blank until
 * the picking of a face is a thing that exists, and picking a face is exactly
 * where custom textures start.
 */
public final class ModelFaces {

	/** What a box without a block wears. */
	private static final Identifier BLANK = NpcStudio.id("textures/model/blank.png");

	private ModelFaces() { }

	/**
	 * Submits the whole model, one draw per material.
	 *
	 * Grouped by texture rather than drawn box by box, because a hull of forty
	 * planks boxes is one draw when they are gathered and forty when they are
	 * not, and the gathering is a map lookup each.
	 */
	public static void submit(Model model, PoseStack pose, SubmitNodeCollector collector,
			int light) {
		submit(model, pose, collector, light, Map.of());
	}

	/**
	 * The same, with some bones turned further than the document turns them.
	 *
	 * This is how a ship's wheel turns: the bone is the wheel, the scene says how
	 * far round it is, and the boxes hanging off it come with it. An empty map is
	 * the ordinary case and takes the cached placement, so nothing that is not in
	 * a scene pays anything for this existing.
	 */
	public static void submit(Model model, PoseStack pose, SubmitNodeCollector collector,
			int light, Map<String, float[]> turns) {
		Map<Identifier, List<ModelPose.Placed>> byTexture = new LinkedHashMap<>();
		for (ModelPose.Placed placed : ModelPose.place(model, turns)) {
			byTexture.computeIfAbsent(textureOf(placed.cube().block()), any -> new java.util.ArrayList<>())
				.add(placed);
		}

		for (Map.Entry<Identifier, List<ModelPose.Placed>> group : byTexture.entrySet()) {
			List<ModelPose.Placed> boxes = group.getValue();
			collector.submitCustomGeometry(pose, RenderTypes.entityCutout(group.getKey()),
				(at, buffer) -> {
					for (ModelPose.Placed placed : boxes) faces(placed, at, buffer, light);
				});
		}
	}

	/**
	 * Where a block keeps its picture.
	 *
	 * By convention rather than by asking the model system, which in this version
	 * does not hand out a block's sprite anywhere a renderer can reach. The
	 * convention holds for the blocks this is for; the ones it does not hold for
	 * come out blank, which is visible and therefore reportable.
	 */
	private static Identifier textureOf(String block) {
		if (block == null || block.isEmpty()) return BLANK;
		Identifier id = Identifier.tryParse(block);
		if (id == null) return BLANK;
		return Identifier.fromNamespaceAndPath(id.getNamespace(),
			"textures/block/" + id.getPath() + ".png");
	}

	/** The six faces of one box, each given the whole texture. */
	private static void faces(ModelPose.Placed placed, PoseStack.Pose at, VertexConsumer buffer,
			int light) {
		Cube cube = placed.cube();
		float[] matrix = placed.matrix();
		float grow = cube.inflate();

		float x0 = cube.fromX() - grow;
		float y0 = cube.fromY() - grow;
		float z0 = cube.fromZ() - grow;
		float x1 = cube.toX() + grow;
		float y1 = cube.toY() + grow;
		float z1 = cube.toZ() + grow;

		// Wound so the outside faces out. A face wound the other way is invisible
		// from where anybody is standing and perfectly visible from inside, which
		// reads as a model with holes in it.
		quad(buffer, at, matrix, light, 0, 0, -1,
			x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0);
		quad(buffer, at, matrix, light, 0, 0, 1,
			x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
		quad(buffer, at, matrix, light, -1, 0, 0,
			x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
		quad(buffer, at, matrix, light, 1, 0, 0,
			x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1);
		quad(buffer, at, matrix, light, 0, 1, 0,
			x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0);
		quad(buffer, at, matrix, light, 0, -1, 0,
			x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
	}

	private static void quad(VertexConsumer buffer, PoseStack.Pose at, float[] matrix, int light,
			float nx, float ny, float nz,
			float ax, float ay, float az, float bx, float by, float bz,
			float cx, float cy, float cz, float dx, float dy, float dz) {
		vertex(buffer, at, matrix, light, nx, ny, nz, ax, ay, az, 0, 1);
		vertex(buffer, at, matrix, light, nx, ny, nz, bx, by, bz, 1, 1);
		vertex(buffer, at, matrix, light, nx, ny, nz, cx, cy, cz, 1, 0);
		vertex(buffer, at, matrix, light, nx, ny, nz, dx, dy, dz, 0, 0);
	}

	private static void vertex(VertexConsumer buffer, PoseStack.Pose at, float[] matrix, int light,
			float nx, float ny, float nz, float x, float y, float z, float u, float v) {
		// Through the model's own frame first — that is the bone and box rotation
		// worked out and tested in ModelPose — and then through whatever the world
		// has done to the whole object.
		float[] placed = ModelPose.apply(matrix, x, y, z);
		float[] normal = ModelPose.apply(matrix, nx, ny, nz);
		float[] origin = ModelPose.apply(matrix, 0, 0, 0);

		buffer.addVertex(at, placed[0], placed[1], placed[2])
			.setColor(0xFFFFFFFF)
			.setUv(u, v)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(light)
			.setNormal(at, normal[0] - origin[0], normal[1] - origin[1], normal[2] - origin[2]);
	}
}
