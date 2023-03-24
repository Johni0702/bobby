package de.johni0702.minecraft.bobby.mixin;

import net.minecraft.network.packet.c2s.play.ClientSettingsC2SPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ClientSettingsC2SPacket.class)
public abstract class ClientSettingsC2SPacketMixin {
    @Shadow
    @Final
    private int viewDistance;

    @ModifyArg(method = "write", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/PacketByteBuf;writeByte(I)Lio/netty/buffer/ByteBuf;", ordinal = 0))
    private int clampMaxValue(int viewDistance) {
        return Math.min(viewDistance, Byte.MAX_VALUE);
    }
}
