package net.democracrycraft.pluginInfrastructure.util;

import org.jetbrains.annotations.NotNull;

import java.text.DecimalFormat;

public final class CurrencyFormatter {

    // DecimalFormat is not thread-safe; a shared instance corrupts output under concurrent
    // formatting. Give each thread its own instance so callers on async threads are safe.
    private static final ThreadLocal<DecimalFormat> FORMAT =
            ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.00"));

    private CurrencyFormatter() {}

    public static @NotNull String format(double amount) {
        return FORMAT.get().format(amount);
    }
}
