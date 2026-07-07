package net.democracrycraft.pluginInfrastructure.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurationParserUtilTest {

    @ParameterizedTest(name = "\"{0}\" -> {1}s")
    @CsvSource({
            "1s, 1",
            "30s, 30",
            "1m, 60",
            "1min, 60",
            "2min, 120",
            "1h, 3600",
            "1hr, 3600",
            "1d, 86400",
            "1w, 604800",
            "1wk, 604800",
    })
    @DisplayName("each unit maps to the correct number of seconds")
    void singleUnits(String input, long expectedSeconds) {
        assertEquals(Duration.ofSeconds(expectedSeconds), DurationParserUtil.parse(input));
    }

    @Test
    @DisplayName("combined segments sum together")
    void combinedSegments() {
        // 2w + 5d + 12h + 30m + 10s
        long expected = 2L * 604800 + 5L * 86400 + 12L * 3600 + 30L * 60 + 10L;
        assertEquals(Duration.ofSeconds(expected), DurationParserUtil.parse("2w5d12h30m10s"));
    }

    @Test
    @DisplayName("segments may repeat and appear in any order")
    void repeatedAndUnorderedSegments() {
        assertEquals(Duration.ofSeconds(120), DurationParserUtil.parse("1m1m"));
        assertEquals(Duration.ofSeconds(30 * 60 + 10), DurationParserUtil.parse("10s30m"));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"1D", "1MIN", "1Hr", "2W5D", "30S"})
    @DisplayName("parsing is case-insensitive")
    void caseInsensitive(String input) {
        assertEquals(DurationParserUtil.parse(input.toLowerCase()), DurationParserUtil.parse(input));
    }

    @Test
    @DisplayName("multi-char suffixes win over single-char ones (greedy match)")
    void greedyMatchPrefersLongerSuffix() {
        // "min" must be read as minutes, not "m" followed by a stray "in".
        assertEquals(Duration.ofMinutes(1), DurationParserUtil.parse("1min"));
        // "wk" and "hr" likewise.
        assertEquals(Duration.ofDays(7), DurationParserUtil.parse("1wk"));
        assertEquals(Duration.ofHours(1), DurationParserUtil.parse("1hr"));
    }

    @Test
    @DisplayName("empty input is rejected")
    void emptyInputThrows() {
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> DurationParserUtil.parse(""));
        assertTrue(ex.getMessage().toLowerCase().contains("empty"));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {
            "abc",     // no digits or units
            "10",      // number without a unit
            "10x",     // unknown unit
            "1.5h",    // decimals are not supported
            "1mh",     // unit with no preceding digits
            " 1h",     // leading whitespace
            "1h ",     // trailing whitespace
            "-5m",     // sign is not part of the grammar
    })
    @DisplayName("malformed input is rejected with a format error")
    void invalidFormatThrows(String input) {
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> DurationParserUtil.parse(input));
        assertTrue(ex.getMessage().toLowerCase().contains("invalid"),
                () -> "unexpected message: " + ex.getMessage());
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"0s", "0m", "0m0s"})
    @DisplayName("a well-formed but zero-length duration is rejected as non-positive")
    void zeroDurationThrows(String input) {
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> DurationParserUtil.parse(input));
        assertTrue(ex.getMessage().toLowerCase().contains("positive"),
                () -> "unexpected message: " + ex.getMessage());
    }

    @Test
    @DisplayName("null input throws (documents current behavior — a raw NPE, not the IAE the Javadoc promises)")
    void nullInputThrows() {
        // NOTE: DurationParserUtil#parse Javadoc claims an IllegalArgumentException for null input,
        // but the implementation dereferences the argument (input.isEmpty()) before any check, so a
        // NullPointerException escapes. This test pins the actual behavior; see the summary for the
        // Javadoc/implementation discrepancy.
        assertThrows(NullPointerException.class, () -> DurationParserUtil.parse(null));
    }
}
