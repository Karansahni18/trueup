package com.karan.trueup.banking;

import java.util.List;

public record ParsedStatement(String currency, List<ParsedRow> rows) {
}