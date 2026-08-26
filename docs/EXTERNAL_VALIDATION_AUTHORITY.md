# External Validation Authority

Use this route only when a pull request changes Rusty Quest validation,
publication, or updater authority protected by
`config/external-validation-authority.json`. Ordinary source and documentation
changes keep their normal risk-proportional validation path.

The base-owned `pull_request_target` workflow checks out only the exact trusted
base. It fetches the PR head and GitHub merge objects into private Git refs,
verifies their event identities and exact merge parents, and invokes the
external verifier pinned to work-environment commit
`50a4c5222c9d6c4567bac09405e43049c61b126f`. The verifier commit, tree,
entrypoint length, and entrypoint SHA-256 are checked before invocation.
Candidate files are never checked out, imported, built, restored, extracted,
or executed.

The static assessment binds the exact event base repository
`MesmerPrism/rusty-quest`, base ref `main`, head repository identity, exact
base/head commits, and the exact fetched GitHub PR merge commit and tree.
GitHub's event merge SHA is observation only: live runs proved it can be empty
or stale relative to the current generated merge ref. The workflow validates
its shape when present and records its relation, but never uses it as
authority. The freshly fetched merge ref must have the exact event base and
head parents. The assessment also records the observed Git and
PowerShell versions, executable byte lengths and SHA-256 digests, plus the
hosted-runner OS, architecture, image OS, and image version. `windows-2025` is
a moving hosted-runner label; there is no accepted current image allowlist.
The assessment therefore records `image_allowlist_enforced=false` and
`drift_status=observed-unpinned`. Runtime drift is visible evidence, not a
rejected condition or a claimed runtime pin. Adding an allowlist is a later
protected two-PR authority change.

A separate `pull_request` workflow checks out the exact candidate head and
executes only credential-free package-updater validation on `windows-2025`:
Rust formatting, the package-updater Rust tests, and the Android static gate.
It pins Temurin `17.0.14+7` and Rust `1.96.0`, has a bounded timeout, and has no
secrets, environments, OIDC, artifacts, caches, or write permission. This
candidate-executing workflow is deliberately non-authoritative: its result is
test evidence only, never owner-effect, acceptance, or publication authority.
Exact Manifold, Lattice, Matter, and Optics companion checkouts satisfy the
Quest workspace's path graph; the test command still selects only the package
updater and makes no validation claim about those companion repositories.

Only the base-owned workflow produces the
`rusty.quest.external_validation_authority_assessment.v1` static admission
result. It always records
`candidate_code_executed=false`, `execution_attested=false`, and
`publication_authority=false`. Dynamic validation, repository review, release
environment approval, and publication remain separate authorities.

## External-owner fallback after bootstrap

The historical `bootstrap-sealed-candidate-i` record is a consumed singleton:
it remains immutable and cannot be rebound, deleted, or reused. A later
protected proposal that the pinned base verifier rejects with exactly
`Protected changes do not match an exact base-approved change set.` may instead
receive one fresh `rusty-quest-external-owner-authorization:v1` PR comment.
The base-owned adapter derives a canonical request from Git objects only and
binds repository/PR, exact base and head commits/trees, ordinal-sorted changed
and protected artifact inventories (mode, byte length, SHA-256), and the
canonical static hold assessment. The signed marker carries one audit id,
pinned issuer/key id, issue/expiry time, and RSA-PSS-SHA256 signature.

The adapter accepts exactly one unedited pinned-owner marker for identical
evidence during its freshness window. Duplicate markers, changed Git objects
or artifacts, changed assessment bytes, stale/future/invalid signatures,
untrusted keys, malformed JSON, and an already-trusted candidate all reject.
Exact-evidence reruns remain idempotent until expiry. The only resulting state
change is static decision `external-owner-authorization`; it retains
`candidate_code_executed=false`, `execution_attested=false`, and
`publication_authority=false`. It grants no test, acceptance, merge, release,
settings, or device authority.

The authority job projects only GitHub's built-in read-only token as
`GITHUB_TOKEN` to its base-owned adapter so it can page public PR comments.
That token is not passed to a candidate checkout, another job, a secret, or an
environment. The fallback invokes the hash-verified pinned verifier through
the workflow's fixed `windows-2025` child-`pwsh` transport. It accepts only
exit status `1` and one `ErrorRecord` with exact type
`System.Management.Automation.RemoteException`,
`FullyQualifiedErrorId` `NativeCommandError`, category `NotSpecified`, and a
target and exception message equal byte-for-byte to:

```
Exception: Protected changes do not match an exact base-approved change set.<CR><LF>
```

