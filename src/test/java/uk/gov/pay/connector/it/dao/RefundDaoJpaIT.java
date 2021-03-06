package uk.gov.pay.connector.it.dao;

import org.apache.commons.lang.math.RandomUtils;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;
import uk.gov.pay.connector.charge.model.domain.ChargeStatus;
import uk.gov.pay.connector.events.dao.EmittedEventDao;
import uk.gov.pay.connector.refund.dao.RefundDao;
import uk.gov.pay.connector.refund.model.domain.RefundEntity;
import uk.gov.pay.connector.refund.model.domain.RefundHistory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.time.ZoneOffset.UTC;
import static java.time.ZonedDateTime.now;
import static org.apache.commons.lang3.RandomStringUtils.randomAlphanumeric;
import static org.apache.commons.lang3.RandomUtils.nextLong;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.oneOf;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertNotNull;
import static uk.gov.pay.connector.charge.model.domain.ParityCheckStatus.MISSING_IN_LEDGER;
import static uk.gov.pay.connector.events.EmittedEventFixture.anEmittedEventEntity;
import static uk.gov.pay.connector.it.dao.DatabaseFixtures.withDatabaseTestHelper;
import static uk.gov.pay.connector.it.resources.ChargeEventsResourceIT.SUBMITTED_BY;
import static uk.gov.pay.connector.matcher.RefundsMatcher.aRefundMatching;
import static uk.gov.pay.connector.model.domain.RefundEntityFixture.userEmail;
import static uk.gov.pay.connector.model.domain.RefundEntityFixture.userExternalId;
import static uk.gov.pay.connector.refund.model.domain.RefundStatus.CREATED;
import static uk.gov.pay.connector.refund.model.domain.RefundStatus.REFUNDED;
import static uk.gov.pay.connector.refund.model.domain.RefundStatus.REFUND_ERROR;
import static uk.gov.pay.connector.refund.model.domain.RefundStatus.REFUND_SUBMITTED;

public class RefundDaoJpaIT extends DaoITestBase {

    private RefundDao refundDao;
    private DatabaseFixtures.TestAccount sandboxAccount;
    private DatabaseFixtures.TestCharge chargeTestRecord;
    private DatabaseFixtures.TestRefund refundTestRecord;
    private String refundGatewayTransactionId;
    private EmittedEventDao emittedEventDao;

    @Before
    public void setUp() throws Exception {
        setup();
        refundDao = env.getInstance(RefundDao.class);
        emittedEventDao = env.getInstance(EmittedEventDao.class);
        databaseTestHelper.deleteAllCardTypes();

        DatabaseFixtures.TestAccount testAccount = DatabaseFixtures
                .withDatabaseTestHelper(databaseTestHelper)
                .aTestAccount();

        DatabaseFixtures.TestCharge testCharge = DatabaseFixtures
                .withDatabaseTestHelper(databaseTestHelper)
                .aTestCharge()
                .withTestAccount(testAccount)
                .withChargeStatus(ChargeStatus.CAPTURED);

        DatabaseFixtures.TestRefund testRefund = DatabaseFixtures
                .withDatabaseTestHelper(databaseTestHelper)
                .aTestRefund()
                .withGatewayTransactionId(randomAlphanumeric(10))
                .withChargeExternalId(testCharge.getExternalChargeId())
                .withTestCharge(testCharge);

        this.sandboxAccount = testAccount.insert();
        this.chargeTestRecord = testCharge.insert();
        this.refundTestRecord = testRefund.insert();
        refundGatewayTransactionId = randomAlphanumeric(30);
    }

    @Test
    public void findById_shouldFindRefund() {
        Optional<RefundEntity> refundEntityOptional = refundDao.findById(RefundEntity.class, refundTestRecord.getId());

        assertThat(refundEntityOptional.isPresent(), is(true));

        RefundEntity refundEntity = refundEntityOptional.get();

        assertNotNull(refundEntity.getId());
        assertThat(refundEntity.getAmount(), is(refundTestRecord.getAmount()));
        assertThat(refundEntity.getStatus(), is(refundTestRecord.getStatus()));
        assertThat(refundEntity.getGatewayTransactionId(), is(refundTestRecord.getGatewayTransactionId()));
        assertThat(refundEntity.getCreatedDate(), is(refundTestRecord.getCreatedDate()));
        assertNotNull(refundEntity.getVersion());
    }

