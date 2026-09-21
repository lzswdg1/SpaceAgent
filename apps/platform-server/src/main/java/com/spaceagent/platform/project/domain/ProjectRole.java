package com.spaceagent.platform.project.domain;

/** Project-local authorization role, independent from Tenant membership roles. */
public enum ProjectRole {
    OWNER,
    ADMIN,
    MEMBER,
    VIEWER;

    public boolean canView() {
        return true;
    }

    public boolean canModify() {
        return this == OWNER || this == ADMIN;
    }

    public boolean canArchive() {
        return this == OWNER;
    }

    public boolean canManageMembers() {
        return this == OWNER;
    }

    public boolean canManageTasks() {
        return this == OWNER || this == ADMIN;
    }

    public boolean canWorkOnTasks() {
        return this == OWNER || this == ADMIN || this == MEMBER;
    }

    public boolean canAccessProjectMemory() {
        return this == OWNER || this == ADMIN || this == MEMBER;
    }
}
