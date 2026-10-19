// SPDX-License-Identifier: AGPL-3.0-or-later
package android.app; public class AppOpsManager { public void checkPackage(int uid,String name){if(uid!=2000||!"com.android.shell".equals(name))throw new SecurityException();} }
