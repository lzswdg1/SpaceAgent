package com.spaceagent.platform.project.domain;

/** Internal execution refs are never repository choices or user-facing branch metadata. */
public final class RepositoryBranchVisibility {
    private RepositoryBranchVisibility() { }

    public static boolean visible(String value) {
        if (value == null || value.isBlank()) return false;
        String branch = value.trim().replaceFirst("^refs/heads/", "")
                .replaceFirst("^(refs/remotes/)?origin/", "");
        return !branch.equals("spaceagent") && !branch.startsWith("spaceagent/")
                && !branch.equals("spaceagent-intake") && !branch.startsWith("spaceagent-intake/");
    }

    public static String publicRef(String value) { return visible(value) ? value : ""; }
}
