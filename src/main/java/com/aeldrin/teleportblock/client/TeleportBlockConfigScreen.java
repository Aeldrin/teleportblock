package com.aeldrin.teleportblock.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

// Экран настроек мода (кнопка "Config" в списке модов - доступен из главного меню и из меню паузы).
// Две кнопки, по одной на каждую среду (см. ModConfig):
//   - "Одиночная игра" - открывает встроенный экран NeoForge (ConfigurationScreen) с ползунками,
//     Undo и сбросом до значений по умолчанию. Погашена, если игрок подключён к чужому серверу:
//     там действуют настройки сервера, а локальный файл ни на что не влияет.
//   - "Сервер" - у клиента всегда погашена, только подсказка: серверные настройки админ правит
//     в config/teleportblock-server.toml на самом сервере, клиент этот файл не видит.
// Свой экран вместо прямой регистрации ConfigurationScreen нужен потому, что ConfigurationScreen
// объявлен final и сам не умеет гасить COMMON-конфиг при игре на сервере. Так как у клиента
// зарегистрирован ровно один конфиг, ConfigurationScreen сразу открывает список настроек, а при
// выходе автоматически возвращает на этот экран.
// Класс чисто клиентский: вызывается из TeleportBlockMod только под проверкой Dist.CLIENT.
public class TeleportBlockConfigScreen extends Screen {
    private static final int BUTTON_WIDTH = 250;

    private final ModContainer mod;
    private final Screen parent;

    public TeleportBlockConfigScreen(ModContainer mod, Screen parent) {
        super(Component.translatable("teleportblock.configuration.title"));
        this.mod = mod;
        this.parent = parent;
    }

    public static void register(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (modContainer, parentScreen) -> new TeleportBlockConfigScreen(modContainer, parentScreen));
    }

    @Override
    protected void init() {
        int x = (this.width - BUTTON_WIDTH) / 2;
        int y = this.height / 4 + 24;

        // Та же проверка, что использует NeoForge для SERVER-конфигов: подключены к серверу и это
        // не свой мир. Хост LAN-мира остаётся в isSingleplayer() и может менять настройки,
        // гости LAN-мира - нет (для них это чужой сервер).
        boolean onRemoteServer = this.minecraft.getCurrentServer() != null && !this.minecraft.isSingleplayer();

        Button singleplayer = Button.builder(
                        Component.translatable("teleportblock.configuration.singleplayer"),
                        button -> this.minecraft.setScreen(new ConfigurationScreen(this.mod, this)))
                .bounds(x, y, BUTTON_WIDTH, 20)
                .tooltip(Tooltip.create(Component.translatable(onRemoteServer
                        ? "teleportblock.configuration.singleplayer.online"
                        : "teleportblock.configuration.singleplayer.tooltip")))
                .build();
        singleplayer.active = !onRemoteServer;
        this.addRenderableWidget(singleplayer);

        Button server = Button.builder(
                        Component.translatable("teleportblock.configuration.server"),
                        button -> {})
                .bounds(x, y + 24, BUTTON_WIDTH, 20)
                .tooltip(Tooltip.create(Component.translatable(onRemoteServer
                        ? "teleportblock.configuration.server.online"
                        : "teleportblock.configuration.server.tooltip")))
                .build();
        server.active = false;
        this.addRenderableWidget(server);

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
                .bounds(x, this.height - 32, BUTTON_WIDTH, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
