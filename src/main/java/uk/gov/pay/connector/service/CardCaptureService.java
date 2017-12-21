package uk.gov.pay.connector.service;

import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.ImmutableList;
import com.google.inject.persist.Transactional;
import io.dropwizard.setup.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.pay.connector.dao.ChargeDao;
import uk.gov.pay.connector.dao.ChargeEventDao;
import uk.gov.pay.connector.dao.PaymentRequestDao;
import uk.gov.pay.connector.exception.ChargeExpiredRuntimeException;
import uk.gov.pay.connector.exception.ChargeNotFoundRuntimeException;
import uk.gov.pay.connector.exception.ConflictRuntimeException;
import uk.gov.pay.connector.exception.IllegalStateRuntimeException;
import uk.gov.pay.connector.exception.OperationAlreadyInProgressRuntimeException;
import uk.gov.pay.connector.model.CaptureGatewayRequest;
import uk.gov.pay.connector.model.domain.ChargeEntity;
import uk.gov.pay.connector.model.domain.ChargeStatus;
import uk.gov.pay.connector.model.domain.GatewayAccountEntity;
import uk.gov.pay.connector.model.gateway.GatewayResponse;

import javax.inject.Inject;
import javax.persistence.OptimisticLockException;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static uk.gov.pay.connector.model.domain.ChargeStatus.AUTHORISATION_SUCCESS;
import static uk.gov.pay.connector.model.domain.ChargeStatus.CAPTURED;
import static uk.gov.pay.connector.model.domain.ChargeStatus.CAPTURE_APPROVED;
import static uk.gov.pay.connector.model.domain.ChargeStatus.CAPTURE_APPROVED_RETRY;
import static uk.gov.pay.connector.model.domain.ChargeStatus.CAPTURE_ERROR;
import static uk.gov.pay.connector.model.domain.ChargeStatus.CAPTURE_SUBMITTED;
import static uk.gov.pay.connector.model.domain.ChargeStatus.fromString;

public class CardCaptureService implements TransactionalGatewayOperation<BaseCaptureResponse> {

    private static final Logger LOG = LoggerFactory.getLogger(CardCaptureService.class);
    private static List<ChargeStatus> legalStatuses = ImmutableList.of(
            AUTHORISATION_SUCCESS,
            CAPTURE_APPROVED,
            CAPTURE_APPROVED_RETRY
    );
    protected final ChargeDao chargeDao;
    protected final ChargeEventDao  chargeEventDao;
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    protected final ChargeStatusUpdater chargeStatusUpdater;

    private final UserNotificationService userNotificationService;
    private final PaymentProviders providers;
    protected MetricRegistry metricRegistry;


    @Inject
    public CardCaptureService(ChargeDao chargeDao, ChargeEventDao chargeEventDao, PaymentProviders providers, UserNotificationService userNotificationService, Environment environment, PaymentRequestDao paymentRequestDao, ChargeStatusUpdater chargeStatusUpdater) {
        this.chargeDao = chargeDao;
        this.chargeEventDao = chargeEventDao;
        this.providers = providers;
        this.metricRegistry = environment.metrics();
        this.chargeStatusUpdater = chargeStatusUpdater;
        this.userNotificationService = userNotificationService;
    }

    public GatewayResponse<BaseCaptureResponse> doCapture(String externalId) {
        ChargeEntity charge;
        try {
            charge = preOperation(externalId);
        } catch (OptimisticLockException e) {
            LOG.info("OptimisticLockException in doCapture for charge external_id=" + externalId);
            throw new ConflictRuntimeException(externalId);
        }
        GatewayResponse<BaseCaptureResponse> operationResponse = operation(charge);
        return postOperation(externalId, operationResponse);
    }

    @Transactional
    @Override
    public ChargeEntity preOperation(String chargeId) {
        return chargeDao.findByExternalId(chargeId)
                .map(chargeEntity -> preOperation(chargeEntity, OperationType.CAPTURE, legalStatuses, ChargeStatus.CAPTURE_READY))
                .orElseThrow(() -> new ChargeNotFoundRuntimeException(chargeId));
    }

    @Transactional
    public ChargeEntity markChargeAsCaptureApproved(String externalId) {
        return chargeDao.findByExternalId(externalId).map(charge -> {
            if (!AUTHORISATION_SUCCESS.getValue().equals(charge.getStatus())) {
                logger.error("Charge is not in the expect state of AUTHORISATION_SUCCESS to be marked as CAPTURE_APPROVED [charge_external_id={}, charge_status={}]",
                        charge.getExternalId(), charge.getStatus());
                throw new IllegalStateRuntimeException(charge.getExternalId());
            }

            logger.info("CAPTURE_APPROVED for charge [charge_external_id={}]", externalId);
            charge.setStatus(CAPTURE_APPROVED);
            chargeEventDao.persistChargeEventOf(charge, Optional.empty());
            chargeStatusUpdater.updateChargeTransactionStatus(charge.getExternalId(), CAPTURE_APPROVED);
            return charge;
        }).orElseThrow(() -> new ChargeNotFoundRuntimeException(externalId));
    }

    @Transactional
    public void markChargeAsCaptureError(String chargeId) {
        logger.error("CAPTURE_ERROR for charge [charge_external_id={}] - reached maximum number of capture attempts",
                chargeId);
        chargeDao.findByExternalId(chargeId).ifPresent(chargeEntity -> {
            chargeEntity.setStatus(CAPTURE_ERROR);
            chargeEventDao.persistChargeEventOf(chargeEntity, Optional.empty());
            chargeStatusUpdater.updateChargeTransactionStatus(chargeEntity.getExternalId(), CAPTURE_ERROR);
        });
    }

