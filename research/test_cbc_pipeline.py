"""Offline independent recurrences and regression checks for the research model."""
import math
import unittest
import random
import time
from decimal import Decimal, localcontext
from dataclasses import replace

try:
    from . import cbc_lead_model as base
    from .cbc_pipeline import *
except ImportError:
    import cbc_lead_model as base
    from cbc_pipeline import *


class DelayTests(unittest.TestCase):
    def test_parallel_readiness_and_wrap(self):
        mount = Mount(pose=Pose(0, 179), yaw_rate=1, pitch_rate=2,
                      ready_after=20, trigger_delay=3, pose_lag=1)
        target = Pose(10, -179)
        self.assertEqual(mount.travel_ticks(target), 5)
        self.assertEqual(mount.earliest_launch(target), 23)
        self.assertEqual(replace(mount, simultaneous_axes=False).travel_ticks(target), 7)

    def test_zero_axis_speed(self):
        mount = Mount(yaw_rate=0, pitch_rate=0)
        self.assertEqual(mount.earliest_launch(Pose()), 0)
        self.assertIsNone(mount.earliest_launch(Pose(0, 1)))

    def test_delayed_plan_matches_independent_target(self):
        profile = base.Profile()
        planner = Planner(profile)
        snapshot = base.Snapshot((350., 5., 20.), (.2, .01, .1), (.0004, .0001, 0), eta=1)
        observation = Observation(snapshot, 96)
        mount = Mount(pose=Pose(0, 60), visible_pose=Pose(0, 60),
                      yaw_rate=3, pitch_rate=2, ready_after=9, trigger_delay=2)
        result = planner.plan(observation, 100, mount, 'LOW', phase=1)
        self.assertEqual(result.status, Status.AIMING, result.reason)
        plan = result.plan
        wait = plan.launch_tick-100
        self.assertGreaterEqual(wait, mount.earliest_launch(plan.pose))
        self.assertTrue(all(a[0] <= b[0] for a, b in zip(plan.delay_trace, plan.delay_trace[1:])))
        self.assertEqual(plan.prediction_dt, 4+wait+1+plan.candidate.n)
        target = base.target_oracle(replace(snapshot, offset=4+wait+1), plan.candidate.n)
        self.assertLess(base.distance(target, base.cbc_oracle(profile, plan.candidate)), 1e-7)

    def test_previous_pose_is_not_current_pose(self):
        snapshot = base.Snapshot((400., 0., 0.), base.ZERO)
        observation = Observation(snapshot, 0)
        initial = Planner().plan(observation, 0, Mount(), 'LOW')
        self.assertEqual(initial.status, Status.AIMING)
        desired = initial.plan.pose
        unreflected = Mount(pose=desired, visible_pose=Pose(), settled=False, pose_lag=1)
        result = Planner().plan(observation, 0, unreflected, 'LOW')
        self.assertEqual(result.status, Status.AIMING)
        self.assertGreaterEqual(result.plan.launch_tick, 1)

    def test_small_drag_coefficients(self):
        for drag in (0., 1e-10, 1e-7, .01):
            kernel = base.PointKernel(base.Profile(drag=drag))
            c = kernel.coefficients(300)
            v, p = (8., 0., 0.), (2., 0., 0.)
            for _ in range(300):
                a = (-drag*v[0], -drag*v[1]-.05, -drag*v[2])
                p = base.add(p, base.add(v, base.scale(a, .5)))
                v = base.add(v, a)
            expected = (2+8*c.A, -.05*c.B, 0.)
            self.assertLess(base.distance(p, expected), 1e-7)


