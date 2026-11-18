package com.karan.trueup.ledger;

public class InvalidLedgerEntryException extends RuntimeException {

    public InvalidLedgerEntryException(String message) {
        super(message);
    }
}