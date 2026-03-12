#!/usr/bin/env bash

### ============================================================
###   Colors and logging
### ============================================================

GREEN="\033[32m"
YELLOW="\033[33m"
RED="\033[31m"
CYAN="\033[36m"
RESET="\033[0m"

info()    { echo -e "${CYAN}[INFO]${RESET} $*"; }
success() { echo -e "${GREEN}[OK]${RESET} $*"; }
warn()    { echo -e "${YELLOW}[WARN]${RESET} $*"; }
error()   { echo -e "${RED}[ERROR]${RESET} $*"; }

### ============================================================
###   Configuration
### ============================================================

# Setup the device route:
info "Setting route /command with DeviceTranslation plugin enabled..."
curl -i http://127.0.0.1:9180/apisix/admin/routes/1 -H 'X-API-KEY: admin' -X PUT -d '
{
    "uri": "/command",
    "plugins": {
        "ext-plugin-pre-req": {
            "conf" : [
                {"name": "AuthFilter", "value": "{\"enable\":\"feature\"}"},
                {"name": "DeviceTranslation", "value": "{\"enable\":\"feature\"}"}
            ]
        }
    }
}'

success "Route setup"
# Check
info "Test it with: curl http://127.0.0.1:9080/command"


# Setup the device route:
info "Setting route /onboarding/translation with DeviceConfig plugin enabled..."
curl -i http://127.0.0.1:9180/apisix/admin/routes/2 -H 'X-API-KEY: admin' -X PUT -d '
{
    "uri": "/onboarding/translation",
    "plugins": {
        "jwt-auth": {
            "_meta": {
                "priority": 13000
            }
        },
        "serverless-pre-function": {
            "_meta": { "priority": 12500 },
            "phase": "rewrite",
            "functions": [
                "return function(conf, ctx)\n  local consumer = ctx.consumer\n  if not consumer then ngx.status = 401 ngx.say(\"401\") ngx.exit(401) end\n  local allowed = {[\"gateway-admin\"]=true,[\"device-admin\"]=true}\n  if not allowed[consumer.username] then ngx.status = 403 ngx.say(\"{\\\"message\\\":\\\"Forbidden\\\"}\") ngx.exit(403) end\nend"
            ]
        },
        "ext-plugin-pre-req": {
            "_meta": {
                "priority": 12000
            },
            "conf" : [
                {"name": "TranslationOnboarding", "value": "{\"enable\":\"feature\"}"}
            ]
        }
    }
}'

success "Route setup"
# Check
info "Test it with: curl http://127.0.0.1:9080/onboarding/translation"


# Gateway admin
curl -i http://127.0.0.1:9180/apisix/admin/consumers -H 'X-API-KEY: admin' -X PUT -d '
{
  "username": "gateway-admin"
}'

curl -i http://127.0.0.1:9180/apisix/admin/consumers/gateway-admin/credentials -H 'X-API-KEY: admin' -X PUT -d '
{
  "id": "cred-gateway-admin",
  "plugins": {
    "jwt-auth": {
      "key": "gateway-admin",
      "secret": "strong-secret-for-jwt-token-gateway-admin",
      "algorithm": "HS256"
    }
  }
}'

# Device admin
curl -i http://127.0.0.1:9180/apisix/admin/consumers \
  -H 'X-API-KEY: admin' -X PUT -d '
{
  "username": "device-admin"
}'

curl -i http://127.0.0.1:9180/apisix/admin/consumers/device-admin/credentials -H 'X-API-KEY: admin' -X PUT -d '
{
  "id": "cred-device-admin",
  "plugins": {
    "jwt-auth": {
      "key": "device-admin",
      "secret": "strong-secret-for-jwt-token-device-admin",
      "algorithm": "HS256"
    }
  }
}'

# Backend admin
curl -i http://127.0.0.1:9180/apisix/admin/consumers -H 'X-API-KEY: admin' -X PUT -d '
{
  "username": "backend-admin"
}'

curl -i http://127.0.0.1:9180/apisix/admin/consumers/backend-admin/credentials -H 'X-API-KEY: admin' -X PUT -d '
{
  "id": "cred-backend-admin",
  "plugins": {
    "jwt-auth": {
      "key": "backend-admin",
      "secret": "strong-secret-for-jwt-token-backend-admin",
      "algorithm": "HS256"
    }
  }
}'


# Setup /onboarding/backend
info "Setting route /onboarding/backend with Onboarding filter enabled..."
curl -i http://127.0.0.1:9180/apisix/admin/routes/4 -H 'X-API-KEY: admin' -X PUT -d '
{
    "uri": "/onboarding/backend",
    "methods": ["POST", "PATCH", "DELETE"],
    "plugins": {
        "jwt-auth": {
            "_meta": {
                "priority": 13000
            }
        },
        "serverless-pre-function": {
            "_meta": { "priority": 12500 },
            "phase": "rewrite",
            "functions": [
                "return function(conf, ctx)\n  local consumer = ctx.consumer\n  if not consumer then ngx.status = 401 ngx.say(\"401\") ngx.exit(401) end\n  local allowed = {[\"gateway-admin\"]=true,[\"backend-admin\"]=true}\n  if not allowed[consumer.username] then ngx.status = 403 ngx.say(\"{\\\"message\\\":\\\"Forbidden\\\"}\") ngx.exit(403) end\nend"
            ]
        },
        "ext-plugin-pre-req": {
            "_meta": {
                "priority": 12000
            },
            "conf" : [
                {"name": "Onboarding", "value": "{\"enable\":\"feature\"}"}
            ]
        }
    }
}'
success "Route setup"
info "Test it with: curl http://127.0.0.1:9080/onboarding/backend"


