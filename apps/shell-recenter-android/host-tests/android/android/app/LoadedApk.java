// SPDX-License-Identifier: AGPL-3.0-or-later
package android.app; public class LoadedApk { public android.content.pm.ApplicationInfo getApplicationInfo(){return new android.content.pm.ApplicationInfo();} public Application makeApplication(boolean force,Instrumentation i){if(!force||i!=null)throw new SecurityException();return new Application();} }
