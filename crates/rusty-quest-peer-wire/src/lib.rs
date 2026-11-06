//! Pure, lossless Quest peer renewal wire projection and restoration.
//!
//! These functions do not authenticate senders, grant authority, sign, persist,
//! transport or execute effects. Callers retain their existing guards and limits.
mod renewal_projection;

pub use renewal_projection::{
    checkpoint_reference, expand_renewal, is_projected, project_renewal, receipt_sha256,
};

/// A codec refusal. Display preserves the original application's exact errors.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum WireError {
    /// renewal projection JSON.
    Json,
    /// renewal projection complete receipt bounds.
    CompleteReceiptBounds,
    /// renewal projection receipt schema.
    ReceiptSchema,
    /// renewal projection media authority absent.
    MediaAuthorityAbsent,
    /// renewal projection checkpoint shape.
    CheckpointShape,
    /// renewal projection checkpoint schema.
    CheckpointSchema,
    /// renewal projection checkpoint authority absent.
    CheckpointAuthorityAbsent,
    /// renewal projection unwrapped reference.
    UnwrappedReference,
    /// renewal projection shape.
    ProjectionShape,
    /// renewal projection closed fields.
    ClosedFields,
    /// renewal projection reference schema.
    ReferenceSchema,
    /// renewal projection closed reference.
    ClosedReference,
    /// renewal projection exact receipt differs.
    ExactReceiptDiffers,
    /// renewal checkpoint reference requires projected receipt.
    CheckpointReferenceRequiresProjectedReceipt,
}

impl WireError {
    /// Stable payload-free diagnostic identifier for desktop consumers.
    #[must_use]
    pub const fn code(self) -> &'static str {
        match self {
            Self::Json => "json",
            Self::CompleteReceiptBounds => "complete_receipt_bounds",
            Self::ReceiptSchema => "receipt_schema",
            Self::MediaAuthorityAbsent => "media_authority_absent",
            Self::CheckpointShape => "checkpoint_shape",
            Self::CheckpointSchema => "checkpoint_schema",
            Self::CheckpointAuthorityAbsent => "checkpoint_authority_absent",
            Self::UnwrappedReference => "unwrapped_reference",
            Self::ProjectionShape => "projection_shape",
            Self::ClosedFields => "closed_fields",
            Self::ReferenceSchema => "reference_schema",
            Self::ClosedReference => "closed_reference",
            Self::ExactReceiptDiffers => "exact_receipt_differs",
            Self::CheckpointReferenceRequiresProjectedReceipt => {
                "checkpoint_reference_requires_projected_receipt"
            }
        }
    }

    /// The exact historical application error message.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Json => "renewal projection JSON",
            Self::CompleteReceiptBounds => "renewal projection complete receipt bounds",
            Self::ReceiptSchema => "renewal projection receipt schema",
            Self::MediaAuthorityAbsent => "renewal projection media authority absent",
            Self::CheckpointShape => "renewal projection checkpoint shape",
            Self::CheckpointSchema => "renewal projection checkpoint schema",
            Self::CheckpointAuthorityAbsent => "renewal projection checkpoint authority absent",
            Self::UnwrappedReference => "renewal projection unwrapped reference",
            Self::ProjectionShape => "renewal projection shape",
            Self::ClosedFields => "renewal projection closed fields",
            Self::ReferenceSchema => "renewal projection reference schema",
            Self::ClosedReference => "renewal projection closed reference",
            Self::ExactReceiptDiffers => "renewal projection exact receipt differs",
            Self::CheckpointReferenceRequiresProjectedReceipt => {
                "renewal checkpoint reference requires projected receipt"
            }
        }
    }
}

impl std::fmt::Display for WireError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(self.as_str())
    }
}
impl std::error::Error for WireError {}
