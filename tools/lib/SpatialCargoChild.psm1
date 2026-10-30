# Own only the optional Cargo computation. Gradle and cache-lane ownership stay
# with the caller. .NET drains both streams while PowerShell runs JVM preflight.
function Start-SpatialCargoChild {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][string]$WorkingDirectory,
        [Parameter(Mandatory)][string]$CaptureDirectory)
    [void][IO.Directory]::CreateDirectory($CaptureDirectory)
    $stdoutPath=Join-Path $CaptureDirectory 'stdout.log'
    $stderrPath=Join-Path $CaptureDirectory 'stderr.log'
    $stdout=$null;$stderr=$null;$process=$null
    try {
        $stdout=[IO.File]::Open($stdoutPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
        $stderr=[IO.File]::Open($stderrPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
        $start=[Diagnostics.ProcessStartInfo]::new()
        $start.FileName=$FilePath;$start.WorkingDirectory=$WorkingDirectory
        $start.UseShellExecute=$false;$start.CreateNoWindow=$true
        $start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
        foreach($argument in $Arguments){$start.ArgumentList.Add($argument)}
        # Force an explicit snapshot now, before any parent/JVM environment changes.
        $start.Environment.Clear()
        foreach($entry in [Environment]::GetEnvironmentVariables('Process').GetEnumerator()){
            $start.Environment[[string]$entry.Key]=[string]$entry.Value
        }
        $process=[Diagnostics.Process]::new();$process.StartInfo=$start
        $clock=[Diagnostics.Stopwatch]::StartNew()
        if(-not $process.Start()){throw 'Unable to start native Cargo child.'}
        $stdoutDrain=$process.StandardOutput.BaseStream.CopyToAsync($stdout)
        $stderrDrain=$process.StandardError.BaseStream.CopyToAsync($stderr)
        return [pscustomobject]@{Process=$process;Stdout=$stdout;Stderr=$stderr;
            StdoutPath=$stdoutPath;StderrPath=$stderrPath;StdoutDrain=$stdoutDrain;StderrDrain=$stderrDrain;
            Clock=$clock;Completed=$false}
    } catch {
        $original=$_
        if($null -ne $process){
            try{if(-not $process.HasExited){$process.Kill($true);$process.WaitForExit()}}catch{$original.Exception.Data['native_start_cleanup_error']=$_.Exception.Message}
            $process.Dispose()
        }
        if($null -ne $stdout){$stdout.Dispose()};if($null -ne $stderr){$stderr.Dispose()}
        throw $original
    }
}

function Complete-SpatialCargoChild {
    [CmdletBinding()]
    param([Parameter(Mandatory)]$Child,[switch]$Cancel,
        [ValidateRange(1,60000)][int]$DrainTimeoutMilliseconds=10000)
    if($Child.Completed){throw 'Native Cargo child was already completed.'}
    $failure=$null;$exitCode=$null;$reaped=$false;$processDurationMs=$null
    try {
        if($Cancel -and -not $Child.Process.HasExited){$Child.Process.Kill($true)}
        $Child.Process.WaitForExit();$reaped=$true;$exitCode=$Child.Process.ExitCode
        $processDurationMs=[long]($Child.Process.ExitTime-$Child.Process.StartTime).TotalMilliseconds
        # Process exit alone never stands in for EOF (descendants can hold pipes).
        $drains=[Threading.Tasks.Task]::WhenAll([Threading.Tasks.Task[]]@($Child.StdoutDrain,$Child.StderrDrain))
        if(-not $drains.Wait($DrainTimeoutMilliseconds)){throw 'Native Cargo child exited but captured streams did not reach EOF.'}
        $drains.GetAwaiter().GetResult()
    } catch {$failure=$_}
    finally {
        $Child.Clock.Stop()
        $stdoutEof=$Child.StdoutDrain.IsCompletedSuccessfully
        $stderrEof=$Child.StderrDrain.IsCompletedSuccessfully
        $Child.Process.StandardOutput.Dispose();$Child.Process.StandardError.Dispose()
        $Child.Stdout.Dispose();$Child.Stderr.Dispose()
        $Child.Completed=$true;$Child.Process.Dispose()
    }
    $result=[pscustomobject]@{exit_code=$exitCode;cancelled=[bool]$Cancel;reaped=$reaped;
        stdout_eof=$stdoutEof;stderr_eof=$stderrEof;duration_ms=$processDurationMs;ownership_duration_ms=$Child.Clock.ElapsedMilliseconds;
        stdout_path=$Child.StdoutPath;stderr_path=$Child.StderrPath;
        stdout=[IO.File]::ReadAllText($Child.StdoutPath);stderr=[IO.File]::ReadAllText($Child.StderrPath)}
    if($null -ne $failure){$failure.Exception.Data['native_execution']=$result;throw $failure}
    return $result
}
Export-ModuleMember -Function Start-SpatialCargoChild,Complete-SpatialCargoChild
