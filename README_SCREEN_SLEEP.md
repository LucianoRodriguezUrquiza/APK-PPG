# Control de pantalla y sueño declarado

Base exacta: `b22-final-ui`, commit `06fa2f13e59e713f1176c56751bce1f4c7a0ebfa`.
Firmware compañero: `LucianoRodriguezUrquiza/PPG-Firmware-ESP32-S3-26-9-2026`, rama
`screen-control-sleep-v1`, archivo `Firmware_BLE.ino`.

## Abrir e instalar

Descargar esta rama con **Code → Download ZIP**, extraer y abrir la carpeta que
contiene `settings.gradle.kts` en Android Studio. Sincronizar Gradle y ejecutar en
el teléfono. Se preservaron las versiones de dependencias y configuración originales.
Para mantener los datos existentes, actualizar usando la misma firma/keystore de
la instalación anterior; no desinstalar ni borrar datos. Los APK debug de GitHub
pueden tener una firma diferente a la de tu Android Studio.

## Uso

- **Conectividad → Ahorro de pantalla**: apagar, encender o consultar. Las acciones
  están deshabilitadas sin conexión o mientras se espera una orden de pantalla.
  No se muestra éxito antes de recibir el ACK del firmware. Timeout/error deja el
  estado sin confirmar y permite consultar/reintentar. Reconectar consulta de nuevo.
- **Registro → Registro de sueño**: «Me voy a dormir» y «Ya me desperté». Fechas
  capturadas al pulsar, confirmadas por persistencia Room. Se recupera el intervalo
  activo al volver a abrir la app, aunque BLE esté desconectado. Se muestra el último
  intervalo finalizado con duración. No hay detección automática ni fases de sueño.

## Persistencia e historial

Room versión 3 añade exclusivamente `sleep_periods(id,startMs,endMs)` mediante
`MIGRATION_2_3`; conserva la migración 1→2 y las cinco tablas previas. Sin migración
 destructiva. Las transacciones serializan inicios simultáneos y evitan duplicados;
finalizar dos veces no cambia la primera hora de fin. Se rechaza una hora anterior
al inicio o al último período cerrado si el reloj del teléfono retrocedió.

La app original utiliza una sola paciente activa. Al confirmar **Reemplazar paciente**,
los períodos se borran junto al historial previo, conforme a esa función existente.

La persistencia del intervalo no implica una nueva capacidad de adquisición en segundo
plano. Se conserva el ciclo de vida BLE/registro anterior: si Android termina el proceso,
no se garantiza adquirir mediciones. El intervalo persiste y los huecos quedan vacíos.

## CSV

Las 52 columnas originales siguen en el mismo orden; se añaden:

- `Estado_sueno`: `SUENO` dentro de un intervalo declarado; `VIGILIA` fuera.
- `sueno_periodo_id`: identificador persistente.
- `sueno_inicio_ms`, `sueno_fin_ms`: epoch Unix en milisegundos, sin pérdida de precisión.
- `sueno_duracion_ms`: duración de períodos cerrados; vacío si sigue activo.

Se clasifica cada medición FC/actividad, SpO₂, PA y PRV por su timestamp original,
con intervalo **[inicio, fin)**. Se incluyen filas `PERIODO_SUENO_DECLARADO` incluso
si no existen mediciones durante ese período. Un período abierto no tiene fin ni
duración definitiva. Los resúmenes diarios/semanales previos conservan su significado
global; sus nuevas columnas quedan vacías. La actividad es independiente de sueño.
`VIGILIA` significa ausencia de declaración de sueño, no vigilia detectada fisiológicamente.
No se inventan datos ni se interpolan huecos. PA/SpO₂/PRV mantienen su carácter exploratorio.

## Verificación reproducible

- `./gradlew testDebugUnitTest`: suite previa y `SleepCsvTest`, con límites exactos,
  medianoche, intervalos abiertos, las cuatro familias de medición y huecos sin datos.
- `./gradlew assembleDebug assembleDebugAndroidTest`: compilación de app y pruebas.
- `SleepPersistenceTest`: migración real 2→3 con datos existentes, doce inicios
  concurrentes, reapertura de la base con intervalo activo, fin idempotente y duración.
- `AppDatabaseMigrationInstrumentedTest`: migración completa 1→3, conserva mediciones
  e inserta/lee las variables exploratorias previas.
- Workflow: `.github/workflows/screen-sleep-validation.yml`; ejecución `40202736934`.

Verificación física pendiente: órdenes de pantalla y ACK con el reloj real;
reconexión apagada; gráfica/registro continuo mientras la luz está apagada; cierre y
reapertura de la app con sueño activo; exportar una noche y contrastar horas reales.
