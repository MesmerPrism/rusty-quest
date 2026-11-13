//! Build-only external payload validation; no effect implementation is public.
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::{
    env, fs,
    path::{Component, Path, PathBuf},
};

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Bundle {
    schema: String,
    abi: String,
    atlas: [u32; 2],
    eye: [u32; 2],
    fragments: [Fragment; 3],
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Fragment {
    path: PathBuf,
    bytes: u64,
    sha256: String,
}

fn digest(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}
fn checked_hash(expected: &str, bytes: &[u8]) -> Result<(), String> {
    if expected.len() != 64
        || !expected
            .bytes()
            .all(|v| v.is_ascii_digit() || (b'a'..=b'f').contains(&v))
        || digest(bytes) != expected
    {
        return Err("FullCore3 SHA256 mismatch or noncanonical digest".into());
    }
    Ok(())
}

pub fn validate(path: &Path, expected: &str) -> Result<[Vec<u8>; 3], String> {
    let root = path
        .parent()
        .ok_or("Bundle parent missing")?
        .canonicalize()
        .map_err(|e| e.to_string())?;
    let data = fs::read(path).map_err(|e| e.to_string())?;
    if data.len() > 16384 {
        return Err("FullCore3 bundle exceeds 16KiB".into());
    }
    checked_hash(expected, &data)?;
    let b: Bundle = serde_json::from_slice(&data).map_err(|e| e.to_string())?;
    if b.schema != "private.meta_input.full3_bundle.v1"
        || b.abi != "presentation128-v1"
        || b.atlas != [3072, 1664]
        || b.eye != [1280, 1280]
    {
        return Err("Unsupported FullCore3 bundle/ABI/extent".into());
    }
    let mut result: [Vec<u8>; 3] = std::array::from_fn(|_| Vec::new());
    let mut seen = std::collections::HashSet::new();
    for (i, f) in b.fragments.iter().enumerate() {
        if f.path.as_os_str().is_empty()
            || f.path
                .components()
                .any(|c| !matches!(c, Component::Normal(_)))
        {
            return Err("FullCore3 shader path must be a relative descendant".into());
        }
        let p = root
            .join(&f.path)
            .canonicalize()
            .map_err(|e| e.to_string())?;
        if !p.starts_with(&root) || !seen.insert(p.clone()) {
            return Err("FullCore3 shader escaped root or duplicated".into());
        }
        let bytes = fs::read(&p).map_err(|e| e.to_string())?;
        if bytes.is_empty() || bytes.len() > 1024 * 1024 || bytes.len() as u64 != f.bytes {
            return Err("FullCore3 shader byte count mismatch".into());
        }
        checked_hash(&f.sha256, &bytes)?;
        println!("cargo:rerun-if-changed={}", p.display());
        result[i] = bytes;
    }
    Ok(result)
}

pub fn prepare(out: &Path) -> Option<[PathBuf; 3]> {
    const PREFIX: &str = "RUSTY_QUEST_NATIVE_RENDERER_META_INPUT_FULL3";
    for suffix in ["_DIAGNOSTIC", "_BUNDLE", "_BUNDLE_SHA256"] {
        println!("cargo:rerun-if-env-changed={PREFIX}{suffix}");
    }
    let enabled = env::var(format!("{PREFIX}_DIAGNOSTIC")).ok();
    let path = env::var_os(format!("{PREFIX}_BUNDLE"));
    let hash = env::var(format!("{PREFIX}_BUNDLE_SHA256")).ok();
    if enabled.is_none() && path.is_none() && hash.is_none() {
        println!("cargo:rustc-env=RUSTY_QUEST_NATIVE_RENDERER_META_INPUT_FULL3_VERIFIED=false");
        for i in 0..3 {
            fs::write(out.join(format!("full3_{i}.frag.spv")), [])
                .expect("write absent bundle placeholder");
        }
        return None;
    }
    assert_eq!(
        enabled.as_deref(),
        Some("true"),
        "FullCore3 requires exact opt-in"
    );
    assert_eq!(
        env::var("RUSTY_QUEST_NATIVE_RENDERER_PRESENTATION_DIAGNOSTIC").as_deref(),
        Ok("true"),
        "FullCore3 requires presentation diagnostic"
    );
    assert_eq!(
        env::var("RUSTY_QUEST_NATIVE_RENDERER_META_INPUT_DIAGNOSTIC").as_deref(),
        Ok("true"),
        "FullCore3 requires Meta input diagnostic"
    );
    let path = PathBuf::from(path.expect("FullCore3 bundle missing"));
    println!("cargo:rerun-if-changed={}", path.display());
    let sources = validate(&path, &hash.expect("FullCore3 bundle SHA256 missing"))
        .expect("FullCore3 bundle rejected");
    // Compile the exact verified bytes, not a mutable external path re-opened later.
    let paths = std::array::from_fn(|i| {
        let p = out.join(format!("full3_{i}.frag.glsl"));
        fs::write(&p, &sources[i]).expect("write verified FullCore3 source");
        p
    });
    println!("cargo:rustc-env=RUSTY_QUEST_NATIVE_RENDERER_META_INPUT_FULL3_VERIFIED=true");
    Some(paths)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};
    static NEXT: AtomicU64 = AtomicU64::new(0);
    struct Fixture {
        root: PathBuf,
        value: serde_json::Value,
    }
    impl Fixture {
        fn new() -> Self {
            let root = env::temp_dir().join(format!(
                "full3-bundle-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            fs::create_dir(&root).unwrap();
            let fragments: Vec<_> = (0..3).map(|i| {
                let text = format!("#version 450\n// stage {i}\n"); let name = format!("stage{i}.glsl");
                fs::write(root.join(&name), &text).unwrap();
                serde_json::json!({"path":name,"bytes":text.len(),"sha256":digest(text.as_bytes())})
            }).collect();
            Self {
                root,
                value: serde_json::json!({"schema":"private.meta_input.full3_bundle.v1","abi":"presentation128-v1","atlas":[3072,1664],"eye":[1280,1280],"fragments":fragments}),
            }
        }
        fn run(&self) -> Result<[Vec<u8>; 3], String> {
            let data = serde_json::to_vec(&self.value).unwrap();
            let p = self.root.join("bundle.json");
            fs::write(&p, &data).unwrap();
            validate(&p, &digest(&data))
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.root);
        }
    }
    #[test]
    fn exact_sources_are_returned_without_reopening_for_compile() {
        let f = Fixture::new();
        let sources = f.run().unwrap();
        fs::write(f.root.join("stage0.glsl"), "changed after validation").unwrap();
        assert!(
            std::str::from_utf8(&sources[0])
                .unwrap()
                .contains("stage 0")
        );
        assert!(f.run().is_err());
    }
    #[test]
    fn absent_bundle_never_becomes_a_payload() {
        assert!(validate(Path::new("definitely-absent-full3.json"), &"0".repeat(64)).is_err());
    }
    #[test]
    fn rejects_shape_and_extra_fields() {
        let mut f = Fixture::new();
        f.value["atlas"] = serde_json::json!([3072, 1665]);
        assert!(f.run().is_err());
        f.value["atlas"] = serde_json::json!([3072, 1664]);
        f.value["fallback"] = true.into();
        assert!(f.run().is_err());
    }
    #[test]
    fn rejects_wrong_pins_and_duplicate_sources() {
        let mut f = Fixture::new();
        f.value["fragments"][0]["sha256"] = "0".repeat(64).into();
        assert!(f.run().is_err());
        let mut f = Fixture::new();
        f.value["fragments"][1] = f.value["fragments"][0].clone();
        assert!(f.run().is_err());
    }
    #[test]
    fn rejects_traversal_and_missing_stage() {
        let mut f = Fixture::new();
        f.value["fragments"][0]["path"] = "../outside.glsl".into();
        assert!(f.run().is_err());
        let mut f = Fixture::new();
        f.value["fragments"].as_array_mut().unwrap().pop();
        assert!(f.run().is_err());
    }
    #[test]
    fn rejects_duplicate_json_authority_fields() {
        let f = Fixture::new();
        let json = serde_json::to_string(&f.value).unwrap();
        let bad = format!("{{\"abi\":\"presentation128-v1\",{}", &json[1..]);
        let p = f.root.join("bundle.json");
        fs::write(&p, &bad).unwrap();
        assert!(validate(&p, &digest(bad.as_bytes())).is_err());
    }
}
