package io.ara.examples.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.examples.support.Ansi;

/** Console presentation shared by the planner examples: banner, section titles, a check line. */
final class PlannerConsole {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PlannerConsole() { }

    static void banner(String title, boolean live, String liveModel, String liveBaseUrl) {
        String model = live
                ? "LIVE · " + liveModel + " @ " + liveBaseUrl
                : "stub · scripted, offline (pass \"live\" for a real model)";
        System.out.println(Ansi.paint(Ansi.BOLD, "═".repeat(74)));
        System.out.println(Ansi.paint(Ansi.BOLD, "  " + title));
        System.out.println("  " + Ansi.paint(Ansi.GREY, "LLM: " + model));
        System.out.println(Ansi.paint(Ansi.BOLD, "═".repeat(74)));
    }

    static void section(String title) {
        int fill = Math.max(1, 60 - title.length());
        System.out.println();
        System.out.println(Ansi.paint(Ansi.BOLD, "── " + title + " " + "─".repeat(fill)));
    }

    static void check(String what, boolean ok) {
        System.out.println("  " + (ok ? Ansi.paint(Ansi.GREEN, "ok      ") : Ansi.paint(Ansi.RED, "MISSING "))
                + what);
    }

    /** {@code text} broken into lines of at most 96 characters, each starting with {@code indent}. */
    static String wrap(String text, String indent) {
        StringBuilder out = new StringBuilder();
        for (String paragraph : text.strip().split("\n")) {
            StringBuilder line = new StringBuilder(indent);
            for (String word : paragraph.split(" ")) {
                if (line.length() > indent.length() && line.length() + word.length() + 1 > 96) {
                    out.append(line).append('\n');
                    line = new StringBuilder(indent);
                }
                if (line.length() > indent.length()) line.append(' ');
                line.append(word);
            }
            out.append(line).append('\n');
        }
        return out.toString().stripTrailing();
    }

    /** One line, at most 110 characters. */
    static String brief(String text) {
        String flat = text == null ? "" : text.replace("\n", " ");
        return flat.length() <= 110 ? flat : flat.substring(0, 107) + "...";
    }

    /** The JSON in {@code text}, or {@code null} when it is not JSON. */
    static JsonNode json(String text) {
        try {
            return text == null ? null : MAPPER.readTree(text);
        } catch (Exception notJson) {
            return null;
        }
    }
}
