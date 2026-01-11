package name.kingdoms.blueprint;

import net.minecraft.core.BlockPos;
import java.util.*;

public final class RoadNetworkPlanner {
    private RoadNetworkPlanner() {}

    public static List<RoadEdge> plan(long regionKey, List<BlockPos> anchors) {
        if (anchors == null || anchors.size() < 2) return List.of();

        Random rng = new Random(regionKey * 341873128712L + 132897987541L);

        int n = anchors.size();
        boolean[] inTree = new boolean[n];
        double[] best = new double[n];
        int[] parent = new int[n];
        Arrays.fill(best, Double.POSITIVE_INFINITY);
        Arrays.fill(parent, -1);

        inTree[0] = true;
        for (int i = 1; i < n; i++) {
            best[i] = cost(anchors.get(0), anchors.get(i));
            parent[i] = 0;
        }

        ArrayList<RoadEdge> edges = new ArrayList<>(n - 1);

        for (int it = 0; it < n - 1; it++) {
            int v = -1;
            double vBest = Double.POSITIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                if (inTree[i]) continue;
                if (best[i] < vBest) { vBest = best[i]; v = i; }
            }
            if (v == -1) break;

            inTree[v] = true;
            edges.add(new RoadEdge(anchors.get(v), anchors.get(parent[v])));

            for (int u = 0; u < n; u++) {
                if (inTree[u]) continue;
                double c = cost(anchors.get(v), anchors.get(u));
                if (c < best[u]) {
                    best[u] = c;
                    parent[u] = v;
                }
            }
        }

        int extra = Math.max(1, n / 6);
        HashSet<Long> used = new HashSet<>();
        for (RoadEdge e : edges) used.add(pairKey(e.a(), e.b()));

        for (int k = 0; k < extra; k++) {
            int i = rng.nextInt(n);
            int j = nearestNeighbor(i, anchors);
            if (j == -1 || i == j) continue;

            long key = pairKey(anchors.get(i), anchors.get(j));
            if (used.contains(key)) continue;

            used.add(key);
            edges.add(new RoadEdge(anchors.get(i), anchors.get(j)));
        }

        return edges;
    }

    private static int nearestNeighbor(int i, List<BlockPos> a) {
        BlockPos p = a.get(i);
        int bestJ = -1;
        double best = Double.POSITIVE_INFINITY;
        for (int j = 0; j < a.size(); j++) {
            if (j == i) continue;
            double c = cost(p, a.get(j));
            if (c < best) { best = c; bestJ = j; }
        }
        return bestJ;
    }

    private static double cost(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        double d2 = dx * dx + dz * dz;
        double dy = Math.abs(a.getY() - b.getY());
        return d2 + dy * 16.0; // less bias; A* handles real terrain
    }

    private static long pairKey(BlockPos a, BlockPos b) {
        long x = a.asLong();
        long y = b.asLong();
        long lo = Math.min(x, y);
        long hi = Math.max(x, y);
        return lo * 31L + hi;
    }
}
