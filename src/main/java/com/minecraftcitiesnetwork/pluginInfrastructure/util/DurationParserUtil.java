package com.minecraftcitiesnetwork.pluginInfrastructure.util;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static utility class for parsing human-readable duration strings into {@link Duration} objects.
 *
 * <p>Supported time units:</p>
 * <ul>
 *   <li>{@code s} — seconds</li>
 *   <li>{@code m}, {@code min} — minutes</li>
 *   <li>{@code h}, {@code hr} — hours</li>
 *   <li>{@code d} — days</li>
 *   <li>{@code w}, {@code wk} — weeks</li>
 * </ul>
 *
 * <p>Units can be combined in a single string (e.g. {@code 1d3hr}, {@code 2w5d12h30m10s}).
 * Matching is case-insensitive, but the whole string must consist of nothing but
 * {@code <digits><unit>} segments — there is no tolerance for leading/trailing whitespace,
 * embedded whitespace, signs, or trailing junk. A string such as {@code "1h2y"} is rejected
 * outright rather than silently interpreted as {@code "1h"}.</p>
 *
 * <p>This class deals only in real, positive, finite durations. The notion of a
 * <em>permanent</em> duration is expressed separately and unambiguously by
 * {@link #parseAllowingPermanent(String, Set)}; it is never encoded as a sentinel value such as
 * {@code -1}, which would be indistinguishable from a parse failure.</p>
 *
 * <p><strong>Input is untrusted.</strong> Callers typically hand this method a string a player or
 * staff member typed into a command, so every rejection path throws
 * {@link IllegalArgumentException} — never a {@link NumberFormatException} or an
 * {@link ArithmeticException} — and no input can produce a silently wrong result by overflowing.
 * See {@link #parse(String)}.</p>
 *
 * <p><strong>Thread-safety:</strong> stateless; all state is immutable and initialised once.</p>
 */
public final class DurationParserUtil {

    /**
     * A conventional set of keywords meaning "permanent" (i.e. no expiry), offered as a default to
     * pass to {@link #parseAllowingPermanent(String, Set)}. Stored lower-case, since that method
     * compares against a lower-cased input.
     *
     * <p>This is a suggestion, not a vocabulary this library imposes: a caller whose commands say
     * {@code forever}, or who reads the wording from a language file, passes its own set. The
     * reusable part is the three-outcome contract on
     * {@link #parseAllowingPermanent(String, Set)}, not these particular English words.</p>
     *
     * <p>Unmodifiable.</p>
     */
    public static final Set<String> PERMANENT_KEYWORDS = Set.of("perm", "permanent");

    /**
     * Ordered map of unit suffix → multiplier in seconds, longest suffix first so that
     * greedy matching picks multi-char suffixes (e.g. "min") before single-char ones (e.g. "m").
     */
    private static final Map<String, Long> UNIT_TO_SECONDS;

    /**
     * Upper bound on the total number of seconds a parsed duration may represent:
     * 365 000 days, i.e. roughly one thousand years.
     *
     * <p>Anything beyond this is a typo rather than an intent, and accepting it would hand
     * downstream code (which commonly stores expiries as epoch milliseconds) a nonsensical
     * timestamp. Rejecting it here turns a silent absurdity into a clear
     * {@link IllegalArgumentException}. The bound is deliberately far above any plausible
     * punishment or lease length — {@code 50000d} (~137 years) is comfortably inside it.</p>
     */
    private static final long MAX_TOTAL_SECONDS = 365_000L * 24 * 60 * 60;

    static {
        // Use a LinkedHashMap so iteration order is insertion order (longest suffixes first).
        UNIT_TO_SECONDS = new LinkedHashMap<>();
        UNIT_TO_SECONDS.put("min", 60L);
        UNIT_TO_SECONDS.put("wk", 7L * 24 * 60 * 60);
        UNIT_TO_SECONDS.put("hr", 60L * 60);
        UNIT_TO_SECONDS.put("w", 7L * 24 * 60 * 60);
        UNIT_TO_SECONDS.put("d", 24L * 60 * 60);
        UNIT_TO_SECONDS.put("h", 60L * 60);
        UNIT_TO_SECONDS.put("m", 60L);
        UNIT_TO_SECONDS.put("s", 1L);
    }

    /**
     * Pattern that matches one or more segments of {@code <digits><unit>}, anchored to both ends
     * of the input. Used for full-string validation; the anchoring is what rejects trailing junk,
     * unknown units, signs and stray whitespace.
     */
    private static final Pattern FULL_PATTERN;

    /**
     * Pattern that captures a single {@code <digits><unit>} segment.
     */
    private static final Pattern SEGMENT_PATTERN;

    static {
        // Build a group that matches any known unit suffix (longest first for correct greedy match).
        String unitGroup = String.join("|", UNIT_TO_SECONDS.keySet()); // min|wk|hr|w|d|h|m|s
        SEGMENT_PATTERN = Pattern.compile("(\\d+)(" + unitGroup + ")", Pattern.CASE_INSENSITIVE);
        FULL_PATTERN = Pattern.compile("^(?:" + SEGMENT_PATTERN.pattern() + ")+$", Pattern.CASE_INSENSITIVE);
    }

    private DurationParserUtil() {
        throw new AssertionError("Utility class");
    }

    /**
     * Parse a human-readable duration string into a {@link Duration}.
     *
     * <p>The entire input must consist of {@code <digits><unit>} segments and nothing else.
     * The returned duration is always strictly positive.</p>
     *
     * @param input the duration string (e.g. {@code "1d3hr"}, {@code "30m"}, {@code "2w5d"})
     * @return the parsed {@link Duration}, always strictly positive
     * @throws IllegalArgumentException if the input is empty, uses an unknown unit, is a bare
     *                                  number with no unit, contains whitespace or any other
     *                                  unrecognised character, totals zero, or is so large that
     *                                  it cannot be represented. This is the <em>only</em>
     *                                  exception type this method throws for bad input; in
     *                                  particular a {@link NumberFormatException} and an
     *                                  {@link ArithmeticException} never escape.
     * @throws NullPointerException     if {@code input} is {@code null}
     */
    public static @NotNull Duration parse(@NotNull String input) {
        Objects.requireNonNull(input, "input");
        if (input.isEmpty()) {
            throw new IllegalArgumentException("Duration string must not be empty");
        }

        // Locale.ROOT: a locale-sensitive lower-casing would map "MIN" to "mın" under a
        // Turkish default locale and break the unit lookup.
        String normalized = input.toLowerCase(Locale.ROOT);

        if (!FULL_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid duration format: '" + input + "'");
        }

        long totalSeconds = 0;
        Matcher matcher = SEGMENT_PATTERN.matcher(normalized);

        while (matcher.find()) {
            String rawAmount = matcher.group(1);
            String unit = matcher.group(2);
            Long multiplier = UNIT_TO_SECONDS.get(unit);
            // multiplier should never be null here since we already validated against FULL_PATTERN

            // Plain `long` arithmetic and an unguarded Long.parseLong are both unsafe on input this
            // untrusted: an absurdly large amount could either blow up with a NumberFormatException
            // leaking out of a command handler, or silently wrap around and yield a
            // plausible-looking but completely wrong duration. Both failure modes are converted
            // into a plain IllegalArgumentException here.
            long amount;
            try {
                amount = Long.parseLong(rawAmount);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(
                        "Duration component is too large: '" + rawAmount + unit + "' in '" + input + "'", ex);
            }
            try {
                totalSeconds = Math.addExact(totalSeconds, Math.multiplyExact(amount, multiplier));
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException("Duration is too large: '" + input + "'", ex);
            }
        }

        if (totalSeconds <= 0) {
            throw new IllegalArgumentException("Duration must be positive: '" + input + "'");
        }
        if (totalSeconds > MAX_TOTAL_SECONDS) {
            throw new IllegalArgumentException("Duration is too large: '" + input + "'");
        }

        return Duration.ofSeconds(totalSeconds);
    }

    /**
     * Parse a duration string that is additionally allowed to be one of {@code permanentKeywords},
     * matched case-insensitively.
     *
     * <p>The three possible outcomes are strictly distinct, and callers must treat them as such:</p>
     * <ul>
     *   <li>An <strong>empty</strong> {@link Optional} means <strong>permanent</strong> — the
     *       caller asked for something with no expiry. It does <em>not</em> mean "could not
     *       parse".</li>
     *   <li>A <strong>present</strong> {@link Optional} holds a strictly positive
     *       {@link Duration}.</li>
     *   <li><strong>Invalid</strong> input throws {@link IllegalArgumentException} — it is never
     *       reported as an empty {@link Optional}.</li>
     * </ul>
     *
     * <p>That three-way split is the point of this method: it is what lets a caller store
     * "permanent" as a real absence (a {@code null} expiry column) instead of a {@code -1} sentinel
     * that a parse failure could masquerade as. The wording that means permanent is the caller's —
     * pass {@link #PERMANENT_KEYWORDS} for the conventional English set, or your own for anything
     * else.</p>
     *
     * @param input             the duration string, or a permanent keyword
     * @param permanentKeywords the keywords meaning "no expiry", in lower case
     * @return {@link Optional#empty()} if the input names a permanent duration, otherwise an
     *         {@link Optional} containing the parsed, strictly positive {@link Duration}
     * @throws IllegalArgumentException if the input is neither a permanent keyword nor a valid
     *                                  duration string; see {@link #parse(String)}
     * @throws NullPointerException     if either argument is {@code null}
     */
    public static @NotNull Optional<Duration> parseAllowingPermanent(@NotNull String input,
                                                                     @NotNull Set<String> permanentKeywords) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(permanentKeywords, "permanentKeywords");
        if (permanentKeywords.contains(input.toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        return Optional.of(parse(input));
    }
}
