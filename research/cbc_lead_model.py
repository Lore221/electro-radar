"""Reproducible mathematical check of point aiming + lead, not mod integration.

Run: python3 -B research/cbc_lead_model.py

Runtime prototype: cached CBC sweep, then a bounded time corrector using the
same ballistic coefficients. No tick simulation in that path. Exhaustive search
and direct tick recurrences at the bottom are OFFLINE TEST ORACLES ONLY.
The bounded search returns unresolved, never a proof that interception is
impossible. It does not implement the full analytic-event fallback, float/LUT
mount transforms, spread, collision-world queries, or certified interval signs.
"""

from dataclasses import dataclass, replace
import math


def add(a, b):
    return tuple(x+y for x, y in zip(a, b))


def scale(a, s):
    return tuple(x*s for x in a)


def dot(a, b):
    return sum(x*y for x, y in zip(a, b))


def distance(a, b):
    return math.sqrt(sum((x-y)**2 for x, y in zip(a, b)))


ZERO = (0., 0., 0.)


@dataclass(frozen=True)
class Profile:
    speed: float = 8.
    length: float = 2.
    drag: float = .01
    gravity: float = .05
    last_sweep: int = 599
    min_pitch: float = -45.
    max_pitch: float = 85.
    quadratic_drag: bool = False


@dataclass(frozen=True)
class Coefficients:
    t: float
    A: float
    B: float
    Ap: float
    Bp: float
    z: float


@dataclass(frozen=True)
class Curve:
    """Target position relative to cannon centre, already shifted to launch."""
    p: tuple
    v: tuple
    a: tuple

    def position(self, t):
        return tuple(p+t*(v+.5*t*a) for p, v, a in zip(self.p, self.v, self.a))

    def derivative(self, t):
        return add(self.v, scale(self.a, t))


@dataclass(frozen=True)
class Snapshot:
    p: tuple
    v: tuple
    a: tuple = ZERO
    eta: int = 0
    offset: int = 0  # sample age + planned launch delay + entity tick phase

    def curve(self):
        v_poly = add(self.v, scale(self.a, .5*self.eta))
        p_launch = add(self.p, add(scale(v_poly, self.offset),
                                  scale(self.a, .5*self.offset*self.offset)))
        return Curve(p_launch, add(v_poly, scale(self.a, self.offset)), self.a)

    def advance(self, acceleration=None):
        a = self.a if acceleration is None else acceleration
        return replace(self, p=add(self.p, add(self.v, scale(a, .5*(self.eta+1)))),
                       v=add(self.v, a), a=a)


@dataclass(frozen=True)
class Candidate:
    n: int
    f: float
    direction: tuple
    pitch: float
    arc: str
    coeff: Coefficients
    profile: Profile


