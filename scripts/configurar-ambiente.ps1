$ErrorActionPreference = 'Stop'
$arquivo = Join-Path (Split-Path -Parent $PSScriptRoot) '.env'
$chaves = @('ORACLE_PASSWORD', 'APP_USER_PASSWORD', 'SPRING_DATASOURCE_PASSWORD')

if (-not (Test-Path -LiteralPath $arquivo)) {
    $admin = 'Db_' + [guid]::NewGuid().ToString('N')
    $app = 'App_' + [guid]::NewGuid().ToString('N')
    $valores = @($admin, $app, $app)
    $linhas = for ($i = 0; $i -lt $chaves.Length; $i++) {
        '{0}={1}' -f $chaves[$i], $valores[$i]
    }
    $linhas | Set-Content -LiteralPath $arquivo -Encoding ascii
    Write-Output 'Arquivo .env criado.'
    return
}

$linhas = @(Get-Content -LiteralPath $arquivo)
$anterior = @($linhas | Where-Object { $_ -cmatch '^MIGRAERP_DB_PASSWORD=' }) | Select-Object -Last 1
if ($anterior) {
    $valorExistente = $anterior.Substring($anterior.IndexOf('=') + 1)
    foreach ($chave in $chaves[1..2]) {
        if (-not ($linhas -cmatch ('^' + $chave + '='))) {
            $linhas += '{0}={1}' -f $chave, $valorExistente
        }
    }
    $linhas = @($linhas | Where-Object { $_ -cnotmatch '^MIGRAERP_DB_PASSWORD=' })
    [System.IO.File]::WriteAllLines($arquivo, [string[]]$linhas, [System.Text.UTF8Encoding]::new($false))
}

$linhas = Get-Content -LiteralPath $arquivo
foreach ($chave in $chaves) {
    if (-not ($linhas -cmatch ('^' + $chave + '=.+$'))) {
        throw ('Defina {0} em .env.' -f $chave)
    }
}
Write-Output 'Arquivo .env existente verificado.'
