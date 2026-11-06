package io.ara.runtime.contract;

import io.ara.core.agent.processor.ProcessingResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PiiRedactor} had no tests at all — the only class in this package without them.
 * These pin the redaction directions empirically probed when the bare-phone hole was
 * closed: each category's canonical form must be redacted, each pattern's known
 * false-positive shape is an accepted cost (over-redaction, never under), and the
 * bare-number hole the PHONE pattern's mandatory separators left open stays closed.
 */
class PiiRedactorTest {

    private String redacted(String payload) {
        ProcessingResult result = PiiRedactor.instance().process(payload);
        return result instanceof ProcessingResult.Pass p ? p.value() : "REJECT";
    }

    private void assertRedacted(String placeholder, String payload, String pii) {
        String out = redacted(payload);
        assertTrue(out.contains(placeholder),
                "'" + payload + "' must be redacted to something containing " + placeholder + ", was: " + out);
        String digits = pii.replaceAll("\\D", "");
        if (!digits.isEmpty()) {
            assertFalse(out.contains(digits),
                    "no digit run of the original payload may survive in: " + out);
        }
    }

    private void assertRedacted(String placeholder, String payload) {
        assertRedacted(placeholder, payload,
                payload.substring(payload.lastIndexOf(' ') + 1));
    }

    // ── each category's canonical form ─────────────────────────────────────────

    @Test
    void email_isRedacted() {
        assertRedacted("[EMAIL]", "write to mario.rossi@example.com please", "mario.rossi@example.com");
        assertRedacted("[EMAIL]", "a+b%1@sub.domain-it.co.uk", "a+b%1@sub.domain-it.co.uk");
    }

    @Test
    void spacedPhone_isRedacted() {
        assertRedacted("[PHONE]", "chiamami al 333 1234567");
        assertRedacted("[PHONE]", "+39 333 1234567");
        assertRedacted("[PHONE]", "chiamare 333-1234567");
    }

    @Test
    void bareItalianPhone_isRedacted() {
        assertRedacted("[PHONE]", "numero 3331234567");
        assertRedacted("[PHONE]", "telefono 0612345678");
        assertRedacted("[PHONE]", "fisso 061234567");
    }

    @Test
    void card_isRedacted() {
        assertRedacted("[CARD]", "carta 4111 1111 1111 1111", "4111 1111 1111 1111");
        assertRedacted("[CARD]", "id 12345678901234567", "12345678901234567");
    }

    @Test
    void ipv4_isRedacted_andInvalidOctetsAreNot() {
        assertRedacted("[IPv4]", "server 10.0.0.1 online", "10.0.0.1");
        assertEquals("999.999.999.999", redacted("999.999.999.999"),
                "octets above 255 are not an address");
    }

    @Test
    void fiscalCode_isRedacted() {
        assertRedacted("[CF]", "fiscale RSSMRA85M01H501Z");
    }

    @Test
    void nullPayload_passesAsEmpty() {
        assertEquals("", redacted(null));
    }

    // ── accepted false positives: over-redaction is the safe error direction ───

    @Test
    void yearRange_isAcceptedCollateral_ofTheSpacedPhonePattern() {
        // "2024-2025" matches PHONE's \d{3,4}<sep>\d{4,7} core. Known false positive,
        // documented on the class: the redactor is best-effort and errs toward over-redaction.
        assertEquals("gli anni [PHONE] sono stati intensi", redacted("gli anni 2024-2025 sono stati intensi"));
    }

    // ── the bare-number rule must not eat what it was narrowed to avoid ────────

    @Test
    void epochTimestamp_andShortNumbers_areNotBarePhoneRedacted() {
        assertEquals("ts 1720000000", redacted("ts 1720000000"),
                "a 10-digit epoch (starts with 1) is not a phone");
        assertEquals("ordine 12345678", redacted("ordine 12345678"),
                "an 8-digit id is not a phone");
    }

    @Test
    void noFalseRejects_ever() {
        // The contract is "always applied as a pass (never rejects)" — even garbage.
        assertPasses("not json at all {");
        assertPasses("");
        assertPasses("123456789012345678901234567890");
    }

    private void assertPasses(String payload) {
        ProcessingResult result = PiiRedactor.instance().process(payload);
        assertTrue(result instanceof ProcessingResult.Pass, "must never reject, got: " + result);
    }
}
