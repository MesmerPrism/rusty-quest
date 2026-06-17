//! NativeActivity lifecycle and input queue pumping.

use std::time::Duration;

use android_activity::{AndroidApp, InputStatus, MainEvent, PollEvent};

#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct ActivityPumpStats {
    pub(crate) input_batches: u64,
    pub(crate) input_events: u64,
    pub(crate) input_errors: u64,
}

pub(crate) fn pump_activity_events(
    app: &AndroidApp,
    timeout: Duration,
    running: &mut bool,
) -> ActivityPumpStats {
    let mut stats = ActivityPumpStats::default();

    app.poll_events(Some(timeout), |event| {
        if let PollEvent::Main(main_event) = event {
            match main_event {
                MainEvent::InputAvailable => {
                    let drained = drain_input_events(app);
                    stats.input_batches = stats.input_batches.saturating_add(1);
                    stats.input_events = stats.input_events.saturating_add(drained.input_events);
                    stats.input_errors = stats.input_errors.saturating_add(drained.input_errors);
                    crate::marker(
                        "android-input",
                        format!(
                            "event=drain status={} inputEvents={} inputErrors={}",
                            if drained.input_errors == 0 {
                                "ok"
                            } else {
                                "error"
                            },
                            drained.input_events,
                            drained.input_errors
                        ),
                    );
                }
                MainEvent::Pause => crate::marker("activity-lifecycle", "event=pause"),
                MainEvent::Resume { .. } => crate::marker("activity-lifecycle", "event=resume"),
                MainEvent::Destroy => {
                    crate::marker("activity-lifecycle", "event=destroy");
                    *running = false;
                }
                MainEvent::InitWindow { .. } => {
                    crate::marker("activity-lifecycle", "event=init-window");
                }
                MainEvent::TerminateWindow { .. } => {
                    crate::marker("activity-lifecycle", "event=terminate-window");
                }
                _ => {}
            }
        }
    });

    stats
}

fn drain_input_events(app: &AndroidApp) -> ActivityPumpStats {
    let mut stats = ActivityPumpStats::default();
    let mut iterator = match app.input_events_iter() {
        Ok(iterator) => iterator,
        Err(error) => {
            let error = error.to_string();
            crate::marker(
                "android-input",
                format!("event=iterator-error reason={}", crate::sanitize(&error)),
            );
            stats.input_errors = 1;
            return stats;
        }
    };

    while iterator.next(|_event| InputStatus::Unhandled) {
        stats.input_events = stats.input_events.saturating_add(1);
    }

    stats
}
