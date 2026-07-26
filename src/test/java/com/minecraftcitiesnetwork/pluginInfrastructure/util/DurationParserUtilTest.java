package com.minecraftcitiesnetwork.pluginInfrastructure.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

import static com.minecraftcitiesnetwork.pluginInfrastructure.util.DurationParserUtil.PERMANENT_KEYWORDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @DisplayName("null input throws NullPointerException, as documented")
    void nullInputThrows() {
        // An explicit requireNonNull, so the NPE carries the parameter name rather than arriving as
        // an incidental dereference of the argument.
        assertThrows(NullPointerException.class, () -> DurationParserUtil.parse(null));
        assertThrows(NullPointerException.class, () -> DurationParserUtil.parseAllowingPermanent(null, PERMANENT_KEYWORDS));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {
            "9999999999999999999999s",   // 22 digits: longer than Long.MAX_VALUE
            "9223372036854775808s",      // 19 digits but one past Long.MAX_VALUE
    })
    @DisplayName("an amount too large for a long is rejected as IAE, never NumberFormatException")
    void oversizedAmountThrowsIllegalArgument(String input) {
        // Regression guard: plain Long.parseLong here would leak a NumberFormatException out of a
        // command handler, which callers have no reason to catch.
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> DurationParserUtil.parse(input));
        assertFalse(ex instanceof NumberFormatException,
                () -> "must not be a NumberFormatException: " + ex);
        assertTrue(ex.getMessage().toLowerCase().contains("too large"),
                () -> "unexpected message: " + ex.getMessage());
    }

    @Test
    @DisplayName("an amount that overflows only after unit multiplication is rejected, not wrapped")
    void overflowingMultiplicationThrows() {
        // Parses as a long fine, but * 604800 (a week in seconds) overflows. With plain `long`
        // arithmetic this silently wrapped to a small positive value and produced a
        // plausible-looking but completely wrong duration.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DurationParserUtil.parse("9223372036854775807w"));
        assertTrue(ex.getMessage().toLowerCase().contains("too large"),
                () -> "unexpected message: " + ex.getMessage());
    }

    @Test
    @DisplayName("a sum that overflows across segments is rejected, not wrapped")
    void overflowingAdditionThrows() {
        String halfMax = Long.toString(Long.MAX_VALUE / 2 + 1);
        assertThrows(IllegalArgumentException.class,
                () -> DurationParserUtil.parse(halfMax + "s" + halfMax + "s"));
    }

    @Test
    @DisplayName("durations beyond the ~1000-year ceiling are rejected")
    void absurdlyLongDurationThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DurationParserUtil.parse("400000d"));
        assertTrue(ex.getMessage().toLowerCase().contains("too large"),
                () -> "unexpected message: " + ex.getMessage());

        // The bound sits far above any plausible real duration, so this must still parse.
        assertEquals(Duration.ofDays(50_000), DurationParserUtil.parse("50000d"));
    }

    @Test
    @DisplayName("unit matching is unaffected by a locale with unusual case mapping")
    void parsingIsLocaleIndependent() {
        Locale previous = Locale.getDefault();
        try {
            // In Turkish, "MIN".toLowerCase() is "mın" (dotless i), which does not match the "min"
            // unit key. Locale.ROOT in the implementation is what keeps this working.
            Locale.setDefault(Locale.of("tr", "TR"));
            assertEquals(Duration.ofMinutes(1), DurationParserUtil.parse("1MIN"));
            assertEquals(Duration.ofHours(1), DurationParserUtil.parse("1HR"));
            assertEquals(Duration.ofDays(7), DurationParserUtil.parse("1WK"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"perm", "permanent", "PERM", "Permanent"})
    @DisplayName("permanent keywords yield an empty Optional, in any casing")
    void permanentKeywordsYieldEmpty(String input) {
        assertTrue(DurationParserUtil.parseAllowingPermanent(input, PERMANENT_KEYWORDS).isEmpty());
    }

    @Test
    @DisplayName("parseAllowingPermanent distinguishes permanent from invalid and from a real duration")
    void parseAllowingPermanentSeparatesTheThreeOutcomes() {
        // Present: a real duration.
        assertEquals(Duration.ofHours(1), DurationParserUtil.parseAllowingPermanent("1h", PERMANENT_KEYWORDS).orElseThrow());
        // Empty: permanent — emphatically not "unparseable".
        assertTrue(DurationParserUtil.parseAllowingPermanent("perm", PERMANENT_KEYWORDS).isEmpty());
        // Throws: invalid. Never reported as an empty Optional.
        assertThrows(IllegalArgumentException.class,
                () -> DurationParserUtil.parseAllowingPermanent("forever", PERMANENT_KEYWORDS));
        assertThrows(IllegalArgumentException.class,
                () -> DurationParserUtil.parseAllowingPermanent("-1", PERMANENT_KEYWORDS));
    }

    @Test
    @DisplayName("the caller supplies the vocabulary; the library does not impose one")
    void permanentVocabularyIsTheCallers() {
        Set<String> ourWords = Set.of("forever", "eternal");

        assertTrue(DurationParserUtil.parseAllowingPermanent("FOREVER", ourWords).isEmpty());
        assertTrue(DurationParserUtil.parseAllowingPermanent("eternal", ourWords).isEmpty());
        // The conventional set is only a default, so its words carry no special status here.
        assertThrows(IllegalArgumentException.class,
                () -> DurationParserUtil.parseAllowingPermanent("perm", ourWords));
    }

    @Test
    @DisplayName("the suggested permanent keyword set is unmodifiable")
    void permanentKeywordsAreUnmodifiable() {
        assertThrows(UnsupportedOperationException.class,
                () -> DurationParserUtil.PERMANENT_KEYWORDS.add("forever"));
    }
}
