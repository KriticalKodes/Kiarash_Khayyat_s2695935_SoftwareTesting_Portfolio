# Auditable test evidence (added in response to grading feedback)

This directory contains the generated reports that were previously claimed in the portfolio but not committed.

## Contents
- `surefire-reports/` — XML and text reports for all 12 test classes (41 tests, 0 failures, 0 errors, 0 skips).
- `jacoco/` — JaCoCo `jacoco.csv`, `jacoco.xml` and HTML index. Service + mapper packages: **95.2% line, 100% branch**; whole project: **90.1% line**. The `verify` phase enforces ≥90% line / ≥80% branch with `jacoco:check`.
- `pit/` — PIT mutation report. **35 mutations generated, 32 killed = 91% mutation score** (threshold 70%), line coverage of mutated classes 95%.
- `logs/mvn_verify.log` — full `mvn -B verify` run showing `All coverage checks have been met`.
- `logs/mvn_pit.log` — full PIT run showing `Generated 35 mutations Killed 32 (91%)`.
- `logs/ci_local_run.log` — local replay of the committed GitHub Actions pipeline (`mvn verify` → PIT → report artifacts).

## New tests added
- `ChargeCalculatorPropertyTest` — 500 generated jqwik cases for pricing formula, HALF_UP and scale invariance.
- `TariffContractTest` — subtractive schema drift and wrong field type in tariff JSON.
- `TariffTimeoutTest` — real local HTTP server that sleeps beyond the client read timeout; verifies bounded degradation.
- `ConcurrencyUniquenessTest` — two parallel registrations of the same manifest number; exactly one succeeds.
- Extra S3 fault-injection cases: generic 404, non-404 propagation, delete failure.
- Extra service case: compensation failure does not mask the original index failure.

## Instrumentation improvements
- Per-registration correlation ID included in log lines.
- Timer around each tariff lookup with `durationMs` in the log.
- Narrowed caught exceptions from `RuntimeException` to `RestClientException` / `HttpMessageConversionException`.
- In-process per-manifest lock to close the concurrent duplicate-check race; a conditional DynamoDB write remains the production-grade recommendation.

## Pipeline
The repository root now contains `.github/workflows/ci.yml`: push/PR → JDK 21 → `mvn verify` (tests + coverage gate) → PIT mutation gate → report artifacts → container dry run.
