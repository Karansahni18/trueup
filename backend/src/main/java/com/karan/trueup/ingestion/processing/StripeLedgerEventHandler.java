package com.karan.trueup.ingestion.processing;

import com.karan.trueup.ingestion.Provider;
import com.karan.trueup.ingestion.ProviderEvent;
import com.karan.trueup.ledger.Account;
import com.karan.trueup.ledger.AccountRepository;
import com.karan.trueup.ledger.Direction;
import com.karan.trueup.ledger.LedgerService;
import com.karan.trueup.ledger.PostEntryCommand;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Component
public class StripeLedgerEventHandler implements ProviderEventHandler {

    static final String CHARGE_SUCCEEDED = "charge.succeeded";
    static final String PAYOUT_PAID = "payout.paid";

    private static final String BANK = "1000";
    private static final String CLEARING = "1100";
    private static final String SALES = "4000";

    private final AccountRepository accountRepository;
    private final LedgerService ledgerService;
    private final JsonMapper jsonMapper;

    public StripeLedgerEventHandler(AccountRepository accountRepository,
                                    LedgerService ledgerService,
                                    JsonMapper jsonMapper) {
        this.accountRepository = accountRepository;
        this.ledgerService = ledgerService;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public boolean supports(Provider provider, String eventType) {
        return provider == Provider.STRIPE
                && (CHARGE_SUCCEEDED.equals(eventType) || PAYOUT_PAID.equals(eventType));
    }

    @Override
    public void handle(ProviderEvent event, String payloadJson) {
        StripeEventPayload payload = jsonMapper.readValue(payloadJson, StripeEventPayload.class);
        StripeEventPayload.StripeObject object = payload.data() == null ? null : payload.data().object();
        if (object == null || object.id() == null || object.amount() == null || object.currency() == null) {
            throw new IllegalArgumentException(
                    "Stripe " + event.getEventType() + " event is missing the id, amount or currency");
        }
        String currency = object.currency().toUpperCase(Locale.ROOT);

        switch (event.getEventType()) {
            case CHARGE_SUCCEEDED -> postEntry(event, currency, object.amount(),
                    dateOf(object.created(), payload.created()),
                    "Stripe charge " + object.id(), CLEARING, SALES);
            case PAYOUT_PAID -> postEntry(event, currency, object.amount(),
                    dateOf(object.arrivalDate(), object.created(), payload.created()),
                    "Stripe payout " + object.id(), BANK, CLEARING);
            default -> throw new IllegalStateException("Unsupported event type " + event.getEventType());
        }
    }

    private void postEntry(ProviderEvent event, String currency, long amount, LocalDate date,
                           String description, String debitCode, String creditCode) {
        UUID tenantId = event.getTenantId();
        Account debit = findAccount(tenantId, debitCode, currency);
        Account credit = findAccount(tenantId, creditCode, currency);

        ledgerService.postEntry(new PostEntryCommand(tenantId, date, description,
                List.of(new PostEntryCommand.Line(debit.getId(), Direction.DEBIT, amount),
                        new PostEntryCommand.Line(credit.getId(), Direction.CREDIT, amount)),
                event.getId()));
    }

    private Account findAccount(UUID tenantId, String code, String currency) {
        Account account = accountRepository.findByTenantIdAndCode(tenantId, code)
                .orElseThrow(() -> new IllegalStateException("This business has no account " + code));
        if (!account.getCurrency().equals(currency)) {
            throw new IllegalStateException("Account " + code + " is in " + account.getCurrency()
                    + " but the event is in " + currency);
        }
        return account;
    }

    /** Uses the first date we find, converted from Stripe's "seconds since 1970" to a calendar day. */
    private static LocalDate dateOf(Long... candidates) {
        for (Long seconds : candidates) {
            if (seconds != null) {
                return Instant.ofEpochSecond(seconds).atZone(ZoneOffset.UTC).toLocalDate();
            }
        }
        throw new IllegalArgumentException("The Stripe event has no date");
    }
}