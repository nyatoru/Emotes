package io.github.kosmx.emotes.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.kosmx.emotes.common.CommonData;
import io.github.kosmx.emotes.server.config.CommonConfig;
import io.github.kosmx.emotes.server.config.ConfigSerializer;
import io.github.kosmx.emotes.server.config.Serializer;
import io.github.kosmx.emotes.server.serializer.UniversalEmoteSerializer;
import io.github.kosmx.emotes.velocity.network.VelocityEmotePlay;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

@Plugin(
        id = "emotecraft",
        name = "emotecraft",
        version = "0.0.0",
        description = "Velocity proxy for Emotecraft",
        authors = {"KosmX", "dima_dencep"},
        url = "https://github.com/KosmX/emotes"
)
public class VelocityWrapper {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private VelocityEmotePlay networkPlay;

    @Inject
    public VelocityWrapper(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        try {
            Files.createDirectories(dataDirectory);
        } catch (Exception ignored) {
        }
        // Keep proxy config separate from backend servers' emotecraft.json
        Path configPath = dataDirectory.resolve("emotecraft.json");
        // Tell InstanceService where to find config/emotes on the proxy
        io.github.kosmx.emotes.velocity.services.VelocityInstanceService.setGameDirectory(dataDirectory);

        Serializer.INSTANCE = new Serializer<>(new ConfigSerializer<>(CommonConfig::new, CommonConfig.staticConfigVersion), CommonConfig.class, configPath);
        UniversalEmoteSerializer.loadEmotes();

        this.networkPlay = new VelocityEmotePlay(server);
        server.getEventManager().register(this, networkPlay);

        logger.info("Loading Emotecraft as a Velocity plugin...");

        server.getChannelRegistrar().register(VelocityEmotePlay.EMOTE_CHANNEL);
        logger.info("Emotecraft Velocity proxy registered channel {}", VelocityEmotePlay.EMOTE_CHANNEL);
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        server.getChannelRegistrar().unregister(VelocityEmotePlay.EMOTE_CHANNEL);
        if (networkPlay != null) {
            server.getEventManager().unregisterListener(this, networkPlay);
        }
    }
}