    @Test
    public void findById_shouldNotFindRefund() {
        long noExistingRefundId = 0L;
        assertThat(refundDao.findById(RefundEntity.class, noExistingRefundId).isPresent(), is(false));
    }

    @Test
    public void findByChargeExternalIdAndGatewayTransactionId_shouldFindRefund() {
        Optional<RefundEntity> refundEntityOptional = refundDao.findByChargeExternalIdAndGatewayTransactionId(chargeTestRecord.getExternalChargeId(), refundTestRecord.getGatewayTransactionId());

        assertThat(refundEntityOptional.isPresent(), is(true));

        RefundEntity refundEntity = refundEntityOptional.get();

        assertNotNull(refundEntity.getId());
        assertThat(refundEntity.getExternalId(), is(refundTestRecord.getExternalRefundId()));
        assertThat(refundEntity.getAmount(), is(refundTestRecord.getAmount()));
        assertThat(refundEntity.getStatus(), is(refundTestRecord.getStatus()));
        assertThat(refundEntity.getCreatedDate(), is(refundTestRecord.getCreatedDate()));
        assertThat(refundEntity.getGatewayTransactionId(), is(refundTestRecord.getGatewayTransactionId()));
        assertNotNull(refundEntity.getVersion());
    }

    @Test
    public void findByChargeExternalIdAndGatewayTransactionId_shouldNotFindRefundIfChargeExternalIdDoesNotMatch() {

        Optional<RefundEntity> refundEntityOptional = refundDao.findByChargeExternalIdAndGatewayTransactionId("charge-externalid-00", refundTestRecord.getGatewayTransactionId());

        assertThat(refundEntityOptional, is(Optional.empty()));
    }

    @Test
    public void findByChargeExternalIdAndGatewayTransactionId_shouldNotFindRefundIfGatewayTransactionIdDoesNotMatch() {
        String noExistingRefundTransactionId = "refund_0";
        assertThat(refundDao.findByChargeExternalIdAndGatewayTransactionId(chargeTestRecord.getExternalChargeId(),
                noExistingRefundTransactionId).isPresent(), is(false));
    }

    @Test
    public void persist_shouldCreateARefund() {
        RefundEntity refundEntity = new RefundEntity(100L, userExternalId, userEmail, chargeTestRecord.getExternalChargeId());
        refundEntity.setStatus(REFUND_SUBMITTED);
        refundEntity.setGatewayTransactionId(refundGatewayTransactionId);
        refundEntity.setChargeExternalId(chargeTestRecord.getExternalChargeId());

        refundDao.persist(refundEntity);

        assertNotNull(refundEntity.getId());

        List<Map<String, Object>> refundByIdFound = databaseTestHelper.getRefund(refundEntity.getId());

        assertThat(refundByIdFound.size(), is(1));
        assertThat(refundByIdFound, hasItems(aRefundMatching(refundEntity.getExternalId(), is(refundGatewayTransactionId),
                refundEntity.getChargeExternalId(), refundEntity.getAmount(), refundEntity.getStatus().getValue())));
        assertThat(refundByIdFound.get(0), hasEntry("created_date", java.sql.Timestamp.from(refundEntity.getCreatedDate().toInstant())));
        assertThat(refundByIdFound.get(0), hasEntry("user_external_id", userExternalId));
        assertThat(refundByIdFound.get(0), hasEntry("user_email", userEmail));
        assertThat(refundByIdFound.get(0), hasEntry("charge_external_id", chargeTestRecord.getExternalChargeId()));
    }

    @Test
    public void persist_shouldSearchHistoryByChargeExternalId() {
        RefundEntity refundEntity = new RefundEntity(100L, userExternalId, userEmail, chargeTestRecord.getExternalChargeId());
        refundEntity.setStatus(REFUND_SUBMITTED);
        refundEntity.setGatewayTransactionId(refundGatewayTransactionId);

        refundDao.persist(refundEntity);
        List<RefundHistory> refundHistoryList = refundDao.searchHistoryByChargeExternalId(chargeTestRecord.getExternalChargeId());

        assertThat(refundHistoryList.size(), is(1));

        RefundHistory refundHistory = refundHistoryList.get(0);
        assertThat(refundHistory.getId(), is(refundEntity.getId()));
        assertThat(refundHistory.getExternalId(), is(refundEntity.getExternalId()));
        assertThat(refundHistory.getAmount(), is(refundEntity.getAmount()));
        assertThat(refundHistory.getStatus(), is(refundEntity.getStatus()));
        assertThat(refundHistory.getUserEmail(), is(refundEntity.getUserEmail()));
        assertThat(refundHistory.getCreatedDate(), is(refundEntity.getCreatedDate()));
        assertThat(refundHistory.getVersion(), is(refundEntity.getVersion()));
        assertThat(refundEntity.getUserExternalId(), is(userExternalId));
        assertThat(refundEntity.getUserEmail(), is(refundEntity.getUserEmail()));
        assertThat(refundHistory.getUserExternalId(), is(refundEntity.getUserExternalId()));
        assertThat(refundHistory.getGatewayTransactionId(), is(refundEntity.getGatewayTransactionId()));
        assertThat(refundHistory.getChargeExternalId(), is(chargeTestRecord.getExternalChargeId()));
    }