class PointKernel:
    """Ballistic primitives receive coordinates, never a target motion model."""

    def __init__(self, profile):
        self.p = profile
        self.q = 1-profile.drag
        assert 0 <= profile.drag < 1
        self.lam = math.log1p(-profile.drag) if profile.drag else 0.
        self.c = 1-profile.drag/2
        self.exp_calls = self.quad_calls = 0

    def coefficients(self, t):
        p = self.p
        if p.drag == 0:
            return Coefficients(t, t, .5*t*t, 1., t, 1.)
        # Share exp/expm1 between the position and velocity coefficients.
        self.exp_calls += 1
        argument = self.lam*t
        if argument > -.1:
            em = math.expm1(argument)
            z = 1+em
            A = -self.c*em/p.drag
        else:
            z = math.exp(argument)
            A = self.c*(1-z)/p.drag
        Ap = -self.c*self.lam*z/p.drag
        if p.drag*max(1., abs(t)) < .001:
            # Stable finite binomial series for B and B': avoid dividing the
            # cancellation t-A by a tiny drag coefficient. Six terms suffice
            # for this research domain; the pipeline still verifies the shot.
            choose, derivative = [1., t], [0., 1.]
            for j in range(2, 8):
                factor = (t-j+1)/j
                choose.append(choose[-1]*factor)
                derivative.append(derivative[-1]*factor+choose[-2]/j)
            B = sum((-p.drag)**j*(choose[j+2]+.5*choose[j+1]) for j in range(6))
            Bp = sum((-p.drag)**j*(derivative[j+2]+.5*derivative[j+1]) for j in range(6))
            return Coefficients(t, t-p.drag*B, B, 1-p.drag*Bp, Bp, z)
        return Coefficients(t, A, (t-A)/p.drag, Ap, (1-Ap)/p.drag, z)

    def neighbour(self, value, step):
        assert step in (-1, 1)
        p = self.p
        if p.drag == 0:
            return self.coefficients(value.t+step)
        S = value.A/self.c
        if step == 1:
            z = value.z*self.q
            A = value.A+self.c*value.z
            B = value.B+self.c*S+.5
        else:
            z = value.z/self.q
            S_previous = S-z
            A = value.A-self.c*z
            B = value.B-self.c*S_previous-.5
        Ap = value.Ap*self.q if step == 1 else value.Ap/self.q
        Bp = value.Bp*self.q+1 if step == 1 else (value.Bp-1)/self.q
        return Coefficients(value.t+step, A, B, Ap, Bp, z)

    def candidates(self, point, n, coeff=None):
        self.quad_calls += 1
        p = self.p
        if n < 0 or n > p.last_sweep:
            return []
        c = self.coefficients(n) if coeff is None else coeff
        assert c.t == n
        R = p.length+p.speed*c.A
        U = p.speed*self.q*c.z
        V = c.A/self.c+c.z if p.drag else n+1
        w = p.gravity*V
        y = point[1]+p.gravity*c.B
        r2 = point[0]**2+point[2]**2
        if r2 > (R+U)**2*(1+1e-12):
            return []  # necessary horizontal reach; rejection is not a global proof
        aa = w*w-U*U
        bb = 2*(w*y-R*U)
        cc = r2+y*y-R*R
        # Research sign handling, not certified near a multiple root.
        if abs(aa) < 1e-14:
            roots = [-cc/bb] if abs(bb) > 1e-14 else []
        else:
            disc = bb*bb-4*aa*cc
            if disc < 0:
                return []
            Q = -.5*(bb+math.copysign(math.sqrt(disc), bb))
            roots = [Q/aa, cc/Q] if Q else [-bb/(2*aa)]
        out = []
        for f in roots:
            if not -1e-10 <= f <= 1+1e-10:
                continue
            f = min(1., max(0., f))
            rho = R+f*U
            if rho <= 0 or r2 <= 0:
                continue
            u = (point[0]/rho, (y+f*w)/rho, point[2]/rho)
            if abs(dot(u, u)-1) > 1e-8:
                continue
            pitch = math.degrees(math.atan2(u[1], math.hypot(u[0], u[2])))
            if not p.min_pitch-1e-8 <= pitch <= p.max_pitch+1e-8:
                continue
            if p.drag:
                infinity = p.length+p.speed*self.c/p.drag
                gap = infinity-rho
                if gap <= 1e-10*max(1., infinity):
                    continue  # unresolved arc classification in this prototype
                W = p.gravity/p.drag*(1/(-self.lam*gap)-1/p.speed)
            else:
                W = p.gravity*(rho-p.length)/(p.speed*p.speed)
            derivative_sign = 1-W*u[1]  # H'(tan(pitch))/r at this fixed point
            if abs(derivative_sign) < 1e-8:
                continue  # tangent/shared-family handling belongs to full solver
            arc = 'LOW' if derivative_sign > 0 else 'HIGH'
            out.append(Candidate(n, f, u, pitch, arc, c, p))
        return out

    def static_seed(self, point, arc):
        """Only a seed: concave fixed-point height function from MD section 13.

        Bisection here favours reproducibility over cold-path performance. No
        root or an unsupported domain means NO_SEED, not NO_INTERCEPTION.
        """
        p = self.p
        r = math.hypot(point[0], point[2])
        if r <= p.length+p.speed or p.gravity <= 0:
            return None
        c_last = self.coefficients(p.last_sweep)
        maximum_radius = p.length+p.speed*c_last.A+p.speed*self.q*c_last.z
        if r >= maximum_radius:
            return None
        max_m = math.sqrt((maximum_radius/r)**2-1)
        lo = max(-max_m, math.tan(math.radians(p.min_pitch)))
        hi = min(max_m, math.tan(math.radians(p.max_pitch)))
        if lo >= hi:
            return None

        def height(m):
            rho = r*math.sqrt(1+m*m)
            A = (rho-p.length)/p.speed
            if p.drag:
                alpha = self.c/p.drag
                fraction = A/alpha
                if not 0 <= fraction < 1:
                    return -math.inf, -math.inf, math.nan
                t = math.log1p(-fraction)/self.lam
                B = (t-A)/p.drag
                W = p.gravity/p.drag*(1/(-self.lam*(p.length+p.speed*alpha-rho))-1/p.speed)
            else:
                t = A
                B = .5*t*t
                W = p.gravity*(rho-p.length)/(p.speed*p.speed)
            Hp = r-W*r*m/math.sqrt(1+m*m)
            return r*m-point[1]-p.gravity*B, Hp, t

        left, right = lo, hi
        if height(lo)[1] <= 0:
            peak = lo
        elif height(hi)[1] >= 0:
            peak = hi
        else:
            for _ in range(48):
                middle = (left+right)/2
                if height(middle)[1] > 0:
                    left = middle
                else:
                    right = middle
            peak = (left+right)/2
        left, right = (lo, peak) if arc == 'LOW' else (peak, hi)
        h_left, h_right = height(left)[0], height(right)[0]
        if h_left*h_right > 0 or not math.isfinite(h_left+h_right):
            return None
        for _ in range(48):
            middle = (left+right)/2
            h, _, t = height(middle)
            if abs(h) < 1e-9:
                return t
            if h*h_left > 0:
                left, h_left = middle, h
            else:
                right = middle
        return height((left+right)/2)[2]


