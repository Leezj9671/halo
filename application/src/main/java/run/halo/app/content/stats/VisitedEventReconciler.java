package run.halo.app.content.stats;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import run.halo.app.core.counter.MeterUtils;
import run.halo.app.core.extension.Counter;
import run.halo.app.core.extension.DailySiteStats;
import run.halo.app.event.post.VisitedEvent;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.DefaultController;
import run.halo.app.extension.controller.DefaultQueue;
import run.halo.app.extension.controller.Reconciler;
import run.halo.app.extension.controller.RequestQueue;
import run.halo.app.infra.InitializationPhase;

/**
 * Update counters after receiving visit event.
 * It will cache the count in memory for one minute and then batch update to the database.
 * In addition to updating the per-resource {@link Counter}, it also maintains a per-day
 * {@link DailySiteStats} record so that time-series charts can be rendered in the dashboard.
 *
 * @author guqing
 * @since 2.0.0
 */
@Slf4j
@Component
public class VisitedEventReconciler
    implements Reconciler<VisitedEventReconciler.VisitCountBucket>, SmartLifecycle {
    private static final DateTimeFormatter DATE_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private volatile boolean running = false;

    private final ExtensionClient client;
    private final RequestQueue<VisitCountBucket> visitedEventQueue;
    private final Map<String, Integer> pooledVisitsMap = new ConcurrentHashMap<>();
    /** Pooled site-wide visit count for the current UTC day (key = yyyy-MM-dd). */
    private final Map<String, AtomicInteger> pooledDailyVisitsMap = new ConcurrentHashMap<>();
    private final Controller visitedEventController;

    public VisitedEventReconciler(ExtensionClient client) {
        this.client = client;
        visitedEventQueue = new DefaultQueue<>(Instant::now);
        visitedEventController = this.setupWith(null);
    }

    @Override
    public Result reconcile(VisitCountBucket visitCountBucket) {
        createOrUpdateVisits(visitCountBucket.name(), visitCountBucket.visits());
        return new Result(false, null);
    }

    private void createOrUpdateVisits(String name, Integer visits) {
        client.fetch(Counter.class, name)
            .ifPresentOrElse(counter -> {
                Integer existingVisit = ObjectUtils.defaultIfNull(counter.getVisit(), 0);
                counter.setVisit(existingVisit + visits);
                client.update(counter);
            }, () -> {
                Counter counter = Counter.emptyCounter(name);
                counter.setVisit(visits);
                client.create(counter);
            });
    }

    /**
     * Put the merged data into the queue every minute for updating to the database.
     */
    @Scheduled(cron = "0 0/1 * * * ?")
    public void queuedVisitBucketTask() {
        Iterator<Map.Entry<String, Integer>> iterator = pooledVisitsMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Integer> item = iterator.next();
            visitedEventQueue.addImmediately(new VisitCountBucket(item.getKey(), item.getValue()));
            iterator.remove();
        }
        // Flush daily visit counters to the database.
        flushDailyVisits();
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return new DefaultController<>(
            this.getClass().getName(),
            this,
            visitedEventQueue,
            null,
            Duration.ofMillis(300),
            Duration.ofMinutes(5));
    }

    @Override
    public void start() {
        this.visitedEventController.start();
        this.running = true;
    }

    @Override
    public void stop() {
        log.debug("Persist visits to database before destroy...");
        try {
            Iterator<Map.Entry<String, Integer>> iterator = pooledVisitsMap.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Integer> item = iterator.next();
                createOrUpdateVisits(item.getKey(), item.getValue());
                iterator.remove();
            }
            flushDailyVisits();
        } catch (Exception e) {
            log.error("Failed to persist visits to database.", e);
        }
        this.running = false;
        this.visitedEventController.dispose();
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }

    @Override
    public int getPhase() {
        return InitializationPhase.CONTROLLERS.getPhase();
    }

    public record VisitCountBucket(String name, int visits) {
    }

    /** Drain {@link #pooledDailyVisitsMap} and persist to {@link DailySiteStats}. */
    private void flushDailyVisits() {
        Iterator<Map.Entry<String, AtomicInteger>> iterator =
            pooledDailyVisitsMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, AtomicInteger> entry = iterator.next();
            String date = entry.getKey();
            // Remove first so that any concurrent increment after this point will create a new
            // entry; the AtomicInteger referenced by 'entry' can no longer be reached via the
            // map and will not receive further increments.
            iterator.remove();
            int visits = entry.getValue().get();
            if (visits > 0) {
                createOrUpdateDailyVisits(date, visits);
            }
        }
    }

    private void createOrUpdateDailyVisits(String date, int visits) {
        client.fetch(DailySiteStats.class, date)
            .ifPresentOrElse(stats -> {
                stats.setVisit(ObjectUtils.defaultIfNull(stats.getVisit(), 0) + visits);
                client.update(stats);
            }, () -> {
                DailySiteStats stats = DailySiteStats.empty(date);
                stats.setVisit(visits);
                client.create(stats);
            });
    }

    @Component
    @RequiredArgsConstructor
    public class VisitedEventListener {
        private final SchemeManager schemeManager;

        @Async
        @EventListener(VisitedEvent.class)
        public void onVisited(VisitedEvent visitedEvent) {
            mergeVisits(visitedEvent);
        }

        private void mergeVisits(VisitedEvent event) {
            var gpn = new GroupPluralName(event.getGroup(), event.getPlural(), event.getName());
            if (!checkVisitSubject(gpn)) {
                log.debug("Skip visit event for: {}", gpn);
                return;
            }
            String counterName =
                MeterUtils.nameOf(event.getGroup(), event.getPlural(), event.getName());
            pooledVisitsMap.compute(counterName, (name, visits) -> {
                if (visits == null) {
                    return 1;
                } else {
                    return visits + 1;
                }
            });
            // Also accumulate in the daily bucket for the current UTC day.
            String today = LocalDate.now(ZoneOffset.UTC).format(DATE_FORMATTER);
            pooledDailyVisitsMap.computeIfAbsent(today, k -> new AtomicInteger(0))
                .incrementAndGet();
        }

        private boolean checkVisitSubject(GroupPluralName groupPluralName) {
            Optional<Scheme> schemeOptional = schemeManager.schemes().stream()
                .filter(scheme -> {
                    GroupVersionKind gvk = scheme.groupVersionKind();
                    return scheme.plural().equals(groupPluralName.plural())
                        && gvk.group().equals(groupPluralName.group());
                })
                .findFirst();
            return schemeOptional.map(
                    scheme -> client.fetch(scheme.groupVersionKind(), groupPluralName.name())
                        .isPresent()
                )
                .orElse(false);
        }

        record GroupPluralName(String group, String plural, String name) {
        }
    }
}
