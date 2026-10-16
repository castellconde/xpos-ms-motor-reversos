# Reto Técnico y Propuesta de Mejoras a la Arquitectura POS en ARO

**Fecha:** 2026-10-16  
**Documento evaluado:** [propuesta-arquitectura-integracion-pos-aro.md](file:///D:/Projects/Arquitectura%20XPOS-SE/propuesta-arquitectura-integracion-pos-aro.md) (v0.1)  
**Rol revisor:** Principal Cloud & Java Architect / Application Security & SDET Lead

---

## 1. Resumen Ejecutivo del Reto Técnico

La propuesta v0.1 establece una base sólida (principios *Offline-First*, aislamiento PCI-DSS, fachada desacoplada de Kafka y motor canónico de transacciones). Sin embargo, al someter el diseño a pruebas de estrés conceptuales para **~50,000 puntos de venta concurrentes**, emergen **7 riesgos técnicos y contradicciones operativas** que comprometerían la disponibilidad, el cumplimiento PCI-DSS o el presupuesto de infraestructura en producción si no se corrigen antes de pasar a ARO.

| # | Hallazgo / Reto Técnico | Impacto en Producción | Severidad | Sección Afectada |
| :--- | :--- | :--- | :--- | :--- |
| **RT-01** | **Ruptura de `RFC 8705` (mTLS Certificate-Bound Tokens) por doble terminación TLS** entre WAF (F5/AppGW) y API Gateway (Kong/Connectivity Link) | Pérdida del thumbprint del certificado de caja o vulnerabilidad de *Header Spoofing* si no se protege `X-Forwarded-Client-Cert` | **Crítico** | Seguridad POS / Borde |
| **RT-02** | **Deadlock en Circuit Breaker + Kafka Pause** y escalamiento espurio de KEDA | Si el consumidor pausa sus particiones en estado `OPEN`, nunca ejecuta llamadas de prueba en `HALF_OPEN` para volver a `CLOSED`; mientras tanto, el lag sube y KEDA escala pods al máximo inútilmente | **Crítico** | Motor / Servicio de Reversos |
| **RT-03** | **Agotamiento de conexiones PostgreSQL (Connection Pool Exhaustion)** por transacciones abiertas durante llamadas a proveedores lentos | Un proveedor con timeout de 15s satura PgBouncer y deja sin conexiones a toda la plataforma si la transacción DB abarca la llamada de red | **Crítico** | Servicio de Reversos / Bitácora |
| **RT-04** | **Cuello de botella por serialización en Hash Encadenado Global** de la bitácora | Calcular `prev_hash` global fila por fila obliga a bloquear el último registro (`FOR UPDATE`), destruyendo el throughput de inserción concurrente | **Alto** | Bitácora de Auditoría |
| **RT-05** | **Brecha de Idempotencia en Tablas Particionadas de PostgreSQL** | En PostgreSQL todo índice `UNIQUE` debe incluir la clave de partición (`fecha_registro`). Un reintento que cruce la medianoche (o cambio de mes) se inserta en otra partición y **duplica el reverso/cargo** | **Crítico** | Mantenimiento de Bitácora / Idempotencia |
| **RT-06** | **Agotamiento de puertos SNAT / Memoria por 50,000 conexiones SSE permanentes** desde las cajas hacia la Fachada POS | Mantener 50k streams HTTP abiertos simultáneamente satura las tablas de estado del firewall/WAF y los sockets de entrada | **Alto** | Seguridad POS / Fachada |
| **RT-07** | **Correlación rota en Gateway ISO 8583 (jPOS) multi-pod** para reversos `0420`/`0421` | Si un reintento de reverso sale por un pod distinto sin persistir `DE 90` (*Original Data Elements*: MTI + STAN + DE 7 + DE 32), el autorizador rechaza el reverso | **Alto** | Adaptadores / ISO 8583 |

---

## 2. Desarrollo Detallado de cada Reto y Mejora Propuesta

### RT-01: Cadena de Confianza mTLS (`RFC 8705`) a través del Borde WAF y API Gateway

* **Qué dice la propuesta actual:**  
  `POS -> WAF (F5 o Azure App Gateway) -> API Gateway (Kong o Connectivity Link) -> Keycloak`. Indica que Keycloak emite tokens ligados al certificado (`RFC 8705`, claim `cnf.x5t#S256`) y que el API Gateway los valida localmente con JWKS.
* **Por qué falla técnicamente:**  
  Para validar un token *certificate-bound* (`RFC 8705`), el API Gateway debe comparar el hash SHA-256 del certificado cliente presentado en el handshake TLS actual contra el claim `cnf.x5t#S256` dentro del JWT. Pero si el WAF hace inspección L7 (WAF OWASP CRS), **el WAF termina el TLS del POS** y abre una *nueva* conexión TLS hacia el API Gateway usando el certificado del propio WAF. El API Gateway ya no ve el certificado de la caja. Además, Azure Application Gateway tiene límites estrictos en el tamaño de cadenas de CAs intermedias para mTLS con decenas de miles de dispositivos.
* **Mejora arquitectónica (M-01):**
  1. **Terminación mTLS en F5/Envoy Borde con propagación criptográfica de `X-Forwarded-Client-Cert` (XFCC):** El WAF valida la cadena PKI de la caja (contra la CA interna de Tiendas), extrae el thumbprint SHA-256 del certificado hoja y lo inyecta en la cabecera `X-Forwarded-Client-Cert`.
  2. **mTLS estricto entre WAF y API Gateway con limpieza de cabeceras:** El WAF elimina cualquier cabecera `X-Forwarded-Client-Cert` que venga desde Internet antes de inyectar la suya, y se autentica contra Kong/Connectivity Link mediante un certificado de cliente exclusivo del WAF.
  3. **Plugin/Filtro RFC 8705 en API Gateway:** El API Gateway valida en memoria:
     $$\text{SHA256}(\text{XFCC}_{\text{leaf}}) == \text{JWT}.\text{cnf}.\text{x5t\#S256}$$

---

### RT-02: Contradicción y *Deadlock* entre Circuit Breaker, Kafka Pause, Topics de Retry y KEDA

* **Qué dice la propuesta actual:**  
  - Línea 195: *"Al agotar los inmediatos o con el circuito abierto, el reverso pasa a topics de espera (`reverso.retry.1m`, `.5m`, `.30m`)."*
  - Línea 198: *"Cuando el circuit breaker se abre, el consumidor DEBE pausar sus particiones en lugar de seguir enviando al proveedor; al cerrarse, reanuda y drena lo pendiente."*
  - Línea 112: *"KEDA escalando consumidores por lag de Kafka."*
* **Por qué falla técnicamente:**
  1. **Contradicción:** Si las particiones están pausadas cuando el circuito está `OPEN`, el consumidor no lee mensajes y por tanto no los mueve a `reverso.retry.1m`. Y si sí los lee y los mueve a `reverso.retry.1m` con el circuito abierto, **vacía el topic principal hacia los topics de retry a velocidad de disco**, quemando los 3 escalones de reintento mientras el proveedor sigue caído y mandando miles de transacciones sanas a la `DLQ` prematuramente.
  2. **Deadlock de recuperación:** En Resilience4j, un Circuit Breaker pasa de `OPEN` a `HALF_OPEN` por tiempo (`waitDurationInOpenState`), pero **para pasar de `HALF_OPEN` a `CLOSED` requiere ejecutar $N$ llamadas reales (`permittedNumberOfCallsInHalfOpenState`)**. Si el consumidor de Kafka sigue pausado en `HALF_OPEN`, jamás entran llamadas al Circuit Breaker y el sistema queda congelado indefinidamente.
  3. **Efecto perverso con KEDA:** Cuando el consumidor pausa sus particiones porque el proveedor cayó, el *consumer lag* en Kafka crece rápidamente. KEDA detecta el aumento de lag y **escala los pods del microservicio al máximo**, lo que dispara un rebalanceo de particiones en Kafka y levanta decenas de pods que inmediatamente ven el proveedor caído y también se pausan.
* **Mejora arquitectónica (M-02):**
  ```mermaid
  stateDiagram-v2
      direction LR
      [*] --> CLOSED: Inicio normal
      CLOSED --> OPEN: Falla >= umbral (50%)
      OPEN --> HALF_OPEN: waitDurationInOpenState (ej. 30s)
      HALF_OPEN --> CLOSED: N llamadas sonda exitosas
      HALF_OPEN --> OPEN: 1 llamada sonda falla

      state CLOSED {
          [*] --> Kafka_Resumed
          Kafka_Resumed: Consumidor ACTIVO (100% capacidad)
      }
      state OPEN {
          [*] --> Kafka_Paused
          Kafka_Paused: Consumidor PAUSADO (mensajes seguros en topic principal)
      }
      state HALF_OPEN {
          [*] --> Kafka_Probe
          Kafka_Probe: Reanuda consumidor para N mensajes sonda o ejecuta Health-Probe
      }
  ```
  1. **Regla clara de enrutamiento:**
     - Si el proveedor está `CLOSED` y una transacción individual falla por error transitorio tras los reintentos inmediatos $\rightarrow$ va a `reverso.retry.1m` / `.5m` / `.30m`.
     - Si la falla es sistémica del proveedor y el Circuit Breaker pasa a `OPEN` $\rightarrow$ **SE PAUSA el consumidor** para que la cola espere en Kafka sin quemar escalones de retry ni contaminar la DLQ.
  2. **Listener reactivo de estado del Circuit Breaker:** Suscribir el controlador de Kafka a `CircuitBreakerOnStateTransitionEvent` con `automaticTransitionFromOpenToHalfOpenEnabled: true`:
     - En transición a `OPEN`: `consumer.pause()`.
     - En transición a `HALF_OPEN`: `consumer.resume()` (permitiendo las llamadas sonda configuradas en `permittedNumberOfCallsInHalfOpenState`). Si las sondas tienen éxito, pasa a `CLOSED`; si fallan, vuelve a `OPEN` y re-pausa.
  3. **Escalado KEDA consciente de salud (Health-Aware Scaling):** Configurar el `ScaledObject` de KEDA combinando el trigger de Kafka con un trigger de Prometheus (`resilience4j_circuitbreaker_state{state="closed"} == 1`) o limitando el escalado cuando el circuito está abierto.

---

### RT-03: Desacoplamiento Transaccional (Evitar retener conexiones PostgreSQL durante I/O de Red)

* **Qué dice la propuesta actual:**  
  El flujo recibe de Kafka, verifica idempotencia en PostgreSQL, llama al proveedor con reintentos inmediatos (backoff exponencial) y escribe estado + bitácora + outbox.
* **Por qué falla técnicamente:**  
  Si un método anotado con `@Transactional` ejecuta la llamada HTTP/SOAP/ISO al proveedor externo (que con 3 reintentos inmediatos y timeouts de 3–5 segundos puede tardar 10–15 segundos), **la conexión física de PostgreSQL permanece bloqueada en estado `idle in transaction` durante 15 segundos**. Con solo 50 mensajes concurrentes, se agota el pool de conexiones y la base de datos colapsa.
* **Mejora arquitectónica (M-03): Patrón de Transacciones Cortas en 3 Fases (Short-Lived TXs)**
  1. **TX-1 (Recepción e Idempotencia, < 5 ms):** Verifica/inserta `(transaccion_original_id, reverso_id)` con estado `EN_PROCESO` y escribe el evento `RECIBIDO` en la bitácora. Hace commit y **devuelve la conexión al pool**.
  2. **Ejecución I/O (Sin conexión DB retenida):** Ejecuta el adaptador del proveedor sobre **Java 21 Virtual Threads** (`@RunOnVirtualThread`) protegido por Resilience4j.
  3. **TX-2 (Resolución, Bitácora y Outbox, < 5 ms):** Toma una conexión del pool, actualiza el estado final (`EXITOSO`, `REINTENTO_DIFERIDO`, `DLQ_INTERVENCION_MANUAL`, etc.), inserta la transición en `bitacora_auditoria` y escribe el evento en `outbox_eventos` en una sola transacción atómica.

---

### RT-04: Cadena de Hashes sin Contención Global (Hash Chain por Agregado + Merkle Root por Partición)

* **Qué dice la propuesta actual:**  
  Línea 204: *"Cada registro PUEDE incluir el hash del anterior para hacer evidente cualquier alteración."*
* **Por qué falla técnicamente:**  
  Si el registro $N$ requiere el hash del registro $N-1$ de **toda la tabla** (`bitacora_auditoria`), todas las transacciones de todos los pods deben hacer fila india sobre el último registro insertado. Esto convierte una tabla particionada de alto rendimiento en un embudo secuencial de ~200 TPS máximo.
* **Mejora arquitectónica (M-04):**
  1. **En caliente (Tiempo real, Cero contención entre transacciones distintas):** La cadena de hashes `prev_hash -> curr_hash` se encadena **por `correlation_id` / `folio`** (o por partición lógica de Kafka donde un solo hilo consumidor procesa secuencialmente su partición). Así, todas las transiciones de una misma transacción (`RECIBIDO -> INTENTO_1 -> REINTENTO_DIFERIDO -> EXITOSO`) forman una cadena criptográfica inmutable sin bloquear a otras cajas:
     $$\text{curr\_hash}_k = \text{SHA256}(\text{prev\_hash}_{k-1} \parallel \text{correlation\_id} \parallel \text{folio} \parallel \text{estado} \parallel \text{codigo\_respuesta} \parallel \text{timestamp})$$
  2. **En cierre de partición (Job de Mantenimiento):** Al cerrar una partición diaria/mensual, el job calcula el **Hash Acumulado / Merkle Root de toda la partición** ordenada determinísticamente por `(fecha_evento, id)` y lo sella en `control_particion_bitacora` y en el manifiesto WORM de Blob Storage.

---

### RT-05: Separación entre Tabla de Estado/Idempotencia y Bitácora Particionada

* **Qué dice la propuesta actual:**  
  Se habla de clave única `(transaccion_original + id_reverso)` en PostgreSQL y bitácora append-only particionada por fecha (`fillfactor 100`, solo `INSERT`).
* **Por qué falla técnicamente:**  
  PostgreSQL **no permite crear un índice `UNIQUE` global** sobre una tabla particionada a menos que la columna de partición (`fecha_evento`) forme parte de la clave única. Si la idempotencia se valida sobre una tabla particionada por fecha, un mensaje enviado a las `23:59:58` y reintentado a las `00:00:05` tendrá distinta fecha y PostgreSQL permitirá duplicarlo. Además, el estado actual de una transacción cambia (`EN_PROCESO -> EXITOSO`), mientras que la bitácora tiene `fillfactor 100` y el usuario solo tiene permiso `INSERT`.
* **Mejora arquitectónica (M-05): Modelo de Datos Dual**
  - **Tabla 1: `reverso_transaccion` (Estado Actual + Idempotencia Fuerte):**
    - Clave única estricta `UNIQUE (transaccion_original_id, reverso_id)` sin depender de la fecha del evento.
    - Contiene el estado actual, conteo de intentos, estrategia de reverso y datos de correlación de red (`DE 90`).
  - **Tabla 2: `bitacora_auditoria` (Append-Only Particionada por Fecha):**
    - `fillfactor = 100`, particionada por rango de `fecha_evento`, sin `UPDATE` ni `DELETE`, con cadena de hashes SHA-256 y sujeta al ciclo de vida `DETACH + DROP` validado por `control_particion_bitacora`.

---

### RT-06: Patrón de Comunicación Caja $\leftrightarrow$ Fachada POS para 50,000 Terminales

* **Qué dice la propuesta actual:**  
  Línea 88: *"Una fachada Quarkus (REST o SSE) publica y consume en su nombre."*
* **Por qué falla técnicamente:**  
  Mantener 50,000 conexiones SSE permanentes a través de internet/MPLS de tiendas, pasando por F5/AppGW + Kong + Pods de Quarkus, consume miles de puertos efímeros SNAT y genera reconexiones masivas (*thundering herd*) cada vez que hay un microcorte de red o despliegue rolling de la fachada.
* **Mejora arquitectónica (M-06):**
  - **Envío de Operaciones (Outbox Caja $\rightarrow$ Nube):** `POST /api/v1/pos/transacciones` (o `/reversos`) con `Idempotency-Key` en cabecera, recibiendo `202 Accepted` con `correlationId` en menos de 50 ms una vez persistido en Kafka (`acks=all`).
  - **Consulta de Estado / Reconciliación:** Para operaciones asíncronas (como reversos diferidos), la caja NO se queda colgada esperando: al recibir `202 Accepted` marca su outbox local como `ENVIADO_NUBE` y libera al cajero inmediatamente (cumpliendo **P1: Offline-first**). Si requiere confirmación final, consulta por lote (`POST /api/v1/pos/reconciliacion`) en su ciclo de sincronización en segundo plano con jitter.

---

### RT-07: Persistencia de Elementos Originales (`DE 90`) y Gestión de `STAN` en ISO 8583

* **Qué dice la propuesta actual:**  
  Menciona `jPOS` en un gateway compartido con `STAN` y `RRN`.
* **Por qué falla técnicamente:**  
  En ISO 8583, un reverso (`0420`) y su repetición (`0421`) exigen que el campo `DE 90` (*Original Data Elements*, 42 dígitos) sea idéntico en todos los reintentos (incluyendo aquellos que vienen 30 minutos después desde los topics diferidos `reverso.retry.30m` y llegan a otro pod).
* **Mejora arquitectónica (M-07):**
  - Incorporar en `TransaccionCanonica` el record inmutable `CorrelacionRedIso` (`mtiOriginal`, `stanOriginal`, `fechaTransmisionDe7`, `rrnDe37`, `adquirenteDe32`) para que los reintentos diferidos (`0421`) reconstruyan exactamente la trama esperada por el switch adquirente sin depender de memoria local del pod de `jPOS`.

---

## 3. Recalibración Recomendada de la Matriz (Op1 vs. Op3)

La recomendación de **Op3 (Híbrida: Kafka + Quarkus en ARO, PostgreSQL Flexible Server administrado en Azure)** es técnicamente la decisión correcta, pero se recomienda agregar dos salvaguardas obligatorias para que Op3 no pierda puntos en Latencia y PCI frente a Op1:

1. **Co-localización de Zonas (Availability Zone Pinning) y VNet Peering / Private Endpoint con Accelerated Networking:**  
   Cada salto `Quarkus (ARO) -> PostgreSQL Flexible Server` en Op3 añade ~0.8–1.5 ms si cruza zonas de disponibilidad. Con las **Transacciones Cortas en 3 Fases (M-03)** y **PgBouncer integrado en Flexible Server**, se reduce la presión de conexiones en un 85%.
2. **Validación de Extensiones en Azure Database for PostgreSQL Flexible Server:**  
   En Azure PostgreSQL Flexible Server, `pg_partman` y `pg_cron` **sí están soportados**, pero deben agregarse explícitamente al parámetro de servidor `azure.extensions = PG_CRON,PG_PARTMAN` y `shared_preload_libraries = pg_cron,pg_partman_bgw` mediante Terraform/Bicep antes del despliegue. Además, el servicio de aplicación debe contar con un **orquestador de mantenimiento determinista** que ejecute la verificación de `control_particion_bitacora` (archivo frío + réplica + checksum) antes de invocar `ALTER TABLE ... DETACH PARTITION`.
