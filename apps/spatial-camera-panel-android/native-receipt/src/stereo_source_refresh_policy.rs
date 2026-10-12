//! A retired import refresh may lose a race with a legitimate control update.
//! Only that exact race defers a frame; native/resource failures remain fatal.
pub(crate) fn classify_retired_refresh(result: Result<(), String>) -> Result<bool, String> {
    match result {
        Ok(()) => Ok(true),
        Err(error) if error == "stereo-normalizer-control-changed-during-refresh" => Ok(false),
        Err(error) => Err(error),
    }
}
pub(crate) fn recording_snapshot_valid(pending: bool, fence_present: bool, requested_revision: u64,
    imported_revision: u64, snapshot_words: [u32; 4], imported_words: [u32; 4]) -> bool {
    !pending && fence_present && requested_revision != 0 && requested_revision == imported_revision
        && snapshot_words == imported_words
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn current_and_changed_controls_have_distinct_frame_results() {
        assert_eq!(classify_retired_refresh(Ok(())), Ok(true));
        assert_eq!(classify_retired_refresh(Err("stereo-normalizer-control-changed-during-refresh".into())), Ok(false));
    }
    #[test]
    fn physical_and_admission_errors_never_become_control_deferrals() {
        for error in ["stereo-import-replacement-before-retirement", "stereo-normalizer-control-unavailable",
            "own-source-foreign-ownership-unavailable", "stereo-normalized-layout-invalid",
            "stereo-recording-admission-invalid", "stereo-normalizer-control-changed-during-refresh-extra", "device-lost"] {
            assert_eq!(classify_retired_refresh(Err(error.into())), Err(error.into()));
        }
    }
    #[test]
    fn imported_snapshot_requires_exact_physical_and_revision_admission() {
        let words=[1,2,3,4];
        assert!(recording_snapshot_valid(false,true,2,2,words,words));
        for (pending,fence,request,imported,snapshot) in [(true,true,2,2,words),
            (false,false,2,2,words),(false,true,0,0,words),(false,true,1,2,words),
            (false,true,2,2,[1,2,3,5])] {
            assert!(!recording_snapshot_valid(pending,fence,request,imported,snapshot,words));
        }
    }
}