The terminal `CRLF` (`0D0A`) is the legacy Windows PowerShell native-error
transport envelope, not verifier policy text. The fixed plain `pwsh -File`
renderer emits exactly five `RemoteException` records: the full hash-pinned
verifier path plus its pinned throw line, `Line |`, the pinned source excerpt
and underline, then the exact hold text. The current hosted `windows-2025`
renderer may instead emit a separate five-record ANSI SGR profile. That third
profile is closed to the live hash-verified verifier path and line `969`, exit
`1`, exact record identity/order/category/target, and exact ordinal UTF-8
bytes. Its control text is constructed directly from `[char]27` plus only the
observed red (`[31;1m`), cyan (`[36;1m`), and reset (`[0m`) sequences, including
the exact source excerpt, underline, and Unicode ellipsis. The adapter accepts
one complete legacy, plain, or ANSI profile only; it does not strip ANSI,
apply a regex, substitute a root placeholder, consume raw stderr, trim,
normalize, or accept a substring of any diagnostic. A bare `LF`, no terminator,
multiple terminators, whitespace, prefix/suffix text, another exception or
record type, a different exit status, extra output (including stdout), a
different verifier path/line/source diagnostic, a partial assessment,
different decisions or approval ids, and missing or malformed assessment fields
all reject. A runner or PowerShell transport change therefore fails closed and
requires a separately reviewed trust-root update. NUL-delimited Git paths are
parsed as strict UTF-8, canonicalized, ordinally sorted, case-collision-free
paths; malformed delimiters, duplicate/colliding paths, and incomplete changed
or protected inventories reject before hashing or signing.

## Extraordinary one-time trust-root bootstrap record

An independently reviewed trust-root evolution may require a one-time record
when the old base can neither emit nor verify the normal runtime assessment.
This is not the external-owner fallback above. Its closed request schema is
`rusty.quest.external_owner_bootstrap_request.v1`; its closed signed envelope
schema is `rusty.quest.external_owner_bootstrap_authorization.v1`; and its only
marker is `rusty-quest-external-owner-bootstrap-authorization:v1`. A record
uses the same pinned owner, key id, RSA-PSS-SHA256 algorithm, canonical JSON
primitive, exact UTC-second (`YYYY-MM-DDTHH:MM:SSZ`) issue/expiry syntax,
issue/expiry window, unedited-comment rule, and freshness bounds as the normal
policy, but contains no runtime executable, tool, runner, normal
assessment, or normal request identity. The independent signer constructs and
signs the closed JSON from the schemas and canonical/RSA primitive; it never
executes candidate helper code.

The record binds the repository and PR; exact base and head commits and trees;
the generated merge tree and its ordered base/head parents; full changed and
protected artifact arrays; both counts; and a separate `inventory_digest`.
`generated_merge.observed_commit` records the synthetic GitHub merge commit
seen during the independent review, but is observation only. On the one-time
consumption readback, a different synthetic merge SHA is admissible only when
its exact tree remains the signed head tree and its ordered parents remain the
signed base commit followed by the signed head commit. The new observed SHA is
still required to be a canonical full object ID; this exception applies only to
that synthetic generated-merge observation, never to the base, head, artifact,
or other commit identities. Every artifact is a present Git blob with exact
portable path, mode, byte length, and lowercase SHA-256. The arrays must be
ordinally sorted, unique, complete, and have identical metadata where a
protected artifact occurs in the changed inventory.

`inventory_digest` is SHA-256 over the following exact domain, named
`rusty.quest.external_owner_bootstrap_inventory.v1`: for every changed
artifact in `StringComparer.Ordinal` path order, encode one UTF-8 (no BOM) LF
line with this fixed six-field order and TAB separators:

```
path<TAB>protected|unprotected<TAB>present<TAB>mode<TAB>size_bytes_decimal<TAB>sha256<LF>
```

`protected` is selected only when the same path appears in the complete
protected array; the protected row must have the same mode, size, and hash.
There is no omitted-field encoding in a bootstrap artifact record. The schema
therefore fixes the digest algorithm, domain, encoding, LF line ending, TAB
separator, field order, and its `-` absent-field sentinel even though the
closed v1 record rejects absent artifacts. Hashing the JSON text, changing the
separator or order, or omitting a line is not the digest domain.

The request also records the independently reviewed trust-root intent, explicit
user-authorized one-time bootstrap, exact old-base `pull_request_target` hold
run/job/message, ordinary credential-free candidate CI as non-authoritative
evidence, and the supplied local aggregate receipt as non-authoritative dynamic
evidence. It permanently states `candidate_code_executed=false`,
`execution_attested=false`, `static_admission_authority=false`,
`acceptance_authority=false`, and `publication_authority=false`. Its sole
decision is `authorize-one-time-bootstrap-merge-review`: it authorizes neither
PR #53 nor any future candidate, static admission, testing, acceptance, merge,
release, settings, or device work.

