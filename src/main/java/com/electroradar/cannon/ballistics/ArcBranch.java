package com.electroradar.cannon.ballistics;

import java.util.Objects;

/**
 * Ветвь конкретного найденного корня. {@link #TANGENT} — двойной корень на огибающей семейства
 * сфер: в нём ветви {@link ArcFamily#LOW} и {@link ArcFamily#HIGH} встречаются, и он подходит обеим.
 */
public enum ArcBranch {
    LOW,
    HIGH,
    TANGENT;

    public boolean belongsTo(ArcFamily family) {
        Objects.requireNonNull(family, "family");
        return this == TANGENT || family == ArcFamily.LOW && this == LOW || family == ArcFamily.HIGH && this == HIGH;
    }
}
