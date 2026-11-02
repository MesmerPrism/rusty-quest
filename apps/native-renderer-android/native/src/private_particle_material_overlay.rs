//! Scalar-only complete-profile overlay, independent of GPU/Android state.
//! Blend/template resolution is deliberately outside this bounded operation.

pub(crate) type Coefficients = [f32; 5];

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct Material {
    pub(crate) coefficients: Coefficients,
    pub(crate) premultiplied: bool,
}

pub(crate) fn resolve(
    showcase: bool,
    mut resolved_template: Material,
    explicit: Coefficients,
    selected: [bool; 5],
) -> Material {
    if showcase {
        for index in 0..5 {
            if selected[index] {
                resolved_template.coefficients[index] = explicit[index];
            }
        }
    }
    resolved_template
}

pub(crate) fn modified(actual: Material, canonical: Material) -> bool {
    actual != canonical
}

#[cfg(test)]
mod tests {
    use super::*;

    const REFERENCE: Material = Material {
        coefficients: [0.36, 0.45, 1.5, 0.0, 0.2],
        premultiplied: true,
    };
    const PACKAGED: Material = Material {
        coefficients: [0.25, 0.5, 1.0, 0.1, 0.3],
        premultiplied: false,
    };
    const EDITED: Coefficients = [0.7, 0.8, 2.0, 0.4, 0.6];

    #[test]
    fn full_poll_heartbeat_frames_next_poll_consume_one_explicit_profile() {
        let full_poll = resolve(true, REFERENCE, EDITED, [true; 5]);
        assert_eq!(full_poll.coefficients, EDITED);
        let mut frame = full_poll;
        for _ in 0..120 {
            frame = resolve(true, frame, EDITED, [true; 5]);
            assert_eq!(frame, full_poll);
        }
        assert_eq!(resolve(true, REFERENCE, EDITED, [true; 5]), frame);
        assert!(modified(frame, REFERENCE));
    }

    #[test]
    fn preset_some_none_switches_and_manual_edits_retain_selected_coefficients() {
        for template in [REFERENCE, PACKAGED, REFERENCE] {
            let same = resolve(true, template, template.coefficients, [true; 5]);
            assert_eq!(same, template);
            assert!(!modified(same, template));
            assert_eq!(
                resolve(true, template, EDITED, [true; 5]).coefficients,
                EDITED
            );
        }
        assert_eq!(resolve(true, REFERENCE, EDITED, [false; 5]), REFERENCE);
    }

    #[test]
    fn masks_are_independent_and_unselected_fields_keep_template_values() {
        for slot in 0..5 {
            let mut mask = [false; 5];
            mask[slot] = true;
            let actual = resolve(true, REFERENCE, EDITED, mask);
            for component in 0..5 {
                assert_eq!(
                    actual.coefficients[component],
                    if component == slot {
                        EDITED[component]
                    } else {
                        REFERENCE.coefficients[component]
                    }
                );
            }
        }
    }

    #[test]
    fn non_showcase_preserves_closed_template_priority() {
        assert_eq!(resolve(false, REFERENCE, EDITED, [true; 5]), REFERENCE);
        assert_eq!(resolve(false, PACKAGED, EDITED, [true; 5]), PACKAGED);
    }

    #[test]
    fn coefficient_overlay_preserves_actual_resolved_blend() {
        for template in [REFERENCE, PACKAGED] {
            let mut material = template;
            for _ in 0..4 {
                material = resolve(true, material, EDITED, [true; 5]);
            }
            assert_eq!(material.coefficients, EDITED);
            assert_eq!(material.premultiplied, template.premultiplied);
        }
        assert!(modified(
            Material {
                premultiplied: false,
                ..REFERENCE
            },
            REFERENCE
        ));
    }
}
