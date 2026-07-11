//! Rust-owned direct-P2P control sockets.

use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4};
use std::time::{Duration, Instant};

use serde_json::json;
use socket2::{Domain, Protocol, SockAddr, Socket, Type};

use rusty_manifold_peer::{
    ManifoldPeerTopologyAuthorization, PEER_TOPOLOGY_AUTHORIZATION_SCHEMA,
    PRODUCT_WIFI_DIRECT_TOPOLOGY_CONTRACT,
};

const MAX_PAYLOAD_BYTES: usize = 4096;

/// Validate a fresh Manifold topology authorization for one local role.
pub fn validate_topology_authorization(
    receipt_json: &str,
    local_peer_id: &str,
    role: &str,
    expected_authority_revision: u64,
    now_ms: u64,
) -> String {
    let receipt: ManifoldPeerTopologyAuthorization = match serde_json::from_str(receipt_json) {
        Ok(value) => value,
        Err(error) => {
            return json!({"status":"blocked","reason":format!("invalid_receipt:{error}")})
                .to_string();
        }
    };
    let reason = if receipt.schema_id.as_str() != PEER_TOPOLOGY_AUTHORIZATION_SCHEMA {
        Some("schema_mismatch")
    } else if !receipt.authorized {
        Some("decision_not_authorized")
    } else if receipt.authority_revision.get() != expected_authority_revision {
        Some("stale_authority_revision")
    } else if receipt.valid_from_ms > now_ms || receipt.expires_at_ms <= now_ms {
        Some("authorization_not_fresh")
    } else if receipt.topology_contract_id.as_str() != PRODUCT_WIFI_DIRECT_TOPOLOGY_CONTRACT {
        Some("topology_contract_mismatch")
    } else if role == "group_owner" && receipt.group_owner_peer_id.as_str() != local_peer_id {
        Some("group_owner_peer_mismatch")
    } else if role == "client" && receipt.client_peer_id.as_str() != local_peer_id {
        Some("client_peer_mismatch")
    } else if !matches!(role, "group_owner" | "client") {
        Some("unsupported_role")
    } else {
        None
    };
    json!({
        "status": if reason.is_none() { "accepted" } else { "blocked" },
        "reason": reason.unwrap_or(""),
        "decision_id": receipt.decision_id.as_str(),
        "session_id": receipt.session_id.as_str(),
        "authority_revision": receipt.authority_revision.get(),
        "expires_at_ms": receipt.expires_at_ms,
        "topology_contract_id": receipt.topology_contract_id.as_str()
    })
    .to_string()
}

fn parse_ipv4(value: &str) -> Result<Ipv4Addr, String> {
    value
        .parse()
        .map_err(|_| format!("invalid IPv4 address: {value}"))
}

fn socket_receipt(
    status: &str,
    local: Ipv4Addr,
    peer: Ipv4Addr,
    port: u16,
    network_handle: u64,
    sent: usize,
    received: usize,
    error: &str,
) -> String {
    json!({
        "status": status,
        "socket": {
            "authority": "rust_direct_socket_provider",
            "local_bind_host": local.to_string(),
            "peer_host": peer.to_string(),
            "peer_port": port,
            "local_bind_applied": status == "pass",
            "closed": true,
            "observed_android_network_handle": network_handle,
            "android_network_substituted_socket_authority": false
        },
        "exchange": {
            "exchange_kind": "bounded_control_exchange",
            "payload_class": "control_probe",
            "messages_sent": if sent > 0 { 1 } else { 0 },
            "messages_received": if received > 0 { 1 } else { 0 },
            "bytes_sent": sent,
            "bytes_received": received,
            "bounded": true
        },
        "error": error
    })
    .to_string()
}

