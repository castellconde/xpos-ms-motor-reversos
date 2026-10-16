-- =========================================================================================
-- Migración Flyway V1: Esquema Transaccional de Reversos y Bitácora Particionada PCI-DSS
-- Compatible con PostgreSQL 16 (CloudNativePG en ARO - Op1 / Azure Flexible Server - Op3)
-- =========================================================================================

-- 1. Tabla de Estado Actual e Idempotencia Fuerte (Separada de la bitácora particionada - RT-05)
-- Garantiza que la restricción UNIQUE (transaccion_original_id, reverso_id) sea global
-- incluso cuando un reintento cruza la medianoche o cambio de mes.
CREATE TABLE IF NOT EXISTS reverso_transaccion (
    id                          BIGSERIAL PRIMARY KEY,
    transaccion_original_id     VARCHAR(64) NOT NULL,
    reverso_id                  VARCHAR(64) NOT NULL,
    correlation_id              VARCHAR(96) NOT NULL,
    idempotency_key             VARCHAR(128) NOT NULL,
    caja_id                     VARCHAR(32) NOT NULL,
    folio                       VARCHAR(64) NOT NULL,
    proveedor_id                VARCHAR(32) NOT NULL,
    protocolo                   VARCHAR(16) NOT NULL,
    monto                       NUMERIC(12, 2) NOT NULL,
    moneda                      CHAR(3) NOT NULL,
    tarjeta_enmascarada         VARCHAR(64) NOT NULL,
    estado_actual               VARCHAR(40) NOT NULL,
    escalon_actual              VARCHAR(32) NOT NULL,
    intentos_totales            INT NOT NULL DEFAULT 0,
    ultimo_codigo_respuesta     VARCHAR(64),
    ultimo_detalle              VARCHAR(512),
    iso_mti_original            VARCHAR(4) NOT NULL,
    iso_stan_de11               VARCHAR(6) NOT NULL,
    iso_fecha_de7               VARCHAR(10) NOT NULL,
    iso_rrn_de37                VARCHAR(12) NOT NULL,
    iso_adquirente_de32         VARCHAR(11) NOT NULL,
    fecha_creacion              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    fecha_actualizacion         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_reverso_idempotencia UNIQUE (transaccion_original_id, reverso_id),
    CONSTRAINT ck_no_pan_claro_reverso CHECK (tarjeta_enmascarada !~ '^\d{13,19}$')
);

CREATE INDEX IF NOT EXISTS idx_reverso_folio ON reverso_transaccion (folio);
CREATE INDEX IF NOT EXISTS idx_reverso_estado_dlq ON reverso_transaccion (estado_actual)
    WHERE estado_actual = 'DLQ_INTERVENCION_MANUAL';

-- 2. Tabla de Bitácora de Auditoría Append-Only Particionada por Rango de Fecha
-- Cumple PCI-DSS 10.2 / 10.5: Sin PAN en claro, fillfactor=100 (solo INSERT), cadena SHA-256 (RT-04)
CREATE TABLE IF NOT EXISTS bitacora_auditoria (
    id                          BIGSERIAL,
    fecha_evento                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    nombre_particion            VARCHAR(64) NOT NULL,
    correlation_id              VARCHAR(96) NOT NULL,
    transaccion_original_id     VARCHAR(64) NOT NULL,
    reverso_id                  VARCHAR(64) NOT NULL,
    caja_id                     VARCHAR(32) NOT NULL,
    folio                       VARCHAR(64) NOT NULL,
    tipo_transaccion            VARCHAR(32) NOT NULL,
    proveedor_id                VARCHAR(32) NOT NULL,
    protocolo                   VARCHAR(16) NOT NULL,
    estado_transicion           VARCHAR(40) NOT NULL,
    numero_intento              INT NOT NULL,
    codigo_respuesta            VARCHAR(64) NOT NULL,
    latencia_ms                 BIGINT NOT NULL DEFAULT 0,
    tarjeta_enmascarada         VARCHAR(64) NOT NULL,
    detalle_enmascarado         VARCHAR(512) NOT NULL,
    prev_hash_sha256            CHAR(64) NOT NULL,
    curr_hash_sha256            CHAR(64) NOT NULL,
    PRIMARY KEY (id, fecha_evento),
    CONSTRAINT ck_no_pan_claro_bitacora CHECK (tarjeta_enmascarada !~ '^\d{13,19}$')
) PARTITION BY RANGE (fecha_evento);

