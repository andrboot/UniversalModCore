package cam72cam.mod.gui.helpers;

import cam72cam.mod.item.ItemStack;
import cam72cam.mod.render.OpenGL;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.widget.button.AbstractButton;
import net.minecraft.client.renderer.RenderHelper;
import org.lwjgl.opengl.GL11;

/** Internal item button class */
public abstract class ItemButton extends AbstractButton {
    public ItemStack stack;

    public ItemButton(ItemStack stack, int x, int y) {
        super(x, y, 32, 32, "");
        this.stack = stack;
    }

    @Override
    public void render(int mouseX, int mouseY, float partialTicks) {
        AbstractGui.fill(x, y, x + 32, y + 32, 0xFFFFFFFF);
        // Pollutes global state...
        RenderHelper.enableStandardItemLighting();
        Minecraft mc = Minecraft.getInstance();

        FontRenderer font = stack.internal.getItem().getFontRenderer(stack.internal);
        try (
                OpenGL.With matrix = OpenGL.matrix();
        ) {
            GL11.glTranslated(x, y, 0);
            GL11.glScaled(2, 2, 1);
            mc.getItemRenderer().renderItemAndEffectIntoGUI(stack.internal, 0, 0);
            mc.getItemRenderer().renderItemOverlays(font, stack.internal, 0, 0);
        }

        // Pollutes global state...
        RenderHelper.disableStandardItemLighting();
    }

    public boolean isMouseOver(int mouseX, int mouseY) {
        return mouseX >= this.x && mouseX < this.x + 32 && mouseY >= this.y && mouseY < this.y + 32;
    }
}
