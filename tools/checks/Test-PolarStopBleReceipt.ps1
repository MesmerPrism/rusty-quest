[CmdletBinding()]
param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path,
    [string]$ReceiptServerPath,
    [string]$ProducerFixtureOut
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$repo=(Resolve-Path -LiteralPath $RepoRoot).Path
$panel=Join-Path $repo 'apps/native-renderer-android/panel-modules/breath-composition/src/main/java/io/github/mesmerprism/rustyquest/native_renderer'
if(-not $ReceiptServerPath){$ReceiptServerPath=Join-Path $panel 'ExperimentSessionBleServer.java'}
$source=[IO.File]::ReadAllText((Resolve-Path -LiteralPath $ReceiptServerPath).Path)
function Get-ActualMethod([string]$anchor) {
    $start=$source.IndexOf($anchor,[StringComparison]::Ordinal)
    if($start -lt 0){throw "Missing production method: $anchor"}
    $opening=$source.IndexOf('{',$start);$depth=0
    for($i=$opening;$i -lt $source.Length;$i++){
        if($source[$i] -eq '{'){$depth++}
        elseif($source[$i] -eq '}'){$depth--;if($depth -eq 0){return $source.Substring($start,$i-$start+1)}}
    }
    throw 'Unclosed production method'
}
$receipt=Get-ActualMethod 'synchronized void updateReceipt('
$detail=Get-ActualMethod 'private static String safeDetail('
$bytes=Get-ActualMethod 'private static byte[] jsonBytes('
$harness=@"
package io.github.mesmerprism.rustyquest.native_renderer;
import org.json.JSONObject;import java.util.*;import java.nio.charset.StandardCharsets;
final class PolarReceiptProducerHarness {
 static class SystemClock {static long elapsedRealtime(){return java.lang.System.nanoTime()/1000000L;}}
 final Map<String,Long> admissionTimes=new HashMap<>(),effectTimes=new HashMap<>();
 static class Peer {String lastCommandId;byte[] receipt,receiptRead;}
 final Map<Object,Peer> peers=new HashMap<>();JSONObject latestOwnerReceipt;String shellOperationId="";
 $receipt
 $detail
 $bytes
 static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
 public static void main(String[]args)throws Exception{
  String id="1111111111111111";PolarReceiptProducerHarness p=new PolarReceiptProducerHarness();
  Peer current=new Peer();current.lastCommandId=id;p.peers.put("current",current);
  Peer other=new Peer();other.lastCommandId="2222222222222222";other.receipt="ORIGINAL".getBytes(StandardCharsets.UTF_8);p.peers.put("other",other);
  p.updateReceipt(id,"accepted","transport only");p.updateReceipt(id,"dispatched","Stop requested; sensor ACK is not confirmed.");
  JSONObject actual=new JSONObject(new String(current.receipt,StandardCharsets.UTF_8));
  check(actual.getInt("v")==1&&id.equals(actual.getString("id"))&&"dispatched".equals(actual.getString("state")),"actual producer must emit dispatched");
  check(new String(other.receipt,StandardCharsets.UTF_8).equals("ORIGINAL"),"no cross-peer receipt adoption");
  byte[] retained=current.receipt.clone();p.updateReceipt(id,"stopped","unsupported physical assertion");
  check(Arrays.equals(retained,current.receipt),"unsupported physical state must not replace receipt");
  p.updateReceipt("wrong-id","confirmed","invalid identity");check(Arrays.equals(retained,current.receipt),"invalid ID cannot replace receipt");
  if(args.length==1)java.nio.file.Files.write(java.nio.file.Paths.get(args[0]),retained,java.nio.file.StandardOpenOption.CREATE_NEW);
  System.out.println("PASS actual production updateReceipt -> v1 exactID dispatched, independent peer and unsupported-state denials");
 }
}
"@
$tempRoot=[IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$output=[IO.Path]::GetFullPath((Join-Path $tempRoot ('rq-polar-receipt-'+[Guid]::NewGuid().ToString('N'))))
[IO.Directory]::CreateDirectory($output)|Out-Null
try{
    $file=Join-Path $output 'PolarReceiptProducerHarness.java'
    [IO.File]::WriteAllText($file,$harness,[Text.UTF8Encoding]::new($false))
    $jar=Get-ChildItem (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.json/json') -Recurse -Filter 'json-*.jar' | Sort-Object FullName -Descending | Select-Object -First 1
    if($null -eq $jar){throw 'Host org.json test dependency unavailable'}
    $javac=(Get-Command javac -ErrorAction Stop).Source;$java=(Get-Command java -ErrorAction Stop).Source
    & $javac '--release' '8' '-encoding' 'UTF-8' '-cp' $jar.FullName '-d' $output (Join-Path $panel 'ExperimentSessionBleProtocol.java') (Join-Path $panel 'ExperimentSessionControlCapability.java') $file
    if($LASTEXITCODE -ne 0){throw "Actual receipt compile failed: $LASTEXITCODE"}
    $runArguments=@('-cp',($output+[IO.Path]::PathSeparator+$jar.FullName),'io.github.mesmerprism.rustyquest.native_renderer.PolarReceiptProducerHarness')
    if($ProducerFixtureOut){$runArguments += [IO.Path]::GetFullPath($ProducerFixtureOut)}
    & $java @runArguments
    if($LASTEXITCODE -ne 0){throw "Actual receipt controls failed: $LASTEXITCODE"}
} finally {
    if(-not $output.StartsWith($tempRoot,[StringComparison]::OrdinalIgnoreCase)-or$output -eq $tempRoot){throw 'Unsafe test scratch cleanup path'}
    Remove-Item -LiteralPath $output -Recurse -Force
}
