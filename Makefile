.PHONY: dev dev-deps recreate-platform rebuild-platform down build-platform build-cli test-platform test-cli test docker-build lint clean help

## dev-deps: Start only reusable local dependencies for host-run development
dev-deps:
	docker compose up -d postgres database-init

## dev: Start the active platform stack without forcing an image rebuild
dev:
	docker compose up -d postgres database-init platform-server

## recreate-platform: Recreate platform-server from the existing image after environment-only changes
recreate-platform:
	docker compose up -d --no-build --force-recreate platform-server

## rebuild-platform: Rebuild and activate only the platform-server image
rebuild-platform:
	docker compose build platform-server
	docker compose up -d --no-deps platform-server

## down: Stop all containers
down:
	docker compose down

## build-platform: Compile platform-server (skip tests)
build-platform:
	./mvnw -q -DskipTests package

## build-cli: Compile CLI
build-cli:
	cd cli && go build -o bin/spaceagent .

## test-platform: Run platform tests
test-platform:
	./mvnw -q test

## test-cli: Run CLI tests
test-cli:
	cd cli && go test ./...

## test: Run all tests
test: test-platform test-cli

## docker-build: Build all Docker images
docker-build:
	docker compose build

## lint: Run architecture validation and Go vet
lint:
	scripts/check-architecture.sh
	cd cli && go vet ./...

## clean: Clean build artifacts
clean:
	./mvnw clean -q
	rm -rf cli/bin

## help: Show available targets
help:
	@echo "Available targets:"
	@grep -E '^## ' $(MAKEFILE_LIST) | sed 's/## /  /' | sort
