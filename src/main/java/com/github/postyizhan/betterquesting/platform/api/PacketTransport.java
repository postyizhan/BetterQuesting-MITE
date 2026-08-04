package com.github.postyizhan.betterquesting.platform.api;

import moddedmite.rustedironcore.network.Packet;
import net.minecraft.ServerPlayer;

public interface PacketTransport {
    void sendToClient(ServerPlayer player, Packet packet);
}
