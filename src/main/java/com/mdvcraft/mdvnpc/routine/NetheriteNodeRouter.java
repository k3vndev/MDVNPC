package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.routine.BoundedPathfinder.Grid;
import com.mdvcraft.mdvnpc.routine.BoundedPathfinder.Node;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Incremental directed marker graph. Every connection is a complete, safe terrain path. */
public final class NetheriteNodeRouter {
    private static final int MAX_MARKERS = 512;
    private static final int MAX_PROBES = 8192;
    private static final int MAX_WORK = 131072;
    private static final int MAX_RESULT_POINTS = 16384;
    private record Edge(Node from, Node to) {}
    private record Entry(Node node, double cost) {}
    private final List<Node> available, entries;
    private final Grid grid;
    private final Node start, goal;
    private final int linkRange, endpointRange, localMaxNodes;
    private final Set<Edge> failed = new HashSet<>();
    private final PriorityQueue<Entry> open = new PriorityQueue<>(Comparator.comparingDouble(Entry::cost));
    private final Map<Node, Double> costs = new HashMap<>();
    private final Map<Node, Node> parent = new HashMap<>();
    private final Map<Node, List<Node>> legs = new HashMap<>();
    private final Deque<Node> outgoing = new ArrayDeque<>();
    private Node entryMarker, expanding, pendingFrom, pendingTo;
    private List<Node> entrance;
    private BoundedPathfinder pending;
    private int entryIndex, probes, work;
    private boolean done, entering;
    private List<Node> result, markerRoute = List.of();

    public NetheriteNodeRouter(List<Node> markers, Grid grid, Node start, Node goal,
                               int linkRange, int endpointRange, int localMaxNodes) {
        this.grid = grid; this.start = start; this.goal = goal;
        this.linkRange = Math.max(1, Math.min(64, linkRange));
        this.endpointRange = Math.max(1, Math.min(64, endpointRange));
        this.localMaxNodes = Math.max(16, Math.min(65536, localMaxNodes));
        available = markers == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(markers));
        if (grid == null || start == null || goal == null || available.size() > MAX_MARKERS
                || available.contains(null)) { entries = List.of(); done = true; return; }
        entries = available.stream().filter(n -> nearby(start, n, this.endpointRange))
                .sorted(Comparator.comparingDouble(n -> distance(start, n))).toList();
        if (entries.isEmpty()) done = true;
    }

    /** The budget covers local A* expansions and graph bookkeeping, shared across all legs. */
    public int advance(int budget) {
        int used = 0;
        while (!done && used < Math.max(0, budget)) {
            if (work >= MAX_WORK || probes >= MAX_PROBES) { done = true; break; }
            if (pending != null) {
                int spent = pending.advance(Math.min(budget - used, MAX_WORK - work));
                used += spent; work += spent;
                if (!pending.done()) break;
                List<Node> path = pending.result(); pending = null;
                if (path == null) failed.add(new Edge(pendingFrom, pendingTo));
                accept(path);
                continue;
            }
            used++; work++;
            if (entrance == null) {
                if (entryIndex >= entries.size()) { done = true; break; }
                entryMarker = entries.get(entryIndex++); entering = true;
                connect(start, entryMarker);
            } else if (!outgoing.isEmpty()) {
                Node next = outgoing.removeFirst(); entering = false;
                if (!failed.contains(new Edge(expanding, next))) connect(expanding, next);
            } else {
                Entry next = open.poll();
                if (next == null) {
                    // The nearest reachable marker can be isolated. Try the next entrance.
                    entrance = null; continue;
                }
                if (next.cost != costs.getOrDefault(next.node, Double.POSITIVE_INFINITY)) continue;
                expanding = next.node;
                if (expanding.equals(goal)) { complete(goal, null); continue; }
                if (nearby(expanding, goal, endpointRange)) outgoing.add(goal);
                for (Node marker : available)
                    if (!marker.equals(expanding) && !marker.equals(goal)
                            && nearby(expanding, marker, linkRange)
                            && costs.getOrDefault(marker, Double.POSITIVE_INFINITY) > next.cost + distance(expanding, marker))
                        outgoing.add(marker);
            }
        }
        return used;
    }

    private void connect(Node from, Node to) {
        pendingFrom = from; pendingTo = to; probes++;
        // Restrict a local link to a small corridor. A supposedly nearby edge must not
        // become an unbounded global search through an unrelated part of the map.
        Grid local = new Grid() {
            @Override public boolean stand(Node n) {
                return n.x() >= Math.min(from.x(), to.x()) - 4 && n.x() <= Math.max(from.x(), to.x()) + 4
                        && n.z() >= Math.min(from.z(), to.z()) - 4 && n.z() <= Math.max(from.z(), to.z()) + 4
                        && n.y() >= Math.min(from.y(), to.y()) - 4 && n.y() <= Math.max(from.y(), to.y()) + 4
                        && grid.stand(n);
            }
            @Override public boolean edge(Node a, Node b) { return grid.edge(a, b); }
        };
        pending = new BoundedPathfinder(local, from, to, localMaxNodes);
    }

    private void accept(List<Node> path) {
        if (entering) {
            if (path == null) return;
            entrance = path; costs.clear(); parent.clear(); legs.clear(); open.clear(); outgoing.clear();
            costs.put(entryMarker, 0.0); open.add(new Entry(entryMarker, 0));
        } else if (path != null) {
            double cost = costs.get(pendingFrom) + pathCost(path);
            if (cost < costs.getOrDefault(pendingTo, Double.POSITIVE_INFINITY)) {
                costs.put(pendingTo, cost); parent.put(pendingTo, pendingFrom);
                legs.put(pendingTo, path); open.add(new Entry(pendingTo, cost));
            }
        }
    }

    private void complete(Node lastMarker, List<Node> exit) {
        LinkedList<Node> chain = new LinkedList<>();
        for (Node n = lastMarker; n != null; n = parent.get(n)) chain.addFirst(n);
        ArrayList<Node> route = new ArrayList<>(entrance);
        for (int i = 1; i < chain.size(); i++) append(route, legs.get(chain.get(i)));
        if (exit != null) append(route, exit);
        if (route.size() <= MAX_RESULT_POINTS) {
            result = List.copyOf(route);
            markerRoute = chain.stream().filter(available::contains).toList();
        }
        done = true;
    }

    private static void append(List<Node> route, List<Node> leg) {
        for (Node node : leg) if (route.isEmpty() || !route.getLast().equals(node)) route.add(node);
    }
    private static double pathCost(List<Node> path) {
        double cost = 0;
        for (int i = 1; i < path.size(); i++) cost += distance(path.get(i - 1), path.get(i));
        return cost;
    }
    private static boolean nearby(Node a, Node b, int range) { return distance(a, b) <= range; }
    private static double distance(Node a, Node b) {
        return Math.sqrt(Math.pow((double) a.x() - b.x(), 2) + Math.pow((double) a.y() - b.y(), 2)
                + Math.pow((double) a.z() - b.z(), 2));
    }
    public boolean done() { return done; }
    /** Complete low-level path, including entry and final approach, or null when no route exists. */
    public List<Node> result() { return result; }
    public List<Node> markers() { return markerRoute; }
}
