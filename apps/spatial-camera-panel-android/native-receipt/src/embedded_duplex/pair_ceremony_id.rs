//! Canonical syntax only. Parsed continuation syntax grants no owner authority.
const PREFIX: &str = "ceremony.duplex.";

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(super) enum CeremonyId<'a> {
    Finite { nonce: &'a str },
    Continuation { ordinal: u64, nonce: &'a str },
}

fn nonce(value: &str) -> Result<(), String> {
    if value.len() == 64
        && value
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    {
        Ok(())
    } else {
        Err("pair ceremony nonce is not canonical lowercase hex".into())
    }
}

pub(super) fn parse(value: &str) -> Result<CeremonyId<'_>, String> {
    let tail = value
        .strip_prefix(PREFIX)
        .ok_or("pair ceremony namespace")?;
    if let Some(tail) = tail.strip_prefix("continuation.") {
        let (word, value) = tail.split_once('.').ok_or("continuation cycle shape")?;
        let ordinal = word
            .parse::<u64>()
            .map_err(|_| "continuation cycle ordinal")?;
        if ordinal == 0 || word != ordinal.to_string() {
            return Err("continuation cycle ordinal is not canonical positive decimal".into());
        }
        nonce(value)?;
        Ok(CeremonyId::Continuation {
            ordinal,
            nonce: value,
        })
    } else {
        nonce(tail)?;
        Ok(CeremonyId::Finite { nonce: tail })
    }
}

pub(super) fn finite(value: &str) -> Result<String, String> {
    nonce(value)?;
    Ok(format!("{PREFIX}{value}"))
}

pub(super) fn continuation(ordinal: u64, value: &str) -> Result<String, String> {
    if ordinal == 0 {
        return Err("continuation cycle ordinal is zero".into());
    }
    nonce(value)?;
    Ok(format!("{PREFIX}continuation.{ordinal}.{value}"))
}

pub(super) fn require_frame(value: &str, kind: &str) -> Result<(), String> {
    let parsed = parse(value)?;
    match kind {
        "hello" | "hello_ack" | "sign_a" | "signed_a" | "prepare_b" | "prepared_b" | "finish_b"
        | "finished_b" => match parsed {
            CeremonyId::Finite { .. } => Ok(()),
            CeremonyId::Continuation { .. } => {
                Err("initial ceremony cannot use continuation identity".into())
            }
        },
        // Finite renewal remains supported. Policy and next ordinal belong to CycleLedger.
        "renew_hello" | "renew_hello_ack" | "renew_sign_a" | "renew_signed_a"
        | "renew_prepare_b" | "renew_prepared_b" | "renew_finish_b" | "renew_finished_b" => Ok(()),
        _ => Err("pair frame kind invalid".into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn canonical_forms_roundtrip_without_granting_policy() {
        let n = "ab".repeat(32);
        assert_eq!(
            parse(&finite(&n).unwrap()).unwrap(),
            CeremonyId::Finite { nonce: &n }
        );
        for ordinal in [1, 80, u64::MAX] {
            let id = continuation(ordinal, &n).unwrap();
            assert_eq!(
                parse(&id).unwrap(),
                CeremonyId::Continuation { ordinal, nonce: &n }
            );
            assert!(require_frame(&id, "renew_hello").is_ok());
            assert!(require_frame(&id, "hello").is_err());
        }
    }
    #[test]
    fn aliases_and_unbounded_forms_are_refused() {
        let n = "ab".repeat(32);
        for word in [
            "0",
            "01",
            "+1",
            "-1",
            " 1",
            "1 ",
            "18446744073709551616",
            "",
        ] {
            assert!(
                parse(&format!("{PREFIX}continuation.{word}.{n}")).is_err(),
                "{word:?}"
            );
        }
        for bad in [
            n.to_uppercase(),
            "a".repeat(63),
            "a".repeat(65),
            format!("{n}.x"),
            format!(" {n}"),
            "é".repeat(32),
        ] {
            assert!(finite(&bad).is_err());
            assert!(parse(&format!("{PREFIX}{bad}")).is_err());
            assert!(parse(&format!("{PREFIX}continuation.1.{bad}")).is_err());
        }
        assert!(continuation(0, &n).is_err());
        assert!(parse(&format!("Ceremony.duplex.{n}")).is_err());
        assert!(require_frame(&finite(&n).unwrap(), "renew_unknown").is_err());
    }
}
