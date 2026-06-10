package com.livebarn.sushi.service;

import com.livebarn.sushi.dto.AnalyticsResponse;
import com.livebarn.sushi.model.Chef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AnalyticsService")
class AnalyticsServiceTest {

    private AnalyticsService analyticsService;

    @BeforeEach
    void setUp() {
        analyticsService = new AnalyticsService();
    }

    private static Timestamp ts(String localDateTime) {
        return Timestamp.valueOf(localDateTime);
    }

    private static Timestamp millisAgo(long millis) {
        return new Timestamp(System.currentTimeMillis() - millis);
    }

    @Nested
    @DisplayName("getAnalytics defaults")
    class GetAnalyticsDefaults {

        @Test
        void returnsZerosAndNullWhenNoData() {
            AnalyticsResponse response = analyticsService.getAnalytics();

            assertEquals(0.0, response.averageWaitTime());
            assertEquals(0.0, response.averageMakeTime());
            assertEquals(0.0, response.chefUtilization());
            assertNull(response.mostPopularSushi());
            assertTrue(response.ordersByHour().isEmpty());
        }

        @Test
        void returnsFixedMetadata() {
            AnalyticsResponse response = analyticsService.getAnalytics();

            assertEquals(0, response.code());
            assertEquals("Analytics retrieved", response.msg());
        }

        @Test
        void ordersByHourIsDefensiveCopy() {
            analyticsService.recordOrderCreated("Salmon", ts("2024-06-10 14:00:00"));

            AnalyticsResponse response = analyticsService.getAnalytics();
            response.ordersByHour().put("99", 999);

            assertEquals(1, analyticsService.getAnalytics().ordersByHour().get("14"));
            assertNull(analyticsService.getAnalytics().ordersByHour().get("99"));
        }
    }

    @Nested
    @DisplayName("recordOrderCreated")
    class RecordOrderCreated {

        @Test
        void incrementsSushiCount() {
            analyticsService.recordOrderCreated("Salmon", ts("2024-06-10 14:00:00"));
            analyticsService.recordOrderCreated("Salmon", ts("2024-06-10 14:15:00"));
            analyticsService.recordOrderCreated("Tuna", ts("2024-06-10 14:30:00"));

            assertEquals("Salmon", analyticsService.getAnalytics().mostPopularSushi());
        }

        @Test
        void bucketsOrdersByHour() {
            analyticsService.recordOrderCreated("Salmon", ts("2024-06-10 14:00:00"));
            analyticsService.recordOrderCreated("Salmon", ts("2024-06-10 14:45:00"));

            assertEquals(2, analyticsService.getAnalytics().ordersByHour().get("14"));
        }

        @Test
        void differentHoursSeparateBuckets() {
            analyticsService.recordOrderCreated("Salmon", ts("2024-06-10 09:00:00"));
            analyticsService.recordOrderCreated("Salmon", ts("2024-06-10 10:00:00"));

            AnalyticsResponse response = analyticsService.getAnalytics();
            assertEquals(1, response.ordersByHour().get("9"));
            assertEquals(1, response.ordersByHour().get("10"));
        }
    }

    @Nested
    @DisplayName("recordCreatedToInProgress")
    class RecordCreatedToInProgress {

        @Test
        void recordsWaitTimeFromCreatedAt() {
            analyticsService.recordCreatedToInProgress(1, millisAgo(5_000));

            assertEquals(5.0, analyticsService.getAnalytics().averageWaitTime(), 0.5);
        }

        @Test
        void averagesMultipleWaitTimes() {
            analyticsService.recordCreatedToInProgress(1, millisAgo(2_000));
            analyticsService.recordCreatedToInProgress(2, millisAgo(4_000));

            assertEquals(3.0, analyticsService.getAnalytics().averageWaitTime(), 0.5);
        }

        @Test
        void startsMakeTimeTracking() throws InterruptedException {
            analyticsService.recordCreatedToInProgress(1, millisAgo(1_000));
            Thread.sleep(200);
            analyticsService.recordOrderFinished(1);

            assertTrue(analyticsService.getAnalytics().averageMakeTime() > 0.0);
        }
    }

