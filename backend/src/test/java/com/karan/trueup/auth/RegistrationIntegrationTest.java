package com.karan.trueup.auth;

import com.karan.trueup.TestcontainersConfiguration;
import com.karan.trueup.auth.dto.RegisterRequest;
import com.karan.trueup.auth.dto.RegisterResponse;
import com.karan.trueup.ledger.Account;
import com.karan.trueup.ledger.AccountRepository;
import com.karan.trueup.ledger.Direction;
import com.karan.trueup.ledger.JournalEntry;
import com.karan.trueup.ledger.JournalEntryRepository;
import com.karan.trueup.ledger.LedgerService;
import com.karan.trueup.ledger.PostEntryCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RegistrationIntegrationTest {

    @Autowired
    AuthService authService;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    LedgerService ledgerService;

    @Autowired
    JournalEntryRepository journalEntryRepository;

    @Autowired
    TransactionTemplate transactionTemplate;

    private RegisterResponse registerNewBusiness() {
        return authService.register(new RegisterRequest(
                "Chart IT " + UUID.randomUUID(),
                "owner-" + UUID.randomUUID() + "@chart.test",
                "Str0ngPass!"));
    }

    @Test
    void register_createsTheDefaultChartOfAccountsForTheNewTenant() {
        RegisterResponse response = registerNewBusiness();

        List<Account> accounts = accountRepository.findAllByTenantId(response.tenantId());

        assertThat(accounts).extracting(Account::getCode)
                .containsExactlyInAnyOrder("1000", "1100", "3000", "4000", "5000", "5100");
        assertThat(accounts).extracting(Account::getCurrency).containsOnly("USD");
    }

    @Test
    void defaultAccountsCanPostAProcessorSaleWithItsFee() {
        UUID tenantId = registerNewBusiness().tenantId();
        Account clearing = accountRepository.findByTenantIdAndCode(tenantId, "1100").orElseThrow();
        Account fees = accountRepository.findByTenantIdAndCode(tenantId, "5000").orElseThrow();
        Account sales = accountRepository.findByTenantIdAndCode(tenantId, "4000").orElseThrow();

        JournalEntry posted = ledgerService.postEntry(new PostEntryCommand(
                tenantId, LocalDate.of(2026, 11, 19), "Stripe sale 100.00 USD, fee 3.20",
                List.of(new PostEntryCommand.Line(clearing.getId(), Direction.DEBIT, 9680),
                        new PostEntryCommand.Line(fees.getId(), Direction.DEBIT, 320),
                        new PostEntryCommand.Line(sales.getId(), Direction.CREDIT, 10000))));

        Integer lineCount = transactionTemplate.execute(status ->
                journalEntryRepository.findById(posted.getId()).orElseThrow().getLines().size());

        assertThat(lineCount).isEqualTo(3);
    }
}