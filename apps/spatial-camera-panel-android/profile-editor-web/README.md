# VR Strobe Profile Editor

This is the flat browser authoring surface for the Rusty Quest Spatial SDK VR
Strobe app. It keeps Trevor's original two-column stimulus portal, complete
nine-profile list, full-canvas interference designer, and compact collapsible
temporal-strobe controls familiar while creating the same versioned JSON bundle
the Quest app imports and exports. The browser preview intentionally omits the
headset's curved carrier and optics; attended Quest validation remains the
visual authority.

The default browser set contains these source profiles, in portal order:

1. Simulated 7 Hz Closed Eye Lucia
2. Simulated 40 Hz Open Eye Roxiva
3. Simulated red
4. Simulated blorbs
5. Simulated shlorgs
6. Real 7 Hz Black & White Strobe
7. Real 14 Hz Noistrobe
8. Real 20 Hz Black & White Strobe
9. Real 12 Hz Red Strobe

The two original design-page cards create new Quest profiles. Existing browser
storage is migrated by inserting any missing source profiles and removing only
the untouched one-profile starter created by the earlier editor. The
`Restore original set` action resets the nine source profiles but preserves
additional profiles.

The photosensitive-stimulus warning is a dedicated entry page. Its
acknowledgement is retained only for the current browser session, so it does
not interrupt profile exploration again during that session and does not
replace the Quest app's own warning flow.

While a designer is open, the keyboard controls are:

- Left / Right: cycle through the current profile set, including session saves.
- Space: pause or resume the preview.
- Up: randomize the active profile using the Quest-safe envelope.
- Down: restore the previous randomization for the active profile. The undo
  history is temporary, per-profile, last-in-first-out, and bounded to 128
  entries.
- S: save a snapshot to the current browser session. Session saves can be
  revisited with Left / Right and are included if `Download for Quest` is
  clicked, but they are not written into the persistent browser library.

Keyboard shortcuts are ignored while an input, select, or editable text field
has focus. Holding a key does not repeat an action; each physical press creates
one command.

Run `tools/Start-SpatialVrStrobeProfileEditor.ps1`, or serve this directory
from any local static HTTP server. Opening `index.html` directly is not
supported because the editor uses JavaScript modules.

The downloaded `rusty-vr-strobe-profiles.json` can be sent to Quest with
`tools/Invoke-SpatialVrStrobeProfileTransfer.ps1 -Action Import`. Use the same
script with `-Action Export` to pull the effective Quest list back into the
browser. Imports replace the complete stored list only after the app-owned
codec validates the bundle.

The pattern vocabulary and flat preview math are derived with explicit
permission from Trevor Hewitt's `vr_strobe`, exact reference commit
`52c71cc069f4102bc4148e05c5fd3fc4d5466479`, and are distributed here under
`AGPL-3.0-or-later`. See the parent app's `THIRD_PARTY_NOTICES.md` and
`morphospace/receipts/mod-010-vr-strobe-source-permission-20260717.json`.
