package com.mdvcraft.mdvnpc.companion;

import com.mdvcraft.mdvnpc.api.CompanionGesture;
import com.mdvcraft.mdvnpc.api.CompanionSpec;
import com.mdvcraft.mdvnpc.config.Settings;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CompanionCoreTest {
    private static final double EPS = 1e-6;

    // ------------------------------------------------------------ FollowMath

    @Test void holdsInsideFollowDistancePlusSlack() {
        assertTrue(FollowMath.shouldHold(2.5, 2.5));
        assertTrue(FollowMath.shouldHold(3.5, 2.5));
        assertFalse(FollowMath.shouldHold(3.51, 2.5));
    }

    @Test void followPointStaysOnTheCompanionsSideAtTheRequestedDistance() {
        double[] p = FollowMath.followPoint(0, 0, 10, 0, 0, 2.5);
        assertEquals(2.5, p[0], EPS);
        assertEquals(0, p[1], EPS);
        double[] diagonal = FollowMath.followPoint(5, 5, 8, 9, 90, 3);
        assertEquals(3, Math.hypot(diagonal[0] - 5, diagonal[1] - 5), EPS);
    }

    @Test void behindIsOppositeToWhereThePlayerLooks() {
        // yaw 0 mira hacia +Z: detrás es -Z.
        double[] south = FollowMath.behind(0, 0, 0, 2);
        assertEquals(0, south[0], EPS);
        assertEquals(-2, south[1], EPS);
        // yaw 90 mira hacia -X: detrás es +X.
        double[] west = FollowMath.behind(0, 0, 90, 2);
        assertEquals(2, west[0], EPS);
        assertEquals(0, west[1], EPS);
        // Encima del jugador: se coloca detrás.
        double[] same = FollowMath.followPoint(1, 1, 1, 1, 0, 2);
        assertEquals(-1, same[1], EPS);
    }

    @Test void retargetsOnlyAfterTheTargetMovesEnough() {
        assertTrue(FollowMath.needsRetarget(null, new double[] { 0, 0 }));
        assertFalse(FollowMath.needsRetarget(new double[] { 0, 0 }, new double[] { 1, 1 }));
        assertTrue(FollowMath.needsRetarget(new double[] { 0, 0 }, new double[] { 1.2, 1.2 }));
    }

    @Test void runsWhenFarBehind() {
        var s = CompanionSettings.defaults();
        assertEquals(s.followSpeed(), FollowMath.speed(s.catchUpDistance(), s));
        assertEquals(s.catchUpSpeed(), FollowMath.speed(s.catchUpDistance() + .1, s));
    }

    @Test void lookAnglesUseMinecraftConvention() {
        float[] south = FollowMath.lookAngles(0, 0, 0, 0, 0, 5);
        assertEquals(0, south[0], 1e-3);
        assertEquals(0, south[1], 1e-3);
        float[] west = FollowMath.lookAngles(0, 0, 0, -5, 0, 0);
        assertEquals(90, west[0], 1e-3);
        float[] up = FollowMath.lookAngles(0, 0, 0, 0, 5, 5);
        assertEquals(-45, up[1], 1e-3);
        assertEquals(10, FollowMath.angularDifference(355, 5), 1e-6);
    }

    // ------------------------------------------------------------ SafeSpot

    @Test void safeSpotPrefersSameHeightThenBelowThenAbove() {
        assertEquals(64, SafeSpot.nearestY(0, 64, 0, 3, (x, y, z) -> true).getAsInt());
        assertEquals(63, SafeSpot.nearestY(0, 64, 0, 3, (x, y, z) -> y == 63 || y == 65).getAsInt());
        assertEquals(66, SafeSpot.nearestY(0, 64, 0, 3, (x, y, z) -> y == 66).getAsInt());
        assertTrue(SafeSpot.nearestY(0, 64, 0, 3, (x, y, z) -> y == 68).isEmpty());
    }

    // ------------------------------------------------------------ gestos

    @Test void waveIsTwoCrouchesWithSwingsAndEndsStanding() {
        var steps = GestureTimeline.steps(CompanionGesture.WAVE);
        assertEquals(2, steps.stream().filter(s -> s.action() == GestureTimeline.Action.SWING).count());
        assertEquals(GestureTimeline.Action.SNEAK_ON, steps.get(0).action());
        assertEquals(GestureTimeline.Action.SNEAK_OFF, steps.get(steps.size() - 1).action());
        for (CompanionGesture gesture : CompanionGesture.values()) {
            var timeline = GestureTimeline.steps(gesture);
            for (int i = 1; i < timeline.size(); i++)
                assertTrue(timeline.get(i - 1).tick() <= timeline.get(i).tick(), gesture + " desordenado");
            assertTrue(GestureTimeline.duration(timeline) <= 40, gesture + " demasiado largo");
        }
    }

    // ------------------------------------------------------------ ajustes

    @Test void shippedConfigMatchesDefaultsAndStillParses() throws Exception {
        try (var stream = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(stream);
            var yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            assertDoesNotThrow(() -> Settings.parse(yaml));
            assertEquals(CompanionSettings.defaults(), CompanionSettings.from(yaml));
        }
    }

    @Test void missingOrBrokenValuesFallBackToSafeLimits() {
        assertEquals(CompanionSettings.defaults(), CompanionSettings.from(null));
        assertEquals(CompanionSettings.defaults(), CompanionSettings.from(new YamlConfiguration()));
        var yaml = new YamlConfiguration();
        yaml.set("companions.max-active", -5);
        yaml.set("companions.follow-distance", 100);
        yaml.set("companions.follow-speed", Double.NaN);
        yaml.set("companions.movement-interval-ticks", 50);
        var s = CompanionSettings.from(yaml);
        assertEquals(0, s.maxActive());
        assertEquals(8, s.followDistance());
        assertEquals(.5, s.followSpeed());
        assertEquals(4, s.cadence());
    }

    // ------------------------------------------------------------ spec y definición

    @Test void specValidatesWorldAndName() {
        Plugin owner = mock(Plugin.class);
        assertThrows(IllegalArgumentException.class,
                () -> CompanionSpec.builder(owner, new Location(null, 0, 0, 0)).build());
        var spec = CompanionSpec.builder(owner, new Location(mock(org.bukkit.World.class), 0, 0, 0))
                .name("  ").skin("").viewer(UUID.randomUUID()).build();
        assertNull(spec.name());
        assertNull(spec.skin());
        assertTrue(spec.removeWhenViewersLeave());
        assertThrows(IllegalArgumentException.class, () -> CompanionSpec.builder(owner,
                new Location(mock(org.bukkit.World.class), 0, 0, 0)).name("x".repeat(65)).build());
    }

    @Test void definitionCopiesTemplateLookButNeverItsInteractionsOrDialogue() {
        Plugin owner = mock(Plugin.class);
        var template = new NpcDefinition("guia", true, "&bAria", false,
                new NpcDefinition.Position(UUID.randomUUID(), "world", 1, 2, 3, 0, 0),
                new NpcDefinition.Skin("Notch", "tex", "sig", null),
                new NpcDefinition.Look(true, 8, true, true),
                new NpcDefinition.Dialogue(true, 8, 10, 0, true, true, List.of("hola")),
                new NpcDefinition.Interaction(4, 1, true, null,
                        List.of(new NpcDefinition.Action(NpcDefinition.Click.RIGHT, NpcDefinition.Executor.CONSOLE, "op {player}"))),
                NpcDefinition.Mode.SHOP, new NpcDefinition.TradeDialogue(true, 5, true, List.of("x")),
                NpcDefinition.Traits.defaults(), new NpcDefinition.Speech("&b[Aria]", null));
        var at = new Location(null, 10, 64, 10);
        var spec = CompanionSpec.builder(owner, new Location(mock(org.bukkit.World.class), 0, 0, 0)).template("guia").build();
        var def = CompanionDefinitions.create("companion-1", template, spec, CompanionSettings.defaults(), at);
        assertEquals("companion-1", def.id());
        assertEquals("&bAria", def.name());
        assertFalse(def.nameVisible());
        assertEquals("&b[Aria]", def.speech().prefix());
        assertEquals(NpcDefinition.Mode.NORMAL, def.mode());
        assertTrue(def.interaction().actions().isEmpty());
        assertFalse(def.dialogue().enabled());
        assertFalse(def.look().enabled());
        // La skin cacheada de la plantilla se usa tal cual; el spec puede sustituirla.
        var cached = new NpcDefinition.Skin("Notch", "cached", "sig2", null);
        assertSame(cached, CompanionDefinitions.skin(template, spec, CompanionSettings.defaults(), def, cached));
        var custom = CompanionSpec.builder(owner, new Location(mock(org.bukkit.World.class), 0, 0, 0))
                .template("guia").skin("Kev").name("&eOtro").nameVisible(true).build();
        var overridden = CompanionDefinitions.create("companion-2", template, custom, CompanionSettings.defaults(), at);
        assertEquals("&eOtro", overridden.name());
        assertTrue(overridden.nameVisible());
        assertEquals("Kev", CompanionDefinitions.skin(template, custom, CompanionSettings.defaults(), overridden, cached).name());
    }

    @Test void definitionWithoutTemplateUsesConfiguredDefaults() {
        var spec = CompanionSpec.builder(mock(Plugin.class), new Location(mock(org.bukkit.World.class), 0, 0, 0)).build();
        var def = CompanionDefinitions.create("companion-3", null, spec, CompanionSettings.defaults(), new Location(null, 0, 0, 0));
        assertEquals("&6Guía", def.name());
        assertTrue(def.nameVisible());
        assertNull(def.speech().prefix());
        assertEquals("Steve", def.skin().name());
    }
}
