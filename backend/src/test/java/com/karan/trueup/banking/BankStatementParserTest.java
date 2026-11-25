package com.karan.trueup.banking;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BankStatementParserTest {

    private static final String HEADER = "date,description,amount,reference";

    private final BankStatementParser parser = new BankStatementParser();

    private static String csv(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    /** Parses a file that we expect to be rejected, and returns the list of problems. */
    private List<String> problemsFor(String csv, String currency) {
        try {
            parser.parse(csv, currency);
        } catch (InvalidStatementException ex) {
            return ex.getProblems();
        }
        throw new AssertionError("expected the statement to be rejected");
    }

    @Test
    void parsesAValidStatement() {
        ParsedStatement statement = parser.parse(csv(HEADER,
                "2026-11-15,STRIPE PAYOUT,96.80,po_demo",
                "2026-11-16,\"ACME, INC\",-12.50,"), "USD");

        assertThat(statement.currency()).isEqualTo("USD");
        assertThat(statement.rows()).hasSize(2);

        ParsedRow first = statement.rows().get(0);
        assertThat(first.rowNumber()).isEqualTo(1);
        assertThat(first.date()).isEqualTo(LocalDate.of(2026, 11, 15));
        assertThat(first.description()).isEqualTo("STRIPE PAYOUT");
        assertThat(first.reference()).isEqualTo("po_demo");
        assertThat(first.amountMinor()).isEqualTo(9680L);

        ParsedRow second = statement.rows().get(1);
        assertThat(second.rowNumber()).isEqualTo(2);
        assertThat(second.description()).as("the comma inside the quotes is kept").isEqualTo("ACME, INC");
        assertThat(second.reference()).isNull();
        assertThat(second.amountMinor()).isEqualTo(-1250L);
    }

    @Test
    void headerNamesIgnoreCaseAndExtraColumnsAreIgnored() {
        ParsedStatement statement = parser.parse(csv(
                " Date , Description ,AMOUNT,Balance",
                "2026-11-15,Deposit,100,5000"), "USD");

        assertThat(statement.rows()).hasSize(1);
        assertThat(statement.rows().get(0).amountMinor()).isEqualTo(10000L);
        assertThat(statement.rows().get(0).reference()).isNull();
    }

    @Test
    void referenceColumnIsOptional() {
        ParsedStatement statement = parser.parse(csv(
                "date,description,amount",
                "2026-11-15,Deposit,10.00"), "USD");

        assertThat(statement.rows().get(0).reference()).isNull();
    }

    @Test
    void rejectsAFileWithoutARequiredColumn() {
        List<String> problems = problemsFor(csv("date,memo,amount", "2026-11-15,Deposit,10.00"), "USD");

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("header").contains("description");
    }

    @Test
    void rejectsAFileWithOnlyAHeader() {
        List<String> problems = problemsFor(csv(HEADER), "USD");

        assertThat(problems).containsExactly("The file has no transactions");
    }

    @Test
    void understandsWholeNumbersAndSignedAmounts() {
        ParsedStatement statement = parser.parse(csv(HEADER,
                "2026-11-15,One,100,",
                "2026-11-15,Two,+5.5,",
                "2026-11-15,Three,-0.07,"), "USD");

        assertThat(statement.rows()).extracting(ParsedRow::amountMinor).containsExactly(10000L, 550L, -7L);
    }

    @Test
    void respectsCurrenciesWithoutDecimals() {
        ParsedStatement yen = parser.parse(csv(HEADER, "2026-11-15,Deposit,1500,"), "JPY");
        assertThat(yen.rows().get(0).amountMinor()).isEqualTo(1500L);

        List<String> problems = problemsFor(csv(HEADER, "2026-11-15,Deposit,15.5,"), "JPY");
        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("0 decimal places");
    }

    @Test
    void rejectsMoreDecimalsThanTheCurrencyAllows() {
        List<String> problems = problemsFor(csv(HEADER, "2026-11-15,Deposit,12.345,"), "USD");

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("Row 1").contains("2 decimal places");
    }

    @Test
    void rejectsThousandsSeparatorsAndWordsInAmounts() {
        List<String> problems = problemsFor(csv(HEADER,
                "2026-11-15,Deposit,\"1,234.50\",",
                "2026-11-15,Deposit,abc,"), "USD");

        assertThat(problems).hasSize(2);
        assertThat(problems.get(0)).contains("Row 1").contains("not a plain number");
        assertThat(problems.get(1)).contains("Row 2").contains("not a plain number");
    }

    @Test
    void rejectsDatesInTheWrongFormat() {
        List<String> problems = problemsFor(csv(HEADER, "15/11/2023,Deposit,10.00,"), "USD");

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("Row 1").contains("date");
    }

    @Test
    void rejectsZeroAmounts() {
        List<String> problems = problemsFor(csv(HEADER, "2026-11-15,Deposit,0.00,"), "USD");

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("Row 1").contains("zero");
    }

    @Test
    void rejectsAnEmptyDescription() {
        List<String> problems = problemsFor(csv(HEADER, "2026-11-15,,10.00,"), "USD");

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("Row 1").contains("description");
    }

    @Test
    void reportsEveryProblemWithItsRowNumber() {
        List<String> problems = problemsFor(csv(HEADER,
                "15/11/2023,Deposit,10.00,",
                "2026-11-15,Deposit,10.00,",
                "2026-11-15,Deposit,abc,"), "USD");

        assertThat(problems).hasSize(2);
        assertThat(problems.get(0)).contains("Row 1");
        assertThat(problems.get(1)).contains("Row 3");
    }

    @Test
    void rejectsAFileThatIsNotValidCsv() {
        List<String> problems = problemsFor(
                "date,description,amount\n2026-11-15,\"never closed,10.00\n", "USD");

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("could not be read");
    }
}