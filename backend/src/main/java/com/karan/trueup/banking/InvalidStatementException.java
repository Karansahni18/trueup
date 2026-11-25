package com.karan.trueup.banking;

import java.util.List;

public class InvalidStatementException extends RuntimeException {

    private final List<String> problems;

    public InvalidStatementException(List<String> problems) {
        super(String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> getProblems() {
        return problems;
    }
}