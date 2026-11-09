package com.mdvcraft.mdvnpc.companion;

import org.bukkit.configuration.ConfigurationSection;

/** Sección {@code companions:} de config.yml, con límites seguros. */
public record CompanionSettings(
        boolean enabled,
        int maxActive,
        int cadence,
        double followDistance,
        double followSpeed,
        double catchUpSpeed,
        double catchUpDistance,
        double teleportDistance,
        double moveSpeed,
        double leadSpeed,
        double leadMaxGap,
        int stuckTeleportTicks,
        int stuckFailTicks,
        String defaultName,
        String defaultSkin) {

    public static CompanionSettings defaults() {
        return new CompanionSettings(true, 20, 2, 2.5, 4.3, 5.6, 7, 24, 3.5, 3.2, 7, 60, 400, "&6Guía", "");
    }

    public static CompanionSettings from(ConfigurationSection root) {
        CompanionSettings d = defaults();
        if (root == null)
            return d;
        ConfigurationSection c = root.getConfigurationSection("companions");
        if (c == null)
            return d;
        return new CompanionSettings(
                c.getBoolean("enabled", d.enabled),
                clamp(c.getInt("max-active", d.maxActive), 0, 200),
                clamp(c.getInt("movement-interval-ticks", d.cadence), 1, 4),
                clamp(c.getDouble("follow-distance", d.followDistance), 1.5, 8),
                clamp(c.getDouble("follow-speed", d.followSpeed), .5, 8),
                clamp(c.getDouble("catch-up-speed", d.catchUpSpeed), .5, 8),
                clamp(c.getDouble("catch-up-distance", d.catchUpDistance), 3, 32),
                clamp(c.getDouble("teleport-distance", d.teleportDistance), 8, 128),
                clamp(c.getDouble("move-speed", d.moveSpeed), .5, 8),
                clamp(c.getDouble("lead-speed", d.leadSpeed), .5, 8),
                clamp(c.getDouble("lead-max-gap", d.leadMaxGap), 3, 32),
                clamp(c.getInt("stuck-teleport-seconds", d.stuckTeleportTicks / 20), 1, 60) * 20,
                clamp(c.getInt("stuck-fail-seconds", d.stuckFailTicks / 20), 5, 300) * 20,
                c.getString("default-name", d.defaultName),
                c.getString("default-skin", d.defaultSkin));
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value))
            return min;
        return Math.max(min, Math.min(max, value));
    }
}
