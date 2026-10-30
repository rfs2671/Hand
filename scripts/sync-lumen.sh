#!/usr/bin/env bash
# Experimental native Meta Neural Band engine (MIT licensed). Source is pinned.
# The vendor's actual band protocol and Air Mouse are not reimplemented here.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SHA="4fe237668683cbe7e613e3c0121723a9f176102a"
DEST="$ROOT/third_party/lumen"
mkdir -p "$ROOT/third_party"
if [ ! -d "$DEST/.git" ]; then
  git clone -q https://github.com/beyondlevi/rokid-lumen.git "$DEST"
fi
git -C "$DEST" checkout --detach "$SHA"
# The original library targets API 37.2; this app deliberately stays on the
# publicly installed Android 35 SDK for reproducible CI builds.
python3 - "$DEST/band/build.gradle.kts" <<'PY'
import pathlib,sys,re
p=pathlib.Path(sys.argv[1])
s=p.read_text()
s=s.replace('id("com.android.library")', 'id("com.android.library")\\n    id("org.jetbrains.kotlin.android")')
s=re.sub(r'compileSdk \{\s*version = release\(37\) \{ minorApiLevel = 2 \}\s*\}', 'compileSdk = 35', s)
p.write_text(s)
PY
# Preview experiment: accept separate real gestures 100 ms apart instead of
# the stock 400 ms ActionGate. GestureRouter still deduplicates a single
# physical event's raw and derived reports; tune from hardware logs.
python3 - "$DEST/rust/bridge/src/controller.rs" <<'PY'
import pathlib,sys
p=pathlib.Path(sys.argv[1]); s=p.read_text()
a='gate: ActionGate::default(),'
assert s.count(a)==1, 'Upstream gesture gate changed; inspect before porting'
p.write_text(s.replace(a,'gate: ActionGate::new(0.10),'))
PY
echo "Pinned Lumen band engine prepared: $SHA"
