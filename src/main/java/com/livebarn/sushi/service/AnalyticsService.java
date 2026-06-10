package com.livebarn.sushi.service;

import com.livebarn.sushi.dto.AnalyticsResponse;
import com.livebarn.sushi.model.Chef;
import com.livebarn.sushi.model.OrderStatus;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AnalyticsService {

    private final long serverStartMillis = System.currentTimeMillis();
    private final Object lock = new Object();
    private final Set<Integer> pausedWhileCreated = ConcurrentHashMap.newKeySet();
    private final Set<Integer> pausedWhileInProgress = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Long> chefBusyStartMillis = new ConcurrentHashMap<>();

    private double waitTimeSum;
    private int waitTimeCount;

    private double makeTimeSum;
    private int makeTimeCount;
    private final Map<Integer, Long> makeTimeStartMillis = new HashMap<>();

    private long totalBusyMillis;
    private final Map<String, Integer> sushiOrderCounts = new HashMap<>();
    private final Map<String, Integer> ordersByHour = new HashMap<>();

    public void recordOrderCreated(String sushiName, Timestamp createdAt) {
        synchronized (lock) {
            sushiOrderCounts.merge(sushiName, 1, Integer::sum);
            int hour = createdAt.toLocalDateTime().getHour();
            ordersByHour.merge(String.valueOf(hour), 1, Integer::sum);
        }
    }

    public void recordCreatedToInProgress(int orderId, Timestamp createdAt) {
        double waitSeconds = (System.currentTimeMillis() - createdAt.getTime()) / 1000.0;
        synchronized (lock) {
            if (!pausedWhileCreated.contains(orderId)) {
                waitTimeSum += waitSeconds;
                waitTimeCount++;
            }
            makeTimeStartMillis.put(orderId, System.currentTimeMillis());
        }
    }

    public void recordPaused(int orderId, int statusBeforePause) {
        if (statusBeforePause == OrderStatus.CREATED) {
            pausedWhileCreated.add(orderId);
        } else if (statusBeforePause == OrderStatus.IN_PROGRESS) {
            pausedWhileInProgress.add(orderId);
        }
    }

    public void recordOrderFinished(int orderId) {
        synchronized (lock) {
            finalizeMakeTime(orderId);
        }
    }

    public void recordOrderCancelled(int orderId) {
        synchronized (lock) {
            makeTimeStartMillis.remove(orderId);
        }
    }

    public void chefBusyStart(int chefId) {
        chefBusyStartMillis.put(chefId, System.currentTimeMillis());
    }

    public void chefBusyEnd(int chefId) {
        Long start = chefBusyStartMillis.remove(chefId);
        if (start != null) {
            synchronized (lock) {
                totalBusyMillis += System.currentTimeMillis() - start;
            }
        }
    }

    public AnalyticsResponse getAnalytics() {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            long busyMillis = totalBusyMillis;
            for (Long start : chefBusyStartMillis.values()) {
                busyMillis += now - start;
            }

            long elapsedMillis = now - serverStartMillis;
            double chefUtilization = elapsedMillis > 0
                    ? (double) busyMillis / (Chef.COUNT * elapsedMillis)
                    : 0.0;

            double averageWaitTime = waitTimeCount > 0 ? waitTimeSum / waitTimeCount : 0.0;
            double averageMakeTime = makeTimeCount > 0 ? makeTimeSum / makeTimeCount : 0.0;

            String mostPopularSushi = sushiOrderCounts.entrySet().stream()
                    .max(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                            .thenComparing(Map.Entry::getKey))
                    .map(Map.Entry::getKey)
                    .orElse(null);

            return new AnalyticsResponse(
                    roundOneDecimal(averageWaitTime),
                    roundOneDecimal(averageMakeTime),
                    roundTwoDecimals(chefUtilization),
                    mostPopularSushi,
                    new HashMap<>(ordersByHour),
                    0,
                    "Analytics retrieved"
            );
        }
    }

    private void finalizeMakeTime(int orderId) {
        if (pausedWhileInProgress.contains(orderId)) {
            makeTimeStartMillis.remove(orderId);
            return;
        }

        Long startMillis = makeTimeStartMillis.remove(orderId);
        if (startMillis != null) {
            makeTimeSum += (System.currentTimeMillis() - startMillis) / 1000.0;
            makeTimeCount++;
        }
    }

    private static double roundOneDecimal(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static double roundTwoDecimals(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
