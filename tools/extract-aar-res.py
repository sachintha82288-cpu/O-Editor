#!/usr/bin/env python3
"""Extract res/ from every AAR in a Gradle cache so aapt2 can link library resources.

Usage: extract-aar-res.py <gradle-cache> <output-dir>
"""
import glob, os, re, sys, zipfile

cache, out = sys.argv[1], sys.argv[2]


def vkey(v):
    return [int(x) if x.isdigit() else 0 for x in re.split(r'[.\-]', v)]


best = {}
for p in glob.glob(cache + '/modules-2/files-2.1/*/*/*/*/*.aar'):
    parts = p[len(cache) + len('/modules-2/files-2.1/'):].split('/')
    group, name, version = parts[0], parts[1], parts[2]
    key = (group, name)
    if key not in best or vkey(version) > vkey(best[key][0]):
        best[key] = (version, p)

count = 0
for (group, name), (version, path) in best.items():
    with zipfile.ZipFile(path) as z:
        entries = [n for n in z.namelist() if n.startswith('res/') and not n.endswith('/')]
        if not entries:
            continue
        target = os.path.join(out, name)
        for entry in entries:
            dest = os.path.join(target, entry)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with open(dest, 'wb') as f:
                f.write(z.read(entry))
        count += 1
print(f"extracted resources from {count} AARs into {out}")
