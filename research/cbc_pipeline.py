"""Research pipeline for CBC aiming. No Minecraft/Java integration.

The actuator and phase contracts are explicit inputs. IdealPose is a test adapter,
not an implementation of Create float/LUT transforms. Runtime uses analytic
ballistics; per-tick recurrences exist only in test_cbc_pipeline.py.
"""
from collections import Counter
from contextvars import ContextVar
from dataclasses import dataclass, field, replace
from enum import Enum
import heapq
import math as native_math
import time

try:
    from . import cbc_lead_model as base
except ImportError:
    import cbc_lead_model as base


class WorkLimit(Exception):
    pass


@dataclass
class Budget:
    limit: int = 1600
    shared: object = None
    used: int = 0
    counts: Counter = field(default_factory=Counter)
    deadline_ns: object = None

    def charge(self, name):
        chain, node = [], self
        while node is not None:
            chain.append(node)
            if node.deadline_ns is not None and time.perf_counter_ns() >= node.deadline_ns:
                raise WorkLimit('wall-clock deadline')
            if node.used >= node.limit:
                raise WorkLimit('shared' if node is not self else 'request')
            node = node.shared
        for node in chain:
            node.used += 1
            node.counts[name] += 1


CURRENT_BUDGET = ContextVar('cbc_research_budget', default=None)


def charge(name):
    budget = CURRENT_BUDGET.get()
    if budget is not None:
        budget.charge(name)


class CountedMath:
    def __init__(self):
        for name in ('sqrt', 'hypot', 'exp', 'expm1', 'log', 'log1p', 'atan2', 'acos', 'sin', 'cos', 'tan'):
            def counted(*args, _name=name, _fn=getattr(native_math, name)):
                charge(_name)
                return _fn(*args)
            setattr(self, name, counted)

    def __getattr__(self, name):
        return getattr(native_math, name)


math = CountedMath()
base.math = math


class Status(str, Enum):
    READY = 'READY'
    AIMING = 'AIMING'
    WAITING_SPAWN = 'WAITING_SPAWN'
    STALE_INPUT = 'STALE_INPUT'
    INVALID_INPUT = 'INVALID_INPUT'
    UNSUPPORTED_MODEL = 'UNSUPPORTED_MODEL'
    CONTROL_UNAVAILABLE = 'CONTROL_UNAVAILABLE'
    NO_POINT_AT_LAUNCH = 'NO_POINT_AT_LAUNCH'
    NO_POINT_IN_WINDOW = 'NO_POINT_IN_WINDOW'
    NO_SELECTED_ARC = 'NO_SELECTED_ARC'
    SEARCH_PENDING = 'SEARCH_PENDING'
    UNRESOLVED = 'UNRESOLVED'
    DELAY_LIMIT = 'DELAY_LIMIT'
    VERIFY_REJECTED = 'VERIFY_REJECTED'
    BUDGET_EXHAUSTED = 'BUDGET_EXHAUSTED'


@dataclass(frozen=True)
class Observation:
    snapshot: base.Snapshot
    sample_tick: int
    target_id: str = 'target'
    revision: int = 0
    observing: bool = True


@dataclass(frozen=True)
class Pose:
    pitch: float = 0.
    yaw: float = 0.


def angle_difference(target, current):
    return (target-current+180.) % 360.-180.


def candidate_pose(candidate):
    return Pose(candidate.pitch,
                math.degrees(math.atan2(-candidate.direction[0], candidate.direction[2])))


