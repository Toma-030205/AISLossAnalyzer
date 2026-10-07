package ais.ui;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

final class ExcludedDateParser {

    private ExcludedDateParser() {
    }

    static Set<LocalDate> parse(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<LocalDate> dates = new LinkedHashSet<>();
        Arrays.stream(value.trim().split("[,、\\s]+"))
                .filter(token -> !token.isBlank())
                .map(LocalDate::parse)
                .forEach(dates::add);
        return Set.copyOf(dates);
    }
}
