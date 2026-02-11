# Load configuration
if (Test-Path "deploy-config.ps1") {
    . ".\deploy-config.ps1"
}
else {
    Write-Host "Error: deploy-config.ps1 not found! Please create it from deploy-config.ps1.example." -ForegroundColor Red
    exit 1
}

Write-Host "--- Truncating all tables in youtubelizer database ---" -ForegroundColor Cyan

# Define the tables to truncate
$TABLES = "download_tasks, videos, channels, requests"

# Execute truncate command on remote server via plink
# Using -i for interactive mode to avoid some pipe issues, and CASCADE to handle foreign keys
plink -batch -pw $REMOTE_PW "$REMOTE_USER@$REMOTE_HOST" `
    "docker exec -i youtubelizer-postgres psql -U youtubelizer_user -d youtubelizer -c 'TRUNCATE TABLE $TABLES CASCADE;'"

if ($LASTEXITCODE -eq 0) {
    Write-Host "Success! All tables have been cleared." -ForegroundColor Green
}
else {
    Write-Host "Failed to truncate tables. Check the output above for errors." -ForegroundColor Red
}
