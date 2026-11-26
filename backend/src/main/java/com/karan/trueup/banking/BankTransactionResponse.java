package com.karan.trueup.banking;

import java.time.LocalDate;
import java.util.UUID;

public record BankTransactionResponse(UUID id, LocalDate date, String description, String reference,
                                      long amountMinor, String currency) {
}