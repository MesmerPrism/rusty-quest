param([string]$RepoRoot=(Join-Path $PSScriptRoot '../..'),[Parameter(Mandatory)][string]$OutputRoot)
$ErrorActionPreference='Stop';Set-StrictMode -Version Latest
$RepoRoot=[IO.Path]::GetFullPath($RepoRoot);$OutputRoot=[IO.Path]::GetFullPath($OutputRoot)
if(-not$OutputRoot.StartsWith((Join-Path $RepoRoot 'target')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)-or(Test-Path $OutputRoot)){throw 'Create-new ignored target output required'}
$null=New-Item -ItemType Directory $OutputRoot
$rustRoot=Join-Path $RepoRoot 'apps/spatial-camera-panel-android/native-receipt/src/embedded_duplex'
$bridge=[IO.File]::ReadAllText((Join-Path $rustRoot 'java_bridge.rs'));$start=$bridge.IndexOf('fn parse_owner_failure_diagnostic(');$end=$bridge.IndexOf('#[cfg(test)]',$start)
if($start-lt0-or$end-lt$start){throw 'Strict production parser boundaries missing'}
$parser=$bridge.Substring($start,$end-$start)
$source=[IO.File]::ReadAllText((Join-Path $rustRoot 'owner_diagnostic_read.rs'))
$null=New-Item -ItemType Directory (Join-Path $OutputRoot 'src')
[IO.File]::WriteAllText((Join-Path $OutputRoot 'Cargo.toml'),"[workspace]`n[package]`nname = `"owner-diagnostic-read-host`"`nversion = `"0.1.0`"`nedition = `"2021`"`n[dependencies]`nserde_json = `"=1.0.151`"`n",[Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $OutputRoot 'src/observation.rs'),$source,[Text.UTF8Encoding]::new($false))
$tests=@'
mod observation;
PARSER
fn main() {}
#[cfg(test)] mod tests {
    use super::*;
    fn none() -> serde_json::Value { serde_json::json!({"stage":"NONE","sink_stage":"NONE","action":"NONE","provider_reason":"NONE","owner":"NONE","cause":"NONE","code":"NONE"}) }
    #[test] fn available_closed_receipt() {
        let value=none();let text=value.to_string();let report=observation::snapshot(&text,parse_owner_failure_diagnostic(&text));
        assert_eq!(report["strict_parser_status"],"AVAILABLE");assert_eq!(report["closed_java_fields"],value);assert_eq!(report["qualification_claimed"],false);
    }
    #[test] fn rejected_combination_retains_only_closed_fields() {
        let mut value=none();value["owner"]=serde_json::json!("codec");let text=value.to_string();let report=observation::snapshot(&text,parse_owner_failure_diagnostic(&text));
        assert_eq!(report["strict_parser_error"],"java_bridge.owner_diagnostic_closed_values");assert_eq!(report["closed_java_fields"],value);
    }
    #[test] fn invalid_fields_never_export_payload() {
        for key in ["stage","sink_stage","action","provider_reason","owner","cause","code"] {
            let mut value=none();value[key]=serde_json::json!("SECRET_PAYLOAD");let text=value.to_string();let report=observation::snapshot(&text,parse_owner_failure_diagnostic(&text));
            assert!(report["closed_java_fields"].is_null());assert!(!report.to_string().contains("SECRET_PAYLOAD"));assert_ne!(report["strict_parser_status"],"AVAILABLE");
        }
    }
    #[test] fn unknown_key_oversize_json_and_type_closed() {
        let mut value=none();value["extra"]=serde_json::json!("SECRET_PAYLOAD");
        for text in [value.to_string(),"x".repeat(257),"not json".into(),"[]".into()] {
            let report=observation::snapshot(&text,parse_owner_failure_diagnostic(&text));assert!(report["closed_java_fields"].is_null());assert!(!report.to_string().contains("SECRET_PAYLOAD"));
        }
    }
    #[test] fn callback_failures_finite_and_no_exception_text() {
        for error in ["java_bridge.owner_diagnostic_call","java_bridge.owner_diagnostic_call.exception","java_bridge.owner_diagnostic_call.exception_state","java_bridge.attach","arbitrary SECRET_PAYLOAD"] {
            let report=observation::unavailable(error);assert_eq!(report["strict_parser_status"],"UNAVAILABLE");assert!(report["closed_java_fields"].is_null());assert!(!report.to_string().contains("SECRET_PAYLOAD"));assert_eq!(report["qualification_claimed"],false);
        }
    }
    #[test] fn historical_four_field_parsing_unchanged() {
        let text=r#"{"stage":"NONE","sink_stage":"NONE","action":"NONE","code":"NONE"}"#;
        assert!(parse_owner_failure_diagnostic(text).is_ok());let report=observation::snapshot(text,parse_owner_failure_diagnostic(text));assert_eq!(report["strict_parser_status"],"AVAILABLE");assert!(report["closed_java_fields"].is_null());
    }
}
'@
[IO.File]::WriteAllText((Join-Path $OutputRoot 'src/main.rs'),$tests.Replace('PARSER',$parser),[Text.UTF8Encoding]::new($false))
& cargo test --offline --jobs 1 --manifest-path (Join-Path $OutputRoot 'Cargo.toml') 2>&1|Tee-Object -FilePath (Join-Path $OutputRoot 'cargo-test.txt')
if($LASTEXITCODE-ne0){throw 'Pure production-parser/projection host tests failed'}
$report=@{schema='rusty.quest.owner_diagnostic_read_host_result.v1';status='passed';production_parser_sha256=(Get-FileHash (Join-Path $rustRoot 'java_bridge.rs')).Hash.ToLowerInvariant();production_projection_sha256=(Get-FileHash (Join-Path $rustRoot 'owner_diagnostic_read.rs')).Hash.ToLowerInvariant();test_cases=6;invalid_enum_controls=7;callback_error_controls=5;limits=@('Extracted actual strict parser and projection; no Android JNI callback/typecheck, full JVM, APK or hardware invocation');device_effects=0}
[IO.File]::WriteAllText((Join-Path $OutputRoot 'RESULT.json'),($report|ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))
