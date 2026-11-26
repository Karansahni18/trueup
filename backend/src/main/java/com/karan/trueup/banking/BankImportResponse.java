package com.karan.trueup.banking;

import java.util.UUID;

public record BankImportResponse(UUID importId, String filename, String currency, int rowCount) {
}