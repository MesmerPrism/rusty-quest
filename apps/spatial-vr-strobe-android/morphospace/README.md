# Spatial VR Strobe workspace

This protocol-v2 workspace is the sole authority for new Spatial VR Strobe
units. The legacy Spatial Camera Panel workspace retains MOD-010 through
MOD-012 as immutable provenance, but it must not be used to authorize new
Strobe work after the project-isolation correction.

The initial lock is inert because the current Strobe source contains
uncommitted work. Resolve a hash-bound descriptor after a coherent source
checkpoint; do not replace the inert lock by hand.
