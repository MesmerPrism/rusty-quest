# Finite real-peer setup and root renewal

The packaged input's optional `setup_credential_horizon_ms` is transferred by
the Java asset loader and validated by the native assembler. Explicit values
are whole minutes from five through sixty, with both initial grant and outer
lease deadlines equal to the checked issuance time plus that horizon. Absence
keeps the legacy five-minute policy. The selected artifact owns the value;
runtime callers cannot replace it.

The native assembler binds this value to the exact admission grant in the
Manifold snapshot. Concurrent renewal uses that authenticated per-grant horizon
for both normal extending grant renewal and generic outer lease renewal. It
still consumes real scoped tokens, accepts signed reciprocal/session evidence,
and requires AcceptedAndAdopted outer, derivative, route and product evidence.
There is no retained-root success shortcut. Deployment requires the compatible
Manifold owner described in its schema-evolution contract; opted-in records are
not readable by older strict snapshot/receipt consumers.

Thirty-minute roots do not grant thirty-minute media or sessions. Existing
operation-token caps, signed session bounds, the 235-second derivative cap,
180-second route cap, and initial Start token cap remain unchanged. A first
renewal must still reach every current bound before it expires. Host controls
prove real native first and later coupled renewals and default behavior, with
deterministic Android executors; they do not prove a physical streaming run.

Focused controls are the admission owner's tests, broker-authority
`finite_thirty_minute_setup_roots_complete_real_first_and_later_coupled_renewals`
and `retained_prepare_compact_22_coupled_cycles_then_expiry`, and the ignored
native-receipt `finite_setup_actual_java_transfer_assembles_native_scoped_policy`.
The latter requires `RUSTY_QUEST_PACKAGED_POLICY_REQUEST`, an explicitly retained
actual Java packaged request. Optional `RQ_FINITE_ROOT_RECEIPT_TEST_OUTPUT`
retains real native coupled receipts for the existing private PS consumer.
