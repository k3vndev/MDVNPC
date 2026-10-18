package com.mdvcraft.mdvnpc.config;

import org.bukkit.configuration.file.YamlConfiguration;

public record Settings(int intervalTicks, int lookIntervalTicks, boolean ignoreInvisible, boolean ignoreSpectators,
                       double rotationThreshold, YamlConfiguration messages) {
    public static Settings parse(YamlConfiguration yaml) {
        for (String key : java.util.List.of("update-interval-ticks", "look-update-interval-ticks")) {
            Object value = yaml.get(key);
            if (value != null && (!(value instanceof Number number) || number.doubleValue() != number.intValue()))
                throw new IllegalArgumentException(key + " debe ser entero");
        }
        int ticks = yaml.getInt("update-interval-ticks", 10);
        int lookTicks = yaml.getInt("look-update-interval-ticks", ticks);
        if (lookTicks < 1 || lookTicks > 200) throw new IllegalArgumentException("look-update-interval-ticks: usar 1..200");
        double threshold = yaml.getDouble("rotation-threshold-degrees", 3);
        if (ticks < 1 || ticks > 200) throw new IllegalArgumentException("update-interval-ticks: usar 1..200");
        if (!Double.isFinite(threshold) || threshold < 0 || threshold > 180)
            throw new IllegalArgumentException("rotation-threshold-degrees: usar 0..180");
        // Legacy name-offset keys are ignored: LibsDisguises owns the native nametag again.
        for (String key : java.util.List.of("routines.seat-offset-y")) {
            Object value = yaml.get(key);
            if (value != null && (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                    || number.doubleValue() < -4 || number.doubleValue() > 4))
                throw new IllegalArgumentException(key + ": usar un número entre -4 y 4");
        }
        for (String key : java.util.List.of("routines.dance-duration-seconds", "routines.dance-seated-seconds")) {
            Object value = yaml.get(key);
            if (value != null && (!(value instanceof Number number) || number.doubleValue() != number.intValue()
                    || number.intValue() < 1 || number.intValue() > 600))
                throw new IllegalArgumentException(key + ": usar segundos enteros entre 1 y 600");
        }
        Object hopEnabled = yaml.get("routines.stuck-hop.enabled");
        if (hopEnabled != null && !(hopEnabled instanceof Boolean))
            throw new IllegalArgumentException("routines.stuck-hop.enabled: usar true o false");
        Object hopDelay = yaml.get("routines.stuck-hop.delay-seconds");
        if (hopDelay != null && (!(hopDelay instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != number.intValue() || number.intValue() < 1 || number.intValue() > 60))
            throw new IllegalArgumentException("routines.stuck-hop.delay-seconds: usar segundos enteros entre 1 y 60");
        for (String key : java.util.List.of("routines.stuck-hop.height", "routines.stuck-hop.distance")) {
            Object value = yaml.get(key);
            if (value != null && (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                    || number.doubleValue() < .05 || number.doubleValue() > 4))
                throw new IllegalArgumentException(key + ": usar bloques entre 0.05 y 4");
        }
        Object nodesEnabled=yaml.get("routines.nodes.enabled");
        if(nodesEnabled!=null && !(nodesEnabled instanceof Boolean))
            throw new IllegalArgumentException("routines.nodes.enabled: usar true o false");
        nodeInteger(yaml,"link-range",2,32);
        nodeInteger(yaml,"endpoint-range",2,48);
        nodeInteger(yaml,"scan-margin",0,64);
        nodeInteger(yaml,"max-distance",16,512);
        nodeInteger(yaml,"scan-blocks-per-update",256,65536);
        nodeInteger(yaml,"search-nodes-per-update",16,2048);
        return new Settings(ticks, lookTicks, yaml.getBoolean("ignore-invisible-players", true),
                yaml.getBoolean("ignore-spectators", true), threshold, yaml);
    }
    private static void nodeInteger(YamlConfiguration yaml,String option,int minimum,int maximum) {
        String key="routines.nodes."+option;
        Object value=yaml.get(key);
        if(value!=null && (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue()!=number.intValue() || number.intValue()<minimum || number.intValue()>maximum))
            throw new IllegalArgumentException(key+": usar un entero entre "+minimum+" y "+maximum);
    }
}
