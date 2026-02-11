# Remote Stop Script
# Stops the application on the remote server using docker-compose down

$RemoteHost = "your_server_ip"
$RemoteUser = "your_user"
$RemotePassword = "your_password"

# Default configuration
$RemoteProjectPath = "/home/user/apps/youtubelizer"

# Load configuration
if (Test-Path "deploy-config.ps1") {
    . ".\deploy-config.ps1"
    # Map config variables if they exist (handling different naming conventions)
    if ($REMOTE_HOST) { $RemoteHost = $REMOTE_HOST }
    if ($REMOTE_USER) { $RemoteUser = $REMOTE_USER }
    if ($REMOTE_PW) { $RemotePassword = $REMOTE_PW }
}
else {
    Write-Warning "deploy-config.ps1 not found! Using default configuration."
}

Write-Host "=== Stopping Remote Application ===" -ForegroundColor Cyan
Write-Host "Target: ${RemoteUser}@${RemoteHost}:${RemoteProjectPath}" -ForegroundColor Cyan
Write-Host ""

if (-not (Get-Command plink -ErrorAction SilentlyContinue)) {
    Write-Host "ERROR: plink not found! Install PuTTY tools." -ForegroundColor Red
    exit 1
}

# Command to ensure directory exists (just in case) and run docker-compose down
$RemoteCmd = "cd $RemoteProjectPath && docker-compose -f docker-compose.prod.yml down"

Write-Host "Running: $RemoteCmd" -ForegroundColor Yellow

plink -pw $RemotePassword "${RemoteUser}@${RemoteHost}" $RemoteCmd

if ($LASTEXITCODE -ne 0) {
    Write-Host "Failed to stop application!" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "Application stopped successfully!" -ForegroundColor Green
