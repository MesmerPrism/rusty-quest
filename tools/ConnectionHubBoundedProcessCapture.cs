using System;
using System.Diagnostics;
using System.IO;
using System.Threading;

namespace RustyQuest.ConnectionHub.Tools
{
    public sealed class BoundedProcessCaptureResult
    {
        public bool CompletedWithinTimeout { get; internal set; }
        public bool DrainCompleted { get; internal set; }
        public int? ExitCode { get; internal set; }
        public byte[] StandardOutput { get; internal set; } = Array.Empty<byte>();
        public byte[] StandardError { get; internal set; } = Array.Empty<byte>();
        public bool StandardOutputExceededLimit { get; internal set; }
        public bool StandardErrorExceededLimit { get; internal set; }
    }

    public static class BoundedProcessCapture
    {
        private sealed class DrainState
        {
            internal readonly Stream Source;
            internal readonly MemoryStream Retained = new MemoryStream();
            internal readonly int MaximumBytes;
            internal bool ExceededLimit;
            internal Exception Failure;

            internal DrainState(Stream source, int maximumBytes)
            {
                Source = source;
                MaximumBytes = maximumBytes;
            }
        }

        public static BoundedProcessCaptureResult Run(
            string fileName,
            string[] arguments,
            int timeoutMilliseconds,
            int maximumBytesPerStream,
            int terminationGraceMilliseconds)
        {
            if (string.IsNullOrWhiteSpace(fileName))
                throw new ArgumentException("A process filename is required.", nameof(fileName));
            if (arguments == null)
                throw new ArgumentNullException(nameof(arguments));
            if (timeoutMilliseconds < 1)
                throw new ArgumentOutOfRangeException(nameof(timeoutMilliseconds));
            if (maximumBytesPerStream < 1)
                throw new ArgumentOutOfRangeException(nameof(maximumBytesPerStream));
            if (terminationGraceMilliseconds < 1)
                throw new ArgumentOutOfRangeException(nameof(terminationGraceMilliseconds));

            var start = new ProcessStartInfo
            {
                FileName = fileName,
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true,
            };
            foreach (string argument in arguments)
                start.ArgumentList.Add(argument);

            using var process = new Process { StartInfo = start };
            if (!process.Start())
                throw new InvalidOperationException("Unable to start the captured process.");

            var stdout = new DrainState(process.StandardOutput.BaseStream, maximumBytesPerStream);
            var stderr = new DrainState(process.StandardError.BaseStream, maximumBytesPerStream);
            var stdoutThread = NewDrainThread(stdout, "rusty-hub-process-stdout");
            var stderrThread = NewDrainThread(stderr, "rusty-hub-process-stderr");
            stdoutThread.Start();
            stderrThread.Start();

            bool completed = process.WaitForExit(timeoutMilliseconds);
            if (!completed)
            {
                try { process.Kill(entireProcessTree: true); }
                catch (InvalidOperationException) { }
                process.WaitForExit(terminationGraceMilliseconds);
            }

            bool stdoutClosed = stdoutThread.Join(terminationGraceMilliseconds);
            bool stderrClosed = stderrThread.Join(terminationGraceMilliseconds);
            if (!stdoutClosed || !stderrClosed)
                throw new TimeoutException("Captured process streams did not close within the termination grace period.");
            if (stdout.Failure != null)
                throw new IOException("Unable to drain captured process stdout.", stdout.Failure);
            if (stderr.Failure != null)
                throw new IOException("Unable to drain captured process stderr.", stderr.Failure);

            return new BoundedProcessCaptureResult
            {
                CompletedWithinTimeout = completed,
                DrainCompleted = true,
                ExitCode = completed ? process.ExitCode : (int?)null,
                StandardOutput = stdout.Retained.ToArray(),
                StandardError = stderr.Retained.ToArray(),
                StandardOutputExceededLimit = stdout.ExceededLimit,
                StandardErrorExceededLimit = stderr.ExceededLimit,
            };
        }

        private static Thread NewDrainThread(DrainState state, string name)
        {
            return new Thread(() => DrainStream(state))
            {
                IsBackground = true,
                Name = name,
            };
        }

        private static void DrainStream(DrainState state)
        {
            try
            {
                var buffer = new byte[16 * 1024];
                int count;
                while ((count = state.Source.Read(buffer, 0, buffer.Length)) > 0)
                {
                    int remaining = state.MaximumBytes - checked((int)state.Retained.Length);
                    if (remaining > 0)
                        state.Retained.Write(buffer, 0, Math.Min(remaining, count));
                    if (count > remaining)
                        state.ExceededLimit = true;
                }
            }
            catch (Exception error)
            {
                state.Failure = error;
            }
        }
    }
}
