package com.aeldrin.teleportblock.compat.ftbchunks;

import dev.ftb.mods.ftbchunks.api.ClaimedChunk;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftblibrary.math.ChunkDimPos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

public class FTBChunksCompat {

    public static boolean canTeleportTo(ServerPlayer player, ServerLevel level, BlockPos target) {
        ChunkPos chunkPos = new ChunkPos(target);
        ChunkDimPos chunkDimPos = new ChunkDimPos(level.dimension(), chunkPos.x, chunkPos.z);
        ClaimedChunk chunk = FTBChunksAPI.api().getManager().getChunk(chunkDimPos);

        System.out.println("FTBChunks check: chunk=" + chunk + " pos=" + target);

        // Чанк не застолблен — телепорт разрешён
        if (chunk == null) return true;

        // Игрок член команды которой принадлежит чанк — разрешён
        if (chunk.getTeamData().isTeamMember(player.getUUID())) return true;

        // Проверяем есть ли у игрока bypass (например оператор)
        if (FTBChunksAPI.api().getManager().getBypassProtection(player.getUUID())) return true;

        return false;
    }
}