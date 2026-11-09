package com.mdvcraft.mdvnpc.api;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Datos para crear un acompañante.
 *
 * <pre>{@code
 * CompanionSpec spec = CompanionSpec.builder(plugin, player.getLocation())
 *     .template("guia")      // copia nombre y skin del NPC "guia" (sin modificarlo)
 *     .viewer(player)        // solo este jugador lo ve
 *     .build();
 * }</pre>
 */
public final class CompanionSpec {
    private final Plugin owner;
    private final Location spawn;
    private final String templateId;
    private final String name;
    private final String skin;
    private final Boolean nameVisible;
    private final Set<UUID> viewers;
    private final boolean removeWhenViewersLeave;

    private CompanionSpec(Builder builder) {
        this.owner = builder.owner;
        this.spawn = builder.spawn.clone();
        this.templateId = builder.templateId;
        this.name = builder.name;
        this.skin = builder.skin;
        this.nameVisible = builder.nameVisible;
        this.viewers = Set.copyOf(builder.viewers);
        this.removeWhenViewersLeave = builder.removeWhenViewersLeave;
    }

    public static Builder builder(Plugin owner, Location spawn) {
        return new Builder(owner, spawn);
    }

    public Plugin owner() { return owner; }
    public Location spawn() { return spawn.clone(); }
    public @Nullable String templateId() { return templateId; }
    public @Nullable String name() { return name; }
    public @Nullable String skin() { return skin; }
    public @Nullable Boolean nameVisible() { return nameVisible; }
    public Set<UUID> viewers() { return viewers; }
    public boolean removeWhenViewersLeave() { return removeWhenViewersLeave; }

    public static final class Builder {
        private final Plugin owner;
        private final Location spawn;
        private String templateId;
        private String name;
        private String skin;
        private Boolean nameVisible;
        private final Set<UUID> viewers = new LinkedHashSet<>();
        private boolean removeWhenViewersLeave = true;

        private Builder(Plugin owner, Location spawn) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.spawn = Objects.requireNonNull(spawn, "spawn").clone();
        }

        /** Id de un NPC de {@code npcs.yml} del que copiar nombre, skin y visibilidad del nombre. El NPC no se modifica. */
        public Builder template(String npcId) { this.templateId = blankToNull(npcId); return this; }

        /** Nombre mostrado (admite códigos {@code &}). Sustituye al de la plantilla. */
        public Builder name(String name) { this.name = blankToNull(name); return this; }

        /** Nombre de cuenta de Minecraft cuya skin usar. Sustituye a la de la plantilla. */
        public Builder skin(String playerName) { this.skin = blankToNull(playerName); return this; }

        public Builder nameVisible(boolean visible) { this.nameVisible = visible; return this; }

        /** Añade un jugador que podrá ver al acompañante. */
        public Builder viewer(Player player) { viewers.add(Objects.requireNonNull(player, "player").getUniqueId()); return this; }

        public Builder viewer(UUID playerId) { viewers.add(Objects.requireNonNull(playerId, "playerId")); return this; }

        /** true (por defecto): se elimina solo cuando ningún visor sigue conectado. */
        public Builder removeWhenViewersLeave(boolean value) { this.removeWhenViewersLeave = value; return this; }

        public CompanionSpec build() {
            if (spawn.getWorld() == null)
                throw new IllegalArgumentException("La ubicación de aparición no tiene mundo");
            if (name != null && name.length() > 64)
                throw new IllegalArgumentException("Nombre: máximo 64 caracteres");
            return new CompanionSpec(this);
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }
}
