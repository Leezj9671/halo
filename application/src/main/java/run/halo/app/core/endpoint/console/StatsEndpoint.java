package run.halo.app.core.endpoint.console;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static run.halo.app.extension.index.query.Queries.and;
import static run.halo.app.extension.index.query.Queries.equal;
import static run.halo.app.extension.index.query.Queries.greaterThan;
import static run.halo.app.extension.index.query.Queries.isNull;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Data;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.Counter;
import run.halo.app.core.extension.DailySiteStats;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * Stats endpoint.
 *
 * @author guqing
 * @since 2.0.0
 */
@Component
public class StatsEndpoint implements CustomEndpoint {

    private static final DateTimeFormatter DATE_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_DAYS = 365;

    private final ReactiveExtensionClient client;

    public StatsEndpoint(ReactiveExtensionClient client) {
        this.client = client;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        var tag = "SystemV1alpha1Console";
        return SpringdocRouteBuilder.route()
            .GET("stats", this::getStats, builder -> builder.operationId("getStats")
                .description("Get stats.")
                .tag(tag)
                .response(responseBuilder()
                    .implementation(DashboardStats.class)
                )
            )
            .GET("stats/daily", this::getDailyStats,
                builder -> builder.operationId("getDailyStats")
                    .description(
                        "Get per-day site visit statistics for the past N days (default 30).")
                    .tag(tag)
                    .parameter(parameterBuilder()
                        .in(ParameterIn.QUERY)
                        .name("days")
                        .description(
                            "Number of days to return (1–" + MAX_DAYS + ", default "
                                + DEFAULT_DAYS + ")")
                        .required(false)
                    )
                    .response(responseBuilder()
                        .implementationArray(DailyStats.class)
                    )
            )
            .build();
    }

    Mono<ServerResponse> getStats(ServerRequest request) {
        var stats = DashboardStats.emptyStats();
        Mono<Void> setFromCounters = client.listAll(
                Counter.class, ListOptions.builder().build(), Sort.unsorted()
            )
            .doOnNext(counter -> {
                var visit = counter.getVisit();
                if (visit != null) {
                    stats.setVisits(stats.getVisits() + visit);
                }
                var totalComment = counter.getTotalComment();
                if (totalComment != null) {
                    stats.setComments(stats.getComments() + totalComment);
                }
                var approvedComment = counter.getApprovedComment();
                if (approvedComment != null) {
                    stats.setApprovedComments(
                        stats.getApprovedComments() + approvedComment
                    );
                }
                var upvote = counter.getUpvote();
                if (upvote != null) {
                    stats.setUpvotes(stats.getUpvotes() + upvote);
                }
            })
            .then();

        Mono<Void> setUsers = client.countBy(User.class, ListOptions.builder()
                .labelSelector()
                .notEq(User.HIDDEN_USER_LABEL, "true")
                .end()
                .andQuery(isNull("metadata.deletionTimestamp"))
                .build()
            )
            .doOnNext(stats::setUsers)
            .then();
        Mono<Void> setPosts = client.countBy(Post.class, ListOptions.builder()
                .andQuery(and(
                    isNull("metadata.deletionTimestamp"),
                    equal("spec.deleted", "false")
                ))
                .build()
            )
            .doOnNext(stats::setPosts)
            .then();

        return Mono.when(setFromCounters, setUsers, setPosts)
            .thenReturn(stats)
            .flatMap(body -> ServerResponse.ok().bodyValue(body));
    }

    Mono<ServerResponse> getDailyStats(ServerRequest request) {
        int days = request.queryParam("days")
            .map(s -> {
                try {
                    return Math.min(MAX_DAYS, Math.max(1, Integer.parseInt(s)));
                } catch (NumberFormatException e) {
                    return DEFAULT_DAYS;
                }
            })
            .orElse(DEFAULT_DAYS);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate startDate = today.minusDays(days - 1L);
        String startDateStr = startDate.format(DATE_FORMATTER);

        // Fetch all DailySiteStats records with name >= startDate.
        var listOptions = ListOptions.builder()
            .andQuery(greaterThan("metadata.name", startDateStr, true))
            .build();

        return client.listAll(DailySiteStats.class, listOptions,
                Sort.by("metadata.name").ascending())
            .collectList()
            .map(records -> {
                // Index persisted records by date string.
                Map<String, DailySiteStats> byDate = records.stream()
                    .collect(Collectors.toMap(
                        r -> r.getMetadata().getName(),
                        Function.identity()
                    ));

                // Build a complete series, filling gaps with zero.
                List<DailyStats> result = new ArrayList<>(days);
                for (int i = 0; i < days; i++) {
                    String date = startDate.plusDays(i).format(DATE_FORMATTER);
                    DailySiteStats persisted = byDate.get(date);
                    DailyStats ds = new DailyStats();
                    ds.setDate(date);
                    if (persisted != null) {
                        ds.setVisit(persisted.getVisit() != null ? persisted.getVisit() : 0);
                        ds.setUpvote(persisted.getUpvote() != null ? persisted.getUpvote() : 0);
                        ds.setComment(persisted.getComment() != null ? persisted.getComment() : 0);
                    }
                    result.add(ds);
                }
                return result;
            })
            .flatMap(body -> ServerResponse.ok().bodyValue(body));
    }

    @Data
    public static class DashboardStats {
        private long visits;
        private long comments;
        private long approvedComments;
        private long upvotes;
        private long users;
        private long posts;

        /**
         * Creates an empty stats that populated initialize value.
         *
         * @return stats with initialize value.
         */
        public static DashboardStats emptyStats() {
            DashboardStats stats = new DashboardStats();
            stats.setVisits(0L);
            stats.setComments(0L);
            stats.setApprovedComments(0L);
            stats.setUpvotes(0L);
            stats.setUsers(0L);
            stats.setPosts(0L);
            return stats;
        }
    }

    /**
     * Per-day stats data-transfer object returned by {@code GET /stats/daily}.
     */
    @Data
    public static class DailyStats {
        /** Date in {@code yyyy-MM-dd} format (UTC). */
        private String date;
        /** Total site visits on this day. */
        private int visit;
        /** Total upvotes on this day. */
        private int upvote;
        /** Total new comments on this day. */
        private int comment;
    }
}