/// Run the bounded group-owner listener using an explicit P2P local bind.
pub fn run_server(local_host: &str, port: u16, network_handle: u64, timeout_ms: u64) -> String {
    let Ok(local) = parse_ipv4(local_host) else {
        return json!({"status":"fail","error":"invalid local host"}).to_string();
    };
    let result = (|| -> Result<(Ipv4Addr, usize, usize), String> {
        let listener = Socket::new(Domain::IPV4, Type::STREAM, Some(Protocol::TCP))
            .map_err(|error| error.to_string())?;
        listener
            .set_reuse_address(true)
            .map_err(|error| error.to_string())?;
        listener
            .bind(&SockAddr::from(SocketAddrV4::new(local, port)))
            .map_err(|error| format!("explicit local bind failed: {error}"))?;
        listener.listen(1).map_err(|error| error.to_string())?;
        listener
            .set_nonblocking(true)
            .map_err(|error| error.to_string())?;
        let deadline = Instant::now() + Duration::from_millis(timeout_ms.max(1));
        let (stream, peer_addr) = loop {
            match listener.accept() {
                Ok(value) => break value,
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                    if Instant::now() >= deadline {
                        return Err("accept timeout".to_string());
                    }
                    std::thread::sleep(Duration::from_millis(50));
                }
                Err(error) => return Err(error.to_string()),
            }
        };
        stream
            .set_nonblocking(false)
            .map_err(|error| error.to_string())?;
        stream
            .set_read_timeout(Some(Duration::from_secs(5)))
            .map_err(|error| error.to_string())?;
        stream
            .set_write_timeout(Some(Duration::from_secs(5)))
            .map_err(|error| error.to_string())?;
        let mut stream: std::net::TcpStream = stream.into();
        let mut buffer = [0_u8; MAX_PAYLOAD_BYTES];
        let received = stream
            .read(&mut buffer)
            .map_err(|error| error.to_string())?;
        if received == 0 || received > MAX_PAYLOAD_BYTES {
            return Err("bounded request was empty or oversized".to_string());
        }
        stream
            .write_all(&buffer[..received])
            .map_err(|error| error.to_string())?;
        stream.flush().map_err(|error| error.to_string())?;
        let peer = match peer_addr.as_socket() {
            Some(SocketAddr::V4(value)) => *value.ip(),
            _ => return Err("peer was not IPv4".to_string()),
        };
        Ok((peer, received, received))
    })();
    match result {
        Ok((peer, sent, received)) => socket_receipt(
            "pass",
            local,
            peer,
            port,
            network_handle,
            sent,
            received,
            "",
        ),
        Err(error) => socket_receipt(
            "fail",
            local,
            Ipv4Addr::UNSPECIFIED,
            port,
            network_handle,
            0,
            0,
            &error,
        ),
    }
}

