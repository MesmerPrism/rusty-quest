//! Guard optional Camera2 lookups using the current metadata's borrowed tag list.

/// Validate the scalar/pointer outputs of ACameraMetadata_getAllTags.
///
/// # Safety
/// For a successful positive count, `tags` must point to that many readable u32s
/// and remain valid for the returned borrow. Camera2 owns this storage; it must
/// not escape the metadata callback or survive ACameraMetadata_free.
pub(super) unsafe fn tag_list_from_ffi<'a>(
    status: i32,
    count: i32,
    tags: *const u32,
) -> Option<&'a [u32]> {
    if status != 0 || count < 0 {
        return None;
    }
    if count == 0 {
        return Some(&[]);
    }
    if tags.is_null()
        || (tags as usize) % std::mem::align_of::<u32>() != 0
        || count as usize > isize::MAX as usize / std::mem::size_of::<u32>()
    {
        return None;
    }
    Some(std::slice::from_raw_parts(tags, count as usize))
}

/// Never invoke the noisy NDK entry lookup for a tag absent from this result.
/// The lookup's own failure remains None; values are not synthesized or cached.
pub(super) fn read_if_present<T>(
    tags: &[u32],
    tag: u32,
    read: impl FnOnce() -> Option<T>,
) -> Option<T> {
    if tags.contains(&tag) {
        read()
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn absent_optional_tags_never_call_the_entry_api() {
        let tags = [0x10005, 0xe0010];
        let tags = unsafe { tag_list_from_ffi(0, tags.len() as i32, tags.as_ptr()) }.unwrap();
        assert_eq!(
            read_if_present::<i64>(tags, 0xe0001, || panic!(
                "absent optional tag must not be queried"
            )),
            None
        );
        assert_eq!(
            read_if_present::<u8>(tags, 0x30000, || panic!(
                "absent optional tag must not be queried"
            )),
            None
        );
    }

    #[test]
    fn present_values_and_entry_failures_are_preserved_without_caching() {
        // No sorting assumption: the inventory may be in any order.
        let tags = [0xe0010, 0x10005];
        let mut calls = 0;
        let value = read_if_present(&tags, 0xe0010, || {
            calls += 1;
            Some(123456789_i64)
        });
        assert_eq!(value, Some(123456789));
        assert_eq!(calls, 1);
        assert_eq!(read_if_present::<i64>(&tags, 0xe0010, || None), None);
        assert_eq!(
            read_if_present::<i64>(&[], 0xe0010, || panic!("next result lost the tag")),
            None
        );
    }

    #[test]
    fn invalid_discovery_outputs_are_rejected_without_dereferencing() {
        let invalid = 1usize as *const u32;
        unsafe {
            assert!(tag_list_from_ffi(-1, 10, invalid).is_none());
            assert!(tag_list_from_ffi(0, -1, invalid).is_none());
            assert!(tag_list_from_ffi(0, 1, std::ptr::null()).is_none());
            assert!(tag_list_from_ffi(0, 1, invalid).is_none());
            assert_eq!(tag_list_from_ffi(0, 0, std::ptr::null()), Some(&[][..]));
        }
    }
}