The permanent runtime adapter must reject a bootstrap marker and never parses
either bootstrap schema. Duplicate markers, a previously consumed audit id,
stale or future timestamps, wrong keys, malformed or noncanonical JSON,
changed artifacts, different inventory digests, a changed base/head, a changed
merge tree, reordered merge parents, or any attempt to add runtime identity
fields reject. A regenerated synthetic SHA with the same exact stable topology
is the sole allowed observation change. This extraordinary record is consumed
only by the orchestrator's already user-authorized, exact-head admin merge of
this trust-root PR. It is a durable review receipt, not retained or reusable
base authority; no later protected proposal may cite it.

GitHub required status checks match a context and optional GitHub App source;
they do not bind a workflow path, matrix, or event. A candidate workflow can
therefore emit the same context through the same GitHub Actions App. The exact
repository-settings contract is
`config/external-validation-authority-settings.json`. Rusty Quest is user-owned,
so organization/enterprise required-workflow rules are not deployable here.
The selected repository rule can bind only context `Static admission` to GitHub
Actions App ID `15368`; it cannot bind the base-owned workflow path. That rule
is not authoritative by itself. Authority remains held until the mandatory
same-name/same-App adversarial probe below produces an observed private receipt.
The ordinary candidate-executing dynamic workflow remains supplemental, not
required and not authoritative.

## Protected Change Procedure

1. Seal the independently reviewed implementation commit as immutable `I`.
   Do not amend, rebase, replace, or force-update it.
2. Have an independent reviewer audit `I` and the exact planned candidate
   tree `J` after the bootstrap base `S` is merged. The approval's
   `required_ancestor` is `I`, but its sorted `changed_paths` and artifact
   evidence describe the complete two-endpoint diff `S..J`, not an assumed
   pre-bootstrap `B..I` diff. This distinction matters when bootstrap and
   candidate touch the same path. Seal an empty-policy bootstrap commit first,
   construct and review the exact combined tree, and prove that the later
   approval-only bootstrap commit is inherited identically by both `S` and
   `J`. Audit every final artifact's Git mode, byte length, and SHA-256.
   Before the final bootstrap commit, replace the live empty
   `approved_change_sets` array with exactly one object shaped like
   `fixtures/validation-authority/bootstrap-approval.valid.json`. Its
   `approval_id` must be exactly `bootstrap-sealed-candidate-i`, its
   `required_ancestor` must be exactly `I`, and its sorted artifacts must equal
   its sorted changed paths. Do not alter `I` after this insertion.
3. Run the policy self-test with
   `-ExpectedBootstrapApprovalAncestor <I>`. A missing approval, a second
   approval, a different ancestor, an extra field, unsorted or mismatched paths,
   or malformed artifact evidence must fail. Once a live approval exists,
   omitting the expected ancestor must also fail.
4. Merge the bootstrap policy PR to `main` with an ordinary two-parent merge
   commit. Squash merge, rebase merge, merge queue, and history rewriting are
   forbidden for this trust-root sequence.
5. Merge that exact updated `main` into the branch containing `I` with an
   ordinary merge commit. `I` must remain byte-for-byte immutable and an
   ancestor of the resulting candidate head.
6. Let the base-owned static-admission workflow inspect the Git objects.
7. Run credential-free dynamic validation separately.
8. Merge the candidate PR with an ordinary two-parent merge commit. Squash,
   rebase, merge queue, and force-update routes are forbidden.
9. Fetch the resulting `main` and prove `git merge-base --is-ancestor I
   origin/main` succeeds. Re-run the base verifier against a matching protected
   proposal and record its consumed-approval rejection. This proves `I` landed
   and the approval cannot authorize another candidate. Do not claim that
   deleting the consumed approval is self-authorizing; any later policy
   evolution needs its own externally approved trust-root procedure.

The first bootstrap itself must also land through an ordinary merge commit.
The bootstrap policy intentionally contains no live approvals before `I` is
sealed. The fixture is inert test data, not an approval. Insert the one live
approval only after independent audit and immediately before the final
bootstrap commit. Until then, run the default self-test against the empty live
array. This same-bootstrap exception is not permission to add an approval to
an implementation PR or to carry more than one approval.

## Local Checks

```powershell
pwsh -NoProfile -NonInteractive -ExecutionPolicy Bypass `
  -File .\tools\checks\Test-ExternalValidationAuthorityPolicySelfTest.ps1 `
  -RepoRoot .
pwsh -NoProfile -NonInteractive -ExecutionPolicy Bypass `
  -File .\tools\checks\Test-ExternalValidationAuthorityStatic.ps1 `
  -RepoRoot .
# After I is sealed and the one live bootstrap approval is inserted:
pwsh -NoProfile -NonInteractive -ExecutionPolicy Bypass `
  -File .\tools\checks\Test-ExternalValidationAuthorityPolicySelfTest.ps1 `
  -RepoRoot . `
  -ExpectedBootstrapApprovalAncestor <I>
