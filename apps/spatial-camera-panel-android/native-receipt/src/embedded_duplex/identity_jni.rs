//! Narrow Ed25519 primitive for the app-private durable identity.

use ed25519_dalek::{Signer, SigningKey};

const MAX_AUTHORITY_BYTES: usize = 131_142;
const SIGNING_DOMAINS: [&[u8]; 5] = [
    b"rusty.quest.android.media.owner_dispatch_envelope.v1\0request\0",
    b"rusty.quest.android.media.owner_dispatch_envelope.v1\0terminal_response\0",
    b"rusty.quest.android.media.owner_dispatch_envelope.v1\0product_activation\0",
    b"rusty.quest.android.media.owner_dispatch_envelope.v1\0product_activation_ack\0",
    b"rusty.manifold.peer.common_lan_reciprocal_ed25519_context.v1\0",
];

fn public_from_seed(seed: &[u8; 32]) -> [u8; 32] {
    SigningKey::from_bytes(seed).verifying_key().to_bytes()
}

fn sign_checked(seed: &[u8; 32], bytes: &[u8]) -> Option<[u8; 64]> {
    if bytes.len() > MAX_AUTHORITY_BYTES
        || !SIGNING_DOMAINS
            .iter()
            .any(|domain| bytes.len() > domain.len() && bytes.starts_with(domain))
    {
        return None;
    }
    Some(SigningKey::from_bytes(seed).sign(bytes).to_bytes())
}

#[cfg(target_os = "android")]
mod android {
    use super::{public_from_seed, sign_checked};
    use jni::objects::{JByteArray, JClass};
    use jni::sys::jbyteArray;
    use jni::JNIEnv;
    use zeroize::Zeroizing;

    #[no_mangle]
    pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_ed25519PublicFromSeed(
        env: JNIEnv<'_>,
        _class: JClass<'_>,
        seed: JByteArray<'_>,
    ) -> jbyteArray {
        (|| {
            let bytes = Zeroizing::new(env.convert_byte_array(&seed).ok()?);
            let seed: &[u8; 32] = bytes.as_slice().try_into().ok()?;
            let public = public_from_seed(seed);
            Some(env.byte_array_from_slice(&public).ok()?.into_raw())
        })()
        .unwrap_or(std::ptr::null_mut())
    }

    #[no_mangle]
    pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_embedded_1duplex_EmbeddedDuplexNative_ed25519SignAuthorityBytes(
        env: JNIEnv<'_>,
        _class: JClass<'_>,
        seed: JByteArray<'_>,
        signing_bytes: JByteArray<'_>,
    ) -> jbyteArray {
        (|| {
            let bytes = Zeroizing::new(env.convert_byte_array(&seed).ok()?);
            let seed: &[u8; 32] = bytes.as_slice().try_into().ok()?;
            let input = env.convert_byte_array(&signing_bytes).ok()?;
            let signature = sign_checked(seed, &input)?;
            Some(env.byte_array_from_slice(&signature).ok()?.into_raw())
        })()
        .unwrap_or(std::ptr::null_mut())
    }
}

#[cfg(test)]
mod tests {
    use super::{public_from_seed, sign_checked, Signer, SigningKey};
    use ed25519_dalek::{Signature, Verifier, VerifyingKey};

    #[test]
    fn rfc8032_first_vector_and_closed_signing_domain() {
        let seed = [
            0x9d, 0x61, 0xb1, 0x9d, 0xef, 0xfd, 0x5a, 0x60, 0xba, 0x84, 0x4a, 0xf4, 0x92, 0xec,
            0x2c, 0xc4, 0x44, 0x49, 0xc5, 0x69, 0x7b, 0x32, 0x69, 0x19, 0x70, 0x3b, 0xac, 0x03,
            0x1c, 0xae, 0x7f, 0x60,
        ];
        let public = [
            0xd7, 0x5a, 0x98, 0x01, 0x82, 0xb1, 0x0a, 0xb7, 0xd5, 0x4b, 0xfe, 0xd3, 0xc9, 0x64,
            0x07, 0x3a, 0x0e, 0xe1, 0x72, 0xf3, 0xda, 0xa6, 0x23, 0x25, 0xaf, 0x02, 0x1a, 0x68,
            0xf7, 0x07, 0x51, 0x1a,
        ];
        assert_eq!(public_from_seed(&seed), public);
        assert_eq!(
            SigningKey::from_bytes(&seed).sign(b"").to_bytes(),
            [
                0xe5, 0x56, 0x43, 0x00, 0xc3, 0x60, 0xac, 0x72, 0x90, 0x86, 0xe2, 0xcc, 0x80, 0x6e,
                0x82, 0x8a, 0x84, 0x87, 0x7f, 0x1e, 0xb8, 0xe5, 0xd9, 0x74, 0xd8, 0x73, 0xe0, 0x65,
                0x22, 0x49, 0x01, 0x55, 0x5f, 0xb8, 0x82, 0x15, 0x90, 0xa3, 0x3b, 0xac, 0xc6, 0x1e,
                0x39, 0x70, 0x1c, 0xf9, 0xb4, 0x6b, 0xd2, 0x5b, 0xf5, 0xf0, 0x59, 0x5b, 0xbe, 0x24,
                0x65, 0x51, 0x41, 0x43, 0x8e, 0x7a, 0x10, 0x0b,
            ]
        );
        assert!(sign_checked(&seed, b"arbitrary\0bytes").is_none());
        let request = b"rusty.quest.android.media.owner_dispatch_envelope.v1\0request\0x";
        let signature = sign_checked(&seed, request).expect("exact authority domain");
        VerifyingKey::from_bytes(&public)
            .unwrap()
            .verify(request, &Signature::from_bytes(&signature))
            .unwrap();
        assert!(sign_checked(
            &seed,
            b"rusty.manifold.peer.common_lan_reciprocal_ed25519_context.v1\0x"
        )
        .is_some());
        assert!(sign_checked(
            &seed,
            b"rusty.manifold.peer.common_lan_reciprocal_ed25519_context.v1\0"
        )
        .is_none());
    }
}
