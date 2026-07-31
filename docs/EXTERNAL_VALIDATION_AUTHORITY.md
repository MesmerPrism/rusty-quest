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
base/head commits, and the exact fetched GitHub PR merge commit and tree. When
the event payload supplies a nonempty merge commit it must equal that fetched
object; an empty nullable event value does not weaken the fetched merge-ref and
exact-parent proof. The assessment also records the observed Git and
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