class PipelineOrderTests(unittest.TestCase):
    def test_ready_means_trigger_now_not_spawn_now(self):
        profile = base.Profile()
        snapshot = base.Snapshot((350., 5., 20.), (.2, 0., .1))
        observation = Observation(snapshot, 10, revision=2)
        seeded_curve = replace(snapshot, offset=3).curve()
        candidate, _ = base.LeadCoordinator(base.PointKernel(profile)).solve(seeded_curve, 'LOW')
        self.assertIsNotNone(candidate)
        pose = candidate_pose(candidate)
        mount = Mount(pose=pose, visible_pose=pose, trigger_delay=3)
        planner, state = Planner(profile), TrackState()
        result = planner.plan(observation, 10, mount, 'LOW', state)
        self.assertEqual(result.status, Status.READY, result.reason)
        self.assertEqual(result.plan.command_tick, 10)
        self.assertEqual(result.plan.launch_tick, 13)
        self.assertTrue(planner.can_trigger(result, observation, 10, mount, 'LOW', state=state))
        self.assertFalse(planner.can_trigger(result, observation, 11, mount, 'LOW', state=state))
        self.assertFalse(planner.can_trigger(result, replace(observation, revision=3), 10, mount, 'LOW', state=state))
        self.assertFalse(planner.can_trigger(result, observation, 10, replace(mount, revision=1), 'LOW', state=state))
        self.assertFalse(planner.can_trigger(result, observation, 10, mount, 'HIGH', state=state))
        self.assertFalse(planner.can_trigger(result, observation, 10, mount, 'LOW', state=state, phase=1))

    def test_future_plan_cannot_authorize_command(self):
        observation = Observation(base.Snapshot((400., 0., 0.), base.ZERO), 0)
        mount = Mount(pose=Pose(0, 50), visible_pose=Pose(0, 50))
        planner, state = Planner(), TrackState()
        result = planner.plan(observation, 0, mount, 'LOW', state)
        self.assertEqual(result.status, Status.AIMING)
        self.assertFalse(planner.can_trigger(result, observation, 0, mount, 'LOW', state=state))

    def test_misreported_visible_pose_is_rejected(self):
        snapshot = base.Snapshot((400., 0., 0.), base.ZERO)
        candidate, _ = base.LeadCoordinator(base.PointKernel(base.Profile())).solve(snapshot.curve(), 'LOW')
        pose = candidate_pose(candidate)
        mount = Mount(pose=pose, visible_pose=Pose(), settled=True)
        result = Planner().plan(Observation(snapshot, 0), 0, mount, 'LOW')
        self.assertEqual(result.status, Status.VERIFY_REJECTED)

    def test_approach_to_trigger_with_fresh_observations(self):
        snapshot = base.Snapshot((350., 5., 20.), (.2, .01, .1), (.0004, 0., 0.))
        planner, state = Planner(), TrackState()
        mount = Mount(pose=Pose(0, 50), visible_pose=Pose(0, 50), yaw_rate=6, pitch_rate=3,
                      ready_after=4, trigger_delay=2)
        for now in range(70):
            observation = Observation(snapshot, now, revision=now)
            result = planner.plan(observation, now, mount, 'LOW', state, phase=1)
            if result.status == Status.READY:
                self.assertTrue(planner.can_trigger(result, observation, now, mount, 'LOW', phase=1, state=state))
                offset = result.plan.launch_tick-now+1
                target = base.target_oracle(replace(snapshot, offset=offset), result.plan.candidate.n)
                self.assertLess(base.distance(target, base.cbc_oracle(planner.profile, result.plan.candidate)), 1e-7)
                break
            self.assertEqual(result.status, Status.AIMING, (now, result.reason))
            desired = result.plan.pose
            dy = angle_difference(desired.yaw, mount.pose.yaw)
            dp = desired.pitch-mount.pose.pitch
            new_pose = Pose(mount.pose.pitch+max(-mount.pitch_rate, min(mount.pitch_rate, dp)),
                            mount.pose.yaw+max(-mount.yaw_rate, min(mount.yaw_rate, dy)))
            settled = (abs(angle_difference(new_pose.yaw, mount.pose.yaw)) < 1e-9
                       and abs(new_pose.pitch-mount.pose.pitch) < 1e-9)
            mount = replace(mount, pose=new_pose, visible_pose=mount.pose, settled=settled,
                            ready_after=max(0, mount.ready_after-1), revision=now+1)
            snapshot = snapshot.advance()
        else:
            self.fail('the consistent moving-target plan never reached its trigger tick')


