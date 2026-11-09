package com.mdvcraft.mdvnpc.command;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.api.Companion;
import com.mdvcraft.mdvnpc.api.CompanionGesture;
import com.mdvcraft.mdvnpc.api.CompanionSpawnException;
import com.mdvcraft.mdvnpc.api.CompanionSpec;
import com.mdvcraft.mdvnpc.api.MoveResult;
import com.mdvcraft.mdvnpc.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /mdvnpc companion ...}: prueba manual de la API de acompañantes. Cada admin
 * controla un acompañante de prueba visible solo para él. Usa la API pública tal cual,
 * así que se comporta igual que uno creado por otro plugin. Nunca edita NPC guardados:
 * la plantilla solo aporta nombre y aspecto.
 */
public final class CompanionCommand {
    public static final List<String> SUBCOMMANDS = List.of("spawn", "follow", "lead", "moveto", "stop", "tp",
            "wave", "swing", "crouch", "say", "show", "hide", "remove", "list");
    private static final String USAGE = "/mdvnpc companion <spawn [plantilla]|follow [distancia]|lead <x y z>|"
            + "moveto <x y z>|stop|tp|wave|swing|crouch|say <texto>|show <jugador>|hide <jugador>|remove|list>";

    private final MdvNpcPlugin plugin;
    private final Map<UUID, UUID> selected = new HashMap<>();

    public CompanionCommand(MdvNpcPlugin plugin) {
        this.plugin = plugin;
    }

    public void command(CommandSender sender, String[] args) {
        var api = plugin.companions();
        if (api == null || !api.companionsEnabled())
            throw new IllegalArgumentException("Los acompañantes están desactivados (companions.enabled)");
        String sub = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) {
            list(sender);
            return;
        }
        if (!(sender instanceof Player player))
            throw new IllegalArgumentException("Solo un jugador puede controlar un acompañante de prueba");
        if (sub.equals("spawn")) {
            spawn(player, args.length >= 3 ? args[2] : null);
            return;
        }
        Companion companion = current(player);
        switch (sub) {
            case "follow" -> {
                if (args.length >= 3)
                    companion.follow(player, number(args[2], "distancia"));
                else
                    companion.follow(player);
                send(player, "&aTe sigue.");
            }
            case "lead" -> report(player, "Guía", companion.lead(target(player, args), player));
            case "moveto" -> report(player, "Camino", companion.moveTo(target(player, args)));
            case "stop" -> { companion.stop(); send(player, "&aQuieto."); }
            case "tp" -> { companion.teleport(player.getLocation()); send(player, "&aTeletransportado a tu posición."); }
            case "wave" -> companion.gesture(CompanionGesture.WAVE);
            case "swing" -> companion.gesture(CompanionGesture.SWING);
            case "crouch" -> companion.gesture(CompanionGesture.CROUCH);
            case "say" -> {
                if (args.length < 3)
                    throw new IllegalArgumentException("/mdvnpc companion say <texto>");
                companion.say(String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
            }
            case "show", "hide" -> {
                if (args.length < 3)
                    throw new IllegalArgumentException("/mdvnpc companion " + sub + " <jugador>");
                Player other = Bukkit.getPlayerExact(args[2]);
                if (other == null)
                    throw new IllegalArgumentException("Jugador no conectado: " + args[2]);
                if (sub.equals("show"))
                    companion.show(other);
                else
                    companion.hide(other);
                send(player, "&a" + (sub.equals("show") ? "Visible" : "Oculto") + " para " + other.getName() + ".");
            }
            case "remove" -> {
                companion.remove();
                selected.remove(player.getUniqueId());
                send(player, "&aAcompañante eliminado.");
            }
            default -> throw new IllegalArgumentException(USAGE);
        }
    }

