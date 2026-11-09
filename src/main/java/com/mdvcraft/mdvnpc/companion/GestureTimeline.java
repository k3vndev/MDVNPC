package com.mdvcraft.mdvnpc.companion;

import com.mdvcraft.mdvnpc.api.CompanionGesture;

import java.util.List;

/** Pasos (en ticks desde el inicio) de cada gesto. Puro: testeable sin servidor. */
public final class GestureTimeline {
    public enum Action { SNEAK_ON, SNEAK_OFF, SWING }

    public record Step(int tick, Action action) {
    }

    private GestureTimeline() {
    }

    public static List<Step> steps(CompanionGesture gesture) {
        return switch (gesture) {
            // Saludo de jugador: agacharse y golpear el aire dos veces.
            case WAVE -> List.of(
                    new Step(0, Action.SNEAK_ON), new Step(3, Action.SWING), new Step(5, Action.SNEAK_OFF),
                    new Step(8, Action.SNEAK_ON), new Step(11, Action.SWING), new Step(13, Action.SNEAK_OFF));
            case SWING -> List.of(new Step(0, Action.SWING));
            case CROUCH -> List.of(
                    new Step(0, Action.SNEAK_ON), new Step(4, Action.SNEAK_OFF),
                    new Step(8, Action.SNEAK_ON), new Step(12, Action.SNEAK_OFF));
        };
    }

    public static int duration(List<Step> steps) {
        return steps.isEmpty() ? 0 : steps.get(steps.size() - 1).tick();
    }
}
