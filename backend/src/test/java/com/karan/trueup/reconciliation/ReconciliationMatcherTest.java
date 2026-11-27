package com.karan.trueup.reconciliation;

import com.karan.trueup.reconciliation.ReconciliationMatcher.BankItem;
import com.karan.trueup.reconciliation.ReconciliationMatcher.LedgerItem;
import com.karan.trueup.reconciliation.ReconciliationMatcher.Match;
import com.karan.trueup.reconciliation.ReconciliationMatcher.MatchRule;
import com.karan.trueup.reconciliation.ReconciliationMatcher.ReconciliationResult;
import com.karan.trueup.reconciliation.ReconciliationMatcher.Unmatched;
import com.karan.trueup.reconciliation.ReconciliationMatcher.UnmatchedReason;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class ReconciliationMatcherTest {

    private final ReconciliationMatcher matcher = new ReconciliationMatcher(3);

    private static BankItem bank(String date, long amount, String description, String reference) {
        return new BankItem(UUID.randomUUID(), LocalDate.parse(date), amount, description, reference);
    }

    private static LedgerItem ledger(String date, long amount, String description) {
        return new LedgerItem(UUID.randomUUID(), LocalDate.parse(date), amount, description);
    }

    private static Unmatched noCandidate(UUID id) {
        return new Unmatched(id, UnmatchedReason.NO_CANDIDATE);
    }

    private static Unmatched ambiguous(UUID id) {
        return new Unmatched(id, UnmatchedReason.AMBIGUOUS);
    }

    @Test
    void matchesByReference_evenWhenTheDatesDiffer() {
        BankItem b = bank("2026-11-15", 9680, "STRIPE TRANSFER", "po_demo");
        LedgerItem l = ledger("2026-11-20", 9680, "Stripe payout po_demo");

        ReconciliationResult result = matcher.match(List.of(b), List.of(l));

        assertThat(result.matches()).containsExactly(new Match(b.id(), l.id(), MatchRule.REFERENCE, 100));
        assertThat(result.unmatchedBank()).isEmpty();
        assertThat(result.unmatchedLedger()).isEmpty();
    }

    @Test
    void aReferenceAloneIsNotEnough_theAmountMustAlsoMatch() {
        BankItem b = bank("2026-11-15", 9680, "STRIPE TRANSFER", "po_demo");
        LedgerItem l = ledger("2026-11-15", 9000, "Stripe payout po_demo");

        ReconciliationResult result = matcher.match(List.of(b), List.of(l));

        assertThat(result.matches()).isEmpty();
        assertThat(result.unmatchedBank()).containsExactly(noCandidate(b.id()));
        assertThat(result.unmatchedLedger()).containsExactly(noCandidate(l.id()));
    }

    @Test
    void aReferenceMustMatchAsAWholeWord() {
        BankItem b = bank("2026-11-15", 500, "TRANSFER", "po_1");
        LedgerItem l = ledger("2026-11-30", 500, "Stripe payout po_10");

        ReconciliationResult result = matcher.match(List.of(b), List.of(l));

        assertThat(result.matches()).isEmpty();
    }

    @Test
    void matchesByAmountAndDate_whenThereIsNoReference() {
        BankItem b = bank("2026-11-15", 5000, "CARD PAYMENT", null);
        LedgerItem l = ledger("2026-11-13", 5000, "Sale");

        ReconciliationResult result = matcher.match(List.of(b), List.of(l));

        assertThat(result.matches()).containsExactly(new Match(b.id(), l.id(), MatchRule.AMOUNT_AND_DATE, 90));
    }

    @Test
    void datesExactlyAtTheToleranceStillMatch() {
        BankItem b = bank("2026-11-15", 5000, "CARD PAYMENT", null);
        LedgerItem l = ledger("2026-11-18", 5000, "Sale");

        assertThat(matcher.match(List.of(b), List.of(l)).matches()).hasSize(1);
    }

    @Test
    void datesBeyondTheToleranceDoNotMatch() {
        BankItem b = bank("2026-11-15", 5000, "CARD PAYMENT", null);
        LedgerItem l = ledger("2026-11-19", 5000, "Sale");

        ReconciliationResult result = matcher.match(List.of(b), List.of(l));

        assertThat(result.matches()).isEmpty();
        assertThat(result.unmatchedBank()).containsExactly(noCandidate(b.id()));
        assertThat(result.unmatchedLedger()).containsExactly(noCandidate(l.id()));
    }

    @Test
    void moneyInAndMoneyOutNeverMatch() {
        BankItem b = bank("2026-11-15", 5000, "DEPOSIT", null);
        LedgerItem l = ledger("2026-11-15", -5000, "Payment");

        assertThat(matcher.match(List.of(b), List.of(l)).matches()).isEmpty();
    }

    @Test
    void twoBankLinesCompetingForOneLedgerLine_areNotGuessed() {
        BankItem b1 = bank("2026-11-15", 500, "COFFEE", null);
        BankItem b2 = bank("2026-11-15", 500, "COFFEE", null);
        LedgerItem l = ledger("2026-11-15", 500, "Coffee");

        ReconciliationResult result = matcher.match(List.of(b1, b2), List.of(l));

        assertThat(result.matches()).isEmpty();
        assertThat(result.unmatchedBank()).containsExactlyInAnyOrder(ambiguous(b1.id()), ambiguous(b2.id()));
        assertThat(result.unmatchedLedger()).containsExactly(ambiguous(l.id()));
    }

    @Test
    void twoAgainstTwoWithTheSameAmount_areNotGuessed() {
        BankItem b1 = bank("2026-11-15", 500, "COFFEE", null);
        BankItem b2 = bank("2026-11-15", 500, "COFFEE", null);
        LedgerItem l1 = ledger("2026-11-15", 500, "Coffee");
        LedgerItem l2 = ledger("2026-11-15", 500, "Coffee");

        ReconciliationResult result = matcher.match(List.of(b1, b2), List.of(l1, l2));

        assertThat(result.matches()).isEmpty();
        assertThat(result.unmatchedBank()).hasSize(2).allMatch(u -> u.reason() == UnmatchedReason.AMBIGUOUS);
        assertThat(result.unmatchedLedger()).hasSize(2).allMatch(u -> u.reason() == UnmatchedReason.AMBIGUOUS);
    }

    @Test
    void referencesCanResolveWhatAmountAndDateCannot() {
        BankItem b1 = bank("2026-11-15", 500, "TRANSFER", "po_1");
        BankItem b2 = bank("2026-11-15", 500, "TRANSFER", "po_2");
        LedgerItem l1 = ledger("2026-11-15", 500, "Stripe payout po_1");
        LedgerItem l2 = ledger("2026-11-15", 500, "Stripe payout po_2");

        ReconciliationResult result = matcher.match(List.of(b1, b2), List.of(l1, l2));

        assertThat(result.matches())
                .extracting(Match::bankId, Match::ledgerId, Match::rule)
                .containsExactlyInAnyOrder(
                        tuple(b1.id(), l1.id(), MatchRule.REFERENCE),
                        tuple(b2.id(), l2.id(), MatchRule.REFERENCE));
        assertThat(result.unmatchedBank()).isEmpty();
        assertThat(result.unmatchedLedger()).isEmpty();
    }

    @Test
    void aLedgerLineIsNeverUsedTwice() {
        BankItem withReference = bank("2026-11-15", 500, "TRANSFER", "po_1");
        BankItem withoutReference = bank("2026-11-15", 500, "TRANSFER", null);
        LedgerItem l = ledger("2026-11-15", 500, "Stripe payout po_1");

        ReconciliationResult result = matcher.match(List.of(withReference, withoutReference), List.of(l));

        assertThat(result.matches()).containsExactly(
                new Match(withReference.id(), l.id(), MatchRule.REFERENCE, 100));
        assertThat(result.unmatchedBank()).containsExactly(noCandidate(withoutReference.id()));
    }

    @Test
    void theNearestDateWinsWhenSeveralLedgerLinesMentionTheReference() {
        BankItem b = bank("2026-11-15", 500, "TRANSFER", "po_1");
        LedgerItem far = ledger("2026-11-10", 500, "Stripe payout po_1");
        LedgerItem near = ledger("2026-11-14", 500, "Stripe payout po_1");

        ReconciliationResult result = matcher.match(List.of(b), List.of(far, near));

        assertThat(result.matches()).containsExactly(new Match(b.id(), near.id(), MatchRule.REFERENCE, 100));
        assertThat(result.unmatchedLedger()).containsExactly(noCandidate(far.id()));
    }

    @Test
    void anEqualDateTieIsNotGuessed() {
        BankItem b = bank("2026-11-15", 500, "TRANSFER", "po_1");
        LedgerItem before = ledger("2026-11-14", 500, "Stripe payout po_1");
        LedgerItem after = ledger("2026-11-16", 500, "Stripe payout po_1");

        ReconciliationResult result = matcher.match(List.of(b), List.of(before, after));

        assertThat(result.matches()).isEmpty();
        assertThat(result.unmatchedBank()).containsExactly(ambiguous(b.id()));
        assertThat(result.unmatchedLedger()).hasSize(2).allMatch(u -> u.reason() == UnmatchedReason.AMBIGUOUS);
    }

    @Test
    void theDemoStatement_matchesThePayoutAndLeavesTwoBankLinesWithoutBooks() {
        BankItem transfer = bank("2023-11-15", 9680, "STRIPE TRANSFER", "po_demo");
        BankItem supplies = bank("2023-11-16", -4520, "OFFICE SUPPLIES, INC", null);
        BankItem fee = bank("2023-11-17", -300, "BANK FEE", null);
        LedgerItem payout = ledger("2023-11-15", 9680, "Stripe payout po_demo");

        ReconciliationResult result = matcher.match(List.of(transfer, supplies, fee), List.of(payout));

        assertThat(result.matches()).containsExactly(
                new Match(transfer.id(), payout.id(), MatchRule.REFERENCE, 100));
        assertThat(result.unmatchedBank()).containsExactlyInAnyOrder(
                noCandidate(supplies.id()), noCandidate(fee.id()));
        assertThat(result.unmatchedLedger()).isEmpty();
    }

    @Test
    void theAnswerDoesNotDependOnTheOrderOfTheInput() {
        List<BankItem> bankItems = new ArrayList<>(List.of(
                bank("2026-11-10", 1000, "A", null),
                bank("2026-11-12", 2000, "B", "ref_b"),
                bank("2026-11-14", 3000, "C", null),
                bank("2026-11-20", 4000, "D", null)));
        List<LedgerItem> ledgerItems = new ArrayList<>(List.of(
                ledger("2026-11-11", 1000, "a"),
                ledger("2026-11-12", 2000, "payout ref_b"),
                ledger("2026-11-14", 3000, "c"),
                ledger("2026-11-30", 5000, "e")));

        ReconciliationResult forward = matcher.match(bankItems, ledgerItems);
        Collections.reverse(bankItems);
        Collections.reverse(ledgerItems);
        ReconciliationResult backward = matcher.match(bankItems, ledgerItems);

        assertThat(backward.matches()).containsExactlyInAnyOrderElementsOf(forward.matches());
        assertThat(backward.unmatchedBank()).containsExactlyInAnyOrderElementsOf(forward.unmatchedBank());
        assertThat(backward.unmatchedLedger()).containsExactlyInAnyOrderElementsOf(forward.unmatchedLedger());
        assertThat(forward.matches()).hasSize(3);
    }

    @Test
    void emptyListsGiveAnEmptyResult() {
        ReconciliationResult result = matcher.match(List.of(), List.of());

        assertThat(result.matches()).isEmpty();
        assertThat(result.unmatchedBank()).isEmpty();
        assertThat(result.unmatchedLedger()).isEmpty();
    }

    @Test
    void aNegativeToleranceIsRejected() {
        assertThatThrownBy(() -> new ReconciliationMatcher(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}