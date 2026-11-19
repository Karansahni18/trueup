package com.karan.trueup.ledger;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ChartOfAccountsService {

    public static final String DEFAULT_CURRENCY = "USD";

    private final AccountRepository accountRepository;

    public ChartOfAccountsService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional
    public List<Account> createDefaultAccounts(UUID tenantId, String currency) {
        List<Account> accounts = List.of(
                new Account(tenantId, "1000", "Bank", AccountType.ASSET, currency),
                new Account(tenantId, "1100", "Payment Processor Clearing", AccountType.ASSET, currency),
                new Account(tenantId, "3000", "Owner Equity", AccountType.EQUITY, currency),
                new Account(tenantId, "4000", "Sales Revenue", AccountType.REVENUE, currency),
                new Account(tenantId, "5000", "Processing Fees", AccountType.EXPENSE, currency),
                new Account(tenantId, "5100", "Refunds and Chargebacks", AccountType.EXPENSE, currency));
        return accountRepository.saveAll(accounts);
    }
}