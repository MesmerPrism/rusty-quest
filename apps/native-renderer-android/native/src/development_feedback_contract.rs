//! Bounded identifiers and numeric inputs for the opt-in developer command transport.

pub(crate) fn valid_request_id(value: &str) -> bool {
    let bytes = value.as_bytes();
    !bytes.is_empty()
        && bytes.len() <= 64
        && (bytes[0].is_ascii_lowercase() || bytes[0].is_ascii_digit())
        && bytes
            .iter()
            .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || *byte == b'-')
}

pub(crate) fn canonical_i64(value: &str) -> Result<i64, &'static str> {
    let parsed = value
        .parse::<i64>()
        .map_err(|_| "canonical signed integer required")?;
    if parsed.to_string() != value {
        return Err("canonical signed integer required");
    }
    Ok(parsed)
}

pub(crate) fn canonical_generation(value: &str) -> Result<u64, &'static str> {
    let parsed = value
        .parse::<u64>()
        .map_err(|_| "positive canonical generation required")?;
    if parsed == 0 || parsed > i64::MAX as u64 || parsed.to_string() != value {
        return Err("positive canonical generation required");
    }
    Ok(parsed)
}

pub(crate) fn valid_sha256(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_only_bounded_ascii_request_ids() {
        assert!(valid_request_id("restore-a-3"));
        assert!(valid_request_id("0"));
        for value in ["", "-a", "A", "../a", "a/b", "a_b", "é", &"a".repeat(65)] {
            assert!(!valid_request_id(value), "{value}");
        }
    }

    #[test]
    fn numeric_inputs_have_one_textual_representation() {
        for value in ["0", "-1", "9223372036854775807", "-9223372036854775808"] {
            assert_eq!(canonical_i64(value).unwrap().to_string(), value);
        }
        for value in ["-0", "+1", "01", " 1", "1 ", "9223372036854775808"] {
            assert!(canonical_i64(value).is_err(), "{value}");
        }
        assert_eq!(canonical_generation("1"), Ok(1));
        for value in ["0", "-1", "+1", "01", "9223372036854775808"] {
            assert!(canonical_generation(value).is_err(), "{value}");
        }
    }

    #[test]
    fn artifact_hash_is_exact_lowercase_sha256() {
        assert!(valid_sha256(&"a0123456789bcdef".repeat(4)));
        for value in [
            "a".repeat(63),
            "a".repeat(65),
            "A".repeat(64),
            "g".repeat(64),
        ] {
            assert!(!valid_sha256(&value));
        }
    }
}
