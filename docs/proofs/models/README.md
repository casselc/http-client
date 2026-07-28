# Schannel invariant models

These bounded models accompany `../schannel-invariants.md`. Each proof family
has three files:

- `*-corrected.smt2`: asks for a counterexample and must be `unsat`;
- `*-buggy.smt2`: changes one load-bearing assertion and must be `sat`;
- `*-nonvacuity.smt2`: requires a useful corrected trace and must be `sat`.

Run all declared verdicts with:

```sh
tools/verify-models.sh
```

The models were linted and checked independently through Chiasmus on
2026-07-28. Its Z3 backend reported all three corrected queries `unsat`, all
three one-assertion mutations `sat`, and all three non-vacuity controls `sat`.
Standalone Z3 is the reproducible repository gate.

These are bounded transition models, not proofs of the Windows implementation,
the SSPI ABI, native allocation, scheduler fairness, or network delivery. Those
premises are covered by the checked-in ABI descriptors and native Windows
runtime gates.
