-- Additive KnowledgeBase authority. Legacy owner-only documents are not reassigned here.
CREATE TABLE platform_knowledge_bases (
    id VARCHAR(36) PRIMARY KEY,
    scope VARCHAR(20) NOT NULL CHECK (scope IN ('PERSONAL','ORGANIZATION')),
    organization_id VARCHAR(36) REFERENCES platform_tenants(id) ON DELETE CASCADE,
    owner_id VARCHAR(36) REFERENCES platform_users(id) ON DELETE SET NULL,
    name VARCHAR(255) NOT NULL CHECK (length(trim(name)) > 0),
    description VARCHAR(2000),
    state VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVE','ARCHIVED')),
    revision BIGINT NOT NULL CHECK (revision > 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_knowledge_base_scope CHECK (
        (scope='PERSONAL' AND organization_id IS NULL AND owner_id IS NOT NULL)
        OR (scope='ORGANIZATION' AND organization_id IS NOT NULL)),
    CONSTRAINT uk_knowledge_base_organization UNIQUE(id, organization_id)
);
CREATE INDEX idx_knowledge_base_personal ON platform_knowledge_bases(owner_id, created_at DESC, id)
    WHERE scope='PERSONAL' AND state='ACTIVE';
CREATE INDEX idx_knowledge_base_organization ON platform_knowledge_bases(organization_id, created_at DESC, id)
    WHERE scope='ORGANIZATION' AND state='ACTIVE';

CREATE TABLE platform_knowledge_base_grants (
    base_id VARCHAR(36) NOT NULL,
    organization_id VARCHAR(36) NOT NULL,
    subject_type VARCHAR(8) NOT NULL CHECK(subject_type IN ('USER','ROLE')),
    subject_id VARCHAR(64) NOT NULL CHECK(length(trim(subject_id)) > 0),
    permission VARCHAR(8) NOT NULL CHECK(permission IN ('READ','WRITE','MANAGE')),
    granted_by VARCHAR(36) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(base_id, subject_type, subject_id),
    FOREIGN KEY(base_id, organization_id) REFERENCES platform_knowledge_bases(id, organization_id) ON DELETE CASCADE,
    CONSTRAINT ck_knowledge_base_grant_role CHECK(subject_type='USER'
        OR (subject_id IN ('OWNER','ADMIN','MEMBER','VIEWER') AND (subject_id<>'VIEWER' OR permission='READ')))
);
CREATE INDEX idx_knowledge_base_grant_subject
    ON platform_knowledge_base_grants(organization_id, subject_type, subject_id, base_id);
COMMENT ON TABLE platform_knowledge_bases IS 'Personal or explicitly shared organization knowledge; document migration is separate';
COMMENT ON COLUMN platform_knowledge_bases.owner_id IS 'Creator; deleted organization creator may be null, current organization OWNER retains management';
