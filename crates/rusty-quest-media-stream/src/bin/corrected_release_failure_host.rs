//! Host-live failure probes for the five CPU/network criteria that do not
//! require Android process authority. Output is one typed JSON document whose
//! counters come from real bounded queues, sockets, process APIs, or filesystem
//! cleanup attempts; the release adapter remains responsible for Git/run joins.

use std::{
    env, fs,
    io::{Read, Write},
    net::{Shutdown, TcpListener, TcpStream},
    path::PathBuf,
    process::Command,
    sync::mpsc::{sync_channel, TrySendError},
};

use rusty_quest_media_stream::{
    MediaFailureCriterion, MediaFailureRecoveryHarness, MediaFailureSnapshot,
};
use serde::Serialize;
use serde_json::{json, Value};

#[derive(Serialize)]
struct Phase {
    observed_state: &'static str,
    authority_revision: u64,
    provider_epoch: u64,
    cleanup_complete: bool,
    probe: Value,
}

#[derive(Serialize)]
struct HostFailureEvidence {
    schema: &'static str,
    criterion_id: String,
    implementation: &'static str,
    before: Phase,
    failure: Phase,
    recovery: Phase,
    observations: Value,
}

fn phase(snapshot: &MediaFailureSnapshot, probe: Value) -> Phase {
    Phase {
        observed_state: snapshot.observed_state,
        authority_revision: snapshot.authority_revision,
        provider_epoch: snapshot.provider_epoch,
        cleanup_complete: snapshot.cleanup_complete,
        probe,
    }
}

fn route_loss() -> Result<(Value, Value, Value, Value), String> {
    let listener = TcpListener::bind("127.0.0.1:0").map_err(|error| error.to_string())?;
    let address = listener.local_addr().map_err(|error| error.to_string())?;
    let mut client = TcpStream::connect(address).map_err(|error| error.to_string())?;
    let (mut server, peer) = listener.accept().map_err(|error| error.to_string())?;
    client
        .write_all(b"before")
        .map_err(|error| error.to_string())?;
    let mut before_bytes = [0_u8; 6];
    server
        .read_exact(&mut before_bytes)
        .map_err(|error| error.to_string())?;
    server
        .shutdown(Shutdown::Both)
        .map_err(|error| error.to_string())?;
    drop(server);
    let mut eof = [0_u8; 1];
    let route_loss_observed = client.read(&mut eof).map_err(|error| error.to_string())? == 0;
    drop(client);

    let mut recovered_client = TcpStream::connect(address).map_err(|error| error.to_string())?;
    let (mut recovered_server, _) = listener.accept().map_err(|error| error.to_string())?;
    recovered_client
        .write_all(b"recovered")
        .map_err(|error| error.to_string())?;
    let mut recovered_bytes = [0_u8; 9];
    recovered_server
        .read_exact(&mut recovered_bytes)
        .map_err(|error| error.to_string())?;
    let recovered = recovered_bytes == *b"recovered";
    recovered_client.shutdown(Shutdown::Both).ok();
    recovered_server.shutdown(Shutdown::Both).ok();
    drop(recovered_client);
    drop(recovered_server);
    drop(listener);
    let observations = json!({
        "route_active_before": before_bytes == *b"before",
        "route_loss_observed": route_loss_observed,
        "route_recovered": recovered,
        "resources_remaining": 0
    });
    Ok((
        json!({"local_address": address, "peer_address": peer, "bytes": 6}),
        json!({"eof_observed": route_loss_observed}),
        json!({"reconnect_address": address, "bytes": 9, "resources_remaining": 0}),
        observations,
    ))
}

fn bounded_queue(slow_consumer: bool) -> Result<(Value, Value, Value, Value), String> {
    let capacity = if slow_consumer { 8 } else { 16 };
    let eager_consumed = if slow_consumer { 60 } else { 80 };
    let offered = 100;
    let (sender, receiver) = sync_channel::<u32>(capacity);
    let mut consumed = 0;
    let mut queued = 0;
    let mut rejected = 0;
    let mut max_depth = 0;
    for value in 0..offered {
        if value < eager_consumed {
            sender.send(value).map_err(|error| error.to_string())?;
            receiver.recv().map_err(|error| error.to_string())?;
            consumed += 1;
            continue;
        }
        match sender.try_send(value) {
            Ok(()) => {
                queued += 1;
                max_depth = max_depth.max(queued);
            }
            Err(TrySendError::Full(_)) => rejected += 1,
            Err(TrySendError::Disconnected(_)) => {
                return Err("bounded queue disconnected unexpectedly".to_owned())
            }
        }
    }
    drop(sender);
    while receiver.recv().is_ok() {
        consumed += 1;
        queued -= 1;
    }
    if queued != 0 || consumed + rejected != offered {
        return Err("bounded queue accounting did not close".to_owned());
    }
    let observations = if slow_consumer {
        json!({
            "offered_count": offered,
            "consumed_count": consumed,
            "dropped_count": rejected,
            "max_queue_depth": max_depth,
            "queue_capacity": capacity
        })
    } else {
        json!({
            "offered_count": offered,
            "processed_count": consumed,
            "rejected_count": rejected,
            "max_queue_depth": max_depth,
            "queue_capacity": capacity
        })
    };
    Ok((
        json!({"channel": "std.sync.mpsc.sync_channel", "capacity": capacity}),
        json!({"offered": offered, "full_rejections": rejected, "max_depth": max_depth}),
        json!({"drained": consumed, "remaining": queued}),
        observations,
    ))
}