@dataclass(frozen=True)
class Mount:
    pose: Pose = Pose()
    visible_pose: Pose = Pose()  # pose actually read by the spawn transform NOW
    yaw_rate: float = 4.        # degrees per tick, supplied by actuator adapter
    pitch_rate: float = 3.
    ready_after: int = 0       # remaining reload/cooldown; may overlap rotation
    trigger_delay: int = 0     # remaining delay after issuing an eligible trigger
    pose_lag: int = 1
    settled: bool = True
    simultaneous_axes: bool = True
    controllable: bool = True
    can_hold: bool = True
    revision: int = 0
    geometry_revision: int = 0

    def travel_ticks(self, desired):
        charge('actuator')
        distances = (abs(angle_difference(desired.yaw, self.pose.yaw)),
                     abs(desired.pitch-self.pose.pitch))
        ticks = []
        for distance, rate in zip(distances, (self.yaw_rate, self.pitch_rate)):
            if distance <= 1e-9:
                ticks.append(0)
            elif rate <= 0:
                return None
            else:
                ticks.append(math.ceil(distance/rate))
        return max(ticks) if self.simultaneous_axes else sum(ticks)

    def earliest_launch(self, desired):
        travel = self.travel_ticks(desired)
        if travel is None:
            return None
        settle = 0 if travel == 0 and self.settled else self.pose_lag
        return max(travel+settle, self.ready_after)+self.trigger_delay

    def worst_launch_delay(self, profile):
        if self.yaw_rate <= 0 or self.pitch_rate <= 0:
            return None
        yaw = math.ceil(180./self.yaw_rate)
        pitch = math.ceil(max(abs(profile.min_pitch-self.pose.pitch),
                              abs(profile.max_pitch-self.pose.pitch))/self.pitch_rate)
        travel = max(yaw, pitch) if self.simultaneous_axes else yaw+pitch
        return max(travel+self.pose_lag, self.ready_after)+self.trigger_delay


@dataclass(frozen=True)
class IdealPose:
    angular_step: float = 0.  # optional SYNTHETIC quantization for guard tests

    def command(self, pose):
        if not self.angular_step:
            return pose
        return Pose(round(pose.pitch/self.angular_step)*self.angular_step,
                    round(pose.yaw/self.angular_step)*self.angular_step)

    def forward(self, profile, pose):
        charge('pose_forward')
        theta, yaw = math.radians(pose.pitch), math.radians(pose.yaw)
        c = math.cos(theta)
        u = (-c*math.sin(yaw), math.sin(theta), c*math.cos(yaw))
        return base.scale(u, profile.length), base.scale(u, profile.speed)


@dataclass(frozen=True)
class Policy:
    max_age: int = 20
    max_launch_delay: int = 100
    delay_rounds: int = 4
    point_tolerance: float = 1e-6
    work_limit: int = 1600
    reserve_nodes: int = 96
    reserve_lead_ticks: int = 2
    allow_reserve: bool = True


@dataclass(frozen=True)
class Plan:
    candidate: base.Candidate
    pose: Pose
    launch_tick: int
    command_tick: int
    target_point: tuple
    prediction_dt: int
    target_id: str
    observation_revision: int
    sample_tick: int
    mount_revision: int
    arc: str
    residual: float
    delay_trace: tuple
    path: str
    request_key: tuple


@dataclass
class TrackState:
    identity: object = None
    caches: dict = field(default_factory=dict)
    plan: object = None
    selected_arc: str = 'LOW'
    reserve_jobs: dict = field(default_factory=dict)
    pending_launch: dict = field(default_factory=dict)
    committed_plan: object = None


@dataclass
class Result:
    status: Status
    reason: str
    plan: object = None
    counts: dict = field(default_factory=dict)
    work: int = 0


class Forecast(base.Curve):
    def position(self, t):
        charge('forecast_position')
        return super().position(t)

    def derivative(self, t):
        charge('forecast_derivative')
        return super().derivative(t)


