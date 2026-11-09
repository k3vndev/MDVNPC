package com.mdvcraft.mdvnpc.command;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.NpcParser;
import org.bukkit.Location;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import java.util.*;

public final class NpcCommand implements CommandExecutor, TabCompleter {
    private static final List<String> COMMANDS = List.of("edit", "editor", "menu", "trabajo", "herrero", "pescador", "help", "list", "status", "create", "movehere", "delete", "rename", "skin", "enable", "mode", "shop", "reload", "routine", "rutina", "rutinas", "clock", "rasgo", "trait", "companion");
    private final MdvNpcPlugin plugin;
    private final CompanionCommand companions;
    public NpcCommand(MdvNpcPlugin plugin) { this.plugin = plugin; this.companions = new CompanionCommand(plugin); }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        var msg = plugin.messages();
        if (!sender.hasPermission("mdvnpc.admin")) { msg.send(sender, "no-permission"); return true; }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "edit", "editor", "menu" -> {
                    if(!(sender instanceof Player player)){msg.send(sender,"player-only");return true;}
                    if(args.length!=2)throw new IllegalArgumentException("/mdvnpc edit <id> - abrir editor del NPC");
                    NpcParser.validateId(args[1]);if(!plugin.definitions().containsKey(args[1]))throw new IllegalArgumentException("NPC no encontrado: "+args[1]);
                    plugin.npcEditor().open(player,args[1]);
                }
                case "routine", "rutina", "rutinas" -> plugin.routineCommands().command(sender, args);
                case "companion" -> companions.command(sender, args);
                case "herrero", "pescador" -> {
                    if(!(sender instanceof Player player)){msg.send(sender,"player-only");return true;}
                    if(args.length!=2)throw new IllegalArgumentException("/mdvnpc "+sub+" <id> - categoría de tienda y estaciones");
                    NpcParser.validateId(args[1]);
                    if(!plugin.definitions().containsKey(args[1]))throw new IllegalArgumentException("NPC no encontrado: "+args[1]);
                    plugin.workEditor().open(player,args[1]);
                }
                case "rasgo", "trait" -> {
                    if(args.length<2)throw new IllegalArgumentException("/mdvnpc rasgo <id> [ninguno|alcoholico|lector|gloton|inquieto|ruidoso|fiestero]");
                    String id=args[1];NpcParser.validateId(id);
                    var npc=plugin.definitions().get(id);
                    if(npc==null)throw new IllegalArgumentException("NPC no encontrado: "+id);
                    if(args.length==2){sender.sendMessage("Rasgo de "+id+": "+npc.traits().type());return true;}
                    if(args.length!=3)throw new IllegalArgumentException("Cada NPC puede tener un solo rasgo");
                    var trait=com.mdvcraft.mdvnpc.trait.Trait.parse(args[2]);
                    plugin.shops().prepareReload();
                    plugin.repository().edit(yaml->{
                        String path="npcs."+id+".trait";
                        yaml.set(path+".type",trait.name().toLowerCase(Locale.ROOT));
                        if(!yaml.contains(path+".beer-cooldown-seconds"))yaml.set(path+".beer-cooldown-seconds",npc.traits().beerCooldownSeconds());
                        if(!yaml.contains(path+".beer-dialogues"))yaml.set(path+".beer-dialogues",npc.traits().beerLines());
                    });
                    plugin.reloadNpcs();msg.send(sender,"saved","npc",id);
                }
                case "trabajo" -> {
                    if(args.length!=4 || !Set.of("musico","músico").contains(args[2].toLowerCase(Locale.ROOT)))
                        throw new IllegalArgumentException("/mdvnpc trabajo <id> musico <flauta|guitarra>");
                    String id=args[1]; NpcParser.validateId(id);
                    if(!plugin.definitions().containsKey(id))throw new IllegalArgumentException("NPC no encontrado: "+id);
                    String mode=switch(args[3].toLowerCase(Locale.ROOT)) {
                        case "flauta" -> "musician_flute";
                        case "guitarra" -> "musician_guitar";
                        default -> throw new IllegalArgumentException("Instrumento: flauta o guitarra");
                    };
                    plugin.shops().prepareReload();
                    plugin.repository().edit(yaml -> yaml.set("npcs."+id+".mode",mode));
                    plugin.reloadNpcs();
                    sender.sendMessage("Trabajo Músico asignado a "+id+" ("+args[3]+"). Usa una rutina de trabajo para establecer el horario y el puesto.");
                }
                case "clock" -> plugin.routineCommands().clock(sender, args);
                case "list" -> msg.send(sender, "list", "npcs", String.join(", ", plugin.definitions().keySet()));
                case "status" -> {
                    if(args.length==1)msg.send(sender,"status","count",""+plugin.definitions().size(),"active",""+plugin.manager().activeCount(),"ticks",""+plugin.settings().intervalTicks());
                    else {
                        if(args.length!=2)throw new IllegalArgumentException("/mdvnpc status [id]");
                        NpcParser.validateId(args[1]);var npc=plugin.definitions().get(args[1]);
                        if(npc==null){msg.send(sender,"not-found");return true;}
                        sender.sendMessage(args[1]+": "+plugin.routines().status(args[1]));
                        if(npc.mode().musician())sender.sendMessage("Música: "+plugin.music().status(args[1]));
                    }
                }
                case "reload" -> { plugin.reloadNpcs(); msg.send(sender, "reloaded", "count", "" + plugin.definitions().size()); }
                case "shop" -> {
                    if (!(sender instanceof Player player)) { msg.send(sender, "player-only"); return true; }
                    if (args.length < 2 || !plugin.definitions().containsKey(args[1])) { msg.help(sender); return true; }
                    plugin.shops().openEditor(player, args[1]);
                }
                case "create", "movehere", "delete", "rename", "skin", "enable", "mode" -> {
                    int needed = Set.of("create", "rename", "skin", "enable", "mode").contains(sub) ? 3 : 2;
                    if (args.length < needed) { msg.help(sender); return true; }
                    String id = args[1];
                    NpcParser.validateId(id);
                    if (!sub.equals("create") && !plugin.definitions().containsKey(id)) { msg.send(sender, "not-found"); return true; }
                    if (sub.equals("create") && plugin.definitions().containsKey(id)) throw new IllegalArgumentException("Ya existe " + id);
                    Location location = null;
                    if (sub.equals("create") || sub.equals("movehere")) {
                        if (!(sender instanceof Player player)) { msg.send(sender, "player-only"); return true; }
                        location = player.getLocation();
                    }
                    if (sub.equals("enable") && !args[2].equalsIgnoreCase("true") && !args[2].equalsIgnoreCase("false"))
                        throw new IllegalArgumentException("Usa true o false");
                    if (sub.equals("mode") && !Set.of("normal", "shop").contains(args[2].toLowerCase(Locale.ROOT)))
                        throw new IllegalArgumentException("Modo: normal o shop");
                    Location destination = location;
                    if (sub.equals("movehere") && plugin.routines().enabled(id)
                            && plugin.manager().resolveWorld(plugin.definitions().get(id)) != destination.getWorld())
                        throw new IllegalArgumentException("Desactiva y reconfigura la rutina antes de cambiar el NPC de mundo");
                    plugin.shops().prepareReload(); // Validate/flush editors before changing NPC files.
                    plugin.repository().edit(yaml -> {
                        String p = "npcs." + id;
                        switch (sub) {
                            case "create" -> {
                                yaml.set(p + ".enabled", true);
                                yaml.set(p + ".trait.type", "none");
                                yaml.set(p + ".trait.beer-cooldown-seconds", 20);
                                yaml.set(p + ".trait.beer-dialogues", com.mdvcraft.mdvnpc.model.NpcDefinition.Traits.defaults().beerLines());
                                boolean modeArg = args.length > 3 && Set.of("shop", "normal").contains(args[3].toLowerCase(Locale.ROOT));
                                yaml.set(p + ".mode", modeArg ? args[3].toLowerCase(Locale.ROOT) : "normal");
                                yaml.set(p + ".name", args.length > (modeArg ? 4 : 3)
                                        ? String.join(" ", Arrays.copyOfRange(args, modeArg ? 4 : 3, args.length)) : id);
                                yaml.set(p + ".name-visible", true);
                                yaml.set(p + ".skin.name", args[2]);
                                yaml.set(p + ".look.enabled", true); yaml.set(p + ".look.range", 6);
                                yaml.set(p + ".dialogue.enabled", true); yaml.set(p + ".dialogue.range", 6);
                                yaml.set(p + ".dialogue.interval-seconds", 40); yaml.set(p + ".dialogue.initial-delay-seconds", 2);
                                yaml.set(p + ".dialogue.random", true); yaml.set(p + ".dialogue.lines", List.of("&7{npc} &f» &7Hola, &e{player}&7."));
                                yaml.set(p + ".interaction.cooldown-seconds", 2); yaml.set(p + ".interaction.range", 6);
                                yaml.set(p + ".interaction.unavailable.cooldown-seconds", 3);
                                yaml.set(p + ".interaction.unavailable.random", true);
                                yaml.set(p + ".interaction.unavailable.lines", List.of("&7{npc} &f» &7Ahora mismo no estoy trabajando. Vuelve durante mi horario."));
                                yaml.set(p + ".interaction.commands", List.of());
                                setLocation(yaml, p, destination);
                            }
                            case "movehere" -> setLocation(yaml, p, destination);
                            case "delete" -> yaml.set(p, null);
                            case "rename" -> yaml.set(p + ".name", String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                            case "skin" -> {
                                yaml.set(p + ".skin.name", args[2]);
                                yaml.set(p + ".skin.texture", ""); yaml.set(p + ".skin.signature", ""); yaml.set(p + ".skin.uuid", "");
                            }
                            case "enable" -> yaml.set(p + ".enabled", Boolean.parseBoolean(args[2]));
                            case "mode" -> yaml.set(p + ".mode", args[2].toLowerCase(Locale.ROOT));
                        }
                    });
                    if (sub.equals("skin") || sub.equals("delete")) plugin.skins().invalidate(id);
                    if (sub.equals("delete")) {
                        plugin.shops().closeAll(); // flush editing sessions before deleting this NPC's offers
                        plugin.shops().deleteShop(id);
                        plugin.routines().repository().edit(y -> y.set("npcs." + id, null));
                    }
                    plugin.reloadNpcs();
                    msg.send(sender, sub.equals("delete") ? "deleted" : "saved", "npc", id);
                }
                default -> { msg.help(sender); com.mdvcraft.mdvnpc.routine.RoutineCommands.help(sender); }
            }
        } catch (Exception ex) { msg.send(sender, "error", "error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()); }
        return true;
    }
    private static void setLocation(YamlConfiguration yaml, String path, Location loc) {
        String p = path + ".location.";
        yaml.set(p + "world", loc.getWorld().getName()); yaml.set(p + "world-uuid", loc.getWorld().getUID().toString());
        yaml.set(p + "x", loc.getX()); yaml.set(p + "y", loc.getY()); yaml.set(p + "z", loc.getZ());
        yaml.set(p + "yaw", loc.getYaw()); yaml.set(p + "pitch", loc.getPitch());
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("mdvnpc.admin")) return List.of();
        if(args.length>=2 && args[0].equalsIgnoreCase("trabajo")) {
            Collection<String> options=switch(args.length) {
                case 2 -> plugin.definitions().keySet(); case 3 -> List.of("musico");
                case 4 -> List.of("flauta","guitarra"); default -> List.of();
            };
            return options.stream().filter(v->v.startsWith(args[args.length-1].toLowerCase(Locale.ROOT))).sorted().toList();
        }
        if (args.length >= 2 && Set.of("routine", "rutina", "rutinas").contains(args[0].toLowerCase(Locale.ROOT))) {
            Collection<String> options = switch(args.length) {
                case 2 -> { var ids = new ArrayList<>(plugin.definitions().keySet()); ids.add("cancelar"); yield ids; }
                case 3 -> List.of("goal", "list", "status", "enable", "delete");
                case 4 -> args[2].equalsIgnoreCase("enable") ? List.of("true", "false") : List.of("1", "2", "3", "4", "5");
                case 5 -> List.of("dormir", "caminar", "sentarse", "trabajo");
                case 6 -> Set.of("caminar", "walk").contains(args[4].toLowerCase(Locale.ROOT)) ? List.of("meta", "aleatorio", "ciclo") : List.of("07:00", "12:00", "18:00", "22:00");
                default -> List.of();
            };
            String prefix = args[args.length-1].toLowerCase(Locale.ROOT);
            return options.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("companion")) {
            String prefix = args[args.length-1].toLowerCase(Locale.ROOT);
            return companions.complete(args).stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("clock")) return org.bukkit.Bukkit.getWorlds().stream().map(org.bukkit.World::getName).filter(n -> n.startsWith(args[1])).toList();
        Collection<String> choices = args.length == 1 ? COMMANDS : args.length == 2 && Set.of("status", "edit", "editor", "menu", "herrero", "pescador", "movehere", "delete", "rename", "skin", "enable", "mode", "shop", "rasgo", "trait").contains(args[0].toLowerCase(Locale.ROOT))
                ? plugin.definitions().keySet() : args.length == 3 && Set.of("rasgo","trait").contains(args[0].toLowerCase(Locale.ROOT)) ? List.of("ninguno","alcoholico","lector","gloton","inquieto","ruidoso","fiestero") : args.length == 3 && args[0].equalsIgnoreCase("enable") ? List.of("true", "false") : args.length == 3 && args[0].equalsIgnoreCase("mode") ? List.of("normal", "shop") : List.of();
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
}
