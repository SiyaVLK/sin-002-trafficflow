# Build, push and deploy TrafficFlow to AWS.
#
#   .\aws\scripts\deploy.ps1 -MqPassword "a-long-enough-password"
#
# Run it from the repository root. Requires the AWS CLI v2, credentials with
# permission to create the resources in aws/infra, and Docker.

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateLength(12, 250)]
    [string] $MqPassword,

    [string] $ProjectName = "trafficflow",
    [string] $Region      = "eu-west-1",
    [string] $MqUsername  = "trafficflow"
)

$ErrorActionPreference = "Stop"

$services = @(
    @{ Name = "ingestion-service";    Port = 7020 },
    @{ Name = "intersection-service"; Port = 7021 },
    @{ Name = "congestion-service";   Port = 7022 },
    @{ Name = "routing-service";      Port = 7023 },
    @{ Name = "watchdog-service";     Port = 7024 }
)

# A tag that says which commit is running. "latest" alone makes a rollback a
# guess.
$tag = (git rev-parse --short HEAD).Trim()
$accountId = (aws sts get-caller-identity --query Account --output text).Trim()
$registry = "$accountId.dkr.ecr.$Region.amazonaws.com"

Write-Host "Account $accountId, region $Region, tag $tag" -ForegroundColor Cyan

# --------------------------------------------------- 1. registries must exist
Write-Host "`n[1/4] Deploying ECR repositories" -ForegroundColor Cyan
aws cloudformation deploy `
    --template-file aws/infra/01-ecr.yaml `
    --stack-name "$ProjectName-ecr" `
    --parameter-overrides ProjectName=$ProjectName `
    --region $Region

# ------------------------------------------------------------- 2. build images
Write-Host "`n[2/4] Building images" -ForegroundColor Cyan
foreach ($service in $services) {
    Write-Host "  $($service.Name)"
    docker build `
        --file aws/docker/Dockerfile `
        --build-arg "SERVICE=$($service.Name)" `
        --build-arg "PORT=$($service.Port)" `
        --tag "$registry/$ProjectName/$($service.Name):$tag" `
        --tag "$registry/$ProjectName/$($service.Name):latest" `
        .
    if ($LASTEXITCODE -ne 0) { throw "Build failed for $($service.Name)" }
}

# -------------------------------------------------------------- 3. push images
Write-Host "`n[3/4] Pushing to ECR" -ForegroundColor Cyan
aws ecr get-login-password --region $Region |
    docker login --username AWS --password-stdin $registry

foreach ($service in $services) {
    docker push "$registry/$ProjectName/$($service.Name):$tag"
    docker push "$registry/$ProjectName/$($service.Name):latest"
}

# -------------------------------------------------------- 4. deploy everything
Write-Host "`n[4/4] Deploying the application stack" -ForegroundColor Cyan
Write-Host "      The Amazon MQ broker takes around 15 minutes to create." -ForegroundColor DarkGray

aws cloudformation deploy `
    --template-file aws/infra/02-app.yaml `
    --stack-name "$ProjectName-app" `
    --capabilities CAPABILITY_IAM `
    --parameter-overrides `
        ProjectName=$ProjectName `
        ImageTag=$tag `
        MqUsername=$MqUsername `
        MqPassword=$MqPassword `
    --region $Region

Write-Host "`nDone." -ForegroundColor Green
aws cloudformation describe-stacks `
    --stack-name "$ProjectName-app" `
    --region $Region `
    --query "Stacks[0].Outputs[?OutputKey!='BrokerOpenWireEndpoint'].[OutputKey,OutputValue]" `
    --output table

Write-Host "Tear it down with:" -ForegroundColor Yellow
Write-Host "  aws cloudformation delete-stack --stack-name $ProjectName-app --region $Region"
