#!/usr/bin/env python3
# Spektralab-X - https://github.com/Dear-Ded/Spektralab-X
# Copyright (C) 2026 Spektralab-X contributors
# Additional terms under GPL-3.0 section 7 apply; see NOTICE.
# Co-created with GPT (OpenAI), 2026-11-11.
"""Offline LUT metrics. SDR Lab metrics clip linear Display P3 to [0, 1]."""
import csv
import json
import math
import sys
from collections import defaultdict


def delta_e(a, b):
    l1, a1, b1 = a
    l2, a2, b2 = b
    c1, c2 = math.hypot(a1, b1), math.hypot(a2, b2)
    cb = (c1 + c2) / 2
    g = .5 * (1 - math.sqrt(cb**7 / (cb**7 + 25**7)))
    ap1, ap2 = (1 + g) * a1, (1 + g) * a2
    cp1, cp2 = math.hypot(ap1, b1), math.hypot(ap2, b2)
    h1, h2 = math.degrees(math.atan2(b1, ap1)) % 360, math.degrees(math.atan2(b2, ap2)) % 360
    dh = h2 - h1
    if cp1 * cp2 == 0:
        dh = 0
    elif dh > 180:
        dh -= 360
    elif dh < -180:
        dh += 360
    dl, dc = l2 - l1, cp2 - cp1
    d_h = 2 * math.sqrt(cp1 * cp2) * math.sin(math.radians(dh / 2))
    lb, cb = (l1 + l2) / 2, (cp1 + cp2) / 2
    if cp1 * cp2 == 0:
        hb = h1 + h2
    elif abs(h1 - h2) <= 180:
        hb = (h1 + h2) / 2
    else:
        hb = (h1 + h2 + (360 if h1 + h2 < 360 else -360)) / 2
    t = (1 - .17 * math.cos(math.radians(hb - 30)) + .24 * math.cos(math.radians(2 * hb))
         + .32 * math.cos(math.radians(3 * hb + 6)) - .20 * math.cos(math.radians(4 * hb - 63)))
    sl = 1 + .015 * (lb - 50)**2 / math.sqrt(20 + (lb - 50)**2)
    sc, sh = 1 + .045 * cb, 1 + .015 * cb * t
    rt = -2 * math.sqrt(cb**7 / (cb**7 + 25**7)) * math.sin(math.radians(60 * math.exp(-((hb - 275) / 25)**2)))
    return math.sqrt(max(0, (dl / sl)**2 + (dc / sc)**2 + (d_h / sh)**2 + rt * dc / sc * d_h / sh))


def lab(rgb):
    r, g, b = [max(0., min(1., v)) for v in rgb]
    xyz = (.4865709486482162*r + .2656676931690931*g + .1982172852343625*b,
           .2289745640697488*r + .6917385218365064*g + .079286914093745*b,
           .0451133818589026*g + 1.043944368900976*b)
    def f(t):
        return t**(1/3) if t > (6/29)**3 else t / (3*(6/29)**2) + 4/29
    x, y, z = [f(v / white) for v, white in zip(xyz, (.9504559270516716, 1., 1.0890577507598784))]
    return 116*y - 16, 500*(x-y), 200*(y-z)


def stats(values):
    v = sorted(values)
    def q(p):
        index = (len(v)-1)*p
        lo = int(index)
        return v[lo] + (v[min(lo+1, len(v)-1)] - v[lo])*(index-lo)
    return dict(mean=sum(v)/len(v), p50=q(.5), p95=q(.95), p99=q(.99), max=v[-1])


def main():
    # Published Sharma supplementary test pairs exercise hue wrapping and chroma.
    for a, b, expected in [((50,2.6772,-79.7751),(50,0,-82.7485),2.0425),
                           ((50,3.1571,-77.2803),(50,0,-82.7485),2.8615),
                           ((50,2.8361,-74.02),(50,0,-82.7485),3.4412),
                           ((50,0,0),(50,-1,2),2.3669)]:
        assert abs(delta_e(a,b)-expected) < .0001
    groups, fallbacks = defaultdict(list), defaultdict(int)
    with open(sys.argv[1], newline='') as stream:
        for row in csv.DictReader(stream):
            key = (row['set'], row['size'], row['method'])
            if row['fallback'] == '1':
                fallbacks[key] += 1
                continue
            ref = [float(row['ref_'+c]) for c in 'rgb']
            got = [float(row['got_'+c]) for c in 'rgb']
            inp = [float(row['in_'+c]) for c in 'rgb']
            errors = [abs(a-b) for a,b in zip(ref,got)]
            value = (errors, delta_e(lab(ref),lab(got)), ref, got, inp, row['group'])
            names = ['all', row['group']]
            if min(inp) < 0:
                names.append('has-negative')
            if max(inp) > 1:
                names.append('has-superwhite')
            for name in names:
                groups[key + (name,)].append(value)
    result = {'metric':'CIEDE2000 of clipped linear Display P3 converted to D65 Lab; not an HDR metric',
              'fallbacks_excluded':{'/'.join(k):v for k,v in fallbacks.items()}, 'groups':{}}
    for key, values in groups.items():
        errors = [e for v in values for e in v[0]]
        result['groups']['/'.join(key)] = dict(count=len(values),
            linear_abs=stats(errors), linear_rmse=math.sqrt(sum(x*x for x in errors)/len(errors)),
            delta_e00=stats([v[1] for v in values]),
            reference_outside_sdr_channels=sum(x<0 or x>1 for v in values for x in v[2]),
            approximate_outside_sdr_channels=sum(x<0 or x>1 for v in values for x in v[3]))
        if key[-1] == 'all':
            result['groups']['/'.join(key)]['worst_samples'] = [
                dict(delta_e00=v[1], input=v[4], reference=v[2], approximate=v[3], group=v[5])
                for v in sorted(values, key=lambda item:item[1], reverse=True)[:5]]
    with open(sys.argv[2], 'w') as stream:
        json.dump(result, stream, indent=2)
        stream.write('\n')
    for key, value in result['groups'].items():
        if key.endswith('/all'):
            print(key, json.dumps(value))


if __name__ == '__main__':
    main()
