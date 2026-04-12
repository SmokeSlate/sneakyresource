package org.smokeslate.sneakyresource;

import java.nio.file.Path;

public record SelfUpdateReport(
    String previousCommit,
    String currentCommit,
    boolean repositoryChanged,
    boolean buildRan,
    Path builtJar,
    Path deployedJar,
    boolean syncRan
) {
    public String summaryLine() {
        final String commitSegment = this.repositoryChanged
            ? "updated to " + this.currentCommit
            : "already at " + this.currentCommit;
        final String buildSegment = this.buildRan ? ", build complete" : ", build skipped";
        final String deploySegment = this.deployedJar != null ? ", staged for next restart" : "";
        final String syncSegment = this.syncRan ? ", sync complete" : "";
        return "SneakyResource self-update: " + commitSegment + buildSegment + deploySegment + syncSegment;
    }
}
