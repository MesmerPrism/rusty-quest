// SPDX-License-Identifier: AGPL-3.0-or-later
package android.system; public class Os { public static Integer uidOverride, euidOverride; public static int getuid(){return uidOverride==null?android.os.Process.uid:uidOverride;} public static int geteuid(){return euidOverride==null?android.os.Process.uid:euidOverride;} public static int getgid(){return 2000;} }
