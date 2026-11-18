package com.karan.trueup.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock
    AccountRepository accountRepository;

    @Mock
    JournalEntryRepository journalEntryRepository;

    LedgerService ledgerService;

    private final UUID tenantId = UUID.randomUUID();
    private Account cashUsd;
    private Account salesUsd;
    private Account cashEur;
    private Account salesEur;

    @BeforeEach
    void setUp() {
        ledgerService = new LedgerService(accountRepository, journalEntryRepository);
        cashUsd = account("1000", AccountType.ASSET, "USD");
        salesUsd = account("4000", AccountType.REVENUE, "USD");
        cashEur = account("1010", AccountType.ASSET, "EUR");
        salesEur = account("4010", AccountType.REVENUE, "EUR");
    }

    private Account account(String code, AccountType type, String currency) {
        Account account = new Account(tenantId, code, code + " account", type, currency);
        ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
        return account;
    }

    private PostEntryCommand.Line line(Account account, Direction direction, long amount) {
        return new PostEntryCommand.Line(account.getId(), direction, amount);
    }

    private PostEntryCommand command(PostEntryCommand.Line... lines) {
        return new PostEntryCommand(tenantId, LocalDate.of(2026, 11, 17), "  Sale  ", List.of(lines));
    }

    @Test
    void postEntry_savesBalancedEntryWithCurrencyTakenFromAccounts() {
        when(accountRepository.findAllByTenantIdAndIdIn(eq(tenantId), anyCollection()))
                .thenReturn(List.of(cashUsd, salesUsd));
        when(journalEntryRepository.save(any(JournalEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        JournalEntry entry = ledgerService.postEntry(command(
                line(cashUsd, Direction.DEBIT, 10000),
                line(salesUsd, Direction.CREDIT, 10000)));

        assertThat(entry.getDescription()).isEqualTo("Sale");
        assertThat(entry.getLines()).hasSize(2);
        assertThat(entry.getLines()).extracting(JournalLine::getCurrency).containsOnly("USD");
        assertThat(entry.getLines()).extracting(JournalLine::getAmountMinor).containsExactly(10000L, 10000L);
    }

    @Test
    void postEntry_rejectsUnbalancedEntry() {
        when(accountRepository.findAllByTenantIdAndIdIn(eq(tenantId), anyCollection()))
                .thenReturn(List.of(cashUsd, salesUsd));

        assertThatThrownBy(() -> ledgerService.postEntry(command(
                line(cashUsd, Direction.DEBIT, 10000),
                line(salesUsd, Direction.CREDIT, 9000))))
                .isInstanceOf(InvalidLedgerEntryException.class)
                .hasMessageContaining("not balanced");

        verify(journalEntryRepository, never()).save(any());
    }

    @Test
    void postEntry_rejectsFewerThanTwoLines() {
        assertThatThrownBy(() -> ledgerService.postEntry(command(
                line(cashUsd, Direction.DEBIT, 10000))))
                .isInstanceOf(InvalidLedgerEntryException.class)
                .hasMessageContaining("at least two lines");

        verifyNoInteractions(accountRepository, journalEntryRepository);
    }

    @Test
    void postEntry_rejectsNonPositiveAmount() {
        assertThatThrownBy(() -> ledgerService.postEntry(command(
                line(cashUsd, Direction.DEBIT, 0),
                line(salesUsd, Direction.CREDIT, 0))))
                .isInstanceOf(InvalidLedgerEntryException.class)
                .hasMessageContaining("positive");

        verifyNoInteractions(accountRepository, journalEntryRepository);
    }

    @Test
    void postEntry_rejectsAccountThatDoesNotExistForTenant() {
        when(accountRepository.findAllByTenantIdAndIdIn(eq(tenantId), anyCollection()))
                .thenReturn(List.of(cashUsd));

        assertThatThrownBy(() -> ledgerService.postEntry(command(
                line(cashUsd, Direction.DEBIT, 10000),
                line(salesUsd, Direction.CREDIT, 10000))))
                .isInstanceOf(InvalidLedgerEntryException.class)
                .hasMessageContaining("do not exist");

        verify(journalEntryRepository, never()).save(any());
    }

    @Test
    void postEntry_acceptsEntryBalancedSeparatelyInEachCurrency() {
        when(accountRepository.findAllByTenantIdAndIdIn(eq(tenantId), anyCollection()))
                .thenReturn(List.of(cashUsd, salesUsd, cashEur, salesEur));
        when(journalEntryRepository.save(any(JournalEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        JournalEntry entry = ledgerService.postEntry(command(
                line(cashUsd, Direction.DEBIT, 10000),
                line(salesUsd, Direction.CREDIT, 10000),
                line(cashEur, Direction.DEBIT, 5000),
                line(salesEur, Direction.CREDIT, 5000)));

        assertThat(entry.getLines()).hasSize(4);
    }

    @Test
    void postEntry_rejectsEntryThatBalancesOnlyAcrossCurrencies() {
        when(accountRepository.findAllByTenantIdAndIdIn(eq(tenantId), anyCollection()))
                .thenReturn(List.of(cashUsd, salesEur));

        assertThatThrownBy(() -> ledgerService.postEntry(command(
                line(cashUsd, Direction.DEBIT, 10000),
                line(salesEur, Direction.CREDIT, 10000))))
                .isInstanceOf(InvalidLedgerEntryException.class)
                .hasMessageContaining("not balanced");

        verify(journalEntryRepository, never()).save(any());
    }
}