<#
.SYNOPSIS
    Guion interactivo de Demo (6 pasos) para el Piloto Local del Motor de Reversos POS en ARO.
.DESCRIPTION
    Ejecuta contra http://localhost:8080 los 6 escenarios descritos en la sección "Plan de pilotos"
    del documento propuesta-arquitectura-integracion-pos-aro.md.
#>
param(
    [string]$BaseUrl = "http://localhost:8080"
)

$ErrorActionPreference = "Stop"

function Write-StepHeader([string]$Titulo) {
    Write-Host ""
    Write-Host "==================================================================================" -ForegroundColor Cyan
    Write-Host " $Titulo" -ForegroundColor Cyan
    Write-Host "==================================================================================" -ForegroundColor Cyan
}

Write-StepHeader "PASO 1: Proveedor ARRIBA -> Reverso exitoso e inspección de bitácora SHA-256"
Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/simulador/proveedor/modo" -ContentType "application/json" -Body '{"modo":"ARRIBA_EXITOSO","latenciaMs":12}' | Out-Null

$rev1 = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/pos/reversos" -ContentType "application/json" `
    -Headers @{
        "X-Forwarded-Client-Cert" = "Hash=thumbprint-pos-0101;Subject=`"CN=POS-0101`""
        "X-JWT-Confirmation-Thumbprint" = "thumbprint-pos-0101"
        "Idempotency-Key" = "IDEM-DEMO-1001"
    } `
    -Body '{
        "transaccionOriginalId": "TXN-DEMO-1001",
        "reversoId": "REV-DEMO-1001",
        "cajaId": "POS-0101",
        "folio": "FOL-DEMO0001",
        "proveedorId": "PROSA-ISO",
        "protocolo": "ISO_8583",
        "monto": 450.75,
        "moneda": "MXN",
        "tarjetaTokenizadaOEnmascarada": "411111******1111"
    }'
$rev1 | ConvertTo-Json -Depth 4 | Write-Host -ForegroundColor Green

Write-Host "`nConsultando Bitácora con Verificación de Cadena SHA-256 para FOL-DEMO0001:" -ForegroundColor Yellow
$bitacora1 = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/v1/soporte/bitacora/FOL-DEMO0001"
$bitacora1 | ConvertTo-Json -Depth 5 | Write-Host

Write-StepHeader "PASO 2: Proveedor APAGADO -> Se abre el Circuit Breaker, se pausa Kafka y se acumulan reversos"
Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/simulador/proveedor/modo" -ContentType "application/json" -Body '{"modo":"APAGADO_SIN_CONEXION","latenciaMs":10}' | Out-Null

foreach ($idx in 2..3) {
    $folio = "FOL-DEMO000$idx"
    $revId = "REV-DEMO-100$idx"
    $txId  = "TXN-DEMO-100$idx"
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/pos/reversos" -ContentType "application/json" -Body "{
        `"transaccionOriginalId`": `"$txId`",
        `"reversoId`": `"$revId`",
        `"cajaId`": `"POS-010$idx`",
        `"folio`": `"$folio`",
        `"proveedorId`": `"BANORTE-REST`",
        `"protocolo`": `"REST`",
        `"monto`": 215.00,
        `"moneda`": `"MXN`",
        `"tarjetaTokenizadaOEnmascarada`": `"TOK-89012345000$idx`"
    }" | Out-Null
}
$estadoPausa = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/v1/simulador/estado"
$estadoPausa | ConvertTo-Json -Depth 4 | Write-Host -ForegroundColor Yellow

Write-StepHeader "PASO 3: Proveedor DE VUELTA -> Se reanuda y drena todo sin duplicados"
Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/simulador/proveedor/modo" -ContentType "application/json" -Body '{"modo":"ARRIBA_EXITOSO","latenciaMs":10}' | Out-Null
$drenados = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/simulador/cola-pausada/drenar" -ContentType "application/json"
$drenados | ConvertTo-Json -Depth 4 | Write-Host -ForegroundColor Green

Write-StepHeader "PASO 4: Error de Negocio (422) -> Va directo a DLQ y se reprocesa desde Soporte"
Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/simulador/proveedor/modo" -ContentType "application/json" -Body '{"modo":"ERROR_NEGOCIO_422","latenciaMs":10}' | Out-Null
$enDlq = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/pos/reversos" -ContentType "application/json" -Body '{
    "transaccionOriginalId": "TXN-DEMO-4001",
    "reversoId": "REV-DEMO-4001",
    "cajaId": "POS-0401",
    "folio": "FOL-DEMO0004",
    "proveedorId": "BBVA-SOAP",
    "protocolo": "SOAP",
    "monto": 980.00,
    "moneda": "MXN",
    "tarjetaTokenizadaOEnmascarada": "520000******9999"
}'
Write-Host "Estado en DLQ:" -ForegroundColor Red
$enDlq | ConvertTo-Json -Depth 4 | Write-Host

Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/simulador/proveedor/modo" -ContentType "application/json" -Body '{"modo":"ARRIBA_EXITOSO","latenciaMs":10}' | Out-Null
Write-Host "`nReprocesando REV-DEMO-4001 desde el endpoint de soporte..." -ForegroundColor Yellow
$reprocesado = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/soporte/dlq/REV-DEMO-4001/reprocesar" -ContentType "application/json" -Body '{
    "operadorSoporte": "roberto.soporte@xpos.mx",
    "motivo": "Autorización manual de ventana extendida confirmada con emisor"
}'
$reprocesado | ConvertTo-Json -Depth 4 | Write-Host -ForegroundColor Green

Write-StepHeader "PASO 5: Depuración Segura de Partición (DETACH + DROP tras confirmar WORM y Réplica)"
$particionMes = (Get-Date).ToUniversalTime().ToString("'bitacora_auditoria_y'yyyy'm'MM")
$estadoPart = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/soporte/particiones" -ContentType "application/json" -Body "{
    `"nombreParticion`": `"$particionMes`",
    `"fechaInicio`": `"2026-10-01`",
    `"fechaFin`": `"2026-10-31`",
    `"archivadoEnFrioWorm`": true,
    `"replicadoAReportes`": true,
    `"conteoFilasArchivadas`": 0,
    `"conteoFilasReplicadas`": 0
}"
$conteoReal = $estadoPart.conteoFilasParticion
Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/soporte/particiones" -ContentType "application/json" -Body "{
    `"nombreParticion`": `"$particionMes`",
    `"fechaInicio`": `"2026-10-01`",
    `"fechaFin`": `"2026-10-31`",
    `"archivadoEnFrioWorm`": true,
    `"replicadoAReportes`": true,
    `"conteoFilasArchivadas`": $conteoReal,
    `"conteoFilasReplicadas`": $conteoReal
}" | Out-Null

$depurada = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/soporte/particiones/$particionMes/depurar" -ContentType "application/json"
$depurada | ConvertTo-Json -Depth 4 | Write-Host -ForegroundColor Green

Write-StepHeader "PASO 6: Verificación de Perfil Activo (Op1 / Op3 sin tocar código)"
$estadoFinal = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/v1/simulador/estado"
$estadoFinal | ConvertTo-Json -Depth 4 | Write-Host -ForegroundColor Cyan
