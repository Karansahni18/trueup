package com.karan.trueup.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChartOfAccountsServiceTest {

    @Mock
    AccountRepository accountRepository;

    ChartOfAccountsService service;

    @BeforeEach
    void setUp() {
        service = new ChartOfAccountsService(accountRepository);
        when(accountRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createDefaultAccounts_savesTheStandardAccountsForTheTenantInTheGivenCurrency() {
        UUID tenantId = UUID.randomUUID();

        List<Account> accounts = service.createDefaultAccounts(tenantId, "EUR");

        assertThat(accounts).extracting(Account::getCode)
                .containsExactly("1000", "1100", "3000", "4000", "5000", "5100");
        assertThat(accounts).extracting(Account::getTenantId).containsOnly(tenantId);
        assertThat(accounts).extracting(Account::getCurrency).containsOnly("EUR");
    }

    @Test
    void createDefaultAccounts_coversEveryAccountTypeThatReconciliationNeeds() {
        List<Account> accounts = service.createDefaultAccounts(UUID.randomUUID(), "USD");

        assertThat(accounts).extracting(Account::getAccountType)
                .contains(AccountType.ASSET, AccountType.EQUITY, AccountType.REVENUE, AccountType.EXPENSE);
    }
}