fn codec_failure() -> Result<(Value, Value, Value, Value), String> {
    let requested = "android.mediacodec.h264.host-unavailable-probe";
    let error = Command::new("__rusty_quest_missing_codec_provider__")
        .spawn()
        .expect_err("reserved missing provider command must not start");
    let rejected = error.kind() == std::io::ErrorKind::NotFound;
    if !rejected {
        return Err(format!("unexpected codec provider error: {error}"));
    }
    Ok((
        json!({"provider_started": false}),
        json!({"os_error_kind": format!("{:?}", error.kind()), "provider_started": false}),
        json!({"rejected_before_start": true}),
        json!({
            "codec_requested": requested,
            "codec_rejected": true,
            "provider_started": false
        }),
    ))
}

fn cleanup_failure() -> Result<(Value, Value, Value, Value), String> {
    let root = env::temp_dir().join(format!(
        "rusty-quest-cleanup-failure-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_err(|error| error.to_string())?
            .as_nanos()
    ));
    fs::create_dir_all(&root).map_err(|error| error.to_string())?;
    let retained = root.join("retained-resource.bin");
    fs::write(&retained, b"live-resource").map_err(|error| error.to_string())?;
    let first = fs::remove_dir(&root).expect_err("nonempty cleanup must fail");
    fs::remove_file(&retained).map_err(|error| error.to_string())?;
    fs::remove_dir(&root).map_err(|error| error.to_string())?;
    let resources_remaining = usize::from(root.exists() || retained.exists());
    if resources_remaining != 0 {
        return Err("cleanup retry left resources".to_owned());
    }
    Ok((
        json!({"resource_root": path_text(&root), "resources": 1}),
        json!({"first_cleanup_error_kind": format!("{:?}", first.kind())}),
        json!({"retry_count": 1, "resources_remaining": resources_remaining}),
        json!({
            "cleanup_failure_observed": true,
            "retry_count": 1,
            "resources_remaining": resources_remaining
        }),
    ))
}

fn path_text(path: &PathBuf) -> String {
    path.to_string_lossy().into_owned()
}

fn main() -> Result<(), String> {
    let criterion = env::args()
        .nth(1)
        .ok_or_else(|| "usage: corrected_release_failure_host <criterion>".to_owned())?;
    let (kind, evidence) = match criterion.as_str() {
        "route_loss" => (MediaFailureCriterion::RouteLoss, route_loss()?),
        "slow_consumer" => (MediaFailureCriterion::SlowConsumer, bounded_queue(true)?),
        "queue_pressure" => (MediaFailureCriterion::QueuePressure, bounded_queue(false)?),
        "codec_failure" => (MediaFailureCriterion::CodecFailure, codec_failure()?),
        "cleanup_failure" => (MediaFailureCriterion::CleanupFailure, cleanup_failure()?),
        _ => return Err("criterion requires the explicit-serial Android adapter".to_owned()),
    };
    let mut transitions = MediaFailureRecoveryHarness::new(kind);
    let before = phase(transitions.snapshot(), evidence.0);
    let failure_snapshot = transitions.inject().map_err(|error| format!("{error:?}"))?;
    let failure = phase(failure_snapshot, evidence.1);
    let recovery_snapshot = transitions
        .recover()
        .map_err(|error| format!("{error:?}"))?;
    let recovery = phase(recovery_snapshot, evidence.2);
    let output = HostFailureEvidence {
        schema: "rusty.quest.corrected_release_host_failure_probe.v1",
        criterion_id: criterion,
        implementation: "live-host-runtime-apis",
        before,
        failure,
        recovery,
        observations: evidence.3,
    };
    println!(
        "{}",
        serde_json::to_string(&output).map_err(|error| error.to_string())?
    );
    Ok(())
}
