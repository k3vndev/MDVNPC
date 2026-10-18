# Rutas con nodos de netherita

Los desplazamientos de las rutinas pueden usar bloques de netherita enterrados como nodos. El NPC busca un nodo de entrada al que pueda llegar, recorre conexiones transitables entre nodos y termina el trayecto hasta su destino. No hace falta registrar cada bloque con un comando.

## Colocar los nodos

Enterrá cada bloque de netherita debajo de **un bloque de suelo**. El punto de paso queda sobre ese suelo, dos bloques por encima de la base de la netherita:

```text
Y + 3   aire para la cabeza del NPC
Y + 2   aire para los pies del NPC  ← punto de paso
Y + 1   bloque de suelo
Y       bloque de netherita        ← marcador
```

Dejá espacio para el cuerpo del NPC y usá suelo que pueda pisar. El marcador debe tener exactamente ese bloque de cobertura; si lo enterrás más profundo no señala la superficie.

Como punto de partida, separá los nodos **entre 4 y 8 bloques** entre sí, con un camino transitable entre cada par. El máximo de conexión predeterminado es **12 bloques**, contando también la diferencia de altura. Colocá más nodos en esquinas, entradas, pasillos y escaleras. El NPC y el destino deben tener una conexión transitable a un nodo situado **hasta 16 bloques** de distancia. Se elige una entrada alcanzable; el nodo geométricamente más cercano puede estar detrás de una pared.

Los nodos pueden estar a distintas alturas, incluso en puentes o pisos superpuestos. Para subir uno o dos bloques, construí escalones, losas o una escalera transitable y poné nodos a lo largo del recorrido. La diferencia de altura entre marcadores no crea un salto ni permite atravesar paredes, techos o huecos. Las comprobaciones de suelo, espacio libre y puertas siguen activas.

## Funcionamiento y límites

- La red se descubre automáticamente en los **chunks cargados**; buscar nodos no carga terreno.
- Por defecto se consideran viajes con una separación de hasta **256 bloques por eje horizontal**, dentro del rectángulo entre origen y destino ampliado **24 bloques** a cada lado. Un desvío fuera de ese rectángulo necesita ajustar el margen.
- El área explorada admite hasta **512 marcadores**. Si supera ese límite, se usa el navegador de Paper para ese viaje. Evitá grandes superficies enteras hechas de netherita bajo el suelo.
- Cada conexión entre dos puntos se calcula en el rectángulo que los contiene, ampliado **4 bloques por lado y en altura**. Para rodear un edificio o subir una escalera larga, poné nodos intermedios en las esquinas y los descansos.
- El escaneo y la búsqueda se reparten entre actualizaciones para limitar el trabajo del servidor. Una red recién descubierta puede requerir varias actualizaciones antes de empezar a caminar.
- Si no hay una red utilizable, el NPC vuelve a intentar el recorrido con el navegador de Paper.
- Colocar o romper bloques, explosiones, pistones y la carga o descarga de chunks invalidan los datos afectados. Los cambios realizados por herramientas que no emitan esos eventos también se recogen al vencer la caché.

## Configuración

En `config.yml`, dentro de `routines`, se pueden ajustar estos valores. Al desactivar `enabled`, las rutinas usan el navegador de Paper directamente.

```yaml
routines:
  nodes:
    enabled: true
    link-range: 12
    endpoint-range: 16
    scan-margin: 24
    max-distance: 256
    scan-blocks-per-update: 8192
    search-nodes-per-update: 256
```

`link-range` limita la separación entre nodos conectados. `endpoint-range` limita la conexión del origen y del destino con la red. `scan-margin` amplía el área explorada; `max-distance` limita la separación horizontal del viaje admitida para la red. Los dos valores `per-update` regulan cuánto trabajo se permite por actualización; aumentarlos acelera el cálculo a costa de más trabajo del servidor.

## Instalar y probar

1. Detené el servidor y reemplazá el JAR de MDVNPC en `plugins`, dejando un solo JAR del plugin.
2. Iniciá el servidor. Los archivos de configuración existentes usan los valores predeterminados de las opciones nuevas aunque todavía no tengan la sección `nodes`; podés agregarla para personalizarlos.
3. Tras editar `config.yml`, ejecutá `/mdvnpc reload` para aplicar los cambios.

Para probarlo, marcá un camino corto con una esquina y una escalera, asigná al NPC una rutina cuyo destino esté al otro extremo y comprobá que el espacio sea transitable en ambos sentidos.