-- Particiones mensuales pre-creadas (automatizables con pg_partman + pg_cron en Flexible Server)
CREATE TABLE IF NOT EXISTS bitacora_auditoria_y2026m03
    PARTITION OF bitacora_auditoria
    FOR VALUES FROM ('2026-03-01 00:00:00+00') TO ('2026-04-01 00:00:00+00')
    WITH (fillfactor = 100);

CREATE TABLE IF NOT EXISTS bitacora_auditoria_y2026m09
    PARTITION OF bitacora_auditoria
    FOR VALUES FROM ('2026-09-01 00:00:00+00') TO ('2026-10-01 00:00:00+00')
    WITH (fillfactor = 100);

CREATE TABLE IF NOT EXISTS bitacora_auditoria_y2026m10
    PARTITION OF bitacora_auditoria
    FOR VALUES FROM ('2026-10-01 00:00:00+00') TO ('2026-11-01 00:00:00+00')
    WITH (fillfactor = 100);

CREATE TABLE IF NOT EXISTS bitacora_auditoria_y2026m11
    PARTITION OF bitacora_auditoria
    FOR VALUES FROM ('2026-11-01 00:00:00+00') TO ('2026-12-01 00:00:00+00')
    WITH (fillfactor = 100);

CREATE TABLE IF NOT EXISTS bitacora_auditoria_default
    PARTITION OF bitacora_auditoria DEFAULT
    WITH (fillfactor = 100);

-- Índices locales por partición: BRIN sobre fecha_evento y B-tree en correlation_id y folio
CREATE INDEX IF NOT EXISTS idx_bitacora_brin_fecha ON bitacora_auditoria USING BRIN (fecha_evento);
CREATE INDEX IF NOT EXISTS idx_bitacora_correlation_id ON bitacora_auditoria (correlation_id);
CREATE INDEX IF NOT EXISTS idx_bitacora_folio ON bitacora_auditoria (folio);

-- 3. Tabla de Control de Particiones para Depuración Segura Condicionada (DETACH + DROP)
CREATE TABLE IF NOT EXISTS control_particion_bitacora (
    nombre_particion            VARCHAR(64) PRIMARY KEY,
    fecha_inicio_rango          DATE NOT NULL,
    fecha_fin_rango             DATE NOT NULL,
    conteo_filas_particion      BIGINT NOT NULL DEFAULT 0,
    conteo_filas_archivadas     BIGINT NOT NULL DEFAULT 0,
    conteo_filas_replicadas     BIGINT NOT NULL DEFAULT 0,
    hash_cierre_sha256          CHAR(64) NOT NULL,
    ruta_blob_parquet_worm      VARCHAR(256),
    archivado_en_frio_worm      BOOLEAN NOT NULL DEFAULT FALSE,
    replicado_a_reportes        BOOLEAN NOT NULL DEFAULT FALSE,
    depurada                    BOOLEAN NOT NULL DEFAULT FALSE,
    fecha_ultima_verificacion   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 4. Tabla Transactional Outbox (para publicar en Kafka sin Dual-Write)
CREATE TABLE IF NOT EXISTS outbox_eventos (
    id                          UUID PRIMARY KEY,
    correlation_id              VARCHAR(96) NOT NULL,
    folio                       VARCHAR(64) NOT NULL,
    topic_destino               VARCHAR(64) NOT NULL,
    estado_evento               VARCHAR(40) NOT NULL,
    motivo                      VARCHAR(512),
    publicado_kafka             BOOLEAN NOT NULL DEFAULT FALSE,
    fecha_creacion              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 5. Vista de Reportes Comerciales (Fuera del alcance PCI: sin payload ni datos sensibles)
-- Leída por DataStage / Azure Data Factory desde la réplica de lectura con marca de agua monótona.
CREATE OR REPLACE VIEW v_reportes_comerciales_reversos AS
SELECT
    id AS marca_agua_id,
    fecha_evento,
    correlation_id,
    caja_id,
    folio,
    tipo_transaccion,
    proveedor_id,
    protocolo,
    estado_transicion,
    numero_intento,
    codigo_respuesta,
    latencia_ms
FROM bitacora_auditoria;
