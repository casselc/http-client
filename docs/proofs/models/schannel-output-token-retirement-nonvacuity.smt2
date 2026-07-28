; Concrete corrected send/free/clear/outer-cleanup trace. Expected: sat with
; exactly one native free and the outer cleanup observing a cleared pointer.

(set-option :produce-unsat-cores true)

(declare-const send_step Int)
(declare-const inner_free_step Int)
(declare-const clear_step Int)
(declare-const outer_cleanup_step Int)
(declare-const outer_sees_live_pointer Bool)
(declare-const total_free_count Int)

(assert (! (= send_step 0) :named send_occurs))
(assert (! (= inner_free_step 1) :named inner_free_occurs))
(assert (! (= clear_step 2) :named descriptor_clear_occurs))
(assert (! (= outer_cleanup_step 3) :named outer_cleanup_occurs))
(assert (! (= outer_sees_live_pointer (< outer_cleanup_step clear_step))
           :named outer_observes_descriptor_state))
(assert (! (= total_free_count
              (+ 1 (ite outer_sees_live_pointer 1 0)))
           :named total_free_count_definition))
(assert (! (and (not outer_sees_live_pointer) (= total_free_count 1))
           :named exactly_once_trace_is_reachable))

(check-sat)
(get-model)