    @Test
    public void shouldSearchAllHistoryStatusTypesByChargeId() {
        RefundEntity refundEntity = new RefundEntity(100L, userExternalId, userEmail, chargeTestRecord.getExternalChargeId());

        refundEntity.setGatewayTransactionId(refundGatewayTransactionId);
        refundEntity.setStatus(CREATED);
        refundDao.persist(refundEntity);

        refundEntity.setStatus(REFUND_SUBMITTED);
        refundDao.merge(refundEntity);

        List<RefundHistory> refundHistoryList = refundDao.searchAllHistoryByChargeExternalId(chargeTestRecord.getExternalChargeId());

        assertThat(refundHistoryList.size(), is(2));
    }

    // CREATED to REFUND_SUBMITTED happens synchronously so not needed to return history for CREATED status
    // Causing issues since gateway transaction id is not populated for CREATED is also being removed as is detected as
    // duplicated.
    @Test
    public void persist_shouldSearchHistoryByChargeId_IgnoringRefundStatusWithStateCreated() {
        RefundEntity refundEntity1 = new RefundEntity(100L, userExternalId, userEmail, chargeTestRecord.getExternalChargeId());
        refundEntity1.setStatus(CREATED);
        refundDao.persist(refundEntity1);

        RefundEntity refundEntity = new RefundEntity(100L, userExternalId, userEmail, chargeTestRecord.getExternalChargeId());
        refundEntity.setStatus(REFUND_SUBMITTED);
        refundEntity.setGatewayTransactionId(refundGatewayTransactionId);
        refundDao.persist(refundEntity);

        List<RefundHistory> refundHistoryList = refundDao.searchHistoryByChargeExternalId(chargeTestRecord.getExternalChargeId());

        assertThat(refundHistoryList.size(), is(1));

        RefundHistory refundHistory = refundHistoryList.get(0);
        assertThat(refundHistory.getId(), is(refundEntity.getId()));
        assertThat(refundHistory.getExternalId(), is(refundEntity.getExternalId()));
        assertThat(refundHistory.getAmount(), is(refundEntity.getAmount()));
        assertThat(refundHistory.getStatus(), is(refundEntity.getStatus()));
        assertThat(refundHistory.getCreatedDate(), is(refundEntity.getCreatedDate()));
        assertThat(refundHistory.getVersion(), is(refundEntity.getVersion()));
        assertThat(refundHistory.getGatewayTransactionId(), is(refundEntity.getGatewayTransactionId()));
        assertThat(userExternalId, is(refundEntity.getUserExternalId()));
        assertThat(refundHistory.getUserEmail(), is(refundEntity.getUserEmail()));
        assertThat(refundHistory.getUserExternalId(), is(refundEntity.getUserExternalId()));
    }

    @Test
    public void getRefundHistoryByRefundExternalIdAndRefundStatus_shouldReturnResultCorrectly() {
        RefundEntity refundEntity = new RefundEntity(100L, userExternalId, userEmail, chargeTestRecord.getExternalChargeId());
        refundEntity.setStatus(CREATED);
        refundEntity.setGatewayTransactionId(refundGatewayTransactionId);

        refundDao.persist(refundEntity);
        Optional<RefundHistory> mayBeRefundHistory = refundDao.getRefundHistoryByRefundExternalIdAndRefundStatus(refundEntity.getExternalId(), CREATED);

        RefundHistory refundHistory = mayBeRefundHistory.get();

        assertThat(refundHistory.getId(), is(refundEntity.getId()));
        assertThat(refundHistory.getExternalId(), is(refundEntity.getExternalId()));
        assertThat(refundHistory.getAmount(), is(refundEntity.getAmount()));
        assertThat(refundHistory.getStatus(), is(refundEntity.getStatus()));
        assertThat(refundHistory.getCreatedDate(), is(refundEntity.getCreatedDate()));
        assertThat(refundHistory.getVersion(), is(refundEntity.getVersion()));
        assertThat(refundHistory.getUserExternalId(), is(refundEntity.getUserExternalId()));
        assertThat(refundHistory.getUserEmail(), is(refundEntity.getUserEmail()));
        assertThat(refundHistory.getGatewayTransactionId(), is(refundEntity.getGatewayTransactionId()));
    }

