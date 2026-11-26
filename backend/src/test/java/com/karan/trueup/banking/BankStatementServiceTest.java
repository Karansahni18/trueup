package com.karan.trueup.banking;

import com.karan.trueup.common.exception.ConflictException;
import com.karan.trueup.ledger.Account;
import com.karan.trueup.ledger.AccountRepository;
import com.karan.trueup.ledger.AccountType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BankStatementServiceTest {

    private static final String CSV = String.join("\n",
            "date,description,amount,reference",
            "2026-11-15,STRIPE PAYOUT,96.80,po_demo",
            "2026-11-16,\"ACME, INC\",-12.50,") + "\n";

    @Mock
    AccountRepository accountRepository;

    @Mock
    BankStatementImportRepository importRepository;

    @Mock
    BankTransactionRepository transactionRepository;

    @Captor
    ArgumentCaptor<List<BankTransaction>> transactionsCaptor;

    @Captor
    ArgumentCaptor<BankStatementImport> importCaptor;

    BankStatementService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID importId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new BankStatementService(
                accountRepository, importRepository, transactionRepository, new BankStatementParser());
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private void bankAccountExists() {
        Account bank = new Account(tenantId, "1000", "Bank", AccountType.ASSET, "USD");
        when(accountRepository.findByTenantIdAndCode(tenantId, "1000")).thenReturn(Optional.of(bank));
    }

    private void importSavesWithAnId() {
        when(importRepository.saveAndFlush(any(BankStatementImport.class))).thenAnswer(inv -> {
            BankStatementImport saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", importId);
            return saved;
        });
    }

    @Test
    void importsEveryRow_usingTheCurrencyOfTheBankAccount() {
        bankAccountExists();
        importSavesWithAnId();

        BankImportResponse response = service.importStatement(tenantId, "june.csv", bytes(CSV));

        assertThat(response.importId()).isEqualTo(importId);
        assertThat(response.rowCount()).isEqualTo(2);
        assertThat(response.currency()).isEqualTo("USD");

        verify(importRepository).saveAndFlush(importCaptor.capture());
        assertThat(importCaptor.getValue().getFilename()).isEqualTo("june.csv");
        assertThat(importCaptor.getValue().getFileHash()).hasSize(64);

        verify(transactionRepository).saveAll(transactionsCaptor.capture());
        List<BankTransaction> saved = transactionsCaptor.getValue();
        assertThat(saved).extracting(BankTransaction::getAmountMinor).containsExactly(9680L, -1250L);
        assertThat(saved).extracting(BankTransaction::getTenantId).containsOnly(tenantId);
        assertThat(saved).extracting(BankTransaction::getImportId).containsOnly(importId);
        assertThat(saved).extracting(BankTransaction::getCurrency).containsOnly("USD");
    }

    @Test
    void rejectsAFileThatWasAlreadyImported() {
        bankAccountExists();
        when(importRepository.existsByTenantIdAndFileHash(eq(tenantId), anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.importStatement(tenantId, "june.csv", bytes(CSV)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already been imported");

        verify(importRepository, never()).saveAndFlush(any());
        verify(transactionRepository, never()).saveAll(any());
    }

    @Test
    void rejectsABusinessWithoutABankAccount() {
        when(accountRepository.findByTenantIdAndCode(tenantId, "1000")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.importStatement(tenantId, "june.csv", bytes(CSV)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Bank account");

        verifyNoInteractions(importRepository, transactionRepository);
    }

    @Test
    void aFileWithProblems_savesNothing() {
        bankAccountExists();
        String broken = "date,description,amount\n2026-11-15,Deposit,abc\n";

        assertThatThrownBy(() -> service.importStatement(tenantId, "bad.csv", bytes(broken)))
                .isInstanceOf(InvalidStatementException.class)
                .hasMessageContaining("Row 1");

        verify(importRepository, never()).saveAndFlush(any());
        verify(transactionRepository, never()).saveAll(any());
    }

    @Test
    void rejectsEmptyAndOversizedFilesBeforeDoingAnything() {
        assertThatThrownBy(() -> service.importStatement(tenantId, "x.csv", new byte[0]))
                .isInstanceOf(InvalidStatementException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> service.importStatement(
                tenantId, "x.csv", new byte[BankStatementService.MAX_FILE_BYTES + 1]))
                .isInstanceOf(InvalidStatementException.class)
                .hasMessageContaining("larger");

        verifyNoInteractions(accountRepository, importRepository, transactionRepository);
    }

    @Test
    void acceptsAFileThatStartsWithAByteOrderMark() {
        bankAccountExists();
        importSavesWithAnId();

        BankImportResponse response = service.importStatement(tenantId, "excel.csv", bytes("\uFEFF" + CSV));

        assertThat(response.rowCount()).isEqualTo(2);
    }

    @Test
    void aUniqueViolationAtTheDatabase_becomesAConflict() {
        bankAccountExists();
        when(importRepository.saveAndFlush(any(BankStatementImport.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.importStatement(tenantId, "june.csv", bytes(CSV)))
                .isInstanceOf(ConflictException.class);

        verify(transactionRepository, never()).saveAll(any());
    }

    @Test
    void cleansUpTheFilename() {
        bankAccountExists();
        importSavesWithAnId();

        service.importStatement(tenantId, "C:\\bank\\june.csv", bytes(CSV));
        service.importStatement(tenantId, "  ", bytes(CSV));

        verify(importRepository, org.mockito.Mockito.times(2)).saveAndFlush(importCaptor.capture());
        assertThat(importCaptor.getAllValues()).extracting(BankStatementImport::getFilename)
                .containsExactly("june.csv", "statement.csv");
    }
}