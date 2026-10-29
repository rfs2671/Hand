#!/bin/bash
# End-to-end test of the Android TV remote client against a fake TV. Usage: run.sh <scratch dir>
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; REPO="$HERE/../.."; SCRATCH="${1:?scratch dir}"
KCP="$(cat "$SCRATCH/kcp.txt")"; STD=/opt/gradle/lib/kotlin-stdlib-2.0.21.jar
SRC="$SCRATCH/atv_src/androidtvremote2-0.3.2/src/androidtvremote2"   # only read as data
WORK="$SCRATCH/atvwork"; rm -rf "$WORK" "$SCRATCH/atv_cls"; mkdir -p "$WORK" "$SCRATCH/atv_cls"
java -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -cp "$STD" -jvm-target 17 \
  -d "$SCRATCH/atv_cls" "$REPO/app/src/main/java/com/hand/bridge/atv" "$REPO/app/src/main/java/com/hand/bridge/Actions.kt" "$REPO/app/src/main/java/com/hand/bridge/GestureTests.kt" "$HERE/AtvTest.kt" 2>&1 | grep -v JAVA_TOOL
PP=46467; RP=46466
RUN="java -cp $SCRATCH/atv_cls:$STD AtvTestKt $WORK"
$RUN gen $PP $RP || exit 1
python3 -I "$HERE/mock_tv.py" "$WORK" $PP $RP x "$SRC" &
MOCK=$!; trap 'kill $MOCK 2>/dev/null' EXIT
for i in $(seq 1 100); do [ -f "$WORK/ready" ] && break; sleep 0.1; done
$RUN run $PP $RP; RC=$?
echo "--- mock log"; cat "$WORK/mock.log" | head -40
exit $RC
