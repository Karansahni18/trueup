package com.karan.trueup.ledger;

import com.karan.trueup.TestcontainersConfiguration;
import com.karan.trueup.tenant.Tenant;
import com.karan.trueup.tenant.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LedgerPostingIntegrationTest {

    @Autowired
    LedgerService ledgerService;

    @Autowired
    TenantRepository tenantRepository;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    JournalEntryRepository journalEntryRepository;

    @Autowired
    TransactionTemplate transactionTemplate;

    private UUID tenantId;
    private Account cash;
    private Account sales;

    @BeforeEach
    void createTenantAndAccounts() {
        Tenant tenant = tenantRepository.save(new Tenant("Ledger IT " + UUID.randomUUID()));
        tenantId = tenant.getId();
        cash = accountRepository.save(new Account(tenantId, "1000", "Cash", AccountType.ASSET, "USD"));
        sales = accountRepository.save(new Account(tenantId, "4000", "Sales", AccountType.REVENUE, "USD"));
    }

    @Test
    void postEntry_commitsBalancedEntryTogetherWithItsLines() {
        JournalEntry posted = ledgerService.postEntry(new PostEntryCommand(
                tenantId, LocalDate.of(2026, 11, 18), "Sale of 100 USD",
                List.of(new PostEntryCommand.Line(cash.getId(), Direction.DEBIT, 10000),
                        new PostEntryCommand.Line(sales.getId(), Direction.CREDIT, 10000))));

        Integer lineCount = transactionTemplate.execute(status ->
                journalEntryRepository.findById(posted.getId()).orElseThrow().getLines().size());

        assertThat(lineCount).isEqualTo(2);
    }

    @Test
    void commit_isRejectedByTheDatabaseWhenAnUnbalancedEntryBypassesTheService() {
        JournalEntry entry = new JournalEntry(tenantId, LocalDate.of(2026, 11, 18), "Unbalanced on purpose");
        entry.addLine(cash, Direction.DEBIT, 10000);
        entry.addLine(sales, Direction.CREDIT, 9000);

        assertThatThrownBy(() -> journalEntryRepository.save(entry))
                .hasStackTraceContaining("is not balanced");

        assertThat(journalEntryRepository.findAll())
                .extracting(JournalEntry::getDescription)
                .doesNotContain("Unbalanced on purpose");
    }

    @Test
    void commit_isRejectedByTheDatabaseWhenAnEntryHasNoLines() {
        JournalEntry entry = new JournalEntry(tenantId, LocalDate.of(2026, 11, 18), "Empty on purpose");

        assertThatThrownBy(() -> journalEntryRepository.save(entry))
                .hasStackTraceContaining("at least two lines");

        assertThat(journalEntryRepository.findAll())
                .extracting(JournalEntry::getDescription)
                .doesNotContain("Empty on purpose");
    }
}