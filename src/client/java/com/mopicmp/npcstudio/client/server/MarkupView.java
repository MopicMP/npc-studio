package com.mopicmp.npcstudio.client.server;

import java.util.List;

import com.mopicmp.npcstudio.server.Markup;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/**
 * Somebody's description, drawn.
 *
 * One method does the placing, the drawing and the working out of what is under
 * the mouse, and that is on purpose rather than for want of tidiness. Every
 * position here depends on how tall the thing above it turned out — a paragraph
 * that wraps to four lines instead of three moves everything after it, and a
 * picture's height is not known until it has arrived over the network. Two
 * methods computing that separately have already drifted apart twice in this
 * screen; three would be worse.
 *
 * So {@link #draw} is called with graphics to draw, or without to measure, and
 * with the mouse to find a link. The three always agree because they are the
 * same walk.
 */
public final class MarkupView {

	private MarkupView() {
	}

	/** A picture that has arrived, with the size it really is. */
	public record Art(Identifier texture, int width, int height) {
	}

	/** Where the pictures inside a description come from. */
	public interface Source {
		/** Ask for one. Null means it has not arrived, or never will. */
		Art art(String url);

		/**
		 * The host of a picture this mod will not fetch, or null if it would.
		 *
		 * The two answers need telling apart, and this is why the interface has a
		 * second method rather than one that returns null twice. Half the pictures
		 * in a Modrinth description live on GitHub or on somebody's own site, and
		 * a description is written by a stranger — fetching whatever address it
		 * names hands this player's address to whoever wrote it, which is the
		 * oldest use there is for an image tag. So there is a list, and what is
		 * off it is shown as a line naming the site with a way to open it, where
		 * the person has decided to go.
		 */
		String unreachable(String url);
	}

	/** How tall it came out, and what the mouse was over. */
	public record Result(int height, String link) {
	}

	private static final int TEXT = 0xFFECEFF1;
	private static final int DIM = 0xFF8A99A6;
	private static final int LINK = 0xFF6FC6F7;
	private static final int EDGE = 0xFF2C333D;
	private static final int CODE_FILL = 0xFF090C10;
	private static final int LINE = 10;

	/** Space a picture inside the description gets while it is on its way. */
	private static final int WAITING = 90;

	public static Result draw(GuiGraphicsExtractor graphics, Font font, List<Markup.Block> blocks,
			int x, int y, int across, Source art, int mouseX, int mouseY) {
		int at = y;
		String found = null;

		for (Markup.Block block : blocks) {
			switch (block) {
				case Markup.Heading heading -> {
					float scale = heading.level() <= 2 ? 1.3f : 1.12f;
					at += heading.level() <= 2 ? 10 : 8;
					if (graphics != null) {
						var pose = graphics.pose();
						pose.pushMatrix();
						pose.translate(x, at);
						pose.scale(scale, scale);
						graphics.text(font, heading.text(), 0, 0, TEXT);
						pose.popMatrix();
					}
					at += (int) Math.ceil(8 * scale) + 4;
				}
				case Markup.Rule rule -> {
					at += 6;
					if (graphics != null) graphics.fill(x, at, x + across, at + 1, EDGE);
					at += 7;
				}
				case Markup.Code code -> {
					// A box of its own, because a snippet of configuration read as
					// a sentence is a snippet nobody can copy correctly.
					List<String> lines = wrapPlain(font, code.text(), across - 16);
					int tall = 8 + lines.size() * LINE + 6;
					if (graphics != null) {
						graphics.fill(x, at, x + across, at + tall, CODE_FILL);
						graphics.fill(x, at, x + across, at + 1, EDGE);
						graphics.fill(x, at + tall - 1, x + across, at + tall, EDGE);
						graphics.fill(x, at, x + 1, at + tall, EDGE);
						graphics.fill(x + across - 1, at, x + across, at + tall, EDGE);
						int line = at + 6;
						for (String each : lines) {
							graphics.text(font, each, x + 8, line, 0xFFA9E4B0);
							line += LINE;
						}
					}
					at += tall + 6;
				}
				case Markup.Picture picture -> {
					String elsewhere = art == null ? "" : art.unreachable(picture.url());
					if (elsewhere != null) {
						// One line, not a box. A picture that is never coming does
						// not deserve the room a picture takes, and ten of them in
						// one description — which is what a page of badges is —
						// would be a screen of empty rectangles.
						boolean here = mouseY >= at - 1 && mouseY < at + LINE - 1
							&& mouseX >= x && mouseX < x + across;
						if (here) found = picture.url();
						if (graphics != null) {
							graphics.text(font, picture.alt().isBlank() ? elsewhere
								: picture.alt() + " — " + elsewhere, x, at, here ? LINK : DIM);
						}
						at += LINE + 2;
						break;
					}
					MarkupView.Art got = art.art(picture.url());
					if (got == null || got.width() <= 0) {
						if (graphics != null) {
							graphics.fill(x, at, x + across, at + WAITING, 0xFF0E1116);
							graphics.text(font, picture.alt().isBlank() ? "…" : picture.alt(),
								x + 6, at + WAITING / 2 - 4, DIM);
						}
						at += WAITING + 6;
					} else {
						int wide = Math.min(across, got.width());
						int tall = Math.max(1, got.height() * wide / got.width());
						if (graphics != null) {
							graphics.blit(
								net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
								got.texture(), x, at, 0, 0, wide, tall,
								got.width(), got.height(), got.width(), got.height());
						}
						at += tall + 6;
					}
				}
				case Markup.Words words -> {
					int indent = words.bullet() ? 10 : 0;
					if (words.spaced()) at += 6;
					if (words.bullet() && graphics != null) {
						graphics.text(font, "·", x + 2, at, DIM);
					}
					MutableComponent line = component(words.spans());
					List<FormattedText> rows = font.getSplitter()
						.splitLines(line, across - indent, Style.EMPTY);
					if (rows.isEmpty()) rows = List.of(FormattedText.of(""));
					for (FormattedText row : rows) {
						boolean here = mouseY >= at - 1 && mouseY < at + LINE - 1
							&& mouseX >= x + indent && mouseX < x + across;
						String link = here ? linkAt(font, row, mouseX - x - indent) : null;
						if (link != null) found = link;
						if (graphics != null) {
							graphics.text(font, Language.getInstance().getVisualOrder(
								link == null ? row : underline(row)),
								x + indent, at, TEXT);
						}
						at += LINE;
					}
					at += 2;
				}
			}
		}
		return new Result(at - y, found);
	}

