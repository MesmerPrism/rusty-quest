use rusty_quest_development_feedback_native::{FeedbackHost, Snapshot, ATTEMPT_LIMIT, IMAGE_LIMIT};
use std::{collections::BTreeMap, env, fs, path::PathBuf, time::{SystemTime, UNIX_EPOCH}};

struct Image { generation: u64, sha256: String, path: PathBuf }
fn images() -> BTreeMap<String, Image> {
    let manifest = env::var("FEEDBACK_NATIVE_MANIFEST").expect("Select the actual prepared host image manifest");
    let root = PathBuf::from(env::var("FEEDBACK_TEST_OUTPUT").expect("Select retained test output"))
        .join(format!("{}-{}", std::process::id(), SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos()));
    fs::create_dir_all(&root).unwrap();
    fs::read_to_string(manifest).unwrap().lines().map(|line| {
        let row: Vec<_> = line.splitn(4, '\t').collect();
        assert_eq!(row.len(), 4);
        let source = PathBuf::from(row[3]);
        let path = root.join(format!("{}{}", row[0], source.extension().map(|x|format!(".{}",x.to_string_lossy())).unwrap_or_default()));
        fs::copy(source, &path).unwrap();
        let mut permissions = fs::metadata(&path).unwrap().permissions(); permissions.set_readonly(true);
        fs::set_permissions(&path, permissions).unwrap();
        (row[0].to_owned(), Image {generation:row[1].parse().unwrap(),sha256:row[2].to_owned(),path})
    }).collect()
}
fn preserved(a: &Snapshot, b: &Snapshot) {
    assert_eq!((a.completed,a.value,a.generation,a.allocation_token,a.buffer,a.active_sha256.as_str()),
               (b.completed,b.value,b.generation,b.allocation_token,b.buffer,b.active_sha256.as_str()));
}
#[test]
fn real_fresh_a_b_fresh_a_keeps_state_and_rejects_bad_candidates() {
    let images=images(); let mut host=FeedbackHost::new();
    assert!(host.compute(4).is_err());
    let first=host.snapshot().unwrap(); assert_eq!((first.completed,first.value,first.buffer),(0,0,[0;8]));
    for (name,probe,expected) in [("a1",13,5_i64),("b2",19,15),("a3",13,20)] {
        let image=&images[name]; host.activate_immutable(&image.path,&image.sha256,image.generation,probe).unwrap();
        let value=host.compute(4).unwrap(); assert_eq!(value.value,expected); assert_eq!(value.buffer,expected.to_le_bytes());
        assert_eq!(value.allocation_token,first.allocation_token);
    }
    let accepted=host.snapshot().unwrap(); assert_eq!(accepted.completed,3); assert_eq!(accepted.retained_images,3);
    for name in ["wrong-version","short-table","wrong-contract","bad-behavior"] {
        let image=&images[name]; assert!(host.activate_immutable(&image.path,&image.sha256,image.generation,13).is_err());
        preserved(&accepted,&host.snapshot().unwrap());
    }
    assert_eq!(host.compute(4).unwrap().value,25);
}
#[test]
fn refuses_digest_identity_writable_and_oversize_inputs_with_finite_attempts() {
    let images=images(); let image=&images["a1"]; let mut host=FeedbackHost::new();
    host.activate_immutable(&image.path,&image.sha256,1,13).unwrap(); let accepted=host.compute(4).unwrap();
    assert!(host.activate_immutable(&image.path,&image.sha256,1,13).is_err());
    assert!(host.activate_immutable(&image.path,&"0".repeat(64),20,13).is_err());
    assert!(host.activate_immutable(&image.path,&image.sha256.to_uppercase(),21,13).is_err());
    let writable=image.path.with_file_name("writable.dll");fs::write(&writable,b"not a library").unwrap();
    assert!(host.activate_immutable(&writable,&image.sha256,22,13).is_err());
    let huge=image.path.with_file_name("oversize.dll");let file=fs::File::create(&huge).unwrap();file.set_len(IMAGE_LIMIT+1).unwrap();drop(file);
    let mut permissions=fs::metadata(&huge).unwrap().permissions();permissions.set_readonly(true);fs::set_permissions(&huge,permissions).unwrap();
    assert!(host.activate_immutable(&huge,&image.sha256,23,13).is_err());
    preserved(&accepted,&host.snapshot().unwrap());
    for generation in 100..100+ATTEMPT_LIMIT { let _=host.activate_immutable(&image.path,&"0".repeat(64),generation,13); }
    assert_eq!(host.snapshot().unwrap().attempts,ATTEMPT_LIMIT);
    assert!(host.activate_immutable(&image.path,&image.sha256,1000,13).is_err());
    assert_eq!(host.compute(4).unwrap().value,10);
}