class LeadCoordinator:
    def __init__(self, kernel):
        self.kernel = kernel
        self.function_calls = 0

    def evaluate(self, curve, t, coeff=None):
        self.function_calls += 1
        k = self.kernel
        p = k.p
        c = k.coefficients(t) if coeff is None else coeff
        X = curve.position(t)
        dX = curve.derivative(t)
        D = (X[0], X[1]+p.gravity*c.B, X[2])
        Dp = (dX[0], dX[1]+p.gravity*c.Bp, dX[2])
        R = p.length+p.speed*c.A
        Rp = p.speed*c.Ap
        norm = math.sqrt(dot(D, D))
        if norm <= 1e-14:
            return -R, math.nan
        # Unsquared radial error has units of blocks and avoids a misleading
        # squared-residual Newton direction for a fast receding target.
        return norm-R, dot(D, Dp)/norm-Rp

    def correct_time(self, curve, seed, initial_coeff=None):
        t = min(self.kernel.p.last_sweep+1., max(0., seed))
        error, derivative = self.evaluate(curve, t, initial_coeff if t == seed else None)
        evaluations = 1
        while evaluations <= 12:
            if abs(error) <= 1e-8:
                return t
            if not math.isfinite(derivative) or abs(derivative) < 1e-10:
                return None
            step = error/derivative
            if not math.isfinite(step):
                return None
            accepted = False
            for factor in (1., .5, .25, .125):
                new_t = t-factor*step
                if not 0 <= new_t <= self.kernel.p.last_sweep+1:
                    continue
                if evaluations >= 12:
                    return None
                new_error, new_derivative = self.evaluate(curve, new_t)
                evaluations += 1
                if abs(new_error) < abs(error) or abs(new_error) <= 1e-8:
                    t, error, derivative = new_t, new_error, new_derivative
                    accepted = True
                    break
            if not accepted:
                return None
        return None  # bounded unresolved, no claim of nonexistence

    def solve(self, curve, arc, cache=None, before_slow=None):
        k = self.kernel

        def choose(n, coeff=None):
            # Crucial: predict separately at THIS integer collision tick.
            choices = [c for c in k.candidates(curve.position(n), n, coeff) if c.arc == arc]
            if not choices:
                return None
            return min(choices, key=lambda c: abs(c.pitch-cache.pitch)) if cache else choices[0]

        cache_valid = cache is not None and cache.profile == k.p and cache.arc == arc
        if cache_valid:
            for step in (0, -1, 1):
                n = cache.n+step
                if not 0 <= n <= k.p.last_sweep:
                    continue
                c = cache.coeff if step == 0 else k.neighbour(cache.coeff, step)
                candidate = choose(n, c)
                if candidate:
                    return candidate, 'SAME_SWEEP' if step == 0 else 'NEIGHBOUR'
        if before_slow is not None and not before_slow():
            return None, 'RANGE_EXCLUDED'
        if cache_valid:
            seed, initial_coeff = float(cache.n), cache.coeff
        else:
            seed = k.static_seed(curve.position(0), arc)
            initial_coeff = None
            if seed is None:
                # Generic seeds only; a failed static aim must not reject lead.
                seed = math.hypot(curve.p[0], curve.p[2])/k.p.speed
                if arc == 'HIGH':
                    seed = min(k.p.last_sweep*.8, 2*k.p.speed/max(k.p.gravity, 1e-6))
        t = self.correct_time(curve, seed, initial_coeff)
        if t is None:
            return None, 'UNRESOLVED'
        n = math.floor(t)
        for j in (n, n+1, n-1, n+2):
            candidate = choose(j)
            if candidate:
                return candidate, 'TIME_CORRECTOR'
        return None, 'UNRESOLVED'


