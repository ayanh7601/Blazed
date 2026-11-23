package com.ayan7601.blazed.modules.main;

import com.ayan7601.blazed.BlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.stream.Collectors;

public class AutoSell extends Module {
    public enum SellMode {
        SellAll,
        SellGui
    }

    public enum MoveMethod {
        QuickMove,
        Pickup
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgGui = settings.createGroup("SellGui");

    // ---- General ----

    private final Setting<SellMode> mode = sgGeneral.add(new EnumSetting.Builder<SellMode>()
            .name("mode")
            .description("SellAll sends /sellall with item names. SellGui opens the sell GUI, moves items in and closes it.")
            .defaultValue(SellMode.SellAll)
            .build());

    private final Setting<List<Item>> itemsToSell = sgGeneral.add(new ItemListSetting.Builder()
            .name("items-to-sell")
            .description("Items that will be automatically sold.")
            .defaultValue(Items.COBBLESTONE, Items.DIRT)
            .build());

    private final Setting<Integer> sellDelay = sgGeneral.add(new IntSetting.Builder()
            .name("sell-delay")
            .description("Delay in seconds between each sell cycle.")
            .defaultValue(60)
            .min(1)
            .sliderMax(300)
            .build());

    private final Setting<Boolean> sellOnActivate = sgGeneral.add(new BoolSetting.Builder()
            .name("sell-on-activate")
            .description("Starts a sell cycle immediately when the module is enabled, instead of waiting for the first delay.")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> notifyOnSell = sgGeneral.add(new BoolSetting.Builder()
            .name("notify-on-sell")
            .description("Shows a chat message in the client whenever a sell cycle happens.")
            .defaultValue(true)
            .build());

    // ---- SellGui only ----

    private final Setting<String> guiCommand = sgGui.add(new StringSetting.Builder()
            .name("gui-command")
            .description("Command (without the slash) that opens the sell GUI.")
            .defaultValue("sellgui")
            .visible(() -> mode.get() == SellMode.SellGui)
            .build());

    private final Setting<MoveMethod> moveMethod = sgGui.add(new EnumSetting.Builder<MoveMethod>()
            .name("move-method")
            .description("QuickMove shift-clicks items in. Pickup picks each stack up and places it in an empty GUI slot (use this if shift-click is blocked).")
            .defaultValue(MoveMethod.QuickMove)
            .visible(() -> mode.get() == SellMode.SellGui)
            .build());

    private final Setting<Boolean> debug = sgGui.add(new BoolSetting.Builder()
            .name("debug")
            .description("Prints each step of the sell GUI cycle in chat to help find where it fails.")
            .defaultValue(true)
            .visible(() -> mode.get() == SellMode.SellGui)
            .build());

    private final Setting<Integer> openDelay = sgGui.add(new IntSetting.Builder()
            .name("open-delay")
            .description("Ticks to wait after the GUI opens before moving items.")
            .defaultValue(4)
            .min(0)
            .sliderMax(20)
            .visible(() -> mode.get() == SellMode.SellGui)
            .build());

    private final Setting<Integer> clicksPerTick = sgGui.add(new IntSetting.Builder()
            .name("clicks-per-tick")
            .description("How many stacks to move into the GUI each tick. Lower is safer on strict anticheats.")
            .defaultValue(2)
            .min(1)
            .sliderMax(10)
            .visible(() -> mode.get() == SellMode.SellGui)
            .build());

    private final Setting<Integer> closeDelay = sgGui.add(new IntSetting.Builder()
            .name("close-delay")
            .description("Ticks to wait after the last item is moved before closing the GUI.")
            .defaultValue(4)
            .min(0)
            .sliderMax(20)
            .visible(() -> mode.get() == SellMode.SellGui)
            .build());

    private enum State { IDLE, WAIT_GUI, MOVING, CLOSING }

    private static final int GUI_TIMEOUT_TICKS = 40;
    private static final int MOVE_TIMEOUT_TICKS = 100;

    private State state = State.IDLE;
    private int timer;
    private int waitTicks;
    private int cooldown;
    private int movedStacks;

    public AutoSell() {
        super(BlazedAddon.CATEGORY, "auto-sell", "Automatically sells configured items using /sellall or the /sellgui menu.");
    }

    @Override
    public void onActivate() {
        state = State.IDLE;
        timer = sellOnActivate.get() ? 0 : secondsToTicks(sellDelay.get());
    }

    @Override
    public void onDeactivate() {
        state = State.IDLE; 
        timer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            state = State.IDLE;
            return;
        }

        switch (state) {
            case IDLE -> tickIdle();
            case WAIT_GUI -> tickWaitGui();
            case MOVING -> tickMoving();
            case CLOSING -> tickClosing();
        }
    }

    // ---- Shared ----