	/**
	 * The spans of a paragraph as one styled component.
	 *
	 * The address of a link is carried in the style's insertion rather than in a
	 * click event. That field is a plain string with nothing in the game reading
	 * it here, which is what is wanted: the address comes from a stranger's
	 * description, and it should be a piece of text this screen looks at when
	 * somebody presses, not something the game might act on by itself.
	 */
	private static MutableComponent component(List<Markup.Span> spans) {
		MutableComponent whole = Component.empty();
		for (Markup.Span span : spans) {
			MutableComponent part = Component.literal(span.text());
			Style style = Style.EMPTY;
			if (span.bold()) style = style.withBold(true);
			if (span.italic()) style = style.withItalic(true);
			if (span.code()) style = style.withColor(0xA9E4B0);
			if (span.isLink()) {
				style = style.withColor(LINK & 0xFFFFFF).withInsertion(span.link());
			}
			whole.append(part.withStyle(style));
		}
		return whole;
	}

	private static FormattedText underline(FormattedText row) {
		// Rebuilt rather than restyled in place: FormattedText has no way to say
		// "the same but underlined", and this happens for one line at a time.
		List<FormattedText> parts = new java.util.ArrayList<>();
		row.visit((style, text) -> {
			parts.add(FormattedText.of(text,
				style.getInsertion() != null ? style.withUnderlined(true) : style));
			return java.util.Optional.empty();
		}, Style.EMPTY);
		return FormattedText.composite(parts);
	}

	/**
	 * Which address is under a point on a line, if any.
	 *
	 * Walked by hand because this version has no method that answers it. Each run
	 * of one style is measured in turn until the point falls inside one, which is
	 * exact for the same reason the drawing is: both use the same widths.
	 */
	private static String linkAt(Font font, FormattedText row, int x) {
		int[] at = {0};
		String[] found = {null};
		row.visit((style, text) -> {
			int wide = font.width(Component.literal(text).withStyle(style));
			if (found[0] == null && x >= at[0] && x < at[0] + wide) {
				found[0] = style.getInsertion();
			}
			at[0] += wide;
			return java.util.Optional.empty();
		}, Style.EMPTY);
		return found[0];
	}

	/** Code wrapped without breaking words, because a word here may be a path. */
	private static List<String> wrapPlain(Font font, String text, int across) {
		List<String> out = new java.util.ArrayList<>();
		for (String line : text.split("\n", -1)) {
			if (font.width(line) <= across) {
				out.add(line);
				continue;
			}
			String rest = line;
			while (font.width(rest) > across && out.size() < 200) {
				String head = font.plainSubstrByWidth(rest, across);
				if (head.isEmpty()) break;
				out.add(head);
				rest = rest.substring(head.length());
			}
			out.add(rest);
		}
		return out;
	}
}
