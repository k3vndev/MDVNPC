# MDVNPC 1.13.0 — acompañantes temporales para otros plugins

- Nueva API pública `com.mdvcraft.mdvnpc.api` registrada en el
  `ServicesManager` (`MdvNpcApi`). Permite a otros plugins crear acompañantes:
  NPC temporales que siguen, guían (`lead`) o caminan a un punto (`moveTo`),
  hacen gestos (`WAVE`, `SWING`, `CROUCH`), hablan por chat y emiten
  `CompanionInteractEvent` / `CompanionRemoveEvent`.
- Los acompañantes no se guardan en `npcs.yml`, no tienen rutinas, sobreviven a
  `/mdvnpc reload` y solo los ven sus jugadores visores. Pueden copiar nombre,
  skin y prefijo de un NPC existente como plantilla sin modificarlo.
- Nueva sección `companions:` en `config.yml` (límite `max-active: 20`,
  velocidades y distancias). Las configuraciones antiguas usan los defaults del
  JAR.
- Los NPC configurados, sus rutinas, tiendas y skins guardadas no cambian: los
  acompañantes usan su propio marcador (`companion-id`), listener y navegador.
  La excepción de spawn de WorldGuard también los cubre.
- El build genera además `MDVNPC-<versión>-api.jar` con solo la API.
- Guía de uso: `docs/GUIA-ACOMPANANTES.md`.

# MDVNPC 1.11.16 — yunque configurable y pesca vanilla

- Cada martillazo del Herrero en la estación de yunque usa ahora
  `BLOCK_ANVIL_PLACE`, el sonido vanilla de colocar un yunque, en lugar de
  `BLOCK_CHAIN_HIT`.
- Se añaden `blacksmith.anvil-hit-volume` y `blacksmith.anvil-hit-pitch` con
  valores por defecto `0.6` y `1.0`; las configuraciones antiguas siguen
  funcionando mediante los defaults del JAR.
- El Pescador deja de crear una boya `ItemDisplay` y un sedal de partículas.
  Envía un `FISHING_BOBBER` vanilla al cliente con el ID del NPC como dueño,
  por lo que Minecraft dibuja el anzuelo y el sedal normal desde la caña.
- El lanzamiento, arco, splash, recasteo, pesca desde tierra/bote, pausas por
  comercio/reacción y limpieza al abandonar la rutina se conservan.
- Se actualizan las pruebas fuente del Herrero y Pescador para los nuevos
  efectos visuales y parámetros.

# MDVNPC 1.11.15 — corrección de arranque

- El constructor público de `FishermanController` sustituye
  `RandomGenerator.getDefault()` por `new Random()`. Evita depender de un
  proveedor opcional del algoritmo `L32X64MixRandom` para activar el plugin.
- Se mantiene la elección aleatoria de puntos, la animación del pescador,
  la reacción a golpes en el bote y el sonido del herrero.
- Se añade una prueba del constructor público con la fábrica anterior
  bloqueada. La validación reproduce el error en Java 21 sin `jdk.random`.

# MDVNPC 1.11.14 — Pescador y clink de Herrero

- Se añade la categoría Pescador conservando `Mode.SHOP`, intercambios y
  horario de Trabajo. El puesto base se configura antes de los puntos de pesca.
- Menú Vendedor/Pescador/Herrero y acceso `/mdvnpc pescador <id>`.
  Configuración guiada de punto de tierra, muelle y punto de bote, con listas
  de hasta 32 puntos de tierra y 32 de bote y eliminación individual.
- Los puntos guardan la posición y dirección de mirada al seleccionarlos.
  Los puntos de bote se marcan en la superficie del agua mediante una consulta
  de mirada de hasta 16 bloques, incluido clic derecho al aire.
- Ciclo con elección aleatoria: tierra durante 45 segundos, camino al muelle,
  bote nativo hasta el punto de agua, pesca durante 45 segundos y regreso al
  muelle para salir, retirar el bote y repetir. Tiempos, distancia de lanzamiento,
  velocidad, plazo de viaje y reintento configurables.
