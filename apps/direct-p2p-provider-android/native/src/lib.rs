//! Rust-owned direct-P2P control sockets.

use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4};
use std::time::{Duration, Instant};

use serde_json::json;
use socket2::{Domain, Protocol, SockAddr, Socket, Type};

const MAX_PAYLOAD_BYTES: usize = 4096;

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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn invalid_client_address_fails_without_opening_a_socket() {
        let receipt = run_client("127.0.0.1", "bad", 9079, "test", 1, 10);
        assert!(receipt.contains("invalid peer host"));
    }
}
