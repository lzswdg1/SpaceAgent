#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ERRORS=0

report_error() {
  echo "ERROR: $*" >&2
  ERRORS=$((ERRORS + 1))
}

cd "$ROOT_DIR"

for required_command in awk find rg sed sort uniq; do
  if ! command -v "$required_command" >/dev/null 2>&1; then
    echo "ERROR: Required command '$required_command' was not found in PATH." >&2
    exit 1
  fi
done

SHARED_MAIN_JAVA="shared/shared-kernel/src/main/java"

for forbidden_type in \
  ChatTurnCompletedEvent \
  MemoryClearRequestedEvent \
  SkillClearRequestedEvent \
  UserServiceClient \
  SkillServiceClient \
  UserInfo \
  UserProfileInfo \
  SkillInfo
do
  matches="$(find "$SHARED_MAIN_JAVA" -type f -name "${forbidden_type}.java" -print)"
  if [[ -n "$matches" ]]; then
    report_error "shared-kernel defines business-shaped type ${forbidden_type}:"
    printf '%s\n' "$matches" >&2
  fi
done

unexpected_shared_clients="$(find "$SHARED_MAIN_JAVA/com/spaceagent/shared/client" \
  -type f -name '*.java' -print 2>/dev/null || true)"
if [[ -n "$unexpected_shared_clients" ]]; then
  report_error "shared-kernel contains retired service clients:"
  printf '%s\n' "$unexpected_shared_clients" >&2
fi

unexpected_shared_events="$(find "$SHARED_MAIN_JAVA/com/spaceagent/shared/event" \
  -type f -name '*.java' -print 2>/dev/null || true)"
if [[ -n "$unexpected_shared_events" ]]; then
  report_error "shared-kernel contains retired event-bus code:"
  printf '%s\n' "$unexpected_shared_events" >&2
fi

shared_business_persistence="$(find "$SHARED_MAIN_JAVA/com/spaceagent/shared" \
  -type f \( -path '*/persistence/*' -o -name '*Repository.java' -o -name '*Dao.java' -o -name '*Mapper.java' \) \
  -print 2>/dev/null || true)"
if [[ -n "$shared_business_persistence" ]]; then
  report_error "shared-kernel contains business persistence contracts:"
  printf '%s\n' "$shared_business_persistence" >&2
fi

if rg -n 'classpath:db/platform-runtime' services \
  --glob 'application*.yml' --glob 'application*.yaml' --glob '!**/target/**' >/dev/null 2>&1; then
  report_error "legacy services must not load the active platform runtime Flyway source"
fi

if rg_output="$(
  rg -n \
    '^import (static )?com\.spaceagent\.(identity|agent|chat|gateway|knowledge|project|conversation|memory|context|runtime|inference|tooling|automation|integration|governance|observability)\.' \
    "$SHARED_MAIN_JAVA" 2>/dev/null
)"; then
  rg_status=0
else
  rg_status=$?
fi

forbidden_business_imports=""
case "$rg_status" in
  0)
    forbidden_business_imports="$rg_output"
    ;;
  1)
    # ripgrep returns 1 when there are no matches; that is not an error.
    ;;
  *)
    report_error "rg failed while checking shared-kernel business imports (exit status $rg_status):"
    ;;
esac

if [[ -n "$forbidden_business_imports" ]]; then
  report_error "shared-kernel imports business modules:"
  printf '%s\n' "$forbidden_business_imports" >&2
fi

PLATFORM_MAIN_JAVA="apps/platform-server/src/main/java"
ADMIN_MAIN_JAVA="apps/platform-admin-server/src/main/java"
PLATFORM_MODULES="identity agent project conversation memory knowledge context runtime inference tooling artifact automation integration governance observability shared"
PLATFORM_BUSINESS_MODULES="identity agent project conversation memory knowledge context runtime inference tooling artifact automation integration governance observability"

backend_plan=".agent/BACKEND-PLAN.md"
if [[ ! -f "$backend_plan" ]]; then
  report_error "Authoritative backend execution plan is missing: $backend_plan"