- Caña en mano, boya mediante `ItemDisplay` y sedal de partículas cosméticas.
  No se generan capturas ni recursos reales.
- La caminata mantiene el navegador de Paper. El bote usa búsqueda acuática
  acotada a 96 bloques y 4096 nodos, colisiones de casco/pasajero y sólo chunks
  cargados; contempla el centro de canales de dos bloques y rechaza pasos
  demasiado estrechos o con agua desconectada.
- El comercio pausa la animación sin desplazar al NPC, incluso montado en el
  bote. El fallo de acceso o agua cede a la atención en el puesto base.
- Los golpes mientras el NPC está en el bote conservan su montaje y pausan
  la actividad durante la reacción; después reanuda pesca o navegación en el
  mismo bote. Golpes en tierra y bebidas conservan el regreso habitual.
  Cambios de horario, recarga y retirada liberan efectos y bote.
- La comprobación del espacio del pasajero reserva 2.6 bloques de altura
  desde el bote para evitar techos que intercepten la parte alta del NPC.
- El Herrero sustituye el sonido del martilleo por `BLOCK_CHAIN_HIT`, un clink
  corto ligado a cada gesto. No añade sonidos al finalizar la sesión.
- La comprobación visual de los efectos y el bote dentro del servidor queda
  pendiente. Consulta `GUIA-1.11.14.md` para configuración y límites.

# MDVNPC 1.11.13 — categorías de tienda y animación de Herrero

- Se añade la categoría Herrero a las tiendas conservando `Mode.SHOP`,
  los intercambios y la configuración anterior de NPC Vendedor.
- Menú Vendedor/Herrero, acceso `/mdvnpc herrero <id>` y selección guiada de
  fundición, caldero con agua y yunque después de fijar el punto normal de Trabajo.
- Recorrido por las estaciones con Paper: fundición de RAW_IRON, enfriamiento
  del lingote, yunque con MACE, enfriamiento de espada y otra sesión de yunque.
  Cada sesión de yunque dura 120 segundos por defecto; los tiempos son configurables.
- Sonidos, partículas y lanzamientos cosméticos mediante objetos visuales
  no recogibles. No se producen recursos ni se consume agua del caldero.
- Las coordenadas de fundición permanecen válidas cuando se elimina su bloque.
  Si faltan estaciones o no puede acceder, vuelve al puesto normal y reintenta.
- El comercio, las reacciones, las bebidas y los cambios de horario ceden la
  animación a los sistemas correspondientes y limpian sus objetos visuales.
- Se mantienen el navegador, el salto de recuperación y las rutinas existentes.
  La prueba visual del recorrido en servidor sigue pendiente.

# MDVNPC 1.11.12 — impulso de recuperación sin requisitos de apoyo

- El fallback comienza sin comprobar apoyo, aterrizaje seguro ni arco completo.
  Puede iniciar flotando o con el cuerpo parcialmente incrustado.
- Impulso hacia la mirada con altura/distancia configuradas, recortado por
  colisiones reales por eje durante la ejecución. Una pared no cancela Y.
- Las vallas cerradas y sus cubiertas permanecen como barreras horizontales,
  incluso si están debajo o la trayectoria pasa sobre su altura física.
- La animación termina aunque no haya movimiento posible y se reintenta tras
  el intervalo configurado. Mantiene exclusiones de cama, silla y puesto ocupado.
- Recuperación de suelo después del salto; progreso horizontal neto para evitar
  que una oscilación mantenga una ruta atascada viva indefinidamente.

# MDVNPC 1.11.11 — inmovilidad y recubrimientos sobre vallas

- El contador del fallback precede los retornos de recuperación de suelo
  y deja de reiniciarse por oscilaciones verticales sin avance.
- El salto prueba avances menores, salto vertical y alturas menores si
  no cabe la opción configurada; conserva colisiones y apoyo seguro.
- Slabs, trampillas y bloques directamente sobre vallas cerradas no
  cuentan como apoyos, escalones ni destinos transitables.
- Se detectan vallas inferiores y alturas fraccionarias, manteniendo
  puertas abiertas y puentes separados. Se añaden regresiones específicas.

