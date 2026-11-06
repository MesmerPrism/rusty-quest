// SPDX-License-Identifier: AGPL-3.0-or-later
package android.app; public class ActivityThread { public static ActivityThread systemMain(){return new ActivityThread();} public Application getApplication(){return null;} public LoadedApk getPackageInfo(String p,android.content.res.CompatibilityInfo c,int f){if(!"com.android.shell".equals(p))throw new SecurityException();return new LoadedApk();} }
