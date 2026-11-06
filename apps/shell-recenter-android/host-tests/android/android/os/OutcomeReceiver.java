// SPDX-License-Identifier: AGPL-3.0-or-later
package android.os; public interface OutcomeReceiver<R,E extends Throwable> { void onResult(R result); void onError(E error); }
