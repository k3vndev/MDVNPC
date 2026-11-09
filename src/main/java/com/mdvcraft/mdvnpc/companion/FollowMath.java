package com.mdvcraft.mdvnpc.companion;

/** Geometría pura (sin Bukkit) de seguir y colocarse detrás de un jugador. Testeable. */
public final class FollowMath {
    /** Margen sobre la distancia de seguimiento dentro del cual el acompañante no se mueve. */
    public static final double HOLD_SLACK = 1.0;
    /** Cuánto debe moverse el jugador para recalcular el destino (evita reiniciar rutas cada tick). */
    public static final double RETARGET_DISTANCE = 1.5;

    private FollowMath() {
    }

    /** true si ya está lo bastante cerca y debe quedarse quieto mirando. */
    public static boolean shouldHold(double distance, double followDistance) {
        return distance <= followDistance + HOLD_SLACK;
    }

    /**
     * Punto a {@code followDistance} del jugador, en la línea que une al jugador con el
     * acompañante: así se acerca por el lado en el que ya está, sin rodearlo.
     * Si están prácticamente en el mismo punto, se coloca detrás según {@code targetYaw}.
     */
    public static double[] followPoint(double targetX, double targetZ, double npcX, double npcZ,
                                       float targetYaw, double followDistance) {
        double dx = npcX - targetX, dz = npcZ - targetZ;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1e-3)
            return behind(targetX, targetZ, targetYaw, followDistance);
        return new double[] { targetX + dx / length * followDistance, targetZ + dz / length * followDistance };
    }

    /** Punto a {@code distance} bloques detrás de alguien que mira hacia {@code yaw} (convención Minecraft). */
    public static double[] behind(double x, double z, float yaw, double distance) {
        double radians = Math.toRadians(yaw);
        // Dirección de la mirada en Minecraft: (-sin(yaw), cos(yaw)). Detrás = la opuesta.
        return new double[] { x + Math.sin(radians) * distance, z - Math.cos(radians) * distance };
    }

    public static boolean needsRetarget(double[] previous, double[] next) {
        if (previous == null)
            return true;
        double dx = previous[0] - next[0], dz = previous[1] - next[1];
        return dx * dx + dz * dz > RETARGET_DISTANCE * RETARGET_DISTANCE;
    }

    /** Velocidad de seguimiento: corre si se quedó lejos. */
    public static double speed(double distance, CompanionSettings settings) {
        return distance > settings.catchUpDistance() ? settings.catchUpSpeed() : settings.followSpeed();
    }

    /** Yaw/pitch (grados) para mirar desde {@code from} hacia {@code to} (ojos). */
    public static float[] lookAngles(double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        double dx = toX - fromX, dy = toY - fromY, dz = toZ - fromZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horizontal));
        return new float[] { yaw, pitch };
    }

    public static double angularDifference(float a, float b) {
        return Math.abs(((a - b) % 360 + 540) % 360 - 180);
    }
}
