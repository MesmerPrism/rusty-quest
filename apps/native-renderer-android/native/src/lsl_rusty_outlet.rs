//! Optional pure-Rust LSL outlet backend for panel-controlled telemetry.
//!
//! Rusty LSL deliberately exposes caller-owned scheduling and interface
//! selection.  This adapter keeps those choices in the native-renderer owner:
//! one explicit IPv4 interface, one caller-polled registry, and no hidden
//! recovery or worker policy.

use std::sync::atomic::AtomicBool;

pub(crate) const RUSTY_LSL_SOURCE_COMMIT: &str = "8b6b2a6cd0c0e5147b7e1cc076a116ef226cddbd";

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct RustyLslStreamDefinition<'a> {
    pub(crate) suffix: &'a str,
    pub(crate) stream_type: &'a str,
    pub(crate) channel_count: usize,
    pub(crate) nominal_rate_hz: f64,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub(crate) struct RustyLslPollHealth {
    pub(crate) discovery_queries: u64,
    pub(crate) discovery_responses: u64,
    pub(crate) consumers_accepted: u64,
    pub(crate) connected_consumers: usize,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub(crate) struct RustyLslPushOutcome {
    pub(crate) complete_deliveries: u64,
}

#[cfg(feature = "rusty-lsl-backend")]
mod compiled {
    use super::*;
    use rusty_lsl::{
        admit_runtime_activation, persistent_float32_local_clock, MetadataTreeLimits,
        PersistentFloat32Outlet, PersistentFloat32OutletActivation, PersistentFloat32OutletId,
        PersistentFloat32OutletLimits, PersistentFloat32OutletRegistry,
        PersistentFloat32OutletRegistryLimits, PersistentFloat32OutletServiceLimits,
        RawSourceTimestamp, RuntimeActivationSelection, RuntimeModule, ShortInfoQueryWireLimits,
        ShortInfoResponderActivation, ShortInfoResponseEnvelopeLimits, StreamDescriptorLimits,
        StreamHandshakeActivation, StreamHandshakeIdentity, StreamHandshakeLimits,
        StreamInfoObservedAdmissionLimits, StreamInfoObservedDocumentParseLimit,
        StreamInfoVolatileFieldLimits, TimestampedFloat32SampleActivation,
        TimestampedFloat32SampleLimits, ACCEPTED_FEATURE_LOCK_FINGERPRINT,
        DOCUMENTED_IPV4_MULTICAST_GROUP, DOCUMENTED_IPV4_MULTICAST_PORT,
    };
    use std::{
        net::{Ipv4Addr, TcpListener, UdpSocket},
        time::Duration,
    };

    const MAX_OUTLETS: usize = 6;
    const MAX_CONSUMERS_PER_OUTLET: usize = 2;
    const MAX_RECORDS_PER_PUSH: usize = 1;
    const MAX_DOCUMENT_BYTES: usize = 16_384;
    const MAX_STREAM_DESCRIPTOR_TEXT: usize = 384;

    struct RegisteredOutlet {
        suffix: String,
        id: PersistentFloat32OutletId,
    }

    pub(crate) struct RustyLslOutletSet {
        registry: PersistentFloat32OutletRegistry,
        outlets: Vec<RegisteredOutlet>,
    }

    impl RustyLslOutletSet {
        pub(crate) fn create(
            interface: Ipv4Addr,
            stream_stem: &str,
            source_stem: &str,
            app_session_id: &str,
            session_id: &str,
            definitions: &[RustyLslStreamDefinition<'_>],
        ) -> Result<Self, String> {
            if definitions.is_empty() || definitions.len() > MAX_OUTLETS {
                return Err("rusty-lsl-outlet-count-out-of-range".to_owned());
            }
            if interface.is_unspecified()
                || interface.is_loopback()
                || interface.is_multicast()
                || interface == Ipv4Addr::BROADCAST
            {
                return Err("rusty-lsl-interface-not-concrete-lan-ipv4".to_owned());
            }
            let (outlet_activation, responder_activation) = activations()?;
            let handshake_limits = handshake_limits();
            let sample_limits = sample_limits();
            let mut prepared = Vec::with_capacity(definitions.len());
            let mut max_body_bytes = 0usize;
            for (index, definition) in definitions.iter().enumerate() {
                let listener = TcpListener::bind((Ipv4Addr::UNSPECIFIED, 0))
                    .map_err(|error| format!("rusty-lsl-listener-bind:{:?}", error.kind()))?;
                let uid = stream_uid(app_session_id, index)?;
                let source_id = format!("{source_stem}:{}", definition.suffix);
                let outlet = PersistentFloat32Outlet::new(
                    outlet_activation,
                    listener,
                    StreamHandshakeIdentity::new(
                        uid.clone(),
                        "quest".to_owned(),
                        source_id.clone(),
                        session_id.to_owned(),
                        handshake_limits,
                    )
                    .map_err(|error| format!("rusty-lsl-identity:{error:?}"))?,
                    handshake_limits,
                    sample_limits,
                    definition.channel_count,
                    PersistentFloat32OutletLimits::new(
                        MAX_RECORDS_PER_PUSH,
                        MAX_CONSUMERS_PER_OUTLET,
                    )
                    .map_err(|error| format!("rusty-lsl-outlet-limits:{error:?}"))?,
                )
                .map_err(|error| format!("rusty-lsl-outlet-create:{error:?}"))?;
                let body = stream_info_body(
                    interface,
                    outlet.local_address().port(),
                    stream_stem,
                    definition,
                    &source_id,
                    &uid,
                    session_id,
                );
                max_body_bytes = max_body_bytes.max(body.len());
                prepared.push((definition.suffix.to_owned(), outlet, body));
            }
            if max_body_bytes == 0 || max_body_bytes > MAX_DOCUMENT_BYTES {
                return Err("rusty-lsl-stream-info-size-out-of-range".to_owned());
            }

            let discovery =
                UdpSocket::bind((Ipv4Addr::UNSPECIFIED, DOCUMENTED_IPV4_MULTICAST_PORT))
                    .map_err(|error| format!("rusty-lsl-discovery-bind:{:?}", error.kind()))?;
            discovery
                .join_multicast_v4(&DOCUMENTED_IPV4_MULTICAST_GROUP, &interface)
                .map_err(|error| format!("rusty-lsl-discovery-join:{:?}", error.kind()))?;
            let service_limits = PersistentFloat32OutletServiceLimits::new(
                MAX_DOCUMENT_BYTES,
                StreamInfoObservedDocumentParseLimit::new(max_body_bytes)
                    .map_err(|error| format!("rusty-lsl-document-limit:{error:?}"))?,
                StreamInfoObservedAdmissionLimits::new(
                    StreamDescriptorLimits::new(
                        MAX_STREAM_DESCRIPTOR_TEXT,
                        128,
                        MAX_STREAM_DESCRIPTOR_TEXT,
                        32,
                    )
                    .map_err(|error| format!("rusty-lsl-descriptor-limits:{error:?}"))?,
                    MetadataTreeLimits::new(1, 1, 1, 4, 1)
                        .map_err(|error| format!("rusty-lsl-metadata-limits:{error:?}"))?,
                    StreamInfoVolatileFieldLimits::new(128, 128, 128)
                        .map_err(|error| format!("rusty-lsl-volatile-limits:{error:?}"))?,
                ),
                ShortInfoQueryWireLimits::new(1024, 2048)
                    .map_err(|error| format!("rusty-lsl-query-limits:{error:?}"))?,
                ShortInfoResponseEnvelopeLimits::new(max_body_bytes, max_body_bytes + 64)
                    .map_err(|error| format!("rusty-lsl-response-limits:{error:?}"))?,
            )
            .map_err(|error| format!("rusty-lsl-service-limits:{error:?}"))?;
            let mut registry = PersistentFloat32OutletRegistry::new_prebound(
                responder_activation,
                interface,
                discovery,
                PersistentFloat32OutletRegistryLimits::new(definitions.len(), service_limits)
                    .map_err(|error| format!("rusty-lsl-registry-limits:{error:?}"))?,
            )
            .map_err(|error| format!("rusty-lsl-registry-create:{error:?}"))?;
            let mut outlets = Vec::with_capacity(prepared.len());
            for (suffix, outlet, body) in prepared {
                let id = registry
                    .register(outlet, body)
                    .map_err(|error| format!("rusty-lsl-register:{error:?}"))?;
                outlets.push(RegisteredOutlet { suffix, id });
            }
            Ok(Self { registry, outlets })
        }

        pub(crate) fn count(&self) -> usize {
            self.outlets.len()
        }

        pub(crate) fn poll(&mut self, stop: &AtomicBool) -> Result<RustyLslPollHealth, String> {
            self.registry
                .poll(stop)
                .map_err(|error| format!("rusty-lsl-poll:{error:?}"))?;
            let health = self.registry.health();
            let connected_consumers = self
                .outlets
                .iter()
                .filter_map(|entry| self.registry.outlet_health(entry.id))
                .map(|health| health.connected_consumers())
                .sum();
            Ok(RustyLslPollHealth {
                discovery_queries: health.discovery_queries(),
                discovery_responses: health.discovery_responses(),
                consumers_accepted: health.consumers_accepted(),
                connected_consumers,
            })
        }

        pub(crate) fn push(
            &mut self,
            suffix: &str,
            sample: &[f32],
            timestamp: f64,
            stop: &AtomicBool,
        ) -> Result<RustyLslPushOutcome, String> {
            let entry = self
                .outlets
                .iter()
                .find(|entry| entry.suffix == suffix)
                .ok_or_else(|| "rusty-lsl-outlet-not-selected".to_owned())?;
            let timestamp = RawSourceTimestamp::new(timestamp)
                .map_err(|error| format!("rusty-lsl-source-timestamp:{error:?}"))?;
            let report = self
                .registry
                .try_push_chunk(entry.id, sample, &[timestamp], stop)
                .ok_or_else(|| "rusty-lsl-outlet-id-missing".to_owned())?
                .map_err(|error| format!("rusty-lsl-push:{error:?}"))?;
            Ok(RustyLslPushOutcome {
                complete_deliveries: report.complete_deliveries() as u64,
            })
        }

        pub(crate) fn close(self) {
            let _ = self.registry.close();
        }
    }

    fn activations() -> Result<
        (
            PersistentFloat32OutletActivation,
            ShortInfoResponderActivation,
        ),
        String,
    > {
        let selections = [
            RuntimeActivationSelection::new(
                RuntimeModule::ShortInfoDiscoveryResponder.id(),
                RuntimeModule::ShortInfoDiscoveryResponder.effective_marker(),
            ),
            RuntimeActivationSelection::new(
                RuntimeModule::StreamHandshake.id(),
                RuntimeModule::StreamHandshake.effective_marker(),
            ),
            RuntimeActivationSelection::new(
                RuntimeModule::TimestampedFloat32Sample.id(),
                RuntimeModule::TimestampedFloat32Sample.effective_marker(),
            ),
            RuntimeActivationSelection::new(
                RuntimeModule::PersistentFloat32Outlet.id(),
                RuntimeModule::PersistentFloat32Outlet.effective_marker(),
            ),
        ];
        let admission = admit_runtime_activation(
            ACCEPTED_FEATURE_LOCK_FINGERPRINT,
            "rusty-quest-native-renderer-lsl-panel-outlet",
            &selections,
        )
        .map_err(|error| format!("rusty-lsl-activation:{error:?}"))?;
        let handshake = StreamHandshakeActivation::new(
            admission
                .capability(RuntimeModule::StreamHandshake)
                .ok_or_else(|| "rusty-lsl-handshake-capability-missing".to_owned())?,
        )
        .map_err(|error| format!("rusty-lsl-handshake-activation:{error:?}"))?;
        let samples = TimestampedFloat32SampleActivation::new(
            admission
                .capability(RuntimeModule::TimestampedFloat32Sample)
                .ok_or_else(|| "rusty-lsl-float32-capability-missing".to_owned())?,
            handshake,
        )
        .map_err(|error| format!("rusty-lsl-float32-activation:{error:?}"))?;
        let outlet = PersistentFloat32OutletActivation::new(
            admission
                .capability(RuntimeModule::PersistentFloat32Outlet)
                .ok_or_else(|| "rusty-lsl-persistent-outlet-capability-missing".to_owned())?,
            samples,
        )
        .map_err(|error| format!("rusty-lsl-outlet-activation:{error:?}"))?;
        let responder = ShortInfoResponderActivation::new(
            admission
                .capability(RuntimeModule::ShortInfoDiscoveryResponder)
                .ok_or_else(|| "rusty-lsl-responder-capability-missing".to_owned())?,
        )
        .map_err(|error| format!("rusty-lsl-responder-activation:{error:?}"))?;
        Ok((outlet, responder))
    }

    fn handshake_limits() -> StreamHandshakeLimits {
        StreamHandshakeLimits::new(4096, 256, Duration::from_millis(10), Duration::from_secs(5))
            .expect("fixed Rusty LSL handshake limits")
    }

    fn sample_limits() -> TimestampedFloat32SampleLimits {
        TimestampedFloat32SampleLimits::new(Duration::from_millis(10), Duration::from_secs(5))
            .expect("fixed Rusty LSL sample limits")
    }

    fn stream_uid(app_session_id: &str, index: usize) -> Result<String, String> {
        if app_session_id.len() != 32
            || !app_session_id
                .chars()
                .all(|character| character.is_ascii_hexdigit())
            || index >= MAX_OUTLETS
        {
            return Err("rusty-lsl-app-session-id-invalid".to_owned());
        }
        Ok(format!(
            "{}-{}-{}-{}-{}{:02x}",
            &app_session_id[0..8],
            &app_session_id[8..12],
            &app_session_id[12..16],
            &app_session_id[16..20],
            &app_session_id[20..30],
            index + 1,
        ))
    }

    #[allow(clippy::too_many_arguments)]
    fn stream_info_body(
        interface: Ipv4Addr,
        port: u16,
        stream_stem: &str,
        definition: &RustyLslStreamDefinition<'_>,
        source_id: &str,
        uid: &str,
        session_id: &str,
    ) -> String {
        let nominal_rate = if definition.nominal_rate_hz == 0.0 {
            "0.000000000000000".to_owned()
        } else {
            format!("{:.13}", definition.nominal_rate_hz)
        };
        format!(
            "<?xml version=\"1.0\"?>\n<info>\n\
\t<name>{stream_stem}_{suffix}</name>\n\
\t<type>{stream_type}</type>\n\
\t<channel_count>{channel_count}</channel_count>\n\
\t<channel_format>float32</channel_format>\n\
\t<source_id>{source_id}</source_id>\n\
\t<nominal_srate>{nominal_rate}</nominal_srate>\n\
\t<version>1.100000000000000</version>\n\
\t<created_at>{created_at}</created_at>\n\
\t<uid>{uid}</uid>\n\
\t<session_id>{session_id}</session_id>\n\
\t<hostname>quest</hostname>\n\
\t<v4address>{interface}</v4address>\n\
\t<v4data_port>{port}</v4data_port>\n\
\t<v4service_port>{port}</v4service_port>\n\
\t<v6address></v6address>\n\
\t<v6data_port>0</v6data_port>\n\
\t<v6service_port>0</v6service_port>\n\
\t<desc />\n</info>\n",
            suffix = definition.suffix,
            stream_type = definition.stream_type,
            channel_count = definition.channel_count,
            created_at = persistent_float32_local_clock(),
        )
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn stream_uid_is_session_bound_and_per_outlet_unique() {
            let session = "00112233445566778899aabbccddeeff";
            let first = stream_uid(session, 0).unwrap();
            let second = stream_uid(session, 1).unwrap();
            assert_eq!(first.len(), 36);
            assert_ne!(first, second);
            assert!(stream_uid("bad", 0).is_err());
        }

        #[test]
        fn body_keeps_exact_public_stream_schema() {
            let definition = RustyLslStreamDefinition {
                suffix: "headset_views",
                stream_type: "HeadsetViews",
                channel_count: 14,
                nominal_rate_hz: 0.0,
            };
            let body = stream_info_body(
                Ipv4Addr::new(192, 0, 2, 10),
                16574,
                "viscereality_P001_S001",
                &definition,
                "io.github.mesmerprism.viscereality:P001:S001:headset_views",
                "00112233-4455-6677-8899-aabbccddee01",
                "S001",
            );
            assert!(body.contains("<name>viscereality_P001_S001_headset_views</name>"));
            assert!(body.contains("<type>HeadsetViews</type>"));
            assert!(body.contains("<channel_count>14</channel_count>"));
            assert!(body.contains("<nominal_srate>0.000000000000000</nominal_srate>"));
            assert!(body.contains("<v4address>192.0.2.10</v4address>"));
        }
    }
}

#[cfg(not(feature = "rusty-lsl-backend"))]
mod unavailable {
    use super::*;
    use std::net::Ipv4Addr;

    pub(crate) struct RustyLslOutletSet;

    impl RustyLslOutletSet {
        pub(crate) fn create(
            _interface: Ipv4Addr,
            _stream_stem: &str,
            _source_stem: &str,
            _app_session_id: &str,
            _session_id: &str,
            _definitions: &[RustyLslStreamDefinition<'_>],
        ) -> Result<Self, String> {
            Err("rusty-lsl-backend-not-compiled".to_owned())
        }

        pub(crate) fn count(&self) -> usize {
            0
        }

        pub(crate) fn poll(&mut self, _stop: &AtomicBool) -> Result<RustyLslPollHealth, String> {
            Err("rusty-lsl-backend-not-compiled".to_owned())
        }

        pub(crate) fn push(
            &mut self,
            _suffix: &str,
            _sample: &[f32],
            _timestamp: f64,
            _stop: &AtomicBool,
        ) -> Result<RustyLslPushOutcome, String> {
            Err("rusty-lsl-backend-not-compiled".to_owned())
        }

        pub(crate) fn close(self) {}
    }
}

pub(crate) const fn compiled() -> bool {
    cfg!(feature = "rusty-lsl-backend")
}

#[cfg(feature = "rusty-lsl-backend")]
pub(crate) use compiled::RustyLslOutletSet;
#[cfg(not(feature = "rusty-lsl-backend"))]
pub(crate) use unavailable::RustyLslOutletSet;
