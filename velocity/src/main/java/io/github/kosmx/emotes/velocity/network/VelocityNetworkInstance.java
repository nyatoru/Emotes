package io.github.kosmx.emotes.velocity.network;

import com.velocitypowered.api.proxy.Player;
import io.github.kosmx.emotes.common.network.EmotePacket;
import io.github.kosmx.emotes.server.network.instance.ConfigNetworkInstance;
import io.github.kosmx.emotes.server.network.instance.ServerNetworkInstance;

import java.util.UUID;

public class VelocityNetworkInstance extends ServerNetworkInstance {

    private final Player player;

    public VelocityNetworkInstance(ConfigNetworkInstance configInstance, Player player) {
        super(configInstance);
        this.player = player;
    }

    public Player getPlayer() {
        return player;
    }

    @Override
    public UUID getUUID() {
        return player.getUniqueId();
    }

    @Override
    public void sendMessage(EmotePacket.Builder packet, boolean updateVersions) {
        if (!isActive()) return;
        if (updateVersions) packet.setVersion(getVersions());
        try {
            byte[] bytes = VelocityEmotePlay.encode(packet.build());
            player.sendPluginMessage(VelocityEmotePlay.EMOTE_CHANNEL, bytes);
        } catch (Exception e) {
            // Don't tear down proxy on single send failure
        }
    }

    @Override
    public void disconnect() {
        // no-op, handled by ProxyDisconnectEvent
    }

    @Override
    public boolean isActive() {
        return player.isActive();
    }
}