    @Test
    public void getRefundHistoryByDateRangeShouldReturnResultCorrectly() {

        ZonedDateTime historyDate = ZonedDateTime.parse("2016-01-01T00:00:00Z");

        DatabaseFixtures.TestAccount testAccount = withDatabaseTestHelper(databaseTestHelper).aTestAccount().insert();
        DatabaseFixtures.TestCharge testCharge = withDatabaseTestHelper(databaseTestHelper).aTestCharge().withTestAccount(testAccount).insert();
        DatabaseFixtures.TestRefund testRefund = withDatabaseTestHelper(databaseTestHelper)
                .aTestRefund()
                .withTestCharge(testCharge)
                .withType(REFUNDED)
                .withCreatedDate(now())
                .withUserEmail(userEmail)
                .withChargeExternalId(testCharge.getExternalChargeId())
                .insert();

        withDatabaseTestHelper(databaseTestHelper)
                .aTestRefundHistory(testRefund)
                .insert(REFUND_SUBMITTED, "ref-2", historyDate.plusMinutes(10), historyDate.plusMinutes(10), SUBMITTED_BY, userEmail)
                .insert(CREATED, "ref-1", historyDate, historyDate, SUBMITTED_BY, userEmail)
                .insert(REFUNDED, "history-tobe-excluded", historyDate.minusDays(10), historyDate.minusDays(10))
                .insert(REFUNDED, "history-tobe-excluded", historyDate.plusHours(1), historyDate.plusHours(1), SUBMITTED_BY, userEmail);

        List<RefundHistory> refundHistoryList = refundDao.getRefundHistoryByDateRange(historyDate, historyDate.plusMinutes(11), 1, 2);

        assertThat(refundHistoryList.size(), is(2));

        RefundHistory refundHistory = refundHistoryList.get(0);
        assertThat(refundHistory.getStatus(), is(CREATED));
        assertThat(refundHistory.getGatewayTransactionId(), is("ref-1"));
        assertThat(refundHistory.getId(), is(testRefund.getId()));
        assertThat(refundHistory.getUserEmail(), is(testRefund.getUserEmail()));

        refundHistory = refundHistoryList.get(1);
        assertThat(refundHistory.getStatus(), is(REFUND_SUBMITTED));
        assertThat(refundHistory.getGatewayTransactionId(), is("ref-2"));
        assertThat(refundHistory.getId(), is(testRefund.getId()));
        assertThat(refundHistory.getUserEmail(), is(testRefund.getUserEmail()));
    }

    @Test
    public void findByChargeExternalIdShouldReturnAListOfRefunds() {
        DatabaseFixtures
                .withDatabaseTestHelper(databaseTestHelper)
                .aTestRefund()
                .withGatewayTransactionId(refundGatewayTransactionId)
                .withChargeExternalId(chargeTestRecord.getExternalChargeId())
                .withTestCharge(chargeTestRecord).insert();

        List<RefundEntity> refundEntityList = refundDao.findRefundsByChargeExternalId(chargeTestRecord.externalChargeId);
        assertThat(refundEntityList.size(), is(2));
        assertThat(refundEntityList.get(0).getChargeExternalId(), is(chargeTestRecord.externalChargeId));
        assertThat(refundEntityList.get(1).getChargeExternalId(), is(chargeTestRecord.externalChargeId));
    }

    @Test
    public void findMaxId_returnsTheMaximumId() {
        RefundEntity refundEntity = new RefundEntity(100L, userExternalId, userEmail, chargeTestRecord.getExternalChargeId());
        refundEntity.setId(nextLong());
        refundEntity.setStatus(REFUND_SUBMITTED.getValue());
        refundDao.persist(refundEntity);

        RefundEntity refundEntity2 = new RefundEntity(100L, userExternalId, userEmail, chargeTestRecord.getExternalChargeId());
        refundEntity2.setId(refundEntity.getId() - 100L);
        refundEntity2.setStatus(REFUND_SUBMITTED.getValue());
        refundDao.persist(refundEntity2);

        assertThat(refundDao.findMaxId(), Matchers.is(refundEntity.getId()));
    }