class FailureAndReserveTests(unittest.TestCase):
    def test_reserve_recovers_a_missed_fast_solution(self):
        snapshot = base.Snapshot((48.973866698218046, 205.20827979827564, 49.89955398331556),
                                 (2.820117343998219, .2590019860944469, -.7128507711902223))
        curve = Forecast(snapshot.p, snapshot.v, snapshot.a)
        candidate, _ = Lead(Kernel(base.Profile())).solve(curve, 'LOW')
        self.assertIsNone(candidate)
        answer = reserve_search(Kernel(base.Profile()), curve, 'LOW', 1e-6, 96, {}, ('fixture',), 70.)
        self.assertIsNotNone(answer.candidate)
        self.assertLess(base.distance(base.cbc_oracle(base.Profile(), answer.candidate),
                                      base.target_oracle(snapshot, answer.candidate.n)), 1e-7)
        pose = candidate_pose(answer.candidate)
        mount = Mount(pose=pose, visible_pose=pose)
        result = Planner(policy=Policy(reserve_lead_ticks=0)).plan(Observation(snapshot, 0), 0, mount, 'LOW')
        self.assertEqual(result.status, Status.READY, result.reason)
        self.assertEqual(result.plan.path, 'RESERVE')

    def test_reserve_preemption_preserves_the_remaining_intervals(self):
        snapshot = base.Snapshot((400., 0., 0.), base.ZERO)
        curve = Forecast(snapshot.p, snapshot.v, snapshot.a)
        jobs = {}
        token = CURRENT_BUDGET.set(Budget(4))
        try:
            with self.assertRaises(WorkLimit):
                reserve_search(Kernel(base.Profile()), curve, 'LOW', 1e-6, 96, jobs, ('same',), 70.)
        finally:
            CURRENT_BUDGET.reset(token)
        answer = reserve_search(Kernel(base.Profile()), curve, 'LOW', 1e-6, 96, jobs, ('same',), 70.)
        self.assertIsNotNone(answer.candidate)

    def test_reserve_node_budget_and_key_invalidation(self):
        curve = Forecast((400., 0., 0.), base.ZERO, base.ZERO)
        jobs = {}
        answer = reserve_search(Kernel(base.Profile()), curve, 'LOW', 1e-6, 1, jobs, ('old',), 70.)
        self.assertEqual(answer.status, Status.SEARCH_PENDING)
        old_job = jobs['LOW']
        reserve_search(Kernel(base.Profile()), curve, 'LOW', 1e-6, 1, jobs, ('new',), 70.)
        self.assertIsNot(old_job, jobs['LOW'])

    def test_statuses_and_lost_observation(self):
        obs = Observation(base.Snapshot((400., 0., 0.), base.ZERO), 0)
        planner, state = Planner(), TrackState()
        good = planner.plan(obs, 0, Mount(), 'LOW', state)
        self.assertIn(good.status, (Status.READY, Status.AIMING))
        self.assertEqual(planner.plan(replace(obs, observing=False), 0, Mount(), 'LOW', state).status,
                         Status.STALE_INPUT)
        self.assertIsNone(state.plan)
        self.assertEqual(planner.plan(obs, 21, Mount(), 'LOW').status, Status.STALE_INPUT)
        invalid = replace(obs, snapshot=replace(obs.snapshot, p=(math.nan, 0., 0.)))
        self.assertEqual(planner.plan(invalid, 0, Mount(), 'LOW').status, Status.INVALID_INPUT)
        self.assertEqual(Planner(replace(base.Profile(), quadratic_drag=True)).plan(obs, 0, Mount(), 'LOW').status,
                         Status.UNSUPPORTED_MODEL)
        far = replace(obs, snapshot=replace(obs.snapshot, p=(5000., 0., 0.)))
        self.assertEqual(planner.plan(far, 0, Mount(), 'LOW').status, Status.NO_POINT_IN_WINDOW)
        self.assertEqual(planner.plan(obs, 0, replace(Mount(), controllable=False), 'LOW').status,
                         Status.CONTROL_UNAVAILABLE)
        self.assertEqual(Planner(policy=Policy(max_launch_delay=1)).plan(obs, 0, Mount(), 'LOW').status,
                         Status.DELAY_LIMIT)

    def test_local_and_shared_work_limits(self):
        obs = Observation(base.Snapshot((400., 0., 0.), base.ZERO), 0)
        self.assertEqual(Planner(policy=Policy(work_limit=1)).plan(obs, 0, Mount(), 'LOW').status,
                         Status.BUDGET_EXHAUSTED)
        shared = Budget(35)
        results = [Planner().plan(replace(obs, target_id=str(i)), 0, Mount(), 'LOW',
                                  shared_budget=shared) for i in range(5)]
        self.assertLessEqual(shared.used, 35)
        self.assertTrue(any(r.status == Status.BUDGET_EXHAUSTED for r in results))
        self.assertTrue(all(r.plan is None for r in results if r.status == Status.BUDGET_EXHAUSTED))

    def test_arc_switch_revokes_old_permission(self):
        profile = replace(base.Profile(), max_pitch=20.)
        snapshot = base.Snapshot((400., 0., 0.), base.ZERO)
        candidate, _ = Lead(Kernel(profile)).solve(snapshot.curve(), 'LOW')
        pose = candidate_pose(candidate)
        mount = Mount(pose=pose, visible_pose=pose)
        planner, state = Planner(profile), TrackState()
        obs = Observation(snapshot, 0)
        low = planner.plan(obs, 0, mount, 'LOW', state)
        self.assertEqual(low.status, Status.READY)
        self.assertTrue(planner.can_trigger(low, obs, 0, mount, 'LOW', state=state))
        high = planner.plan(obs, 0, mount, 'HIGH', state)
        self.assertNotEqual(high.status, Status.READY)
        self.assertFalse(planner.can_trigger(low, obs, 0, mount, 'LOW', state=state))
        self.assertEqual(state.selected_arc, 'HIGH')

    def test_synthetic_pose_quantization_is_not_silently_accepted(self):
        obs = Observation(base.Snapshot((400., 0., 0.), base.ZERO), 0)
        result = Planner(pose_adapter=IdealPose(angular_step=1.)).plan(obs, 0, Mount(), 'LOW')
        self.assertEqual(result.status, Status.VERIFY_REJECTED)

    def test_solution_disappears_and_returns_without_stale_permission(self):
        snapshot = base.Snapshot((400., 0., 0.), base.ZERO)
        candidate, _ = Lead(Kernel(base.Profile())).solve(snapshot.curve(), 'LOW')
        pose = candidate_pose(candidate)
        mount = Mount(pose=pose, visible_pose=pose)
        planner, state = Planner(), TrackState()
        original = Observation(snapshot, 0)
        ready = planner.plan(original, 0, mount, 'LOW', state)
        self.assertEqual(ready.status, Status.READY)
        far = replace(original, snapshot=replace(snapshot, p=(5000., 0., 0.)), revision=1)
        lost = planner.plan(far, 0, mount, 'LOW', state)
        self.assertEqual(lost.status, Status.NO_POINT_IN_WINDOW)
        self.assertFalse(planner.can_trigger(ready, original, 0, mount, 'LOW', state=state))
        returned = replace(original, revision=2)
        restored = planner.plan(returned, 0, mount, 'LOW', state)
        self.assertEqual(restored.status, Status.READY)
        self.assertTrue(planner.can_trigger(restored, returned, 0, mount, 'LOW', state=state))
        self.assertLess(check_plan_reference(planner.profile, returned, 0, mount, restored.plan, 0), 1e-7)

    def test_both_arcs_and_identity_invalidation(self):
        planner, state = Planner(), TrackState()
        observation = Observation(base.Snapshot((400., 0., 0.), base.ZERO), 0)
        low = planner.plan(observation, 0, Mount(), 'LOW', state)
        high = planner.plan(observation, 0, Mount(), 'HIGH', state)
        self.assertEqual(low.status, Status.AIMING)
        self.assertEqual(high.status, Status.AIMING)
        self.assertLess(low.plan.pose.pitch, high.plan.pose.pitch)
        self.assertEqual(set(state.caches), {'LOW', 'HIGH'})
        for changed_observation, changed_mount, changed_profile in (
                (replace(observation, target_id='new'), Mount(), planner.profile),
                (replace(observation, target_id='new'), Mount(geometry_revision=1), planner.profile),
                (replace(observation, target_id='new'), Mount(geometry_revision=1),
                 replace(planner.profile, speed=9.))):
            old_caches = state.caches
            planner.profile = changed_profile
            result = planner.plan(changed_observation, 0, changed_mount, 'LOW', state)
            self.assertIsNot(state.caches, old_caches)
            self.assertNotIn('HIGH', state.caches)
            self.assertIn(result.status, (Status.AIMING, Status.READY))
            self.assertLess(check_plan_reference(changed_profile, changed_observation, 0,
                                                changed_mount, result.plan, 0), 1e-7)

    def test_wall_deadline(self):
        obs = Observation(base.Snapshot((400., 0., 0.), base.ZERO), 0)
        budget = Budget(10000, deadline_ns=time.perf_counter_ns()-1)
        result = Planner().plan(obs, 0, Mount(), 'LOW', shared_budget=budget)
        self.assertEqual(result.status, Status.BUDGET_EXHAUSTED)
        self.assertEqual(budget.used, 0)

    def test_claim_once_and_hold_until_actual_ack(self):
        snapshot = base.Snapshot((400., 0., 0.), base.ZERO)
        candidate, _ = Lead(Kernel(base.Profile())).solve(snapshot.curve(), 'LOW')
        pose = candidate_pose(candidate)
        mount = Mount(pose=pose, visible_pose=pose, trigger_delay=3)
        observation = Observation(snapshot, 0)
        planner, state = Planner(), TrackState()
        result = planner.plan(observation, 0, mount, 'LOW', state)
        self.assertTrue(planner.claim_trigger(result, observation, 0, mount, 'LOW', state))
        self.assertFalse(planner.claim_trigger(result, observation, 0, mount, 'LOW', state))
        for now in (1, 3, 4):
            changed = replace(observation, target_id='different-target', revision=now)
            waiting = planner.plan(changed, now, mount, 'HIGH', state)
            self.assertEqual(waiting.status, Status.WAITING_SPAWN)
            self.assertIs(waiting.plan, result.plan)
        self.assertEqual(planner.acknowledge_trigger(state, 4), 'LAUNCHED_AT_DIFFERENT_TICK')
        self.assertIsNone(state.committed_plan)


