package com.karan.trueup.ingestion.processing;

import com.karan.trueup.ingestion.Provider;
import com.karan.trueup.ingestion.ProviderEvent;
import com.karan.trueup.ledger.Account;
import com.karan.trueup.ledger.AccountRepository;
import com.karan.trueup.ledger.AccountType;
import com.karan.trueup.ledger.Direction;
import com.karan.trueup.ledger.LedgerService;
import com.karan.trueup.ledger.PostEntryCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StripeLedgerEventHandlerTest {

    @Mock
    AccountRepository accountRepository;

    @Mock
    LedgerService ledgerService;

    StripeLedgerEventHandler handler;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private Account bank;
    private Account clearing;
    private Account sales;

    @BeforeEach
    void setUp() {
        handler = new StripeLedgerEventHandler(accountRepository, ledgerService, JsonMapper.builder().build());
        bank = account("1000", AccountType.ASSET, "USD");
        clearing = account("1100", AccountType.ASSET, "USD");
        sales = account("4000", AccountType.REVENUE, "USD");
    }

    private Account account(String code, AccountType type, String currency) {
        Account account = new Account(tenantId, code, code + " account", type, currency);
        ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
        return account;
    }

    private ProviderEvent event(String type) {
        ProviderEvent event = BeanUtils.instantiateClass(ProviderEvent.class);
        ReflectionTestUtils.setField(event, "id", eventId);
        ReflectionTestUtils.setField(event, "tenantId", tenantId);
        ReflectionTestUtils.setField(event, "provider", Provider.STRIPE);
        ReflectionTestUtils.setField(event, "eventType", type);
        return event;
    }

    private void accountExists(Account account) {
        when(accountRepository.findByTenantIdAndCode(tenantId, account.getCode()))
                .thenReturn(Optional.of(account));
    }

    @Test
    void chargeSucceeded_putsMoneyIntoClearingFromSales() {
        accountExists(clearing);
        accountExists(sales);
        String json = "{\"id\":\"evt_1\",\"type\":\"charge.succeeded\",\"created\":1700000000,"
                + "\"data\":{\"object\":{\"id\":\"ch_1\",\"amount\":10000,\"currency\":\"usd\",\"created\":1700000000}}}";

        handler.handle(event("charge.succeeded"), json);

        ArgumentCaptor<PostEntryCommand> captor = ArgumentCaptor.forClass(PostEntryCommand.class);
        verify(ledgerService).postEntry(captor.capture());
        PostEntryCommand command = captor.getValue();
        assertThat(command.tenantId()).isEqualTo(tenantId);
        assertThat(command.sourceEventId()).isEqualTo(eventId);
        assertThat(command.entryDate()).isEqualTo(LocalDate.of(2023, 11, 14));
        assertThat(command.description()).contains("ch_1");
        assertThat(command.lines()).containsExactly(
                new PostEntryCommand.Line(clearing.getId(), Direction.DEBIT, 10000),
                new PostEntryCommand.Line(sales.getId(), Direction.CREDIT, 10000));
    }

    @Test
    void payoutPaid_putsMoneyIntoBankFromClearing_usingTheArrivalDate() {
        accountExists(bank);
        accountExists(clearing);
        String json = "{\"id\":\"evt_2\",\"type\":\"payout.paid\",\"created\":1700000100,"
                + "\"data\":{\"object\":{\"id\":\"po_1\",\"amount\":9680,\"currency\":\"usd\","
                + "\"created\":1699990000,\"arrival_date\":1700006400}}}";

        handler.handle(event("payout.paid"), json);

        ArgumentCaptor<PostEntryCommand> captor = ArgumentCaptor.forClass(PostEntryCommand.class);
        verify(ledgerService).postEntry(captor.capture());
        PostEntryCommand command = captor.getValue();
        assertThat(command.entryDate()).isEqualTo(LocalDate.of(2023, 11, 15));
        assertThat(command.description()).contains("po_1");
        assertThat(command.lines()).containsExactly(
                new PostEntryCommand.Line(bank.getId(), Direction.DEBIT, 9680),
                new PostEntryCommand.Line(clearing.getId(), Direction.CREDIT, 9680));
    }

    @Test
    void eventInAnotherCurrencyThanTheAccounts_isRejected() {
        accountExists(clearing);
        String json = "{\"id\":\"evt_3\",\"type\":\"charge.succeeded\",\"created\":1700000000,"
                + "\"data\":{\"object\":{\"id\":\"ch_3\",\"amount\":5000,\"currency\":\"eur\"}}}";

        assertThatThrownBy(() -> handler.handle(event("charge.succeeded"), json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EUR");

        verify(ledgerService, never()).postEntry(any());
    }

    @Test
    void eventWithoutAnAmount_isRejected() {
        String json = "{\"id\":\"evt_4\",\"type\":\"charge.succeeded\","
                + "\"data\":{\"object\":{\"id\":\"ch_4\",\"currency\":\"usd\"}}}";

        assertThatThrownBy(() -> handler.handle(event("charge.succeeded"), json))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(accountRepository, ledgerService);
    }

    @Test
    void businessWithoutTheNeededAccount_isRejected() {
        when(accountRepository.findByTenantIdAndCode(tenantId, "1100")).thenReturn(Optional.empty());
        String json = "{\"id\":\"evt_5\",\"type\":\"charge.succeeded\",\"created\":1700000000,"
                + "\"data\":{\"object\":{\"id\":\"ch_5\",\"amount\":5000,\"currency\":\"usd\"}}}";

        assertThatThrownBy(() -> handler.handle(event("charge.succeeded"), json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1100");

        verify(ledgerService, never()).postEntry(any());
    }

    @Test
    void handlesOnlyTheTwoEventsItKnows() {
        assertThat(handler.supports(Provider.STRIPE, "charge.succeeded")).isTrue();
        assertThat(handler.supports(Provider.STRIPE, "payout.paid")).isTrue();
        assertThat(handler.supports(Provider.STRIPE, "charge.refunded")).isFalse();
        assertThat(handler.supports(Provider.STRIPE, "customer.created")).isFalse();
    }
}