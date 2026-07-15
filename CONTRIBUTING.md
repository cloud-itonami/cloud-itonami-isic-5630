# Contributing to cloud-itonami-isic-5630

Contributions should preserve the actor's scope: beverage-serving venue
back-office coordination only, with CRITICAL exclusions of any direct
responsible-service-of-alcohol (RSA) authority decision and any direct
equipment control (see README.md).

- All code must be .cljc (portable Clojure, no JVM-only constructs).
- Tests must pass: `clojure -M:dev:test`
- Commit messages should link to relevant ADRs or issues.

**This actor does NOT:**
- Decide to continue, resume, or authorize service to an intoxicated patron.
- Override, bypass, or waive an age-verification/ID-check failure, or
  authorize service to a minor.
- Directly control any equipment (taps, POS terminals, access control
  systems).
- Make any final responsible-service-of-alcohol (RSA) authority decision of
  any kind.

Contributions that cross these boundaries will be rejected.
