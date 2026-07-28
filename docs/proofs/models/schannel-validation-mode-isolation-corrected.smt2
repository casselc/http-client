; Bounded claim: request-flags derives the manual-validation bit from each
; connection's own insecure? argument. Expected: unsat for a
; secure/insecure/secure isolation violation.

(set-option :produce-unsat-cores true)

(declare-const insecure_0 Bool)
(declare-const insecure_1 Bool)
(declare-const insecure_2 Bool)
(declare-const manual_0 Bool)
(declare-const manual_1 Bool)
(declare-const manual_2 Bool)
(declare-const violation Bool)

(assert (! (and (not insecure_0) insecure_1 (not insecure_2))
           :named secure_insecure_secure_trace))
(assert (! (= manual_0 insecure_0)
           :named call_0_derives_fresh_flags))
(assert (! (= manual_1 insecure_1)
           :named call_1_derives_fresh_flags))
(assert (! (= manual_2 insecure_2)
           :named call_2_derives_fresh_flags))
(assert (! (= violation
              (or manual_0 (not manual_1) manual_2))
           :named validation_mode_violation_definition))
(assert (! violation :named property_violated))

(check-sat)
(get-unsat-core)
