package mekanism.cloudworks.common.runtime;

import mekanism.cloudworks.api.CloudworksThermalPlan;

/** Snapshot-budgeted heat exchange shared by the Runtime tick and its conservation tests. */
final class CloudworksRuntimeThermalExchange {
    private CloudworksRuntimeThermalExchange() {}

    static void exchange(CloudworksRuntimeThermalStore thermal, CloudworksThermalPlan[] plans,
            CloudworksRuntimeLinkStore links, int[] thermalLinks, double workRate,
            double[] temperature, double[] minimum, double[] maximum,
            double[] outgoing, double[] incoming, double[] requested) {
        for (int link : thermalLinks) {
            requested[link] = 0;
            if (links.phase(link) != CloudworksRuntimeLinkStore.HEAT || links.ratio(link) == 0) continue;
            int from = links.source(link), to = links.target(link);
            if (plans[from] == null || plans[to] == null) continue;
            if (temperature[from] < temperature[to]) { int swap = from; from = to; to = swap; }
            double delta = temperature[from] - temperature[to];
            double equilibrium = delta / (1 / thermal.capacity(from) + 1 / thermal.capacity(to));
            double conductance = plans[from].conduction + plans[to].conduction;
            double amount = Math.min(equilibrium, delta * Math.min(thermal.capacity(from), thermal.capacity(to))
                    / conductance * links.ratio(link) * workRate);
            requested[link] = from == links.source(link) ? amount : -amount;
            outgoing[from] += amount;
            incoming[to] += amount;
            minimum[from] = Math.min(minimum[from], temperature[to]);
            maximum[to] = Math.max(maximum[to], temperature[from]);
        }
        for (int link : thermalLinks) {
            if (requested[link] == 0) continue;
            int from = requested[link] > 0 ? links.source(link) : links.target(link);
            int to = requested[link] > 0 ? links.target(link) : links.source(link);
            double sourceScale = Math.min(1,
                    0.5 * (temperature[from] - minimum[from]) * thermal.capacity(from) / outgoing[from]);
            double targetScale = Math.min(1,
                    0.5 * (maximum[to] - temperature[to]) * thermal.capacity(to) / incoming[to]);
            double moved = thermal.take(from, Math.abs(requested[link]) * Math.min(sourceScale, targetScale), 0);
            thermal.add(to, moved);
        }
    }
}
