[CmdletBinding()]
param()
$ErrorActionPreference='Stop';Set-StrictMode -Version Latest
if(-not$IsWindows){throw 'Windows credential ACL verification requires Windows'}
$repo=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $repo 'tools/Invoke-ExperimentSessionControl.ps1'),[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Actual CLI parse failed'}
$blocks=@($ast.FindAll({param($n)$n -is [Management.Automation.Language.IfStatementAst]},$true))
$prepare=@($blocks|Where-Object{$_.Extent.Text.StartsWith('if($enrollment)')-and$_.Extent.Text.Contains('Credential directory ACL verification failed')})
$persist=@($blocks|Where-Object{$_.Extent.Text.StartsWith('if($enrollment)')-and$_.Extent.Text.Contains('Private enrollment response malformed')})
if($prepare.Count-ne1-or$persist.Count-ne1){throw 'Ambiguous actual credential output guards'}
$prepareBlock=[scriptblock]::Create($prepare[0].Extent.Text);$persistBlock=[scriptblock]::Create($persist[0].Extent.Text)
$enrollment=$true;$shape=[pscustomobject]@{trusted_lan_consent=$true;operation_id='1111111111111111'}
$SecretOutputDirectory=Join-Path ([IO.Path]::GetTempPath()) ('rq-private-enrollment-test-'+[Guid]::NewGuid().ToString('N'))
$secretPath=$null
. $prepareBlock
if(-not(Test-Path -LiteralPath $SecretOutputDirectory)-or[string]::IsNullOrEmpty($secretPath)){throw 'Protected output was not prepared'}
$denied=$false;try{. $prepareBlock}catch{$denied=$true};if(-not$denied){throw 'Existing credential directory admitted'}
$decoded='{"schema":"rusty.quest.experiment_session.operator_receipt.v1","operation":"enroll-trusted-lan","native_response":{"command_status":"enrolled","operation_id":"1111111111111111","credentials":{"key":"synthetic-secret-never-native"}}}'
$result=. $persistBlock
if(($result|ConvertTo-Json -Compress).Contains('synthetic-secret')-or$result.result-cne'saved-private-response'){throw 'Credential leaked to normal output'}
if([IO.File]::ReadAllText($secretPath)-cne$decoded){throw 'Private exact response was not retained'}
$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$acl=Get-Acl -LiteralPath $secretPath
if(@($acl.Access).Count-ne1-or$acl.Access[0].IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value-cne$sid){throw 'Unexpected secret file principal'}
$denied=$false;try{. $persistBlock}catch{$denied=$true};if(-not$denied){throw 'Existing secret file overwritten'}
$shape.trusted_lan_consent=$false;$SecretOutputDirectory+='.denied'
$denied=$false;try{. $prepareBlock}catch{$denied=$true};if(-not$denied-or(Test-Path -LiteralPath $SecretOutputDirectory)){throw 'Missing consent created credential destination'}
$enrollment=$false
$denied=$false;try{. $prepareBlock}catch{$denied=$true};if(-not$denied){throw 'Non-enrollment credential output admitted'}
'PASS actual CLI protected current-user-only directory/file ACL, no normal-output secret, create-new/no-overwrite, consent and operation denials; synthetic credentials only; no ADB'
