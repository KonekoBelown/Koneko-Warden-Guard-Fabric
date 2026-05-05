package com.koneko.wardenguard.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

public final class WardenGuardConfigScreen extends Screen {
    private final Screen parent;
    private ButtonWidget autoSummonButton;

    public WardenGuardConfigScreen(Screen parent) {
        super(Text.translatable("config.koneko_warden_guard.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        this.autoSummonButton = ButtonWidget.builder(autoSummonText(), button -> {
                    WardenGuardConfig.setAutoSummonWhenAttacked(!WardenGuardConfig.autoSummonWhenAttacked());
                    button.setMessage(autoSummonText());
                })
                .dimensions(centerX - 120, this.height / 2 - 12, 240, 20)
                .build();
        this.addDrawableChild(this.autoSummonButton);

        this.addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> this.close())
                .dimensions(centerX - 100, this.height / 2 + 24, 200, 20)
                .build());
    }

    private Text autoSummonText() {
        return Text.translatable(WardenGuardConfig.autoSummonWhenAttacked()
                ? "config.koneko_warden_guard.auto_summon.on"
                : "config.koneko_warden_guard.auto_summon.off");
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 36, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("config.koneko_warden_guard.help"),
                this.width / 2,
                this.height / 2 - 42,
                0xA0A0A0);
    }
}
