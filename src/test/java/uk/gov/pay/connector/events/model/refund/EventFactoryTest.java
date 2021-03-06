package uk.gov.pay.connector.events.model.refund;

import org.hamcrest.core.Is;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import uk.gov.pay.connector.charge.model.ChargeResponse;
import uk.gov.pay.connector.charge.model.domain.Charge;
import uk.gov.pay.connector.charge.model.domain.ChargeEntity;
import uk.gov.pay.connector.charge.model.domain.ChargeEntityFixture;
import uk.gov.pay.connector.charge.model.domain.ChargeStatus;
import uk.gov.pay.connector.charge.service.ChargeService;
import uk.gov.pay.connector.chargeevent.dao.ChargeEventDao;
import uk.gov.pay.connector.chargeevent.model.domain.ChargeEventEntity;
import uk.gov.pay.connector.client.ledger.model.LedgerTransaction;
import uk.gov.pay.connector.events.eventdetails.EmptyEventDetails;
import uk.gov.pay.connector.events.eventdetails.charge.CaptureConfirmedEventDetails;
import uk.gov.pay.connector.events.eventdetails.charge.PaymentCreatedEventDetails;
import uk.gov.pay.connector.events.eventdetails.charge.PaymentNotificationCreatedEventDetails;
import uk.gov.pay.connector.events.eventdetails.charge.RefundAvailabilityUpdatedEventDetails;
import uk.gov.pay.connector.events.eventdetails.refund.RefundCreatedByUserEventDetails;
import uk.gov.pay.connector.events.eventdetails.refund.RefundEventWithGatewayTransactionIdDetails;
import uk.gov.pay.connector.events.model.Event;
import uk.gov.pay.connector.events.model.EventFactory;
import uk.gov.pay.connector.events.model.ResourceType;
import uk.gov.pay.connector.events.model.charge.CancelByExternalServiceSubmitted;
import uk.gov.pay.connector.events.model.charge.CaptureAbandonedAfterTooManyRetries;
import uk.gov.pay.connector.events.model.charge.CaptureConfirmed;
import uk.gov.pay.connector.events.model.charge.CaptureSubmitted;
import uk.gov.pay.connector.events.model.charge.PaymentCreated;
import uk.gov.pay.connector.events.model.charge.PaymentNotificationCreated;
import uk.gov.pay.connector.events.model.charge.RefundAvailabilityUpdated;
import uk.gov.pay.connector.gateway.PaymentGatewayName;
import uk.gov.pay.connector.gateway.PaymentProvider;
import uk.gov.pay.connector.gateway.PaymentProviders;
import uk.gov.pay.connector.gateway.sandbox.SandboxPaymentProvider;
import uk.gov.pay.connector.pact.ChargeEventEntityFixture;
import uk.gov.pay.connector.pact.RefundHistoryEntityFixture;
import uk.gov.pay.connector.queue.statetransition.PaymentStateTransition;
import uk.gov.pay.connector.queue.statetransition.RefundStateTransition;
import uk.gov.pay.connector.queue.statetransition.StateTransition;
import uk.gov.pay.connector.refund.dao.RefundDao;
import uk.gov.pay.connector.refund.model.domain.RefundHistory;
import uk.gov.pay.connector.refund.model.domain.RefundStatus;
import uk.gov.pay.connector.refund.service.RefundService;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.apache.commons.lang3.RandomStringUtils.randomAlphanumeric;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsInstanceOf.instanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class EventFactoryTest {

    private final ChargeEntity charge = ChargeEntityFixture.aValidChargeEntity().build();

    @Mock
    private ChargeService chargeService;
    @Mock
    private RefundDao refundDao;
    @Mock
    private RefundService refundService;
    @Mock
    private ChargeEventDao chargeEventDao;
    @Mock
    private PaymentProviders paymentProviders;
    
    private EventFactory eventFactory;
    
    @Before
    public void setUp() {
        PaymentProvider paymentProvider = new SandboxPaymentProvider();
        when(paymentProviders.byName(any(PaymentGatewayName.class))).thenReturn(paymentProvider);
        
        eventFactory = new EventFactory(chargeService, refundDao, refundService, chargeEventDao, paymentProviders);
    }
    
    @Test
    public void shouldCreateCorrectEventsFromRefundCreatedStateTransition() throws Exception {
        when(chargeService.findCharge(charge.getExternalId())).thenReturn(Optional.of(Charge.from(charge)));
        RefundHistory refundCreatedHistory = RefundHistoryEntityFixture.aValidRefundHistoryEntity()
                .withStatus(RefundStatus.CREATED.getValue())
                .withUserExternalId("user_external_id")
                .withUserEmail("test@example.com")
                .withChargeExternalId(charge.getExternalId())
                .withAmount(charge.getAmount())
                .build();
        when(refundDao.getRefundHistoryByRefundExternalIdAndRefundStatus(
                refundCreatedHistory.getExternalId(),
                refundCreatedHistory.getStatus())
        ).thenReturn(Optional.of(refundCreatedHistory));
        
        
        StateTransition refundStateTransition = new RefundStateTransition(
                refundCreatedHistory.getExternalId(), refundCreatedHistory.getStatus(), RefundCreatedByUser.class
        );
        List<Event> refundEvents = eventFactory.createEvents(refundStateTransition);
        
        assertThat(refundEvents.size(), is(2));
        
        RefundCreatedByUser refundCreatedByUser = (RefundCreatedByUser) refundEvents.stream()
                .filter(e -> ResourceType.REFUND.equals(e.getResourceType()))
                .findFirst().get();

        RefundCreatedByUserEventDetails eventDetails = (RefundCreatedByUserEventDetails) refundCreatedByUser.getEventDetails();
        assertThat(refundCreatedByUser.getParentResourceExternalId(), is(charge.getExternalId()));
        assertThat(eventDetails.getRefundedBy(), is("user_external_id"));
        assertThat(eventDetails.getUserEmail(), is("test@example.com"));
        assertThat(refundCreatedByUser.getResourceType(), is(ResourceType.REFUND));
        assertThat(refundCreatedByUser.getEventDetails(), is(instanceOf(RefundCreatedByUserEventDetails.class)));
        
        RefundAvailabilityUpdated refundAvailabilityUpdated = (RefundAvailabilityUpdated) refundEvents.stream()
                .filter(e -> ResourceType.PAYMENT.equals(e.getResourceType()))
                .findFirst().get();
        assertThat(refundAvailabilityUpdated.getResourceExternalId(), is(charge.getExternalId()));
        assertThat(refundAvailabilityUpdated.getResourceType(), is(ResourceType.PAYMENT));
        assertThat(refundAvailabilityUpdated.getEventDetails(), is(instanceOf(RefundAvailabilityUpdatedEventDetails.class)));
    }

    @Test
    public void shouldCreateCorrectEventsFromRefundSubmittedStateTransition() throws Exception {
        when(chargeService.findCharge(charge.getExternalId())).thenReturn(Optional.of(Charge.from(charge)));
        RefundHistory refundSubmittedHistory = RefundHistoryEntityFixture.aValidRefundHistoryEntity()
                .withStatus(RefundStatus.REFUND_SUBMITTED.getValue())
                .withUserExternalId("user_external_id")
                .withChargeExternalId(charge.getExternalId())
                .withAmount(charge.getAmount())
                .build();
        when(refundDao.getRefundHistoryByRefundExternalIdAndRefundStatus(
                refundSubmittedHistory.getExternalId(),
                refundSubmittedHistory.getStatus())
        ).thenReturn(Optional.of(refundSubmittedHistory));
        
        
        StateTransition refundStateTransition = new RefundStateTransition(
                refundSubmittedHistory.getExternalId(), refundSubmittedHistory.getStatus(), RefundSubmitted.class
        );
        List<Event> refundEvents = eventFactory.createEvents(refundStateTransition);

        
        assertThat(refundEvents.size(), is(1));

        RefundSubmitted refundSubmitted = (RefundSubmitted) refundEvents.get(0);
        
        assertThat(refundSubmitted.getParentResourceExternalId(), is(charge.getExternalId()));
        assertThat(refundSubmitted.getResourceExternalId(), is(refundSubmittedHistory.getExternalId()));
        assertThat(refundSubmitted.getResourceType(), is(ResourceType.REFUND));
        assertThat(refundSubmitted.getEventDetails(), is(instanceOf(RefundEventWithGatewayTransactionIdDetails.class)));
    }

    @Test
    public void shouldCreateCorrectEventsFromRefundSucceededStateTransition() throws Exception {
        when(chargeService.findCharge(charge.getExternalId())).thenReturn(Optional.of(Charge.from(charge)));
        RefundHistory refundSucceededHistory = RefundHistoryEntityFixture.aValidRefundHistoryEntity()
                .withStatus(RefundStatus.REFUNDED.getValue())
                .withUserExternalId("user_external_id")
                .withChargeExternalId(charge.getExternalId())
                .withAmount(charge.getAmount())
                .build();
        when(refundDao.getRefundHistoryByRefundExternalIdAndRefundStatus(
                refundSucceededHistory.getExternalId(),
                refundSucceededHistory.getStatus())
        ).thenReturn(Optional.of(refundSucceededHistory));
        
        
        StateTransition refundStateTransition = new RefundStateTransition(
                refundSucceededHistory.getExternalId(), refundSucceededHistory.getStatus(), RefundSucceeded.class
        );
        List<Event> refundEvents = eventFactory.createEvents(refundStateTransition);

        
        assertThat(refundEvents.size(), is(1));

        RefundSucceeded refundSucceeded = (RefundSucceeded) refundEvents.get(0);

        assertThat(refundSucceeded.getParentResourceExternalId(), is(charge.getExternalId()));
        assertThat(refundSucceeded.getResourceExternalId(), is(refundSucceededHistory.getExternalId()));
        assertThat(refundSucceeded.getResourceType(), is(ResourceType.REFUND));
        assertThat(refundSucceeded.getEventDetails(), is(instanceOf(RefundEventWithGatewayTransactionIdDetails.class)));
    }

    @Test
    public void shouldCreateCorrectEventsFromRefundErrorStateTransition() throws Exception {
        when(chargeService.findCharge(charge.getExternalId())).thenReturn(Optional.of(Charge.from(charge)));
        RefundHistory refundErrorHistory = RefundHistoryEntityFixture.aValidRefundHistoryEntity()
                .withStatus(RefundStatus.REFUND_ERROR.getValue())
                .withUserExternalId("user_external_id")
                .withChargeExternalId(charge.getExternalId())
                .withGatewayTransactionId(randomAlphanumeric(30))
                .withAmount(charge.getAmount())
                .build();
        when(refundDao.getRefundHistoryByRefundExternalIdAndRefundStatus(
                refundErrorHistory.getExternalId(),
                refundErrorHistory.getStatus())
        ).thenReturn(Optional.of(refundErrorHistory));
        
        
        StateTransition refundStateTransition = new RefundStateTransition(
                refundErrorHistory.getExternalId(), refundErrorHistory.getStatus(), RefundError.class
        );
        List<Event> refundEvents = eventFactory.createEvents(refundStateTransition);

        
        assertThat(refundEvents.size(), is(2));

        RefundError refundError = (RefundError) refundEvents.stream()
                .filter(e -> ResourceType.REFUND.equals(e.getResourceType()))
                .findFirst().get();
        assertThat(refundError.getParentResourceExternalId(), is(charge.getExternalId()));
        assertThat(((RefundEventWithGatewayTransactionIdDetails) refundError.getEventDetails()).getGatewayTransactionId(), is(refundErrorHistory.getGatewayTransactionId()));
        assertThat(refundError.getResourceType(), is(ResourceType.REFUND));
        assertThat(refundError.getEventDetails(), is(instanceOf(RefundEventWithGatewayTransactionIdDetails.class)));

        RefundAvailabilityUpdated refundAvailabilityUpdated = (RefundAvailabilityUpdated) refundEvents.stream()
                .filter(e -> ResourceType.PAYMENT.equals(e.getResourceType()))
                .findFirst().get();
        assertThat(refundAvailabilityUpdated.getResourceExternalId(), is(charge.getExternalId()));
        assertThat(refundAvailabilityUpdated.getResourceType(), is(ResourceType.PAYMENT));
        assertThat(refundAvailabilityUpdated.getEventDetails(), is(instanceOf(RefundAvailabilityUpdatedEventDetails.class)));
    }

    @Test
    public void shouldCreatePaymentCreatedEventWithCorrectPayloadForPaymentCreatedStateTransition() throws Exception{
        Long chargeEventEntityId = 100L;
        ChargeEventEntity chargeEventEntity = ChargeEventEntityFixture
                .aValidChargeEventEntity()
                .withCharge(charge)
                .withId(chargeEventEntityId)
                .build();
        when(chargeEventDao.findById(ChargeEventEntity.class, chargeEventEntityId)).thenReturn(
                Optional.of(chargeEventEntity)
        );
        PaymentStateTransition paymentStateTransition = new PaymentStateTransition(chargeEventEntityId, PaymentCreated.class);
        
        List<Event> events = eventFactory.createEvents(paymentStateTransition);

        assertThat(events.size(), is(2));
        PaymentCreated event = (PaymentCreated) events.get(0); 
        assertThat(event, instanceOf(PaymentCreated.class));
        assertThat(event.getEventDetails(), instanceOf(PaymentCreatedEventDetails.class));
        assertThat(event.getResourceExternalId(), Is.is(chargeEventEntity.getChargeEntity().getExternalId()));

        RefundAvailabilityUpdated event2 = (RefundAvailabilityUpdated) events.get(1);
        assertThat(event2, instanceOf(RefundAvailabilityUpdated.class));
        assertThat(event2.getEventDetails(), instanceOf(RefundAvailabilityUpdatedEventDetails.class));
    }

    @Test
    public void shouldCreateEventWithNoPayloadForNonPayloadEventStateTransition() throws Exception {
        Long chargeEventEntityId = 100L;
        ChargeEventEntity chargeEventEntity = ChargeEventEntityFixture
                .aValidChargeEventEntity()
                .withId(chargeEventEntityId)
                .build();
        when(chargeEventDao.findById(ChargeEventEntity.class, chargeEventEntityId)).thenReturn(
                Optional.of(chargeEventEntity)
        );
        PaymentStateTransition paymentStateTransition = new PaymentStateTransition(chargeEventEntityId, CancelByExternalServiceSubmitted.class);

        List<Event> events = eventFactory.createEvents(paymentStateTransition);

        assertThat(events.size(), is(1));
        CancelByExternalServiceSubmitted event = (CancelByExternalServiceSubmitted) events.get(0);
        assertThat(event, instanceOf(CancelByExternalServiceSubmitted.class));
        assertThat(event.getEventDetails(), instanceOf(EmptyEventDetails.class));
    }

    @Test
    public void shouldCreatedARefundAvailabilityUpdatedEventForCaptureConfirmedStateTransition() throws Exception {
        Long chargeEventEntityId = 100L;
        ChargeEventEntity chargeEventEntity = ChargeEventEntityFixture
                .aValidChargeEventEntity()
                .withCharge(charge)
                .withId(chargeEventEntityId)
                .build();
        when(chargeEventDao.findById(ChargeEventEntity.class, chargeEventEntityId)).thenReturn(
                Optional.of(chargeEventEntity)
        );
        PaymentStateTransition paymentStateTransition = new PaymentStateTransition(chargeEventEntityId, CaptureConfirmed.class);

        List<Event> events = eventFactory.createEvents(paymentStateTransition);

        assertThat(events.size(), is(2));
        CaptureConfirmed event1 = (CaptureConfirmed) events.get(0);
        assertThat(event1, instanceOf(CaptureConfirmed.class));
        assertThat(event1.getEventDetails(), instanceOf(CaptureConfirmedEventDetails.class));

        RefundAvailabilityUpdated event2 = (RefundAvailabilityUpdated) events.get(1);
        assertThat(event2, instanceOf(RefundAvailabilityUpdated.class));
        assertThat(event2.getEventDetails(), instanceOf(RefundAvailabilityUpdatedEventDetails.class));
    }

    @Test
    public void shouldCreatedARefundAvailabilityUpdatedEvent_ifPaymentIsHistoric() throws Exception {
        ChargeResponse.RefundSummary refundSummary = new ChargeResponse.RefundSummary();
        refundSummary.setStatus("available");
        LedgerTransaction transaction = new LedgerTransaction();
        transaction.setTransactionId(charge.getExternalId());
        transaction.setPaymentProvider("sandbox");
        transaction.setRefundSummary(refundSummary);
        transaction.setAmount(charge.getAmount());
        transaction.setTotalAmount(charge.getAmount());
        transaction.setCreatedDate(ZonedDateTime.now().toString());
        transaction.setGatewayAccountId(charge.getGatewayAccount().getId().toString());
        when(chargeService.findCharge(transaction.getTransactionId())).thenReturn(Optional.of(Charge.from(transaction)));

        RefundHistory refundErrorHistory = RefundHistoryEntityFixture.aValidRefundHistoryEntity()
                .withStatus(RefundStatus.REFUND_ERROR.getValue())
                .withUserExternalId("user_external_id")
                .withChargeExternalId(charge.getExternalId())
                .withAmount(charge.getAmount())
                .build();
        when(refundDao.getRefundHistoryByRefundExternalIdAndRefundStatus(
                refundErrorHistory.getExternalId(),
                refundErrorHistory.getStatus())
        ).thenReturn(Optional.of(refundErrorHistory));


        StateTransition refundStateTransition = new RefundStateTransition(
                refundErrorHistory.getExternalId(), refundErrorHistory.getStatus(), RefundError.class
        );
        List<Event> refundEvents = eventFactory.createEvents(refundStateTransition);

        assertThat(refundEvents.size(), is(2));

        RefundAvailabilityUpdated refundAvailabilityUpdated = (RefundAvailabilityUpdated) refundEvents.stream()
                .filter(e -> ResourceType.PAYMENT.equals(e.getResourceType()))
                .findFirst().get();
        assertThat(refundAvailabilityUpdated.getResourceExternalId(), is(transaction.getTransactionId()));
        assertThat(refundAvailabilityUpdated.getResourceType(), is(ResourceType.PAYMENT));
        assertThat(refundAvailabilityUpdated.getEventDetails(), is(instanceOf(RefundAvailabilityUpdatedEventDetails.class)));
    }
    
    @Test
    public void shouldCreatedARefundAvailabilityUpdatedEventForCaptureSubmittedStateTransition() throws Exception {
        Long chargeEventEntityId = 100L;
        ChargeEventEntity chargeEventEntity = ChargeEventEntityFixture
                .aValidChargeEventEntity()
                .withCharge(charge)
                .withId(chargeEventEntityId)
                .build();
        when(chargeEventDao.findById(ChargeEventEntity.class, chargeEventEntityId)).thenReturn(
                Optional.of(chargeEventEntity)
        );
        PaymentStateTransition paymentStateTransition = new PaymentStateTransition(chargeEventEntityId, CaptureSubmitted.class);

        List<Event> events = eventFactory.createEvents(paymentStateTransition);

        assertThat(events.size(), is(2));
        CaptureSubmitted event1 = (CaptureSubmitted) events.get(0);
        assertThat(event1, instanceOf(CaptureSubmitted.class));

        RefundAvailabilityUpdated event2 = (RefundAvailabilityUpdated) events.get(1);
        assertThat(event2, instanceOf(RefundAvailabilityUpdated.class));
        assertThat(event2.getEventDetails(), instanceOf(RefundAvailabilityUpdatedEventDetails.class));
    }

    @Test
    public void shouldCreatedARefundAvailabilityUpdatedEventForEventThatLeadsToTerminalState() throws Exception {
        Long chargeEventEntityId = 100L;
        ChargeEventEntity chargeEventEntity = ChargeEventEntityFixture
                .aValidChargeEventEntity()
                .withCharge(charge)
                .withId(chargeEventEntityId)
                .build();
        when(chargeEventDao.findById(ChargeEventEntity.class, chargeEventEntityId)).thenReturn(
                Optional.of(chargeEventEntity)
        );
        PaymentStateTransition paymentStateTransition = new PaymentStateTransition(chargeEventEntityId, CaptureAbandonedAfterTooManyRetries.class);

        List<Event> events = eventFactory.createEvents(paymentStateTransition);

        assertThat(events.size(), is(2));
        CaptureAbandonedAfterTooManyRetries event = (CaptureAbandonedAfterTooManyRetries) events.get(0);
        assertThat(event, instanceOf(CaptureAbandonedAfterTooManyRetries.class));
        assertThat(event.getEventDetails(), instanceOf(EmptyEventDetails.class));

        RefundAvailabilityUpdated event2 = (RefundAvailabilityUpdated) events.get(1);
        assertThat(event2, instanceOf(RefundAvailabilityUpdated.class));
        assertThat(event2.getEventDetails(), instanceOf(RefundAvailabilityUpdatedEventDetails.class));
    }

    @Test
    public void shouldCreatedCorrectEventForPaymentNotificationCreated() throws Exception {
        ChargeEntity charge = ChargeEntityFixture.aValidChargeEntity()
                .withStatus(ChargeStatus.PAYMENT_NOTIFICATION_CREATED)
                .build();
        Long chargeEventEntityId = 100L;
        ChargeEventEntity chargeEventEntity = ChargeEventEntityFixture
                .aValidChargeEventEntity()
                .withCharge(charge)
                .withId(chargeEventEntityId)
                .build();
        when(chargeEventDao.findById(ChargeEventEntity.class, chargeEventEntityId)).thenReturn(
                Optional.of(chargeEventEntity)
        );
        PaymentStateTransition paymentStateTransition = new PaymentStateTransition(chargeEventEntityId, PaymentNotificationCreated.class);
        List<Event> events = eventFactory.createEvents(paymentStateTransition);

        assertThat(events.size(), is(1));

        PaymentNotificationCreated event = (PaymentNotificationCreated) events.get(0);
        assertThat(event, is(instanceOf(PaymentNotificationCreated.class)));

        assertThat(event.getEventDetails(), instanceOf(PaymentNotificationCreatedEventDetails.class));
        assertThat(event.getResourceExternalId(), is(chargeEventEntity.getChargeEntity().getExternalId()));

        PaymentNotificationCreatedEventDetails eventDetails = (PaymentNotificationCreatedEventDetails) event.getEventDetails();
        assertThat(eventDetails.getGatewayTransactionId(), is(charge.getGatewayTransactionId()));
    }
}
