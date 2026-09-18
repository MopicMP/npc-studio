package com.mopicmp.npcstudio.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mojang.serialization.JsonOps;
import com.mopicmp.npcstudio.dialogue.codec.DialogueCodecs;

/**
 * Writing on the canvas, which is a node and is not part of the conversation.
 *
 * <h2>The one property it must never lose</h2>
 *
 * A graph is the same graph with every comment deleted. Everything here is a way of
 * asking that: nothing flows through one, nothing complains about one, and the engine
 * cannot arrive at one by any route a conversation takes.
 *
 * It was twice not a node — kept as text hanging off a node id — on the argument that a
 * node is a place the conversation can be. The argument was sound and it was answering
 * the wrong question: what was wanted was dragging, selecting, several about one node
 * and one about two, and every one of those is free here and would have been built
 * again, differently, the other way.
 */
class CommentTest {

	private static Dialogue withComment(Node.Comment comment) {
		return Dialogue.builder("doc")
			.start("line")
			.add(new Node.Line("line", "", "Hello.", Presentation.SUBTITLE, null, "end"))
			.add(new Node.End("end"))
			.add(comment)
			.build();
	}

	@Test
	@DisplayName("nothing leads out of a comment, so nothing can flow through one")
	void itIsNotOnAnyPath() {
		Node.Comment comment = new Node.Comment("why");
		assertEquals(List.of(), comment.exits());
		assertTrue(comment.isWriting());
		assertFalse(comment.waits());
		assertFalse(new Node.End("end").isWriting());
	}

	@Test
	@DisplayName("a well-commented graph is a graph with no complaints about it")
	void theValidatorLeavesItAlone() {
		// Both checks it would otherwise trip: it cannot be reached from the start, and
		// it has no way to an ending. Warned about, the first would put a permanent
		// complaint on every graph anybody explained — and a warning that is always
		// there is a warning nobody reads.
		Dialogue graph = withComment(new Node.Comment("why", "mind the order", 200, 4,
			List.of("line")));
		assertTrue(DialogueValidator.validate(graph).ok(),
			DialogueValidator.validate(graph).toString());
		assertEquals("no problems", DialogueValidator.validate(graph).toString());
	}

	@Test
	@DisplayName("a graph cannot begin at a comment")
	void itIsRefusedAsAStart() {
		// The one route in that exists at all: everything else in a graph is somebody's
		// way out, and a comment is nobody's. Reaching one ends the conversation at once,
		// which from outside is a character who says nothing when you click her.
		Dialogue graph = Dialogue.builder("doc")
			.start("why")
			.add(new Node.Comment("why"))
			.add(new Node.End("end"))
			.build();
		var report = DialogueValidator.validate(graph);
		assertFalse(report.ok());
		assertTrue(report.toString().contains("cannot be where the graph begins"), report.toString());
	}

	@Test
	@DisplayName("one comment may be about two nodes, and naming one twice does not double it")
	void aboutIsASet() {
		Node.Comment comment = new Node.Comment("why")
			.about("line", true).about("end", true).about("line", true);
		assertEquals(List.of("end", "line"), comment.about().stream().sorted().toList());
		assertEquals(2, comment.about().size());
		assertTrue(comment.about("line", false).about().equals(List.of("end")));
	}

	@Test
	@DisplayName("a comment survives the file, size and attachments and all")
	void throughTheFile() {
		Dialogue was = withComment(new Node.Comment("why", "mind the order", 200, 4,
			List.of("line", "end")));
		var written = DialogueCodecs.DIALOGUE.encodeStart(JsonOps.INSTANCE, was)
			.getOrThrow(message -> new AssertionError(message));
		Dialogue back = DialogueCodecs.DIALOGUE.parse(JsonOps.INSTANCE, written)
			.getOrThrow(message -> new AssertionError(message));

		assertEquals(was, back);
		Node.Comment read = (Node.Comment) back.node("why");
		assertEquals(200, read.wide());
		assertEquals(4, read.rows());
		assertEquals(List.of("line", "end"), read.about());
	}

	@Test
	@DisplayName("a size out of range is brought back inside rather than refused")
	void sizesAreBounded() {
		// These arrive from a file somebody else wrote. A card the size of the canvas is
		// a card covering the graph and one of no size is a card nobody can read, but
		// neither is worth throwing a document out over — it is a number, and the
		// nearest sensible number is the obvious answer.
		Node.Comment huge = new Node.Comment("why", "x", 100_000, 900, List.of());
		assertEquals(Node.Comment.WIDEST, huge.wide());
		assertEquals(Node.Comment.MOST_ROWS, huge.rows());

		Node.Comment tiny = new Node.Comment("why", "x", -4, 0, List.of());
		assertEquals(Node.Comment.NARROWEST, tiny.wide());
		assertEquals(Node.Comment.FEWEST_ROWS, tiny.rows());
	}
}
