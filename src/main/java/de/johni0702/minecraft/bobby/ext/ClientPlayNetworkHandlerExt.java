package de.johni0702.minecraft.bobby.ext;

import net.minecraft.client.network.ClientPlayNetworkHandler;

public interface ClientPlayNetworkHandlerExt {
    void bobby_queueUnloadFakeLightDataTask(Runnable runnable);

    static ClientPlayNetworkHandlerExt get(ClientPlayNetworkHandler handler) {
        return (ClientPlayNetworkHandlerExt) handler;
    }
}
