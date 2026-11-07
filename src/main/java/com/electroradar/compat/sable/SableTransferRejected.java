package com.electroradar.compat.sable;

/** A preflight refusal, distinct from a partially failed upstream transfer. */
public final class SableTransferRejected extends IllegalStateException {
    public SableTransferRejected(String message) { super(message); }
}
