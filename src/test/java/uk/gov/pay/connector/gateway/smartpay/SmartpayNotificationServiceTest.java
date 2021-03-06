package uk.gov.pay.connector.gateway.smartpay;

import org.apache.commons.lang3.StringUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import uk.gov.pay.connector.charge.model.domain.Charge;
import uk.gov.pay.connector.charge.model.domain.ChargeEntityFixture;
import uk.gov.pay.connector.charge.service.ChargeService;
import uk.gov.pay.connector.gateway.processor.ChargeNotificationProcessor;
import uk.gov.pay.connector.gateway.processor.RefundNotificationProcessor;
import uk.gov.pay.connector.gatewayaccount.model.GatewayAccountEntity;
import uk.gov.pay.connector.gatewayaccount.service.GatewayAccountService;
import uk.gov.pay.connector.refund.model.domain.RefundStatus;
import uk.gov.pay.connector.util.TestTemplateResourceLoader;

import java.time.ZonedDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.AUTHORISATION_SUCCESS;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.CAPTURED;
import static uk.gov.pay.connector.gateway.PaymentGatewayName.SMARTPAY;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.SMARTPAY_MULTIPLE_NOTIFICATIONS_DIFFERENT_DATES;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.SMARTPAY_NOTIFICATION_AUTHORISATION;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.SMARTPAY_NOTIFICATION_CAPTURE;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.SMARTPAY_NOTIFICATION_CAPTURE_WITH_UNKNOWN_STATUS;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.SMARTPAY_NOTIFICATION_REFUND;
import static uk.gov.pay.connector.util.TransactionId.randomId;

@RunWith(MockitoJUnitRunner.class)
public class SmartpayNotificationServiceTest {
    private final String originalReference = "original-reference";
    private final String pspReference = "psp-reference";
    private SmartpayNotificationService notificationService;
    @Mock
    private ChargeService mockChargeService;
    @Mock
    private GatewayAccountService mockGatewayAccountService;
    @Mock
    private ChargeNotificationProcessor mockChargeNotificationProcessor;
    @Mock
    private RefundNotificationProcessor mockRefundNotificationProcessor;
    private Charge charge;
    private GatewayAccountEntity gatewayAccountEntity = ChargeEntityFixture.defaultGatewayAccountEntity();

    private static String sampleSmartpayNotification(String location,
                                                     String merchantReference,
                                                     String originalReference,
                                                     String pspReference) {
        return TestTemplateResourceLoader.load(location)
                .replace("{{merchantReference}}", merchantReference)
                .replace("{{originalReference}}", originalReference)
                .replace("{{transactionId}}", originalReference)
                .replace("{{transactionId2}}", originalReference)
                .replace("{{originalReference}}", originalReference)
                .replace("{{pspReference}}", pspReference);
    }

    @Before
    public void setup() {
        notificationService = new SmartpayNotificationService(
                mockChargeService,
                mockChargeNotificationProcessor,
                mockRefundNotificationProcessor,
                mockGatewayAccountService
        );
        charge = Charge.from(ChargeEntityFixture.aValidChargeEntity()
                .withStatus(AUTHORISATION_SUCCESS)
                .build());

        when(mockChargeService.findByProviderAndTransactionIdFromDbOrLedger(SMARTPAY.getName(), originalReference)).thenReturn(Optional.of(charge));
        when(mockGatewayAccountService.getGatewayAccount(charge.getGatewayAccountId())).thenReturn(Optional.of(gatewayAccountEntity));
    }

    @Test
    public void shouldUpdateCharge_WhenNotificationIsForChargeCapture() {
        final String payload = sampleSmartpayNotification(SMARTPAY_NOTIFICATION_CAPTURE,
                randomId(), originalReference, pspReference);

        notificationService.handleNotificationFor(payload);

        verifyNoInteractions(mockRefundNotificationProcessor);
        verify(mockChargeNotificationProcessor).invoke(originalReference, charge, CAPTURED,
                ZonedDateTime.parse("2015-10-08T13:48:30+02:00"));  // from notification-capture.json
    }