    @Nested
    @DisplayName("recordPaused and recordOrderFinished")
    class RecordPausedAndFinished {

        @Test
        void excludesMakeTimeWhenPausedWhileInProgress() throws InterruptedException {
            analyticsService.recordCreatedToInProgress(1, millisAgo(1_000));
            analyticsService.recordPaused(1);
            Thread.sleep(200);
            analyticsService.recordOrderFinished(1);

            assertEquals(0.0, analyticsService.getAnalytics().averageMakeTime());
        }

        @Test
        void finishWithoutStartIsNoOp() {
            analyticsService.recordOrderFinished(99);

            assertEquals(0.0, analyticsService.getAnalytics().averageMakeTime());
        }

        @Test
        void finishAfterInProgressRecordsMakeTime() throws InterruptedException {
            analyticsService.recordCreatedToInProgress(1, millisAgo(1_000));
            Thread.sleep(200);
            analyticsService.recordOrderFinished(1);

            assertEquals(0.2, analyticsService.getAnalytics().averageMakeTime(), 0.2);
        }

        @Test
        void finishAveragesMultipleMakeTimes() throws InterruptedException {
            analyticsService.recordCreatedToInProgress(1, millisAgo(1_000));
            Thread.sleep(200);
            analyticsService.recordOrderFinished(1);

            analyticsService.recordCreatedToInProgress(2, millisAgo(1_000));
            Thread.sleep(400);
            analyticsService.recordOrderFinished(2);

            assertEquals(0.3, analyticsService.getAnalytics().averageMakeTime(), 0.2);
        }
    }

    @Nested
    @DisplayName("recordOrderCancelled")
    class RecordOrderCancelled {

        @Test
        void cancelRemovesMakeTimeStart() throws InterruptedException {
            analyticsService.recordCreatedToInProgress(1, millisAgo(1_000));
            analyticsService.recordOrderCancelled(1);
            Thread.sleep(200);
            analyticsService.recordOrderFinished(1);

            assertEquals(0.0, analyticsService.getAnalytics().averageMakeTime());
        }

        @Test
        void cancelUnknownOrderIdIsSafe() {
            analyticsService.recordOrderCancelled(999);

            assertEquals(0.0, analyticsService.getAnalytics().averageMakeTime());
        }
    }

    @Nested
    @DisplayName("chefBusyStart and chefBusyEnd")
    class ChefBusy {

        @Test
        void endWithoutStartIsNoOp() {
            analyticsService.chefBusyEnd(1);

            assertEquals(0.0, analyticsService.getAnalytics().chefUtilization());
        }

        @Test
        void recordsCompletedBusyInterval() throws InterruptedException {
            analyticsService.chefBusyStart(1);
            Thread.sleep(150);
            analyticsService.chefBusyEnd(1);

            assertTrue(analyticsService.getAnalytics().chefUtilization() > 0.0);
        }

        @Test
        void includesInFlightBusyTimeInGetAnalytics() throws InterruptedException {
            analyticsService.chefBusyStart(1);
            Thread.sleep(150);

            assertTrue(analyticsService.getAnalytics().chefUtilization() > 0.0);
        }

        @Test
        void utilizationUsesChefCountDenominator() throws InterruptedException {
            Thread.sleep(300);
            analyticsService.chefBusyStart(1);
            Thread.sleep(150);
            analyticsService.chefBusyEnd(1);

            double utilization = analyticsService.getAnalytics().chefUtilization();
            assertTrue(utilization > 0.0);
            assertTrue(utilization < 1.0 / Chef.COUNT + 0.05);
        }
    }

    @Nested
    @DisplayName("getAnalytics aggregation")
    class GetAnalyticsAggregation {

