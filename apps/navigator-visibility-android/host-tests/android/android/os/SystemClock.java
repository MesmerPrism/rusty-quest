// SPDX-License-Identifier: AGPL-3.0-or-later
// Host test double; never packaged.
package android.os;
public final class SystemClock {
 private SystemClock() { }
 public static long uptimeMillis() { return System.nanoTime() / 1000000; }
}
