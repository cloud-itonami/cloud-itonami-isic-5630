# cloud-itonami-isic-5630

**Beverage Serving Activities** — ISIC Rev.4 class 5630.

A coordination-only actor for beverage-serving venues (bars, pubs, coffee shops, juice bars — on-premise beverage service, distinct from beverage manufacturing), behind an independent Governor that earns advisor trust through structured oversight: proposal → advise → govern → decide → commit|hold|escalate.

## Features

- **Closed proposal-op allowlist**: `log-service-record` (order/tab/inventory-draw data logging), `schedule-staffing-operation` (shift/prep scheduling proposal), `coordinate-supply-order` (beverage/ingredient procurement proposal), `flag-guest-safety-concern` (surface an over-service/underage-attempt/altercation concern — ALWAYS escalates). All `:effect :propose`.
- **Three HARD governor checks** (permanent, un-overridable):
  1. **Venue unverified** — target venue must exist AND be independently registered/license-verified in the store.
  2. **Effect is :propose** — any other `:effect` value is rejected.
  3. **RSA-decision / scope exclusion** — a proposal outside the closed op allowlist, or one that directly *finalizes* a responsible-service-of-alcohol (RSA) authority decision (continuing/resuming/authorizing service to an intoxicated patron; overriding, bypassing or waiving an age-verification/ID-check failure; serving a minor), is permanently blocked. This actor NEVER directly finalizes an RSA decision and NEVER controls equipment (taps, POS terminals, access control) — see `beverageops.governor` for the full term catalog. Merely *flagging* a suspected over-service or underage-attempt concern (`flag-guest-safety-concern`) is not itself a scope violation — that is exactly what the op exists to surface.
- **Two ESCALATE (always human sign-off) gates**:
  - `flag-guest-safety-concern` — ALWAYS escalates, regardless of confidence or phase, and is never a member of any phase's `:auto` set.
  - `coordinate-supply-order` above a cost threshold (`beverageops.governor/supply-cost-threshold`, default 5000) — ALWAYS escalates, so a large procurement commitment is never auto-committed silently.
- **Staged rollout** (Phase 0→3):
  - Phase 0: read-only
  - Phase 1: service-record logging only (approval-gated)
  - Phase 2: + staffing-operation, supply-order proposals (approval-gated)
  - Phase 3: auto-commits clean, high-confidence proposals (guest-safety concerns and above-threshold supply orders always escalate)
- **Append-only audit ledger** — every decision is an immutable log entry.
- **langgraph-clj StateGraph** — one request = one supervised run; human-in-the-loop via `interrupt-before`.

## CRITICAL scope exclusions

This actor is an **operations-coordination** actor, not a responsible-service-of-alcohol (RSA) authority and not equipment control. It does **NOT**:

- Decide to continue, resume, or authorize service to an intoxicated patron.
- Override, bypass, or waive an age-verification/ID-check failure, or authorize service to a minor.
- Directly control any equipment (taps, POS terminals, access control systems).
- Make any final RSA authority decision of any kind — those decisions belong to venue staff/management, always with a human in the loop.

`flag-guest-safety-concern` exists so a suspected over-service, underage-attempt, or altercation concern can be **surfaced to a human** — it never itself decides how to resolve the concern, and it always escalates.

## Development

```bash
# Install dependencies (if inside the superproject, use :dev alias for local overrides)
clojure -M:dev -P

# Run tests
clojure -M:dev:test

# Run linter
clojure -M:lint

# Run demo
clojure -M:run
```

## Test suite

- `test/beverageops/governor_test.clj` — unit tests of governor hard checks and RSA-decision exclusion
- `test/beverageops/advisor_test.clj` — advisor proposal shape and consistency
- `test/beverageops/phase_test.clj` — rollout phase logic
- `test/beverageops/governor_contract_test.clj` — full graph integration, audit trail
- `test/beverageops/store_contract_test.clj` — Store protocol and MemStore implementation

## Modules

- `beverageops.store` — SSoT (MemStore, String-keyed venue directory, append-only ledger)
- `beverageops.advisor` — contained intelligence node (mock + real-LLM seam)
- `beverageops.governor` — independent compliance layer
- `beverageops.phase` — staged rollout (0→3)
- `beverageops.operation` — langgraph-clj StateGraph
- `beverageops.sim` — demo driver

## License

AGPL-3.0-or-later. See LICENSE file.

## Governance

This actor is part of the cloud-itonami Wave 4 (human-facing/personal-services) fleet. See ADR-2607121000, ADR-2607152500, and the ISIC-5630 beverage-serving-coverage ADR in `90-docs/adr/` of the `com-junkawasaki/root` superproject for design decisions.