    @Test
    public void findRefundToExpunge_shouldReturnRefundReadyForExpunging() {
        String chargeExternalId = randomAlphanumeric(26);
        RefundEntity refundToExpunge = new RefundEntity(100L, userExternalId, userEmail, chargeExternalId);
        refundToExpunge.setStatus(REFUNDED);
        refundToExpunge.setCreatedDate(ZonedDateTime.now(UTC).minusDays(7));
        refundDao.persist(refundToExpunge);

        RefundEntity refundToExclude = new RefundEntity(100L, userExternalId, userEmail, chargeExternalId);
        refundToExclude.setStatus(REFUNDED);
        refundToExclude.setCreatedDate(ZonedDateTime.now(UTC));
        refundDao.persist(refundToExclude);

        RefundEntity refundParityCheckedRecentlyAndToBeExcluded = new RefundEntity(100L, userExternalId, userEmail, chargeExternalId);
        refundParityCheckedRecentlyAndToBeExcluded.setStatus(REFUNDED);
        refundParityCheckedRecentlyAndToBeExcluded.setCreatedDate(ZonedDateTime.now(UTC).minusDays(17));
        refundParityCheckedRecentlyAndToBeExcluded.setParityCheckDate(now(ZoneId.of("UTC")).minusDays(2));
        refundParityCheckedRecentlyAndToBeExcluded.setParityCheckStatus(MISSING_IN_LEDGER);
        refundDao.persist(refundParityCheckedRecentlyAndToBeExcluded);

        Optional<RefundEntity> mayBeRefundToExpunge = refundDao.findRefundToExpunge(5, 7);

        assertThat(mayBeRefundToExpunge.isPresent(), Matchers.is(true));
        RefundEntity refundEntity = mayBeRefundToExpunge.get();
        assertThat(refundEntity.getId(), Matchers.is(refundToExpunge.getId()));
        assertThat(refundEntity.getExternalId(), Matchers.is(refundToExpunge.getExternalId()));

        refundDao.expungeRefund(refundToExpunge.getExternalId());
    }

    @Test
    public void findRefundToExpunge_shouldReturnParityCheckedRefundIfEligible() {
        String chargeExternalId = randomAlphanumeric(26);
        RefundEntity refundParityCheckedRecentlyAndNotEligibleForExpunging = new RefundEntity(100L, userExternalId, userEmail, chargeExternalId);
        refundParityCheckedRecentlyAndNotEligibleForExpunging.setStatus(REFUNDED);
        refundParityCheckedRecentlyAndNotEligibleForExpunging.setCreatedDate(ZonedDateTime.now(UTC).minusDays(10));
        refundParityCheckedRecentlyAndNotEligibleForExpunging.setParityCheckDate(now(ZoneId.of("UTC")).minusDays(2));
        refundParityCheckedRecentlyAndNotEligibleForExpunging.setParityCheckStatus(MISSING_IN_LEDGER);
        refundDao.persist(refundParityCheckedRecentlyAndNotEligibleForExpunging);

        RefundEntity refundParityCheckedPreviouslyAndIsNowEligibleForExpunging = new RefundEntity(100L, userExternalId, userEmail, chargeExternalId);
        refundParityCheckedPreviouslyAndIsNowEligibleForExpunging.setStatus(REFUNDED);
        refundParityCheckedPreviouslyAndIsNowEligibleForExpunging.setCreatedDate(ZonedDateTime.now(UTC).minusDays(10));
        refundParityCheckedPreviouslyAndIsNowEligibleForExpunging.setParityCheckDate(now(ZoneId.of("UTC")).minusDays(8));
        refundParityCheckedPreviouslyAndIsNowEligibleForExpunging.setParityCheckStatus(MISSING_IN_LEDGER);
        refundDao.persist(refundParityCheckedPreviouslyAndIsNowEligibleForExpunging);

        Optional<RefundEntity> mayBeRefundToExpunge = refundDao.findRefundToExpunge(5, 7);

        assertThat(mayBeRefundToExpunge.isPresent(), Matchers.is(true));
        RefundEntity refundEntity = mayBeRefundToExpunge.get();
        assertThat(refundEntity.getId(), Matchers.is(refundParityCheckedPreviouslyAndIsNowEligibleForExpunging.getId()));
        assertThat(refundEntity.getExternalId(), Matchers.is(refundParityCheckedPreviouslyAndIsNowEligibleForExpunging.getExternalId()));

        refundDao.expungeRefund(refundEntity.getExternalId());
    }

