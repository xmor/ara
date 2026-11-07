package io.ara.runtime.scheduler;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a schedule's {@code inputTemplate} at fire time, so the agent receives a date that is
 * right on the day it runs, not the one that was right when the schedule was written
 * ({ADR: LocalAgentScheduler templates}). The tokens it knows:
 *
 * <table>
 *   <caption>tokens</caption>
 *   <tr><th>Token</th><th>Means</th></tr>
 *   <tr><td>{@code {today}}</td><td>now, ISO {@code yyyy-MM-dd}</td></tr>
 *   <tr><td>{@code {today:FORMAT}}</td><td>now, in a {@link DateTimeFormatter} pattern</td></tr>
 *   <tr><td>{@code {date-N:FORMAT}} / {@code {date+N}}</td><td>N days from today
 *       ({"N"} may be omitted after the sign-less spelling {@code {date}}), pattern optional</td></tr>
 * </table>
 *
 * Everything else is left untouched: a text may legitimately contain braces. A pattern the
 * formatter cannot parse throws {@link IllegalArgumentException} — the fire fails with a
 * dispatch failure the schedule history shows, rather than sending the agent a half-rendered
 * request. Attacks the ambiguity from the cheap side: rendering is a pure function, tested on
 * its own, and the scheduler calls it right before the task is built.
 *
 * <p>Thread-safe; stateless.
 */
public final class InputTemplates {

    /** {@code {date-7:dd/MM/yyyy}}: the offset is part of the token, and the pattern follows the last {@code :}. */
    private static final Pattern TOKEN = Pattern.compile(
            "\\{(today|date)([-+]\\d+)?((?::[^{}:]+))?}");

    private InputTemplates() {}

    /** Renders for the current local date in the JVM's zone. */
    public static String render(String input) {
        return render(input, LocalDate.now(ZoneId.systemDefault()));
    }

    /** Renders against {@code today}, so a test can pin the moment a fire simulates. */
    public static String render(String input, LocalDate today) {
        if (input == null || input.indexOf('{') < 0) {
            return input;
        }
        Matcher m = TOKEN.matcher(input);
        StringBuilder out = new StringBuilder(input.length() + 32);
        while (m.find()) {
            String replacement;
            try {
                replacement = renderToken(m.group(1), m.group(2), m.group(3), today);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("inputTemplate token '" + m.group() + "': " + e.getMessage());
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String renderToken(String kind, String offset, String patternPart, LocalDate today) {
        int days = 0;
        if (offset != null && !offset.isBlank()) {
            if ("today".equals(kind)) {
                throw new IllegalArgumentException("today takes no day offset — use {date-N} for that");
            }
            try {
                days = Integer.parseInt(offset);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("the day offset is not a number");
            }
        }
        LocalDate when = today.plusDays(days);
        String pattern = patternPart == null ? null : patternPart.substring(1);
        if (pattern == null || pattern.isBlank()) {
            return when.toString();   // ISO-8601
        }
        try {
            return DateTimeFormatter.ofPattern(pattern).format(when);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("unknown date-time pattern: " + e.getMessage());
        }
    }
}