else
  backend_next_count="$(rg -c '^- Status: `NEXT`$' "$backend_plan" || true)"
  backend_active_count="$(rg -c '^- Status: `IN_PROGRESS`$' "$backend_plan" || true)"
  backend_blocked_count="$(rg -c '^- Status: `BLOCKED`$' "$backend_plan" || true)"
  backend_queued_count="$(rg -c '^- Status: `QUEUED`$' "$backend_plan" || true)"
  backend_milestone_count="$(rg -c '^### M[0-9]+-PR[0-9]+ ' "$backend_plan" || true)"
  backend_status_count="$(rg -c '^- Status: `(COMPLETE|IN_PROGRESS|NEXT|QUEUED|BLOCKED|CANCELLED)`$' "$backend_plan" || true)"
  backend_plan_state="$(sed -n 's/^> Plan-State: //p' "$backend_plan")"
  backend_active_pointer="$(sed -n 's/^> Active-Milestone: //p' "$backend_plan")"
  backend_next_pointer="$(sed -n 's/^> Next-Milestone: //p' "$backend_plan")"
  backend_next_status_id="$(awk '/^### M[0-9]+-PR[0-9]+ / { id=$2 } /^- Status: `NEXT`$/ { print id }' "$backend_plan")"
  backend_active_status_id="$(awk '/^### M[0-9]+-PR[0-9]+ / { id=$2 } /^- Status: `IN_PROGRESS`$/ { print id }' "$backend_plan")"
  backend_blocked_status_id="$(awk '/^### M[0-9]+-PR[0-9]+ / { id=$2 } /^- Status: `BLOCKED`$/ { print id }' "$backend_plan")"
  backend_active_unit_pointer="$(sed -n 's/^> Active-Work-Unit: //p' "$backend_plan")"
  backend_next_unit_pointer="$(sed -n 's/^> Next-Work-Unit: //p' "$backend_plan")"
  backend_unit_count="$(rg -c '^\| M[0-9]+-PR[0-9]+-U[0-9]+[A-Z]? \| (COMPLETE|IMPLEMENTED_PENDING_TEST|IN_PROGRESS|NEXT|QUEUED|BLOCKED|CANCELLED) \|' "$backend_plan" || true)"
  backend_pending_unit_count="$(rg -c '^\| M[0-9]+-PR[0-9]+-U[0-9]+[A-Z]? \| IMPLEMENTED_PENDING_TEST \|' "$backend_plan" || true)"
  backend_pending_parent_ids="$(awk -F'|' '$3 ~ /^[[:space:]]*IMPLEMENTED_PENDING_TEST[[:space:]]*$/ { gsub(/^[[:space:]]+|[[:space:]]+$/, "", $2); sub(/-U[0-9]+[A-Z]?$/, "", $2); print $2 }' "$backend_plan" | sort -u)"
  backend_unit_next_count="$(rg -c '^\| M[0-9]+-PR[0-9]+-U[0-9]+[A-Z]? \| NEXT \|' "$backend_plan" || true)"
  backend_unit_active_count="$(rg -c '^\| M[0-9]+-PR[0-9]+-U[0-9]+[A-Z]? \| IN_PROGRESS \|' "$backend_plan" || true)"
  backend_unit_blocked_count="$(rg -c '^\| M[0-9]+-PR[0-9]+-U[0-9]+[A-Z]? \| BLOCKED \|' "$backend_plan" || true)"
  backend_unit_next_id="$(awk -F'|' '$3 ~ /^[[:space:]]*NEXT[[:space:]]*$/ { gsub(/^[[:space:]]+|[[:space:]]+$/, "", $2); print $2 }' "$backend_plan")"
  backend_unit_active_id="$(awk -F'|' '$3 ~ /^[[:space:]]*IN_PROGRESS[[:space:]]*$/ { gsub(/^[[:space:]]+|[[:space:]]+$/, "", $2); print $2 }' "$backend_plan")"
  backend_unit_blocked_id="$(awk -F'|' '$3 ~ /^[[:space:]]*BLOCKED[[:space:]]*$/ { gsub(/^[[:space:]]+|[[:space:]]+$/, "", $2); print $2 }' "$backend_plan")"
  backend_duplicate_unit_ids="$(awk -F'|' '$2 ~ /^[[:space:]]*M[0-9]+-PR[0-9]+-U[0-9]+[A-Z]?[[:space:]]*$/ { gsub(/^[[:space:]]+|[[:space:]]+$/, "", $2); print $2 }' "$backend_plan" | sort | uniq -d)"
  if [[ "${backend_milestone_count:-0}" -eq 0 \
      || "${backend_status_count:-0}" -ne "${backend_milestone_count:-0}" ]]; then
    report_error "Every backend milestone must contain exactly one valid Status field"
  fi
  for backend_required_field in Goal Current-Evidence Owner-Boundary Implementation Acceptance \
      Verification Not-In-Scope Final-Effect Done Next; do
    backend_required_count="$(rg -c "^- ${backend_required_field}:" "$backend_plan" || true)"
    if [[ "${backend_required_count:-0}" -ne "${backend_milestone_count:-0}" ]]; then
      report_error "Every backend milestone must contain one ${backend_required_field} field"
    fi
  done
  if [[ "${backend_unit_count:-0}" -lt "${backend_milestone_count:-0}" ]]; then
    report_error "Backend plan must contain bounded Work Units for every milestone"
  fi
  if [[ -n "$backend_duplicate_unit_ids" ]]; then
    report_error "Backend plan contains duplicate Work Unit IDs: $backend_duplicate_unit_ids"
  fi
  if [[ "${backend_pending_unit_count:-0}" -gt 0 ]]; then
    backend_batch_file=".agent/VALIDATION-BATCH.md"
    if [[ ! -f "$backend_batch_file" ]]; then
      report_error "Pending backend Units require a durable validation batch record"
    else
      backend_batch_target="$(sed -n 's/^> Target-Unit-Count: //p' "$backend_batch_file")"
      backend_batch_state="$(sed -n 's/^> Batch-State: //p' "$backend_batch_file")"
      backend_batch_milestone="$(sed -n 's/^> Milestone: //p' "$backend_batch_file")"
      if [[ ! "$backend_batch_target" =~ ^([1-9]|10)$ ]]; then
        report_error "Backend validation batch size must be 1-10 according to the declared recovery boundary"
      elif [[ "$backend_pending_unit_count" -ge "$backend_batch_target" ]]; then
        report_error "Backend batch is overdue: its boundary Unit must stay active during validation"
      fi
      if [[ "$backend_batch_state" != "OPEN" && "$backend_batch_state" != "TESTING" \
          && "$backend_batch_state" != "FAILED" ]]; then
        report_error "Pending backend Units cannot belong to an idle or passed batch"
      fi
      if [[ "$backend_pending_parent_ids" != "$backend_active_pointer" \
          || "$backend_batch_milestone" != "$backend_active_pointer" \
          || ( "${backend_active_count:-0}" -ne 1 && "${backend_blocked_count:-0}" -ne 1 ) ]]; then
        report_error "Pending backend Units must remain within the active or blocked milestone"
      fi
    fi
  fi
  if [[ "${backend_unit_active_count:-0}" -gt 1 || "${backend_unit_next_count:-0}" -gt 1 \
      || "${backend_unit_blocked_count:-0}" -gt 1 ]]; then
    report_error "Backend plan may contain at most one active, next or blocked Work Unit"
  fi
  if [[ "${backend_blocked_count:-0}" -gt 1 ]]; then
    report_error "Backend plan may contain at most one BLOCKED milestone"
  elif [[ "${backend_blocked_count:-0}" -eq 1 \
      && ( "${backend_active_count:-0}" -ne 0 || "${backend_next_count:-0}" -ne 0 ) ]]; then
    report_error "Blocked backend plan must contain no IN_PROGRESS or NEXT milestone"
  elif [[ "${backend_blocked_count:-0}" -eq 0 && "${backend_active_count:-0}" -eq 0 \
      && ( "${backend_queued_count:-0}" -gt 0 || "${backend_next_count:-0}" -gt 0 ) \
      && "${backend_next_count:-0}" -ne 1 ]]; then
    report_error "Idle backend plan must contain exactly one NEXT milestone"
  elif [[ "${backend_blocked_count:-0}" -eq 0 \
      && "${backend_active_count:-0}" -eq 1 && "${backend_next_count:-0}" -ne 0 ]]; then
    report_error "Active backend plan must contain one IN_PROGRESS milestone and no NEXT milestone"
  elif [[ "${backend_active_count:-0}" -gt 1 ]]; then
    report_error "Backend plan may contain at most one IN_PROGRESS milestone"
  fi
  if [[ "${backend_active_count:-0}" -eq 0 && "${backend_blocked_count:-0}" -eq 0 \
      && ( "${backend_queued_count:-0}" -gt 0 || "${backend_next_count:-0}" -gt 0 ) ]]; then
    if [[ "$backend_plan_state" != "READY_FOR_NEXT" || "$backend_active_pointer" != "NONE" \
        || "$backend_next_pointer" == "NONE" \
        || "$backend_next_pointer" != "$backend_next_status_id" \
        || ! "$backend_next_pointer" =~ ^M[0-9]+-PR[0-9]+$ ]] \
        || ! rg -q "^### ${backend_next_pointer} " "$backend_plan"; then
      report_error "Idle backend plan metadata does not point to its valid NEXT milestone"
    fi
    if [[ "${backend_unit_next_count:-0}" -ne 1 \
        || "${backend_unit_active_count:-0}" -ne 0 \
        || "${backend_unit_blocked_count:-0}" -ne 0 \
        || "$backend_active_unit_pointer" != "NONE" \
        || "$backend_next_unit_pointer" != "$backend_unit_next_id" \
        || "${backend_unit_next_id%-U*}" != "$backend_next_pointer" ]]; then
      report_error "Idle backend plan does not point to the first NEXT Work Unit of its NEXT milestone"
    fi
  elif [[ "${backend_active_count:-0}" -eq 1 ]]; then
    if [[ "$backend_active_pointer" != "$backend_active_status_id" \
        || "$backend_next_pointer" != "NONE" ]]; then
      report_error "Active backend plan metadata does not point to its IN_PROGRESS milestone"
    fi
    if [[ "${backend_unit_active_count:-0}" -eq 1 ]]; then
      if [[ "$backend_plan_state" != "ACTIVE" \
          || "$backend_active_unit_pointer" != "$backend_unit_active_id" \
          || "$backend_next_unit_pointer" != "NONE" \
          || "${backend_unit_active_id%-U*}" != "$backend_active_pointer" ]]; then
        report_error "Active backend plan does not point to its IN_PROGRESS Work Unit"
      fi
    elif [[ "${backend_unit_active_count:-0}" -eq 0 \
        && "${backend_unit_next_count:-0}" -eq 1 ]]; then
      if [[ "$backend_plan_state" != "READY_FOR_NEXT_UNIT" \
          || "$backend_active_unit_pointer" != "NONE" \
          || "$backend_next_unit_pointer" != "$backend_unit_next_id" \
          || "${backend_unit_next_id%-U*}" != "$backend_active_pointer" ]]; then
        report_error "Backend plan between units does not point to the next Unit of its active milestone"
      fi
    else
      report_error "IN_PROGRESS milestone must contain one active or one next Work Unit"
    fi
  elif [[ "${backend_blocked_count:-0}" -eq 1 ]]; then
    if [[ "$backend_plan_state" != "BLOCKED" \
        || "$backend_active_pointer" != "$backend_blocked_status_id" \
        || "$backend_next_pointer" != "NONE" ]]; then
      report_error "Blocked backend plan metadata does not point to its BLOCKED milestone"
    fi
    if [[ "${backend_unit_blocked_count:-0}" -ne 1 \
        || "$backend_active_unit_pointer" != "$backend_unit_blocked_id" \
        || "$backend_next_unit_pointer" != "NONE" \
        || "${backend_unit_blocked_id%-U*}" != "$backend_active_pointer" ]]; then
      report_error "Blocked backend plan does not point to its BLOCKED Work Unit"
    fi
  elif [[ "${backend_queued_count:-0}" -eq 0 && "${backend_next_count:-0}" -eq 0 ]]; then
    if [[ "$backend_plan_state" != "COMPLETE" || "$backend_active_pointer" != "NONE" \
        || "$backend_next_pointer" != "NONE" || "$backend_active_unit_pointer" != "NONE" \
        || "$backend_next_unit_pointer" != "NONE" \
        || "${backend_unit_active_count:-0}" -ne 0 || "${backend_unit_next_count:-0}" -ne 0 \
        || "${backend_unit_blocked_count:-0}" -ne 0 \
        || "${backend_pending_unit_count:-0}" -ne 0 ]]; then
      report_error "Completed backend plan metadata or Work Unit state is inconsistent"
    fi
  fi
  for backend_plan_ref in AGENTS.md .agent/CURRENT.md \
      docs/architecture/PRODUCT-ROADMAP.md; do
    if ! rg -q '\.agent/BACKEND-PLAN\.md' "$backend_plan_ref"; then
      report_error "Backend plan is not wired into $backend_plan_ref"
    fi
  done