    private void tickIdle() {
        if (itemsToSell.get().isEmpty()) return;

        if (timer > 0) {
            timer--;
            return;
        }

        if (mode.get() == SellMode.SellAll) {
            sendSellAll();
            timer = secondsToTicks(sellDelay.get());
            return;
        }

        // SellGui: if a non-container screen is open (e.g. the Meteor click GUI you toggled the module from,
        // or chat), wait for it to close instead of skipping. The timer stays at 0 so it fires as soon as you close it.
        if (mc.gui.screen() != null && !isGuiOpen()) return;

        // Don't open the GUI if there's nothing to sell.
        if (!hasSellableItems()) {
            debug("No items from your list in inventory, skipping.");
            timer = secondsToTicks(sellDelay.get());
            return;
        }

        if (isGuiOpen()) {
            debug("Another container is already open, skipping.");
            timer = secondsToTicks(sellDelay.get());
            return;
        }

        debug("Sending /" + guiCommand.get());
        mc.getConnection().sendCommand(guiCommand.get());
        state = State.WAIT_GUI;
        waitTicks = 0;
    }

    private void finishCycle() {
        state = State.IDLE;
        timer = secondsToTicks(sellDelay.get());
    }

    // ---- SellAll mode ----

    private void sendSellAll() {
        String itemNames = itemsToSell.get().stream()
            .map(item -> BuiltInRegistries.ITEM.getKey(item).getPath())
            .collect(Collectors.joining(" "));

        if (itemNames.isEmpty()) return;

        mc.getConnection().sendCommand("sellall " + itemNames);

        if (notifyOnSell.get()) {
            ChatUtils.info("Selling: " + itemNames);
        }
    }

    // ---- SellGui mode ----

    private void tickWaitGui() {
        if (isGuiOpen()) {
            state = State.MOVING;
            debug("GUI opened (" + (mc.player.containerMenu.slots.size() - 36) + " GUI slots).");
            cooldown = openDelay.get();
            waitTicks = 0;
            movedStacks = 0;
            return;
        }

        if (++waitTicks > GUI_TIMEOUT_TICKS) {
            debug("Timed out waiting for the GUI.");
            if (notifyOnSell.get()) warning("Sell GUI did not open. Is /" + guiCommand.get() + " correct for this server?");
            finishCycle();
        }
    }

    private void tickMoving() {
        if (!isGuiOpen()) {
            finishCycle();
            return;
        }

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        AbstractContainerMenu menu = mc.player.containerMenu;
        int guiSize = menu.slots.size() - 36; // last 36 slots are always the player's inventory

        if (guiSize <= 0) {
            mc.player.closeContainer();
            finishCycle();
            return;
        }

        int moved = 0;
        boolean full = false;
        for (int i = guiSize; i < menu.slots.size() && moved < clicksPerTick.get(); i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            if (stack.isEmpty() || !itemsToSell.get().contains(stack.getItem())) continue;

            if (moveMethod.get() == MoveMethod.QuickMove) {
                click(menu, i, ContainerInput.QUICK_MOVE);
            } else {
                int target = findEmptyGuiSlot(menu, guiSize);
                if (target == -1) { full = true; break; }

                click(menu, i, ContainerInput.PICKUP);
                click(menu, target, ContainerInput.PICKUP);

                if (!menu.getCarried().isEmpty()) { // slot rejected the item, put it back
                    click(menu, i, ContainerInput.PICKUP);
                    full = true;
                    break;
                }
            }

            moved++;
            movedStacks++;
        }

        waitTicks++;

        // Nothing left to move, or the GUI is full / clicks are being rejected.
        if (moved == 0 || full || waitTicks > MOVE_TIMEOUT_TICKS) {
            debug("Moved " + movedStacks + " stack(s). Closing" + (full ? " (GUI full / slot rejected)." : "."));
            state = State.CLOSING;
            cooldown = closeDelay.get();
        }
    }

    private void click(AbstractContainerMenu menu, int slot, ContainerInput type) {
        mc.gameMode.handleContainerInput(menu.containerId, slot, 0, type, mc.player);
    }

    private int findEmptyGuiSlot(AbstractContainerMenu menu, int guiSize) {
        for (int i = 0; i < guiSize; i++) {
            if (menu.slots.get(i).getItem().isEmpty()) return i;
        }
        return -1;
    }

    private void debug(String msg) {
        if (mode.get() == SellMode.SellGui && debug.get()) ChatUtils.info("[AutoSell] " + msg);
    }

    private void tickClosing() {
        if (cooldown > 0) {
            cooldown--;
            return;
        }

        if (isGuiOpen()) mc.player.closeContainer(); // server sells on close

        if (notifyOnSell.get()) ChatUtils.info("Sold " + movedStacks + " stack(s) via /" + guiCommand.get() + ".");
        finishCycle();
    }

    private boolean isGuiOpen() {
        return mc.gui.screen() instanceof AbstractContainerScreen<?>
            && mc.player.containerMenu != mc.player.inventoryMenu;
    }

    private boolean hasSellableItems() {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && itemsToSell.get().contains(stack.getItem())) return true;
        }
        return false;
    }

    private int secondsToTicks(int seconds) {
        return seconds * 20;
    }
}