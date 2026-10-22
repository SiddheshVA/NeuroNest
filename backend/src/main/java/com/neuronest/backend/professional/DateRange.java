package com.neuronest.backend.professional;

import java.time.LocalDate;

/** A range of calendar dates, both ends included. */
public record DateRange(LocalDate from, LocalDate to) {

    public boolean contains(LocalDate date) {
        return !date.isBefore(from) && !date.isAfter(to);
    }
}
