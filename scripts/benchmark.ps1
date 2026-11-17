param(
    [string]$Api = 'http://localhost:8080',
    [ValidateRange(2, 20)][int]$Repeticoes = 6,
    [string]$Saida = 'benchmark.log'
)

$ErrorActionPreference = 'Stop'
$projeto = Split-Path -Parent $PSScriptRoot
$saidaCompleta = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Saida)
Push-Location $projeto
try {
    $geracao = docker compose exec -T oracle bash /migraerp-db/sql.sh '@/migraerp-db/perf-dados.sql'
    if ($LASTEXITCODE -ne 0) { throw ($geracao -join [Environment]::NewLine) }
    $lote = [regex]::Match(($geracao -join ' '), 'LOTE_PERF=([0-9]+)').Groups[1].Value
    if (-not $lote) { throw 'O Oracle nao retornou o identificador do lote.' }

    "MigraERP | $(Get-Date -Format o) | lote $lote | 100000 registros" |
        Tee-Object -FilePath $saidaCompleta
    $url = "$($Api.TrimEnd('/'))/api/lotes/$lote/validar"
    $resultados = @{JAVA = @(); PLSQL = @()}
    $esperado = '99350/650'

    for ($i = 0; $i -le $Repeticoes; $i++) {
        $motores = if ($i % 2 -eq 0) { @('JAVA', 'PLSQL') } else { @('PLSQL', 'JAVA') }
        foreach ($motor in $motores) {
            $relogio = [System.Diagnostics.Stopwatch]::StartNew()
            $resposta = Invoke-RestMethod -Method Post -Uri "${url}?motor=$motor" -TimeoutSec 180
            $relogio.Stop()
            $contagem = "$($resposta.lote.validos)/$($resposta.lote.invalidos)"
            if ($contagem -ne $esperado) { throw "$motor retornou $contagem. Esperado $esperado." }
            if ($i -gt 0) { $resultados[$motor] += [double]$resposta.motorMs }
            "rodada=$i motor=$motor validos/invalidos=$contagem motor_ms=$($resposta.motorMs) http_ms=$($relogio.Elapsed.TotalMilliseconds)" |
                Tee-Object -FilePath $saidaCompleta -Append
        }
    }
    foreach ($motor in @('JAVA', 'PLSQL')) {
        $ordenado = @($resultados[$motor] | Sort-Object)
        $centro = [int][Math]::Floor($ordenado.Count / 2)
        $mediana = if ($ordenado.Count % 2 -eq 0) {
            ($ordenado[$centro - 1] + $ordenado[$centro]) / 2
        } else {
            $ordenado[$centro]
        }
        "$motor mediana_motor_ms=$mediana (rodada 0 excluida)" |
            Tee-Object -FilePath $saidaCompleta -Append
    }

    docker compose exec -T oracle bash /migraerp-db/sql.sh '@/migraerp-db/perf-plano.sql' $lote |
        Tee-Object -FilePath $saidaCompleta -Append
    if ($LASTEXITCODE -ne 0) { throw 'O experimento de plano falhou. Veja o arquivo de saida.' }
} finally {
    Pop-Location
}
