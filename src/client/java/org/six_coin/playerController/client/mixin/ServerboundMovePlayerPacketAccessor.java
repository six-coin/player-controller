package org.six_coin.playerController.client.mixin;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 让 {@code feature/MovePackets} 能改写即将发出去的移动包。
 *
 * <p>{@code ServerboundMovePlayerPacket} 的坐标 / 朝向字段都是 {@code protected final}，
 * 这里借 {@code @Mutable + @Accessor} 把它们打开 —— 跟 Meteor 的
 * {@code ServerboundMovePlayerPacketAccessor} 是同一个做法。
 */
@Mixin(ServerboundMovePlayerPacket.class)
public interface ServerboundMovePlayerPacketAccessor {

    @Mutable
    @Accessor("x")
    void playerController$setX(double x);

    @Mutable
    @Accessor("y")
    void playerController$setY(double y);

    @Mutable
    @Accessor("z")
    void playerController$setZ(double z);

    @Mutable
    @Accessor("yRot")
    void playerController$setYRot(float yRot);

    @Mutable
    @Accessor("xRot")
    void playerController$setXRot(float xRot);

    @Mutable
    @Accessor("onGround")
    void playerController$setOnGround(boolean onGround);
}
