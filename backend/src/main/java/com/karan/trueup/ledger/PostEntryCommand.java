package com.karan.trueup.ledger;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PostEntryCommand(UUID tenantId, LocalDate entryDate, String description, List<Line> lines) {

    public record Line(UUID accountId, Direction direction, long amountMinor) {
    }
}