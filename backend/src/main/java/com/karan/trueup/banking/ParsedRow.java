package com.karan.trueup.banking;

import java.time.LocalDate;

public record ParsedRow(int rowNumber, LocalDate date, String description, String reference, long amountMinor) {
}