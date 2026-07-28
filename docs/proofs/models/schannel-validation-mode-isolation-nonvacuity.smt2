; Concrete corrected secure/insecure/secure connection sequence. Expected: sat
; with the manual-validation selections false/true/false.

(set-option :produce-unsat-cores true)

(declare-const insecure_0 Bool)
(declare-const insecure_1 Bool)
(declare-const insecure_2 Bool)
(declare-const manual_0 Bool)
(declare-const manual_1 Bool)
(declare-const manual_2 Bool)

(assert (! (and (not insecure_0) insecure_1 (not insecure_2))
           :named secure_insecure_secure_trace))
(assert (! (= manual_0 insecure_0)
           :named call_0_derives_fresh_flags))
(assert (! (= manual_1 insecure_1)
           :named call_1_derives_fresh_flags))
(assert (! (= manual_2 insecure_2)
           :named call_2_derives_fresh_flags))
(assert (! (and (not manual_0) manual_1 (not manual_2))
           :named isolated_modes_are_reachable))

(check-sat)
(get-model)
