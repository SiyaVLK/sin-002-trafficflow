#!/usr/bin/env bash
# Build, push and deploy TrafficFlow to AWS. The bash equivalent of deploy.ps1.
#
#   MQ_PASSWORD='a-long-enough-password' ./aws/scripts/deploy.sh
#
# Run it from the repository root.

set -euo pipefail

PROJECT_NAME="${PROJECT_NAME:-trafficflow}"
REGION="${AWS_REGION:-eu-west-1}"
MQ_USERNAME="${MQ_USERNAME:-trafficflow}"
: "${MQ_PASSWORD:?Set MQ_PASSWORD to at least 12 characters}"

SERVICES=(
  "ingestion-service:7020"
  "intersection-service:7021"
  "congestion-service:7022"
  "routing-service:7023"
  "watchdog-service:7024"
)

TAG="$(git rev-parse --short HEAD)"
ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
REGISTRY="${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com"

echo "Account ${ACCOUNT_ID}, region ${REGION}, tag ${TAG}"

echo
echo "[1/4] Deploying ECR repositories"
aws cloudformation deploy \
  --template-file aws/infra/01-ecr.yaml \
  --stack-name "${PROJECT_NAME}-ecr" \
  --parameter-overrides "ProjectName=${PROJECT_NAME}" \
  --region "${REGION}"

echo
echo "[2/4] Building images"
for entry in "${SERVICES[@]}"; do
  service="${entry%%:*}"
  port="${entry##*:}"
  echo "  ${service}"
  docker build \
    --file aws/docker/Dockerfile \
    --build-arg "SERVICE=${service}" \
    --build-arg "PORT=${port}" \
    --tag "${REGISTRY}/${PROJECT_NAME}/${service}:${TAG}" \
    --tag "${REGISTRY}/${PROJECT_NAME}/${service}:latest" \
    .
done

echo
echo "[3/4] Pushing to ECR"
aws ecr get-login-password --region "${REGION}" \
  | docker login --username AWS --password-stdin "${REGISTRY}"

for entry in "${SERVICES[@]}"; do
  service="${entry%%:*}"
  docker push "${REGISTRY}/${PROJECT_NAME}/${service}:${TAG}"
  docker push "${REGISTRY}/${PROJECT_NAME}/${service}:latest"
done

echo
echo "[4/4] Deploying the application stack"
echo "      The Amazon MQ broker takes around 15 minutes to create."
aws cloudformation deploy \
  --template-file aws/infra/02-app.yaml \
  --stack-name "${PROJECT_NAME}-app" \
  --capabilities CAPABILITY_IAM \
  --parameter-overrides \
      "ProjectName=${PROJECT_NAME}" \
      "ImageTag=${TAG}" \
      "MqUsername=${MQ_USERNAME}" \
      "MqPassword=${MQ_PASSWORD}" \
  --region "${REGION}"

echo
aws cloudformation describe-stacks \
  --stack-name "${PROJECT_NAME}-app" \
  --region "${REGION}" \
  --query "Stacks[0].Outputs[?OutputKey!='BrokerOpenWireEndpoint'].[OutputKey,OutputValue]" \
  --output table

echo "Tear it down with:"
echo "  aws cloudformation delete-stack --stack-name ${PROJECT_NAME}-app --region ${REGION}"
