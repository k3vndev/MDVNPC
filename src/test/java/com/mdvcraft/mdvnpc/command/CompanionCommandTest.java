package com.mdvcraft.mdvnpc.command;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.api.Companion;
import com.mdvcraft.mdvnpc.api.CompanionGesture;
import com.mdvcraft.mdvnpc.api.CompanionSpec;
import com.mdvcraft.mdvnpc.api.MoveResult;
import com.mdvcraft.mdvnpc.companion.CompanionService;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CompanionCommandTest {
    ServerMock server;
    PlayerMock admin;
    MdvNpcPlugin plugin;
    CompanionService api;
    Companion companion;
    NpcDefinition template;
    CompanionCommand command;

    @BeforeEach void setup() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        admin = server.addPlayer();
        plugin = mock(MdvNpcPlugin.class);
        api = mock(CompanionService.class);
        companion = mock(Companion.class);
        template = mock(NpcDefinition.class);
        UUID id = UUID.randomUUID();
        when(plugin.companions()).thenReturn(api);
        when(plugin.definitions()).thenReturn(Map.of("guia", template));
        when(api.companionsEnabled()).thenReturn(true);
        when(api.spawnCompanion(any())).thenReturn(companion);
        when(api.companion(id)).thenReturn(Optional.of(companion));
        when(companion.id()).thenReturn(id);
        when(companion.isValid()).thenReturn(true);
        command = new CompanionCommand(plugin);
    }

    @AfterEach void cleanup() { MockBukkit.unmock(); }

    @Test void spawnWithTemplateOnlyReadsTheExistingNpc() {
        command.command(admin, new String[] { "companion", "spawn", "guia" });
        var spec = ArgumentCaptor.forClass(CompanionSpec.class);
        verify(api).spawnCompanion(spec.capture());
        assertEquals("guia", spec.getValue().templateId());
        assertEquals(java.util.Set.of(admin.getUniqueId()), spec.getValue().viewers());
        verifyNoInteractions(template);
        verify(plugin, never()).repository();
        verify(plugin, never()).skins();
    }

    @Test void unknownTemplateIsRejectedBeforeSpawning() {
        assertThrows(IllegalArgumentException.class,
                () -> command.command(admin, new String[] { "companion", "spawn", "nadie" }));
        verify(api, never()).spawnCompanion(any());
    }

    @Test void controlsTheSelectedCompanionAndReplacesItOnRespawn() {
        command.command(admin, new String[] { "companion", "spawn" });
        command.command(admin, new String[] { "companion", "follow", "3" });
        verify(companion).follow(admin, 3.0);
        command.command(admin, new String[] { "companion", "wave" });
        verify(companion).gesture(CompanionGesture.WAVE);
        command.command(admin, new String[] { "companion", "say", "hola", "mundo" });
        verify(companion).say("hola mundo");
        when(companion.lead(any(), eq(admin))).thenReturn(CompletableFuture.completedFuture(MoveResult.ARRIVED));
        command.command(admin, new String[] { "companion", "lead", "~10", "~", "5" });
        var target = ArgumentCaptor.forClass(Location.class);
        verify(companion).lead(target.capture(), eq(admin));
        assertEquals(admin.getLocation().getX() + 10, target.getValue().getX(), 1e-9);
        assertEquals(5.5, target.getValue().getZ(), 1e-9);
        command.command(admin, new String[] { "companion", "spawn" });
        verify(companion).remove();
    }

    @Test void withoutCompanionTheCommandExplainsHowToCreateOne() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> command.command(admin, new String[] { "companion", "follow" }));
        assertTrue(error.getMessage().contains("spawn"));
    }

    @Test void disabledCompanionsAreReported() {
        when(api.companionsEnabled()).thenReturn(false);
        assertThrows(IllegalArgumentException.class,
                () -> command.command(admin, new String[] { "companion", "spawn" }));
    }

    @Test void coordinatesSupportRelativeAndBlockCenters() {
        assertEquals(12.5, CompanionCommand.coordinate("12", 0), 1e-9);
        assertEquals(12.25, CompanionCommand.coordinate("12.25", 0), 1e-9);
        assertEquals(7, CompanionCommand.coordinate("~", 7), 1e-9);
        assertEquals(4, CompanionCommand.coordinate("~-3", 7), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> CompanionCommand.coordinate("x", 0));
    }

    @Test void tabCompletionOffersTemplatesAndSubcommands() {
        assertTrue(command.complete(new String[] { "companion", "" }).contains("spawn"));
        assertEquals(java.util.List.of("guia"), command.complete(new String[] { "companion", "spawn", "" }));
    }
}
