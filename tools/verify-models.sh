#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
model_dir="$root/docs/proofs/models"
checked=0

for model in "$model_dir"/*.smt2; do
  name="$(basename "$model")"
  case "$name" in
    *-corrected.smt2) expected=unsat ;;
    *-buggy.smt2|*-nonvacuity.smt2) expected=sat ;;
    *)
      echo "no declared verdict for $name" >&2
      exit 1
      ;;
  esac

  actual="$(z3 "$model" | sed -n '1p')"
  if [[ "$actual" != "$expected" ]]; then
    echo "$name: expected $expected, got $actual" >&2
    z3 "$model" >&2
    exit 1
  fi
  echo "$name: $actual"
  checked=$((checked + 1))
done

if [[ "$checked" -ne 9 ]]; then
  echo "expected 9 models, checked $checked" >&2
  exit 1
fi

echo "all $checked model verdicts match"
