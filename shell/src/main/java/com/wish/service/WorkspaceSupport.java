package com.wish.service;

import java.nio.file.Files;
import java.nio.file.Path;

final class WorkspaceSupport {

    private WorkspaceSupport() {}

    static Path resolveWorkspaceRoot(String configured) {
        Path root = Path.of(configured).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create workspace directory: " + root, e);
        }
        return root;
    }
}
