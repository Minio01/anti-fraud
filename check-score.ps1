$ErrorActionPreference = 'Stop'

$grpcurl = Get-Command grpcurl.exe -CommandType Application -ErrorAction SilentlyContinue
$grpcurlPath = if ($grpcurl) {
    $grpcurl.Source
} else {
    Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Links\grpcurl.exe'
}
if (-not (Test-Path -LiteralPath $grpcurlPath -PathType Leaf)) {
    throw 'grpcurl.exe was not found. Install it with: winget install --id fullstorydev.grpcurl --exact'
}

$requests = (Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $PSScriptRoot 'Score.http')) -split '(?m)^###.*\r?\n'
$utf8 = [System.Text.UTF8Encoding]::new($false)
$arguments = '-connect-timeout 3 -max-time 10 -plaintext -import-path proto/src/main/proto -proto risk/v1/scoring.proto -d @ localhost:9090 risk.v1.RiskScoringService/Score'

foreach ($request in $requests) {
    if (-not $request.Trim()) { continue }
    $body = ($request.Trim() -split '\r?\n\r?\n', 2)[1]
    $transaction = ($body | ConvertFrom-Json).transaction
    $inputFile = [System.IO.Path]::GetTempFileName()
    try {
        [System.IO.File]::WriteAllText($inputFile, $body, $utf8)
        Write-Host "Score: $($transaction.amountMinor) $($transaction.currency) minor units"
        $process = Start-Process -FilePath $grpcurlPath -ArgumentList $arguments -WorkingDirectory $PSScriptRoot -RedirectStandardInput $inputFile -NoNewWindow -Wait -PassThru
        if ($process.ExitCode -ne 0) { exit $process.ExitCode }
    } finally {
        Remove-Item -LiteralPath $inputFile
    }
}
