package uk.gov.pay.connector.service;

import com.google.inject.Provider;
import fj.data.Either;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.pay.connector.dao.ChargeDao;
import uk.gov.pay.connector.dao.RefundDao;
import uk.gov.pay.connector.model.ExtendedNotification;
import uk.gov.pay.connector.model.Notification;
import uk.gov.pay.connector.model.Notifications;
import uk.gov.pay.connector.model.domain.ChargeEntity;
import uk.gov.pay.connector.model.domain.ChargeStatus;
import uk.gov.pay.connector.model.domain.RefundEntity;
import uk.gov.pay.connector.model.domain.RefundStatus;
import uk.gov.pay.connector.resources.PaymentGatewayName;
import uk.gov.pay.connector.service.transaction.NonTransactionalOperation;
import uk.gov.pay.connector.service.transaction.TransactionContext;
import uk.gov.pay.connector.service.transaction.TransactionFlow;
import uk.gov.pay.connector.service.transaction.TransactionalOperation;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

public class NotificationService {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final ChargeDao chargeDao;
    private final RefundDao refundDao;
    private final PaymentProviders paymentProviders;
    private Provider<TransactionFlow> transactionFlowProvider;

    @Inject
    public NotificationService(ChargeDao chargeDao, RefundDao refundDao, PaymentProviders paymentProviders, Provider<TransactionFlow> transactionFlowProvider) {
        this.chargeDao = chargeDao;
        this.refundDao = refundDao;
        this.paymentProviders = paymentProviders;
        this.transactionFlowProvider = transactionFlowProvider;
    }

    public void acceptNotificationFor(PaymentGatewayName paymentGatewayName, String payload) {
        new Handler(paymentProviders.byName(paymentGatewayName)).execute(payload);
    }

    private class Handler {
        private PaymentProvider paymentProvider;

        public Handler(PaymentProvider paymentProvider) {
            this.paymentProvider = paymentProvider;
        }

        public void execute(String payload) {
            transactionFlowProvider.get()
                    .executeNext(prepare(payload))
                    .executeNext(finish())
                    .complete();
        }

        private <T> NonTransactionalOperation<TransactionContext, List<ExtendedNotification<T>>> prepare(String payload) {
            return context -> {
                Either<String, Notifications<T>> notifications = paymentProvider.parseNotification(payload);

                if (notifications.isLeft()) {
                    logger.error(format("Notification parsing failed: %s", notifications.left().value()));
                    return new ArrayList();
                }

                List<ExtendedNotification<T>> extendedNotifications = notifications.right().value().get().stream()
                        .map(toExtendedNotification())
                        .filter(isValid())
                        .collect(Collectors.toList());

                return extendedNotifications;
            };
        }

        private <T> TransactionalOperation<TransactionContext, Void> finish() {
            return context -> {
                List<ExtendedNotification<T>> notifications = context.get(ArrayList.class);
                notifications.forEach(
                        notification -> {
                            if (!notification.getInternalStatus().isPresent()) {
                                logger.info(format("Notification with transaction id=%s ignored.",
                                        notification.getTransactionId()));
                                return;
                            }

                            Enum newStatus = notification.getInternalStatus().get();

                            if (notification.isOfChargeType()) {
                                updateChargeStatus(notification, newStatus);
                                return;
                            }

                            if (notification.isOfRefundType()) {
                                if (isBlank(notification.getReference())) {
                                    logger.info(format("Notification with transaction id=%s and no reference ignored.",
                                            notification.getTransactionId()));
                                    return;
                                }
                                updateRefundStatus(notification, newStatus);
                                return;
                            }

                            logger.error(format("Notification with transaction id=%s and status=%s is neither of type charge nor refund",
                                    notification.getTransactionId(), notification.getInternalStatus()));
                            return;
                        });
                return null;
            };
        }

        private <T> void updateChargeStatus(ExtendedNotification<T> notification, Enum newStatus) {
            Optional<ChargeEntity> optionalChargeEntity = chargeDao.findByProviderAndTransactionId(
                    paymentProvider.getPaymentGatewayName(), notification.getTransactionId());
            if (!optionalChargeEntity.isPresent()) {
                logger.error(format("Notification with transaction id=%s failed updating charge status to: %s",
                        notification.getTransactionId(), newStatus));
                return;
            }
            ChargeEntity chargeEntity = optionalChargeEntity.get();

            logger.info(format("Notification with transaction id=%s updated charge status - from=%s, to=%s",
                    notification.getTransactionId(), chargeEntity.getStatus(), newStatus));
            chargeEntity.setStatus((ChargeStatus) newStatus);
            chargeDao.mergeAndNotifyStatusHasChanged(chargeEntity);
        }

        private <T> void updateRefundStatus(ExtendedNotification<T> notification, Enum newStatus) {
            Optional<RefundEntity> optionalRefundEntity = refundDao.findByExternalId(notification.getReference());
            if (!optionalRefundEntity.isPresent()) {
                logger.error(format("Notification with transaction id=%s and reference=%s failed updating refund status to: %s",
                        notification.getTransactionId(), notification.getReference(), newStatus));
                return;
            }
            RefundEntity refundEntity = optionalRefundEntity.get();

            refundEntity.setStatus((RefundStatus) newStatus);
            logger.info(format("Notification with transaction id=%s and reference=%s updated refund status - from=%s, to=%s",
                    notification.getTransactionId(), notification.getReference(), refundEntity.getStatus(), newStatus));
        }

        private <T> Function<Notification<T>, ExtendedNotification<T>> toExtendedNotification() {
            return notification ->
                    ExtendedNotification.extend(notification, mapStatus(notification.getStatus()));
        }

        private <T> Predicate<ExtendedNotification<T>> isValid() {
            return notification -> {
                if (!isNotBlank(notification.getTransactionId())) {
                    logger.warn("Notification with no transaction id ignored.");
                    return false;
                }
                return true;
            };
        }

        private <T> Optional<Enum> mapStatus(T status) {
            Status mappedStatus = paymentProvider.getStatusMapper().from(status);

            if (mappedStatus.isUnknown()) {
                logger.warn(format("Notification with unknown status %s.", status));
                return Optional.empty();
            }

            if (mappedStatus.isIgnored()) {
                logger.info(format("Notification with ignored status %s.", status));
                return Optional.empty();
            }
            return Optional.of(mappedStatus.get());
        }
    }
}
