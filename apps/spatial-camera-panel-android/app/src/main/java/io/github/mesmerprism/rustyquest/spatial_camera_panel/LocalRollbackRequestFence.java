package io.github.mesmerprism.rustyquest.spatial_camera_panel;

import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Async command fence only: admission never changes the selected source or its owners. */
public final class LocalRollbackRequestFence {
  public static final class Ticket {
    public final long requestSequence;
    public final long routeGeneration;
    private Ticket(long sequence, long generation) { requestSequence=sequence;routeGeneration=generation; }
  }
  private long sequence;
  public synchronized Ticket begin(long currentRouteGeneration) {
    sequence=Math.addExact(sequence,1L);
    return new Ticket(sequence,currentRouteGeneration);
  }
  public synchronized boolean isCurrent(Ticket ticket,long currentRouteGeneration) {
    return ticket!=null&&ticket.requestSequence==sequence&&ticket.routeGeneration==currentRouteGeneration;
  }
  /** Linearize admission with begin(): a superseded queued command cannot change the native latch. */
  public synchronized <T> T commitIfCurrent(Ticket ticket, LongSupplier currentRouteGeneration,
      BooleanSupplier ownerAlive, Callable<T> nativeSelection) throws Exception {
    if (!isCurrent(ticket,currentRouteGeneration.getAsLong()) || !ownerAlive.getAsBoolean())
      throw new IllegalStateException("Local rollback request was superseded or its Activity retired");
    return nativeSelection.call();
  }
}