# MDVNPC 1.11.10 — vallas y recuperación al caminar

- Se impide trepar o saltar vallas conservando la altura inicial de los pies
  durante la subida y durante el arco del salto. Las vallas no son apoyos válidos.
- Recuperación configurable tras 3 segundos de inmovilidad: altura 0.6 y
  avance 1 bloque en la dirección de la mirada, con repetición si sigue quieto.
- Se aplica al caminar hacia meta/ciclo/aleatorio, cama, trabajo y silla;
  se excluyen poses, trabajo en el puesto y otras fases estacionarias.
- El salto valida arco, apoyo, puertas, chunks y movimiento real; no activa AI
  ni atraviesa obstáculos. Los reintentos del mismo goal conservan el contador.
- Se preserva la configuración de subida/bajada de la base 1.11.9. Consultar
  `VALIDACION-1.11.10.md` para los resultados y límites de las pruebas.

# MDVNPC 1.11.8 — superficies parciales sin bucles

- El replay sondea la altura de colisión real delante del NPC aunque Paper mantenga la misma Y entre waypoints; evita `choque -> replan -> choque` en slabs, carpets, stairs y pavimentos mixtos.
- Subidas y bajadas siguen el soporte físico de `DIRT_PATH`, `MUD`, `SOUL_SAND`, losas, escaleras y capas finas, con el mismo límite seguro de un bloque y comprobación completa de colisiones.
- Los waypoints aceptan una tolerancia vertical pequeña y, al coincidir X/Z, se valida el soporte físico para que una diferencia de 1/16–1/8 de bloque no genere órbitas.
- El destino físico se convierte a la celda transitable entera antes de llamar a `Pathfinder.findPath(...)`; una alfombra/path/mud ya no puede hacer que Paper reciba como destino el propio bloque de soporte.
- El editor valida superficies por forma de colisión en vez de `Material#isSolid()`, permitiendo WORK/WALK sobre alfombras, slabs y otras superficies transitables no clasificadas como sólidas.
- Recuperación acotada de NPC restaurado unos píxeles dentro de carpet/bottom slab/capa parcial; los bloques completos no se atraviesan.
- La corrección es común a WALK meta/ciclo/aleatorio, WORK, SLEEP/SIT y retornos porque todos usan el navegador central.
- Se añadieron regresiones de fuente para slab y carpet ocultos entre nodos de igual Y, terreno mixto, `SOUL_SAND`, destinos parciales, selección de superficie y recuperación de carpet.
- La suite nueva queda pendiente de ejecución local en esta entrega porque el entorno no dispone de Maven/dependencias; ver `VALIDACION-1.11.8.md`.

# MDVNPC 1.11.7 — ejecución de rutas y recuperación

- La subida conserva su altura durante la entrada al escalón; el asentamiento ya no la deshace.
- El alcance comienza en 16 y permite 24/32/48/64 también para rodeos en el mismo piso.
- Se detectan ciclos mediante un historial acotado de endpoints y el mejor progreso del viaje.
- Reintentos: colisión local 4 ticks, chunk descargado 20 y búsqueda agotada 100.
- Se valida el desplazamiento real tras un teleport; una respuesta exitosa sin movimiento cuenta como fallo.
- La colisión incluye muros y vallas que sobresalen desde un bloque inferior y el panel real de una puerta abierta.
- Caché de formas acotada a 512 entradas durante una sola actualización, con invalidación por estado y sin guardar puertas.
- Progreso acumulado para velocidades pequeñas y recuperación de suelo al llegar o permanecer en WORK.
- Recuperación de pérdida de suelo para NPC con NoAI, sin intervenir en poses, baile, reacción ni bebida.
- Pruebas de viajes largos a cama/puesto en otra altura, terreno, ciclos, presupuesto y pérdida de suelo.

# MDVNPC 1.11.6 — navegación entre plantas sin regresiones

- Corrige las regresiones de 1.11.5 sobre stairs, mud/path, diagonales, hoyos y llegada a WORK.
- Mantiene la búsqueda ampliada hasta 64 únicamente para destinos en otra planta o parciales en sombra vertical.
- Mantiene gravedad activa en los aldeanos base.

