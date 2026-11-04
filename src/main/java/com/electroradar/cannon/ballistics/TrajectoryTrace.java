package com.electroradar.cannon.ballistics;

/** Один прямой проход уже выбранной траектории до точки попадания, без поиска углов. */
public final class TrajectoryTrace {
    private TrajectoryTrace() {}

    public enum Status { CLEAR, BLOCKED, UNLOADED }
    public record Obstacle(Status status, Vector3 point) {
        public Obstacle {
            if (status == Status.CLEAR || point == null) throw new IllegalArgumentException("Invalid obstacle");
        }
    }
    public record Result(Status status, int tick, Vector3 point, int segments) {}
    @FunctionalInterface public interface Probe {
        /** null означает свободный отрезок. Реализация не должна загружать чанки. */
        Obstacle inspect(Vector3 start, Vector3 end);
    }

    public static Result check(ProjectileProfile profile, Vector3 pivot, Vector3 direction,
                               int lastTick, double fraction, Probe probe) {
        if (lastTick < 0 || !(fraction >= 0 && fraction <= 1)) throw new IllegalArgumentException("Invalid trace endpoint");
        Vector3 position = pivot.add(direction.scale(profile.spawnOffset()));
        Vector3 velocity = direction.scale(profile.speed());
        Vector3 gravity = new Vector3(0, -profile.gravity(), 0);
        for (int tick = 0; tick <= lastTick; tick++) {
            Vector3 force = velocity.scale(-profile.drag()).add(gravity);
            Vector3 step = velocity.add(force);
            Vector3 end = position.add(step.scale(tick == lastTick ? fraction : 1));
            Obstacle obstacle = probe.inspect(position, end);
            if (obstacle != null) return new Result(obstacle.status(), tick, obstacle.point(), tick + 1);
            if (tick == lastTick) return new Result(Status.CLEAR, tick, end, tick + 1);
            // Следующий sweep начинается в p+v+a/2, а не в конце предыдущего sweep p+v+a.
            position = position.add(velocity).add(force.scale(0.5));
            velocity = step;
        }
        throw new IllegalStateException("Trace did not terminate");
    }
}
