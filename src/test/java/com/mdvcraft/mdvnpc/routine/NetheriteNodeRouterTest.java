package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.routine.BoundedPathfinder.Grid;
import com.mdvcraft.mdvnpc.routine.BoundedPathfinder.Node;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NetheriteNodeRouterTest {
    @Test void stackedNeighbourFloorsDoNotHideTheReachableLowerRoute() {
        Node start=node(0,66,0), lower=node(1,64,0), goal=node(3,64,0);
        Set<Node> floors=Set.of(start,node(1,67,0),lower,node(2,64,0),goal);
        NetheriteNodeRouter router=new NetheriteNodeRouter(List.of(lower,node(2,64,0)),
                grid(floors),start,goal,2,3,128);
        finish(router);
        assertNotNull(router.result());
        assertTrue(router.result().contains(lower));
    }
    private static Node node(int x, int y, int z) { return new Node(x, y, z); }
    private static Grid grid(Set<Node> floors) {
        return new Grid() {
            @Override public boolean stand(Node n) { return floors.contains(n); }
            @Override public boolean edge(Node from, Node to) {
                return to.y() - from.y() <= 1 && from.y() - to.y() <= 2;
            }
        };
    }
    private static void finish(NetheriteNodeRouter router) {
        for (int i = 0; i < 100_000 && !router.done(); i++)
            assertTrue(router.advance(3) <= 3, "every update stays inside the shared search budget");
        assertTrue(router.done(), "bounded graph search terminates");
    }

    @Test void followsMarkedDetourAndIncludesFinalApproach() {
        Set<Node> floor = new HashSet<>();
        for (int z = 0; z <= 6; z++) { floor.add(node(0, 64, z)); floor.add(node(10, 64, z)); }
        for (int x = 0; x <= 10; x++) floor.add(node(x, 64, 6));
        List<Node> markers = List.of(node(0, 64, 2), node(0, 64, 6), node(5, 64, 6), node(10, 64, 6), node(10, 64, 2));
        NetheriteNodeRouter router = new NetheriteNodeRouter(markers, grid(floor), node(0, 64, 0), node(10, 64, 0), 5, 2, 512);
        finish(router);
        assertNotNull(router.result());
        assertEquals(node(0, 64, 0), router.result().getFirst());
        assertEquals(node(10, 64, 0), router.result().getLast());
        assertEquals(markers, router.markers());
        assertTrue(router.result().containsAll(markers));
        for (int i = 1; i < router.result().size(); i++) {
            Node a = router.result().get(i - 1), b = router.result().get(i);
            assertEquals(1, Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z()));
            assertTrue(floor.contains(b));
        }
    }

    @Test void bypassesNearestUnreachableMarker() {
        Set<Node> floor = new HashSet<>();
        for (int x = 0; x <= 12; x++) floor.add(node(x, 64, 0));
        Node unreachable = node(0, 64, 1); // A floor alone cannot be crossed from the route.
        floor.add(unreachable);
        Grid blocked = new Grid() {
            @Override public boolean stand(Node n) { return floor.contains(n); }
            @Override public boolean edge(Node a, Node b) { return !a.equals(unreachable) && !b.equals(unreachable); }
        };
        List<Node> markers = List.of(unreachable, node(3, 64, 0), node(7, 64, 0), node(10, 64, 0));
        NetheriteNodeRouter router = new NetheriteNodeRouter(markers, blocked, node(0, 64, 0), node(12, 64, 0), 4, 3, 512);
        finish(router);
        assertNotNull(router.result());
        assertEquals(node(3, 64, 0), router.markers().getFirst());
        assertFalse(router.result().contains(unreachable));
    }

    @Test void rejectsIsolatedReachableEntranceAndUsesNextConnectedEntrance() {
        Set<Node> floor = new HashSet<>();
        for (int x = 0; x <= 12; x++) floor.add(node(x, 64, 0));
        // First entrance is reachable from start but no other marker lies in its link range.
        List<Node> markers = List.of(node(0, 64, 1), node(3, 64, 0), node(6, 64, 0), node(9, 64, 0));
        floor.add(markers.getFirst());
        NetheriteNodeRouter router = new NetheriteNodeRouter(markers, grid(floor), node(0, 64, 0), node(12, 64, 0), 3, 3, 512);
        finish(router);
        assertNotNull(router.result());
        assertEquals(node(3, 64, 0), router.markers().getFirst());
    }

    @Test void twoBlockFloorDifferenceUsesActualIntermediateStairAndNeverDirectlyJumpsUpTwo() {
        Set<Node> floor = new HashSet<>(List.of(node(0, 64, 0), node(1, 64, 0), node(2, 65, 0), node(3, 66, 0), node(4, 66, 0), node(5, 66, 0)));
        List<Node> markers = List.of(node(1, 64, 0), node(4, 66, 0));
        NetheriteNodeRouter router = new NetheriteNodeRouter(markers, grid(floor), node(0, 64, 0), node(5, 66, 0), 4, 1, 256);
        finish(router);
        assertNotNull(router.result());
        assertTrue(router.result().contains(node(2, 65, 0)));
        assertTrue(router.result().contains(node(3, 66, 0)));
    }

    @Test void directedDropDoesNotCreateReverseClimbConnection() {
        Set<Node> floor = new HashSet<>(List.of(node(0, 66, 0), node(1, 66, 0), node(2, 64, 0), node(3, 64, 0)));
        List<Node> markers = List.of(node(1, 66, 0), node(2, 64, 0));
        NetheriteNodeRouter down = new NetheriteNodeRouter(markers, grid(floor), node(0, 66, 0), node(3, 64, 0), 3, 1, 128);
        finish(down); assertNotNull(down.result());
        NetheriteNodeRouter up = new NetheriteNodeRouter(markers, grid(floor), node(3, 64, 0), node(0, 66, 0), 3, 1, 128);
        finish(up); assertNull(up.result());
    }

    @Test void goalWithoutReachableExitNeverReturnsPartialPathAsSuccess() {
        Set<Node> floor = new HashSet<>();
        for (int x = 0; x <= 8; x++) floor.add(node(x, 64, 0));
        floor.add(node(10, 64, 0));
        NetheriteNodeRouter router = new NetheriteNodeRouter(List.of(node(2, 64, 0), node(6, 64, 0)), grid(floor), node(0, 64, 0), node(10, 64, 0), 4, 4, 256);
        finish(router); assertNull(router.result());
    }

    @Test void emptyOrExcessiveGraphDeclinesRouting() {
        Node start = node(0, 64, 0), goal = node(2, 64, 0);
        assertTrue(new NetheriteNodeRouter(List.of(), grid(Set.of(start, goal)), start, goal, 8, 8, 128).done());
        ArrayList<Node> many = new ArrayList<>();
        for (int i = 0; i < 513; i++) many.add(node(i, 64, 0));
        NetheriteNodeRouter oversized = new NetheriteNodeRouter(many, grid(new HashSet<>(many)), start, goal, 8, 8, 128);
        assertTrue(oversized.done()); assertNull(oversized.result());
    }
}
