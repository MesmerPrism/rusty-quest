//! Downstream-material absence scan for the pure contract and fixtures.

use std::{fs, path::Path};

fn forbidden_terms() -> Vec<String> {
    [
        ["viscere", "ality"],
        ["rusty-gpu-viscere", "ality"],
        ["kura", "moto"],
        ["icos", "phere"],
        ["sph", "ere"],
        ["rad", "ius"],
        ["or", "bit"],
        ["sha", "der"],
        ["partici", "pant"],
        ["question", "naire"],
        ["stu", "dy"],
        ["s:", "\\work"],
        ["c:", "\\users"],
    ]
    .into_iter()
    .map(|parts| parts.concat())
    .collect()
}

fn collect_files(root: &Path, files: &mut Vec<std::path::PathBuf>) {
    for entry in fs::read_dir(root).expect("scan directory") {
        let path = entry.expect("scan entry").path();
        if path.is_dir() {
            collect_files(&path, files);
        } else {
            files.push(path);
        }
    }
}

#[test]
fn contract_and_fixtures_contain_no_downstream_material() {
    let manifest_root = Path::new(env!("CARGO_MANIFEST_DIR"));
    let repository_root = manifest_root
        .parent()
        .and_then(Path::parent)
        .expect("repository root");
    let roots = [
        manifest_root.to_path_buf(),
        repository_root.join("fixtures/breath-contract"),
    ];
    let mut files = Vec::new();
    for root in roots {
        collect_files(&root, &mut files);
    }
    files.sort();
    let forbidden_terms = forbidden_terms();
    for path in files {
        let text = fs::read_to_string(&path).expect("public text file");
        let normalized = text.to_lowercase();
        for forbidden in &forbidden_terms {
            assert!(
                !normalized.contains(forbidden),
                "forbidden downstream term '{forbidden}' in {}",
                path.display()
            );
        }
    }
}