# MDVNPC 1.11.5 — Pisos múltiples y gravedad

- Las rutas parciales que terminan debajo/encima de cama o trabajo ya no se siguen hacia la pared.
- Búsqueda adaptativa ampliada hasta 64 bloques sólo cuando hace falta.
- Los viajes entre plantas conservan un mínimo de alcance durante el viaje para encontrar escaleras, rampas o puertas alejadas.
- Los NPC base tienen gravedad física activa sin reactivar IA ni awareness.
- Test de regresión para el callejón vertical bajo un destino de otra planta.

# MDVNPC 1.11.4 — Suelos parciales y recuperación vertical

- `RoutineNavigator` ahora se apoya en la altura física real del suelo aunque Paper ya haya avanzado al siguiente waypoint.
- Corrige NPC que quedaban girando sobre `DIRT_PATH`, `MUD`, alfombras, slabs y otras superficies con altura parcial.
- Al bajar, el NPC se asienta gradualmente sobre el soporte real una vez que su cuerpo despeja el borde anterior.
- Si un avance horizontal choca con un escalón físico delante del cuerpo, puede subirlo aunque el waypoint intermedio todavía conserve una Y baja.
- Los waypoints admiten una pequeña tolerancia vertical para evitar bucles por diferencias de 1/16–1/8 de bloque, sin saltarse slabs o bloques completos.
- Añadidas pruebas de regresión para `STONE -> DIRT_PATH -> STONE` y `STONE -> MUD -> STONE`.
- La corrección es central al navegador y por tanto aplica a meta, ciclo, aleatorio, cama y puesto de trabajo.

# MDVNPC 1.11.3 — Pathfinding adaptativo y recuperación de atascos

- `RoutineNavigator` amplía el `FOLLOW_RANGE` sólo al detectar falta de progreso: 16 → 24 → 32 bloques.
- Se usa `PathResult.canReachFinalPoint()` y se aceptan rutas parciales/rodeos que no reduzcan inmediatamente la distancia al destino.
- Se eliminó el rechazo global por un chunk vecino descargado; se siguen rechazando el inicio, destino o waypoints que realmente estén en chunks no cargados.
- Reintentos separados: obstáculo local rápido, chunk descargado intermedio y ruta agotada lenta.
- Detector de progreso/atasco para impedir que una ruta mala se repita indefinidamente mirando una pared.
- `RoutineTerrain` separa soporte bajo los pies de colisión lateral; una esquina de pared ya no se interpreta como un escalón.
- Soporte de superficies finas con colisión, incluida alfombra, conservando stairs, slabs, bloques completos y desniveles de hasta un bloque.
- Reproducción de diagonales con deslizamiento corto por eje cuando la caja de 0,60 bloques roza una esquina.
- Cama/asiento: se conservan todos los accesos localmente válidos y sólo se prueba otro lado después de agotar la búsqueda ampliada del lado actual.
- El límite de viaje mide 60 s sin progreso, no 60 s desde la salida; una ruta larga o velocidad baja no se cancela mientras el NPC continúe avanzando.
- Sin cambios a tiendas, diálogos, músicos, traits, puertas, sillas ni almacenamiento fuera de las integraciones necesarias de rutina.
- Guía: `GUIA-1.11.3.md`.

# MDVNPC 1.11.2 — Escaleras, puertas y sillas

- Caminata con las cajas de colisión reales de escaleras y losas, incluida la altura del apoyo bajo el cuerpo del NPC.
- Aproximación al escalón antes de elevarse; desplazamiento horizontal y vertical comparten el límite de velocidad.
- La navegación normal permite subir bloques y cambiar de nivel. El movimiento de baile conserva una pista nivelada.
- Cierre de puertas de madera utilizadas por la ruta, incluidas las que ya estaban abiertas si `routines.close-preopened-doors` está activo.
- La comprobación de ocupación usa el hueco de la puerta: un NPC sentado o quieto al lado ya no retiene su cierre. Se siguen comprobando ocupantes del paso, redstone, cambios de la puerta y protecciones.
- Montaje de sillas verificado contra el soporte exacto; recuperación de desmontajes cercanos y actualización del montaje al aparecer observadores o reanudarse la rutina.
- Limpieza y regreso local ante montajes fallidos; un soporte desaparecido, un vehículo ajeno o un desplazamiento grande obliga a abandonar la pose y reintentar por la ruta.
- Guía: `GUIA-1.11.2.md`. Resultados de comprobaciones locales en `dist/VERIFICACION.txt`; la apariencia y el comportamiento con clientes reales requieren una comprobación en el servidor.

