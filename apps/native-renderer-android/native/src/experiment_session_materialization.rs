//! Selected immutable packaged metadata; recording/session history stays in its original root.
use std::{fs, io::Read, path::{Component, Path, PathBuf}};

pub(crate) fn selected_directory(files_root: &Path, profile_sha256: &str) -> Result<PathBuf, String> {
    if !files_root.is_absolute()
        || files_root.components().any(|p| matches!(p, Component::ParentDir | Component::CurDir))
        || profile_sha256.len() != 64
        || !profile_sha256.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    {
        return Err("experiment-session-materialized-path-invalid".into());
    }
    let selected = files_root.join("viscereality-recordings").join("packaged-closures").join(profile_sha256);
    require_directory(files_root)?;
    let mut current = files_root.to_owned();
    for component in selected.strip_prefix(files_root).map_err(|_| "materialized-path-escape")?.components() {
        current.push(component);
        require_directory(&current)?;
    }
    Ok(selected)
}

fn require_directory(path: &Path) -> Result<(), String> {
    let metadata = fs::symlink_metadata(path).map_err(|e| format!("materialized-directory:{e}"))?;
    if !metadata.file_type().is_dir() { return Err("experiment-session-materialized-path-invalid".into()); }
    Ok(())
}

pub(crate) fn read_file(directory: &Path, relative: &Path) -> Result<Vec<u8>, String> {
    if relative.is_absolute() || relative.components().any(|p| !matches!(p, Component::Normal(_))) {
        return Err("experiment-session-materialized-path-invalid".into());
    }
    require_directory(directory)?;
    let path = directory.join(relative);
    let mut parent = directory.to_owned();
    if let Some(parts) = relative.parent() {
        for part in parts.components() { parent.push(part); require_directory(&parent)?; }
    }
    let metadata = fs::symlink_metadata(&path).map_err(|e| format!("materialized-file:{e}"))?;
    if !metadata.file_type().is_file() { return Err("experiment-session-materialized-path-invalid".into()); }
    let mut options = fs::OpenOptions::new(); options.read(true);
    #[cfg(any(target_os = "android", target_os = "linux"))]
    { use std::os::unix::fs::OpenOptionsExt; options.custom_flags(0x20000); } // O_NOFOLLOW
    #[cfg(windows)]
    { use std::os::windows::fs::OpenOptionsExt; options.custom_flags(0x00200000); } // FILE_FLAG_OPEN_REPARSE_POINT
    let mut file = options.open(&path).map_err(|e| format!("materialized-file-open:{e}"))?;
    if !file.metadata().map_err(|e| e.to_string())?.file_type().is_file() {
        return Err("experiment-session-materialized-path-invalid".into());
    }
    let mut bytes = Vec::new(); file.read_to_end(&mut bytes).map_err(|e| format!("materialized-file-read:{e}"))?;
    // Recheck after the read as well; the caller still verifies the compiled exact digest.
    require_directory(directory)?;
    if !fs::symlink_metadata(&path).map_err(|e| e.to_string())?.file_type().is_file() {
        return Err("experiment-session-materialized-path-invalid".into());
    }
    Ok(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};
    fn root(label: &str) -> PathBuf {
        let p=std::env::temp_dir().join(format!("rq-materialized-{label}-{}-{}", std::process::id(), SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos()));
        fs::create_dir(&p).unwrap(); p
    }
    #[test]
    fn retained_legacy_never_substitutes_for_missing_selected_closure() {
        let r=root("missing"); let legacy=r.join("viscereality-recordings");fs::create_dir(&legacy).unwrap();
        fs::write(legacy.join("experiment-session-profile.json"), b"old").unwrap();
        assert!(selected_directory(&r,&"a".repeat(64)).is_err());
        assert_eq!(fs::read(legacy.join("experiment-session-profile.json")).unwrap(),b"old");fs::remove_dir_all(r).unwrap();
    }
    #[test]
    fn selected_profile_and_guidance_share_exact_scope_preserving_history() {
        let r=root("selected");let selected=r.join("viscereality-recordings").join("packaged-closures").join("a".repeat(64));
        fs::create_dir_all(selected.join("breath-guidance")).unwrap();
        let history=r.join("viscereality-recordings/20261011T000000000Z");fs::create_dir(&history).unwrap();fs::write(history.join("events.jsonl"),b"history").unwrap();
        fs::write(selected.join("experiment-session-profile.json"),b"new").unwrap();fs::write(selected.join("breath-guidance/condition-a.json"),b"guide").unwrap();
        let d=selected_directory(&r,&"a".repeat(64)).unwrap();
        assert_eq!(read_file(&d,Path::new("experiment-session-profile.json")).unwrap(),b"new");
        assert_eq!(read_file(&d,Path::new("breath-guidance/condition-a.json")).unwrap(),b"guide");
        assert!(read_file(&d,Path::new("breath-guidance/condition-b.json")).is_err());
        assert_eq!(fs::read(history.join("events.jsonl")).unwrap(),b"history");fs::remove_dir_all(r).unwrap();
    }
    #[test]
    fn invalid_scope_and_escape_are_denied() {
        let r=root("invalid");for h in ["../x".into(),"A".repeat(64),"g".repeat(64),"a".repeat(63)] { assert!(selected_directory(&r,&h).is_err()); }
        assert!(selected_directory(Path::new("relative"),&"a".repeat(64)).is_err());
        assert!(read_file(&r,Path::new("../outside")).is_err());fs::remove_dir_all(r).unwrap();
    }
    #[test]
    fn linked_selected_directory_is_denied() {
        let r=root("links");let parent=r.join("viscereality-recordings").join("packaged-closures");fs::create_dir_all(&parent).unwrap();
        let other=r.join("other-owner-directory");fs::create_dir(&other).unwrap();let link=parent.join("a".repeat(64));
        #[cfg(windows)]
        { let result=std::process::Command::new("cmd.exe").args(["/c","mklink","/J"]).arg(&link).arg(&other).output().unwrap(); assert!(result.status.success(), "junction create: {}",String::from_utf8_lossy(&result.stdout)); }
        #[cfg(unix)]
        std::os::unix::fs::symlink(&other,&link).unwrap();
        assert!(selected_directory(&r,&"a".repeat(64)).is_err());
        fs::remove_dir_all(r).unwrap();
    }
}
