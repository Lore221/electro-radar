package com.electroradar.cannon.ballistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrajectoryTraceTest {
    @Test void tracesCbcSweepsAndStopsAtTheLastFraction() {
        var starts = new ArrayList<Vector3>();
        var ends = new ArrayList<Vector3>();
        var result = TrajectoryTrace.check(ProjectileProfile.of(4, 2, 0, 2), Vector3.ZERO, new Vector3(1, 0, 0),
                2, .25, (start, end) -> { starts.add(start); ends.add(end); return null; });
        assertEquals(List.of(new Vector3(2, 0, 0), new Vector3(6, -1, 0), new Vector3(10, -4, 0)), starts);
        assertEquals(List.of(new Vector3(6, -2, 0), new Vector3(10, -5, 0), new Vector3(11, -5.5, 0)), ends);
        assertEquals(TrajectoryTrace.Status.CLEAR, result.status());
        assertEquals(3, result.segments());
    }

    @Test void wallAfterTheHitDoesNotVetoTheShotButWallBeforeItDoes() {
        var profile = ProjectileProfile.of(10, 0, 0, 0);
        var direction = new Vector3(1, 0, 0);
        var clear = TrajectoryTrace.check(profile, Vector3.ZERO, direction, 1, .2,
                (start, end) -> end.x() >= 15 ? new TrajectoryTrace.Obstacle(TrajectoryTrace.Status.BLOCKED, new Vector3(15, 0, 0)) : null);
        assertEquals(TrajectoryTrace.Status.CLEAR, clear.status());
        assertEquals(new Vector3(12, 0, 0), clear.point());
        var blocked = TrajectoryTrace.check(profile, Vector3.ZERO, direction, 1, .2,
                (start, end) -> end.x() >= 11 ? new TrajectoryTrace.Obstacle(TrajectoryTrace.Status.BLOCKED, new Vector3(11, 0, 0)) : null);
        assertEquals(TrajectoryTrace.Status.BLOCKED, blocked.status());
        assertEquals(1, blocked.tick());
        assertEquals(2, blocked.segments());
    }

    @Test void firstObstacleOrUnloadedAreaStopsTheWalkImmediately() {
        for (var status : List.of(TrajectoryTrace.Status.BLOCKED, TrajectoryTrace.Status.UNLOADED)) {
            var result = TrajectoryTrace.check(ProjectileProfile.of(4, 2, .01, .05), Vector3.ZERO, new Vector3(1, 0, 0),
                    100, 1, (start, end) -> new TrajectoryTrace.Obstacle(status, start));
            assertEquals(status, result.status());
            assertEquals(0, result.tick());
            assertEquals(1, result.segments());
        }
    }
}
