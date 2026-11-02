//! Keep the adjustable draw budget separate from resident output capacity.
pub(crate) fn tracer_budget(particles: u32, state_slots: u32, requested: u32) -> (u32, u32) {
    let capacity = if particles == 0 {
        0
    } else {
        state_slots / particles
    };
    (capacity, requested.min(capacity))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn zero_startup_draw_retains_capacity_for_later_increase() {
        assert_eq!(tracer_budget(2562, 17934, 0), (7, 0));
        assert_eq!(tracer_budget(2562, 17934, 5), (7, 5));
        assert_eq!(tracer_budget(2562, 17934, 99), (7, 7));
    }
    #[test]
    fn main_only_and_empty_payloads_have_no_tracer_capacity() {
        assert_eq!(tracer_budget(2562, 0, 7), (0, 0));
        assert_eq!(tracer_budget(0, 17934, 7), (0, 0));
    }
}
