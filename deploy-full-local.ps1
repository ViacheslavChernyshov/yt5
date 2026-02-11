# Complete local deployment script
# Build JAR locally -> Build Docker image locally -> Deploy to remote server

$RemoteHost = "192.168.4.42"
$RemoteUser = "user"
$RemotePassword = "1qaz2wsx"
# Load configuration
if (Test-Path "deploy-config.ps1") {
    . ".\deploy-config.ps1"
}
else {
    Write-Host "Error: deploy-config.ps1 not found! Please create it from deploy-config.ps1.example." -ForegroundColor Red
    exit 1
}

Write-Host "=== Complete Local Build + Remote Deploy ===" -ForegroundColor Green
Write-Host ""

# Step 1: Build JAR locally
Write-Host "Step 1: Building JAR locally..." -ForegroundColor Cyan
Push-Location $LocalProjectPath

Write-Host "Running: mvnw clean package -DskipTests" -ForegroundColor Yellow
.\mvnw clean package -DskipTests

if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed!" -ForegroundColor Red
    Pop-Location
    exit 1
}

$JarPath = "$LocalProjectPath\target\youtubelizer-0.1.0.jar"
if (-not (Test-Path $JarPath)) {
    Write-Host "JAR not found!" -ForegroundColor Red
    Pop-Location
    exit 1
}

Write-Host "JAR built successfully!" -ForegroundColor Green
Write-Host ""

# Step 2: Build Docker image locally
Write-Host "Step 2: Building Docker image locally..." -ForegroundColor Cyan
Write-Host "Running: docker build -f Dockerfile.prod -t ${ImageName}:${ImageTag} ." -ForegroundColor Yellow

docker build -f Dockerfile.prod -t "${ImageName}:${ImageTag}" .

if ($LASTEXITCODE -ne 0) {
    Write-Host "Docker build failed!" -ForegroundColor Red
    Pop-Location
    exit 1
}

Write-Host "Docker image built successfully!" -ForegroundColor Green
Write-Host ""

# Step 3: Save Docker image to tar file
Write-Host "Step 3: Saving Docker image to file..." -ForegroundColor Cyan
Write-Host "Running: docker save -o $ImageFile ${ImageName}:${ImageTag}" -ForegroundColor Yellow

if (Test-Path $ImageFile) {
    Remove-Item $ImageFile -Force
}

docker save -o $ImageFile "${ImageName}:${ImageTag}"

if ($LASTEXITCODE -ne 0) {
    Write-Host "Docker save failed!" -ForegroundColor Red
    Pop-Location
    exit 1
}

$FileSizeMB = (Get-Item $ImageFile).Length / 1MB
Write-Host "Image saved: $ImageFile (${FileSizeMB:F2} MB)" -ForegroundColor Green
Write-Host ""

# Step 4: Upload tar file to remote server
Write-Host "Step 4: Uploading Docker image to remote server..." -ForegroundColor Cyan
Write-Host "This may take a moment (file is ${FileSizeMB:F2} MB)..." -ForegroundColor Yellow

if (-not (Get-Command pscp -ErrorAction SilentlyContinue)) {
    Write-Host "ERROR: pscp not found! Install PuTTY tools." -ForegroundColor Red
    Pop-Location
    exit 1
}

pscp -pw $RemotePassword -batch $ImageFile "${RemoteUser}@${RemoteHost}:${RemoteProjectPath}/"

if ($LASTEXITCODE -ne 0) {
    Write-Host "Upload failed!" -ForegroundColor Red
    Pop-Location
    exit 1
}

Write-Host "Docker image uploaded successfully!" -ForegroundColor Green
Write-Host ""

# Step 4.1: Upload docker-compose.prod.yml to remote server
Write-Host "Step 4.1: Uploading docker-compose.prod.yml to remote server..." -ForegroundColor Cyan

pscp -pw $RemotePassword -batch docker-compose.prod.yml "${RemoteUser}@${RemoteHost}:${RemoteProjectPath}/"

if ($LASTEXITCODE -ne 0) {
    Write-Host "Upload of docker-compose.prod.yml failed!" -ForegroundColor Red
    Pop-Location
    exit 1
}

Write-Host "Configuration uploaded successfully!" -ForegroundColor Green
Write-Host ""

# Step 5: Load image and start container on remote server
Write-Host "Step 5: Loading image and starting container on remote server..." -ForegroundColor Cyan

if (-not (Get-Command plink -ErrorAction SilentlyContinue)) {
    Write-Host "ERROR: plink not found! Install PuTTY tools." -ForegroundColor Red
    Pop-Location
    exit 1
}

$RemoteCmd = "cd $RemoteProjectPath && docker load -i $ImageFile && docker-compose -f docker-compose.prod.yml up -d"

plink -pw $RemotePassword "${RemoteUser}@${RemoteHost}" $RemoteCmd

if ($LASTEXITCODE -ne 0) {
    Write-Host "Docker load/start failed!" -ForegroundColor Red
    Pop-Location
    exit 1
}

Write-Host "Container started!" -ForegroundColor Green
Write-Host ""

# Step 6: Check container status
Write-Host "Step 6: Checking container status..." -ForegroundColor Cyan

$StatusCmd = "docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'"
plink -pw $RemotePassword "${RemoteUser}@${RemoteHost}" $StatusCmd

Write-Host ""
Write-Host "=== Deployment Complete ===" -ForegroundColor Green
$appUrl = "http://" + $RemoteHost + ":8080"
Write-Host "Access your app at: $appUrl" -ForegroundColor Yellow
Write-Host ""

# Cleanup local tar file
Write-Host "Cleaning up local tar file..." -ForegroundColor Cyan
Remove-Item $ImageFile -Force
Write-Host "Done!" -ForegroundColor Green

Pop-Location
