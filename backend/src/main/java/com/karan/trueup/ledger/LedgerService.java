package com.karan.trueup.ledger;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class LedgerService {

    private final AccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;

    public LedgerService(AccountRepository accountRepository,
                         JournalEntryRepository journalEntryRepository) {
        this.accountRepository = accountRepository;
        this.journalEntryRepository = journalEntryRepository;
    }

    @Transactional
    public JournalEntry postEntry(PostEntryCommand command) {
        validateShape(command);
        Map<UUID, Account> accounts = loadAccounts(command);
        validateBalanced(command, accounts);

        JournalEntry entry = new JournalEntry(
                command.tenantId(), command.entryDate(), command.description().trim(),
                command.sourceEventId());
        for (PostEntryCommand.Line line : command.lines()) {
            entry.addLine(accounts.get(line.accountId()), line.direction(), line.amountMinor());
        }
        return journalEntryRepository.save(entry);
    }

    private void validateShape(PostEntryCommand command) {
        if (command.tenantId() == null) {
            throw new InvalidLedgerEntryException("Tenant is required");
        }
        if (command.entryDate() == null) {
            throw new InvalidLedgerEntryException("Entry date is required");
        }
        if (command.description() == null || command.description().isBlank()) {
            throw new InvalidLedgerEntryException("Description is required");
        }
        if (command.lines() == null || command.lines().size() < 2) {
            throw new InvalidLedgerEntryException("An entry needs at least two lines");
        }
        for (PostEntryCommand.Line line : command.lines()) {
            if (line.accountId() == null || line.direction() == null) {
                throw new InvalidLedgerEntryException("Every line needs an account and a direction");
            }
            if (line.amountMinor() <= 0) {
                throw new InvalidLedgerEntryException("Line amounts must be positive");
            }
        }
    }

    private Map<UUID, Account> loadAccounts(PostEntryCommand command) {
        Set<UUID> ids = command.lines().stream()
                .map(PostEntryCommand.Line::accountId)
                .collect(Collectors.toSet());
        Map<UUID, Account> found = accountRepository.findAllByTenantIdAndIdIn(command.tenantId(), ids)
                .stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        if (found.size() != ids.size()) {
            throw new InvalidLedgerEntryException("One or more accounts do not exist for this tenant");
        }
        return found;
    }

    private void validateBalanced(PostEntryCommand command, Map<UUID, Account> accounts) {
        Map<String, Long> netByCurrency = new HashMap<>();
        for (PostEntryCommand.Line line : command.lines()) {
            String currency = accounts.get(line.accountId()).getCurrency();
            long signed = line.direction() == Direction.DEBIT ? line.amountMinor() : -line.amountMinor();
            netByCurrency.merge(currency, signed, Math::addExact);
        }
        netByCurrency.forEach((currency, net) -> {
            if (net != 0) {
                throw new InvalidLedgerEntryException("Entry is not balanced for " + currency);
            }
        });
    }
}