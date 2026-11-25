package com.karan.trueup.banking;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class BankStatementParser {

    static final int MAX_ROWS = 5000;
    private static final int MAX_PROBLEMS_SHOWN = 20;
    private static final int MAX_DESCRIPTION_LENGTH = 500;
    private static final int MAX_REFERENCE_LENGTH = 100;

    public ParsedStatement parse(String csvText, String currency) {
        int fractionDigits = Currency.getInstance(currency).getDefaultFractionDigits();
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .build();

        List<ParsedRow> rows = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        try (CSVParser parser = format.parse(new StringReader(csvText))) {
            Map<String, Integer> columns = columnPositions(parser.getHeaderNames());
            if (!columns.keySet().containsAll(List.of("date", "description", "amount"))) {
                throw new InvalidStatementException(List.of(
                        "The header must contain the columns date, description and amount (found: "
                                + String.join(", ", parser.getHeaderNames()) + ")"));
            }

            int rowNumber = 0;
            for (CSVRecord record : parser) {
                rowNumber++;
                if (rowNumber > MAX_ROWS) {
                    problems.add("The file has more than " + MAX_ROWS + " rows. Please split it into smaller files");
                    break;
                }
                readRow(rowNumber, record, columns, fractionDigits, rows, problems);
            }
        } catch (InvalidStatementException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new InvalidStatementException(List.of("The file could not be read as CSV: " + ex.getMessage()));
        }

        if (problems.isEmpty() && rows.isEmpty()) {
            problems.add("The file has no transactions");
        }
        if (!problems.isEmpty()) {
            throw new InvalidStatementException(limit(problems));
        }
        return new ParsedStatement(currency, List.copyOf(rows));
    }

    private static Map<String, Integer> columnPositions(List<String> headerNames) {
        Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < headerNames.size(); i++) {
            String name = headerNames.get(i) == null ? "" : headerNames.get(i).trim().toLowerCase(Locale.ROOT);
            positions.putIfAbsent(name, i);
        }
        return positions;
    }

    private void readRow(int rowNumber, CSVRecord record, Map<String, Integer> columns, int fractionDigits,
                         List<ParsedRow> rows, List<String> problems) {
        int problemsBefore = problems.size();

        String dateText = cell(record, columns.get("date"));
        LocalDate date = null;
        try {
            date = LocalDate.parse(dateText);
        } catch (DateTimeParseException ex) {
            problems.add("Row " + rowNumber + ": date '" + dateText + "' must look like 2026-11-24");
        }

        String description = cell(record, columns.get("description"));
        if (description.isBlank()) {
            problems.add("Row " + rowNumber + ": description is empty");
        } else if (description.length() > MAX_DESCRIPTION_LENGTH) {
            problems.add("Row " + rowNumber + ": description is longer than " + MAX_DESCRIPTION_LENGTH + " characters");
        }

        String reference = cell(record, columns.get("reference"));
        if (reference.length() > MAX_REFERENCE_LENGTH) {
            problems.add("Row " + rowNumber + ": reference is longer than " + MAX_REFERENCE_LENGTH + " characters");
        }

        String amountText = cell(record, columns.get("amount"));
        long amountMinor = 0;
        try {
            amountMinor = toMinorUnits(amountText, fractionDigits);
            if (amountMinor == 0) {
                problems.add("Row " + rowNumber + ": amount cannot be zero");
            }
        } catch (IllegalArgumentException ex) {
            problems.add("Row " + rowNumber + ": amount '" + amountText + "' " + ex.getMessage());
        }

        if (problems.size() == problemsBefore) {
            rows.add(new ParsedRow(rowNumber, date, description, reference.isBlank() ? null : reference, amountMinor));
        }
    }

    private static String cell(CSVRecord record, Integer index) {
        if (index == null || index >= record.size()) {
            return "";
        }
        String value = record.get(index);
        return value == null ? "" : value.trim();
    }

    /** Turns "96.80" into 9680 (for a currency with 2 decimals). Strict on purpose. */
    private static long toMinorUnits(String text, int fractionDigits) {
        if (!text.matches("[+-]?\\d+(\\.\\d+)?")) {
            throw new IllegalArgumentException("is not a plain number like 1234.50");
        }
        BigDecimal value = new BigDecimal(text);
        if (value.scale() > fractionDigits) {
            throw new IllegalArgumentException("has more than " + fractionDigits + " decimal places");
        }
        try {
            return value.movePointRight(fractionDigits).longValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("is too large");
        }
    }

    private static List<String> limit(List<String> problems) {
        if (problems.size() <= MAX_PROBLEMS_SHOWN) {
            return problems;
        }
        List<String> shown = new ArrayList<>(problems.subList(0, MAX_PROBLEMS_SHOWN));
        shown.add("...and " + (problems.size() - MAX_PROBLEMS_SHOWN) + " more problems");
        return shown;
    }
}