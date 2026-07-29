package com.example.ragagent.export;

import java.util.ArrayList;
import java.util.List;

/**
 * Flattens export markdown to plain text for the TXT format: markup is removed, the content it
 * wrapped is kept. Code fences are dropped but their contents stay verbatim, so code in the source
 * document survives the round trip.
 */
public final class PlainTextRenderer {

    private PlainTextRenderer() {}

    public static String render(String markdown) {
        if (markdown == null || markdown.isBlank()) return "";

        List<String> out = new ArrayList<>();
        for (String line : markdown.split("\n", -1)) {
            String t = line.strip();
            if (t.startsWith("```")) continue;                   // drop the fence, keep the code

            // Blockquote markers must come off before heading markers: a Vision image description
            // can itself contain markdown headings, so a line reads "> ### 제목". Stripping headings
            // first would leave the "###" stranded once the "> " was removed.
            t = t.replaceAll("^(?:>\\s?)+", "");                 // blockquote markers (nestable)
            t = t.replaceAll("^(#{1,6})\\s+", "");               // heading markers
            t = t.replaceAll("\\*\\*(.+?)\\*\\*", "$1");
            t = t.replaceAll("\\*(.+?)\\*", "$1");
            t = t.replaceAll("`(.+?)`", "$1");
            t = t.replaceAll("!?\\[([^\\]]*)]\\([^)]*\\)", "$1"); // links/images → their label
            out.add(t);
        }
        return String.join("\n", out).replaceAll("\n{3,}", "\n\n").strip();
    }
}
