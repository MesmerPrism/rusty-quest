# Verified saved-recording retrieval

`ui.recording_export` is an explicit feature depending on the command gateway.
It selects Android metadata `rusty.quest.feature.recording_export=true` and native
build input `RUSTY_QUEST_NATIVE_RECORDING_EXPORT=true`. Gateway selection alone
adds no recording capability. Unselected native runtimes create no export worker.
Authenticated full Wi-Fi status advertises `recordings: 1` only when selected;
old APKs omit it. BLE discovery/control and relay never admit this action.

Only `exchangeLan` admits the signed `recordings` action. The ordinary protocol
entry remains closed to it. Existing RQG1 authentication, exact request bytes,
nonce replay protection, enrolled/guest expiry and native-owner lifetimes apply.
The complete signed reply must be strictly smaller than 65536 UTF-8 bytes.

Closed payloads:

- `{query:"list",before:"",limit:16}`: newest first, at most 16 rows. Use
  `next_before` for the next page. Numbers are integer JSON types, not strings
  or floating values; unknown fields are rejected.
- `{query:"describe",id:"utc-ns-00000000000000000000"}`: validate and hash one
  saved recording. Both list and describe initially return `state:"preparing"`;
  poll the same query for `ready`, `busy`, or `unavailable`. A single independent
  native worker and one retained result bound resource use; polls create no
  additional jobs. Another query cannot replace an executing preparation job.
- `{query:"read",manifest_id:"<64 lowercase hex>",file:"events.jsonl",offset:0,
  length:32768}`: read 1..32768 raw bytes within the pinned file. The reply binds
  manifest ID, file, offset, length and SHA-256 and supplies ordinary base64.

The native query seam bypasses the experiment mutation queue and live writer.
Main-thread admission captures a fresh complete native idle Remote observation
and controller/native/gateway/mode/session scope; IO occurs on the LAN caller
or independent native preparation worker. Main revalidation occurs before
signing. Native checks current owner, epoch, packed mode revision, idle session,
initialization/recovery/root readiness and generation around IO. Retirement
invalidates preparation publication and cached results. The internal controller
ticket enters native as a SHA-256 fingerprint; credentials are excluded.

Eligibility uses the shared recovery manifest/terminal/row validators through a
strict final-only `RecordingFiles` reader seam. The export source exposes only
the eight pinned file handles and their checked metadata; it has no directory
path or checkpoint-reader capability. Invalid or missing final metadata stops
immediately. Ordinary interrupted recovery retains its existing path-based
prefix validation and checkpoint fallback policy.

Android and Linux anchor the app-private files parent, recording root and
session directories, then open each component relative to retained descriptors
with O_NOFOLLOW (and O_DIRECTORY for directories). Regular opens are nonblocking
so a FIFO replacement cannot stall before type rejection. No read/enumeration
permission on system ancestors is added; trusted platform aliases stay allowed.
Windows host custody retains directory handles without delete sharing and opens
final entries as reparse points, rejecting every reparse type before reading.
Chunk bytes use positional reads on the original pinned handles. Validation,
hashing and delivery therefore share custody even if a path is substituted.
Owner checks interrupt metadata/stream reads at most every 32 KiB; original and
export caps remain unchanged.

Export takes a cancellation-aware filename/type inventory from the retained
session directory, capped at 65536 total entries, including ignored checkpoint
and index filenames. The same regular-file/UTF-8 `.jsonl` predicate is used by
ordinary recovery and export. An unexpected stream makes export unavailable;
only the original pinned core8 handles supply metadata/stream body reads.

Directory stamps and a one-shot kernel namespace witness fence the inventory
and every later use of a prepared offer. Registration occurs before core pinning
and filename enumeration. Windows uses a change-notification HANDLE polled with
zero wait; Android/Linux use one nonblocking inotify descriptor on the retained
directory's kernel fd alias. Any namespace/type signal, overflow/ignored event,
EOF or watcher failure is unavailable, and invalidation stays sticky. There is
no watcher thread, rearm, overlapped user buffer, destructor wait or checkpoint
body read. Failed registration has no timestamp-only fallback. Handles close
through normal bounded ownership cleanup. App-private directory access is used;
no system-ancestor enumeration or Android permission expansion is introduced.

An eligible recording has recovery error=None, disposition finalized-completed
or finalized-early, final.saved=true, and actual lengths exactly equal to every
validated durable stream length. An interrupted, corrupt, unsaved, lossy, active,
linked, replaced, mutated or oversized entry is unavailable. Listings label
unvalidated entries `not-finalized` or `not-validated-saved`, never completed.

Exactly eight original files are exported: manifest.json, final.json,
events.jsonl, polar-acc.jsonl, polar-ecg.jsonl, polar-hr-rr.jsonl, breath.jsonl,
and radius.jsonl. Checkpoints and the device index are excluded. All six
physiological/event streams are included. No recording file is created,
modified or removed. The native limit is 128 MiB total original bytes per
recording; metadata files are at most 1 MiB each. Regular file handles, link
counts, filesystem identity/change stamps, root/directory identity and pre/post
checks bind validation/hashing and every chunk. Native errors disclose no paths.
The manifest includes original experiment identity (null for legacy recordings
that lack it), condition, disposition, sizes, SHA-256 and core8 policy. The
adapter adds the admitted device ID and actual serial (empty when unavailable)
inside the signed manifest response. Manifest ID binds the native core manifest;
device/serial are authenticated transport metadata added afterwards.

The browser verifies all lengths and SHA-256 before offering a ZIP containing
those eight original files plus export-manifest.json. A ZIP offer is not proof
of durable storage on the phone. Connection recovery never retries an export.
Host tests qualify parsing, files, lifetimes and ZIP bytes; actual Quest LAN,
phone download/storage and larger recordings need attended validation.