# 1.5.0 — personalidad y prefijos

- Prefijo por NPC desde el editor, con vista previa, eliminación y restauración del formato original.
- Formato compartido para todas las frases propias del NPC, incluidas fuera de horario y enfado.
- Voz en secuencias; ruido espontáneo más largo para ruidoso.
- Reacción cosmética a golpes, pausa de caminata, mirada al atacante y recuperación de rutina.
- Configuración independiente de miradas caminando/sentado, inquieto más activo y gestos configurables.
- Configuración anterior compatible, nuevos tests fuente, workflow intacto. Sin compilación ni ejecución de pruebas locales.
- Guía: PERSONALIDAD-1.5.0.md.

# MDVNPC 1.3.1

- Lectura ocasional sentado con libro, duración configurable y restauración del objeto anterior.
- Miradas espaciadas y suavizadas al desplazarse y sentarse; seguimiento sentado limitado a 3 bloques.
- Mantener cama/asiento durante suspensión y reparar posición/pose de sueño al reactivarse.
- Cerrar puertas de madera previamente abiertas al pasar, con control de obstáculos, redstone y protecciones.
- Tarea compartida y estado acotado; sin cargas de chunks ni escrituras adicionales periódicas.
- Pruebas de regresión añadidas como fuente, sin ejecutar ni compilar por petición del usuario.
- Se preservan GUI, diálogos y almacenamiento de 1.3.0. Workflow de GitHub intacto.
# MDVNPC 1.3.0

- Nuevo editor gráfico con `/mdvnpc routine|rutina|rutinas <npc>`: lista goals, alta rápida y edición por goal.
- Edición de horario, velocidad, puntos/cama/sillas/puesto, modo y radio de caminar desde el GUI.
- Diálogos independientes por goal con rango, intervalo, demora inicial, orden y línea de visión.
- Compatibilidad: los WORK antiguos heredan el diálogo global hasta ser editados.
- Mensaje configurable con cooldown al hacer clic derecho cuando el NPC no está trabajando.
- Persistencia por NPC en `NPCs/<id>/npc.yml`, `routines.yml`, `shop.yml` y `skin-cache.yml`.
- Migración automática de `npcs.yml`, `routines.yml`, `shops.yml` y `skins.yml` con copia `*.legacy-backup`.
- Escrituras optimizadas: editar un NPC o su reloj no reescribe archivos de otros NPC.
- Maven 1.3.0; workflow de GitHub sin cambios de versión manual.

# MDVNPC 1.2.0

- Goals por mundo y hora: dormir, caminar, sentarse y trabajar, con velocidad individual.
- Caminar meta, aleatorio entre puntos marcados y ciclo; horarios nocturnos y validación de superposiciones.
- Selección por clic, preguntas de horario por chat, cancelación y detección de ediciones concurrentes.
- Poses de cama y asiento en stairs; comida y bebida cosméticas, incluida cerveza MMOItems configurable.
- Tiendas y comandos disponibles solamente al llegar al puesto y durante el horario de trabajo.
- Puertas de madera con cierre cuando queda libre el paso, integración opcional con WorldGuard.
- Navegación incremental y caché acotadas, sin tickets de chunks ni escrituras por movimiento.
- Recuperación por horario al cargar zonas; limpieza de asientos, poses y sesiones al retirar NPC.
- Reloj opcional por mundo con duración independiente de día/noche y restauración de doDaylightCycle.
- Maven 1.2.0; build.yml sin cambios. Entrega únicamente fuente, sin compilación final por petición del usuario.
# MDVNPC 1.1.2

