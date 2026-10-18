package com.mdvcraft.mdvnpc.routine;

import java.util.*;

/** Incremental A*: bounded memory, bounded work per call, no Bukkit access off-thread. */
public final class BoundedPathfinder {
    public record Node(int x, int y, int z) {}
    public interface Grid { boolean stand(Node n); boolean edge(Node from, Node to); }
    private record Entry(Node node, double score, int distance) {}
    private final Grid grid;
    private final Node start, goal;
    private final int limit;
    private final PriorityQueue<Entry> open = new PriorityQueue<>(Comparator.comparingDouble(Entry::score));
    private final Map<Node, Integer> cost = new HashMap<>();
    private final Map<Node, Node> parent = new HashMap<>();
    private boolean done;
    private List<Node> result;
    public BoundedPathfinder(Grid grid, Node start, Node goal, int limit) {
        this.grid = grid; this.start = start; this.goal = goal; this.limit = limit;
        if (!grid.stand(start) || !grid.stand(goal)) { done = true; return; }
        open.add(new Entry(start, heuristic(start), 0)); cost.put(start, 0);
    }
    private double heuristic(Node n) { return Math.abs(n.x - goal.x) + Math.abs(n.z - goal.z) + Math.abs(n.y - goal.y); }
    public int advance(int budget) {
        int used = 0;
        while (!done && used < budget) {
            if (open.isEmpty()) { done = true; break; }
            Entry entry = open.poll(); used++;
            Node n = entry.node;
            if (entry.distance != cost.getOrDefault(n, -1)) continue;
            if (n.equals(goal)) {
                LinkedList<Node> route = new LinkedList<>();
                for (Node p = n; p != null; p = parent.get(p)) route.addFirst(p);
                result = List.copyOf(route); done = true; break;
            }
            for (int[] d : DIRECTIONS) for (int dy : HEIGHTS) {
                Node next = new Node(n.x + d[0], n.y + dy, n.z + d[1]);
                if (Math.abs(next.x - start.x) > 512 || Math.abs(next.z - start.z) > 512 || !grid.stand(next) || !grid.edge(n, next)) continue;
                int distance = entry.distance + 1 + Math.abs(dy);
                if (distance < cost.getOrDefault(next, Integer.MAX_VALUE)) {
                    if (!cost.containsKey(next) && cost.size() >= limit) { done = true; return used; }
                    cost.put(next, distance); parent.put(next, n); open.add(new Entry(next, distance + heuristic(next), distance));
                }
                // A bridge and a lower floor can both be legal neighbours in the same
                // column. Keep both so the first height cannot hide the only valid route.
            }
        }
        return used;
    }
    private static final int[][] DIRECTIONS = {{1,0},{-1,0},{0,1},{0,-1}};
    // Integer node deltas needed to represent physical +1.5 / -2.3 transitions on
    // slabs, carpets and lowered blocks. Grid.edge remains the authoritative geometry check.
    private static final int[] HEIGHTS = {0,1,-1,2,-2,-3};
    public boolean done() { return done; }
    public List<Node> result() { return result; }
    public int visited() { return cost.size(); }
}
