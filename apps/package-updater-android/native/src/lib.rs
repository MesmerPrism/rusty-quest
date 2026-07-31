//! Small JNI bridge for verifying build-fixed Ed25519 release signatures.

use ed25519_dalek::{Signature, VerifyingKey};

/// Verifies one exact Ed25519 signature from raw RFC 8032 key material.
#[must_use]
pub fn verify_ed25519(public_key: &[u8], message: &[u8], signature: &[u8]) -> bool {
    let Ok(public_key): Result<[u8; 32], _> = public_key.try_into() else {
        return false;
    };
    let Ok(signature): Result<[u8; 64], _> = signature.try_into() else {
        return false;
    };
    let Ok(verifying_key) = VerifyingKey::from_bytes(&public_key) else {
        return false;
    };
    verifying_key
        .verify_strict(message, &Signature::from_bytes(&signature))
        .is_ok()
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_packageupdater_NativeEd25519Verifier_nativeVerify(
    mut env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    public_key: jni::objects::JByteArray,
    message: jni::objects::JByteArray,
    signature: jni::objects::JByteArray,
) -> jni::sys::jboolean {
    match env
        .with_env(|env| -> jni::errors::Result<jni::sys::jboolean> {
            let public_key = env.convert_byte_array(public_key)?;
            let message = env.convert_byte_array(message)?;
            let signature = env.convert_byte_array(signature)?;
            Ok(if verify_ed25519(&public_key, &message, &signature) {
                jni::sys::JNI_TRUE
            } else {
                jni::sys::JNI_FALSE
            })
        })
        .into_outcome()
    {
        jni::Outcome::Ok(value) => value,
        jni::Outcome::Err(_) | jni::Outcome::Panic(_) => jni::sys::JNI_FALSE,
    }
}

#[cfg(test)]
mod tests {
    use ed25519_dalek::{Signer, SigningKey};

    use super::verify_ed25519;

    #[test]
    fn accepts_exact_signature_and_rejects_damage() {
        let signing_key = SigningKey::from_bytes(&[7_u8; 32]);
        let message = b"rusty.quest.package_update_manifest.v1\0{}";
        let signature = signing_key.sign(message).to_bytes();
        assert!(verify_ed25519(
            signing_key.verifying_key().as_bytes(),
            message,
            &signature
        ));

        let mut damaged = signature;
        damaged[0] ^= 0x01;
        assert!(!verify_ed25519(
            signing_key.verifying_key().as_bytes(),
            message,
            &damaged
        ));
        assert!(!verify_ed25519(&[0_u8; 31], message, &signature));
        assert!(!verify_ed25519(
            signing_key.verifying_key().as_bytes(),
            message,
            &[0_u8; 63]
        ));
    }
}
