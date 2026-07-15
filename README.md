# hive-recife

A small Clojure adapter over [recife](https://github.com/pfeodrippe/recife) (Clojure → TLA⁺/TLC model checker) that normalizes a model-check run into a single, uniform result value with classified counterexample traces.

recife lets you write a TLA⁺ specification in Clojure and check it with TLC. `hive-recife` sits one layer above: it runs a spec and maps recife's raw output into a closed `ModelCheckResult`

```clojure
{:status :ok | :fail | :skip
 :details { … }}
```

- `:ok` — no property was violated.
- `:fail` — a counterexample exists; `:details :counterexample` carries the classified violation (`:safety` / `:liveness`), its length, and the state sequence.
- `:skip` — the check was **indeterminate**: recife/TLC is not on the classpath, there was no spec, or TLC errored. A `:skip` is **never** conflated with `:ok` — absence of evidence is not a pass.

## Why

recife's raw result shape is easy to get wrong. In run-local mode `run-model` returns the result map directly; only the async path returns a derefable. A clean run yields `{:trace :ok …}`; a violation puts the counterexample under `:trace` with a `:trace-info` violation marker. `hive-recife` folds those cases into one total function so callers get a predictable value on every path — including the failure paths.

## Install

deps.edn:

```clojure
io.github.hive-agi/hive-recife {:mvn/version "0.1.0"}
```

recife itself is resolved lazily (`requiring-resolve`), so `hive-recife.core` loads on any classpath; a check simply returns `:skip` when recife/TLC is absent.

## Usage

```clojure
(require '[hive-recife.core :as mc])

;; A ModelSpec is a plain map: the initial global state, the recife
;; components (processes, invariants, temporal properties), and the declared
;; names of the safety / liveness properties it carries.
(mc/check! spec)
;; => {:status :ok :details {:distinct-states 12 :generated-states 29}}
```

The effect is **injected**: `check!` takes an optional `run-fn` (a function from a spec to a raw recife result), so the pure normalization pipeline is testable with no recife/TLC on the classpath:

```clojure
(mc/check! spec (fn [_] {:trace :ok}))                 ;; => {:status :ok …}
(mc/check! spec (fn [_] {:trace [[0 s0] [1 s1]] :trace-info {}}))
;; => {:status :fail :details {:counterexample {:violation-type :safety …}}}
```

### Violation classification

Counterexamples are classified by an ordered, open rule chain (first match wins), so new cases are added as rules rather than edits:

| recife `:trace-info` marker | classified as |
|---|---|
| `:back-to-state`, `:stuttering` | `:liveness` |
| `:invariant` (or a bare finite counterexample) | `:safety` |

## Example

`hive-recife.examples.verdict-handoff` models a compare-and-swap plan→commit handoff as a recife spec with a safety invariant (a stale/expired verdict is never applied). See `src/hive_recife/examples/verdict_handoff.clj`.

## Development

```bash
clojure -M:test        # run the suite (recife/TLC resolved on the :test classpath)
clojure -M:nrepl       # nREPL for iterating a live spec
```

The value objects are [malli](https://github.com/metosin/malli) schemas; the pure functions carry `m/=>` contracts sourced from them.

## License

MIT © 2026 Pedro Gomes Branquinho. See [LICENSE](LICENSE).
