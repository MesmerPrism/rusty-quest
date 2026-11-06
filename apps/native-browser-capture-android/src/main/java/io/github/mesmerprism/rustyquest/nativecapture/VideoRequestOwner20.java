// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.mesmerprism.rustyquest.nativecapture;
/** One native video start and one global Stop under explicit Root sole-capture scope. */
final class VideoRequestOwner20 {
    private boolean attempted,returned,accepted,stopAttempted,stopReturned,stopAccepted,originalRemoteBinderDead;
    private long stopElapsed=-1;
    synchronized void beforeCall(){if(attempted)throw new IllegalStateException("start-no-replay");attempted=true;}
    synchronized void callReturned(boolean accepted){if(!attempted||returned)throw new IllegalStateException("start-return-ownership");returned=true;this.accepted=accepted;}
    synchronized void beforeStop(){if(!attempted||stopAttempted||originalRemoteBinderDead)throw new IllegalStateException("one-stop-for-own-attempted-start-only");stopAttempted=true;}
    synchronized void stopReturned(boolean accepted,long elapsed){if(!stopAttempted||stopReturned||elapsed<0)throw new IllegalStateException("stop-return-ownership");stopReturned=true;stopAccepted=accepted;stopElapsed=elapsed;}
    synchronized boolean safeToClose(long elapsed){return !attempted||originalRemoteBinderDead||(stopReturned&&stopAccepted&&elapsed>=stopElapsed&&elapsed-stopElapsed>=2000);}
    synchronized void originalRemoteBinderDied(){originalRemoteBinderDead=true;}
    synchronized boolean remoteBinderDead(){return originalRemoteBinderDead;}
    synchronized boolean attempted(){return attempted;}
    synchronized boolean returned(){return returned;}
    synchronized boolean accepted(){return returned&&accepted;}
    synchronized boolean stopAttempted(){return stopAttempted;}
    synchronized boolean stopReturned(){return stopReturned;}
    synchronized boolean stopAccepted(){return stopReturned&&stopAccepted;}
    synchronized long stopElapsed(){return stopElapsed;}
}
