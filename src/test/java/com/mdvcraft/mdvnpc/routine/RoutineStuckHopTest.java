package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.trait.HitReactionService;
import com.mdvcraft.mdvnpc.trait.TraitService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Service ownership and timing over actual positions; Navigator tests validate the arc. */
class RoutineStuckHopTest {
    @Test void pendingMarkerPlanningDoesNotRestartItselfThroughRecoveryHops() throws Exception {
        when(navigator.planningNodes("thurg")).thenReturn(true);
        start(walk());f.advance(182);noHop();
        when(navigator.planningNodes("thurg")).thenReturn(false);
        f.advance(60);
        verify(navigator).startRecoveryHop(f.npc,.6,1,242);
    }
    @TempDir Path folder;
    RoutineTransitionTest f;
    MdvNpcPlugin plugin;
    RoutineNavigator navigator;

    @BeforeEach void setup() throws Exception {
        f=new RoutineTransitionTest();f.folder=folder;f.setup();
        when(f.entity.isInsideVehicle()).thenReturn(false);
        plugin=(MdvNpcPlugin)field(f.service,"plugin");
        plugin.settings().messages().set("routines.occasional-looking",false);
        navigator=mock(RoutineNavigator.class);
        when(navigator.move(any(),any(),anyDouble(),anyLong(),anyInt())).thenReturn(RoutineNavigator.Result.WAITING);
    }
    @AfterEach void cleanup(){if(f!=null)f.cleanup();}
    private static Object field(Object object,String name) throws Exception {
        var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
    }
    private static void set(Object object,String name,Object value) throws Exception {
        var field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);
    }
    private Object state() throws Exception {return ((Map<?,?>)field(f.service,"states")).get("thurg");}
    private void start(RoutineGoal goal) throws Exception {
        f.service.repository().put("thurg",goal);f.service.start();set(f.service,"navigator",navigator);
    }
    private RoutineGoal walk() {return f.goal(1,RoutineGoal.Type.WALK,RoutineGoal.WalkMode.CYCLE,0,0,160);}
    private void noHop(){verify(navigator,never()).startRecoveryHop(any(),anyDouble(),anyDouble(),anyLong());}

    @ParameterizedTest
    @CsvSource({"WALK,TARGET,8", "WALK,CYCLE,8", "WALK,RANDOM,8", "WORK,CYCLE,8",
            "SLEEP,CYCLE,0", "SIT,CYCLE,18"})
    void eachWalkingIntentRequestsTheConfiguredHopOnlyAfterThreeSeconds(RoutineGoal.Type type,
            RoutineGoal.WalkMode mode,int target) throws Exception {
        if(type==RoutineGoal.Type.SLEEP)f.position.setX(4.5);
        start(f.goal(1,type,mode,0,0,target));
        f.advance(60);noHop();
        f.advance(2);verify(navigator).startRecoveryHop(f.npc,.6,1,62);
        assertFalse(f.service.canInteract(f.npc));
    }

    @Test void aRejectedHopIsRetriedEveryThreeSecondsWithoutResettingItsMovementAnchor() throws Exception {
        start(walk());f.advance(182);
        verify(navigator).startRecoveryHop(f.npc,.6,1,62);
        verify(navigator).startRecoveryHop(f.npc,.6,1,122);
        verify(navigator).startRecoveryHop(f.npc,.6,1,182);
        verify(navigator,times(3)).startRecoveryHop(any(),anyDouble(),anyDouble(),anyLong());
    }

    @Test void anAnimationThatEndsWithoutMovingRestartsTheThreeSecondDelay() throws Exception {
        AtomicBoolean active=new AtomicBoolean();
        when(navigator.startRecoveryHop(any(),anyDouble(),anyDouble(),anyLong())).thenAnswer(call->{active.set(true);return true;});
        when(navigator.activeHop("thurg")).thenAnswer(call->active.get());
        when(navigator.advanceHop(any(),anyLong())).thenAnswer(call->{active.set(false);return RoutineNavigator.Result.WAITING;});
        start(walk());f.advance(122);
        verify(navigator,times(1)).startRecoveryHop(any(),anyDouble(),anyDouble(),anyLong());
        f.advance(2);verify(navigator).startRecoveryHop(f.npc,.6,1,124);
    }

    @Test void successfulButNoOpMovementCommandsStillTriggerRecovery() throws Exception {
        when(navigator.move(any(),any(),anyDouble(),anyLong(),anyInt())).thenReturn(RoutineNavigator.Result.MOVING);
        start(walk());f.advance(62);verify(navigator).startRecoveryHop(f.npc,.6,1,62);
        assertEquals(.5,f.position.getX());
    }

    @Test void boundedTinyJitterDoesNotPretendToBeWalkingProgress() throws Exception {
        AtomicBoolean side=new AtomicBoolean();
        when(navigator.move(any(),any(),anyDouble(),anyLong(),anyInt())).thenAnswer(call->{
            side.set(!side.get());f.position.setX(.5+(side.get()?.002:-.002));return RoutineNavigator.Result.MOVING;
        });
        start(walk());f.advance(62);verify(navigator).startRecoveryHop(f.npc,.6,1,62);
    }

    @Test void repeatedVerticalCorrectionsDoNotHideAStationaryWalker() throws Exception {
        AtomicBoolean high=new AtomicBoolean();
        when(navigator.move(any(),any(),anyDouble(),anyLong(),anyInt())).thenAnswer(call->{
            high.set(!high.get());f.position.setY(64+(high.get()?.07:-.07));
            return RoutineNavigator.Result.MOVING;
        });
        start(walk());f.advance(122);
        verify(navigator).startRecoveryHop(f.npc,.6,1,62);
        verify(navigator).startRecoveryHop(f.npc,.6,1,122);
        assertEquals(.5,f.position.getX());assertEquals(.5,f.position.getZ());
    }

    @ParameterizedTest
    @EnumSource(value=RoutineGravity.Result.class,names={"BLOCKED","LIFTING"})
    void floorRecoveryWithoutActualAdvanceStillAttemptsAHopAfterThreeSeconds(RoutineGravity.Result result) throws Exception {
        RoutineGravity floor=mock(RoutineGravity.class);
        when(floor.tick(any(),any(),anyLong(),anyInt(),anyDouble())).thenReturn(result);
        set(f.service,"gravity",floor);
        start(walk());f.advance(60);noHop();
        assertTrue((Boolean)field(state(),"recoveringFloor"));
        f.advance(2);verify(navigator).startRecoveryHop(f.npc,.6,1,62);
        f.advance(60);verify(navigator).startRecoveryHop(f.npc,.6,1,122);
        verify(navigator,times(1)).move(any(),any(),anyDouble(),anyLong(),anyInt());
    }

    @Test void aRecoveryHopGetsItsFramesBeforeBlockedGravityCanReturn() throws Exception {
        RoutineGravity floor=mock(RoutineGravity.class);
        when(floor.tick(any(),any(),anyLong(),anyInt(),anyDouble())).thenReturn(RoutineGravity.Result.BLOCKED);
        set(f.service,"gravity",floor);
        AtomicBoolean active=new AtomicBoolean();
        when(navigator.startRecoveryHop(any(),anyDouble(),anyDouble(),anyLong())).thenAnswer(call->{active.set(true);return true;});
        when(navigator.activeHop("thurg")).thenAnswer(call->active.get());
        when(navigator.advanceHop(any(),anyLong())).thenAnswer(call->{active.set(false);return RoutineNavigator.Result.ARRIVED;});
        start(walk());f.advance(64);
        verify(navigator).startRecoveryHop(f.npc,.6,1,62);
        verify(navigator).advanceHop(f.npc,64);
        verify(floor,never()).tick(eq(f.npc),any(),eq(62L),anyInt(),anyDouble());
        verify(floor,never()).tick(eq(f.npc),any(),eq(64L),anyInt(),anyDouble());
        assertFalse(f.service.canInteract(f.npc));
    }

    @Test void genuinelyBlockedBodyRecoveryDoesNotStarveTheWalkingTimer() throws Exception {
        // Full-block overlap is blocked floor geometry, rather than a mocked gravity status.
        f.position.setY(63.8);
        start(walk());f.advance(60);noHop();
        assertTrue(f.service.status("thurg").contains("suelo seguro"),f.service.status("thurg"));
        f.advance(2);verify(navigator).startRecoveryHop(f.npc,.6,1,62);
        assertEquals(63.8,f.position.getY());
        verify(navigator,times(1)).move(any(),any(),anyDouble(),anyLong(),anyInt());
    }

    @ParameterizedTest @CsvSource({"66,false", "63.8,true"})
    void stationaryAirborneAndEmbeddedWalkersRepeatEvenAnUnmovingAcceptedHop(double y,boolean grounded) throws Exception {
        f.position.setY(y);when(f.entity.isOnGround()).thenReturn(grounded);
        RoutineGravity floor=mock(RoutineGravity.class);
        when(floor.tick(any(),any(),anyLong(),anyInt(),anyDouble())).thenReturn(RoutineGravity.Result.BLOCKED);
        set(f.service,"gravity",floor);
        AtomicBoolean active=new AtomicBoolean();
        when(navigator.startRecoveryHop(any(),anyDouble(),anyDouble(),anyLong())).thenAnswer(call->{active.set(true);return true;});
        when(navigator.activeHop("thurg")).thenAnswer(call->active.get());
        when(navigator.advanceHop(any(),anyLong())).thenAnswer(call->{active.set(false);return RoutineNavigator.Result.ARRIVED;});
        start(walk());f.advance(2);clearInvocations(floor);f.advance(122);
        verify(navigator).startRecoveryHop(f.npc,.6,1,62);
        verify(navigator).advanceHop(f.npc,64);
        verify(navigator).startRecoveryHop(f.npc,.6,1,124);
        verify(floor,times(2)).reset(any());
        assertEquals(y,f.position.getY());assertFalse(f.service.canInteract(f.npc));
    }

    @Test void overheadCollisionDoesNotHideTheRepeatedWalkingRequest() throws Exception {
        Block ceiling=mock(Block.class);
        when(ceiling.getType()).thenReturn(Material.STONE);when(ceiling.isPassable()).thenReturn(false);
        when(ceiling.getBoundingBox()).thenReturn(new BoundingBox(0,65,0,1,66,1));
        f.blocks.put("0,65,0",ceiling);
        start(walk());f.advance(122);
        verify(navigator).startRecoveryHop(f.npc,.6,1,62);
        verify(navigator).startRecoveryHop(f.npc,.6,1,122);
        assertEquals(64,f.position.getY());
    }

    @Test void aSelectedDestinationWithoutAnySafeApproachKeepsItsRecoveryClock() throws Exception {
        Block unsupported=mock(Block.class);
        when(unsupported.getType()).thenReturn(Material.AIR);when(unsupported.isPassable()).thenReturn(true);
        when(unsupported.getBoundingBox()).thenReturn(new BoundingBox());
        when(f.world.getBlockAt(eq(160),anyInt(),anyInt())).thenReturn(unsupported);
        start(walk());f.advance(60);noHop();
        assertNull(field(state(),"destination"));assertNotNull(field(state(),"stuckAnchor"));
        f.advance(2);verify(navigator).startRecoveryHop(f.npc,.6,1,62);
        f.advance(60);verify(navigator).startRecoveryHop(f.npc,.6,1,122);
    }

    @Test void aFinishedForcedHopHandsTheNextFrameBackToFloorRecovery() throws Exception {
        RoutineGravity floor=mock(RoutineGravity.class);
        when(floor.tick(any(),any(),anyLong(),anyInt(),anyDouble())).thenAnswer(call->
                f.position.getY()>64?RoutineGravity.Result.FALLING:RoutineGravity.Result.STABLE);
        set(f.service,"gravity",floor);
        AtomicBoolean active=new AtomicBoolean();
        when(navigator.startRecoveryHop(any(),anyDouble(),anyDouble(),anyLong())).thenAnswer(call->{active.set(true);return true;});
        when(navigator.activeHop("thurg")).thenAnswer(call->active.get());
        when(navigator.advanceHop(any(),anyLong())).thenAnswer(call->{f.position.setY(66);active.set(false);return RoutineNavigator.Result.ARRIVED;});
        start(walk());f.advance(64);
        assertTrue((Boolean)field(state(),"recoveringFloor"));
        verify(floor,never()).tick(eq(f.npc),any(),eq(64L),anyInt(),anyDouble());
        f.advance(2);verify(floor).tick(eq(f.npc),any(),eq(66L),anyInt(),anyDouble());
        assertTrue((Boolean)field(state(),"recoveringFloor"));
    }

    @Test void minimumSpeedAtOneTickCadenceAccumulatesSmallRealSteps() throws Exception {
        plugin.settings().messages().set("routines.movement-interval-ticks",1);
        when(navigator.move(any(),any(),anyDouble(),anyLong(),anyInt())).thenAnswer(call->{
            f.position.add(.01,0,0);return RoutineNavigator.Result.MOVING;
        });
        start(walk().withSpeed(.2));f.advance(240);noHop();assertTrue(f.position.getX()>2.8);
    }

    @ParameterizedTest @EnumSource(value=RoutineGoal.Type.class,names={"SLEEP","SIT"})
    void alternateApproachesAndNativeCancellationDoNotResetTheClock(RoutineGoal.Type type) throws Exception {
        int target=type==RoutineGoal.Type.SLEEP?0:18;
        f.position.setX(4.5);when(navigator.exhausted("thurg")).thenReturn(true);
        start(f.goal(1,type,RoutineGoal.WalkMode.CYCLE,0,0,target));
        f.advance(58);assertNull(field(state(),"destination"),"all native approaches already failed before the hop delay");noHop();
        f.advance(4);verify(navigator,atLeastOnce()).cancel("thurg");
        verify(navigator).startRecoveryHop(f.npc,.6,1,62);
    }

    @Test void changingTheActualGoalStartsAFreshWalkingDelay() throws Exception {
        start(f.goal(1,RoutineGoal.Type.WALK,RoutineGoal.WalkMode.CYCLE,720,960,8));
        f.service.repository().put("thurg",f.goal(2,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,960,720,12));
        f.advance(40);when(f.world.getFullTime()).thenReturn(10000L);f.advance(60);noHop();
        f.advance(2);verify(navigator).startRecoveryHop(f.npc,.6,1,102);
    }

    @ParameterizedTest @CsvSource({"REACTION", "DRINK", "MOUNT"})
    void timeOwnedByOtherSystemsIsExcludedAndResumeGetsAFreshDelay(String owner) throws Exception {
        AtomicBoolean busy=new AtomicBoolean();
        if(owner.equals("REACTION")) {
            HitReactionService reactions=mock(HitReactionService.class);when(reactions.busy("thurg")).thenAnswer(call->busy.get());
            when(plugin.reactions()).thenReturn(reactions);
        } else if(owner.equals("DRINK")) {
            TraitService traits=mock(TraitService.class);when(traits.busy("thurg")).thenAnswer(call->busy.get());
            when(plugin.traits()).thenReturn(traits);
        } else when(f.entity.isInsideVehicle()).thenAnswer(call->busy.get());
        start(walk());f.advance(40);busy.set(true);f.advance(100);noHop();
        busy.set(false);f.advance(60);noHop();f.advance(2);verify(navigator).startRecoveryHop(f.npc,.6,1,202);
    }

    @ParameterizedTest @EnumSource(value=RoutineGoal.Type.class,names={"WORK","SIT","SLEEP"})
    void arrivedWorkersAndFurniturePosesNeverHop(RoutineGoal.Type type) throws Exception {
        int target=type==RoutineGoal.Type.SIT?18:0;
        when(navigator.move(any(),any(),anyDouble(),anyLong(),anyInt())).thenAnswer(call->{
            f.position=((Location)call.getArgument(1)).clone();return RoutineNavigator.Result.ARRIVED;
        });
        start(f.goal(1,type,RoutineGoal.WalkMode.CYCLE,0,0,target));f.advance(180);noHop();
    }

    @Test void dormantOrDisabledNpcAndAnIdleCompletedMetaDoNotHop() throws Exception {
        f.service.start();set(f.service,"navigator",navigator);f.advance(100);noHop();
        f.service.repository().put("thurg",walk());f.following=false;f.observerPosition=null;
        f.advance(100);noHop();assertTrue(f.service.status("thurg").contains("suspendido"));
        f.following=true;
        f.service.repository().edit(yaml->yaml.set("npcs.thurg.enabled",false));
        assertFalse(f.service.enabled("thurg"));
        f.advance(100);noHop();
    }

    @Test void aCompletedMetaIsIdleRatherThanStuck() throws Exception {
        when(navigator.move(any(),any(),anyDouble(),anyLong(),anyInt())).thenAnswer(call->{
            f.position=((Location)call.getArgument(1)).clone();return RoutineNavigator.Result.ARRIVED;
        });
        start(f.goal(1,RoutineGoal.Type.WALK,RoutineGoal.WalkMode.TARGET,0,0,8));f.advance(180);noHop();
        assertTrue(f.service.status("thurg").contains("secuencia terminada"));
    }

    @Test void activeDancingDoesNotBecomeStuckWalking() throws Exception {
        start(walk());f.advance(2);
        DanceController dancers=mock(DanceController.class);
        when(dancers.tick(any(),anyLong(),anyInt(),anyDouble())).thenReturn(DanceController.Result.DANCING);
        set(f.service,"dancers",dancers);set(state(),"dancing",true);f.advance(180);noHop();
    }

    @Test void aWalkingReturnToTheReservedSeatUsesTheSameRecoveryClock() throws Exception {
        start(f.goal(1,RoutineGoal.Type.SIT,RoutineGoal.WalkMode.CYCLE,0,0,18));f.advance(2);
        set(state(),"returningFromDance",true);set(state(),"nextReturnCheck",Long.MAX_VALUE);
        f.advance(60);verify(navigator).startRecoveryHop(f.npc,.6,1,62);
        assertTrue(f.service.claimed(f.goal(1,RoutineGoal.Type.SIT,RoutineGoal.WalkMode.CYCLE,0,0,18).points().getFirst()));
    }

    @Test void completingAHopIsNotArrivalAtWork() throws Exception {
        AtomicBoolean active=new AtomicBoolean();
        when(navigator.startRecoveryHop(any(),anyDouble(),anyDouble(),anyLong())).thenAnswer(call->{active.set(true);return true;});
        when(navigator.activeHop("thurg")).thenAnswer(call->active.get());
        when(navigator.advanceHop(any(),anyLong())).thenAnswer(call->{active.set(false);return RoutineNavigator.Result.ARRIVED;});
        start(f.goal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,0,0,160));f.advance(66);
        verify(navigator).advanceHop(f.npc,64);assertFalse(f.service.canInteract(f.npc));
    }

    @Test void verticalHopAnimationDoesNotRefreshTheSixtySecondRouteTimeout() throws Exception {
        AtomicBoolean active=new AtomicBoolean();AtomicInteger frame=new AtomicInteger();
        when(navigator.startRecoveryHop(any(),anyDouble(),anyDouble(),anyLong())).thenAnswer(call->{active.set(true);frame.set(0);return true;});
        when(navigator.activeHop("thurg")).thenAnswer(call->active.get());
        when(navigator.advanceHop(any(),anyLong())).thenAnswer(call->{
            int n=frame.incrementAndGet();f.position.setY(64+.6*Math.sin(Math.PI*n/6));
            if(n==6){f.position.setY(64);active.set(false);return RoutineNavigator.Result.ARRIVED;}
            return RoutineNavigator.Result.MOVING;
        });
        doAnswer(call->{active.set(false);return null;}).when(navigator).cancel("thurg");
        start(walk());f.advance(1204);
        assertTrue(f.service.status("thurg").contains("ruta inaccesible"),f.service.status("thurg"));
        verify(navigator,atLeast(10)).startRecoveryHop(any(),anyDouble(),anyDouble(),anyLong());
    }

    @Test void actualHorizontalHopProgressRestartsTheDelayAtItsLanding() throws Exception {
        AtomicBoolean active=new AtomicBoolean();AtomicInteger frame=new AtomicInteger();
        when(navigator.startRecoveryHop(any(),anyDouble(),anyDouble(),anyLong())).thenAnswer(call->{active.set(true);frame.set(0);return true;});
        when(navigator.activeHop("thurg")).thenAnswer(call->active.get());
        when(navigator.advanceHop(any(),anyLong())).thenAnswer(call->{
            f.position.add(.16,0,0);
            if(frame.incrementAndGet()==6){active.set(false);return RoutineNavigator.Result.ARRIVED;}
            return RoutineNavigator.Result.MOVING;
        });
        start(walk());f.advance(132);verify(navigator,times(1)).startRecoveryHop(any(),anyDouble(),anyDouble(),anyLong());
        f.advance(2);verify(navigator).startRecoveryHop(f.npc,.6,1,134);
    }

    @Test void aHopThatMovesOutAndReturnsDoesNotRefreshRouteProgress() throws Exception {
        AtomicBoolean active=new AtomicBoolean();AtomicInteger frame=new AtomicInteger();
        when(navigator.startRecoveryHop(any(),anyDouble(),anyDouble(),anyLong())).thenAnswer(call->{active.set(true);frame.set(0);return true;});
        when(navigator.activeHop("thurg")).thenAnswer(call->active.get());
        when(navigator.advanceHop(any(),anyLong())).thenAnswer(call->{
            int n=frame.incrementAndGet();f.position.setX(n<6?.7:.5);
            if(n==6){active.set(false);return RoutineNavigator.Result.ARRIVED;}
            return RoutineNavigator.Result.MOVING;
        });
        doAnswer(call->{active.set(false);return null;}).when(navigator).cancel("thurg");
        start(walk());f.advance(74);
        assertEquals(2L,field(state(),"travelSince"));assertEquals(.5,f.position.getX());
        f.advance(1130);
        assertTrue(f.service.status("thurg").contains("ruta inaccesible"),f.service.status("thurg"));
    }

    @Test void configurationControlsDelayAndForwardsTheEntireValidHeightAndDistanceRange() throws Exception {
        plugin.settings().messages().set("routines.stuck-hop.delay-seconds",1);
        plugin.settings().messages().set("routines.stuck-hop.height",4);
        plugin.settings().messages().set("routines.stuck-hop.distance",4);
        start(walk());f.advance(20);noHop();f.advance(2);verify(navigator).startRecoveryHop(f.npc,4,4,22);
    }

    @Test void theFeatureCanBeDisabledWithoutChangingNativeMovementCalls() throws Exception {
        plugin.settings().messages().set("routines.stuck-hop.enabled",false);
        start(walk());f.advance(180);noHop();
        verify(navigator,atLeast(50)).move(any(),any(),anyDouble(),anyLong(),anyInt());
    }
}
