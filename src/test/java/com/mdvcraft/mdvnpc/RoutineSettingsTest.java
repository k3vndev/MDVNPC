package com.mdvcraft.mdvnpc;

import com.mdvcraft.mdvnpc.config.Settings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoutineSettingsTest {
    @Test void nodeRoutingDefaultsAndValidation() throws Exception {
        try(var stream=getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(stream);
            var yaml=YamlConfiguration.loadConfiguration(new InputStreamReader(stream,StandardCharsets.UTF_8));
            assertDoesNotThrow(()->Settings.parse(yaml));
            assertTrue(yaml.getBoolean("routines.nodes.enabled"));
            assertEquals(12,yaml.getInt("routines.nodes.link-range"));
        }
        assertDoesNotThrow(()->Settings.parse(new YamlConfiguration()));
        for(String option:List.of("link-range","endpoint-range","scan-margin","max-distance",
                "scan-blocks-per-update","search-nodes-per-update")) {
            for(Object value:List.of(-1,100000,2.5,Double.NaN,"muchos")) {
                var yaml=new YamlConfiguration();yaml.set("routines.nodes."+option,value);
                assertThrows(IllegalArgumentException.class,()->Settings.parse(yaml));
            }
        }
        var yaml=new YamlConfiguration();yaml.set("routines.nodes.enabled","true");
        assertThrows(IllegalArgumentException.class,()->Settings.parse(yaml));
    }
    @Test void shippedDefaultsUseFullDanceRestCycleAndHalfBlockSeat() throws Exception {
        try (var stream = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(stream);
            var yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            var settings = Settings.parse(yaml);
            assertEquals(60, settings.messages().getInt("routines.dance-duration-seconds"));
            assertEquals(30, settings.messages().getInt("routines.dance-seated-seconds"));
            assertEquals(.5, settings.messages().getDouble("routines.seat-offset-y"));
            assertFalse(settings.messages().contains("routines.name-offset-seated-y"));
            assertFalse(settings.messages().contains("routines.name-offset-sleeping-y"));
        }
    }

    @Test void malformedSeatOffsetsAreRejectedBeforeNpcReload() {
        for (var key : List.of("routines.seat-offset-y")) {
            for (var value : List.of(Double.NaN, Double.POSITIVE_INFINITY, -4.01, 4.01, "alto")) {
                var yaml = new YamlConfiguration(); yaml.set(key, value);
                var error = assertThrows(IllegalArgumentException.class, () -> Settings.parse(yaml));
                assertTrue(error.getMessage().contains(key));
            }
        }
    }

    @Test void retiredNameOffsetsNeverBlockReloadOfExistingConfigurations() {
        for (var value : List.of(Double.NaN, Double.POSITIVE_INFINITY, -10, 10, "alto")) {
            var yaml = new YamlConfiguration();
            yaml.set("routines.name-offset-seated-y", value);
            yaml.set("routines.name-offset-sleeping-y", value);
            assertDoesNotThrow(() -> Settings.parse(yaml));
        }
    }

    @Test void danceDurationsRequireBoundedWholeSeconds() {
        for (var key : List.of("routines.dance-duration-seconds", "routines.dance-seated-seconds")) {
            for (var value : List.of(0, 601, 30.5, Double.NaN, "sesenta")) {
                var yaml = new YamlConfiguration(); yaml.set(key, value);
                assertThrows(IllegalArgumentException.class, () -> Settings.parse(yaml));
            }
        }
    }

    @Test void existingSeatOverrideAndMissingNewKeysRemainCompatible() {
        var yaml = new YamlConfiguration(); yaml.set("routines.seat-offset-y", 0.0);
        yaml.set("routines.dance-min-seconds", 20); yaml.set("routines.dance-max-seconds", 40);
        assertEquals(0, Settings.parse(yaml).messages().getDouble("routines.seat-offset-y"));
        yaml.set("routines.name-offset-seated-y", -.45); yaml.set("routines.name-offset-sleeping-y", .75);
        assertDoesNotThrow(() -> Settings.parse(yaml));
    }

    @Test void shippedStuckHopDefaultsMatchTheRequestedRecovery() throws Exception {
        try (var stream=getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(stream);
            var settings=Settings.parse(YamlConfiguration.loadConfiguration(new InputStreamReader(stream,StandardCharsets.UTF_8)));
            assertTrue(settings.messages().getBoolean("routines.stuck-hop.enabled"));
            assertEquals(3,settings.messages().getInt("routines.stuck-hop.delay-seconds"));
            assertEquals(.6,settings.messages().getDouble("routines.stuck-hop.height"));
            assertEquals(1,settings.messages().getDouble("routines.stuck-hop.distance"));
            assertEquals(1.3,settings.messages().getDouble("routines.max-climb-height"),"retain the supplied climb preference");
        }
    }

    @Test void malformedRecoveryParametersAreRejectedBeforeReload() {
        for(String key:List.of("routines.stuck-hop.height","routines.stuck-hop.distance"))
            for(Object value:List.of(.049,4.001,Double.NaN,Double.POSITIVE_INFINITY,"alto")) {
                var yaml=new YamlConfiguration();yaml.set(key,value);
                assertTrue(assertThrows(IllegalArgumentException.class,()->Settings.parse(yaml)).getMessage().contains(key));
            }
        for(Object value:List.of(0,61,3.5,Double.NaN,"tres")) {
            var yaml=new YamlConfiguration();yaml.set("routines.stuck-hop.delay-seconds",value);
            assertThrows(IllegalArgumentException.class,()->Settings.parse(yaml));
        }
        var yaml=new YamlConfiguration();yaml.set("routines.stuck-hop.enabled","true");
        assertThrows(IllegalArgumentException.class,()->Settings.parse(yaml));
    }

    @Test void configurableRecoveryBoundariesAndLegacyConfigurationsRemainAccepted() {
        assertDoesNotThrow(()->Settings.parse(new YamlConfiguration()));
        for(double value:new double[]{.05,4}) {
            var yaml=new YamlConfiguration();yaml.set("routines.stuck-hop.height",value);yaml.set("routines.stuck-hop.distance",value);
            yaml.set("routines.stuck-hop.enabled",false);yaml.set("routines.stuck-hop.delay-seconds",value==.05?1:60);
            assertDoesNotThrow(()->Settings.parse(yaml));
        }
    }
}
