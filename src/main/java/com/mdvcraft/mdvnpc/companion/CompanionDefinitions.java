package com.mdvcraft.mdvnpc.companion;

import com.mdvcraft.mdvnpc.api.CompanionSpec;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import org.bukkit.Location;

import java.util.List;

/**
 * Construye la {@link NpcDefinition} en memoria de un acompañante. Nunca se registra en
 * {@code plugin.definitions()} ni se guarda: solo alimenta al disfraz, al navegador y a los mensajes.
 */
final class CompanionDefinitions {
    private CompanionDefinitions() {
    }

    static NpcDefinition create(String id, NpcDefinition template, CompanionSpec spec,
                                CompanionSettings settings, Location at) {
        String name = spec.name() != null ? spec.name()
                : template != null ? template.name() : settings.defaultName();
        boolean nameVisible = spec.nameVisible() != null ? spec.nameVisible()
                : template == null || template.nameVisible();
        var world = at.getWorld();
        var position = new NpcDefinition.Position(world == null ? null : world.getUID(),
                world == null ? "" : world.getName(), at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch());
        var skin = template != null ? template.skin() : new NpcDefinition.Skin(fallbackSkin(settings), "", "", null);
        return new NpcDefinition(id, true, name, nameVisible, position, skin,
                new NpcDefinition.Look(false, 0, false, false),
                new NpcDefinition.Dialogue(false, 0, 0, 0, false, false, List.of()),
                new NpcDefinition.Interaction(0, 0, false, null, List.of()),
                NpcDefinition.Mode.NORMAL,
                new NpcDefinition.TradeDialogue(false, 20, true, List.of()),
                NpcDefinition.Traits.defaults(),
                // De la plantilla solo se copian nombre y aspecto; habla con el formato por defecto.
                NpcDefinition.Speech.defaults());
    }

    /**
     * Skin final: la del spec (nombre de jugador) gana; si no, la de la plantilla tal como
     * está en caché ({@code resolvedTemplateSkin}, solo lectura); si no, la configurada por defecto.
     */
    static NpcDefinition.Skin skin(NpcDefinition template, CompanionSpec spec, CompanionSettings settings,
                                   NpcDefinition definition, NpcDefinition.Skin resolvedTemplateSkin) {
        if (spec.skin() != null)
            return new NpcDefinition.Skin(spec.skin(), "", "", null);
        if (template != null)
            return resolvedTemplateSkin != null ? resolvedTemplateSkin : template.skin();
        return definition.skin();
    }

    static String fallbackSkin(CompanionSettings settings) {
        if (settings.defaultSkin() != null && !settings.defaultSkin().isBlank())
            return settings.defaultSkin().trim();
        // Un nombre visible como "Guía" no es un jugador real: mejor una skin predecible.
        return "Steve";
    }
}