    private void spawn(Player player, String template) {
        Companion previous = selectedCompanion(player);
        if (previous != null)
            previous.remove();
        var builder = CompanionSpec.builder(plugin, player.getLocation()).viewer(player);
        if (template != null) {
            if (!plugin.definitions().containsKey(template))
                throw new IllegalArgumentException("NPC plantilla no encontrado: " + template);
            builder.template(template);
        }
        Companion companion;
        try {
            companion = plugin.companions().spawnCompanion(builder.build());
        } catch (CompanionSpawnException ex) {
            throw new IllegalArgumentException(ex.reason() + ": " + ex.getMessage());
        }
        selected.put(player.getUniqueId(), companion.id());
        send(player, "&aAcompañante creado" + (template == null ? "" : " con el aspecto de " + template)
                + ". Solo tú lo ves. Prueba &e/mdvnpc companion follow&a.");
    }

    private void list(CommandSender sender) {
        var api = plugin.companions();
        send(sender, "&eAcompañantes activos: &f" + api.activeCompanions() + "/" + api.maxCompanions());
        for (Plugin owner : Bukkit.getPluginManager().getPlugins()) {
            for (Companion c : api.companions(owner)) {
                Location at = c.location();
                String where = at == null || at.getWorld() == null ? "?" : at.getWorld().getName() + " "
                        + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ();
                List<String> viewers = c.viewers().stream().map(id -> {
                    Player p = Bukkit.getPlayer(id);
                    return p == null ? id.toString().substring(0, 8) : p.getName();
                }).toList();
                send(sender, "&7- &f" + c.name() + " &7(" + owner.getName() + ", " + c.state() + ") &f" + where
                        + " &7visores: &f" + String.join(", ", viewers));
            }
        }
    }

    private Companion selectedCompanion(Player player) {
        UUID id = selected.get(player.getUniqueId());
        if (id == null)
            return null;
        var companion = plugin.companions().companion(id).filter(Companion::isValid).orElse(null);
        if (companion == null)
            selected.remove(player.getUniqueId());
        return companion;
    }

    private Companion current(Player player) {
        Companion companion = selectedCompanion(player);
        if (companion == null)
            throw new IllegalArgumentException("No tienes acompañante de prueba: /mdvnpc companion spawn [plantilla]");
        return companion;
    }

    /** {@code <x y z>} admite {@code ~} y {@code ~n} relativos a tu posición. */
    static Location target(Player player, String[] args) {
        if (args.length != 5)
            throw new IllegalArgumentException("/mdvnpc companion " + args[1] + " <x> <y> <z> (admite ~)");
        Location base = player.getLocation();
        return new Location(base.getWorld(), coordinate(args[2], base.getX()),
                coordinate(args[3], base.getY()), coordinate(args[4], base.getZ()));
    }

    static double coordinate(String value, double base) {
        if (value.startsWith("~"))
            return base + (value.length() == 1 ? 0 : number(value.substring(1), "coordenada"));
        double parsed = number(value, "coordenada");
        // Enteros sin decimales = centro del bloque, como hace el juego.
        return value.contains(".") ? parsed : parsed + .5;
    }

    private static double number(String value, String what) {
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed))
                throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Número no válido (" + what + "): " + value);
        }
    }

    private void report(Player player, String what, CompletableFuture<MoveResult> future) {
        send(player, "&a" + what + " en curso...");
        UUID id = player.getUniqueId();
        future.thenAccept(result -> {
            Player online = Bukkit.getPlayer(id);
            if (online != null && result != MoveResult.CANCELLED)
                send(online, (result == MoveResult.ARRIVED ? "&a" : "&c") + what + ": " + result);
        });
    }

    private static void send(CommandSender sender, String text) {
        sender.sendMessage(Text.color(text));
    }

    public List<String> complete(String[] args) {
        if (args.length == 2)
            return SUBCOMMANDS;
        if (args.length == 3) {
            String sub = args[1].toLowerCase(Locale.ROOT);
            if (sub.equals("spawn"))
                return List.copyOf(plugin.definitions().keySet());
            if (sub.equals("show") || sub.equals("hide"))
                return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        if (args.length >= 3 && args.length <= 5 && Arrays.asList("lead", "moveto").contains(args[1].toLowerCase(Locale.ROOT)))
            return List.of("~");
        return List.of();
    }
}
