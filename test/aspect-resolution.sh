#!/usr/bin/env bash
set -euo pipefail

jolt=${1:-${JOLT_ASPECT_JOLT:-}}
if [[ -z "$jolt" || "$jolt" != /* || ! -x "$jolt" ]]; then
  echo "usage: test/aspect-resolution.sh /absolute/path/to/aspect-capable-jolt" >&2
  exit 2
fi

repo=$(cd "$(dirname "$0")/.." && pwd)
fixture="$repo/test/aspect-build"
scratch=$(mktemp -d "${TMPDIR:-/tmp}/http-client-aspects.XXXXXX")
trap 'rm -rf "$scratch"' EXIT

prepare_case() {
  local name=$1
  local target="$scratch/$name"
  mkdir -p "$target"
  cp -R "$fixture/src" "$target/src"
  mkdir -p "$target/resources"
  sed "s|@HTTP_CLIENT_ROOT@|$repo|g" "$fixture/deps.edn.template" \
    >"$target/deps.edn"
}

build_case() {
  local name=$1
  local target="$scratch/$name"
  env JOLT_PWD="$target" "$jolt" build \
    -m jolt.http.aspect-fixture -o "$target/target/aspect-fixture"
}

prepare_case green
build_case green
report="$scratch/green/target/aspects.edn"
test -s "$report"
test "$(grep -o ':aspect :http-client.core/request' "$report" | wc -l | tr -d ' ')" = 1
grep -q ':entry clj-http.lite.core/request' "$report"
grep -q ':ordinal 1' "$report"

manifest="$repo/resources/META-INF/jolt/aspects/http-client-core.edn"

prepare_case zero
mkdir -p "$scratch/zero/resources/META-INF/jolt/aspects"
sed 's#clj-http.lite.core/request#clj-http.lite.core/missing#' "$manifest" \
  >"$scratch/zero/resources/META-INF/jolt/aspects/http-client-core.edn"
if build_case zero >"$scratch/zero.out" 2>&1; then
  echo "FAIL: zero-match aspect mutant unexpectedly built" >&2
  exit 1
fi
if ! grep -q 'aspect matched no join points' "$scratch/zero.out" ||
   ! grep -q ':expected 1' "$scratch/zero.out" ||
   ! grep -q ':actual 0' "$scratch/zero.out"; then
  echo "FAIL: zero-match mutant did not produce the exact cardinality error" >&2
  cat "$scratch/zero.out" >&2
  exit 1
fi

prepare_case duplicate
mkdir -p "$scratch/duplicate/resources/META-INF/jolt/aspects"
sed 's/{:entry clj-http.lite.core\/request/{:ns jolt.http.aspect-fixture :call clj-http.lite.core\/request/' \
  "$manifest" \
  >"$scratch/duplicate/resources/META-INF/jolt/aspects/http-client-core.edn"
if build_case duplicate >"$scratch/duplicate.out" 2>&1; then
  echo "FAIL: duplicate-call aspect mutant unexpectedly built" >&2
  exit 1
fi
if ! grep -q 'aspect match count was ambiguous' "$scratch/duplicate.out" ||
   ! grep -q ':expected 1' "$scratch/duplicate.out" ||
   ! grep -Eq ':actual [2-9][0-9]*' "$scratch/duplicate.out"; then
  echo "FAIL: duplicate-call mutant did not produce the exact cardinality error" >&2
  cat "$scratch/duplicate.out" >&2
  exit 1
fi

echo "PASS: compiler resolved one HTTP request entry and rejected zero/duplicate mutants"
