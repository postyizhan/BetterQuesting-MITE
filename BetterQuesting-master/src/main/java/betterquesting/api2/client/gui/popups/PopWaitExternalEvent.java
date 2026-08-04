package betterquesting.api2.client.gui.popups;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.lwjgl.util.vector.Vector4f;

import betterquesting.api2.client.gui.SceneController;
import betterquesting.api2.client.gui.misc.GuiAlign;
import betterquesting.api2.client.gui.misc.GuiPadding;
import betterquesting.api2.client.gui.misc.GuiTransform;
import betterquesting.api2.client.gui.panels.CanvasEmpty;
import betterquesting.api2.client.gui.panels.CanvasTextured;
import betterquesting.api2.client.gui.panels.content.PanelGeneric;
import betterquesting.api2.client.gui.panels.content.PanelTextBox;
import betterquesting.api2.client.gui.resources.colors.GuiColorStatic;
import betterquesting.api2.client.gui.resources.textures.ColorTexture;
import betterquesting.api2.client.gui.resources.textures.IGuiTexture;
import betterquesting.api2.client.gui.themes.presets.PresetTexture;
import betterquesting.core.BetterQuesting;

public class PopWaitExternalEvent<T> extends CanvasEmpty {

    private String message;
    private final IGuiTexture icon;
    private final CompletableFuture<T> future;
    private PanelTextBox label;

    public PopWaitExternalEvent(@Nonnull String message) {
        this(message, null);
    }

    public PopWaitExternalEvent(@Nonnull String message, @Nullable IGuiTexture icon) {
        super(new GuiTransform(GuiAlign.FULL_BOX));
        this.message = message;
        this.icon = icon;
        this.future = new CompletableFuture<>();
    }

    @Override
    public void initPanel() {
        super.initPanel();

        this.addPanel(
            new PanelGeneric(
                new GuiTransform(GuiAlign.FULL_BOX, new GuiPadding(0, 0, 0, 0), 1),
                new ColorTexture(new GuiColorStatic(0x80000000))));

        CanvasTextured cvBox = new CanvasTextured(
            new GuiTransform(new Vector4f(0.2F, 0.3F, 0.8F, 0.6F)),
            PresetTexture.PANEL_MAIN.getTexture());
        this.addPanel(cvBox);

        if (icon != null) {
            CanvasTextured icoFrame = new CanvasTextured(
                new GuiTransform(new Vector4f(0.5F, 0.3F, 0.5F, 0.3F), -16, -40, 32, 32, 0),
                PresetTexture.PANEL_MAIN.getTexture());
            this.addPanel(icoFrame);

            icoFrame
                .addPanel(new PanelGeneric(new GuiTransform(GuiAlign.FULL_BOX, new GuiPadding(8, 8, 8, 8), 0), icon));
        }

        label = new PanelTextBox(new GuiTransform(GuiAlign.FULL_BOX, new GuiPadding(8, 8, 8, 8), 0), message)
            .setAlignment(1);
        cvBox.addPanel(label);
    }

    @Override
    public void drawPanel(int mx, int my, float partialTick) {
        super.drawPanel(mx, my, partialTick);
        // there isn't an otherwise good place to poll for this..
        if (future.isDone()) {
            if (SceneController.getActiveScene() != null) SceneController.getActiveScene()
                .closePopup();
            handleComplete();
        }
    }

    protected void handleComplete() {

        if (future.isCancelled()) {
            onCancel();
            return;
        }
        T v;
        try {
            v = future.get();
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        } catch (ExecutionException e) {
            onError(e);
            return;
        }
        onComplete(v);
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
        label.setText(message);
    }

    public void complete(T val) {
        future.complete(val);
    }

    public void fail(Throwable ex) {
        future.completeExceptionally(ex);
    }

    public void cancel() {
        future.cancel(true);
    }

    public void ensureDone() {
        if (!future.isDone()) {
            future.cancel(true);
        }
    }

    // impl note: these are callbacks for subclass to override
    // it's worth mentioning that completable future and its friends does not allow stuff in pipeline to be
    // suspended, then resumed later, or on another thread. This makes callbacks like this mandatory
    protected void onComplete(T future) {}

    protected void onCancel() {}

    protected void onError(ExecutionException e) {
        BetterQuesting.logger.error("External Event Error", e);
    }

    // == TRAP ALL UI USAGE UNTIL CLOSED ===

    @Override
    public boolean onMouseClick(int mx, int my, int click) {
        super.onMouseClick(mx, my, click);

        return true;
    }

    @Override
    public boolean onMouseRelease(int mx, int my, int click) {
        super.onMouseRelease(mx, my, click);

        return true;
    }

    @Override
    public boolean onMouseScroll(int mx, int my, int scroll) {
        super.onMouseScroll(mx, my, scroll);

        return true;
    }

    @Override
    public boolean onKeyTyped(char c, int keycode) {
        super.onKeyTyped(c, keycode);

        return true;
    }
}
