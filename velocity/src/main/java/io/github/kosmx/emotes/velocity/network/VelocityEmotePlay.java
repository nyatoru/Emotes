package io.github.kosmx.emotes.velocity.network;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import io.github.kosmx.emotes.common.CommonData;
import io.github.kosmx.emotes.common.network.EmotePacket;
import io.github.kosmx.emotes.common.network.PacketBound;
import io.github.kosmx.emotes.common.network.objects.NetData;
import io.github.kosmx.emotes.server.network.AbstractServerEmotePlay;
import io.github.kosmx.emotes.server.network.instance.ConfigNetworkInstance;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Velocity proxy emote relay.
 *
 * <p>The proxy does NOT render emotes — it only relays {@code emotecraft:emote} plugin messages
 * between players across backend servers (and handles config handshake for download-on-join emotes).
 * Actual play-state tracking / validation still lives in {@link AbstractServerEmotePlay}.
 *
 * <p>Channel semantics:
 * <ul>
 *   <li>{@code emotecraft:emote} — the single emotecraft channel (since networking v8).</li>
 *   <li>Velocity forwards plugin messages between {@code Player}s; the backend Paper server still
 *       does tracker-based broadcast within its own world. On the proxy we broadcast to all
 *       currently connected players except the sender (the backend will de-duplicate for colocated
 *       players via its own tracker check).</li>
 * </ul>
 */
public class VelocityEmotePlay extends AbstractServerEmotePlay<VelocityNetworkInstance> {

    public static final ChannelIdentifier EMOTE_CHANNEL =
            MinecraftChannelIdentifier.from(CommonData.getIDAsString(CommonData.playEmoteID));

    private final ProxyServer server;

    /**
     * All known players currently connected to the proxy.
     */
    private final Map<UUID, VelocityNetworkInstance> players = new ConcurrentHashMap<>();

    /**
     * Per-player config handshake state. A player in this set has not yet finished the emotecraft
     * config exchange (equivalent to Paper's {@code PaperConfigTask.ON_CONFIG}).
     */
    private final Map<UUID, ConfigNetworkInstance> pendingConfigs = new ConcurrentHashMap<>();

    public VelocityEmotePlay(ProxyServer server) {
        this.server = server;
    }

    // ---- Encoding helpers ----

