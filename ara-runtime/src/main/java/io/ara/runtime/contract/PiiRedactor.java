package io.ara.runtime.contract;

import io.ara.core.agent.processor.InputProcessor;
import io.ara.core.agent.processor.OutputProcessor;
import io.ara.core.agent.processor.ProcessingResult;

import java.util.regex.Pattern;

/**
 * Redacts common PII (Personally Identifiable Information) patterns from a payload
 * before it reaches an LLM or leaves the system.
 *
 * <p>Each recognized category is replaced with a typed placeholder so downstream
 * agents can still reason about the structure without accessing the sensitive value:
 * <ul>
 *   <li>Email addresses → {@code [EMAIL]}</li>
 *   <li>Italian/international phone numbers → {@code [PHONE]}</li>
 *   <li>Credit/debit card numbers (Luhn-shaped, 13–19 digits) → {@code [CARD]}</li>
 *   <li>IPv4 addresses → {@code [IPv4]}</li>
 *   <li>Italian fiscal codes → {@code [CF]}</li>
 * </ul>
 *
 * <p>The redaction is best-effort and regex-based; it is not a substitute for a
 * dedicated NLP-based PII detection library in high-compliance environments.
 * Always applied as a pass (never rejects) — redaction is silent by design.
 *
 * <p>Can be used as both an {@link InputProcessor} and an {@link OutputProcessor}.
 */
public final class PiiRedactor implements InputProcessor, OutputProcessor {

    private static final PiiRedactor INSTANCE = new PiiRedactor();

    // email
    private static final Pattern EMAIL = Pattern.compile(
            "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");

    // phone: +39 333 1234567 / 333-123-4567 / (039) 1234567
    private static final Pattern PHONE = Pattern.compile(
            "(?:\\+?\\d{1,3}[\\s\\-.])?(?:\\(?\\d{2,4}\\)?[\\s\\-.])?\\d{3,4}[\\s\\-.]\\d{4,7}");

    // bare Italian numbers written with no separator at all — "3331234567", "0612345678" —
    // which PHONE's core (\d{3,4}<separator>\d{4,7}) cannot see: the most common written form
    // of an Italian mobile was sailing through unredacted. Prefix-pinned (mobiles: exactly 10
    // digits starting with 3; landlines: 9-10 starting with 0) rather than any \d{9,10}\b on
    // purpose — an unprefixed bare-run rule would also eat epoch timestamps (10 digits
    // starting with 1) and generic numeric ids, over-redaction the contract would tolerate
    // but there is no reason to accept when the prefix narrows the surface for free. Numeric
    // ids that do start with 3/0 at that length are still redacted: the accepted cost, in
    // the direction this class treats as safe (over-redaction, never under).
    private static final Pattern PHONE_BARE = Pattern.compile("\\b3\\d{9}\\b|\\b0\\d{8,9}\\b");

    // card: 13-19 consecutive digits (optionally spaced/dashed in groups)
    private static final Pattern CARD = Pattern.compile(
            "\\b(?:\\d[ \\-]?){13,19}\\b");

    // IPv4
    private static final Pattern IPV4 = Pattern.compile(
            "\\b(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\b");

    // Italian fiscal code (codice fiscale)
    private static final Pattern CF = Pattern.compile(
            "\\b[A-Z]{6}\\d{2}[A-EHLMPRST]\\d{2}[A-Z]\\d{3}[A-Z]\\b",
            Pattern.CASE_INSENSITIVE);

    private PiiRedactor() {}

    public static PiiRedactor instance() { return INSTANCE; }

    @Override
    public ProcessingResult process(String payload) {
        if (payload == null) return ProcessingResult.pass("");
        String result = payload;
        result = EMAIL.matcher(result).replaceAll("[EMAIL]");
        result = IPV4 .matcher(result).replaceAll("[IPv4]");
        result = CF   .matcher(result).replaceAll("[CF]");
        result = CARD .matcher(result).replaceAll("[CARD]");
        result = PHONE.matcher(result).replaceAll("[PHONE]");
        result = PHONE_BARE.matcher(result).replaceAll("[PHONE]");
        return ProcessingResult.pass(result);
    }
}
