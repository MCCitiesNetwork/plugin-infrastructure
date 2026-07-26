package com.minecraftcitiesnetwork.pluginInfrastructure.util;

import org.jetbrains.annotations.NotNull;

import java.text.DateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

public final class DateFormatter {

    private DateFormatter() {}

    /**
     * Formats a {@link LocalDateTime} using the given {@link DateFormat}, interpreting the value in
     * the JVM's default time zone.
     *
     * @param dateFormat the format to apply; note {@link DateFormat} is not thread-safe, so callers
     *                   must not share a single instance across threads
     * @param dateTime   the local date-time to format
     * @return the formatted string
     */
    public static @NotNull String format(@NotNull DateFormat dateFormat, @NotNull LocalDateTime dateTime) {
        Date date = Date.from(dateTime.atZone(ZoneId.systemDefault()).toInstant());
        return dateFormat.format(date);
    }
}
