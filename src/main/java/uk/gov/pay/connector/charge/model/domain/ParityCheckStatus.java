package uk.gov.pay.connector.charge.model.domain;

public enum ParityCheckStatus {
    SKIPPED,
    EXISTS_IN_LEDGER,
    MISSING_IN_LEDGER,
    DATA_MISMATCH
}
