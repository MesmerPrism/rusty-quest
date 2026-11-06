//! Closed bridge vocabulary shared by Android dispatch and real host controls.
//! This validates only the purpose/prior shape. It grants no phase or actor.
pub(super) fn validate(purpose: &str, has_prior: bool) -> Result<(), &'static str> {
    match purpose {
        "initial" | "initial_preparation" | "current_phase" if !has_prior => Ok(()),
        "linked_fence" if has_prior => Ok(()),
        _ => Err("incoming fresh phase binding purpose differs"),
    }
}

#[cfg(test)]
mod tests {
    use super::validate;
    #[test]
    fn actual_four_purpose_shapes_are_accepted() {
        for word in ["initial", "initial_preparation", "current_phase"] {
            assert_eq!(validate(word, false), Ok(()));
        }
        assert_eq!(validate("linked_fence", true), Ok(()));
    }
    #[test]
    fn first_preparation_is_not_a_linked_prior() {
        assert_eq!(validate("initial_preparation", false), Ok(()));
        assert!(validate("initial_preparation", true).is_err());
    }
    #[test]
    fn prior_presence_remains_closed_for_every_purpose() {
        for word in ["initial", "initial_preparation", "current_phase"] {
            assert!(validate(word, true).is_err());
        }
        assert!(validate("linked_fence", false).is_err());
    }
    #[test]
    fn unknown_case_and_suffix_variants_never_gain_dispatch() {
        for word in [
            "",
            "Initial",
            "initial_preparation ",
            "initial-preparation",
            "fence",
            "linked_fence_extra",
        ] {
            assert!(validate(word, false).is_err());
            assert!(validate(word, true).is_err());
        }
    }
}
