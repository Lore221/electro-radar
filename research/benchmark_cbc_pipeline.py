"""Full research-pipeline timing; Python timings are NOT Java/TPS predictions.

python3 -B research/benchmark_cbc_pipeline.py --pairs 1000 --warm-frames 3 \
    --output research/cbc_pipeline_results.json

Input generation and physical movement of the test actuator are outside timed
calls. Every timed call includes forecast preparation, launch-delay agreement,
point/time search, any reserve, and the forward launch-pose check. No test oracle
or projectile tick simulation is included. Each pair has its own target, profile,
planner and state; no target-result deduplication or concurrent workers are used.
"""
import argparse
import copy
from collections import Counter
from dataclasses import dataclass, replace
from datetime import datetime, timezone
import json
import math
import os
import platform
import random
import sys
import time

try:
    from . import cbc_lead_model as base
    from .cbc_pipeline import Planner, Policy, Mount, Pose, Observation, TrackState, Budget, Status, angle_difference
except ImportError:
    import cbc_lead_model as base
    from cbc_pipeline import Planner, Policy, Mount, Pose, Observation, TrackState, Budget, Status, angle_difference


@dataclass
class Pair:
    planner: Planner
    state: TrackState
    snapshot: base.Snapshot
    mount: Mount
    arc: str
    target_id: str
    last_result: object = None


def fixtures(count):
    rng = random.Random(311026)
    out = []
    for i in range(count):
        # Distinct ballistic profiles too: no hidden shared profile-cache gain.
        profile = base.Profile(speed=8+(i-count/2)*.0001)
        snapshot = base.Snapshot((rng.uniform(320, 420), rng.uniform(-10, 20), rng.uniform(-60, 60)),
                                 (rng.uniform(.05, .2), rng.uniform(-.01, .01), rng.uniform(-.1, .1)),
                                 (rng.uniform(-.0001, .0001), 0., rng.uniform(-.0001, .0001)),
                                 eta=(-1, 0, 1)[i % 3])
        pose = Pose(rng.uniform(-5, 15), rng.uniform(-160, 120))
        mount = Mount(pose=pose, visible_pose=pose, yaw_rate=4, pitch_rate=3,
                      ready_after=i % 5, trigger_delay=i % 3)
        out.append(Pair(Planner(profile, Policy(work_limit=1600)), TrackState(), snapshot,
                        mount, 'LOW' if i % 2 == 0 else 'HIGH', str(i)))
    return out


def advance(pair, shock=False):
    acceleration = base.scale(pair.snapshot.v, -2.) if shock else pair.snapshot.a
    pair.snapshot = pair.snapshot.advance(acceleration)
    current = pair.mount.pose
    desired = (pair.last_result.plan.pose if pair.last_result is not None
               and pair.last_result.status in (Status.READY, Status.AIMING) else current)
    dy = angle_difference(desired.yaw, current.yaw)
    dp = desired.pitch-current.pitch
    new = Pose(current.pitch+max(-pair.mount.pitch_rate, min(pair.mount.pitch_rate, dp)),
               current.yaw+max(-pair.mount.yaw_rate, min(pair.mount.yaw_rate, dy)))
    settled = abs(angle_difference(new.yaw, current.yaw)) < 1e-9 and abs(new.pitch-current.pitch) < 1e-9
    pair.mount = replace(pair.mount, pose=new, visible_pose=current, settled=settled,
                         ready_after=max(0, pair.mount.ready_after-1), revision=pair.mount.revision+1)


def percentile(values, percent):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered)*percent)-1)] if ordered else 0.


def run_batch(pairs, now, label, shared=None):
    durations, works = [], []
    statuses, operations = Counter(), Counter()
    deferred = 0
    batch_start = time.perf_counter_ns()
    for index, pair in enumerate(pairs):
        if shared is not None and (shared.used >= shared.limit or
                (shared.deadline_ns is not None and time.perf_counter_ns() >= shared.deadline_ns)):
            deferred = len(pairs)-index
            statuses['DEFERRED_BY_BATCH_BUDGET'] += deferred
            # A previous result's timestamp/revision cannot authorize this tick.
            break
        observation = Observation(pair.snapshot, now, pair.target_id, now)
        begin = time.perf_counter_ns()
        result = pair.planner.plan(observation, now, pair.mount, pair.arc, pair.state,
                                   phase=1, shared_budget=shared)
        if result.status == Status.READY:
            assert pair.planner.can_trigger(result, observation, now, pair.mount, pair.arc,
                                            phase=1, state=pair.state)
        elapsed = time.perf_counter_ns()-begin
        durations.append(elapsed/1000.)
        works.append(result.work)
        statuses[result.status.value] += 1
        operations.update(result.counts)
        pair.last_result = result
    return {'name': label, 'requested_pairs': len(pairs), 'evaluated_pairs': len(durations),
            'deferred_pairs': deferred, 'sum_solver_ms': sum(durations)/1000.,
            'p50_us': percentile(durations, .5), 'p95_us': percentile(durations, .95),
            'p99_us': percentile(durations, .99), 'max_us': max(durations, default=0.),
            'mean_work_units': sum(works)/len(works) if works else 0.,
            'max_work_units': max(works, default=0), 'statuses': dict(statuses),
            'operations': dict(operations), 'shared_work_used': shared.used if shared else None,
            'batch_elapsed_ms': (time.perf_counter_ns()-batch_start)/1e6}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--pairs', type=int, default=1000)
    parser.add_argument('--warm-frames', type=int, default=3)
    parser.add_argument('--output', default='research/cbc_pipeline_results.json')
    args = parser.parse_args()
    pairs = fixtures(args.pairs)
    batches = [run_batch(pairs, 0, 'cold')]
    print('cold complete', flush=True)
    for frame in range(1, args.warm_frames+1):
        for pair in pairs:
            advance(pair)
        batches.append(run_batch(pairs, frame, 'tracking_'+str(frame)))
        print('tracking', frame, 'complete', flush=True)
    for pair in pairs:
        advance(pair, shock=True)
    deadline_pairs = copy.deepcopy(pairs)
    batches.append(run_batch(pairs, args.warm_frames+1, 'reversals_work_budget_only', Budget(40000)))
    wall_budget = Budget(40000, deadline_ns=time.perf_counter_ns()+5_000_000)
    batches.append(run_batch(deadline_pairs, args.warm_frames+1, 'reversals_5ms_wall_budget', wall_budget))
    print('budgeted reversals complete', flush=True)
    for i, pair in enumerate(pairs):
        pair.snapshot = base.Snapshot((5000.+i, 0., 0.), base.ZERO)
    batches.append(run_batch(pairs, args.warm_frames+2, 'provably_out_of_range'))
    report = {'created_utc': datetime.now(timezone.utc).isoformat(),
              'python': sys.version, 'platform': platform.platform(), 'logical_cpus': os.cpu_count(),
              'execution': 'single-thread Python research model with instrumentation',
              'scope': 'complete planner + atomic trigger check; input generation/oracles excluded',
              'not_measured': ['Minecraft/CBC integration', 'Java runtime', 'real mount LUT', 'world obstruction checks'],
              'batches': batches}
    with open(args.output, 'w', encoding='utf-8') as stream:
        json.dump(report, stream, indent=2)
        stream.write('\n')
    for batch in batches:
        print(batch['name'], 'p50/p95/p99 us', tuple(round(batch[k], 2) for k in ('p50_us', 'p95_us', 'p99_us')),
              'sum_ms', round(batch['sum_solver_ms'], 2), 'statuses', batch['statuses'], flush=True)


if __name__ == '__main__':
    main()