    public static byte[] encode(EmotePacket packet) {
        ByteBuf buf = Unpooled.buffer();
        try {
            packet.write(buf, PacketBound.CLIENT);
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    private static EmotePacket decode(byte[] data, PacketBound bound) {
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        try {
            return new EmotePacket(buf, bound);
        } finally {
            buf.release();
        }
    }

    // ---- Proxy events ----

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        // (Re)register on every server switch so downstream forwarding keeps working.
        // This also covers the initial join.
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        // Keep the negotiated config if we already had one; otherwise start fresh.
        VelocityNetworkInstance existing = players.get(uuid);
        ConfigNetworkInstance cfg = existing != null
                ? new ConfigNetworkInstance(new ConcurrentHashMap<>(existing.getVersions()))
                : pendingConfigs.getOrDefault(uuid, new ConfigNetworkInstance());
        // Players that haven't completed config yet stay in pendingConfigs until a valid config packet arrives.
        players.put(uuid, new VelocityNetworkInstance(cfg, player));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        VelocityNetworkInstance inst = players.remove(uuid);
        if (inst != null) inst.disconnect();
        pendingConfigs.remove(uuid);
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!EMOTE_CHANNEL.equals(event.getIdentifier())) return;

        // We handle the message ourselves — don't auto-forward to the backend server as a raw plugin message.
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        if (!(event.getSource() instanceof Player player)) return;

        UUID uuid = player.getUniqueId();
        VelocityNetworkInstance net = players.get(uuid);
        if (net == null) {
            // Player connected before this listener was registered (race on reload); create entry.
            ConfigNetworkInstance cfg = pendingConfigs.getOrDefault(uuid, new ConfigNetworkInstance());
            net = new VelocityNetworkInstance(cfg, player);
            players.put(uuid, net);
        }

        byte[] raw = event.getData();
        VelocityNetworkInstance finalNet = net;
        try {
            EmotePacket packet = decode(raw, PacketBound.SERVER);

            // Config exchange (download-on-join) — player sends CONFIG during login.
            // We negotiate here so the proxy can stream builtin/server emotes to the client
            // even if the backend server has no emotecraft.
            if (packet.data.purpose == io.github.kosmx.emotes.common.network.PacketTask.CONFIG) {
                ConfigNetworkInstance cfg = pendingConfigs.get(uuid);
                if (cfg == null) {
                    cfg = new ConfigNetworkInstance(new ConcurrentHashMap<>(finalNet.getVersions()));
                    pendingConfigs.put(uuid, cfg);
                }
                try {
                    cfg.receiveConfigMessage(packet, reply -> {
                        try {
                            player.sendPluginMessage(EMOTE_CHANNEL, encode(reply));
                        } catch (Exception ignored) {
                        }
                    });
                    // Mirror negotiated versions onto the player instance so streaming uses the right versions
                    finalNet.setVersions(cfg.versions());
                    pendingConfigs.remove(uuid);
                } catch (Exception e) {
                    CommonData.LOGGER.warn("Invalid emotecraft config from {}", player.getUsername(), e);
                }
                return;
            }

            // Clear pending if player started streaming without completing config (vanilla fallback)
            pendingConfigs.remove(uuid);

            receiveMessage(packet, finalNet);
        } catch (Exception e) {
            CommonData.LOGGER.warn("Invalid emotecraft packet from {}", player.getUsername(), e);
        }
    }

    // ---- AbstractServerEmotePlay impl ----

    @Override
    protected VelocityNetworkInstance getPlayerFromUUID(UUID uuid) {
        VelocityNetworkInstance inst = players.get(uuid);
        if (inst != null) return inst;

        // Player may exist on proxy but not yet in our map (e.g. event ordering race)
        return server.getPlayer(uuid).map(p -> {
            ConfigNetworkInstance cfg = pendingConfigs.getOrDefault(uuid, new ConfigNetworkInstance());
            VelocityNetworkInstance created = new VelocityNetworkInstance(cfg, p);
            players.put(uuid, created);
            return created;
        }).orElse(null);
    }

    @Override
    protected void sendForTrackedBy(NetData data, VelocityNetworkInstance sender) {
        // On a proxy there is no world/tracker — broadcast to all other connected players.
        // Backend Paper servers already filter by tracking; this ensures cross-server visibility.
        byte[] encoded;
        try {
            encoded = encode(new EmotePacket.Builder(data.copy()).build());
        } catch (Exception e) {
            CommonData.LOGGER.warn("Failed to encode emote packet", e);
            return;
        }
        UUID senderId = sender.getUUID();
        for (VelocityNetworkInstance target : players.values()) {
            if (target.getUUID().equals(senderId)) continue;
            if (!target.isActive()) continue;
            try {
                target.getPlayer().sendPluginMessage(EMOTE_CHANNEL, encoded);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void sendForEveryone(NetData data) {
        byte[] encoded;
        try {
            encoded = encode(new EmotePacket.Builder(data.copy()).build());
        } catch (Exception e) {
            CommonData.LOGGER.warn("Failed to encode emote packet", e);
            return;
        }
        for (VelocityNetworkInstance target : players.values()) {
            if (!target.isActive()) continue;
            try {
                target.getPlayer().sendPluginMessage(EMOTE_CHANNEL, encoded);
            } catch (Exception ignored) {
            }
        }
    }

    public static VelocityEmotePlay getInstance() {
        return (VelocityEmotePlay) AbstractServerEmotePlay.getInstance();
    }
}