# Everything below this line is offline verification, not the aiming runtime.
def target_oracle(snapshot, n):
    p, v = snapshot.p, snapshot.v
    for _ in range(snapshot.offset+n):
        p = add(p, add(v, scale(snapshot.a, .5*(snapshot.eta+1))))
        v = add(v, snapshot.a)
    return p


def cbc_oracle(profile, candidate):
    pos = scale(candidate.direction, profile.length)
    velocity = scale(candidate.direction, profile.speed)
    for n in range(candidate.n+1):
        speed = math.sqrt(dot(velocity, velocity))
        assert speed >= 1e-4
        drag = min(profile.drag*speed, speed)
        force = add(scale(velocity, -drag/speed), (0., -profile.gravity, 0.))
        sweep = add(velocity, force)
        if n == candidate.n:
            assert dot(sweep, sweep) >= .01**2
            return add(pos, scale(sweep, candidate.f))
        pos = add(pos, add(velocity, scale(force, .5)))
        velocity = sweep


def exhaustive_oracle(profile, curve, arc):
    kernel = PointKernel(profile)
    return [c for n in range(profile.last_sweep+1)
            for c in kernel.candidates(curve.position(n), n) if c.arc == arc]


def run():
    cases = [
        ('static', Snapshot((400., 0., 0.), ZERO), Profile()),
        ('velocity', Snapshot((360., 0., 20.), (.25, 0., .1)), Profile()),
        ('acceleration', Snapshot((320., 10., 20.), (.1, .02, -.03), (.0006, .0001, .0002)), Profile()),
        ('old_velocity_position', Snapshot((320., 0., 20.), (.15, .01, .05), (.0004, .0002, -.0001), -1, 7), Profile()),
        ('new_velocity_position', Snapshot((320., 0., 20.), (.15, .01, .05), (.0004, .0002, -.0001), 1, 7), Profile()),
        ('vacuum', Snapshot((330., 0., 20.), (.12, 0., .05), (.0004, 0., -.0001), 0, 3), Profile(drag=0.)),
        ('changing_inputs', Snapshot((340., 0., 20.), (.15, 0., .05)), Profile()),
    ]
    totals = {'SAME_SWEEP': 0, 'NEIGHBOUR': 0, 'TIME_CORRECTOR': 0, 'UNRESOLVED': 0}
    max_error = max_target_error = largest_fractional_time_error = 0.
    misses_with_existing_solution = total_evaluations = 0
    largest_query_evaluations = 0
    for name, snapshot, profile in cases:
        kernel = PointKernel(profile)
        lead = LeadCoordinator(kernel)
        caches = {'LOW': None, 'HIGH': None}
        counts = dict.fromkeys(totals, 0)
        for tick in range(60):
            if name == 'changing_inputs':
                acceleration = (.0005*math.sin(tick*.23), 0., .0004*math.cos(tick*.17))
                if tick == 30:
                    # Abrupt reversal: the measured acceleration is the actual
                    # velocity difference. No promise about later true motion.
                    acceleration = scale(snapshot.v, -2.)
                snapshot = snapshot.advance(acceleration)
            elif tick:
                snapshot = snapshot.advance()
            curve = snapshot.curve()
            for arc in ('LOW', 'HIGH'):
                before = lead.function_calls
                before_exp = kernel.exp_calls
                candidate, path = lead.solve(curve, arc, caches[arc])
                if path in ('SAME_SWEEP', 'NEIGHBOUR'):
                    assert kernel.exp_calls == before_exp
                largest_query_evaluations = max(largest_query_evaluations, lead.function_calls-before)
                counts[path] += 1
                totals[path] += 1
                if candidate is None:
                    if exhaustive_oracle(profile, curve, arc):
                        misses_with_existing_solution += 1
                    continue
                expected = target_oracle(snapshot, candidate.n)
                max_target_error = max(max_target_error, distance(expected, curve.position(candidate.n)))
                error = distance(cbc_oracle(profile, candidate), expected)
                max_error = max(max_error, error)
                assert error < 1e-7, (name, tick, arc, error)
                wrong_time = curve.position(candidate.n+candidate.f)
                largest_fractional_time_error = max(largest_fractional_time_error,
                                                    distance(wrong_time, expected))
                caches[arc] = candidate
        total_evaluations += lead.function_calls
        print(name, counts, 'time_function_evaluations', lead.function_calls,
              'coefficient_exp_evaluations', kernel.exp_calls, flush=True)
    print('TOTAL', totals, 'time_function_evaluations', total_evaluations,
          'max_time_evaluations_per_query', largest_query_evaluations,
          'unresolved_despite_existing_solution', misses_with_existing_solution, flush=True)
    print('MAX_ERRORS', {'CBC_vs_target_blocks': max_error,
                         'target_polynomial_vs_tick_oracle': max_target_error,
                         'incorrect_n_plus_f_target_displacement': largest_fractional_time_error}, flush=True)
    assert max_error < 1e-7 and max_target_error < 1e-8
    assert largest_fractional_time_error > .01

    # Regression: an unreachable current point is not a proof against intercept.
    profile = Profile()
    snapshot = Snapshot((950., 0., 0.), (-4., 0., 0.))
    kernel = PointKernel(profile)
    assert kernel.static_seed(snapshot.curve().position(0), 'LOW') is None
    references = exhaustive_oracle(profile, snapshot.curve(), 'LOW')
    assert references
    candidate, path = LeadCoordinator(kernel).solve(snapshot.curve(), 'LOW')
    if candidate:
        assert distance(cbc_oracle(profile, candidate), target_oracle(snapshot, candidate.n)) < 1e-7
    print('ENTERS_RANGE', {'static_seed': None, 'reference_interceptions': len(references),
                          'fast_path_status': path}, flush=True)

    profile = Profile(drag=0., gravity=0.)
    snapshot = Snapshot((200., 0., 0.), (7.2, 0., 0.))
    lead = LeadCoordinator(PointKernel(profile))
    candidate, path = lead.solve(snapshot.curve(), 'LOW')
    assert candidate is not None
    assert distance(cbc_oracle(profile, candidate), target_oracle(snapshot, candidate.n)) < 1e-7
    print('FAST_RECEDING', {'path': path, 'time_function_evaluations': lead.function_calls,
                            'validated_sweep': candidate.n}, flush=True)


if __name__ == '__main__':
    run()