    @Test
    public void shouldNotUpdateCharge_WhenNotificationIsForCaptureAndChargeIsHistoric() {
        final String payload = sampleSmartpayNotification(SMARTPAY_NOTIFICATION_CAPTURE,
                randomId(), originalReference, pspReference);

        charge = Charge.from(ChargeEntityFixture.aValidChargeEntity()
                .withStatus(AUTHORISATION_SUCCESS)
                .build());
        charge.setHistoric(true);
        when(mockChargeService.findByProviderAndTransactionIdFromDbOrLedger(SMARTPAY.getName(), originalReference)).thenReturn(Optional.of(charge));

        notificationService.handleNotificationFor(payload);

        verifyNoInteractions(mockRefundNotificationProcessor);
        verifyNoInteractions(mockChargeNotificationProcessor);
    }

    @Test
    public void shouldUpdateRefund_WhenNotificationIsForRefund() {
        final String payload = sampleSmartpayNotification(SMARTPAY_NOTIFICATION_REFUND,
                randomId(), originalReference, pspReference);

        notificationService.handleNotificationFor(payload);

        verifyNoInteractions(mockChargeNotificationProcessor);
        verify(mockRefundNotificationProcessor).invoke(SMARTPAY,
                RefundStatus.REFUNDED, gatewayAccountEntity, pspReference, originalReference, charge);
    }

    @Test
    public void shouldIgnore_WhenNotificationIsForAuthorisation() {
        final String payload = sampleSmartpayNotification(SMARTPAY_NOTIFICATION_AUTHORISATION,
                randomId(), originalReference, pspReference);

        notificationService.handleNotificationFor(payload);

        verifyNoInteractions(mockChargeNotificationProcessor);
        verifyNoInteractions(mockRefundNotificationProcessor);
    }

    @Test
    public void shouldProcessMultipleNotifications() {
        final String payload = sampleSmartpayNotification(SMARTPAY_MULTIPLE_NOTIFICATIONS_DIFFERENT_DATES,
                randomId(), originalReference, pspReference);

        notificationService.handleNotificationFor(payload);

        verify(mockChargeNotificationProcessor).invoke(any(), any(), any(), any());
        verifyNoInteractions(mockRefundNotificationProcessor);
    }

    @Test
    public void shouldIgnoreNotificationWhenStatusIsUnknown() {
        final String payload = sampleSmartpayNotification(SMARTPAY_NOTIFICATION_CAPTURE_WITH_UNKNOWN_STATUS,
                randomId(), originalReference, pspReference);

        notificationService.handleNotificationFor(payload);

        verifyNoInteractions(mockChargeNotificationProcessor);
        verifyNoInteractions(mockRefundNotificationProcessor);
    }

    @Test
    public void shouldNotUpdateChargeOrRefund_WhenTransactionIdIsNotAvailable() {
        final String payload = sampleSmartpayNotification(SMARTPAY_NOTIFICATION_REFUND,
                randomId(), originalReference, StringUtils.EMPTY);

        notificationService.handleNotificationFor(payload);

        verifyNoInteractions(mockChargeNotificationProcessor);
        verifyNoInteractions(mockRefundNotificationProcessor);
    }

    @Test
    public void shouldNotUpdateChargeOrRefund_WhenGatewayAccountEntityIsNotAvailable() {
        final String payload = sampleSmartpayNotification(SMARTPAY_NOTIFICATION_REFUND,
                randomId(), originalReference, pspReference);

        when(mockGatewayAccountService.getGatewayAccount(charge.getGatewayAccountId())).thenReturn(Optional.empty());
        notificationService.handleNotificationFor(payload);

        verifyNoInteractions(mockChargeNotificationProcessor);
        verifyNoInteractions(mockRefundNotificationProcessor);
    }

    @Test
    public void shouldNotUpdateChargeOrRefund_WhenChargeIsNotFoundForTransactionId() {
        final String payload = sampleSmartpayNotification(SMARTPAY_NOTIFICATION_REFUND,
                randomId(), "unknown-transaction-id", pspReference);

        notificationService.handleNotificationFor(payload);

        verifyNoInteractions(mockChargeNotificationProcessor);
        verifyNoInteractions(mockRefundNotificationProcessor);
    }

    @Test
    public void shouldNotUpdateChargeOrRefund_WhenPayloadIsInvalid() {
        final String payload = "invalid-payload";

        notificationService.handleNotificationFor(payload);

        verifyNoInteractions(mockChargeNotificationProcessor);
        verifyNoInteractions(mockRefundNotificationProcessor);
    }
}