    @Override
    public GatewayResponse<BaseCaptureResponse> operation(ChargeEntity chargeEntity) {
        return getPaymentProviderFor(chargeEntity)
                .capture(CaptureGatewayRequest.valueOf(chargeEntity));
    }

    @Transactional
    @Override
    public GatewayResponse<BaseCaptureResponse> postOperation(String chargeId, GatewayResponse<BaseCaptureResponse> operationResponse) {
        return chargeDao.findByExternalId(chargeId)
                .map(chargeEntity -> {

                    ChargeStatus nextStatus = determineNextStatus(operationResponse);

                    String transactionId = operationResponse.getBaseResponse()
                            .map(BaseCaptureResponse::getTransactionId).orElse("");

                    logger.info("Capture for {} ({} {}) for {} ({}) - {} .'. {} -> {}",
                            chargeEntity.getExternalId(), chargeEntity.getPaymentGatewayName().getName(), chargeEntity.getGatewayTransactionId(),
                            chargeEntity.getGatewayAccount().getAnalyticsId(), chargeEntity.getGatewayAccount().getId(),
                            operationResponse, chargeEntity.getStatus(), nextStatus);

                    chargeEntity.setStatus(nextStatus);

                    if (isBlank(transactionId)) {
                        logger.warn("Card capture response received with no transaction id. - charge_external_id={}", chargeId);
                    }

                    GatewayAccountEntity account = chargeEntity.getGatewayAccount();

                    metricRegistry.counter(String.format("gateway-operations.%s.%s.%s.capture.result.%s", account.getGatewayName(), account.getType(), account.getId(), nextStatus.toString())).inc();

                    chargeEventDao.persistChargeEventOf(chargeEntity, Optional.empty());
                    chargeStatusUpdater.updateChargeTransactionStatus(chargeEntity.getExternalId(), nextStatus);

                    if (operationResponse.isSuccessful()) {
                        userNotificationService.notifyPaymentSuccessEmail(chargeEntity);
                    }

                    //for sandbox, immediately move from CAPTURE_SUBMITTED to CAPTURED, as there will be no external notification
                    if (chargeEntity.getPaymentGatewayName() == PaymentGatewayName.SANDBOX) {
                        chargeEntity.setStatus(CAPTURED);
                        ZonedDateTime gatewayEventTime = ZonedDateTime.now();
                        chargeEventDao.persistChargeEventOf(chargeEntity, Optional.of(gatewayEventTime));
                        chargeStatusUpdater.updateChargeTransactionStatus(chargeEntity.getExternalId(), CAPTURED, gatewayEventTime);
                    }

                    return operationResponse;
                })
                .orElseThrow(() -> new ChargeNotFoundRuntimeException(chargeId));
    }

    private ChargeStatus determineNextStatus(GatewayResponse<BaseCaptureResponse> operationResponse) {
        if (operationResponse.isSuccessful()) {
            return CAPTURE_SUBMITTED;
        } else {
            return operationResponse.getGatewayError()
                    .map(timeoutError -> CAPTURE_APPROVED_RETRY)
                    .orElse(CAPTURE_ERROR);
        }
    }

    public ChargeEntity preOperation(ChargeEntity chargeEntity, OperationType operationType, List<ChargeStatus> legalStatuses, ChargeStatus lockingStatus) {

        GatewayAccountEntity gatewayAccount = chargeEntity.getGatewayAccount();

        logger.info("Card pre-operation - charge_external_id={}, charge_status={}, account_id={}, amount={}, operation_type={}, provider={}, provider_type={}, locking_status={}",
                chargeEntity.getExternalId(),
                fromString(chargeEntity.getStatus()),
                gatewayAccount.getId(),
                chargeEntity.getAmount(),
                operationType.getValue(),
                gatewayAccount.getGatewayName(),
                gatewayAccount.getType(),
                lockingStatus);

        if (chargeEntity.hasStatus(ChargeStatus.EXPIRED)) {
            throw new ChargeExpiredRuntimeException(operationType.getValue(), chargeEntity.getExternalId());
        }

        if (!chargeEntity.hasStatus(legalStatuses)) {
            if (chargeEntity.hasStatus(lockingStatus)) {
                throw new OperationAlreadyInProgressRuntimeException(operationType.getValue(), chargeEntity.getExternalId());
            }
            logger.error("Charge is not in a legal status to do the pre-operation - charge_external_id={}, status={}, legal_states={}",
                    chargeEntity.getExternalId(), chargeEntity.getStatus(), getLegalStatusNames(legalStatuses));
            throw new IllegalStateRuntimeException(chargeEntity.getExternalId());
        }

        chargeEntity.setStatus(lockingStatus);
        chargeStatusUpdater.updateChargeTransactionStatus(chargeEntity.getExternalId(), lockingStatus);
        return chargeEntity;
    }

    private String getLegalStatusNames(List<ChargeStatus> legalStatuses) {
        return legalStatuses.stream().map(ChargeStatus::toString).collect(Collectors.joining(", "));
    }

    public PaymentProviderOperations getPaymentProviderFor(ChargeEntity chargeEntity) {
        return providers.byName(chargeEntity.getPaymentGatewayName());
    }

    public enum OperationType {
        CAPTURE("Capture"),
        AUTHORISATION("Authorisation"),
        AUTHORISATION_3DS("3D Secure Response Authorisation"),
        CANCELLATION("Cancellation");

        private String value;

        OperationType(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }
}
