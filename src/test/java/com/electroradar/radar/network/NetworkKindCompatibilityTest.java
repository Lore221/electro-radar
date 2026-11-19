package com.electroradar.radar.network;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class NetworkKindCompatibilityTest {
    private static class Consumer implements NetworkLinkTarget {
        public NetworkId networkIdForLinking() { return null; }
        public boolean reconfigureNetwork(NetworkId id) { return true; }
    }
    private static class Source extends Consumer implements NetworkIdBindingTarget {
        public NetworkId getOrCreateNetworkIdForBinding() { return NetworkId.create(); }
        public void applyNetworkIdFromItem(NetworkId id) { }
    }
    @Test void sharedConsumerAcceptsBothObservationDomainsButSourceRejectsOtherKind() {
        assertTrue(new Consumer().acceptsNetworkKind(NetworkKind.SIMPLE_RADAR));
        assertFalse(new Consumer().acceptsNetworkKind(NetworkKind.INTERCEPTION));
        assertFalse(new Source().acceptsNetworkKind(NetworkKind.SIMPLE_RADAR));
        var simple=new Source() { public NetworkKind networkKindForLinking() { return NetworkKind.SIMPLE_RADAR; } };
        assertTrue(simple.acceptsNetworkKind(NetworkKind.SIMPLE_RADAR));
        assertFalse(simple.acceptsNetworkKind(NetworkKind.RADAR));
    }
}
