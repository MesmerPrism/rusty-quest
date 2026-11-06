use crate::abi::{Transform, BUFFER_LEN};

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct State {
    pub completed: u64,
    pub value: i64,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct GuardedBuffer {
    pub before: u64,
    pub bytes: [u8; BUFFER_LEN],
    pub after: u64,
}

impl GuardedBuffer {
    pub fn new() -> Self {
        Self {
            before: 0xaabbccdd,
            bytes: [0; BUFFER_LEN],
            after: 0x11223344,
        }
    }
}

pub fn probe(function: Transform, expected: i64) -> Result<(), &'static str> {
    let mut output = 0;
    let mut scratch = GuardedBuffer::new();
    let status = unsafe {
        function(
            5,
            7,
            &mut output,
            scratch.bytes.as_mut_ptr(),
            BUFFER_LEN as u32,
        )
    };
    if status != 0 {
        return Err("scratch rejected");
    }
    if scratch.before != 0xaabbccdd || scratch.after != 0x11223344 {
        return Err("scratch guards");
    }
    if output != expected || scratch.bytes != expected.to_le_bytes() {
        return Err("scratch result");
    }
    Ok(())
}

pub fn dispatch(
    function: Transform,
    state: &mut State,
    buffer: &mut GuardedBuffer,
    input: i64,
) -> Result<(), &'static str> {
    let next = state.completed.checked_add(1).ok_or("counter overflow")?;
    let mut output = 0;
    let saved = *buffer;
    let status = unsafe {
        function(
            input,
            state.value,
            &mut output,
            buffer.bytes.as_mut_ptr(),
            BUFFER_LEN as u32,
        )
    };
    if status != 0
        || buffer.before != saved.before
        || buffer.after != saved.after
        || buffer.bytes != output.to_le_bytes()
    {
        *buffer = saved;
        return Err("operation rejected");
    }
    state.value = output;
    state.completed = next;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    unsafe extern "C" fn plus_one(
        input: i64,
        prior: i64,
        output: *mut i64,
        bytes: *mut u8,
        len: u32,
    ) -> i32 {
        if len < 8 {
            return 1;
        }
        unsafe {
            let value = prior + input + 1;
            *output = value;
            std::ptr::copy_nonoverlapping(value.to_le_bytes().as_ptr(), bytes, 8);
        }
        0
    }
    unsafe extern "C" fn rejects(_: i64, _: i64, _: *mut i64, _: *mut u8, _: u32) -> i32 {
        1
    }

    // A successful no-op dispatch or increment on rejection breaks these observed states.
    #[test]
    fn only_completed_operations_advance_host_state() {
        let mut state = State {
            completed: 0,
            value: 0,
        };
        let mut buffer = GuardedBuffer::new();
        let address = buffer.bytes.as_ptr();
        dispatch(plus_one, &mut state, &mut buffer, 4).unwrap();
        assert_eq!(
            state,
            State {
                completed: 1,
                value: 5
            }
        );
        assert_eq!(buffer.bytes, 5i64.to_le_bytes());
        assert_eq!(buffer.bytes.as_ptr(), address);
        assert_eq!((buffer.before, buffer.after), (0xaabbccdd, 0x11223344));
        let saved = state;
        assert!(dispatch(rejects, &mut state, &mut buffer, 4).is_err());
        assert_eq!(state, saved);
    }

    // Publishing a candidate without exercising its scratch result breaks this.
    #[test]
    fn wrong_candidate_behavior_fails_scratch_validation() {
        assert_eq!(probe(plus_one, 14), Err("scratch result"));
        assert!(probe(plus_one, 13).is_ok()); // prior 7 + input 5 + 1
        assert_eq!(probe(rejects, 13), Err("scratch rejected"));
    }
}
