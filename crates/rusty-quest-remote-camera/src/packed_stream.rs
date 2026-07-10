//! RMANVID v4 packed-stereo metadata and bounded binary pair extension.
//!
//! This module is data-only. Android camera, codec, socket, and GPU adapters
//! consume the contract but remain outside this crate.

use std::collections::BTreeSet;

use serde::{Deserialize, Serialize};

use crate::model::{
    ValidationError, FRAME_LAYOUT_SIDE_BY_SIDE_LEFT_RIGHT,
    PAIRING_POLICY_NEAREST_TIMESTAMP_BOUNDED, PAIR_TIMESTAMP_CAMERA2_SENSOR,
};

/// RMANVID schema selected by packed-stereo streams.
pub const RMANVID_PACKED_STEREO_SCHEMA_VERSION: u32 = 4;

/// Packed stream metadata schema id.
pub const PACKED_STEREO_STREAM_METADATA_SCHEMA: &str =
    "rusty.quest.remote_camera.packed_stereo_stream_metadata.v1";

/// Fixed pair extension length for every RMANVID v4 packet.
pub const PACKED_STEREO_PAIR_EXTENSION_BYTES: usize = 48;

/// Validated RMANVID v4 stream-header metadata.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PackedStereoStreamMetadata {
    /// Metadata schema id.
    pub schema: String,
    /// RMANVID record schema selected by the stream header.
    pub rmanvid_schema_version: u32,
    /// Packed raster layout token.
    pub frame_layout: String,
    /// Ordered eye regions in the raster.
    pub eye_order: Vec<String>,
    /// Full packed width.
    pub packed_width: u32,
    /// Full packed height.
    pub packed_height: u32,
    /// Width of one eye region.
    pub per_eye_width: u32,
    /// Height of one eye region.
    pub per_eye_height: u32,
    /// Camera2 id for the left region.
    pub left_camera_id: String,
    /// Camera2 id for the right region.
    pub right_camera_id: String,
    /// Physical source timestamp authority.
    pub pair_timestamp_source: String,
    /// Source-frame pairing policy.
    pub pairing_policy: String,
    /// Maximum accepted absolute source timestamp skew.
    pub max_pair_delta_ns: u64,
    /// Whether a CPU pixel-copy path produced the packed raster.
    pub cpu_pixel_copy: bool,
}

/// Fixed-width per-video-packet source-pair evidence.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct PackedStereoPairMetadata {
    /// Monotonic accepted pair id.
    pub stereo_pair_id: u64,
    /// Monotonic left source capture frame id.
    pub left_source_frame: u64,
    /// Monotonic right source capture frame id.
    pub right_source_frame: u64,
    /// Left Camera2 sensor timestamp.
    pub left_sensor_timestamp_ns: i64,
    /// Right Camera2 sensor timestamp.
    pub right_sensor_timestamp_ns: i64,
    /// Absolute left/right timestamp delta.
    pub pair_delta_ns: u64,
}

impl PackedStereoPairMetadata {
    /// Documented not-applicable shape carried by codec-config packets.
    pub const CODEC_CONFIG_NOT_APPLICABLE: Self = Self {
        stereo_pair_id: 0,
        left_source_frame: 0,
        right_source_frame: 0,
        left_sensor_timestamp_ns: 0,
        right_sensor_timestamp_ns: 0,
        pair_delta_ns: 0,
    };
}

/// Validate stream-header metadata before decoder/render adoption.
pub fn validate_packed_stream_metadata(
    metadata: &PackedStereoStreamMetadata,
    header_width: u32,
    header_height: u32,
) -> Result<(), Vec<ValidationError>> {
    let mut errors = Vec::new();
    if metadata.schema != PACKED_STEREO_STREAM_METADATA_SCHEMA {
        errors.push(ValidationError::new(format!(
            "unsupported packed stream metadata schema {}",
            metadata.schema
        )));
    }
    if metadata.rmanvid_schema_version != RMANVID_PACKED_STEREO_SCHEMA_VERSION {
        errors.push(ValidationError::new(format!(
            "packed stream requires RMANVID schema version {}",
            RMANVID_PACKED_STEREO_SCHEMA_VERSION
        )));
    }
    if metadata.frame_layout != FRAME_LAYOUT_SIDE_BY_SIDE_LEFT_RIGHT {
        errors.push(ValidationError::new(
            "packed stream frame_layout must be side_by_side_left_right",
        ));
    }
    if metadata.eye_order != ["left", "right"] {
        errors.push(ValidationError::new(
            "packed stream eye_order must be exactly left then right",
        ));
    }
    if metadata.per_eye_width == 0
        || metadata.per_eye_height == 0
        || metadata.packed_width != metadata.per_eye_width.saturating_mul(2)
        || metadata.packed_height != metadata.per_eye_height
        || header_width != metadata.packed_width
        || header_height != metadata.packed_height
    {
        errors.push(ValidationError::new(
            "packed stream dimensions disagree with per-eye or RMANVID header dimensions",
        ));
    }
    if metadata.left_camera_id.trim().is_empty()
        || metadata.right_camera_id.trim().is_empty()
        || metadata.left_camera_id == metadata.right_camera_id
    {
        errors.push(ValidationError::new(
            "packed stream requires distinct left and right camera ids",
        ));
    }
    if metadata.pair_timestamp_source != PAIR_TIMESTAMP_CAMERA2_SENSOR {
        errors.push(ValidationError::new(
            "packed stream pair timestamp source must be camera2_sensor_timestamp",
        ));
    }
    if metadata.pairing_policy != PAIRING_POLICY_NEAREST_TIMESTAMP_BOUNDED {
        errors.push(ValidationError::new(
            "packed stream pairing policy must be nearest_timestamp_bounded",
        ));
    }
    if metadata.max_pair_delta_ns == 0 || metadata.max_pair_delta_ns > 1_000_000_000 {
        errors.push(ValidationError::new(
            "packed stream max_pair_delta_ns must be 1..=1000000000",
        ));
    }
    if metadata.cpu_pixel_copy {
        errors.push(ValidationError::new(
            "packed stream promotion metadata requires cpu_pixel_copy=false",
        ));
    }
    if errors.is_empty() {
        Ok(())
    } else {
        Err(errors)
    }
}

