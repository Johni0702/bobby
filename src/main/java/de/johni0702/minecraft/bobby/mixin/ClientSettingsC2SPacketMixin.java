package de.johni0702.minecraft.bobby.mixin;

import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(SyncedClientOptions.class)
public abstract class ClientSettingsC2SPacketMixin {
    @ModifyArg(method = "write", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/PacketByteBuf;writeByte(I)Lnet/minecraft/network/PacketByteBuf;", ordinal = 0))
    private int clampMaxValue(int viewDistance) {
        return Math.min(viewDistance, Byte.MAX_VALUE);
    }
}