/// Run the bounded client using an explicit P2P local bind before connect.
pub fn run_client(
    local_host: &str,
    peer_host: &str,
    port: u16,
    run_id: &str,
    network_handle: u64,
    timeout_ms: u64,
) -> String {
    let Ok(local) = parse_ipv4(local_host) else {
        return json!({"status":"fail","error":"invalid local host"}).to_string();
    };
    let Ok(peer) = parse_ipv4(peer_host) else {
        return json!({"status":"fail","error":"invalid peer host"}).to_string();
    };
    let payload = format!("RUSTY_DIRECT_P2P_CONTROL:{run_id}");
    if payload.len() > MAX_PAYLOAD_BYTES {
        return socket_receipt(
            "fail",
            local,
            peer,
            port,
            network_handle,
            0,
            0,
            "payload too large",
        );
    }
    let result = (|| -> Result<(usize, usize), String> {
        let socket = Socket::new(Domain::IPV4, Type::STREAM, Some(Protocol::TCP))
            .map_err(|error| error.to_string())?;
        socket
            .bind(&SockAddr::from(SocketAddrV4::new(local, 0)))
            .map_err(|error| format!("explicit local bind failed: {error}"))?;
        socket
            .connect_timeout(
                &SockAddr::from(SocketAddrV4::new(peer, port)),
                Duration::from_millis(timeout_ms.max(1)),
            )
            .map_err(|error| error.to_string())?;
        socket
            .set_read_timeout(Some(Duration::from_secs(5)))
            .map_err(|error| error.to_string())?;
        socket
            .set_write_timeout(Some(Duration::from_secs(5)))
            .map_err(|error| error.to_string())?;
        let mut stream: std::net::TcpStream = socket.into();
        stream
            .write_all(payload.as_bytes())
            .map_err(|error| error.to_string())?;
        stream.flush().map_err(|error| error.to_string())?;
        let mut response = vec![0_u8; payload.len()];
        stream
            .read_exact(&mut response)
            .map_err(|error| error.to_string())?;
        if response != payload.as_bytes() {
            return Err("echo payload mismatch".to_string());
        }
        Ok((payload.len(), response.len()))
    })();
    match result {
        Ok((sent, received)) => socket_receipt(
            "pass",
            local,
            peer,
            port,
            network_handle,
            sent,
            received,
            "",
        ),
        Err(error) => socket_receipt("fail", local, peer, port, network_handle, 0, 0, &error),
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_directp2p_RustDirectSocketProvider_nativeRunServer(
    mut env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    local_host: jni::objects::JString,
    port: jni::sys::jint,
    network_handle: jni::sys::jlong,
    timeout_ms: jni::sys::jlong,
) -> jni::sys::jstring {
    match env
        .with_env(|env| -> jni::errors::Result<jni::sys::jstring> {
            let local = local_host.try_to_string(env)?;
            let response = run_server(
                &local,
                port as u16,
                network_handle as u64,
                timeout_ms as u64,
            );
            env.new_string(response).map(|value| value.into_raw())
        })
        .into_outcome()
    {
        jni::Outcome::Ok(value) => value,
        jni::Outcome::Err(_) | jni::Outcome::Panic(_) => std::ptr::null_mut(),
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_directp2p_RustDirectSocketProvider_nativeRunClient(
    mut env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    local_host: jni::objects::JString,
    peer_host: jni::objects::JString,
    port: jni::sys::jint,
    run_id: jni::objects::JString,
    network_handle: jni::sys::jlong,
    timeout_ms: jni::sys::jlong,
) -> jni::sys::jstring {
    match env
        .with_env(|env| -> jni::errors::Result<jni::sys::jstring> {
            let local = local_host.try_to_string(env)?;
            let peer = peer_host.try_to_string(env)?;
            let run = run_id.try_to_string(env)?;
            let response = run_client(
                &local,
                &peer,
                port as u16,
                &run,
                network_handle as u64,
                timeout_ms as u64,
            );
            env.new_string(response).map(|value| value.into_raw())
        })
        .into_outcome()
    {
        jni::Outcome::Ok(value) => value,
        jni::Outcome::Err(_) | jni::Outcome::Panic(_) => std::ptr::null_mut(),
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_directp2p_RustDirectSocketProvider_nativeValidateTopologyAuthorization(
    mut env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    receipt_json: jni::objects::JString,
    local_peer_id: jni::objects::JString,
    role: jni::objects::JString,
    expected_revision: jni::sys::jlong,
    now_ms: jni::sys::jlong,
) -> jni::sys::jstring {
    match env
        .with_env(|env| -> jni::errors::Result<jni::sys::jstring> {
            let receipt = receipt_json.try_to_string(env)?;
            let peer = local_peer_id.try_to_string(env)?;
            let local_role = role.try_to_string(env)?;
            let response = validate_topology_authorization(
                &receipt,
                &peer,
                &local_role,
                expected_revision as u64,
                now_ms as u64,
            );
            env.new_string(response).map(|value| value.into_raw())
        })
        .into_outcome()
    {
        jni::Outcome::Ok(value) => value,
        jni::Outcome::Err(_) | jni::Outcome::Panic(_) => std::ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn invalid_client_address_fails_without_opening_a_socket() {
        let receipt = run_client("127.0.0.1", "bad", 9079, "test", 1, 10);
        assert!(receipt.contains("invalid peer host"));
    }

    #[test]
    fn topology_authorization_is_revision_and_role_scoped() {
        let receipt =
            include_str!("../../../../fixtures/peer-session/topology-authorization.pass.json");
        let accepted =
            validate_topology_authorization(receipt, "peer.alpha", "group_owner", 2, 2_000);
        assert!(accepted.contains("\"status\":\"accepted\""));
        let stale = validate_topology_authorization(receipt, "peer.alpha", "group_owner", 3, 2_000);
        assert!(stale.contains("stale_authority_revision"));
        let wrong_peer =
            validate_topology_authorization(receipt, "peer.beta", "group_owner", 2, 2_000);
        assert!(wrong_peer.contains("group_owner_peer_mismatch"));
    }
}
