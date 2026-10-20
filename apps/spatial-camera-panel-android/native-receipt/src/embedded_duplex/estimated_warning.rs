//! Estimated diagnostics are best-effort; authority failures are returned by callers.
use std::io::Write;
fn write_to(output: &mut impl Write, message: &str) {
    let _ = writeln!(output, "{message}");
}
pub(super) fn publish(message: &str) {
    write_to(&mut std::io::stderr().lock(), message);
}
#[cfg(test)]
mod tests {
    use super::*;
    struct Broken {
        attempts: usize,
    }
    impl Write for Broken {
        fn write(&mut self, _: &[u8]) -> std::io::Result<usize> {
            self.attempts += 1;
            Err(std::io::Error::new(
                std::io::ErrorKind::BrokenPipe,
                "modeled warning sink refused",
            ))
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Err(std::io::Error::new(
                std::io::ErrorKind::BrokenPipe,
                "modeled warning flush refused",
            ))
        }
    }
    #[test]
    fn failed_estimate_publication_does_not_refuse_or_cancel_the_original() {
        let (release, owned) = std::sync::mpsc::channel();
        let original = std::thread::spawn(move || owned.recv().unwrap());
        let identity = original.thread().id();
        let mut sink = Broken { attempts: 0 };
        // Host-only regression containment ensures the gated actual worker is
        // released and joined even if a future warning implementation panics.
        let published = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            write_to(&mut sink, "sameOriginal=true signedExpiryStrict=true")
        }));
        release.send(Err::<(), _>("actual signed expiry")).unwrap();
        assert_eq!(identity, original.thread().id());
        let terminal = original.join().unwrap();
        assert!(published.is_ok());
        assert!(sink.attempts > 0);
        assert_eq!(terminal, Err("actual signed expiry"));
    }
}
