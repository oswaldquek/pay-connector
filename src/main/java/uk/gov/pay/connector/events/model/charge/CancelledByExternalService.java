package uk.gov.pay.connector.events.model.charge;

import java.time.ZonedDateTime;

public class CancelledByExternalService extends PaymentEventWithoutDetails {
    public CancelledByExternalService(String resourceExternalId, ZonedDateTime timestamp) {
        super(resourceExternalId, timestamp);
    }
}
