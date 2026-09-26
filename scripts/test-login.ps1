$b = @{username='admin'; password='Admin@123!'; tenantId='acme-corp'} | ConvertTo-Json
try {
    $r = Invoke-RestMethod -Uri 'http://localhost:8081/auth/login' -Method Post -ContentType 'application/json' -Body $b
    Write-Host 'RESPONSE:'
    $r | ConvertTo-Json -Depth 5
} catch {
    Write-Host 'ERROR:' $_.Exception.Message
    if ($_.Exception.Response) {
        $s = $_.Exception.Response.GetResponseStream()
        $sr = New-Object System.IO.StreamReader($s)
        Write-Host $sr.ReadToEnd()
    }
}
