//! Bounded app-owned source-set observations. No flag or submit call is pixel proof.
use crate::spatial_stereo_dropouts::{Freshness, Key as ProgressKey, Progress};
use crate::stereo_input_set::StereoFrameIdentity;
use std::sync::Mutex;
pub(crate) const WORD_COUNT: usize = 160;
const HISTORY: usize = 64;
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct SourceFact {
    pub identity: StereoFrameIdentity,
    pub geometry_revision: Option<u64>,
    pub config_revision: u64,
    pub prefix: u32,
    pub observed_at_ns: u64,
    pub content_serial: u64,
    pub processing_codes: [u32; 4],
}
#[derive(Clone, Copy, Debug)]
pub(crate) struct FrameFact {
    pub arm_generation: u64,
    pub ordinal: u64,
    pub surface: u64,
    pub revision: u64,
    pub policies: [u32; 6],
    pub mask: [u32; 3],
    pub sources: [Option<SourceFact>; 2],
    pub demanded: [usize; 2],
    pub final_draw: bool,
    pub geometry_sampled: bool,
    pub available: [bool; 2],
    pub primary_prefix: u32,
    pub final_abi_version: u32,
}
#[derive(Clone, Copy, Default)]
struct OriginStats {
    first: u64,
    last: u64,
    distinct: u64,
    max_gap: u64,
    max_age: u64,
    missing: u64,
    transitions: u64,
    removed_at: u64,
    removals: u64,
    last_identity: Option<StereoFrameIdentity>,
}
#[derive(Clone, Copy)]
struct PixelFact {
    frame: FrameFact,
    now: u64,
    count: u64,
    hash: u64,
    format: u32,
    width: u32,
    height: u32,
    flags: u32,
    contract: u32,
}
struct State {
    process: u64,
    challenge: [u64; 2],
    generation: u64,
    armed: bool,
    carrier_live: bool,
    cleanup: u64,
    armed_at: u64,
    pending_since: u64,
    first_recorded: u64,
    last_recorded: u64,
    recorded: u64,
    entered: u64,
    first_gpu: u64,
    last_gpu: u64,
    gpu: u64,
    max_gap: u64,
    first_both: u64,
    last_both: u64,
    both: u64,
    max_both_gap: u64,
    own_after_peer: u64,
    peer_removed_at: u64,
    peer_removed_count: u64,
    origins: [OriginStats; 2],
    pending: Option<FrameFact>,
    last: Option<FrameFact>,
    history: [Option<FrameFact>; HISTORY],
    next: usize,
    pixel: Option<PixelFact>,
    blend_strip: Option<(u64, u64, [[u8; 4]; 5])>,
    pixel_count: u64,
    pixel_unavailable: u64,
    foreign_enabled: bool,
    capability_mask: u64,
    sdk_session: u64,
    readback_requested: bool,
    dropout_progress: [Progress; 3],
    freshness: Freshness,
    pending_lifetime: Option<u64>,
}
impl State {
    const fn empty() -> Self {
        Self {
            process: 0,
            challenge: [0; 2],
            generation: 0,
            armed: false,
            carrier_live: false,
            cleanup: 0,
            armed_at: 0,
            pending_since: 0,
            first_recorded: 0,
            last_recorded: 0,
            recorded: 0,
            entered: 0,
            first_gpu: 0,
            last_gpu: 0,
            gpu: 0,
            max_gap: 0,
            first_both: 0,
            last_both: 0,
            both: 0,
            max_both_gap: 0,
            own_after_peer: 0,
            peer_removed_at: 0,
            peer_removed_count: 0,
            origins: [OriginStats {
                first: 0,
                last: 0,
                distinct: 0,
                max_gap: 0,
                max_age: 0,
                missing: 0,
                transitions: 0,
                removed_at: 0,
                removals: 0,
                last_identity: None,
            }; 2],
            pending: None,
            last: None,
            history: [None; HISTORY],
            next: 0,
            pixel: None,
            blend_strip: None,
            pixel_count: 0,
            pixel_unavailable: 0,
            foreign_enabled: false,
            capability_mask: 0,
            sdk_session: 0,
            readback_requested: false,
            dropout_progress: [Progress::empty(); 3],
            freshness: Freshness::empty(),
            pending_lifetime: None,
        }
    }
    fn arm(&mut self, process: u64, challenge: [u64; 2], now: u64) -> u64 {
        let Some(generation) = self.generation.checked_add(1) else {
            return 0;
        };
        let (live, foreign, capabilities, session, cleanup) = (
            self.carrier_live,
            self.foreign_enabled,
            self.capability_mask,
            self.sdk_session,
            self.cleanup,
        );
        let mut freshness = std::mem::replace(&mut self.freshness, Freshness::empty());
        freshness.reject(true); // An ARM replacement cannot erase an earned lifetime.
        *self = Self::empty();
        self.freshness = freshness;
        self.generation = generation;
        self.process = process;
        self.foreign_enabled = foreign;
        self.capability_mask = capabilities;
        self.sdk_session = session;
        self.challenge = challenge;
        self.armed = true;
        self.carrier_live = live;
        self.armed_at = now;
        self.cleanup = if cleanup == 3 {
            3
        } else if live {
            1
        } else {
            cleanup
        };
        if live || cleanup == 3 {
            self.pending_since = now;
        }
        self.dropout_progress[0].demand(live, now);
        generation
    }
    fn record(&mut self, fact: FrameFact, now: u64) {
        self.record_timed(fact, now, Some(now));
    }
    fn record_timed(&mut self, fact: FrameFact, now: u64, event_now: Option<u64>) {
        if fact.primary_prefix != 0
            && (fact.final_abi_version != 2
                || fact.primary_prefix != 6
                || crate::stereo_bank_mask_v1::validate(fact.mask) != Ok(true)
                || fact
                    .sources
                    .iter()
                    .any(|s| s.is_none_or(|s| s.prefix != 6 || s.config_revision != fact.revision))
                || fact.sources[0]
                    .zip(fact.sources[1])
                    .is_none_or(|(a, b)| a.processing_codes != b.processing_codes))
        {
            self.freshness.reject(false);
            return;
        }
        if !self.armed
            || fact.arm_generation != self.generation
            || fact.ordinal == 0
            || fact.surface == 0
            || now < self.armed_at
            || now < self.last_recorded
        {
            self.freshness.reject(false);
            return;
        }
        if fact.sources.iter().flatten().any(|source| {
            source.identity.epoch.process_generation != self.process
                || source.observed_at_ns > now
                || ![1, 3, 4, 6].contains(&source.prefix)
        }) {
            self.freshness.reject(false);
            return;
        }
        self.dropout_progress[0].demand(self.carrier_live, now);
        for origin in 0..2 {
            self.dropout_progress[origin + 1].demand(fact.demanded[origin] > 0, now);
        }
        if self.cleanup != 3 {
            self.cleanup = 1;
        }
        if self.pending_since == 0 {
            self.pending_since = now;
        }
        self.first_recorded = if self.recorded == 0 {
            now
        } else {
            self.first_recorded
        };
        self.last_recorded = now;
        self.recorded = self.recorded.saturating_add(1);
        self.freshness.record_context(frame_epoch(fact), fact.revision, fact.demanded.map(|v|v>0), event_now);
        self.pending_lifetime = self.freshness.active_stamp();
        self.pending = Some(fact);
    }
    fn retire(&mut self, ordinal: u64, surface: u64, now: u64) {
        self.retire_timed(ordinal, surface, now, Some(now));
    }
    fn retire_timed(&mut self, ordinal: u64, surface: u64, now: u64, event_now: Option<u64>) {
        let Some(fact) = self.pending else { return };
        if fact.ordinal != ordinal
            || fact.surface != surface
            || now < self.last_recorded
            || now < self.last_gpu
        {
            return;
        }
        if !self.armed || fact.arm_generation != self.generation {
            self.pending = None;
            return;
        }
        self.freshness.retired(self.pending_lifetime, frame_epoch(fact),
            [fact.surface,fact.ordinal,fact.sources[0].map_or(0,|s|s.identity.pair_sequence),fact.sources[1].map_or(0,|s|s.identity.pair_sequence)],
            [true,fact.final_draw&&fact.sources[0].is_some_and(|s|self.origins[0].last_identity!=Some(s.identity)),
                fact.final_draw&&fact.sources[1].is_some_and(|s|self.origins[1].last_identity!=Some(s.identity)),
                fact.final_draw&&fact.sources.iter().all(Option::is_some)],
            fact.sources.map(|s|s.map(|s|s.observed_at_ns)),event_now);
        self.pending_lifetime=None;
        self.pending = None;
        self.first_gpu = if self.gpu == 0 { now } else { self.first_gpu };
        self.dropout_progress[0].advance(
            ProgressKey {
                epoch: surface,
                sequence: ordinal,
            },
            now,
        );
        if self.last_gpu != 0 {
            self.max_gap = self.max_gap.max(now.saturating_sub(self.last_gpu));
        }
        self.last_gpu = now;
        self.gpu = self.gpu.saturating_add(1);
        for origin in 0..2 {
            let stats = &mut self.origins[origin];
            let Some(source) = fact.sources[origin] else {
                stats.missing = stats.missing.saturating_add(1);
                continue;
            };
            stats.max_age = stats.max_age.max(now.saturating_sub(source.observed_at_ns));
            if !fact.final_draw {
                continue;
            }
            if fact.demanded[origin] > 0 {
                let key = ProgressKey {
                    epoch: source.identity.epoch.source_generation,
                    sequence: source.identity.pair_sequence,
                };
                self.dropout_progress[origin + 1].advance(key, now);
                self.dropout_progress[origin + 1].source_age_at_progress(
                    key,
                    now,
                    source.observed_at_ns,
                );
            }
            if stats.last_identity != Some(source.identity) {
                if let Some(previous) = stats.last_identity {
                    if previous.epoch != source.identity.epoch {
                        stats.transitions = stats.transitions.saturating_add(1);
                    }
                }
                if stats.last != 0 {
                    stats.max_gap = stats.max_gap.max(now.saturating_sub(stats.last));
                }
                if stats.distinct == 0 {
                    stats.first = now;
                }
                stats.last = now;
                stats.distinct = stats.distinct.saturating_add(1);
                stats.last_identity = Some(source.identity);
            }
        }
        if fact.final_draw && fact.sources.iter().all(Option::is_some) {
            if self.both == 0 {
                self.first_both = now;
            } else {
                self.max_both_gap = self.max_both_gap.max(now.saturating_sub(self.last_both));
            }
            self.last_both = now;
            self.both = self.both.saturating_add(1);
        }
        if fact.final_draw
            && fact.sources[0].is_some()
            && !fact.available[1]
            && self.peer_removed_at != 0
            && now >= self.peer_removed_at
        {
            self.own_after_peer = self.own_after_peer.saturating_add(1);
        }
        self.last = Some(fact);
        self.history[self.next] = Some(fact);
        self.next = (self.next + 1) % HISTORY;
    }
    fn pixel(
        &mut self,
        ordinal: u64,
        surface: u64,
        now: u64,
        count: u64,
        hash: u64,
        format: u32,
        width: u32,
        height: u32,
        flags: u32,
        contract: u32,
    ) {
        if !self.armed {
            return;
        }
        let Some(frame) = self
            .history
            .iter()
            .flatten()
            .find(|f| {
                f.arm_generation == self.generation
                    && f.ordinal == ordinal
                    && f.surface == surface
                    && f.final_draw
            })
            .copied()
        else {
            return;
        };
        self.pixel_count = self.pixel_count.saturating_add(1);
        self.pixel = Some(PixelFact {
            frame,
            now,
            count,
            hash,
            format,
            width,
            height,
            flags,
            contract,
        });
    }
    fn blend_pixels(&mut self, ordinal: u64, surface: u64, samples: [[u8; 4]; 5]) {
        let Some(pixel) = self.pixel else { return };
        if pixel.frame.ordinal != ordinal
            || pixel.frame.surface != surface
            || pixel.frame.mask[0] & 0xc0000000 != 0xc0000000
            || !pixel.frame.final_draw
            || pixel.frame.demanded != [6, 6]
            || pixel
                .frame
                .sources
                .iter()
                .any(|s| s.is_none_or(|s| s.prefix < 6))
            || crate::stereo_bank_mask_v1::validate(pixel.frame.mask) != Ok(true)
            || ![0, 255].contains(&samples[2][2])
            || samples[4] != [77, 66, 1, 255]
            || samples.iter().any(|p| p[3] != 255)
        {
            return;
        }
        self.blend_strip = Some((ordinal, surface, samples));
    }
    fn blend_json(&self) -> String {
        fn frame(f: FrameFact) -> serde_json::Value {
            serde_json::json!({"ordinal":f.ordinal,"surface_generation":f.surface,
            "arm_generation":f.arm_generation,"control_revision":f.revision,"policy":f.policies,"mask_words":f.mask,
            "final_draw_retired":f.final_draw,"final_source_bank_abi_version":f.final_abi_version,
            "derived_primary_completed_prefix":f.primary_prefix,
            "derived_primary":if f.primary_prefix==6 {Some(serde_json::json!({"completed_prefix":f.primary_prefix,
                "control_revision":f.revision,"mask_words":f.mask,"source_keys":f.sources.map(|source|source.map(|s|serde_json::json!({
                    "process_generation":s.identity.epoch.process_generation,"source_generation":s.identity.epoch.source_generation,
                    "pair_sequence":s.identity.pair_sequence,"packed_pts_ns":s.identity.packed_pts_ns,
                    "left_timestamp_ns":s.identity.left_timestamp_ns,"right_timestamp_ns":s.identity.right_timestamp_ns,
                    "calibration_revision":s.identity.calibration_revision,"geometry_revision":s.geometry_revision,
                    "config_revision":s.config_revision,"content_serial":s.content_serial,"processing_policy":s.processing_codes}))),
                "scope":"recorded-derived-input-guide-prefix-same-original-source-fence-not-final-stack-pixels"}))}else{None},
            "effective_mask_state":if f.mask==[0;3]{"disabled"}else if f.final_abi_version==2 && f.primary_prefix!=6 {"closed-missing-primary"}else if f.final_draw&&f.sources.iter().all(|s|s.is_some_and(|s|s.prefix>=6)){"retired-both-current-banks"}else{"closed-missing-bank"},"banks":f.sources.map(|source|source.map(|s|serde_json::json!({
                "process_generation":s.identity.epoch.process_generation,"source_generation":s.identity.epoch.source_generation,
                "pair_sequence":s.identity.pair_sequence,"packed_pts_ns":s.identity.packed_pts_ns,"config_revision":s.config_revision,
                "content_serial":s.content_serial,"prefix":s.prefix})))})
        }
        let samples = self.pixel.and_then(|p| {
            self.blend_strip
                .filter(|(o, s, _)| *o == p.frame.ordinal && *s == p.frame.surface)
        });
        serde_json::json!({"schema":"rusty.quest.stereo.neutral_mask_readback.v1","version":1,
            "process_generation":self.process,"challenge_words":self.challenge.map(|word|word as i64),"arm_generation":self.generation,"armed":self.armed,
            "retired_frame":self.last.map(frame),"pixel_frame":self.pixel.map(|p|frame(p.frame)),
            "sample_status":if samples.is_some(){"available"}else{"unavailable"},
            "sample_rgba8":samples.map(|(_,_,p)|p),"pixel_format":self.pixel.map(|p|p.format),
            "sample_contract":"diagnostic-input-layer-same-submission-row0-Own-Peer-signal-mask-packing-output-marker-unorm-v1",
            "sample_uv_contract":"signal-blue255=bank-eye0-packed-uv0.25,0.5;blue0=mono-uv0.5,0.5",
            "scope":"camera-input-layer-not-final-stack-or-photons"}).to_string()
    }
    fn legacy_dropout_report(&self, now:u64)->serde_json::Value {
        serde_json::json!({"schema":"rusty.quest.stereo.dropout_observation.v1",
        "process_generation":self.process,"arm_generation":self.generation,"clock":"CLOCK_MONOTONIC",
        "sample_ns":now,"bound_ns":crate::spatial_stereo_dropouts::BOUND_NS,
        "series_order":["gpu_retirement","own_distinct_adoption","peer_distinct_adoption"],
        "counter_columns":crate::spatial_stereo_dropouts::COUNTER_COLUMNS.as_slice(),
        "bin_upper_ns":[500_000_000u64,1_000_000_000,2_000_000_000],
        "sample_columns":["previous_ns","current_ns","gap_ns","previous_epoch","previous_sequence","epoch","sequence"],
        "sample_retention":"first_two_and_latest_two; counters_complete",
        "gap_scope":"within_demand_and_source_epoch; closed_once_on_progress; open_and_censored_separate; no_first_wait_gap",
        "origin_demand_context_observed":self.recorded>0,
        "camera_frame_join":"unavailable","qualification_claimed":false,
        "series":self.dropout_progress.iter().map(|series|series.compact_snapshot(now)).collect::<Vec<_>>()})
    }
    fn dropout_json(&mut self,entry:u64,now:u64,exit:u64)->Option<String> {
        fn signed(value:&serde_json::Value)->bool {
            match value {
                serde_json::Value::Number(number)=>number.as_i64().is_some(),
                serde_json::Value::Array(values)=>values.iter().all(signed),
                serde_json::Value::Object(values)=>values.values().all(signed),
                _=>true,
            }
        }
        let report=self.dropout_report(entry,now,exit);
        if !signed(&report){return None;}
        let exact=report.to_string();
        if exact.len()>16384 {None}else{Some(exact)}
    }
    fn dropout_report(&mut self,entry:u64,now:u64,exit:u64)->serde_json::Value {
        self.freshness.observe_clock(Some(now));
        if entry>now||now>exit||exit>i64::MAX as u64 {self.freshness.clock_valid=false;}
        let words=self.words(Some(now),true,true,true);
        let indices=[16usize,51,55,56,25,26,27];
        let watermark_valid=indices.iter().all(|i|words[*i]>=0);
        let overflowed=self.freshness.overflowed()||!watermark_valid;
        serde_json::json!({"schema":"rusty.quest.stereo.dropout_observation.v2",
            "process_generation":self.process.min(i64::MAX as u64),"arm_generation":self.generation.min(i64::MAX as u64),
            "challenge_words":self.challenge.map(|v|v as i64),"clock":"CLOCK_MONOTONIC",
            "atomicity":"single_native_telemetry_mutex","bound_ns":crate::spatial_stereo_dropouts::BOUND_NS,
            "sample_entry_ns":entry.min(i64::MAX as u64),"sample_ns":now.min(i64::MAX as u64),"sample_exit_ns":exit.min(i64::MAX as u64),
            "sample_sequence":self.freshness.sample_sequence,"counters_complete":!overflowed,
            "overflowed":overflowed,"clock_valid":self.freshness.clock_valid,
            "state_valid":self.freshness.state_valid&&self.process<=i64::MAX as u64&&self.generation<=i64::MAX as u64,
            "lifetime":self.freshness.lifetime(),
            "native160_watermark":{"gpu_retired_count":words[16].max(0),"both_origin_retired_count":words[51].max(0),
                "own_distinct_count":words[55].max(0),"peer_distinct_count":words[56].max(0),
                "last_retired_ordinal":words[25].max(0),"last_retired_surface":words[26].max(0),"config_revision":words[27].max(0)},
            "legacy_v1":self.legacy_dropout_report(now),"series":self.freshness.snapshot(now)})
    }
    fn words(
        &self,
        now: Option<u64>,
        selected: bool,
        pipeline: bool,
        capture_claimed: bool,
    ) -> [i64; WORD_COUNT] {
        let mut w = [0i64; WORD_COUNT];
        let values = [
            1,
            WORD_COUNT as u64,
            self.process,
            self.challenge[0],
            self.challenge[1],
            self.generation,
            self.armed as u64,
            selected as u64,
            pipeline as u64,
            self.cleanup,
            self.first_recorded,
            self.last_recorded,
            self.recorded,
            self.entered,
            self.first_gpu,
            self.last_gpu,
            self.gpu,
            self.max_gap,
            self.pixel_count,
            self.pixel_unavailable,
            self.peer_removed_count,
            self.peer_removed_at,
            self.own_after_peer,
            self.origins[1].transitions,
            self.pending.map_or(0, |f| f.ordinal),
            self.last.map_or(0, |f| f.ordinal),
            self.last.map_or(0, |f| f.surface),
            self.last.map_or(0, |f| f.revision),
        ];
        for (i, value) in values.into_iter().enumerate() {
            w[i] = value as i64;
        }
        if let Some(f) = self.last {
            for i in 0..6 {
                w[28 + i] = f.policies[i] as i64;
            }
            let origin = f.policies[3] as usize;
            w[34] = origin as i64;
            if let Some(source) = f.sources.get(origin).copied().flatten() {
                w[35] = (f.final_draw && f.geometry_sampled && source.prefix >= 6) as i64;
                w[36] = source.identity.calibration_revision.is_some() as i64;
                w[37] = source.identity.calibration_revision.unwrap_or(0) as i64;
                w[38] = source.prefix as i64;
            }
        }
        if let Some(p) = self.pixel {
            let v = [
                p.frame.ordinal,
                p.frame.surface,
                p.now,
                p.count,
                p.hash,
                p.format as u64,
                p.width as u64,
                p.height as u64,
                p.flags as u64,
                p.contract as u64,
            ];
            for (i, value) in v.into_iter().enumerate() {
                w[39 + i] = value as i64;
            }
            for origin in 0..2 {
                if let Some(s) = p.frame.sources[origin] {
                    let b = 128 + origin * 5;
                    w[b] = 1;
                    w[b + 1] = s.identity.epoch.process_generation as i64;
                    w[b + 2] = s.identity.epoch.source_generation as i64;
                    w[b + 3] = s.identity.pair_sequence as i64;
                    w[b + 4] = s.prefix as i64;
                }
            }
            for i in 0..6 {
                w[138 + i] = p.frame.policies[i] as i64;
            }
            w[144] = p.frame.revision as i64;
            w[145] = p.frame.policies[3] as i64;
            w[146] = p.frame.geometry_sampled as i64;
            for origin in 0..2 {
                if let Some(s) = p.frame.sources[origin] {
                    w[147 + origin * 3] = s.identity.left_timestamp_ns;
                    w[148 + origin * 3] = s.identity.right_timestamp_ns;
                    w[149 + origin * 3] = s.identity.packed_pts_ns;
                }
            }
        }
        for (i, value) in [
            self.first_both,
            self.last_both,
            self.both,
            self.max_both_gap,
            now.is_some() as u64,
            now.unwrap_or(0),
            self.origins[0].distinct,
            self.origins[1].distinct,
            self.origins[0].max_gap,
            self.origins[1].max_gap,
            self.origins[0].max_age,
            self.origins[1].max_age,
            self.pending_since,
            self.armed_at,
        ]
        .into_iter()
        .enumerate()
        {
            w[49 + i] = value as i64;
        }
        w[63] = capture_claimed as i64;
        w[153] = self.foreign_enabled as i64;
        w[154] = self.capability_mask as i64;
        w[155] = self.sdk_session as i64;
        w[156] = self.carrier_live as i64;
        w[157] = self.pending.is_some() as i64;
        w[158] = self.last.map_or(0, |f| f.final_draw as i64);
        w[159] = self.pixel.is_some() as i64;
        for origin in 0..2 {
            let b = 64 + origin * 32;
            let stats = self.origins[origin];
            w[b + 1] = origin as i64;
            if let Some(s) = self.last.and_then(|f| f.sources[origin]) {
                let v = [
                    1,
                    origin as u64,
                    s.identity.epoch.process_generation,
                    s.identity.epoch.source_generation,
                    s.identity.pair_sequence,
                    s.identity.left_timestamp_ns as u64,
                    s.identity.right_timestamp_ns as u64,
                    s.identity.packed_pts_ns as u64,
                    s.identity.calibration_revision.is_some() as u64,
                    s.identity.calibration_revision.unwrap_or(0),
                    s.geometry_revision.is_some() as u64,
                    s.geometry_revision.unwrap_or(0),
                    s.config_revision,
                    s.prefix as u64,
                    s.observed_at_ns,
                    self.last_gpu,
                    self.last_gpu.saturating_sub(s.observed_at_ns),
                    s.content_serial,
                ];
                for (i, value) in v.into_iter().enumerate() {
                    w[b + i] = value as i64;
                }
                for i in 0..4 {
                    w[b + 27 + i] = s.processing_codes[i] as i64;
                }
            }
            let v = [
                stats.first,
                stats.last,
                stats.distinct,
                stats.max_gap,
                stats.missing,
                stats.transitions,
                stats.removed_at,
                stats.removals,
                self.last.map_or(0, |f| f.demanded[origin] as u64),
            ];
            for (i, value) in v.into_iter().enumerate() {
                w[b + 18 + i] = value as i64;
            }
            w[b + 31] = self.last.map_or(0, |f| f.available[origin] as i64);
        }
        w
    }
}
fn frame_epoch(fact:FrameFact)->[u64;5] {
    [fact.surface,fact.sources[0].map_or(0,|s|s.identity.epoch.process_generation),
        fact.sources[0].map_or(0,|s|s.identity.epoch.source_generation),
        fact.sources[1].map_or(0,|s|s.identity.epoch.process_generation),
        fact.sources[1].map_or(0,|s|s.identity.epoch.source_generation)]
}
/// Observers run in owner -> telemetry order and never change an owner's result.
pub(crate) fn completed_media_start(sequence:Option<u64>,original:Option<&str>,action:Option<&str>,activation:Option<&str>) {
    if let Ok(mut s)=STATE.lock(){let now=now_ns();s.freshness.completed_start(sequence.unwrap_or(0),original,action,activation,now);}
}
pub(crate) fn media_stop_requested(){if let Ok(mut s)=STATE.lock(){let now=now_ns();s.freshness.stop(now);}}
pub(crate) fn media_stopped(){if let Ok(mut s)=STATE.lock(){s.freshness.stopped();}}
static STATE: Mutex<State> = Mutex::new(State::empty());
#[cfg(target_os = "android")]
fn now_ns() -> Option<u64> {
    let mut value = libc::timespec {
        tv_sec: 0,
        tv_nsec: 0,
    };
    if unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut value) } != 0
        || value.tv_sec < 0
        || value.tv_nsec < 0
    {
        return None;
    }
    (value.tv_sec as u64)
        .checked_mul(1_000_000_000)?
        .checked_add(value.tv_nsec as u64)
}
#[cfg(not(target_os = "android"))]
fn now_ns() -> Option<u64> {
    None
}
pub(crate) fn arm_generation() -> u64 {
    STATE
        .lock()
        .map_or(0, |s| if s.armed { s.generation } else { 0 })
}
fn unavailable_event_clock() {
    if let Ok(mut s)=STATE.lock(){s.freshness.observe_clock(None);s.freshness.reject(true);}
}
pub(crate) fn record_frame(frame: FrameFact) {
    if let Some(now) = now_ns() {
        if let Ok(mut s) = STATE.lock() {
            let event_now=now_ns();
            s.record_timed(frame, now, event_now);
        }
    } else {
        unavailable_event_clock();
    }
}
pub(crate) fn observe_final_draw(geometry_sampled: bool) {
    if let Ok(mut s) = STATE.lock() {
        if let Some(f) = s.pending.as_mut() {
            f.final_draw = true;
            f.geometry_sampled = geometry_sampled;
        }
    }
}
pub(crate) fn observe_submission_entry() {
    if let Ok(mut s) = STATE.lock() {
        if s.armed && s.pending.is_some() {
            s.entered = s.entered.saturating_add(1);
        }
    }
}
pub(crate) fn gpu_retired(ordinal: u64, surface: u64) {
    if let Some(now) = now_ns() {
        if let Ok(mut s) = STATE.lock() {
            let event_now=now_ns();
            s.retire_timed(ordinal, surface, now, event_now);
        }
    } else {
        unavailable_event_clock();
    }
}
pub(crate) fn peer_removed(epoch: crate::stereo_input_set::SourceEpoch) {
    if let Some(now) = now_ns() {
        if let Ok(mut s) = STATE.lock() {
            if s.armed && epoch.process_generation == s.process {
                s.freshness.reject(true);
                s.peer_removed_at = now;
                s.peer_removed_count = s.peer_removed_count.saturating_add(1);
                s.origins[1].removed_at = now;
                s.origins[1].removals = s.origins[1].removals.saturating_add(1);
            }
        }
    } else {
        unavailable_event_clock();
    }
}
pub(crate) fn physical_cleanup_terminal() -> bool {
    STATE.lock().map_or(false, |s| {
        s.cleanup == 2 && !s.carrier_live && s.pending.is_none()
    })
}
pub(crate) fn carrier_live() {
    if let Some(now) = now_ns() {
        if let Ok(mut s) = STATE.lock() {
            s.carrier_live = true;
            if s.armed {
                s.dropout_progress[0].demand(true, now);
                if s.cleanup != 3 {
                    s.cleanup = 1;
                }
                s.pending_since = now;
            }
        }
    } else {
        unavailable_event_clock();
    }
}
pub(crate) fn carrier_device(foreign_enabled: bool, capability_mask: u64, sdk_session: u64) {
    if let Ok(mut s) = STATE.lock() {
        s.foreign_enabled = foreign_enabled;
        s.capability_mask = capability_mask;
        s.sdk_session = sdk_session;
    }
}
pub(crate) fn carrier_cleanup(terminal: bool) {
    if let Ok(mut s) = STATE.lock() {
        s.freshness.reject(true);
        s.carrier_live = false;
        if let Some(now) = now_ns() {
            for series in &mut s.dropout_progress {
                series.demand(false, now);
            }
        } else {s.freshness.observe_clock(None);}
        if !terminal || s.cleanup == 3 {
            s.cleanup = 3;
        } else {
            s.cleanup = 2;
        }
    }
}
pub(crate) fn blend_oracle_requested(ordinal: u64, surface: u64) -> bool {
    STATE.lock().is_ok_and(|s| {
        s.pending.is_some_and(|f| {
            f.ordinal == ordinal && f.surface == surface && f.mask[0] & 0xc0000000 == 0xc0000000
        })
    })
}
pub(crate) fn blend_readback_complete(ordinal: u64, surface: u64, samples: [[u8; 4]; 5]) {
    if let Ok(mut s) = STATE.lock() {
        s.blend_pixels(ordinal, surface, samples);
    }
}
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_StereoBankControls_nativeReadMaskReadback(
    env: jni::JNIEnv<'_>,
    _: jni::objects::JClass<'_>,
) -> jni::sys::jstring {
    let Ok(s) = STATE.lock() else {
        return std::ptr::null_mut();
    };
    match env.new_string(s.blend_json()) {
        Ok(v) => v.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
pub(crate) fn readback_complete(
    ordinal: u64,
    surface: u64,
    count: u64,
    hash: u64,
    format: u32,
    width: u32,
    height: u32,
    flags: u32,
    contract: u32,
) {
    if let Some(now) = now_ns() {
        if let Ok(mut s) = STATE.lock() {
            s.pixel(
                ordinal, surface, now, count, hash, format, width, height, flags, contract,
            );
        }
    }
}
pub(crate) fn readback_unavailable() {
    if let Ok(mut s) = STATE.lock() {
        if s.armed {
            s.pixel_unavailable = s.pixel_unavailable.saturating_add(1);
        }
    }
}
pub(crate) fn take_requested_readback() -> bool {
    STATE.lock().map_or(false, |mut s| {
        let requested = s.armed && s.readback_requested;
        s.readback_requested = false;
        requested
    })
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_StereoBankControls_nativeRequestConcurrentStereoReadback(
    _: jni::JNIEnv<'_>,
    _: jni::objects::JClass<'_>,
    hi: jni::sys::jlong,
    lo: jni::sys::jlong,
    generation: jni::sys::jlong,
) -> jni::sys::jboolean {
    if !crate::own_stereo_capture_runtime::capture_route_selected() {
        return 0;
    }
    let Ok(process) = crate::own_packed_pool_jni::process_generation() else {
        return 0;
    };
    STATE.lock().map_or(0, |mut s| {
        if !s.armed
            || s.process != process
            || s.challenge != [hi as u64, lo as u64]
            || s.generation != generation as u64
        {
            return 0;
        }
        s.readback_requested = true;
        1
    })
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_StereoBankControls_nativeArmConcurrentStereoQualification(
    _: jni::JNIEnv<'_>,
    _: jni::objects::JClass<'_>,
    hi: jni::sys::jlong,
    lo: jni::sys::jlong,
) -> jni::sys::jlong {
    if !crate::own_stereo_capture_runtime::capture_route_selected() || (hi == 0 && lo == 0) {
        return 0;
    }
    let (Ok(process), Some(now)) = (crate::own_packed_pool_jni::process_generation(), now_ns())
    else {
        return 0;
    };
    STATE.lock().map_or(0, |mut s| {
        s.arm(process, [hi as u64, lo as u64], now) as i64
    })
}
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_StereoBankControls_nativeDisarmConcurrentStereoQualification(
    _: jni::JNIEnv<'_>,
    _: jni::objects::JClass<'_>,
    hi: jni::sys::jlong,
    lo: jni::sys::jlong,
    generation: jni::sys::jlong,
) -> jni::sys::jboolean {
    let Ok(process) = crate::own_packed_pool_jni::process_generation() else {
        return 0;
    };
    STATE.lock().map_or(0, |mut s| {
        if !s.armed
            || s.process != process
            || s.challenge != [hi as u64, lo as u64]
            || s.generation != generation as u64
        {
            return 0;
        }
        if let Some(now) = now_ns() {
            for series in &mut s.dropout_progress {
                series.demand(false, now);
            }
        } else {s.freshness.observe_clock(None);}
        s.freshness.reject(true);
        s.armed = false;
        1
    })
}

/// Debug report only. Existing 160 qualification words and their acceptance
/// meaning are unchanged. Serial/app/challenge/arm checks precede export.
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_StereoBankControls_nativeReadConcurrentStereoDropouts(
    mut env: jni::JNIEnv<'_>,
    _: jni::objects::JClass<'_>,
    hi: jni::sys::jlong,
    lo: jni::sys::jlong,
    generation: jni::sys::jlong,
) -> jni::sys::jstring {
    let (Ok(process), Some(entry)) = (crate::own_packed_pool_jni::process_generation(), now_ns())
    else {
        return std::ptr::null_mut();
    };
    let Ok(mut s) = STATE.lock() else {
        return std::ptr::null_mut();
    };
    if !s.armed
        || s.process != process
        || s.challenge != [hi as u64, lo as u64]
        || s.generation != generation as u64
    {
        return std::ptr::null_mut();
    }
    let Some(now) = now_ns() else {return std::ptr::null_mut();};
    let Some(exit) = now_ns() else {return std::ptr::null_mut();};
    let exact = s.dropout_json(entry, now, exit);
    drop(s);
    let Some(exact)=exact else {return std::ptr::null_mut();};
    env.new_string(exact)
        .map_or(std::ptr::null_mut(), |value| value.into_raw())
}
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_spatial_1camera_1panel_StereoBankControls_nativeReadConcurrentStereoQualification(
    mut env: jni::JNIEnv<'_>,
    _: jni::objects::JClass<'_>,
) -> jni::sys::jlongArray {
    let selected = crate::own_stereo_capture_runtime::capture_route_selected();
    let mut words = STATE.lock().map_or([0; WORD_COUNT], |s| {
        s.words(
            now_ns(),
            selected,
            crate::spatial_public_multistack_runtime::source_banks_enabled(),
            crate::own_stereo_capture_runtime::capture_claimed(),
        )
    });
    #[cfg(rq_environment_depth_spatial_sdk_api_layer)]
    {
        let binding = crate::spatial_sdk_depth_handoff::spatial_depth_device_binding();
        let current = binding
            .filter(|binding| binding.session_generation == words[155] as u64 && words[156] != 0);
        words[153] = current.as_ref().map_or(0, |binding| {
            (binding.enabled_capability_mask
                & crate::spatial_sdk_depth_handoff::SPATIAL_DEPTH_CAP_FOREIGN_QUEUE_OWNERSHIP_V2
                != 0) as i64
        });
        words[154] = current
            .as_ref()
            .map_or(0, |binding| binding.enabled_capability_mask as i64);
    }
    match env.new_long_array(WORD_COUNT as i32) {
        Ok(array) => {
            if env.set_long_array_region(&array, 0, &words).is_err() {
                return std::ptr::null_mut();
            }
            array.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::stereo_input_set::SourceEpoch;
    #[test]
    fn finite_serializer_refuses_unsafe_legacy_integer_without_changing_legacy() {
        let mut s=State::empty();s.arm(7,[1,2],1);s.process=u64::MAX;
        assert_eq!(s.words(Some(10),true,true,true)[2],-1);
        assert!(s.dropout_json(8,9,10).is_none());
    }
    #[test]
    fn finite_actual_clean_pair_is_available_for_host_comparator_interop() {
        let mut s=State::empty();let generation=s.arm(7,[1,2],1);
        s.freshness.completed_start(1,Some("00000000000000000000000000000001"),Some("platform.actual.action.1"),Some("activation.actual.1"),Some(10));
        let mut first=frame(generation,1,Some(source(2,1)));for source in first.sources.iter_mut().flatten(){source.observed_at_ns=20;}
        s.record(first,20);s.retire(1,8,30);let a=s.dropout_report(40,50,60);
        let mut rpc_before=frame(generation,2,Some(source(2,2)));for source in rpc_before.sources.iter_mut().flatten(){source.observed_at_ns=62;}
        s.record(rpc_before,62);s.retire(2,8,65);let fa=s.words(Some(70),true,true,true);
        let mut second=frame(generation,3,Some(source(2,3)));for source in second.sources.iter_mut().flatten(){source.observed_at_ns=80;}
        s.record(second,80);s.retire(3,8,90);let fb=s.words(Some(100),true,true,true);
        let mut rpc_after=frame(generation,4,Some(source(2,4)));for source in rpc_after.sources.iter_mut().flatten(){source.observed_at_ns=102;}
        s.record(rpc_after,102);s.retire(4,8,105);let b=s.dropout_report(110,120,130);
        assert_eq!(a["native160_watermark"]["gpu_retired_count"],1);assert_eq!(b["native160_watermark"]["gpu_retired_count"],4);
        assert_eq!((fa[16],fb[16]),(2,3));
        assert_eq!(a["state_valid"],true);assert_eq!(b["state_valid"],true);
        println!("FINITE_CLEAN_NATIVE_PAIR {}",serde_json::json!({"D_A":a,"FRAME_A":fa.as_slice(),"FRAME_B":fb.as_slice(),"D_B":b}));
    }
    #[test]
    fn finite_actual_maximum_serializer_fits_complete_bridge_body() {
        let mut s=State::empty();s.arm(7,[u64::MAX,u64::MAX],1);
        let (f,old)=crate::spatial_stereo_dropouts::finite_tests::maximum_fixture();s.freshness=f;s.dropout_progress=old;
        s.process=i64::MAX as u64;s.generation=i64::MAX as u64;s.gpu=i64::MAX as u64;s.both=i64::MAX as u64;
        s.origins[0].distinct=i64::MAX as u64;s.origins[1].distinct=i64::MAX as u64;
        let mut last=frame(s.generation,1,Some(source(2,1)));last.ordinal=i64::MAX as u64;last.surface=i64::MAX as u64;last.revision=i64::MAX as u64;s.last=Some(last);
        let raw=s.dropout_json(i64::MAX as u64,i64::MAX as u64,i64::MAX as u64).unwrap();
        assert!(raw.len()<=16384,"maximum production JSON bytes={}",raw.len());
        assert_eq!(serde_json::from_str::<serde_json::Value>(&raw).unwrap()["overflowed"],true);
        println!("FINITE_MAX_NATIVE_JSON {}",raw);
    }
    fn start_v2(s: &mut State, sequence:u64, now:u64) {
        s.freshness.completed_start(sequence,Some("original"),Some("action"),Some("activation"),Some(now));
    }
    #[test]
    #[cfg(not(target_os="android"))]
    fn finite_actual_record_and_retire_wrappers_latch_missing_initial_clock() {
        // The host clock leaf is intentionally unavailable. Exercise the real
        // wrappers, including the first pre-lock legacy clock read.
        for retirement in [false,true] {
            let mut local=State::empty();let generation=local.arm(7,[1,2],1);start_v2(&mut local,1,10);
            let previous={let mut state=STATE.lock().unwrap();std::mem::replace(&mut *state,local)};
            if retirement {gpu_retired(1,8);}else{record_frame(frame(generation,1,Some(source(2,1))));}
            let (clock,state_valid)={let mut state=STATE.lock().unwrap();let flags=(state.freshness.clock_valid,state.freshness.state_valid);*state=previous;flags};
            assert!(!clock);assert!(!state_valid);
        }
    }
    #[test]
    fn finite_pending_stamp_rejects_pre_start_retirement_without_changing_native160() {
        let mut s=State::empty();let generation=s.arm(7,[1,2],1);
        s.record(frame(generation,1,Some(source(2,1))),20);
        start_v2(&mut s,1,25);s.retire(1,8,30);
        assert_eq!(s.words(Some(30),true,true,true)[16],1);
        assert!(!s.freshness.state_valid);
        assert_eq!(s.freshness.snapshot(30)[0]["progress_count"],0);
        s.record(frame(generation,2,Some(source(2,2))),40);s.retire(2,8,50);
        assert!(!s.freshness.state_valid);
        assert_eq!(s.freshness.snapshot(50)[0]["rejected_observation_count"],1);
    }
    #[test]
    fn finite_under_lock_event_time_follows_snapshot_not_pre_lock_time() {
        let mut s=State::empty();let generation=s.arm(7,[1,2],1);start_v2(&mut s,1,10);
        s.freshness.observe_clock(Some(100));
        s.record_timed(frame(generation,1,Some(source(2,1))),20,Some(110));
        s.retire_timed(1,8,30,Some(120));
        assert_eq!(s.freshness.snapshot(130)[0]["last_progress_ns"],120);
        assert_eq!(s.words(Some(130),true,true,true)[15],30);
        assert!(s.freshness.clock_valid);
        s.freshness.observe_clock(Some(140));
        s.record_timed(frame(generation,2,Some(source(2,2))),40,Some(50));
        s.retire_timed(2,8,50,Some(150));
        assert!(!s.freshness.clock_valid);
    }
    #[test]
    fn finite_four_event_series_exactly_match_legacy_watermarks_and_held_sources() {
        let mut s=State::empty();let generation=s.arm(7,[u64::MAX,2],1);start_v2(&mut s,1,10);
        for n in 1..=3 {let mut f=frame(generation,n,Some(source(2,1)));f.sources[0]=Some(source(1,1));s.record(f,n*20);s.retire(n,8,n*20+10);}
        let report=s.dropout_report(70,80,90);
        assert_eq!(report["series"][0]["progress_count"],3);
        assert_eq!(report["series"][1]["progress_count"],1);
        assert_eq!(report["series"][2]["progress_count"],1);
        assert_eq!(report["series"][3]["progress_count"],3);
        assert_eq!(report["native160_watermark"]["both_origin_retired_count"],3);
        assert_eq!(report["challenge_words"][0],-1);
        assert_eq!(report["series"][1]["source_age_observation_count"],1);
        assert_eq!(report["series"][3]["source_age_observation_count"],3);
        assert_eq!(report["legacy_v1"],s.legacy_dropout_report(80));
        assert_eq!(report.as_object().unwrap().len(),19);
        assert_eq!(report["series"][0].as_object().unwrap().len(),25);
        assert!(report.to_string().len()<=16384);
    }
    #[test]
    fn finite_new_lifetime_is_clean_while_old_arm_maxima_and_stopped_time_remain() {
        let mut s=State::empty();s.carrier_live=true;let generation=s.arm(7,[1,2],1);start_v2(&mut s,1,10);
        for (n,now) in [(1,20),(2,1_000_000_020)] {
            let mut f=frame(generation,n,Some(source(2,n)));for source in f.sources.iter_mut().flatten(){source.observed_at_ns=now;}
            s.record(f,now);s.retire(n,8,now+10);
        }
        assert_eq!(s.freshness.snapshot(1_000_000_030)[0]["gap_over_bound_count"],1);
        s.freshness.stop(Some(1_000_000_040));s.freshness.stopped();
        s.freshness.completed_start(2,Some("original.2"),Some("action.2"),Some("activation.2"),Some(8_000_000_000));
        for (n,now) in [(3,8_000_000_010),(4,8_020_000_010)] {
            let mut f=frame(generation,n,Some(source(2,n)));for source in f.sources.iter_mut().flatten(){source.observed_at_ns=now;}
            s.record(f,now);s.retire(n,8,now+10);
        }
        let report=s.dropout_report(8_020_000_030,8_020_000_040,8_020_000_050);
        assert!(s.words(Some(8_020_000_040),true,true,true)[17]>500_000_000);
        assert_eq!(report["lifetime"]["sequence"],2);assert_eq!(report["state_valid"],true);
        for row in report["series"].as_array().unwrap(){
            assert_eq!(row["progress_count"],2);assert_eq!(row["gap_over_bound_count"],0);
            assert_eq!(row["first_wait_over_bound_count"],0);assert_eq!(row["source_age_over_bound_count"],0);
        }
        assert_eq!(report["native160_watermark"]["gpu_retired_count"],4);
    }
    #[test]
    fn finite_native160_and_v1_outputs_equal_with_observer_active_or_unavailable() {
        let mut old=State::empty();let mut new=State::empty();
        old.arm(7,[1,2],1);new.arm(7,[1,2],1);start_v2(&mut new,1,10);
        for n in 1..=12 {let f=frame(1,n,Some(source(2,n)));old.record(f,n*600_000_000);new.record(f,n*600_000_000);old.retire(n,8,n*600_000_000+1);new.retire(n,8,n*600_000_000+1);}
        assert_eq!(old.words(Some(8_000_000_000),true,true,true),new.words(Some(8_000_000_000),true,true,true));
        assert_eq!(old.legacy_dropout_report(8_000_000_000),new.legacy_dropout_report(8_000_000_000));
    }
    #[test]
    fn mask_challenge_json_preserves_signed_jni_words_and_all_nonce_bits() {
        // Challenges are two raw JNI long bit words, not nonnegative counters.
        // Android JSON parses integers outside Long's range through Double.
        let cases = [
            [0, 1],
            [0, u64::MAX],
            [u64::MAX, 0],
            [u64::MAX, u64::MAX],
            [i64::MAX as u64, 1u64 << 63],
            [1u64 << 63, i64::MAX as u64],
            [0x75c4476021caf74e, 0xd946dd73d62a05cc],
        ];
        for challenge in cases {
            let mut s = State::empty();
            s.arm(7, challenge, 10);
            let raw = s.blend_json();
            let report: serde_json::Value = serde_json::from_str(&raw).unwrap();
            let words = s.words(Some(20), true, true, true);
            for index in 0..2 {
                let signed = report["challenge_words"][index].as_i64().unwrap();
                assert_eq!(signed, words[3 + index]);
                assert_eq!(signed as u64, challenge[index]);
                assert_ne!((signed ^ 1) as u64, challenge[index]);
            }
            assert_eq!(s.challenge, challenge);
            assert_eq!(report["process_generation"], 7);
            assert_eq!(report["arm_generation"], 1);
            assert_eq!(report["sample_status"], "unavailable");
            // Actual producer bytes consumed by the independent Android JSON test.
            println!(
                "MASK_CHALLENGE_WIRE_CASE {}",
                serde_json::json!({"expected":challenge.map(|word|word as i64),"report":report})
            );
        }
    }
    fn source(epoch: u64, sequence: u64) -> SourceFact {
        SourceFact {
            identity: StereoFrameIdentity {
                epoch: SourceEpoch {
                    process_generation: 7,
                    source_generation: epoch,
                },
                pair_sequence: sequence,
                left_timestamp_ns: sequence as i64 * 10,
                right_timestamp_ns: sequence as i64 * 10 + 1,
                packed_pts_ns: sequence as i64 * 20,
                calibration_revision: None,
            },
            geometry_revision: None,
            config_revision: 3,
            prefix: 6,
            observed_at_ns: 10,
            content_serial: sequence,
            processing_codes: [0; 4],
        }
    }
    fn frame(generation: u64, ordinal: u64, peer: Option<SourceFact>) -> FrameFact {
        FrameFact {
            arm_generation: generation,
            ordinal,
            surface: 8,
            revision: 3,
            policies: [0, 1, 0, 0, 2, 2],
            mask: [0; 3],
            sources: [Some(source(1, ordinal)), peer],
            demanded: [6, 6],
            final_draw: true,
            geometry_sampled: false,
            available: [true, peer.is_some()],
            primary_prefix: 0,
            final_abi_version: 1,
        }
    }
    #[test]
    fn primary_provenance_requires_same_complete_originals_and_actual_retirement() {
        let mut s = State::empty();
        let generation = s.arm(7, [1, 2], 10);
        let mut f = frame(generation, 1, Some(source(2, 1)));
        f.final_abi_version = 2;
        f.primary_prefix = 6;
        f.mask = crate::stereo_bank_mask_v1::pack(true, 0.5, 0.05, 1.0, true, false).unwrap();
        for source in f.sources.iter_mut().flatten() {
            source.config_revision = f.revision;
        }
        let mut bad = f;
        bad.sources[1] = None;
        s.record(bad, 20);
        assert!(s.pending.is_none());
        bad = f;
        bad.sources[1].as_mut().unwrap().config_revision += 1;
        s.record(bad, 20);
        assert!(s.pending.is_none());
        bad = f;
        bad.sources[1].as_mut().unwrap().processing_codes[0] = 1;
        s.record(bad, 20);
        assert!(s.pending.is_none());
        bad = f;
        bad.mask = [0; 3];
        s.record(bad, 20);
        assert!(s.pending.is_none());
        bad = f;
        bad.mask = [u32::MAX; 3];
        s.record(bad, 20);
        assert!(s.pending.is_none());
        s.record(f, 20);
        let before: serde_json::Value = serde_json::from_str(&s.blend_json()).unwrap();
        assert!(before["retired_frame"].is_null());
        s.retire(1, 8, 30);
        let j: serde_json::Value = serde_json::from_str(&s.blend_json()).unwrap();
        assert_eq!(j["retired_frame"]["derived_primary_completed_prefix"], 6);
        assert_eq!(
            j["retired_frame"]["derived_primary"]["mask_words"][0],
            f.mask[0]
        );
        assert_eq!(
            j["retired_frame"]["derived_primary"]["source_keys"][1]["source_generation"],
            2
        );
        assert_eq!(j["sample_status"], "unavailable");
        assert!(j["sample_rgba8"].is_null());
    }
    #[test]
    fn mask_strip_requires_actual_retired_frame_pixel_identity_and_exact_marker() {
        let mut s = State::empty();
        let a = s.arm(7, [1, 2], 10);
        let mut f = frame(a, 1, Some(source(2, 1)));
        f.mask = crate::stereo_bank_mask_v1::pack(true, 0.5, 0.1, 1.0, false, true).unwrap();
        let samples = [
            [255, 255, 255, 255],
            [0, 0, 0, 255],
            [255, 255, 0, 255],
            [0, 0, 0, 255],
            [77, 66, 1, 255],
        ];
        s.record(f, 20);
        s.blend_pixels(1, 8, samples);
        assert!(s.blend_strip.is_none());
        s.retire(1, 8, 30);
        s.blend_pixels(1, 8, samples);
        assert!(s.blend_strip.is_none());
        s.pixel(1, 8, 31, 5, 123, 1, 16, 16, 0, 1);
        s.blend_pixels(2, 8, samples);
        assert!(s.blend_strip.is_none());
        let mut damaged = samples;
        damaged[4][2] = 2;
        s.blend_pixels(1, 8, damaged);
        assert!(s.blend_strip.is_none());
        s.blend_pixels(1, 8, samples);
        let j: serde_json::Value = serde_json::from_str(&s.blend_json()).unwrap();
        assert_eq!(j["sample_status"], "available");
        assert_eq!(j["pixel_frame"]["mask_words"][0], f.mask[0]);
        assert_eq!(j["pixel_frame"]["banks"][1]["source_generation"], 2);
        assert_eq!(s.words(Some(40), true, true, true).len(), 160);
        s.arm(7, [3, 4], 41);
        assert!(s.blend_strip.is_none());
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&s.blend_json()).unwrap()["sample_status"],
            "unavailable"
        );
    }
    #[test]
    fn partial_mask_banks_do_not_claim_sample_proof() {
        let mut s = State::empty();
        let a = s.arm(7, [1, 2], 10);
        let mut f = frame(a, 1, None);
        f.mask = crate::stereo_bank_mask_v1::pack(true, 0.5, 0.1, 1.0, false, true).unwrap();
        s.record(f, 20);
        s.retire(1, 8, 30);
        s.pixel(1, 8, 31, 5, 123, 1, 16, 16, 0, 1);
        s.blend_pixels(1, 8, [[77, 66, 1, 255]; 5]);
        assert!(s.blend_strip.is_none());
    }
    #[test]
    fn submit_and_recording_do_not_create_gpu_or_pixel_evidence() {
        let mut s = State::empty();
        let a = s.arm(7, [1, 2], 10);
        s.record(frame(a, 1, Some(source(2, 1))), 20);
        let w = s.words(Some(30), true, true, true);
        assert_eq!(w[12], 1);
        assert_eq!(w[16], 0);
        assert_eq!(w[18], 0);
        assert_eq!(w[9], 1);
    }
    #[test]
    fn exact_retirement_and_readback_identity_are_required() {
        let mut s = State::empty();
        let a = s.arm(7, [1, 2], 10);
        s.record(frame(a, 1, Some(source(2, 1))), 20);
        s.retire(2, 8, 30);
        assert_eq!(s.gpu, 0);
        s.retire(1, 8, 30);
        s.pixel(1, 9, 31, 4, 5, 1, 2, 2, 0, 1);
        assert_eq!(s.pixel_count, 0);
        s.pixel(1, 8, 31, 4, 5, 1, 2, 2, 0, 1);
        assert_eq!(s.pixel_count, 1);
        let w = s.words(Some(40), true, true, true);
        assert_eq!(w[39], 1);
        assert_eq!(w[132], 6);
        assert_eq!(w[145], 0);
        assert_eq!(w[35], 0);
    }
    #[test]
    fn peer_restart_and_own_after_removal_preserve_epoch_facts() {
        let mut s = State::empty();
        let a = s.arm(7, [1, 2], 10);
        s.record(frame(a, 1, Some(source(2, 1))), 20);
        s.retire(1, 8, 30);
        s.peer_removed_at = 35;
        s.record(frame(a, 2, None), 40);
        s.retire(2, 8, 50);
        assert_eq!(s.own_after_peer, 1);
        s.record(frame(a, 3, Some(source(3, 1))), 60);
        s.retire(3, 8, 70);
        assert_eq!(s.origins[1].transitions, 1);
        assert_eq!(s.origins[1].max_gap, 40);
    }
    #[test]
    fn arm_rejects_old_inflight_generation_and_history_is_bounded() {
        let mut s = State::empty();
        let old = s.arm(7, [1, 2], 10);
        let a = s.arm(7, [3, 4], 20);
        s.record(frame(old, 1, None), 30);
        assert_eq!(s.recorded, 0);
        for ordinal in 1..=100 {
            s.record(frame(a, ordinal, None), ordinal * 10 + 30);
            s.retire(ordinal, 8, ordinal * 10 + 31);
        }
        assert_eq!(s.history.iter().flatten().count(), HISTORY);
        s.pixel(1, 8, 1101, 1, 1, 1, 1, 1, 0, 1);
        assert_eq!(s.pixel_count, 0);
        assert_eq!(s.gpu, 100);
    }
    #[test]
    fn rearm_cannot_clear_physical_quarantine_and_disarm_does_not_retire_gpu() {
        let mut s = State::empty();
        s.cleanup = 3;
        let a = s.arm(7, [1, 2], 10);
        assert_eq!(s.cleanup, 3);
        s.record(frame(a, 1, None), 20);
        s.armed = false;
        assert!(s.pending.is_some());
        assert_eq!(s.cleanup, 3);
        s.retire(1, 8, 30);
        assert_eq!(s.gpu, 0);
        assert!(s.pending.is_none());
        assert_eq!(s.cleanup, 3);
    }
}

/// Cancel only the exact typed never-submitted SDK frame. No GPU or rendered counters advance.
#[cfg(any(rq_environment_depth_spatial_sdk_api_layer, test))]
pub(crate) fn cancel_sdk_unsubmitted(
    ordinal: u64,
    surface: u64,
    proof: &crate::spatial_sdk_depth_handoff::SpatialUnsubmittedProof,
) {
    if ordinal == 0 || ordinal > u32::MAX as u64 || surface == 0 || surface > u32::MAX as u64 {
        return;
    }
    if let Ok(mut s) = STATE.lock() {
        if s.pending
            .is_some_and(|f| f.ordinal == ordinal && f.surface == surface)
            && proof.matches_request(s.sdk_session, (surface << 32) | ordinal)
        {
            s.pending = None;
        }
    }
}

pub(crate) struct RetirementOwnerFacts {
    pub cleanup: u64,
    pub carrier_live: bool,
    pub pending_frame: bool,
}
pub(crate) fn retirement_owner_facts() -> Result<RetirementOwnerFacts, String> {
    let state = STATE
        .lock()
        .map_err(|_| "renderer qualification owner poisoned")?;
    Ok(RetirementOwnerFacts {
        cleanup: state.cleanup,
        carrier_live: state.carrier_live,
        pending_frame: state.pending.is_some(),
    })
}