class Kernel(base.PointKernel):
    def __init__(self, profile):
        charge('profile_prepare')
        super().__init__(profile)
        self._regime_guaranteed = None
        self._last_interval = None

    def last_interval(self):
        if self._last_interval is None:
            self._last_interval = interval_coefficients(self.p, self.p.last_sweep)
        return self._last_interval

    def regime_guaranteed(self):
        if self._regime_guaranteed is not None:
            return self._regime_guaranteed
        charge('profile_regime_bound')
        p = self.p
        if p.drag == 0:
            self._regime_guaranteed = True  # normalize() cannot change zero drag
        elif p.gravity == 0:
            _, U, _, _ = self.last_interval()
            speed = U/(1-Interval.exact(p.drag))
            self._regime_guaranteed = speed.lo >= 1e-4
        else:
            # Minimum over ALL z in [0,1] and allowed elevations. Scale by k
            # before forming the rational expression to avoid g/k overflow.
            uy = math.sin(math.radians(p.max_pitch))
            uy_bound = Interval(uy-1e-14, min(1., uy+1e-14))
            s, g, k = map(Interval.exact, (p.speed, p.gravity, p.drag))
            bound = (s.square()*g.square()*(1-uy_bound.square())
                     /(s.square()*k.square()+g.square()+2*s*g*k*uy_bound))
            self._regime_guaranteed = bound.lo > 1e-8
        return self._regime_guaranteed

    def coefficients(self, t):
        charge('coefficients')
        return super().coefficients(t)

    def neighbour(self, value, step):
        charge('coefficient_neighbour')
        return super().neighbour(value, step)

    def candidates(self, point, n, coeff=None):
        charge('quadratic_candidate')
        return super().candidates(point, n, coeff)

    def static_seed(self, point, arc):
        """Analytic derivatives + bracketed Newton; no 48-step angle bisection."""
        charge('static_seed')
        p, r = self.p, math.hypot(point[0], point[2])
        if r <= p.length+p.speed or p.gravity <= 0:
            return None
        last = self.coefficients(p.last_sweep)
        radius_max = p.length+p.speed*last.A+p.speed*self.q*last.z
        if r >= radius_max:
            return None
        mmax = math.sqrt((radius_max/r)**2-1)
        lo = max(-mmax, math.tan(math.radians(p.min_pitch)))
        hi = min(mmax, math.tan(math.radians(p.max_pitch)))
        if lo >= hi:
            return None

        def derivatives(m):
            charge('static_derivatives')
            w = math.sqrt(1+m*m)
            rho = r*w
            rp, rpp = r*m/w, r/(w*w*w)
            if p.drag:
                gap = p.length+p.speed*self.c/p.drag-rho
                if gap <= 0:
                    return None
                W = p.gravity/p.drag*(1/(-self.lam*gap)-1/p.speed)
                Wp = p.gravity/(-p.drag*self.lam*gap*gap)
            else:
                W = p.gravity*(rho-p.length)/(p.speed*p.speed)
                Wp = p.gravity/(p.speed*p.speed)
            return rho, r-W*rp, -Wp*rp*rp-W*rpp

        def height(m):
            charge('static_height')
            d = derivatives(m)
            if d is None:
                return None
            A = (d[0]-p.length)/p.speed
            if p.drag:
                fraction = A*p.drag/self.c
                if not 0 <= fraction < 1:
                    return None
                t = math.log1p(-fraction)/self.lam
                B = (t-A)/p.drag if p.drag*max(1., abs(t)) >= .001 else self.coefficients(t).B
            else:
                t, B = A, .5*A*A
            return r*m-point[1]-p.gravity*B, d[1], t

        dl, dh = derivatives(lo), derivatives(hi)
        if dl is None or dh is None:
            return None
        if dl[1] <= 0:
            peak = lo
        elif dh[1] >= 0:
            peak = hi
        else:
            a, b, peak = lo, hi, (lo+hi)/2
            for _ in range(24):
                _, first, second = derivatives(peak)
                if abs(first) <= 1e-10*max(1., r):
                    break
                if first > 0:
                    a = peak
                else:
                    b = peak
                trial = peak-first/second if second else math.nan
                peak = trial if a < trial < b else (a+b)/2
            else:
                return None
        a, b = (lo, peak) if arc == 'LOW' else (peak, hi)
        va, vb = height(a), height(b)
        if va is None or vb is None or va[0]*vb[0] > 0:
            return None
        m = (a+b)/2
        for _ in range(28):
            value = height(m)
            if value is None:
                return None
            error, derivative, t = value
            if abs(error) <= 1e-8:
                return t
            if error*va[0] > 0:
                a, va = m, value
            else:
                b = m
            trial = m-error/derivative if derivative else math.nan
            m = trial if a < trial < b else (a+b)/2
        return None


class Lead(base.LeadCoordinator):
    def evaluate(self, curve, t, coeff=None):
        charge('lead_evaluation')
        return super().evaluate(curve, t, coeff)


