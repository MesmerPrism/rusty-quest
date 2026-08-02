# Fleet Agent fixtures

`checkin-claims-golden.valid.json` is copied from the public Rusty Fleet
contract fixture at commit
`8181683be4a3abbc5daa0c4497c7aeb9e76316a8`:

<https://github.com/MesmerPrism/rusty-fleet/blob/8181683be4a3abbc5daa0c4497c7aeb9e76316a8/fixtures/contracts/checkin-claims.valid.json>

The fixture's RFC 8785/JCS claims digest is:

`401ba8b5a3190fc9e34ec8c203d596d69b5a0eb8bd1d7e23996aa5f37146ca04`

The domain-separated signing-message digest is:

`a9dd28a3681ccd242fee648a7010b85a69df38147f487e8c4e7e2b08116b8432`

The test seed and signature are public deterministic test vectors only. They
must never be used for enrollment or an installed agent.

`fleet-agent.disabled.profile.json` is the default-inert example. Its
fingerprint is intentionally not an enrollment record and its documentation
endpoint is non-routable.

`key-record-release-scenarios.valid.json` records the exact portable capsule
file set, the distinct target and workspace-parse-only repository identities,
and its non-claims. `key-record-release-scenarios.damaged.json` is the
closed negative matrix for artifact/source/provenance substitution, secret
leakage, extra repository/field/file damage, and stale version/target damage.
It also covers ASCII and UTF-16 machine-path leakage in the executable. The
release self-test updates linked hashes and checksum projections where needed,
rejects escalation of a workspace-parse-only repository into a dependency role,
then requires each semantic mutation to reject; fixture values contain no
executable, seed, profile, Hub configuration, or private device evidence.