pwsh -NoProfile -NonInteractive -ExecutionPolicy Bypass `
  -File .\tools\checks\Test-ExternalValidationAuthorityStatic.ps1 `
  -RepoRoot . `
  -ExpectedBootstrapApprovalAncestor <I>
# The aggregate check accepts the same parameter after insertion:
pwsh -NoProfile -NonInteractive -ExecutionPolicy Bypass `
  -File .\tools\check_all.ps1 `
  -ExpectedBootstrapApprovalAncestor <I>
```

The upstream verifier's focused regression is owned by the pinned work
environment and must be run from that exact source when changing the pin. The
Quest checks prove only the repository adapter, policy closure, both workflow
constraints, and typed assessment projection.

## Repository Settings Required

Landing these files is not sufficient repository protection. After the
bootstrap and its one sealed-candidate approval land, configure an active
repository ruleset on `refs/heads/main` with no bypass actors, required pull
requests, strict required status context `Static admission` bound to GitHub
Actions App ID `15368`, and force pushes and deletion blocked. Read the applied
ruleset back through the API and hash its canonical snapshot. The settings do
not identify `.github/workflows/external-validation-authority.yml`; they only
identify a same-App context.

Before any candidate merge, publication, release, or Pages deployment, run this
mandatory adversarial probe while all three deployment environments remain
closed:

1. Open a disposable PR containing the unapproved protected path
   `.github/workflows/external-validation-authority-spoof-probe.yml`. It must be
   a credential-free `pull_request` workflow whose job name is exactly
   `Static admission` and whose only result is success. Do not add a policy
   approval for this diff.
2. Prove the base-owned `pull_request_target` run inspected the exact event
   base/head/merge objects and concluded failure, while the candidate workflow
   concluded success. Query workflow runs and check runs through the API; record
   every run ID/attempt/path/event/head SHA and every check-run ID/suite ID/name,
   App ID, conclusion, details URL, and completion time.
3. Exercise both completion orders. First rerun the base workflow to failure,
   then rerun the spoof to success and read merge state. Next rerun the spoof to
   success, then rerun the base workflow to failure and read merge state again.
   Each phase must map unambiguously to its workflow and check run on the exact
   branch-protection-evaluated SHA.
4. API-read the active ruleset, PR base/head/merge identities, complete check
   inventory, and protection-aware `mergeStateStatus` after each ordering. Do
   not call the merge endpoint, enable auto-merge, use an admin bypass, or push
   the probe to `main`.
5. A pass requires `BLOCKED` after both orderings, both opposite-result runs
   proven, distinguishable run-to-check mappings, exact App ID `15368`, zero
   bypass, and no inconsistency. Any merge-eligible or non-`BLOCKED` state,
   ambiguous inventory, missing run, different App, ordering difference, or
   bypass produces `decision=hold`.
6. Close the PR unmerged. Store the observed receipt privately using
   `rusty.quest.external_validation_authority_probe_receipt.v1`; commit only a
   private/local evidence pointer where appropriate. The committed fixture is
   synthetic and permanently `decision=hold`; it is not evidence.

`github-pages`, `package-update-labs-publication`, and
`package-updater-labs-release` remain closed until an observed pass receipt is
reviewed. Required deployments or environment approvals do not by themselves
bind a workflow path, so they do not replace the probe. A future dedicated
GitHub App or organization/enterprise required-workflow rule may replace this
mode only through a separately reviewed authority change.

Restrict secret-bearing deployment environments independently and exactly:

- `package-update-labs-publication` admits only the protected `main` branch.
  Its publishing workflow must run at the exact `main` commit; tags, feature
  branches, pull requests, and `pull_request_target` runs are denied.
- `package-updater-labs-release` admits only protected tags matching exactly
  `package-updater-v0.1.0-alpha.*`. The release workflow must additionally
  prove the tag's peeled commit is reachable from protected `main`. Branch and
  pull-request deployments are denied.
- `github-pages` admits only the protected `main` publisher/deployer workflow.
  The deploy job must depend on the same run's successful publication job and
  deploy the exact feed commit emitted by that job; arbitrary branch, tag,
  pull-request, artifact, or independently selected feed revisions are denied.

No pull-request-triggered workflow may reference any of these environments.
Add environment reviewers where the release is intentionally attended. A
workflow assessment, including a passing static assessment, never substitutes
for branch, tag, environment, exact publisher/deployment lineage, or
publication-owner controls.