@dataclass(frozen=True)
class Interval:
    """Outward IEEE-double arithmetic; transcendental functions are avoided."""
    lo: float
    hi: float

    @staticmethod
    def exact(x):
        return Interval(float(x), float(x))

    @staticmethod
    def enclosure(lo, hi):
        return Interval(math.nextafter(lo, -math.inf), math.nextafter(hi, math.inf))

    def __add__(self, value):
        b = value if isinstance(value, Interval) else Interval.exact(value)
        return Interval.enclosure(self.lo+b.lo, self.hi+b.hi)

    __radd__ = __add__

    def __neg__(self):
        return Interval(-self.hi, -self.lo)

    def __sub__(self, value):
        return self+(-value if isinstance(value, Interval) else -Interval.exact(value))

    def __rsub__(self, value):
        return Interval.exact(value)+(-self)

    def __mul__(self, value):
        b = value if isinstance(value, Interval) else Interval.exact(value)
        products = (self.lo*b.lo, self.lo*b.hi, self.hi*b.lo, self.hi*b.hi)
        if not all(math.isfinite(x) for x in products):
            return Interval(-math.inf, math.inf)
        return Interval.enclosure(min(products), max(products))

    __rmul__ = __mul__

    def __truediv__(self, value):
        b = value if isinstance(value, Interval) else Interval.exact(value)
        if b.lo <= 0 <= b.hi:
            return Interval(-math.inf, math.inf)
        reciprocal = Interval.enclosure(1/b.hi, 1/b.lo)
        return self*reciprocal

    def square(self):
        low = 0. if self.lo <= 0 <= self.hi else min(self.lo*self.lo, self.hi*self.hi)
        high = max(self.lo*self.lo, self.hi*self.hi)
        return Interval.enclosure(low, high)


def interval_coefficients(profile, n):
    charge('interval_coefficients')
    k, s, L = Interval.exact(profile.drag), profile.speed, profile.length
    if profile.drag == 0:
        t = Interval.exact(n)
        return L+s*t, Interval.exact(s), t.square()*.5, t+1
    q = 1-k
    z, factor, exponent = Interval.exact(1), q, n
    while exponent:  # exponentiation by squaring: O(log n), no flight simulation
        if exponent & 1:
            z = z*factor
        factor = factor*factor
        exponent >>= 1
    c = 1-k*.5
    A = c*(1-z)/k
    B = (n-A)/k
    return L+s*A, s*q*z, B, (1-q*z)/k


def curve_bounds(curve, a, b, tolerance=0.):
    t = Interval.exact(a) if a == b else Interval(float(a), float(b))
    return tuple(Interval.exact(p)+t*(Interval.exact(v)+.5*acc*t)
                 +Interval(-tolerance, tolerance)
                 for p, v, acc in zip(curve.p, curve.v, curve.a))


def norm2_bounds(values):
    out = Interval.exact(0.)
    for v in values:
        out = out+v.square()
    return out


def window_excluded(profile, snapshot, age, phase, max_delay, tolerance, last_coeff=None):
    """Conservative horizontal range proof for ALL allowed launch delays."""
    charge('window_bound')
    v = base.add(snapshot.v, base.scale(snapshot.a, .5*snapshot.eta))
    curve = base.Curve(snapshot.p, v, snapshot.a)
    start = max(0, age+phase)
    end = age+phase+max_delay+profile.last_sweep
    if end < start:
        return False
    box = curve_bounds(curve, start, end, tolerance)
    R, U, _, _ = interval_coefficients(profile, profile.last_sweep) if last_coeff is None else last_coeff
    return norm2_bounds((box[0], box[2])).lo > (R+U).square().hi


@dataclass
class ReserveJob:
    key: tuple
    heap: list
    uncertain_leaves: bool = False
    visited: int = 0
    coeff_cache: dict = field(default_factory=dict)
    delayed_witness: object = None


@dataclass(frozen=True)
class ReserveAnswer:
    candidate: object
    status: Status
    reason: str


