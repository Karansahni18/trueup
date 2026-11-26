package com.karan.trueup.banking;

import com.karan.trueup.auth.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class BankStatementController {

    private final BankStatementService service;

    public BankStatementController(BankStatementService service) {
        this.service = service;
    }

    @PostMapping("/bank-imports")
    public ResponseEntity<BankImportResponse> importStatement(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(name = "filename", defaultValue = "statement.csv") String filename,
            @RequestBody(required = false) byte[] body) {
        BankImportResponse response = service.importStatement(user.tenantId(), filename, body);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/bank-transactions")
    public List<BankTransactionResponse> latestTransactions(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.latestTransactions(user.tenantId());
    }
}