def actual_sweep_oracle(profile, pose, n):
    """Independent tick recurrence using launch angles, not the solver's u/f."""
    pitch, yaw = math.radians(pose.pitch), math.radians(pose.yaw)
    u = (-math.cos(pitch)*math.sin(yaw), math.sin(pitch), math.cos(pitch)*math.cos(yaw))
    position, velocity = base.scale(u, profile.length), base.scale(u, profile.speed)
    for tick in range(n+1):
        speed = math.sqrt(base.dot(velocity, velocity))
        drag = min(profile.drag*speed, speed)
        force = base.scale(velocity, -drag/speed) if speed >= 1e-4 else base.ZERO
        force = base.add(force, (0., -profile.gravity, 0.))
        sweep = base.add(velocity, force)
        if tick == n:
            return position, sweep
        position = base.add(position, base.add(velocity, base.scale(force, .5)))
        velocity = sweep


def check_plan_reference(profile, observation, now, mount, plan, phase, tolerance=1e-6):
    wait = plan.launch_tick-now
    offset = now-observation.sample_tick+wait+phase
    target = base.target_oracle(replace(observation.snapshot, offset=offset), plan.candidate.n)
    point_error = base.distance(target, plan.target_point)
    assert point_error < 1e-7
    launch_pose = mount.visible_pose if plan.command_tick == now else plan.pose
    start, sweep = actual_sweep_oracle(profile, launch_pose, plan.candidate.n)
    norm2 = base.dot(sweep, sweep)
    assert norm2 >= .0001
    fraction = min(1., max(0., base.dot(base.add(target, base.scale(start, -1.)), sweep)/norm2))
    error = base.distance(target, base.add(start, base.scale(sweep, fraction)))
    assert error <= tolerance+1e-7, error
    assert mount.earliest_launch(plan.pose) <= wait
    assert plan.command_tick+mount.trigger_delay == plan.launch_tick
    return error