        @Test
        void mostPopularSushiPicksHighestCount() {
            analyticsService.recordOrderCreated("Salmon", ts("2024-06-10 14:00:00"));
            analyticsService.recordOrderCreated("Salmon", ts("2024-06-10 14:05:00"));
            analyticsService.recordOrderCreated("Salmon", ts("2024-06-10 14:10:00"));
            analyticsService.recordOrderCreated("Tuna", ts("2024-06-10 14:15:00"));

            assertEquals("Salmon", analyticsService.getAnalytics().mostPopularSushi());
        }

        @Test
        void mostPopularSushiTieBreaksByLexicographicallyLargerName() {
            analyticsService.recordOrderCreated("Zebra", ts("2024-06-10 14:00:00"));
            analyticsService.recordOrderCreated("Zebra", ts("2024-06-10 14:05:00"));
            analyticsService.recordOrderCreated("Apple", ts("2024-06-10 14:10:00"));
            analyticsService.recordOrderCreated("Apple", ts("2024-06-10 14:15:00"));

            assertEquals("Zebra", analyticsService.getAnalytics().mostPopularSushi());
        }

        @Test
        void roundsWaitTimeToOneDecimal() {
            analyticsService.recordCreatedToInProgress(1, millisAgo(1_250));

            assertEquals(1.3, analyticsService.getAnalytics().averageWaitTime(), 0.15);
        }

        @Test
        void roundsUtilizationToTwoDecimals() throws InterruptedException {
            analyticsService.chefBusyStart(1);
            Thread.sleep(150);
            analyticsService.chefBusyEnd(1);

            double utilization = analyticsService.getAnalytics().chefUtilization();
            assertEquals(Math.round(utilization * 100.0) / 100.0, utilization, 0.0001);
        }
    }

    @Nested
    @DisplayName("end-to-end flows")
    class EndToEndFlows {

        @Test
        void happyPath() throws InterruptedException {
            analyticsService.recordOrderCreated("Salmon", ts("2024-06-10 14:00:00"));
            analyticsService.recordCreatedToInProgress(1, millisAgo(2_000));
            analyticsService.chefBusyStart(1);
            Thread.sleep(200);
            analyticsService.chefBusyEnd(1);
            analyticsService.recordOrderFinished(1);

            AnalyticsResponse response = analyticsService.getAnalytics();
            assertTrue(response.averageWaitTime() > 0.0);
            assertTrue(response.averageMakeTime() > 0.0);
            assertTrue(response.chefUtilization() > 0.0);
            assertEquals("Salmon", response.mostPopularSushi());
            assertEquals(1, response.ordersByHour().get("14"));
        }

        @Test
        void pauseInProgressExcludesMakeTime() throws InterruptedException {
            analyticsService.recordCreatedToInProgress(1, millisAgo(2_000));
            analyticsService.recordPaused(1);
            Thread.sleep(200);
            analyticsService.recordOrderFinished(1);

            AnalyticsResponse response = analyticsService.getAnalytics();
            assertTrue(response.averageWaitTime() > 0.0);
            assertEquals(0.0, response.averageMakeTime());
        }

        @Test
        void cancelInProgressExcludesMakeTime() throws InterruptedException {
            analyticsService.recordCreatedToInProgress(1, millisAgo(2_000));
            analyticsService.recordOrderCancelled(1);
            Thread.sleep(200);
            analyticsService.recordOrderFinished(1);

            assertEquals(0.0, analyticsService.getAnalytics().averageMakeTime());
        }

        @Test
        void multipleOrdersSameHour() throws InterruptedException {
            analyticsService.recordOrderCreated("Salmon", ts("2024-06-10 14:00:00"));
            analyticsService.recordOrderCreated("Tuna", ts("2024-06-10 14:30:00"));

            analyticsService.recordCreatedToInProgress(1, millisAgo(2_000));
            analyticsService.recordCreatedToInProgress(2, millisAgo(4_000));
            analyticsService.recordOrderFinished(1);
            analyticsService.recordOrderFinished(2);

            AnalyticsResponse response = analyticsService.getAnalytics();
            assertEquals(2, response.ordersByHour().get("14"));
            assertEquals(3.0, response.averageWaitTime(), 0.5);
        }
    }
}
