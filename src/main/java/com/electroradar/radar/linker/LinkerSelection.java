package com.electroradar.radar.linker;

import com.electroradar.radar.network.NetworkKind;

/** Последовательность кликов принадлежит одному предмету linker. */
public record LinkerSelection(String target, NetworkKind kind) {
    public static LinkerSelection empty() { return new LinkerSelection("", NetworkKind.RADAR); }
    public LinkerSelection click(String next, boolean dual, boolean disconnect) {
        if (disconnect) return empty();
        return new LinkerSelection(next, dual && target.equals(next) && kind == NetworkKind.RADAR
                ? NetworkKind.INTERCEPTION : NetworkKind.RADAR);
    }
}
