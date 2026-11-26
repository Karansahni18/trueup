package com.karan.trueup.banking;

import com.karan.trueup.common.exception.ConflictException;
import com.karan.trueup.ledger.Account;
import com.karan.trueup.ledger.AccountRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class BankStatementService {

    static final int MAX_FILE_BYTES = 2_000_000;

    private static final String BANK_ACCOUNT_CODE = "1000";
    private static final String DEFAULT_FILENAME = "statement.csv";
    private static final String ALREADY_IMPORTED = "This file has already been imported.";

    private final AccountRepository accountRepository;
    private final BankStatementImportRepository importRepository;
    private final BankTransactionRepository transactionRepository;
    private final BankStatementParser parser;

    public BankStatementService(AccountRepository accountRepository,
                                BankStatementImportRepository importRepository,
                                BankTransactionRepository transactionRepository,
                                BankStatementParser parser) {
        this.accountRepository = accountRepository;
        this.importRepository = importRepository;
        this.transactionRepository = transactionRepository;
        this.parser = parser;
    }

    @Transactional
    public BankImportResponse importStatement(UUID tenantId, String filename, byte[] fileBytes) {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new InvalidStatementException(List.of("The file is empty"));
        }
        if (fileBytes.length > MAX_FILE_BYTES) {
            throw new InvalidStatementException(
                    List.of("The file is larger than 2 MB. Please split it into smaller files"));
        }

        String currency = accountRepository.findByTenantIdAndCode(tenantId, BANK_ACCOUNT_CODE)
                .map(Account::getCurrency)
                .orElseThrow(() -> new ConflictException(
                        "This business has no Bank account (code 1000), so the statement currency is unknown."));

        String fileHash = sha256Hex(fileBytes);
        if (importRepository.existsByTenantIdAndFileHash(tenantId, fileHash)) {
            throw new ConflictException(ALREADY_IMPORTED);
        }

        ParsedStatement statement = parser.parse(decode(fileBytes), currency);

        BankStatementImport saved;
        try {
            saved = importRepository.saveAndFlush(new BankStatementImport(
                    tenantId, cleanFilename(filename), fileHash, currency, statement.rows().size()));
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(ALREADY_IMPORTED);
        }

        List<BankTransaction> transactions = statement.rows().stream()
                .map(row -> new BankTransaction(tenantId, saved.getId(), row.date(), row.description(),
                        row.reference(), row.amountMinor(), currency))
                .toList();
        transactionRepository.saveAll(transactions);

        return new BankImportResponse(saved.getId(), saved.getFilename(), currency, transactions.size());
    }

    @Transactional(readOnly = true)
    public List<BankTransactionResponse> latestTransactions(UUID tenantId) {
        return transactionRepository.findTop200ByTenantIdOrderByPostedDateDescIdDesc(tenantId).stream()
                .map(t -> new BankTransactionResponse(t.getId(), t.getPostedDate(), t.getDescription(),
                        t.getReference(), t.getAmountMinor(), t.getCurrency()))
                .toList();
    }

    private static String decode(byte[] fileBytes) {
        String text = new String(fileBytes, StandardCharsets.UTF_8);
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static String cleanFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return DEFAULT_FILENAME;
        }
        String name = filename.trim();
        int lastSeparator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (lastSeparator >= 0) {
            name = name.substring(lastSeparator + 1);
        }
        if (name.isBlank()) {
            return DEFAULT_FILENAME;
        }
        return name.length() > 255 ? name.substring(0, 255) : name;
    }
}