package com.karan.trueup.reconciliation;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Pairs bank statement lines with ledger lines of the Bank account.
 * Amounts are signed: money coming into the bank is positive, money going out is negative.
 */
public class ReconciliationMatcher {

    public static final int DEFAULT_TOLERANCE_DAYS = 3;
    static final int REFERENCE_CONFIDENCE = 100;
    static final int AMOUNT_AND_DATE_CONFIDENCE = 90;

    /** One line of the bank statement. */
    public record BankItem(UUID id, LocalDate date, long amountMinor, String description, String reference) {
    }

    /** One line of the ledger's Bank account. */
    public record LedgerItem(UUID id, LocalDate date, long amountMinor, String description) {
    }

    public enum MatchRule {
        REFERENCE,
        AMOUNT_AND_DATE
    }

    public record Match(UUID bankId, UUID ledgerId, MatchRule rule, int confidence) {
    }

    public enum UnmatchedReason {
        /** Nothing on the other side has the same amount within the date window. */
        NO_CANDIDATE,
        /** Several possible partners exist, so we refused to guess. */
        AMBIGUOUS
    }

    public record Unmatched(UUID id, UnmatchedReason reason) {
    }

    public record ReconciliationResult(List<Match> matches,
                                       List<Unmatched> unmatchedBank,
                                       List<Unmatched> unmatchedLedger) {
    }

    private final int toleranceDays;

    public ReconciliationMatcher() {
        this(DEFAULT_TOLERANCE_DAYS);
    }

    public ReconciliationMatcher(int toleranceDays) {
        if (toleranceDays < 0) {
            throw new IllegalArgumentException("toleranceDays must not be negative");
        }
        this.toleranceDays = toleranceDays;
    }

    public ReconciliationResult match(List<BankItem> bankItems, List<LedgerItem> ledgerItems) {
        // Sorting first makes the answer independent of the order the lists arrive in.
        List<BankItem> bank = new ArrayList<>(bankItems);
        bank.sort(Comparator.comparing(BankItem::date).thenComparing(BankItem::id));
        List<LedgerItem> ledger = new ArrayList<>(ledgerItems);
        ledger.sort(Comparator.comparing(LedgerItem::date).thenComparing(LedgerItem::id));

        List<Match> matches = new ArrayList<>();

        // Pass 1 removes what it matches from both lists.
        matchByReference(bank, ledger, matches);

        // Pass 2 works on what is left: find every possible pair first, then accept only unique ones.
        Map<UUID, List<LedgerItem>> ledgerCandidatesForBank = new HashMap<>();
        Map<UUID, List<BankItem>> bankCandidatesForLedger = new HashMap<>();
        for (BankItem b : bank) {
            for (LedgerItem l : ledger) {
                if (isCandidate(b, l)) {
                    ledgerCandidatesForBank.computeIfAbsent(b.id(), key -> new ArrayList<>()).add(l);
                    bankCandidatesForLedger.computeIfAbsent(l.id(), key -> new ArrayList<>()).add(b);
                }
            }
        }

        Set<UUID> matchedBank = new HashSet<>();
        Set<UUID> matchedLedger = new HashSet<>();
        for (BankItem b : bank) {
            List<LedgerItem> mine = ledgerCandidatesForBank.getOrDefault(b.id(), List.of());
            if (mine.size() != 1) {
                continue;
            }
            LedgerItem l = mine.get(0);
            if (bankCandidatesForLedger.get(l.id()).size() != 1) {
                continue;
            }
            matches.add(new Match(b.id(), l.id(), MatchRule.AMOUNT_AND_DATE, AMOUNT_AND_DATE_CONFIDENCE));
            matchedBank.add(b.id());
            matchedLedger.add(l.id());
        }

        List<Unmatched> unmatchedBank = new ArrayList<>();
        for (BankItem b : bank) {
            if (!matchedBank.contains(b.id())) {
                unmatchedBank.add(new Unmatched(b.id(), ledgerCandidatesForBank.containsKey(b.id())
                        ? UnmatchedReason.AMBIGUOUS : UnmatchedReason.NO_CANDIDATE));
            }
        }
        List<Unmatched> unmatchedLedger = new ArrayList<>();
        for (LedgerItem l : ledger) {
            if (!matchedLedger.contains(l.id())) {
                unmatchedLedger.add(new Unmatched(l.id(), bankCandidatesForLedger.containsKey(l.id())
                        ? UnmatchedReason.AMBIGUOUS : UnmatchedReason.NO_CANDIDATE));
            }
        }

        return new ReconciliationResult(List.copyOf(matches), List.copyOf(unmatchedBank), List.copyOf(unmatchedLedger));
    }

    private void matchByReference(List<BankItem> bank, List<LedgerItem> ledger, List<Match> matches) {
        Iterator<BankItem> iterator = bank.iterator();
        while (iterator.hasNext()) {
            BankItem b = iterator.next();
            if (b.reference() == null || b.reference().isBlank()) {
                continue;
            }
            Pattern wholeWord = Pattern.compile(
                    "(?<![A-Za-z0-9_])" + Pattern.quote(b.reference().trim()) + "(?![A-Za-z0-9_])",
                    Pattern.CASE_INSENSITIVE);

            List<LedgerItem> candidates = new ArrayList<>();
            for (LedgerItem l : ledger) {
                if (l.amountMinor() == b.amountMinor()
                        && l.description() != null
                        && wholeWord.matcher(l.description()).find()) {
                    candidates.add(l);
                }
            }

            LedgerItem chosen = closestUnique(b, candidates);
            if (chosen != null) {
                matches.add(new Match(b.id(), chosen.id(), MatchRule.REFERENCE, REFERENCE_CONFIDENCE));
                ledger.remove(chosen);
                iterator.remove();
            }
        }
    }

    /** Returns the candidate nearest in date, or null if there is none or two are equally near. */
    private LedgerItem closestUnique(BankItem bank, List<LedgerItem> candidates) {
        long bestDistance = Long.MAX_VALUE;
        LedgerItem best = null;
        boolean tie = false;
        for (LedgerItem l : candidates) {
            long distance = daysBetween(bank.date(), l.date());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = l;
                tie = false;
            } else if (distance == bestDistance) {
                tie = true;
            }
        }
        return tie ? null : best;
    }

    private boolean isCandidate(BankItem bank, LedgerItem ledger) {
        return bank.amountMinor() == ledger.amountMinor()
                && daysBetween(bank.date(), ledger.date()) <= toleranceDays;
    }

    private static long daysBetween(LocalDate a, LocalDate b) {
        return Math.abs(ChronoUnit.DAYS.between(a, b));
    }
}