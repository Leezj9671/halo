package run.halo.app.core.extension;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;
import run.halo.app.extension.Metadata;

/**
 * Per-day site statistics snapshot.
 *
 * <p>Each instance records the total number of site-wide events (visits, upvotes, new comments)
 * that occurred on a single calendar day. The {@code metadata.name} field is the date string in
 * {@code yyyy-MM-dd} format (e.g. {@code 2024-01-15}) and acts as the natural primary key.
 *
 * @author guqing
 * @since 2.20.0
 */
@Data
@GVK(group = "metrics.halo.run", version = "v1alpha1",
    kind = "DailySiteStats",
    plural = "dailysitestats",
    singular = "dailysitestats")
@EqualsAndHashCode(callSuper = true)
public class DailySiteStats extends AbstractExtension {

    /** Total page-views / visits recorded on this day, across all content. */
    private Integer visit;

    /** Total new upvotes recorded on this day, across all content. */
    private Integer upvote;

    /** Total new comments (including replies) recorded on this day. */
    private Integer comment;

    /**
     * Creates a zero-value instance for the given date string.
     *
     * @param date date string in {@code yyyy-MM-dd} format
     * @return a new {@link DailySiteStats} with all counters initialised to 0
     */
    public static DailySiteStats empty(String date) {
        DailySiteStats stats = new DailySiteStats();
        stats.setMetadata(new Metadata());
        stats.getMetadata().setName(date);
        stats.setVisit(0);
        stats.setUpvote(0);
        stats.setComment(0);
        return stats;
    }
}
