package com.aeldrin.teleportblock.advancement;

import com.aeldrin.teleportblock.TeleportBlockMod;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.MinMaxBounds;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

public class TeleportTrigger extends SimpleCriterionTrigger<TeleportTrigger.TriggerInstance> {

    public static final TeleportTrigger INSTANCE = new TeleportTrigger();

    @Override
    public Codec<TriggerInstance> codec() {
        return TriggerInstance.CODEC;
    }

    public void trigger(ServerPlayer player, int totalTeleports) {
        this.trigger(player, instance -> instance.matches(totalTeleports));
    }

    public record TriggerInstance(
            Optional<ContextAwarePredicate> player,
            MinMaxBounds.Ints teleportCount
    ) implements SimpleCriterionTrigger.SimpleInstance {

        public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(TriggerInstance::player),
                        MinMaxBounds.Ints.CODEC.optionalFieldOf("teleport_count", MinMaxBounds.Ints.ANY).forGetter(TriggerInstance::teleportCount)
                ).apply(instance, TriggerInstance::new)
        );

        public boolean matches(int totalTeleports) {
            return teleportCount.matches(totalTeleports);
        }
    }
}