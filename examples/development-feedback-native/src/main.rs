mod abi;
mod digest;
mod loader;
mod runtime;

use loader::{Candidate, ResidentHost};
use runtime::{dispatch, GuardedBuffer, State};
use std::{fs, path::PathBuf};

struct Artifact {
    name: String,
    generation: u64,
    hash: String,
    path: PathBuf,
}

fn select(
    host: &mut ResidentHost,
    artifact: &Artifact,
    scratch_expected: i64,
) -> Result<(), &'static str> {
    host.select(Candidate {
        path: &artifact.path,
        sha256: &artifact.hash,
        generation: artifact.generation,
        scratch_expected,
    })
}

fn run() -> Result<(), String> {
    let manifest = std::env::args()
        .nth(1)
        .ok_or("expected artifact TSV path")?;
    let text = fs::read_to_string(manifest).map_err(|e| e.to_string())?;
    let mut artifacts = Vec::new();
    for line in text.lines() {
        let fields: Vec<_> = line.split('\t').collect();
        if fields.len() != 4 {
            return Err("invalid artifact manifest".into());
        }
        let artifact = Artifact {
            name: fields[0].into(),
            generation: fields[1].parse().map_err(|_| "generation")?,
            hash: fields[2].into(),
            path: fs::canonicalize(fields[3]).map_err(|e| e.to_string())?,
        };
        artifacts.push(artifact);
    }
    if artifacts.len() != 7
        || artifacts
            .iter()
            .map(|a| a.name.as_str())
            .collect::<Vec<_>>()
            != [
                "a1",
                "b2",
                "a3",
                "wrong-version",
                "short-table",
                "wrong-contract",
                "bad-behavior",
            ]
    {
        return Err("expected exact A1/B2/A3 and negative fixtures".into());
    }
    let mut host = ResidentHost::new();
    // One authoritative allocation survives every candidate and dispatch.
    let mut buffer = Box::new(GuardedBuffer::new());
    let buffer_address = buffer.bytes.as_ptr();
    let mut state = State {
        completed: 0,
        value: 0,
    };
    select(&mut host, &artifacts[0], 13)?;
    dispatch(host.active.unwrap().1, &mut state, &mut buffer, 4)?;
    assert_eq!(
        state,
        State {
            completed: 1,
            value: 5
        }
    );
    println!("accepted A1 generation=1 counter=1 value=5");

    let saved = state;
    let saved_buffer = *buffer;
    let images = host.retained_images();
    assert_eq!(
        host.select(Candidate {
            path: &artifacts[1].path,
            sha256: &"0".repeat(64),
            generation: 2,
            scratch_expected: 19
        }),
        Err("artifact SHA256")
    );
    assert_eq!(host.retained_images(), images); // mismatch never reaches loader
    assert_eq!(state, saved);
    assert_eq!(*buffer, saved_buffer);
    dispatch(host.active.unwrap().1, &mut state, &mut buffer, 4)?;
    println!("rejected hash; prior A1 continues counter=2 value=10");

    for (index, reason) in [
        (3, "ABI version"),
        (4, "API size"),
        (5, "contract"),
        (6, "scratch result"),
    ] {
        let saved = state;
        let saved_buffer = *buffer;
        assert_eq!(select(&mut host, &artifacts[index], 13), Err(reason));
        assert_eq!(host.active.unwrap().0, 1);
        assert_eq!(state, saved);
        assert_eq!(*buffer, saved_buffer);
        dispatch(host.active.unwrap().1, &mut state, &mut buffer, 4)?;
        println!(
            "rejected {reason}; prior A1 continues counter={} value={}",
            state.completed, state.value
        );
    }
    let saved = state;
    let saved_buffer = *buffer;
    assert_eq!(
        host.select(Candidate {
            path: &artifacts[1].path,
            sha256: &artifacts[1].hash,
            generation: 99,
            scratch_expected: 19
        }),
        Err("generation")
    );
    assert_eq!(state, saved);
    assert_eq!(*buffer, saved_buffer);
    dispatch(host.active.unwrap().1, &mut state, &mut buffer, 4)?;
    assert_eq!(
        state,
        State {
            completed: 7,
            value: 35
        }
    );
    println!("rejected generation; prior A1 continues counter=7 value=35");

    let saved = state;
    let saved_buffer = *buffer;
    select(&mut host, &artifacts[1], 19)?;
    assert_eq!(state, saved);
    assert_eq!(*buffer, saved_buffer);
    dispatch(host.active.unwrap().1, &mut state, &mut buffer, 4)?;
    assert_eq!(
        state,
        State {
            completed: 8,
            value: 45
        }
    );
    println!("accepted B2 generation=2 changed helper counter=8 value=45");
    let saved = state;
    let saved_buffer = *buffer;
    select(&mut host, &artifacts[2], 13)?;
    assert_eq!(state, saved);
    assert_eq!(*buffer, saved_buffer);
    dispatch(host.active.unwrap().1, &mut state, &mut buffer, 4)?;
    assert_eq!(
        state,
        State {
            completed: 9,
            value: 50
        }
    );
    println!("accepted A3 generation=3 counter=9 value=50; code rollback preserves state");
    let saved = state;
    let saved_buffer = *buffer;
    assert_eq!(
        dispatch(host.active.unwrap().1, &mut state, &mut buffer, i64::MAX),
        Err("operation rejected")
    );
    assert_eq!(state, saved);
    assert_eq!(*buffer, saved_buffer);
    assert_eq!(buffer.bytes.as_ptr(), buffer_address);
    assert_eq!((buffer.before, buffer.after), (0xaabbccdd, 0x11223344));
    assert_eq!(host.retained_images(), 8);
    println!("overflow leaves counter/buffer/state unchanged; allocation/guards stable; retained images=8; no unload");
    println!("PASS native A1-B2-A3 conformance");
    Ok(())
}

fn main() {
    if let Err(error) = run() {
        eprintln!("FAIL: {error}");
        std::process::exit(1);
    }
}
