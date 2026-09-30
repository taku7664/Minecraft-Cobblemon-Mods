"""Fits LocalOpponentIntentPredictor.switchChance on the FEAT lines LocalSwitchCalibrationTest prints.

Usage: python fit_switch_model.py "<log glob>" [comma-separated features]
Logistic regression by Newton's method with a small L2 penalty. Prints the weights, the Brier score against the base
rate alone, a 5-fold cross-validated Brier (an over-fitted model shows there) and a reliability table. The default
features are the ones the shipped model uses; "a*b" is a product term."""
import glob, math, sys, random
NAMES = ['threatened', 'stay', 'bestGain', 'preserve', 'sweep', 'stop', 'bestAttack', 'hp', 'bench', 'freeGain']
rows = []
for path in glob.glob(sys.argv[1]):
    for line in open(path, encoding='utf-8', errors='replace'):
        if 'FEAT,' not in line:
            continue
        parts = line.strip().split('FEAT,')[-1].split(',')
        rows.append((int(parts[0]), int(parts[1]), [float(x) for x in parts[2:]]))
use = sys.argv[2].split(',') if len(sys.argv) > 2 else ['threatened', 'bestGain', 'bestAttack', 'hp', 'preserve', 'bench']
def feats(x):
    d = dict(zip(NAMES, x))
    out = [1.0]
    for u in use:
        if '*' in u:
            a, b = u.split('*'); out.append(d[a] * d[b])
        elif u.startswith('pos:'):
            out.append(max(0.0, d[u[4:]]))
        else:
            out.append(d[u])
    return out
def sigmoid(z):
    return 1 / (1 + math.exp(-max(-30, min(30, z))))
def fit(data, lam=0.01):
    k = len(data[0][0]); w = [0.0] * k
    for _ in range(40):
        g = [0.0] * k; H = [[0.0] * k for _ in range(k)]
        for x, y in data:
            p = sigmoid(sum(a * b for a, b in zip(w, x)))
            for i in range(k):
                g[i] += (p - y) * x[i]
                for j in range(k):
                    H[i][j] += p * (1 - p) * x[i] * x[j]
        for i in range(1, k):
            g[i] += lam * len(data) * w[i] * 0.01; H[i][i] += lam * len(data) * 0.01
        # Solve H d = g
        n = k; A = [H[i][:] + [g[i]] for i in range(n)]
        for c in range(n):
            piv = max(range(c, n), key=lambda r: abs(A[r][c])); A[c], A[piv] = A[piv], A[c]
            if abs(A[c][c]) < 1e-12: continue
            for r in range(n):
                if r != c:
                    f = A[r][c] / A[c][c]
                    for cc in range(c, n + 1): A[r][cc] -= f * A[c][cc]
        d = [A[i][n] / A[i][i] if abs(A[i][i]) > 1e-12 else 0.0 for i in range(n)]
        w = [a - b for a, b in zip(w, d)]
        if max(abs(x) for x in d) < 1e-7: break
    return w
data = [(feats(x), y) for y, _, x in rows]
print('rows', len(data), 'switch rate %.3f' % (sum(y for _, y in data) / len(data)))
w = fit(data)
print('weights', ' '.join('%s=%+.3f' % (n, v) for n, v in zip(['bias'] + use, w)))
def brier(ws, part):
    return sum((sigmoid(sum(a * b for a, b in zip(ws, x))) - y) ** 2 for x, y in part) / len(part)
base = sum(y for _, y in data) / len(data)
print('brier in-sample %.4f constant %.4f' % (brier(w, data), base * (1 - base)))
random.seed(1); idx = list(range(len(data))); random.shuffle(idx)
cv = []
for f in range(5):
    test = [data[i] for i in idx[f::5]]; train = [data[i] for j, i in enumerate(idx) if j % 5 != f]
    cv.append(brier(fit(train), test))
print('brier 5-fold %.4f' % (sum(cv) / 5))
bins = {}
for x, y in data:
    p = sigmoid(sum(a * b for a, b in zip(w, x)))
    bins.setdefault(min(9, int(p * 10)), []).append((p, y))
for b in sorted(bins):
    m = bins[b]
    print('   bin %.1f n=%4d predicted=%.3f actual=%.3f' % (b / 10, len(m), sum(p for p, _ in m) / len(m), sum(y for _, y in m) / len(m)))
sw = [r for r in rows if r[0] == 1]
print('incoming = the best free matchup in %d/%d switches' % (sum(r[1] for r in sw), len(sw)))
