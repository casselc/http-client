#!/usr/bin/env bash

# Fail closed unless Jolt resolves exactly one canonical jolt-crypto checkout at
# the reviewed full revision. This tests the selected graph, not just deps.edn's
# spelling: a transitive fork identity would appear as a second classpath entry.

set -euo pipefail

if [[ $# -eq 0 ]]; then
  jolt_cmd=(jolt)
else
  jolt_cmd=("$@")
fi
crypto_sha=5effcc89a3258499a79a2a3d69edad9e7800d1bf
canonical_repo_path=/jolt-lang/jolt-crypto
canonical_cache_path=/https___github.com_jolt-lang_jolt-crypto

matches_selected_crypto() {
  local path=$1
  [[ $path == *${canonical_repo_path}/${crypto_sha}/src ||
     $path == *${canonical_cache_path}/${crypto_sha}/src ]]
}

classpath=$("${jolt_cmd[@]}" -Srepro -Spath)
mapfile -t crypto_entries < <(
  tr ':' '\n' <<<"$classpath" | awk '/jolt-crypto/ { print }'
)

if [[ ${#crypto_entries[@]} -ne 1 ]]; then
  printf 'expected one resolved jolt-crypto entry, found %d\n' \
    "${#crypto_entries[@]}" >&2
  printf '  %s\n' "${crypto_entries[@]}" >&2
  exit 1
fi

if ! matches_selected_crypto "${crypto_entries[0]}"; then
  printf 'resolved jolt-crypto is not the canonical reviewed checkout:\n  %s\n' \
    "${crypto_entries[0]}" >&2
  exit 1
fi

# Mutation controls: either the old fork identity or a different full revision
# must be rejected by the same predicate used on the live selected classpath.
if matches_selected_crypto \
  "/tmp/casselc/jolt-crypto/${crypto_sha}/src"; then
  echo 'wrong-repository control unexpectedly matched' >&2
  exit 1
fi
if ! matches_selected_crypto \
  "/tmp${canonical_cache_path}/${crypto_sha}/src"; then
  echo 'hosted canonical-cache control unexpectedly failed' >&2
  exit 1
fi
if matches_selected_crypto \
  "/tmp${canonical_repo_path}/0000000000000000000000000000000000000000/src"; then
  echo 'wrong-revision control unexpectedly matched' >&2
  exit 1
fi

echo 'wrong-repository and wrong-revision controls rejected'
printf 'canonical jolt-crypto resolution passed: %s\n' "${crypto_entries[0]}"