# Setup /onboarding/backendAuthZ
info "Setting route /onboarding/backendAuthZ with Onboarding filter enabled..."
curl -i http://127.0.0.1:9180/apisix/admin/routes/5 -H 'X-API-KEY: admin' -X PUT -d '
{
    "uri": "/onboarding/backendAuthZ",
    "methods": ["POST", "PATCH", "DELETE"],
    "plugins": {
        "jwt-auth": {
            "_meta": {
                "priority": 13000
            }
        },
        "serverless-pre-function": {
            "_meta": { "priority": 12500 },
            "phase": "rewrite",
            "functions": [
                "return function(conf, ctx)\n  local consumer = ctx.consumer\n  if not consumer then ngx.status = 401 ngx.say(\"401\") ngx.exit(401) end\n  local allowed = {[\"gateway-admin\"]=true,[\"backend-admin\"]=true,[\"device-admin\"]=true}\n  if not allowed[consumer.username] then ngx.status = 403 ngx.say(\"{\\\"message\\\":\\\"Forbidden\\\"}\") ngx.exit(403) end\nend"
            ]
        },
        "ext-plugin-pre-req": {
            "_meta": {
                "priority": 12000
            },
            "conf" : [
                {"name": "Onboarding", "value": "{\"enable\":\"feature\"}"}
            ]
        }
    }
}'
success "Route setup"
info "Test it with: curl http://127.0.0.1:9080/onboarding/backendAuthZ"


# Setup /onboarding/device
info "Setting route /onboarding/device with Onboarding filter enabled..."
curl -i http://127.0.0.1:9180/apisix/admin/routes/6 -H 'X-API-KEY: admin' -X PUT -d '
{
    "uri": "/onboarding/device",
    "methods": ["POST", "PATCH", "DELETE"],
    "plugins": {
        "jwt-auth": {
            "_meta": {
                "priority": 13000
            }
        },
        "serverless-pre-function": {
            "_meta": { "priority": 12500 },
            "phase": "rewrite",
            "functions": [
                "return function(conf, ctx)\n  local consumer = ctx.consumer\n  if not consumer then ngx.status = 401 ngx.say(\"401\") ngx.exit(401) end\n  local allowed = {[\"gateway-admin\"]=true,[\"device-admin\"]=true}\n  if not allowed[consumer.username] then ngx.status = 403 ngx.say(\"{\\\"message\\\":\\\"Forbidden\\\"}\") ngx.exit(403) end\nend"
            ]
        },
        "ext-plugin-pre-req": {
            "_meta": {
                "priority": 12000
            },
            "conf" : [
                {"name": "Onboarding", "value": "{\"enable\":\"feature\"}"}
            ]
        }
    }
}'
success "Route setup"
info "Test it with: curl http://127.0.0.1:9080/onboarding/device"


# Setup /onboarding/deviceAuthZ
info "Setting route /onboarding/deviceAuthZ with Onboarding filter enabled..."
curl -i http://127.0.0.1:9180/apisix/admin/routes/7 -H 'X-API-KEY: admin' -X PUT -d '
{
    "uri": "/onboarding/deviceAuthZ",
    "methods": ["POST", "PATCH", "DELETE"],
    "plugins": {
        "jwt-auth": {
            "_meta": {
                "priority": 13000
            }
        },
        "serverless-pre-function": {
            "_meta": { "priority": 12500 },
            "phase": "rewrite",
            "functions": [
                "return function(conf, ctx)\n  local consumer = ctx.consumer\n  if not consumer then ngx.status = 401 ngx.say(\"401\") ngx.exit(401) end\n  local allowed = {[\"gateway-admin\"]=true,[\"device-admin\"]=true,[\"backend-admin\"]=true}\n  if not allowed[consumer.username] then ngx.status = 403 ngx.say(\"{\\\"message\\\":\\\"Forbidden\\\"}\") ngx.exit(403) end\nend"
            ]
        },
        "ext-plugin-pre-req": {
            "_meta": {
                "priority": 12000
            },
            "conf" : [
                {"name": "Onboarding", "value": "{\"enable\":\"feature\"}"}
            ]
        }
    }
}'
success "Route setup"
info "Test it with: curl http://127.0.0.1:9080/onboarding/deviceAuthZ"


# Settup /commands
info "Setting route /commands with Onboarding filter enabled..."
curl -i http://127.0.0.1:9180/apisix/admin/routes/8 -H 'X-API-KEY: admin' -X PUT -d '
{
    "uri": "/commands",
    "methods": ["GET"],
    "plugins": {
        "ext-plugin-pre-req": {
            "conf" : [
                {"name": "Onboarding", "value": "{\"enable\":\"feature\"}"}
            ]
        }
    }
}'
success "Route setup"
info "Test it with: curl http://127.0.0.1:9080/commands"


# Setup /backend/info
info "Setting route /backendForward with AuthFilter enabled..."
curl -i http://127.0.0.1:9180/apisix/admin/routes/9 -H 'X-API-KEY: admin' -X PUT -d '
{
    "uri": "/backendForward",
    "methods": ["POST"],
    "plugins": {
        "ext-plugin-pre-req": {
            "conf" : [
                {"name": "AuthFilter", "value": "{\"enable\":\"feature\"}"},
                {"name": "BackendForwarder", "value": "{\"enable\":\"feature\"}"}
            ]
        }
    }
}'
success "Route setup"
info "Test it with: curl http://127.0.0.1:9080/backend/info"