def reserve_search(kernel, curve, arc, tolerance, node_limit, jobs, key, hint=0., accept=None):
    """Bounded branch-and-bound over integer sweep indices, using closed forms.

    No enumeration of all ticks is scheduled up front. Limits may leave work
    pending. NO_POINT_AT_LAUNCH requires every interval to have been excluded;
    failure of the floating quadratic solver alone is NEVER such a proof.
    """
    profile = kernel.p
    job = jobs.get(arc)
    if job is None or job.key != key:
        job = ReserveJob(key, [(0., 0, profile.last_sweep)])
        jobs[arc] = job

    def coeff(n):
        if n not in job.coeff_cache:
            job.coeff_cache[n] = interval_coefficients(profile, n)
        return job.coeff_cache[n]

    def excluded(a, b):
        box = curve_bounds(curve, a, b, tolerance)
        Ra, _, Ba, _ = coeff(a)
        Rb, Ub, Bb, Vb = coeff(b)
        drop = Interval(Ba.lo, (Bb+Vb).hi)*profile.gravity
        adjusted = (box[0], box[1]+drop, box[2])
        norm = norm2_bounds(adjusted)
        radius_max, radius_min = Rb+Ub, Ra
        return norm.lo > radius_max.square().hi or norm.hi < radius_min.square().lo

    def leaf_excluded(n):
        x, y, z = curve_bounds(curve, n, n, tolerance)
        R, U, B, V = coeff(n)
        Y, w = y+profile.gravity*B, profile.gravity*V
        constant = x.square()+Y.square()+z.square()-R.square()
        half_linear = w*Y-R*U
        quadratic = w.square()-U.square()
        bernstein = (constant, constant+half_linear, constant+2*half_linear+quadratic)
        return all(c.lo > 0 for c in bernstein) or all(c.hi < 0 for c in bernstein)

    def priority(a, b):
        return max(a-hint, hint-b, 0.)

    for _ in range(node_limit):
        if not job.heap:
            break
        item = heapq.heappop(job.heap)
        _, a, b = item
        try:
            charge('reserve_node')
            job.visited += 1
            if excluded(a, b):
                continue
            if a == b:
                point = curve.position(a)
                for candidate in kernel.candidates(point, a):
                    if candidate.arc == arc and (accept is None or accept(candidate)):
                        # The caller has a witness; future revisions will search
                        # again if needed. Do not reuse a partial no-solution proof.
                        jobs.pop(arc, None)
                        return ReserveAnswer(candidate, Status.AIMING, 'RESERVE_WITNESS')
                if not leaf_excluded(a):
                    job.uncertain_leaves = True
                continue
            middle = (a+b)//2
            heapq.heappush(job.heap, (priority(a, middle), a, middle))
            heapq.heappush(job.heap, (priority(middle+1, b), middle+1, b))
        except WorkLimit:
            heapq.heappush(job.heap, item)  # no lost interval on preemption
            raise
    if job.heap:
        return ReserveAnswer(None, Status.SEARCH_PENDING, 'RESERVE_NODE_BUDGET')
    if job.uncertain_leaves:
        return ReserveAnswer(None, Status.UNRESOLVED, 'ARC_OR_NUMERICAL_UNCERTAINTY')
    return ReserveAnswer(None, Status.NO_POINT_AT_LAUNCH, 'ALL_INTERVALS_EXCLUDED')


def verify(profile, candidate, point, pose, pose_adapter, tolerance, kernel):
    """Independent forward formula from the commanded/observed launch pose."""
    charge('verification')
    if not profile.min_pitch <= pose.pitch <= profile.max_pitch:
        return False, math.inf, 'PITCH_LIMIT'
    p0, v0 = pose_adapter.forward(profile, pose)
    k = profile.drag
    G = (0., -profile.gravity, 0.)
    coeff = kernel.coefficients(candidate.n)  # fresh guard, not cached p/v
    if k and not kernel.regime_guaranteed():
        speed2 = base.dot(v0, v0)
        g = profile.gravity
        denominator = speed2*k*k+g*g+2*g*v0[1]*k
        one_minus_z = ((speed2*k*k+g*v0[1]*k)/denominator
                       if denominator > 0 else -1.)
        indices = {0, candidate.n}
        if 0 < one_minus_z < 1:
            vertex = math.log1p(-one_minus_z)/kernel.lam
            nearest = math.floor(vertex)
            indices.update(j for j in range(nearest-1, nearest+3) if 0 <= j <= candidate.n)
        for j in indices:
            cj = coeff if j == candidate.n else kernel.coefficients(j)
            S = cj.A/(1-k/2)
            velocity = base.add(base.scale(v0, cj.z), base.scale(G, S))
            if base.dot(velocity, velocity) < 1e-8:
                return False, math.inf, 'NORMALIZATION_REGIME'
    start = base.add(p0, base.add(base.scale(v0, coeff.A), base.scale(G, coeff.B)))
    V = coeff.A/(1-k/2)+coeff.z if k else candidate.n+1
    sweep = base.add(base.scale(v0, (1-k)*coeff.z), base.scale(G, V))
    length2 = base.dot(sweep, sweep)
    if length2 < .0001:
        return False, math.inf, 'CBC_SWEEP_CUTOFF'
    diff = base.add(point, base.scale(start, -1.))
    f = min(1., max(0., base.dot(diff, sweep)/length2))
    error = base.distance(base.add(start, base.scale(sweep, f)), point)
    return error <= tolerance, error, 'OK' if error <= tolerance else 'POSE_MISS'