- Editor con movimientos reales de inventario: elimina la copia deliberada del cursor.
- Clic, división de pilas, arrastre, teclas numéricas y mano secundaria delegados a Paper.
- Shift + clic en ambos sentidos, respetando capacidad y excluyendo botones.
- Doble clic recoge solo objetos editables, sin extraer controles.
- Persistencia de columnas incompletas en `shops.yml`; retirar un componente ya no restaura la oferta anterior al reabrir.
- Guardar conserva la ventana y su cursor; cambios de página protegidos.
- Barreras de referencias ausentes bloqueadas, con eliminación explícita por clic derecho vacío.
- Se conservan skins, trades, MMOItems y el workflow de GitHub sin cambios de versión en build.yml.
# MDVNPC 1.1.1

Base: ZIP MDVNPC-1.1.0-source.zip entregado por el usuario. Se conservan comandos, NPC normales, tiendas nativas, editor de 100 páginas, diálogos, skins explícitas y excepción de spawn de WorldGuard.

- Skins por nombre: captura de textura firmada, firma y UUID en `skins.yml`; restauración al reiniciar, recargar y reaparecer el NPC. Las texturas explícitas de `npcs.yml` tienen prioridad. `/mdvnpc skin <id> <nombre>` invalida la caché, incluso si se repite el nombre.
- Una sola tarea temporal de captura de skins, cada 20 ticks, con límite de 60 intentos. No se guardan perfiles vacíos. Escritura agrupada en un único trabajador y vaciado al apagar.
- Compras: comprobación de NPC vigente, distancia, mundo, visibilidad del jugador, permisos, versión de ofertas, resultado, costos exactos y cantidades. No se retiran ni entregan ítems manualmente: Paper realiza la transacción.
- La edición de ofertas y la desaparición del NPC invalidan inmediatamente las sesiones existentes; su cierre se difiere fuera del evento de inventario.
- Clic normal y Shift admitidos en el resultado. Teclas numéricas, descarte y otros modos especiales bloqueados en el resultado. Editor de plantillas protegido ante clics cancelados, arrastre y sesiones huérfanas.
- Recarga bloqueada si una página editada no se puede guardar. Al cerrar una página inválida se conserva una copia administrativa en `shop-recovery/`; no se consumen objetos reales del administrador.
- Resolución de MMOItems falla de forma explícita si la API está rota; no convierte silenciosamente referencias en snapshots. Categoría/ID conservados. Resolución compartida para referencias idénticas durante una apertura, sin caché entre aperturas.
- Cantidades superiores al máximo apilable dejan la oferta no disponible, evitando el ajuste implícito de precios del motor vanilla. Índices y cantidades mal formados rechazados.
- Guardado de páginas detecta cambios externos en esa página. Escritura temporal, sincronización a disco, sustitución y copia `.bak` para NPC, tiendas y skins.
- Diálogo comercial agrupado por jugador/NPC/tick; limpieza de estado al recargar y mantenimiento periódico. Se conserva la frecuencia independiente de mirada.
- `shop-allow-cancelled-interaction: false` respeta por defecto la cancelación de otros plugins. La excepción anterior es configurable. `worldguard-spawn-bypass: true` mantiene la excepción original y permite desactivarla.
- Entorno de pruebas con MockBukkit compatible con Paper 1.21.6. Pruebas de regresión de skins, pagos, sesiones, editor y persistencia.
- Maven: versión 1.1.1. `build.yml` conservado byte por byte: el patrón `target/MDVNPC-*.jar` ya permite publicar versiones nuevas sin modificarlo.


## 1.11.8-v4 - recuperación final de superficies parciales
- Añade un fallback conservador cuando Paper omite un nodo intermedio de slab/carpet y el replay normal no puede producir un subpaso.
- El fallback inspecciona una banda corta delante del cuerpo y solo asciende sobre colisiones parciales de hasta 0.51 bloques.
- Evita convertir un slab/carpet válido en `WAITING -> replan -> giro` sin permitir trepar muros o bloques completos.
- La validación CI de v3 quedó en 458 tests / 2 fallos / 0 errores; v4 queda pendiente de `mvn clean verify`.
