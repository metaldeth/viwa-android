# WS and offline resilience

## Big Rock Brief

- **Session:** `ws-offline-resilience`
- **Owner:** complex orchestration
- **Repos:** `viwa-android`, `viwa-telemetry`
- **Reference:** `C:\shaker`
- **Outcome:** stable machine telemetry connection, automatic recovery after silent disconnect, durable offline subscription/sales operations, and telemetry-managed technician keys.
- **Non-goals:** Docker changes; unrelated UI refactors; offline card/SBP payments.

## Acceptance

1. WS detects half-open connections and reconnects without user action.
2. Reconnect does not create concurrent active sessions or duplicate business effects.
3. Known subscriptions can be served offline only within a bounded, cached entitlement.
4. Offline usage and sales survive process death/reboot and sync idempotently.
5. Technician keys have scoped access, expiry/revocation handling, and audit events.
6. Emulator chaos/soak covers server restart, network loss, recovery, and prolonged stability.

## Verification

- Android unit/integration tests and debug build.
- Telemetry API tests and schema migration checks.
- Emulator install and offline scenarios.
- Safe network-chaos tests that do not firewall the operator IP.
- Review, session log, and complex completion checklist.

## Constraints

- Existing uncommitted Android changes must be preserved and reviewed before extension.
- `C:\shaker` is read-only reference.
- All replayable operations require stable idempotency keys.

## Owner decisions

- Offline subscription sync covers all active subscriptions as hashed, PII-free delta records with signed grants.
- Offline allowance is configured per subscription tariff. Missing tariff policy means offline pours are denied.
- Technician access may work offline from a signed trusted-key allowlist with scopes and revocation epoch.
- Reconcile rejection creates an audit conflict and admin notification; it does not block the machine.
- Verified fixes may be deployed to the telemetry server and installed on the emulator during this session.
- Final overnight report must be delivered as a Cursor Canvas.
