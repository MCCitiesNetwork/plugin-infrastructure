package com.minecraftcitiesnetwork.pluginInfrastructure.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DateFormatterTest {

    // A SimpleDateFormat uses the JVM default time zone by default, and DateFormatter converts the
    // LocalDateTime through that same default zone — so the rendered wall-clock components round-trip
    // exactly regardless of what the actual default zone happens to be on this machine/CI.
    @Test
    @DisplayName("renders the local date-time's wall-clock fields with the supplied pattern")
    void formatsWithGivenPattern() {
        DateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        LocalDateTime dateTime = LocalDateTime.of(2026, 7, 7, 13, 45, 30);

        assertEquals("2026-07-07 13:45:30", DateFormatter.format(format, dateTime));
    }

    @Test
    @DisplayName("honors a different pattern")
    void honorsDifferentPattern() {
        DateFormat format = new SimpleDateFormat("dd/MM/yyyy");
        LocalDateTime dateTime = LocalDateTime.of(1999, 12, 31, 23, 59, 59);

        assertEquals("31/12/1999", DateFormatter.format(format, dateTime));
    }

    @Test
    @DisplayName("midnight formats as 00:00:00")
    void formatsMidnight() {
        DateFormat format = new SimpleDateFormat("HH:mm:ss");
        LocalDateTime midnight = LocalDateTime.of(2026, 1, 1, 0, 0, 0);

        assertEquals("00:00:00", DateFormatter.format(format, midnight));
    }
}
