package com.spaceagent.platform.integration.application;
import org.springframework.stereotype.Service;
import java.util.*;

/** An explicit description of existing command contracts, never an authorization grant. */
@Service public class AdministrationCapabilityCatalog {
    public record Capability(String resource,String mode,List<String> commands,List<String> constraints){}
    public List<Capability> capabilities(){return List.of(
        writable("USER",List.of("USER_CREATE","USER_UPDATE","USER_SUSPEND","USER_RESTORE","USER_SESSIONS_REVOKE","USER_PASSWORD_RESET","USER_DELETION_PREFLIGHT","USER_DELETION_REQUEST")),
        writable("ORGANIZATION",List.of("ORGANIZATION_CREATE","ORGANIZATION_UPDATE","ORGANIZATION_DELETE","ORGANIZATION_MEMBER_ADD","ORGANIZATION_MEMBER_ROLE_UPDATE","ORGANIZATION_MEMBER_REMOVE","ORGANIZATION_OWNER_TRANSFER")),
        writable("MCP_REGISTRY",List.of("MCP_REGISTRY_SYNC_REQUEST","MCP_REGISTRY_CANDIDATE_APPROVE","MCP_REGISTRY_CANDIDATE_REJECT")),
        writable("ARTIFACT_DELETION",List.of("ARTIFACT_DELETION_RETRY")),
        readonly("AGENT_CONFIGURATION","CONTENT_EDIT_UNAVAILABLE; OWNER_APPROVAL_NOT_BYPASSED"),
        readonly("PROVIDER_MCP_CREDENTIAL","NO_PLAINTEXT; NO_GENERIC_ROTATION_COMMAND"),
        readonly("PROJECT_TASK_WORKSPACE_CONVERSATION","NO_USER_IMPERSONATION_OR_CODE_EDIT"),
        readonly("KNOWLEDGE_MEMORY_AUTOMATION","NO_GENERIC_ROW_CRUD"),
        readonly("AUDIT_USAGE_LEDGER","IMMUTABLE; UNKNOWN_NOT_RETRIED"),
        readonly("RESOURCE_MEASUREMENTS","U04_DEFERRED; NO_SYNTHETIC_CPU_MEMORY_NETWORK_VALUES"));}
    private static Capability writable(String resource,List<String> commands){return new Capability(resource,"CONTROLLED_COMMANDS",commands,List.of("RECENT_MFA","REASON","IDEMPOTENCY","OWNER_LIFECYCLE_VALIDATION","ASYNC_RESULT_REQUIRES_COMMAND_OR_OWNER_EVIDENCE"));}
    private static Capability readonly(String resource,String constraint){return new Capability(resource,"READ_ONLY",List.of(),List.of(constraint));}
}
