package com.minecraftcitiesnetwork.pluginInfrastructure.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrencyFormatterTest {

    // CurrencyFormatter formats with the pattern "#,##0.00" against the default locale's symbols.
    // Build expectations from those same symbols so the assertions hold in any locale/CI environment.
    private static final DecimalFormatSymbols SYMBOLS = new DecimalFormatSymbols();
    private static final char GROUP = SYMBOLS.getGroupingSeparator();
    private static final char DECIMAL = SYMBOLS.getDecimalSeparator();
    private static final char MINUS = SYMBOLS.getMinusSign();

    @Test
    @DisplayName("always renders exactly two fractional digits")
    void twoFractionalDigits() {
        assertEquals("0" + DECIMAL + "00", CurrencyFormatter.format(0));
        assertEquals("5" + DECIMAL + "00", CurrencyFormatter.format(5));
        assertEquals("5" + DECIMAL + "50", CurrencyFormatter.format(5.5));
    }

    @Test
    @DisplayName("groups thousands")
    void groupsThousands() {
        assertEquals("1" + GROUP + "234" + DECIMAL + "56", CurrencyFormatter.format(1234.56));
        assertEquals("1" + GROUP + "234" + GROUP + "567" + DECIMAL + "89",
                CurrencyFormatter.format(1234567.89));
    }

    @Test
    @DisplayName("keeps the sign on negative amounts")
    void negativeAmounts() {
        assertEquals(MINUS + "1" + GROUP + "234" + DECIMAL + "50", CurrencyFormatter.format(-1234.5));
    }

    @Test
    @DisplayName("rounds to two decimals")
    void roundsToTwoDecimals() {
        // Values chosen to be unambiguous under binary floating point (avoid exact .xx5 halves).
        assertEquals("1" + DECIMAL + "24", CurrencyFormatter.format(1.239));
        assertEquals("1" + DECIMAL + "23", CurrencyFormatter.format(1.231));
    }

    @Test
    @DisplayName("concurrent formatting is correct — each thread gets its own DecimalFormat")
    void concurrentFormattingIsThreadSafe() throws Exception {
        int threads = 8;
        int iterationsPerThread = 5_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Boolean>> tasks = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                double value = 1000 * (t + 1) + 0.25; // e.g. 1000.25, 2000.25, ...
                String expected = CurrencyFormatter.format(value);
                tasks.add(() -> {
                    for (int i = 0; i < iterationsPerThread; i++) {
                        if (!expected.equals(CurrencyFormatter.format(value))) {
                            return false;
                        }
                    }
                    return true;
                });
            }
            List<Future<Boolean>> results = pool.invokeAll(tasks);
            for (Future<Boolean> result : results) {
                assertTrue(result.get(), "concurrent formatting produced a corrupted result");
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
    }
}
