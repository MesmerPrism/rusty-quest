// SPDX-License-Identifier: AGPL-3.0-or-later
package android.os; public class Handler { public Handler(){} public Handler(Looper l){} public boolean post(Runnable r){r.run();return true;} public boolean postDelayed(Runnable r,long ms){return true;} }
