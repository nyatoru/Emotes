package io.github.kosmx.emotes.velocity.services;

import io.github.kosmx.emotes.server.services.InstanceService;

import java.nio.file.Path;

public class VelocityInstanceService implements InstanceService {

    private static Path gameDir = Path.of("");

    public static void setGameDirectory(Path dir) {
        gameDir = dir;
    }

    @Override
    public Path getGameDirectory() {
        return gameDir;
    }

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    public boolean isServiceActive() {
        return true;
    }
}