/// Encode the fixed pair extension in network byte order.
pub fn encode_packed_pair_metadata(
    pair: PackedStereoPairMetadata,
) -> [u8; PACKED_STEREO_PAIR_EXTENSION_BYTES] {
    let mut bytes = [0u8; PACKED_STEREO_PAIR_EXTENSION_BYTES];
    bytes[0..8].copy_from_slice(&pair.stereo_pair_id.to_be_bytes());
    bytes[8..16].copy_from_slice(&pair.left_source_frame.to_be_bytes());
    bytes[16..24].copy_from_slice(&pair.right_source_frame.to_be_bytes());
    bytes[24..32].copy_from_slice(&pair.left_sensor_timestamp_ns.to_be_bytes());
    bytes[32..40].copy_from_slice(&pair.right_sensor_timestamp_ns.to_be_bytes());
    bytes[40..48].copy_from_slice(&pair.pair_delta_ns.to_be_bytes());
    bytes
}

/// Decode the exact fixed pair extension and reject truncation or trailing bytes.
pub fn decode_packed_pair_metadata(
    bytes: &[u8],
) -> Result<PackedStereoPairMetadata, ValidationError> {
    if bytes.len() != PACKED_STEREO_PAIR_EXTENSION_BYTES {
        return Err(ValidationError::new(format!(
            "packed pair extension must be exactly {} bytes, got {}",
            PACKED_STEREO_PAIR_EXTENSION_BYTES,
            bytes.len()
        )));
    }
    Ok(PackedStereoPairMetadata {
        stereo_pair_id: read_u64(bytes, 0),
        left_source_frame: read_u64(bytes, 8),
        right_source_frame: read_u64(bytes, 16),
        left_sensor_timestamp_ns: read_i64(bytes, 24),
        right_sensor_timestamp_ns: read_i64(bytes, 32),
        pair_delta_ns: read_u64(bytes, 40),
    })
}

/// Validate one pair extension according to its packet role.
pub fn validate_packed_pair_metadata(
    pair: &PackedStereoPairMetadata,
    codec_config_packet: bool,
    max_pair_delta_ns: u64,
) -> Result<(), ValidationError> {
    if codec_config_packet {
        if *pair != PackedStereoPairMetadata::CODEC_CONFIG_NOT_APPLICABLE {
            return Err(ValidationError::new(
                "codec-config packet requires the zero/not-applicable packed pair shape",
            ));
        }
        return Ok(());
    }
    if pair.stereo_pair_id == 0 || pair.left_source_frame == 0 || pair.right_source_frame == 0 {
        return Err(ValidationError::new(
            "video packet requires nonzero pair and source frame ids",
        ));
    }
    if pair.left_sensor_timestamp_ns <= 0 || pair.right_sensor_timestamp_ns <= 0 {
        return Err(ValidationError::new(
            "video packet requires positive source sensor timestamps",
        ));
    }
    let measured = pair
        .left_sensor_timestamp_ns
        .abs_diff(pair.right_sensor_timestamp_ns);
    if pair.pair_delta_ns != measured {
        return Err(ValidationError::new(
            "packed pair_delta_ns disagrees with source sensor timestamps",
        ));
    }
    if max_pair_delta_ns == 0 || pair.pair_delta_ns > max_pair_delta_ns {
        return Err(ValidationError::new(
            "packed pair source timestamp skew exceeds the configured bound",
        ));
    }
    Ok(())
}

/// Validate monotonic unique pair/source ids and prohibit source-frame reuse.
pub fn validate_packed_pair_sequence(
    pairs: &[PackedStereoPairMetadata],
    max_pair_delta_ns: u64,
) -> Result<(), ValidationError> {
    let mut previous_pair = 0;
    let mut previous_left = 0;
    let mut previous_right = 0;
    let mut seen_left = BTreeSet::new();
    let mut seen_right = BTreeSet::new();
    for pair in pairs {
        validate_packed_pair_metadata(pair, false, max_pair_delta_ns)?;
        if pair.stereo_pair_id <= previous_pair
            || pair.left_source_frame <= previous_left
            || pair.right_source_frame <= previous_right
            || !seen_left.insert(pair.left_source_frame)
            || !seen_right.insert(pair.right_source_frame)
        {
            return Err(ValidationError::new(
                "packed pair sequence reuses or does not monotonically advance pair/source frame ids",
            ));
        }
        previous_pair = pair.stereo_pair_id;
        previous_left = pair.left_source_frame;
        previous_right = pair.right_source_frame;
    }
    Ok(())
}

fn read_u64(bytes: &[u8], offset: usize) -> u64 {
    u64::from_be_bytes(
        bytes[offset..offset + 8]
            .try_into()
            .expect("fixed extension"),
    )
}

fn read_i64(bytes: &[u8], offset: usize) -> i64 {
    i64::from_be_bytes(
        bytes[offset..offset + 8]
            .try_into()
            .expect("fixed extension"),
    )
}