    @Test
    public void findRefundToExpunge_shouldNotReturnRefundIfChargeStillExists() {
        RefundEntity refund = new RefundEntity(100L, userExternalId, userEmail, chargeTestRecord.getExternalChargeId());
        refund.setStatus(REFUNDED);
        refund.setCreatedDate(ZonedDateTime.now(UTC).minusDays(10));
        refundDao.persist(refund);

        Optional<RefundEntity> mayBeRefundToExpunge = refundDao.findRefundToExpunge(5, 7);

        assertThat(mayBeRefundToExpunge.isPresent(), Matchers.is(false));
    }

    @Test
    public void expungeRefund_shouldExpungeRefundRelatedRecordsCorrectly() {
        RefundEntity refundToExpunge = new RefundEntity(100L, userExternalId, userEmail, chargeTestRecord.getExternalChargeId());
        refundToExpunge.setStatus(REFUNDED);
        refundDao.persist(refundToExpunge);

        RefundEntity refundToNotBeExpunged = new RefundEntity(100L, userExternalId, userEmail, chargeTestRecord.getExternalChargeId());
        refundToNotBeExpunged.setStatus(REFUND_ERROR);
        refundDao.persist(refundToNotBeExpunged);

        emittedEventDao.persist(anEmittedEventEntity()
                .withResourceExternalId(refundToExpunge.getExternalId())
                .withResourceType("refund")
                .withId(RandomUtils.nextLong())
                .build());

        // assert data is as expected
        Optional<RefundEntity> mayBeRefundEntity = refundDao.findByExternalId(refundToExpunge.getExternalId());
        List<RefundHistory> refundHistoryList = refundDao.searchHistoryByChargeExternalId(chargeTestRecord.getExternalChargeId());
        boolean containsEmittedEventForRefundExternalId = databaseTestHelper.containsEmittedEventWithExternalId(refundToExpunge.getExternalId());

        assertThat(containsEmittedEventForRefundExternalId, is(true));
        assertThat(mayBeRefundEntity.isPresent(), Matchers.is(true));
        assertThat(refundHistoryList.size(), Matchers.is(2));

        // act
        refundDao.expungeRefund(refundToExpunge.getExternalId());

        mayBeRefundEntity = refundDao.findByExternalId(refundToExpunge.getExternalId());
        refundHistoryList = refundDao.searchHistoryByChargeExternalId(chargeTestRecord.getExternalChargeId());
        containsEmittedEventForRefundExternalId = databaseTestHelper.containsEmittedEventWithExternalId(refundToExpunge.getExternalId());

        assertThat(mayBeRefundEntity.isPresent(), Matchers.is(false));
        assertThat(refundHistoryList.size(), Matchers.is(1));
        assertThat(containsEmittedEventForRefundExternalId, is(false));
    }

    @Test
    public void getRefundHistoryByRefundExternalId_shouldReturnResultsCorrectly() {
        RefundEntity refundEntity = new RefundEntity(100L, userExternalId, userEmail, chargeTestRecord.getExternalChargeId());
        refundEntity.setStatus(CREATED);
        refundDao.persist(refundEntity);

        refundEntity.setStatus(REFUND_ERROR);
        refundDao.merge(refundEntity);

        RefundEntity refundThatShouldNotBeReturned = new RefundEntity(100L, userExternalId, userEmail, chargeTestRecord.getExternalChargeId());
        refundThatShouldNotBeReturned.setStatus(CREATED);
        refundDao.persist(refundThatShouldNotBeReturned);

        List<RefundHistory> refundHistoryList = refundDao.getRefundHistoryByRefundExternalId(refundEntity.getExternalId());

        assertThat(refundHistoryList.size(), is(2));
        assertThat(refundHistoryList.get(0).getExternalId(), is(refundEntity.getExternalId()));
        assertThat(refundHistoryList.get(0).getStatus(), oneOf(CREATED, REFUND_ERROR));
        assertThat(refundHistoryList.get(1).getExternalId(), is(refundEntity.getExternalId()));
        assertThat(refundHistoryList.get(1).getStatus(), oneOf(CREATED, REFUND_ERROR));
    }
}
