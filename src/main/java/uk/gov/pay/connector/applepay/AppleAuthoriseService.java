package uk.gov.pay.connector.applepay;

import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import io.dropwizard.setup.Environment;
import uk.gov.pay.connector.charge.model.domain.ChargeEntity;
import uk.gov.pay.connector.charge.model.domain.ChargeStatus;
import uk.gov.pay.connector.charge.service.ChargeService;
import uk.gov.pay.connector.gateway.PaymentProviders;
import uk.gov.pay.connector.gateway.model.AuthCardDetails;
import uk.gov.pay.connector.gateway.model.response.BaseAuthoriseResponse;
import uk.gov.pay.connector.gateway.model.response.GatewayResponse;
import uk.gov.pay.connector.paymentprocessor.model.OperationType;
import uk.gov.pay.connector.paymentprocessor.service.CardAuthoriseBaseService;
import uk.gov.pay.connector.paymentprocessor.service.CardExecutorService;

import java.time.format.DateTimeFormatter;
import java.util.Optional;


public class AppleAuthoriseService extends CardAuthoriseBaseService<AppleDecryptedPaymentData> {
    private static final DateTimeFormatter EXPIRY_DATE_FORMAT = DateTimeFormatter.ofPattern("MM/yy");

    @Inject
    AppleAuthoriseService(PaymentProviders paymentProviders, CardExecutorService cardExecutorService, ChargeService chargeService, Environment environment) {
        super(paymentProviders, cardExecutorService, chargeService, environment);
    }

    @Override
    @Transactional
    public ChargeEntity prepareChargeForAuthorisation(String chargeId, AppleDecryptedPaymentData applePaymentData) {
        ChargeEntity charge = chargeService.lockChargeForProcessing(chargeId, OperationType.AUTHORISATION);
        providers.getAppleAuthoriserFor(charge)
                .generateTransactionId()
                .ifPresent(charge::setGatewayTransactionId);
        return charge;
    }

    @Override
    @Transactional
    public void processGatewayAuthorisationResponse(
            String chargeExternalId,
            ChargeStatus oldChargeStatus,
            AppleDecryptedPaymentData applePaymentData,
            GatewayResponse<BaseAuthoriseResponse> operationResponse) {

        logger.info("Processing gateway auth response for apple pay");
        Optional<String> transactionId = extractTransactionId(chargeExternalId, operationResponse);
        ChargeStatus status = extractChargeStatus(operationResponse.getBaseResponse(), operationResponse.getGatewayError());

        AuthCardDetails authCardDetailsToBePersisted = new AuthCardDetails();
        authCardDetailsToBePersisted.setCardHolder(applePaymentData.getPaymentInfo().getCardholderName());
        authCardDetailsToBePersisted.setCardNo(applePaymentData.getPaymentInfo().getLastDigitsCardNumber());
        authCardDetailsToBePersisted.setPayersCardType(applePaymentData.getPaymentInfo().getCardType());
        authCardDetailsToBePersisted.setCardBrand(applePaymentData.getPaymentInfo().getBrand());
        authCardDetailsToBePersisted.setEndDate(applePaymentData.getApplicationExpirationDate().format(EXPIRY_DATE_FORMAT));
        authCardDetailsToBePersisted.setCorporateCard(false);
        ChargeEntity updatedCharge = chargeService.updateChargePostAuthorisation(
                chargeExternalId,
                status,
                transactionId,
                Optional.empty(),
                operationResponse.getSessionIdentifier(),
                authCardDetailsToBePersisted);

        logger.info("Authorisation for {} ({} {}) for {} ({}) - {} .'. {} -> {}",
                updatedCharge.getExternalId(), updatedCharge.getPaymentGatewayName().getName(),
                transactionId.orElse("missing transaction ID"),
                updatedCharge.getGatewayAccount().getAnalyticsId(), updatedCharge.getGatewayAccount().getId(),
                operationResponse, oldChargeStatus, status);

        metricRegistry.counter(String.format(
                "gateway-operations.%s.%s.%s.authorise.result.%s",
                updatedCharge.getGatewayAccount().getGatewayName(),
                updatedCharge.getGatewayAccount().getType(),
                updatedCharge.getGatewayAccount().getId(),
                status.toString())).inc();
    }

    @Override
    protected GatewayResponse<BaseAuthoriseResponse> authorise(ChargeEntity chargeEntity, AppleDecryptedPaymentData applePaymentData) {
        logger.info("Authorising charge for apple pay");
        AuthorisationApplePayGatewayRequest authorisationGatewayRequest = AuthorisationApplePayGatewayRequest.valueOf(chargeEntity, applePaymentData);
        return providers
                .getAppleAuthoriserFor(chargeEntity)
                .authorise(authorisationGatewayRequest);
    }

}
