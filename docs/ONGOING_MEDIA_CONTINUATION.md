# Prospective native media continuation

The explicit config field `embedded_duplex.ongoing_media_policy` selects the
closed Manifold policy object
`{"$schema":"rusty.manifold.peer.ongoing_media_policy.v1"}`. It enters through
the existing config digest/product owner; no property, caller counter, browser
message or unsigned receipt enables it. Omission serializes exactly as before
and keeps the finite default. The fixed160-word native ABI is unchanged.

Selected coupled renewals use
`rusty.quest.concurrent.authority_renewal_receipt.v2`. They expose the actual
`ongoing_media_policy`, `cumulative_renewal_count`, `checkpoint_sequence`,
`checkpointed_renewal_count` and `suffix_renewal_count`. Zero checkpoint
sequence/count precedes the first checkpoint. Total equals checkpointed plus
suffix; it is never reset by history compaction. `continuation_checkpoint`
appears on an actual checkpoint transition or exact retry. Finite receipts
remain v1 and omit these fields.

The provider retains exact pending phases and checkpoints only after actual
same-resource product adoption. The original issue-time route and derivative
remain in the accepted owner checkpoint. Current grants, signed revisions,
clock, capability, lease/session/route deadlines and expiry math remain
unchanged. A rejected/unknown phase is not a new Start, an accepted checkpoint
or physical cleanup. A detached ongoing snapshot cannot restore itself:
the facade seals its current live owner independently and admits only that
exact target against the live Broker. It cannot attest process-death recovery.

The native signed-pair actor uses the explicit continuation ceremony namespace
`ceremony.duplex.continuation.<ordinal>.<nonce>`. A strictly next ordinal and
canonical nonce are required. Completed ordinal high-water survives actor
round reset; old/skipped/relabelled cycles reject. Simultaneous-start yielding
retains at most eight abandoned contenders for the current ordinal. A yielded
unaccepted contender does not become an accepted renewal. Pending request and
reply retention remains exact; fixed eight-message bounds remain.

Selected native lifecycle observations use
`rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v2`; `continuation`
has schema `rusty.quest.embedded_duplex.ongoing_media_projection.v1`. It joins
the actual coupled policy, owner count, checkpoint/suffix counts, provider
epoch, owner request and physical graph digest. The enclosing observation
still binds config SHA, native executor generation, app-process generation,
current Start mutation/activation and original route. A consumer must join all
those records; an ordinal alone grants no authority. Under this explicit mode,
the top-level `ongoing_media_policy` also reports the native-selected policy
before the first accepted renewal; a missing continuation result is not a
completed renewal. Under this explicit mode,
`renewal_total_completed` is monotonic for the live provider lifetime, including
across normal media Stop/Start; finite v1 retains its current-transaction
meaning. New Start/ARM evidence remains a separate transaction boundary.

Cleanup Prepare may carry a native-derived
`rusty.quest.retained_cleanup.continuation_checkpoint.v1` ancestry certificate.
The projector first validates its actual owner and live Broker, then joins
original prior topology to the checkpoint baseline and remaining suffix. The
enclosing existing native signed Prepare authenticates the complete certificate;
a detached history digest is neither restore authority nor cleanup permission.
The receiver must also explicitly select ongoing policy and preserve enrolled
signer, original Start/ticket/route/provider and current requester checks.
Finite certificate bytes and rules are unchanged.

This first source candidate remains incomplete for service operation until
human Stop. Its typed projection explicitly reports
`generic_history_checkpointing_complete:false`. Generic Broker/admission/
reciprocal/audit/evidence capacities remain, and later media-lifetime
compaction needs further work. No host may reinterpret it as unlimited
authority or bypass a finite refusal. Physical continued motion, display-feed
continuity and actual Android qualification are separate later work.

The real coupled-owner tests cover67 renewals and original retained cleanup
ancestry; the actual native CycleLedger tests cover finite32 refusal,80
continuation completions, stale/skipped/noncanonical/abandoned cycles and mode
relabel refusal. Test clocks accelerate owner time; these are not live-device
receipts. Cargo/source graph selection must pin the actual Manifold and all
ancillary owners rather than resolve an ambient workspace junction.