class WholeAlgorithmTests(unittest.TestCase):
    def test_manoeuvres_with_full_forecast_and_actuator_loop(self):
        accepted, worst_error = 0, 0.
        for arc in ('LOW', 'HIGH'):
            planner, state = Planner(), TrackState()
            snapshot = base.Snapshot((350., 5., 20.), (.2, .01, .1), eta=1)
            mount = Mount(pose=Pose(0, 50), visible_pose=Pose(0, 50), trigger_delay=2)
            for now in range(60):
                observation = Observation(snapshot, now, revision=now)
                result = planner.plan(observation, now, mount, arc, state, phase=1)
                if result.status in (Status.READY, Status.AIMING):
                    # Each shot is evaluated against the forecast available NOW;
                    # a later manoeuvre is not retroactively part of that input.
                    worst_error = max(worst_error, check_plan_reference(
                        planner.profile, observation, now, mount, result.plan, 1))
                    accepted += 1
                    desired = result.plan.pose
                else:
                    self.assertIsNone(result.plan)
                    desired = mount.pose
                dy = angle_difference(desired.yaw, mount.pose.yaw)
                dp = desired.pitch-mount.pose.pitch
                pose = Pose(mount.pose.pitch+max(-mount.pitch_rate, min(mount.pitch_rate, dp)),
                            mount.pose.yaw+max(-mount.yaw_rate, min(mount.yaw_rate, dy)))
                settled = (abs(angle_difference(pose.yaw, mount.pose.yaw)) < 1e-9
                           and abs(pose.pitch-mount.pose.pitch) < 1e-9)
                mount = replace(mount, pose=pose, visible_pose=mount.pose, settled=settled,
                                revision=now+1)
                acceleration = (base.scale(snapshot.v, -2.) if now == 29 else
                                ((.0004, 0., -.0002) if now < 40 else (-.0003, 0., .0001)))
                snapshot = snapshot.advance(acceleration)
        self.assertGreater(accepted, 80)
        self.assertLess(worst_error, 1e-7)
        print('\nmanoeuvre-loop', 'accepted', accepted, 'max_error', worst_error)

    def test_zero_drag_apex_does_not_invalidate_later_flight(self):
        profile = base.Profile(drag=0., min_pitch=-90., max_pitch=90.)
        kernel = Kernel(profile)
        coeff = kernel.coefficients(200)
        candidate = base.Candidate(200, .5, (0., 1., 0.), 90., 'HIGH', coeff, profile)
        start, sweep = actual_sweep_oracle(profile, Pose(90., 0.), 200)
        point = base.add(start, base.scale(sweep, .5))
        valid, error, reason = verify(profile, candidate, point, Pose(90., 0.), IdealPose(), 1e-6, kernel)
        self.assertTrue(valid, reason)
        self.assertLess(error, 1e-7)

    def test_random_complete_requests(self):
        rng = random.Random(20261031)
        statuses, accepted, max_error = Counter(), 0, 0.
        for i in range(300):
            profile = base.Profile(speed=rng.uniform(5, 11), length=rng.uniform(0, 6),
                                   drag=rng.choice((0., 1e-8, .01, .03)),
                                   gravity=rng.choice((.025, .05)), last_sweep=rng.choice((99, 299, 599)))
            snapshot = base.Snapshot((rng.uniform(40, 600), rng.uniform(-60, 100), rng.uniform(-100, 100)),
                                     (rng.uniform(-.5, .5), rng.uniform(-.04, .04), rng.uniform(-.3, .3)),
                                     (rng.uniform(-.0005, .0005), rng.uniform(-.0001, .0001),
                                      rng.uniform(-.0005, .0005)), eta=rng.choice((-1, 0, 1)))
            age, phase = rng.randrange(6), rng.randrange(2)
            observation = Observation(snapshot, 100-age, str(i), i)
            pose = Pose(rng.uniform(-10, 30), rng.uniform(-180, 180))
            mount = Mount(pose=pose, visible_pose=pose, yaw_rate=rng.choice((2., 4., 8.)),
                          pitch_rate=rng.choice((1., 3., 6.)), trigger_delay=rng.randrange(4),
                          ready_after=rng.randrange(9), pose_lag=rng.randrange(3))
            planner = Planner(profile, Policy(work_limit=2500, max_launch_delay=160))
            arc = 'LOW' if i % 2 == 0 else 'HIGH'
            result = planner.plan(observation, 100, mount, arc, phase=phase)
            statuses[result.status.value] += 1
            if result.status in (Status.READY, Status.AIMING):
                self.assertEqual(result.plan.candidate.arc, arc)
                max_error = max(max_error, check_plan_reference(profile, observation, 100, mount, result.plan, phase))
                accepted += 1
            else:
                self.assertIsNone(result.plan)
        self.assertGreater(accepted, 100)
        print('\nwhole-random', dict(statuses), 'accepted', accepted, 'max_error', max_error)

    def test_interval_coefficients_enclose_high_precision_formula(self):
        with localcontext() as context:
            context.prec = 90
            for drag in (1e-10, 1e-7, .01, .5):
                for n in (0, 1, 20, 599):
                    profile = base.Profile(drag=drag)
                    intervals = interval_coefficients(profile, n)
                    k = Decimal.from_float(drag)
                    q = 1-k
                    z = q**n
                    A = (1-k/2)*(1-z)/k
                    B = (Decimal(n)-A)/k
                    exact = (Decimal(2)+8*A, 8*q*z, B, (1-q*z)/k)
                    for bound, value in zip(intervals, exact):
                        self.assertLessEqual(Decimal.from_float(bound.lo), value)
                        self.assertGreaterEqual(Decimal.from_float(bound.hi), value)

    def test_neighbour_derivatives_with_tiny_drag(self):
        for drag in (0., 1e-10, 1e-7, .01):
            kernel = base.PointKernel(base.Profile(drag=drag))
            coefficient = kernel.coefficients(200)
            for _ in range(20):
                coefficient = kernel.neighbour(coefficient, 1)
            direct = kernel.coefficients(220)
            self.assertLess(abs(coefficient.Bp-direct.Bp), 1e-8)
            self.assertLess(abs(coefficient.B-direct.B), 1e-7)

    def test_known_shots_are_never_excluded_by_range_proof(self):
        rng = random.Random(818)
        for _ in range(80):
            profile = base.Profile(drag=rng.choice((0., .002, .01)))
            n = rng.randrange(1, 200)
            pose = Pose(rng.uniform(-20, 70), rng.uniform(-180, 180))
            start, sweep = actual_sweep_oracle(profile, pose, n)
            point = base.add(start, base.scale(sweep, rng.random()))
            snapshot = base.Snapshot(point, base.ZERO)
            self.assertFalse(window_excluded(profile, snapshot, 0, 0, 100, 1e-6))

    def test_horizon_edges_and_phase(self):
        profile = base.Profile(last_sweep=99)
        kernel = base.PointKernel(profile)
        for n in (0, 1, 99):
            start, sweep = actual_sweep_oracle(profile, Pose(20, -90), n)
            point = base.add(start, base.scale(sweep, .4))
            candidates = kernel.candidates(point, n)
            self.assertTrue(candidates)
            for c in candidates:
                self.assertLess(base.distance(base.cbc_oracle(profile, c), point), 1e-7)
            self.assertEqual(kernel.candidates(point, 100), [])

    def test_gravity_free_high_arc_is_explicit(self):
        result = Planner(base.Profile(gravity=0)).plan(
            Observation(base.Snapshot((100., 0., 0.), base.ZERO), 0), 0, Mount(), 'HIGH')
        self.assertEqual(result.status, Status.NO_SELECTED_ARC)


if __name__ == '__main__':
    unittest.main()
