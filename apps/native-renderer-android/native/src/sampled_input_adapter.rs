//! Descriptor contract at the imported-image / ordinary effect-graph boundary.
//! AHB descriptorFormat is deliberately NOT used to infer a Vulkan image format.

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Route {
    DirectUnorm,
    ConvertedToOwnedUnorm,
}

pub(crate) fn route(
    regular_unorm: bool,
    undefined_format: bool,
    external_format: u64,
    conversion: bool,
    immutable_sampler: bool,
    linear: bool,
) -> Result<Route, &'static str> {
    if !linear {
        return Err("actual imported source sampler must be LINEAR");
    }
    if regular_unorm
        && !undefined_format
        && external_format == 0
        && !conversion
        && !immutable_sampler
    {
        return Ok(Route::DirectUnorm);
    }
    if conversion
        && immutable_sampler
        && ((external_format != 0 && undefined_format)
            || (external_format == 0 && !undefined_format))
    {
        return Ok(Route::ConvertedToOwnedUnorm);
    }
    Err("unsupported or inconsistent imported sampler/conversion contract")
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn synthetic_external_format_is_converted_not_relabelled_rgba() {
        assert_eq!(
            route(false, true, 12345, true, true, true),
            Ok(Route::ConvertedToOwnedUnorm)
        );
        assert_eq!(
            route(true, false, 0, false, false, true),
            Ok(Route::DirectUnorm)
        );
        // A named YUV format with a conversion has the same descriptor obligation.
        assert_eq!(
            route(false, false, 0, true, true, true),
            Ok(Route::ConvertedToOwnedUnorm)
        );
    }
    #[test]
    fn incompatible_layout_and_nearest_cannot_enter_effect_graph() {
        for (undefined, external, conversion, immutable, linear) in [
            (true, 12345, false, true, true),
            (true, 12345, true, false, true),
            (true, 0, true, true, true),
            (false, 12345, true, true, true),
            (true, 12345, true, true, false),
        ] {
            assert!(route(false, undefined, external, conversion, immutable, linear).is_err());
        }
    }
}
