package uk.gov.pay.connector.model.domain;

/**
 * The enum type that should be used to map from and to JSON when
 * dealing with what type (CREDIT, DEBIT, CREDIT_OR_DEBIT) card is
 * used to make a payment. This is also used to calculate corporate
 * surcharges, based on other rules.
 * <p>
 * This should not be confused with {@link CardTypeEntity.SupportedType}
 * which is used to map values from the database to frontend
 * labels. This is used to drive the frontend UI
 */
public enum PayersCardType {
    DEBIT,
    CREDIT,
    DEBIT_OR_CREDIT
}