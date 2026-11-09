# Acompañantes temporales (API de MDVNPC 1.13.0)

Otros plugins pueden crear **acompañantes**: NPC temporales con skin de jugador
que siguen, guían o caminan hasta un punto, hacen gestos y hablan por chat.

- **No se guardan**: no aparecen en `npcs.yml`, no tienen rutinas ni editor y
  desaparecen al reiniciar el servidor. Los NPC normales no cambian en nada.
- **Sobreviven a `/mdvnpc reload`**: el reload solo relee su sección
  `companions:`.
- **Visibles solo para sus visores**: el resto de jugadores no los ve ni puede
  interactuar con ellos.
- **Límite global**: `companions.max-active` (20 por defecto).
- Se eliminan solos cuando se desconectan todos sus visores (configurable por
  acompañante), cuando se deshabilita el plugin que los creó o cuando se apaga
  MDVNPC.

## Dependencia

Compila contra `MDVNPC-<versión>-api.jar`, que solo contiene `com.mdvcraft.mdvnpc.api`
(Maven: `provided` o `system`). En `plugin.yml` añade `softdepend: [MDVNPC]`
(o `depend`).

## Obtener la API

```java
RegisteredServiceProvider<MdvNpcApi> rsp =
        getServer().getServicesManager().getRegistration(MdvNpcApi.class);
MdvNpcApi npcs = rsp == null ? null : rsp.getProvider();
```

Todo se usa desde el **hilo principal**.

## Ejemplo: guía de bienvenida

```java
Companion guia = npcs.spawnCompanion(CompanionSpec.builder(this, spawn)
        .template("guia")        // copia nombre y skin del NPC "guia" (opcional; no lo modifica)
        .viewer(player)          // solo este jugador lo ve
        .build());

guia.gesture(CompanionGesture.WAVE);          // se agacha y golpea el aire dos veces
guia.say("¡Hola {player}! Bienvenido.");      // {player} y {npc} funcionan
guia.follow(player);                          // lo sigue a ~2.5 bloques

// Más tarde: guiarlo hasta las crates.
guia.lead(crates, player).thenAccept(result -> {
    if (result == MoveResult.ARRIVED) guia.say("Aquí están las crates.");
});

// Al terminar:
guia.remove();
```

Si `spawnCompanion` falla lanza `CompanionSpawnException` con un `reason()`:
`DISABLED`, `LIMIT_REACHED`, `UNKNOWN_TEMPLATE`, `INVALID_LOCATION`
(chunk no cargado), `SPAWN_BLOCKED` (otro plugin canceló la aparición) o
`DISGUISE_FAILED`.

## Movimiento

| Método | Qué hace |
|---|---|
| `follow(player[, distancia])` | Sigue al jugador. Corre si se queda lejos; si está a más de `teleport-distance`, en otro mundo o atascado, reaparece detrás de él. |
| `moveTo(destino[, velocidad])` | Camina hasta el destino. Devuelve `CompletableFuture<MoveResult>`. |
| `lead(destino, jugador)` | Camina delante; si el jugador se aleja más de `lead-max-gap`, se para y lo mira hasta que vuelva. |
| `stop()` | Se queda quieto. |
| `teleport(ubicación)` | Lo mueve al instante (también a otro mundo). |

Cada orden nueva cancela la anterior: su future termina con `CANCELLED`.
Otros resultados: `ARRIVED`, `FAILED` (sin ruta o atascado
`stuck-fail-seconds`) y `REMOVED`. El movimiento usa el mismo navegador que las
rutinas (puertas, escalones, nodos de netherita).

Cuando está quieto mira al jugador indicado con `lookAt(player)`, o al que sigue
o guía, o al visor más cercano.

## Gestos y chat

- `gesture(WAVE)`: agacharse + golpe, dos veces (saludo de jugador).
- `gesture(SWING)`: un golpe al aire. `gesture(CROUCH)`: agacharse dos veces.
- `say(mensaje)`: a todos sus visores; `say(jugador, mensaje)`: solo a uno.
  Formato: `&7<nombre> &f» mensaje` (el prefijo de la plantilla no se copia).

## Eventos

- `CompanionInteractEvent`: un visor hizo click derecho/izquierdo sobre él.
  El click vanilla siempre se cancela (no recibe daño ni abre comercio).
- `CompanionRemoveEvent`: se eliminó; `reason()` es `REMOVED`, `VIEWERS_LEFT`,
  `OWNER_DISABLED`, `SHUTDOWN` o `LOST`.

## Probar en el juego: `/mdvnpc companion`

Solo para `mdvnpc.admin`. Cada admin controla un acompañante de prueba visible
solo para él (crear otro sustituye al anterior). Usa la API pública tal cual.

| Comando | Qué hace |
|---|---|
| `spawn [plantilla]` | Lo crea en tu posición. Con plantilla copia nombre y skin de ese NPC (no lo edita). |
| `follow [distancia]` | Te sigue. |
| `lead <x y z>` | Te guía hasta ahí (admite `~`, p. ej. `~20 ~ ~`). Avisa al llegar o fallar. |
| `moveto <x y z>` | Camina hasta ahí sin esperarte. |
| `stop` / `tp` | Quieto / teletransportarlo a ti. |
| `wave` / `swing` / `crouch` | Gestos. |
| `say <texto>` | Habla por chat (solo a sus visores). |
| `show <jugador>` / `hide <jugador>` | Añadir/quitar visores. |
| `remove` | Eliminarlo. |
| `list` | Todos los acompañantes activos de cualquier plugin. |

## Configuración (`config.yml`)

```yaml
companions:
  enabled: true
  max-active: 20
  movement-interval-ticks: 2
  follow-distance: 2.5
  follow-speed: 4.3
  catch-up-speed: 5.6
  catch-up-distance: 7
  teleport-distance: 24
  stuck-teleport-seconds: 3
  move-speed: 3.5
  lead-speed: 3.2
  lead-max-gap: 7
  stuck-fail-seconds: 20
  default-name: '&6Guía'
  default-skin: ''
```

Las configuraciones existentes no necesitan cambios: los valores que falten se
toman del JAR.

## Detalles técnicos

- Entidad: aldeano sin IA disfrazado con LibsDisguises, igual que los NPC
  normales, pero marcado con `companion-id` (no `npc-id`) y la etiqueta
  `MDVNPC_COMPANION`. La limpieza de NPC normales nunca lo toca y viceversa.
- Visibilidad: `setVisibleByDefault(false)` + `Player#showEntity`.
- Excepción de WorldGuard (`worldguard-spawn-bypass`): también aplica a los
  acompañantes en el instante de su aparición.
- Protegido contra daño, fuego, portales, monturas, empujones y teletransportes
  externos.
