package com.aeldrin.teleportblock;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.common.util.DeferredSoundType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

// Звуки мода (2.2). Собственные SoundEvent вместо прямого вызова ванильных - чтобы у них были
// свои субтитры и чтобы ресурспаки (например Faithful) могли заменить звук. Сами звуки по
// умолчанию ссылаются на ванильные события в assets/teleportblock/sounds.json ("type": "event"):
//   - AMBIENT         -> block.respawn_anchor.ambient (тихое гудение блока со связью между измерениями;
//                        якорь возрождения входит в рецепт блока, поэтому звук тематичный)
//   - CROSS_DIMENSION -> block.portal.travel (переход в другое измерение - игрок слышит его после
//                        межмирового телепорта, как после ванильного портала)
//   - звуки самого блока (TELEPORT_BLOCK_SOUNDS) - заменили плейсхолдер AMETHYST:
//       установка  -> block.respawn_anchor.charge  (якорь возрождения входит в рецепт - блок
//       разрушение -> block.respawn_anchor.deplete  "заряжается" при установке и "разряжается" при сломе)
//       шаги / удар / падение -> block.lodestone.*  (тяжёлый "магнитный" камень)
//     Субтитры - ванильные общие ключи (subtitles.block.generic.*): Minecraft уже перевёл их на все
//     языки, и субтитры совпадают с ванильными блоками. У падения субтитра нет, как в ванилле.
public class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, TeleportBlockMod.MODID);

    public static final DeferredHolder<SoundEvent, SoundEvent> AMBIENT = register("block.teleport_block.ambient");
    public static final DeferredHolder<SoundEvent, SoundEvent> CROSS_DIMENSION = register("block.teleport_block.cross_dimension");

    public static final DeferredHolder<SoundEvent, SoundEvent> BREAK = register("block.teleport_block.break");
    public static final DeferredHolder<SoundEvent, SoundEvent> STEP = register("block.teleport_block.step");
    public static final DeferredHolder<SoundEvent, SoundEvent> PLACE = register("block.teleport_block.place");
    public static final DeferredHolder<SoundEvent, SoundEvent> HIT = register("block.teleport_block.hit");
    public static final DeferredHolder<SoundEvent, SoundEvent> FALL = register("block.teleport_block.fall");

    // DeferredSoundType (NeoForge) принимает поставщики звуков: сами SoundEvent регистрируются позже
    // создания свойств блока, поэтому обычный SoundType с готовыми звуками здесь не подходит.
    public static final DeferredSoundType TELEPORT_BLOCK_SOUNDS =
            new DeferredSoundType(1.0f, 1.0f, BREAK, STEP, PLACE, HIT, FALL);

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUND_EVENTS.register(name, () -> SoundEvent.createVariableRangeEvent(
                ResourceLocation.fromNamespaceAndPath(TeleportBlockMod.MODID, name)));
    }
}
