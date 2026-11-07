package io.ara.runtime.scheduler;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The input templating of a schedule fire: dates that are right when the run happens, tested on a
 * pinned date so no test looks at a calendar.
 */
class InputTemplatesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 7);

    @Test
    void theWeekWindowComesOut_right() {
        assertEquals("Resoconto dal 2026-10-31 al 2026-11-07",
                InputTemplates.render("Resoconto dal {date-7} al {today}", TODAY));
    }

    @Test
    void patternsFollowTheColon_includingTheItalianSpelling() {
        assertEquals("arXiv research from 01/11/2026 to 07/11/2026",
                InputTemplates.render("arXiv research from {date-6:dd/MM/yyyy} to {date:dd/MM/yyyy}", TODAY));
    }

    @Test
    void futureOffsetsAndPlainTodayWork() {
        assertEquals("2026-11-14", InputTemplates.render("{date+7}", TODAY));
        assertEquals("2026-11-07", InputTemplates.render("{date}", TODAY));
        assertEquals("20261107", InputTemplates.render("{today:yyyyMMdd}", TODAY));
    }

    @Test
    void unknownTextAndTokensPassThroughUntouched() {
        String input = "set {count} items — the brace is literal { not a token}";
        assertEquals(input, InputTemplates.render(input, TODAY));
        assertEquals("plain", InputTemplates.render("plain", TODAY));
        assertTrue(InputTemplates.render(null, TODAY) == null);
    }

    @Test
    void aBrokenPatternFailsTheFire_withTheTokenNamed() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> InputTemplates.render("finestra {date-7:QUI NON POSSO }", TODAY));
        assertTrue(e.getMessage().contains("{date-7:QUI NON POSSO }"), e.getMessage());

        IllegalArgumentException noOffset = assertThrows(IllegalArgumentException.class,
                () -> InputTemplates.render("{today-1}", TODAY));
        assertTrue(noOffset.getMessage().contains("date-N"), noOffset.getMessage());
    }
}
