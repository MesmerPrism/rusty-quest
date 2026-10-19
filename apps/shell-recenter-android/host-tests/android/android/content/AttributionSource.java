// SPDX-License-Identifier: AGPL-3.0-or-later
package android.content; public class AttributionSource { public String getPackageName(){return "com.android.shell";} public int getUid(){return android.os.Process.myUid();} public int getPid(){return android.os.Process.myPid();} }