class Planner:
    def __init__(self, profile=base.Profile(), policy=Policy(), pose_adapter=IdealPose()):
        self.profile, self.policy, self.pose_adapter = profile, policy, pose_adapter
        self._kernel = None

    def plan(self, observation, now, mount, arc, state=None, *, phase=0, shared_budget=None):
        state = TrackState() if state is None else state
        meter = Budget(self.policy.work_limit, shared_budget)
        token = CURRENT_BUDGET.set(meter)
        try:
            result = self._plan(observation, now, mount, arc, state, phase)
        except WorkLimit as exc:
            state.plan = None
            result = Result(Status.BUDGET_EXHAUSTED, str(exc))
        except (ArithmeticError, ValueError) as exc:
            state.plan = None
            result = Result(Status.UNRESOLVED, 'NUMERICAL_UNCERTAINTY: '+str(exc))
        finally:
            CURRENT_BUDGET.reset(token)
        result.counts, result.work = dict(meter.counts), meter.used
        return result

    def request_key(self, observation, now, mount, arc, phase):
        return (observation, now, mount, arc, phase, self.profile, self.policy, self.pose_adapter)

    def can_trigger(self, result, observation, now, mount, arc, *, state, phase=0):
        """Final atomic caller check, immediately before an external trigger.

        READY authorizes the command NOW. The shell may spawn later. No tool or
        game side effect is performed here. After an irreversible trigger, later
        target observations do not retroactively invalidate its original plan.
        """
        return (result.status == Status.READY and result.plan is not None
                and result.plan.command_tick == now
                and state.plan is result.plan and state.selected_arc == arc
                and state.committed_plan is None
                and result.plan.request_key == self.request_key(observation, now, mount, arc, phase))

    def claim_trigger(self, result, observation, now, mount, arc, state, *, phase=0):
        if not self.can_trigger(result, observation, now, mount, arc, phase=phase, state=state):
            return False
        state.committed_plan, state.plan = result.plan, None
        return True

    def acknowledge_trigger(self, state, actual_launch_tick=None):
        committed = state.committed_plan
        if committed is None:
            return 'NO_PENDING_COMMAND'
        if actual_launch_tick is not None and actual_launch_tick < committed.command_tick:
            return 'INVALID_ACKNOWLEDGEMENT'
        state.committed_plan = None
        if actual_launch_tick is None:
            return 'COMMAND_CANCELLED_OR_REJECTED'
        return ('LAUNCHED_ON_TIME' if actual_launch_tick == committed.launch_tick
                else 'LAUNCHED_AT_DIFFERENT_TICK')

    def _plan(self, observation, now, mount, arc, state, phase):
        p, policy, snapshot = self.profile, self.policy, observation.snapshot
        if state.committed_plan is not None:
            state.plan = None
            reason = ('hold committed pose until spawn' if now < state.committed_plan.launch_tick
                      else 'await actual spawn/cancellation acknowledgement; ETA is not confirmation')
            return Result(Status.WAITING_SPAWN, reason, state.committed_plan)
        numbers = (*snapshot.p, *snapshot.v, *snapshot.a, p.speed, p.length, p.drag, p.gravity,
                   p.min_pitch, p.max_pitch, mount.pose.pitch, mount.pose.yaw,
                   mount.visible_pose.pitch, mount.visible_pose.yaw, mount.yaw_rate, mount.pitch_rate)
        state.plan, previous_plan = None, state.plan
        if (not all(math.isfinite(x) for x in numbers) or arc not in ('LOW', 'HIGH')
                or snapshot.eta not in (-1, 0, 1) or snapshot.offset != 0
                or not all(isinstance(x, int) for x in (now, observation.sample_tick, phase,
                                                       mount.ready_after, mount.trigger_delay, mount.pose_lag,
                                                       p.last_sweep))
                or observation.sample_tick > now or min(mount.ready_after, mount.trigger_delay, mount.pose_lag) < 0
                or p.speed <= 0 or p.length < 0 or p.gravity < 0 or p.last_sweep < 0
                or mount.yaw_rate < 0 or mount.pitch_rate < 0
                or not math.isfinite(policy.point_tolerance) or policy.point_tolerance <= 0
                or min(policy.max_age, policy.max_launch_delay, policy.delay_rounds,
                       policy.reserve_nodes, policy.reserve_lead_ticks) < 0
                or not -90 <= p.min_pitch <= p.max_pitch <= 90):
            return Result(Status.INVALID_INPUT, 'invalid snapshot/profile/actuator contract')
        if p.quadratic_drag or not 0 <= p.drag < 1:
            return Result(Status.UNSUPPORTED_MODEL, 'unsupported drag model')
        age = now-observation.sample_tick
        if not observation.observing or age > policy.max_age:
            return Result(Status.STALE_INPUT, 'no fresh observable snapshot')
        if not mount.controllable or not mount.can_hold:
            return Result(Status.CONTROL_UNAVAILABLE, 'cannot control and hold the launch pose')
        identity = (observation.target_id, p, mount.geometry_revision)
        if state.identity != identity:
            state.identity, state.caches, state.reserve_jobs, state.pending_launch, previous_plan = identity, {}, {}, {}, None
        if state.selected_arc != arc:
            previous_plan = None
        state.selected_arc = arc
        if p.gravity == 0 and arc == 'HIGH':
            return Result(Status.NO_SELECTED_ARC, 'zero gravity has one straight ballistic family')
        if self._kernel is None or self._kernel.p != p:
            self._kernel = Kernel(p)
        kernel = self._kernel
        lead = Lead(kernel)
        range_checked, range_failure = False, None

        def before_slow_search():
            nonlocal range_checked, range_failure
            if not range_checked:
                range_checked = True
                if window_excluded(p, snapshot, age, phase, policy.max_launch_delay,
                                   policy.point_tolerance, kernel.last_interval()):
                    range_failure = (Result(Status.NO_POINT_IN_WINDOW, 'horizontal bound excludes the entire launch/flight window')
                                     if kernel.regime_guaranteed() else
                                     Result(Status.UNSUPPORTED_MODEL, 'range exclusion needs an unproved drag-normalization regime'))
            return range_failure is None
        wait = max(mount.ready_after, 0 if mount.settled else mount.pose_lag)+mount.trigger_delay
        if previous_plan is not None and previous_plan.launch_tick >= now:
            wait = max(wait, previous_plan.launch_tick-now)
        pending = state.pending_launch.get(arc)
        if pending is not None and pending >= now:
            wait = max(wait, pending-now)
        elif pending is not None:
            state.pending_launch.pop(arc, None)
            state.reserve_jobs.pop(arc, None)
        trace = []
        cache = state.caches.get(arc)
        tried_worst = False
        for attempt in range(policy.delay_rounds+1):
            charge('delay_round')
            if wait > policy.max_launch_delay:
                return Result(Status.DELAY_LIMIT, 'planned launch exceeds allowed delay')
            tau = age+wait+phase
            if tau < 0:
                return Result(Status.INVALID_INPUT, 'negative target prediction offset')
            curve0 = replace(snapshot, offset=tau).curve()
            curve = Forecast(curve0.p, curve0.v, curve0.a)
            candidate, path = lead.solve(curve, arc, cache, before_slow=before_slow_search)
            if range_failure is not None:
                return range_failure
            if candidate is None:
                answer = ReserveAnswer(None, Status.UNRESOLVED, 'FAST_SEARCH_FAILED')
                if policy.allow_reserve:
                    if (wait < policy.reserve_lead_ticks <= policy.max_launch_delay
                            and attempt < policy.delay_rounds):
                        wait = policy.reserve_lead_ticks
                        continue
                    # Prefer witnesses reachable by the actuator at this fixed
                    # launch tick. Otherwise the first ballistic witness could
                    # postpone launch and hide a later-n witness already aligned.
                    deferred = []
                    def deadline_feasible(other):
                        command_pose = self.pose_adapter.command(candidate_pose(other))
                        needed = mount.earliest_launch(command_pose)
                        if needed is not None and needed <= wait:
                            return True
                        if needed is not None and (not deferred or needed < deferred[0][0]):
                            deferred[:] = [(needed, other)]
                            active_job = state.reserve_jobs.get(arc)
                            if active_job is not None:
                                active_job.delayed_witness = deferred[0]
                        return False
                    key = (p, curve.p, curve.v, curve.a, arc, now+wait, policy.point_tolerance,
                           mount, self.pose_adapter, 'DEADLINE')
                    state.pending_launch[arc] = now+wait
                    old_job = state.reserve_jobs.get(arc)
                    if old_job is not None and old_job.key == key and old_job.delayed_witness is not None:
                        deferred[:] = [old_job.delayed_witness]
                    answer = reserve_search(kernel, curve, arc, policy.point_tolerance, policy.reserve_nodes,
                                            state.reserve_jobs, key, cache.n if cache else 0., deadline_feasible)
                    if answer.candidate is None and deferred and answer.status != Status.SEARCH_PENDING:
                        answer = ReserveAnswer(deferred[0][1], Status.AIMING, 'RESERVE_DELAY_SEED')
                candidate = answer.candidate
                if candidate is not None:
                    path = 'RESERVE'
                    state.pending_launch.pop(arc, None)
                else:
                    if answer.status == Status.SEARCH_PENDING:
                        return Result(answer.status, answer.reason)
                    worst = mount.worst_launch_delay(p)
                    if (not tried_worst and worst is not None and wait < worst <= policy.max_launch_delay
                            and attempt < policy.delay_rounds):
                        wait, tried_worst = worst, True
                        continue
                    # A proof at one launch tick is not a proof for all delays.
                    state.pending_launch.pop(arc, None)
                    return Result(Status.UNRESOLVED, answer.reason+'; no result at tested launch times')
            cache = candidate
            state.caches[arc] = candidate  # a hint only; never a firing permission
            pose = self.pose_adapter.command(candidate_pose(candidate))
            required = mount.earliest_launch(pose)
            trace.append((wait, required))
            if required is None:
                if policy.allow_reserve:
                    key = (p, curve.p, curve.v, curve.a, arc, now+wait, policy.point_tolerance,
                           mount, self.pose_adapter, 'ACTUATOR')
                    def feasible(other):
                        other_pose = self.pose_adapter.command(candidate_pose(other))
                        needed = mount.earliest_launch(other_pose)
                        return needed is not None and needed <= wait
                    answer = reserve_search(kernel, curve, arc, policy.point_tolerance, policy.reserve_nodes,
                                            state.reserve_jobs, key, candidate.n, feasible)
                    if answer.candidate is not None:
                        candidate = answer.candidate
                        pose = self.pose_adapter.command(candidate_pose(candidate))
                        required, path = mount.earliest_launch(pose), 'RESERVE_ACTUATOR'
                    else:
                        return Result(answer.status if answer.status == Status.SEARCH_PENDING else Status.UNRESOLVED,
                                      'no actuator-feasible candidate: '+answer.reason)
                else:
                    return Result(Status.UNRESOLVED, 'no actuator path to the candidate pose')
            if required > wait:
                if attempt >= policy.delay_rounds:
                    return Result(Status.UNRESOLVED, 'launch-delay correction budget exhausted')
                if attempt == policy.delay_rounds-1:
                    worst = mount.worst_launch_delay(p)
                    if worst is None:
                        return Result(Status.UNRESOLVED, 'no bounded actuator completion time')
                    wait = max(required, worst)
                else:
                    wait = required
                continue
            point = curve.position(candidate.n)
            command_now = wait == mount.trigger_delay
            actual_pose = mount.visible_pose if command_now else pose
            valid, residual, reason = verify(p, candidate, point, actual_pose,
                                             self.pose_adapter, policy.point_tolerance, kernel)
            if not valid:
                return Result(Status.VERIFY_REJECTED, reason)
            plan = Plan(candidate, pose, now+wait, now+wait-mount.trigger_delay, point,
                        tau+candidate.n, observation.target_id, observation.revision,
                        observation.sample_tick, mount.revision, arc, residual, tuple(trace), path,
                        self.request_key(observation, now, mount, arc, phase))
            state.plan = plan
            return Result(Status.READY if command_now else Status.AIMING,
                          'verified trigger now; hold pose until spawn' if command_now
                          else 'verified future plan; recheck before triggering', plan)
        return Result(Status.UNRESOLVED, 'no delay fixed point in budget')
