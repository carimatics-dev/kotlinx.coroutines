From iris.heap_lang Require Import proofmode notation lang.
Require Import SegmentQueue.lib.util.getAndSet.
Require Import SegmentQueue.lib.util.interruptibly.

Definition new_exchange : val :=
  λ: <>, ref NONE.

Definition init_exchange : val :=
  λ: "ℓ", "ℓ" <- NONE.

Definition await : val :=
  rec: "await" "e" := (match: !"e" with
                         NONE => "await" "e"
                       | SOME "v" => match: "v" with
                                      NONE => !#0
                                    | SOME "v'" => "v'"
                                    end
                       end)%E.

Notation CANCELLED := (SOME NONE) (only parsing).
Notation RESUMED x := (SOME (SOME x)) (only parsing).

Definition await_interruptibly : val :=
  (loop:
    λ: "e", !"e"
  interrupted:
    λ: "e", getAndSet "e" CANCELLED)%E.

Definition pass : val := λ: "e" "r", getAndSet "e" (RESUMED "r").

Definition resume : val := λ: "e", pass "e" #().