fi

for module in $PLATFORM_MODULES; do
  for boundary in api domain infrastructure; do
    package_info="$PLATFORM_MAIN_JAVA/com/spaceagent/platform/$module/$boundary/package-info.java"
    if [[ ! -f "$package_info" ]]; then
      report_error "platform-server module $module is missing the $boundary package boundary"
    fi
  done
done

for module in $PLATFORM_BUSINESS_MODULES; do
  package_info="$PLATFORM_MAIN_JAVA/com/spaceagent/platform/$module/application/package-info.java"
  if [[ ! -f "$package_info" ]]; then
    report_error "platform-server business module $module is missing the application package boundary"
  fi
done

if [[ -d "$ADMIN_MAIN_JAVA" ]]; then
  admin_platform_imports="$(
    rg -n '^import (static )?com\.spaceagent\.platform\.' "$ADMIN_MAIN_JAVA" \
      --glob '*.java' 2>/dev/null || true
  )"
  if [[ -n "$admin_platform_imports" ]]; then
    report_error "platform-admin-server imports the tenant platform runtime:"
    printf '%s\n' "$admin_platform_imports" >&2
  fi

  admin_tenant_authority_leaks="$(
    rg -n '\b(tenant_id|organization_id|tenant_role|spaceagent_platform|platform_users)\b' \
      "$ADMIN_MAIN_JAVA" apps/platform-admin-server/src/main/resources \
      --glob '*.java' --glob '*.yml' --glob '*.yaml' --glob '*.sql' 2>/dev/null || true
  )"
  if [[ -n "$admin_tenant_authority_leaks" ]]; then
    report_error "platform-admin-server contains tenant or platform-database authority:"
    printf '%s\n' "$admin_tenant_authority_leaks" >&2
  fi

  admin_secret_authority_leaks="$(
    rg -ni '\b(provider.*(api.?key|secret)|mcp.*(credential|secret)|api_key_ciphertext)\b' \
      "$ADMIN_MAIN_JAVA" apps/platform-admin-server/src/main/resources \
      --glob '*.java' --glob '*.yml' --glob '*.yaml' --glob '*.sql' 2>/dev/null || true
  )"
  if [[ -n "$admin_secret_authority_leaks" ]]; then
    report_error "platform-admin-server contains platform Provider/MCP secret authority:"
    printf '%s\n' "$admin_secret_authority_leaks" >&2
  fi

  admin_domain_framework_imports="$(
    rg -n '^import (static )?(org\.springframework|org\.mybatis|jakarta\.persistence)\.' \
      "$ADMIN_MAIN_JAVA/com/spaceagent/admin"/*/domain/*.java 2>/dev/null || true
  )"
  if [[ -n "$admin_domain_framework_imports" ]]; then
    report_error "platform-admin-server domain packages import framework types:"
    printf '%s\n' "$admin_domain_framework_imports" >&2
  fi

  admin_http_repository_imports="$(
    rg -n '^import (static )?com\.spaceagent\.admin\..*(Repository|\.infrastructure\.persistence)' \
      "$ADMIN_MAIN_JAVA" --glob '*Controller.java' 2>/dev/null || true
  )"
  if [[ -n "$admin_http_repository_imports" ]]; then
    report_error "platform-admin-server HTTP controllers access repositories/persistence:"
    printf '%s\n' "$admin_http_repository_imports" >&2
  fi
fi

ADMIN_WEB_SRC="apps/admin-web/src"
if [[ -d "$ADMIN_WEB_SRC" ]]; then
  admin_web_internal_bypass="$(
    rg -n '/internal/system-admin|/internal/' "$ADMIN_WEB_SRC" --glob '*.{ts,tsx}' 2>/dev/null || true
  )"
  if [[ -n "$admin_web_internal_bypass" ]]; then
    report_error "Admin Web calls private platform internal APIs instead of /admin/v1:"
    printf '%s\n' "$admin_web_internal_bypass" >&2
  fi

  admin_web_credential_storage="$(
    rg -ni '(localStorage|sessionStorage).*(access.?token|refresh.?token|csrf|password|credential)|(accessToken|refreshToken|csrfToken|password).*(localStorage|sessionStorage)' \
      "$ADMIN_WEB_SRC" --glob '*.{ts,tsx}' 2>/dev/null || true
  )"
  if [[ -n "$admin_web_credential_storage" ]]; then
    report_error "Admin Web persists administrator credentials or session material:"
    printf '%s\n' "$admin_web_credential_storage" >&2
  fi

  admin_web_unsafe_rendering="$(
    rg -n 'dangerouslySetInnerHTML|document\.write\(|eval\(' \
      "$ADMIN_WEB_SRC" --glob '*.{ts,tsx}' 2>/dev/null || true
  )"
  if [[ -n "$admin_web_unsafe_rendering" ]]; then
    report_error "Admin Web contains unsafe dynamic rendering:"
    printf '%s\n' "$admin_web_unsafe_rendering" >&2
  fi
fi

system_admin_controller="apps/platform-server/src/main/java/com/spaceagent/platform/integration/infrastructure/http/PlatformSystemAdministrationHttpController.java"
if [[ -f "$system_admin_controller" ]]; then
  system_admin_controller_bypass="$(
    rg -n 'JdbcTemplate|\.infrastructure\.persistence|api_key_ciphertext|encrypted_auth_json|password_hash' \
      "$system_admin_controller" 2>/dev/null || true
  )"
  if [[ -n "$system_admin_controller_bypass" ]]; then
    report_error "System Admin internal controller bypasses owner APIs or exposes secret storage:"
    printf '%s\n' "$system_admin_controller_bypass" >&2
  fi

  required_system_admin_apis=(identity inference agent project conversation runtime tooling)
  for module in "${required_system_admin_apis[@]}"; do
    if ! find "$PLATFORM_MAIN_JAVA/com/spaceagent/platform/$module/api" \
      -maxdepth 1 -name '*SystemAdministrationApi.java' -print -quit | grep -q .; then
      report_error "platform-server module $module is missing its SystemAdministration Application API"
    fi
  done
fi

platform_admin_contract_secret_fields="$(
  rg -ni 'api.?key|ciphertext|encrypted|password.?hash|access.?token|refresh.?token' \
    contracts/platform-admin/v1 --glob '*.json' 2>/dev/null || true
)"
if [[ -n "$platform_admin_contract_secret_fields" ]]; then
  report_error "platform-admin wire contract contains a raw secret/ciphertext-shaped field:"
  printf '%s\n' "$platform_admin_contract_secret_fields" >&2
fi

platform_shared_business_imports="$(
  rg -n \
    '^import (static )?com\.spaceagent\.platform\.(identity|agent|project|conversation|memory|knowledge|context|runtime|inference|tooling|artifact|automation|integration|governance|observability)\.' \
    "$PLATFORM_MAIN_JAVA/com/spaceagent/platform/shared" 2>/dev/null || true
)"
if [[ -n "$platform_shared_business_imports" ]]; then
  report_error "platform-server shared module depends on another platform module:"
  printf '%s\n' "$platform_shared_business_imports" >&2
fi

domain_framework_imports="$(
  rg -n \
    '^import (static )?(org\.springframework|org\.mybatis|dev\.langchain4j|io\.temporal)\.' \
    "$PLATFORM_MAIN_JAVA/com/spaceagent/platform"/*/domain/*.java 2>/dev/null || true
)"
if [[ -n "$domain_framework_imports" ]]; then
  report_error "platform-server domain packages import framework/provider SDK types:"
  printf '%s\n' "$domain_framework_imports" >&2
fi

runtime_domain_imports="$(
  rg -n \
    '^import (static )?com\.spaceagent\.platform\.runtime\.' \
    "$PLATFORM_MAIN_JAVA/com/spaceagent/platform/project" \
    "$PLATFORM_MAIN_JAVA/com/spaceagent/platform/conversation" \
    "$PLATFORM_MAIN_JAVA/com/spaceagent/platform/memory" \
    "$PLATFORM_MAIN_JAVA/com/spaceagent/platform/knowledge" 2>/dev/null || true
)"
if [[ -n "$runtime_domain_imports" ]]; then
  report_error "platform-server project/conversation/memory/knowledge modules depend on runtime:"
  printf '%s\n' "$runtime_domain_imports" >&2
fi

inference_domain_imports="$(
  rg -n \
    '^import (static )?com\.spaceagent\.platform\.(identity|agent|project|conversation|memory|knowledge|context|runtime|tooling|artifact|automation|integration|governance|observability)\.' \
    "$PLATFORM_MAIN_JAVA/com/spaceagent/platform/inference" 2>/dev/null || true
)"
if [[ -n "$inference_domain_imports" ]]; then
  report_error "platform-server inference module depends on another business module:"
  printf '%s\n' "$inference_domain_imports" >&2
fi

agent_definition_ownership="$(
  rg -n '\b(taskId|projectId|workspaceId|conversationId|agentRunId|checkpointId)\b' \
    "$PLATFORM_MAIN_JAVA/com/spaceagent/platform/agent/domain/AgentDefinition.java" \
    2>/dev/null || true
)"
if [[ -n "$agent_definition_ownership" ]]; then
  report_error "AgentDefinition owns runtime/project/task/workspace/conversation state:"
  printf '%s\n' "$agent_definition_ownership" >&2
fi

python_business_persistence="$(
  rg -n '^(from|import) (asyncpg|psycopg|psycopg2|sqlalchemy|sqlite3|redis|pymongo)(\.| |$)' \
    workers/sandbox-worker \
    --glob '*.py' --glob '!**/__pycache__/**' 2>/dev/null || true
)"
if [[ -n "$python_business_persistence" ]]; then
  report_error "Python sandbox worker imports a business persistence client:"
  printf '%s\n' "$python_business_persistence" >&2
fi

retired_compatibility_paths=(
  "services/ai-orchestrator"
  "workers/sandbox-worker/sandbox_worker/executor.py"
  "apps/platform-server/src/main/java/com/spaceagent/platform/PlatformControlPlaneConfiguration.java"
  "apps/platform-server/src/main/java/com/spaceagent/platform/integration/infrastructure/http/PlatformGithubConnectionHttpController.java"
  "apps/platform-server/src/main/java/com/spaceagent/platform/project/application/GithubConnectionApplicationService.java"
)
for retired_path in "${retired_compatibility_paths[@]}"; do
  if [[ -e "$retired_path" ]]; then
    report_error "retired compatibility path returned: $retired_path"
  fi
done

retired_compatibility_refs="$(
  rg -n 'platform\.ai-orchestrator|platform\.source-control\.github|/api/v1/source-control/github|--executor process' \
    "$PLATFORM_MAIN_JAVA" cli workers/sandbox-worker --glob '!**/target/**' 2>/dev/null || true
)"
if [[ -n "$retired_compatibility_refs" ]]; then
  report_error "retired Python/native-GitHub/process compatibility reference returned:"
  printf '%s\n' "$retired_compatibility_refs" >&2
fi

source_control_filesystem_access="$(
  rg -n '^import java\.(nio\.file|io\.File)' \
    "$PLATFORM_MAIN_JAVA/com/spaceagent/platform/project" \
    --glob '*SourceRepository*.java' --glob '*WorkspaceBridge*.java' \
    --glob '*Github*.java' 2>/dev/null || true
)"
if [[ -n "$source_control_filesystem_access" ]]; then
  report_error "M19 server-side source-control code imports local filesystem path APIs:"
  printf '%s\n' "$source_control_filesystem_access" >&2
fi

for retired_workspace_gateway in \
  "$PLATFORM_MAIN_JAVA/com/spaceagent/platform/project/infrastructure/ManagedWorkspaceCodingGateway.java" \
  "$PLATFORM_MAIN_JAVA/com/spaceagent/platform/project/infrastructure/ManagedWorkspaceToolGateway.java"; do
  if [[ -e "$retired_workspace_gateway" ]]; then
    report_error "retired host Workspace gateway returned: $retired_workspace_gateway"
  fi
done
if ! rg -q 'WorkspaceSandboxGateway' \
  "$PLATFORM_MAIN_JAVA/com/spaceagent/platform/project/application/CodingWorkspaceApplicationService.java" \
  "$PLATFORM_MAIN_JAVA/com/spaceagent/platform/project/application/WorkspaceToolApplicationService.java"; then
  report_error "Project Workspace effects are not routed through the OCI Sandbox port"
fi

browser_local_path_fields="$(
  rg -n 'String (localPath|rootPath|absolutePath|serverPath)[,;)]' \
    "$PLATFORM_MAIN_JAVA/com/spaceagent/platform/integration/infrastructure/http/PlatformSourceRepositoryHttpController.java" \
    "$PLATFORM_MAIN_JAVA/com/spaceagent/platform/integration/infrastructure/http/PlatformLocalWorkspaceBridgeHttpController.java" \
    2>/dev/null || true
)"
if [[ -n "$browser_local_path_fields" ]]; then
  report_error "Browser-facing M19 DTOs accept local filesystem paths:"
  printf '%s\n' "$browser_local_path_fields" >&2
fi

TYPESCRIPT_ORCHESTRATOR="services/multi-agent-orchestrator"
if [[ -d "$TYPESCRIPT_ORCHESTRATOR" ]]; then
  typescript_business_persistence="$(
    rg -n \
      'from "(pg|postgres|postgresql|redis|ioredis|@prisma/client|typeorm|sequelize|mongoose)"|node:(child_process|cluster)' \
      "$TYPESCRIPT_ORCHESTRATOR/src" --glob '*.ts' 2>/dev/null || true
  )"
  if [[ -n "$typescript_business_persistence" ]]; then
    report_error "TypeScript orchestration imports persistence or side-effect process clients:"
    printf '%s\n' "$typescript_business_persistence" >&2
  fi

  typescript_authority_leaks="$(
    rg -n '\b(apiKey|providerSecret|encryptedApiKey|checkpointer|MemorySaver|PostgresSaver)\b' \
      "$TYPESCRIPT_ORCHESTRATOR/src" --glob '*.ts' 2>/dev/null || true
  )"
  if [[ -n "$typescript_authority_leaks" ]]; then
    report_error "TypeScript orchestration source contains secret or durable-checkpointer ownership:"
    printf '%s\n' "$typescript_authority_leaks" >&2
  fi

  framework_contract_leaks="$(
    rg -n 'LangGraph|LANGGRAPH|StateGraph|Command<|GraphNode' \
      contracts/multi-agent --glob '*.json' 2>/dev/null || true
  )"
  if [[ -n "$framework_contract_leaks" ]]; then
    report_error "multi-agent contracts expose LangGraph implementation types:"
    printf '%s\n' "$framework_contract_leaks" >&2
  fi
fi

cross_module_persistence_imports="$(
  rg -n \
    '^import (static )?com\.spaceagent\.platform\.(identity|agent|project|conversation|memory|knowledge|context|runtime|inference|tooling|artifact|automation|integration|governance|observability|shared)\.infrastructure\.persistence' \
    "$PLATFORM_MAIN_JAVA" 2>/dev/null || true
)"
if [[ -n "$cross_module_persistence_imports" ]]; then
  report_error "platform-server module imports another module's infrastructure persistence package:"
  printf '%s\n' "$cross_module_persistence_imports" >&2
fi

observability_authority_reads="$(
  rg -n 'platform_(agent_runs|model_call_ledger|conversations|messages|agent_definitions|tenants)\b' \
    "$PLATFORM_MAIN_JAVA/com/spaceagent/platform/observability" --glob '*.java' \
    2>/dev/null || true
)"
if [[ -n "$observability_authority_reads" ]]; then
  report_error "Observability Java persistence bypasses its read-only projection views:"
  printf '%s\n' "$observability_authority_reads" >&2
fi

http_controller_repository_imports="$(
  rg -n \
    '^import (static )?com\.spaceagent\.platform\..*(Repository|\.infrastructure\.persistence)' \
    "$PLATFORM_MAIN_JAVA/com/spaceagent/platform/integration/infrastructure/http" 2>/dev/null || true
)"
if [[ -n "$http_controller_repository_imports" ]]; then
  report_error "platform-server HTTP controllers access repositories/persistence instead of Application APIs:"
  printf '%s\n' "$http_controller_repository_imports" >&2
fi

project_handoff_boundary_leaks="$(
  rg -n 'JdbcTemplate|\.infrastructure\.persistence|ProcessBuilder|platform_project_' \
    "$PLATFORM_MAIN_JAVA/com/spaceagent/platform/integration/application/ProjectRunHandoffCoordinator.java" \
    "$PLATFORM_MAIN_JAVA/com/spaceagent/platform/integration/application/ProjectHandoffFinalizer.java" \
    2>/dev/null || true
)"
if [[ -n "$project_handoff_boundary_leaks" ]]; then
  report_error "Project handoff integration bypasses owner Application APIs:"
  printf '%s\n' "$project_handoff_boundary_leaks" >&2
fi

for required_handoff_file in \
  "$PLATFORM_MAIN_JAVA/com/spaceagent/platform/runtime/domain/ProjectRunHandoff.java" \
  "$PLATFORM_MAIN_JAVA/com/spaceagent/platform/runtime/api/ProjectRunHandoffApplicationApi.java" \
  "apps/platform-server/src/main/resources/db/platform-runtime/V1047__project_run_handoffs.sql"
do
  if [[ ! -f "$required_handoff_file" ]]; then
    report_error "M51-PR5 authoritative handoff artifact is missing: $required_handoff_file"
  fi
done

telemetry_sources=(
  "$PLATFORM_MAIN_JAVA/com/spaceagent/platform/inference/infrastructure/MicrometerInferenceTelemetry.java"
  "$PLATFORM_MAIN_JAVA/com/spaceagent/platform/runtime/infrastructure/MicrometerRuntimeOperationalTelemetry.java"
  "services/multi-agent-orchestrator/src/telemetry.ts"
  "workers/sandbox-worker/sandbox_worker/telemetry.py"
)
for telemetry_source in "${telemetry_sources[@]}"; do
  if [[ ! -f "$telemetry_source" ]]; then
    report_error "M52-PR2 telemetry source is missing: $telemetry_source"
  fi
done
genai_content_capture="$(
  rg -n 'gen_ai\.(input\.messages|output\.messages|system_instructions|tool\.call\.(arguments|result))' \
    "${telemetry_sources[@]}" 2>/dev/null || true
)"
if [[ -n "$genai_content_capture" ]]; then
  report_error "GenAI telemetry enables prohibited content-bearing attributes:"
  printf '%s\n' "$genai_content_capture" >&2
fi

if [[ ! -f "apps/platform-server/src/main/resources/db/platform-runtime/V1048__model_first_chunk_evidence.sql" ]]; then
  report_error "M52-PR2 ModelCall first-chunk migration is missing"
fi

chat_approval_checkpoint="$PLATFORM_MAIN_JAVA/com/spaceagent/platform/runtime/domain/ChatToolWaitCheckpoint.java"
chat_runtime_service="$PLATFORM_MAIN_JAVA/com/spaceagent/platform/runtime/application/ChatRuntimeApplicationService.java"
if [[ ! -f "$chat_approval_checkpoint" ]] || ! rg -q 'chat-approval/v1' "$chat_approval_checkpoint"; then
  report_error "M53-PR1 versioned Chat approval checkpoint is missing"
fi

tool_reconciliation_service="$PLATFORM_MAIN_JAVA/com/spaceagent/platform/runtime/application/RuntimeToolReconciliationApplicationService.java"
tool_reconciliation_api="$PLATFORM_MAIN_JAVA/com/spaceagent/platform/runtime/api/RuntimeToolReconciliationApplicationApi.java"
if [[ ! -f "$tool_reconciliation_service" ]] \
    || ! rg -q 'case "document_write"' "$tool_reconciliation_service" \
    || ! rg -q 'case "workspace-write_file"' "$tool_reconciliation_service" \
    || ! rg -q 'case "workspace-delete_file"' "$tool_reconciliation_service" \
    || rg -q 'external\.execute|workspace\.write' "$tool_reconciliation_service"; then
  report_error "M53-PR2 reconciliation is missing or can redispatch a mutation"
fi
if rg -q 'ToolExecutionStatus resolution|String result,|ReconciliationEvidence evidence' \
    "$tool_reconciliation_api"; then
  report_error "M53-PR2 public API allows client-directed ledger resolution/evidence"
fi

chat_task_migration="apps/platform-server/src/main/resources/db/platform-runtime/V1049__chat_root_task_scope.sql"
if [[ ! -f "$chat_task_migration" ]] \
    || ! rg -q 'ck_platform_task_scope' "$chat_task_migration" \
    || ! rg -q 'chat_task_id' "$chat_task_migration" \
    || ! rg -q 'source_message_id' "$chat_task_migration"; then
  report_error "M54-PR1 scoped Chat Root Task migration is missing or incomplete"
fi
if ! rg -q 'createOrGetChatRootTask' "$chat_runtime_service" \
    || rg -q 'createProject\(' "$chat_runtime_service" \
    || rg -q 'createPlan\(' "$chat_runtime_service"; then
  report_error "M54-PR1 Chat Task flow is missing or invents a hidden Project/TaskPlan"
fi

chat_plan_migration="apps/platform-server/src/main/resources/db/platform-runtime/V1050__chat_task_plan_scope.sql"
chat_plan_service="$PLATFORM_MAIN_JAVA/com/spaceagent/platform/project/application/TaskPlanApplicationService.java"
multi_agent_service="$PLATFORM_MAIN_JAVA/com/spaceagent/platform/runtime/application/MultiAgentOrchestrationApplicationService.java"
if [[ ! -f "$chat_plan_migration" ]] \
    || ! rg -q 'ck_platform_task_plan_scope' "$chat_plan_migration" \
    || ! rg -q 'source_agent_run_id' "$chat_plan_migration" \
    || ! rg -q 'fk_platform_plan_step_chat_child' "$chat_plan_migration"; then
  report_error "M54-PR2 scoped Chat TaskPlan migration is missing or incomplete"
fi
if ! rg -q 'createChatProposal' "$chat_plan_service" \
    || ! rg -q 'lockChatProposalSource' "$chat_plan_service" \
    || ! rg -q 'createChatProposal' "$multi_agent_service"; then
  report_error "M54-PR2 accepted LangGraph plans do not pass through Java TaskPlan authority"
fi

chat_plan_review_checkpoint="$PLATFORM_MAIN_JAVA/com/spaceagent/platform/runtime/domain/ChatPlanReviewCheckpoint.java"
if [[ ! -f "$chat_plan_review_checkpoint" ]] \
    || ! rg -q 'chat-plan-review/v1' "$chat_plan_review_checkpoint" \
    || ! rg -q 'beginAutomaticPlanning' "$chat_runtime_service" \
    || ! rg -q 'WAITING_PLAN_APPROVAL' "$chat_runtime_service" \
    || ! rg -q 'PLATFORM_CHAT_AUTOMATIC_PLANNING_ENABLED: \$\{CHAT_AUTOMATIC_PLANNING_ENABLED:-false\}' docker-compose.release.yml; then
  report_error "M54-PR3A automatic Chat planning is not durable or release-default-off"
fi
if ! rg -q 'resumePlan\(' "$chat_runtime_service" \
    || ! rg -q 'executeActivePlan\(' "$chat_runtime_service" \
    || ! rg -q 'transitionChatPlanStep' "$chat_runtime_service" \
    || ! rg -q 'planProgress\(\)' "$chat_runtime_service"; then
  report_error "M54-PR3B active DAG execution or Tool-wait plan progress is missing"
fi
if ! rg -q 'acquireLease' "$chat_runtime_service" \
    || ! rg -q 'resumeFenced' "$chat_runtime_service" \
    || ! rg -q 'markRunWaitingForUser' "$chat_runtime_service"; then
  report_error "M53-PR1 Chat approval resume is not protected by Runtime checkpoint/lease state"
fi

skill_migration="apps/platform-server/src/main/resources/db/platform-runtime/V1051__versioned_skill_registry.sql"
skill_registry="$PLATFORM_MAIN_JAVA/com/spaceagent/platform/tooling/application/SkillRegistryApplicationService.java"
skill_catalog="$PLATFORM_MAIN_JAVA/com/spaceagent/platform/tooling/application/RuntimeCapabilityCatalogApplicationService.java"
if [[ ! -f "$skill_migration" ]] \
    || ! rg -q 'platform_skill_definitions' "$skill_migration" \
    || ! rg -q 'platform_skill_versions' "$skill_migration" \
    || ! rg -q 'fk_platform_skill_definition_current_version' "$skill_migration"; then
  report_error "M55-PR1 versioned Skill persistence is missing or incomplete"
fi
if [[ ! -f "$skill_registry" ]] \
    || ! rg -q 'MAX_INSTRUCTION_BYTES' "$skill_registry" \
    || ! rg -q 'SKILL_TOOL_NOT_REGISTERED' "$skill_registry" \
    || rg -q 'ProcessBuilder|Runtime\.getRuntime' "$skill_registry" \
    || ! rg -q 'SkillVersionStatus\.PUBLISHED' "$skill_catalog"; then
  report_error "M55-PR1 Skill binding is not bounded, inert and published-version scoped"
fi
project_coding_coordinator="$PLATFORM_MAIN_JAVA/com/spaceagent/platform/integration/application/ProjectCodingCoordinator.java"
skill_context_type="$PLATFORM_MAIN_JAVA/com/spaceagent/platform/context/domain/ContextSourceType.java"
if ! rg -q 'resolvePinnedSkills' "$skill_catalog" \
    || ! rg -q 'AGENT_SKILL_TOOL_NOT_ENABLED' "$skill_catalog" \
    || ! rg -q 'SkillVersionStatus\.DEPRECATED' "$skill_catalog" \
    || ! rg -q 'SKILL' "$skill_context_type"; then
  report_error "M55-PR2 historical Skill pin or required Tool subset validation is missing"
fi
if ! rg -q 'ContextSourceType\.SKILL' "$chat_runtime_service" \
    || ! rg -q 'skill-context-bound' "$chat_runtime_service" \
    || ! rg -q 'checkpointSkillUse' "$chat_runtime_service" \
    || ! rg -q 'resolvePinnedSkills' "$project_coding_coordinator" \
    || ! rg -q 'checkpointSkillUse' "$project_coding_coordinator"; then
  report_error "M55-PR2 Chat/Project Skill context or RunStep evidence is missing"
fi

project_plan_execution_api="$PLATFORM_MAIN_JAVA/com/spaceagent/platform/integration/api/ProjectPlanExecutionApplicationApi.java"
project_plan_execution_http="$PLATFORM_MAIN_JAVA/com/spaceagent/platform/integration/infrastructure/http/PlatformProjectPlanExecutionHttpController.java"
if [[ ! -f "$project_plan_execution_api" ]] \
    || [[ ! -f "$project_plan_execution_http" ]] \
    || ! rg -q 'DispatchCommand' "$project_plan_execution_api" \
    || ! rg -q '/execute' "$project_plan_execution_http" \
    || ! rg -q 'project-plan-auto:' "$project_coding_coordinator" \
    || ! rg -q 'dispatchSuccessor' "$project_coding_coordinator" \
    || ! rg -q 'PROJECT_PLAN_ACTIVE_JOB_MISSING' "$project_coding_coordinator" \
    || ! rg -q 'completeRootTask' "$chat_plan_service" \
    || ! rg -q 'failRootTask' "$chat_plan_service"; then
  report_error "M56-PR1 durable Project Plan auto-dispatch or lifecycle closure is missing"
fi
if rg -q 'import .*\.infrastructure\.|JdbcTemplate' \
    "$project_plan_execution_api" "$project_plan_execution_http"; then
  report_error "M56-PR1 Project Plan execution API/HTTP bypasses owner-module persistence"
fi

project_plan_runtime_http="$PLATFORM_MAIN_JAVA/com/spaceagent/platform/integration/infrastructure/http/PlatformProjectPlanExecutionRuntimeHttpController.java"
project_plan_runtime_service="$PLATFORM_MAIN_JAVA/com/spaceagent/platform/runtime/application/ProjectPlanExecutionApplicationService.java"
project_coding_job_repository="$PLATFORM_MAIN_JAVA/com/spaceagent/platform/runtime/domain/ProjectCodingJobRepository.java"
if [[ ! -f "$project_plan_runtime_http" || ! -f "$project_plan_runtime_service" ]] \
    || ! rg -q 'ProjectCodingCoordinator' "$project_plan_runtime_http" \
    || ! rg -q 'coordinator\.dispatch' "$project_plan_runtime_http" \
    || ! rg -q 'insertIfAbsent\(created\)' "$project_plan_runtime_service" \
    || ! rg -q 'findActiveByExecutionId' "$project_plan_runtime_service" \
    || ! rg -q 'findActiveByExecutionId' "$project_coding_job_repository" \
    || ! rg -q 'completeReviewedJob\(' "$project_coding_coordinator" \
    || ! rg -q 'lockedExecution\.state\(\)' "$project_coding_coordinator" \
    || ! rg -q 'planExecutions\.fail\(transition, code\)' "$project_coding_coordinator"; then
  report_error "M57 Project Plan execution remediation boundary is incomplete"
fi

chat_http_bypass_imports="$(
  rg -n \
    '^import (static )?com\.spaceagent\.platform\.(conversation|inference|tooling)\.' \
    "$PLATFORM_MAIN_JAVA/com/spaceagent/platform/integration/infrastructure/http/PlatformChatHttpController.java" \
    2>/dev/null || true
)"
if [[ -n "$chat_http_bypass_imports" ]]; then
  report_error "PlatformChatHttpController bypasses the Runtime facade:"
  printf '%s\n' "$chat_http_bypass_imports" >&2
fi

knowledge_owner_imports="$(
  rg -n \
    '^import (static )?com\.spaceagent\.platform\.(agent|conversation|runtime)\.' \
    "$PLATFORM_MAIN_JAVA/com/spaceagent/platform/knowledge" 2>/dev/null || true
)"
if [[ -n "$knowledge_owner_imports" ]]; then
  report_error "Knowledge depends on Agent, Conversation, or Runtime:"
  printf '%s\n' "$knowledge_owner_imports" >&2
fi

obsolete_web_paths=(
  "copy/frontend"
  "copy/shared"
  "packages/web-contracts"
  ".kiro/specs/frontend-redesign"
  ".kiro/specs/ai-agent-workspace-frontend"
  "scripts/smoke-new-web-p0.py"
  "scripts/smoke-new-web-p1.py"
  "docs/new-web-backend-adaptation-plan.md"
  "docs/architecture/WEB-FRONTEND-INTEGRATION-AUDIT.md"
  "docs/interview-demo-runbook.md"
)
for obsolete_web_path in "${obsolete_web_paths[@]}"; do
  if [[ -e "$obsolete_web_path" ]]; then
    report_error "Superseded Web artifact returned to the active tree: $obsolete_web_path"
  fi
done

obsolete_web_dependency_refs="$(
  rg -n -i \
    '@linker/frontend|@spaceagent/web-contracts|better-auth|sandpack-react|radix-ui|recharts|frontend-agent|copy/frontend|copy/shared' \
    package.json package-lock.json apps/web apps/admin-web .github README.md .gitignore .dockerignore \
    --glob '!apps/web/prototypes/**' --glob '!apps/web/dist/**' \
    --glob '!apps/admin-web/dist/**' 2>/dev/null || true
)"
if [[ -n "$obsolete_web_dependency_refs" ]]; then
  report_error "Superseded Web dependency or path reference returned:"
  printf '%s\n' "$obsolete_web_dependency_refs" >&2
fi

obsolete_web_api_aliases="$(
  rg -n '/users/(agents|sessions|model-providers|knowledge-bases)|/admin/model-providers' \
    "$PLATFORM_MAIN_JAVA/com/spaceagent/platform/integration/infrastructure/http" \
    --glob '*.java' 2>/dev/null || true
)"
if [[ -n "$obsolete_web_api_aliases" ]]; then
  report_error "Superseded Web-only HTTP alias returned:"
  printf '%s\n' "$obsolete_web_api_aliases" >&2
fi

if (( ERRORS > 0 )); then
  echo "Architecture validation failed with $ERRORS problem(s)." >&2
  exit 1
fi

echo "Architecture validation passed."
