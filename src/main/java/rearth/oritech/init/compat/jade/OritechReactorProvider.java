package rearth.oritech.init.compat.jade;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import rearth.oritech.Oritech;
import rearth.oritech.block.entity.reactor.ReactorControllerBlockEntity;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.IElementHelper;

/**
 * Jade panel for the fission reactor controller. It mirrors (and extends) the information the
 * reactor GUI shows, so a player can diagnose a running reactor just by looking at it:
 * <ul>
 *     <li>assembly state, size and stack height</li>
 *     <li>rod count, active rods and total fuel</li>
 *     <li>energy production and the energy buffer fill level</li>
 *     <li>heat production vs. heat removal, plus the resulting net heat</li>
 *     <li>hottest component: absolute heat, per-layer heat and the change in the last 5s</li>
 *     <li>a predicted stability verdict with ticks until meltdown</li>
 *     <li>meltdown countdown while the reactor is already unstable</li>
 *     <li>warning threshold, redstone state and safe-mode cooldown</li>
 * </ul>
 * All values are gathered server side on demand through {@link IServerDataProvider}, and the
 * heat trend is shipped as a ready-to-display delta so the client never needs the raw history.
 */
public enum OritechReactorProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {

    INSTANCE;

    private static final ResourceLocation ID = Oritech.id("reactor_status");

    private static final String DATA = "oritech_jade_reactor";

    private static final String ASSEMBLED = "assembled";
    private static final String HEIGHT = "height";
    private static final String SIZE_X = "size_x";
    private static final String SIZE_Z = "size_z";
    private static final String TOTAL_RODS = "total_rods";
    private static final String ACTIVE_RODS = "active_rods";
    private static final String FUEL = "fuel";
    private static final String FUEL_CAPACITY = "fuel_capacity";
    private static final String ENERGY_RATE = "energy_rate";
    private static final String ENERGY_STORED = "energy_stored";
    private static final String ENERGY_CAPACITY = "energy_capacity";
    private static final String HEAT_PRODUCED = "heat_produced";
    private static final String HEAT_REMOVED = "heat_removed";
    private static final String HOTTEST = "hottest";
    private static final String HOTTEST_LAYER = "hottest_layer";
    private static final String HOTTEST_CHANGE = "hottest_change";
    private static final String TREND = "trend";
    private static final String TICKS_TO_MELTDOWN = "ticks_to_meltdown";
    private static final String WARNING_THRESHOLD = "warning_threshold";
    private static final String UNSTABLE_TICKS = "unstable_ticks";
    private static final String MAX_UNSTABLE_TICKS = "max_unstable_ticks";
    private static final String REDSTONE_DISABLED = "redstone_disabled";
    private static final String COOLDOWN = "cooldown";
    private static final String MAX_HEAT = "max_heat";

    private static final int HEAT_TREND_WINDOW_TICKS = 100; // the 5s the tooltip reports on

    // ---- server side: gather ----

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {

        if (!(accessor.getBlockEntity() instanceof ReactorControllerBlockEntity reactor)) return;

        var out = new CompoundTag();
        out.putBoolean(ASSEMBLED, reactor.assembled && reactor.active);
        out.putInt(HEIGHT, Math.max(1, reactor.getStackHeight()));
        out.putInt(MAX_HEAT, ReactorControllerBlockEntity.MAX_HEAT);

        if (reactor.areaMin != null && reactor.areaMax != null) {
            // interior footprint, excluding the walls on both ends
            out.putInt(SIZE_X, Math.max(0, reactor.areaMax.getX() - reactor.areaMin.getX() - 1));
            out.putInt(SIZE_Z, Math.max(0, reactor.areaMax.getZ() - reactor.areaMin.getZ() - 1));
        }

        if (!out.getBoolean(ASSEMBLED)) {
            out.putBoolean(COOLDOWN, reactor.isCoolingDown());
            data.put(DATA, out);
            return;
        }

        out.putInt(TOTAL_RODS, reactor.totalRodCount);
        out.putInt(ACTIVE_RODS, reactor.activeRodCount);
        out.putInt(ENERGY_RATE, reactor.energyProduced);
        out.putLong(ENERGY_STORED, reactor.energyStorage.getAmount());
        out.putLong(ENERGY_CAPACITY, reactor.energyStorage.getCapacity());
        out.putInt(HEAT_PRODUCED, reactor.heatProduced);
        out.putInt(HEAT_REMOVED, reactor.heatRemoved);
        out.putInt(HOTTEST, reactor.hottestHeat);
        out.putInt(HOTTEST_LAYER, reactor.normalizedHottestHeat);
        out.putInt(HOTTEST_CHANGE, reactor.hottestHeatChange);
        out.putInt(TREND, (int) (reactor.heatTrendPerTick() * 100));
        out.putInt(TICKS_TO_MELTDOWN, reactor.ticksUntilMeltdown());
        out.putInt(WARNING_THRESHOLD, reactor.warningHeatThreshold);
        out.putInt(UNSTABLE_TICKS, reactor.getUnstableTicks());
        out.putInt(MAX_UNSTABLE_TICKS, reactor.getMaxUnstableTicks());
        out.putBoolean(REDSTONE_DISABLED, reactor.isRedstoneDisabled());
        out.putBoolean(COOLDOWN, reactor.isCoolingDown());

        // total fuel currently buffered in all fuel ports, summed over the whole stack.
        // the controller already keeps the port references, so this stays cheap on big reactors
        out.putInt(FUEL, reactor.getTotalFuel());
        out.putInt(FUEL_CAPACITY, reactor.getTotalFuelCapacity());
        out.putInt(HEIGHT, Math.max(1, reactor.getStackHeight()));

        data.put(DATA, out);
    }

    // ---- client side: render ----

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {

        var data = accessor.getServerData();
        if (!data.contains(DATA)) return;

        var d = data.getCompound(DATA);
        var helper = IElementHelper.get();
        var maxHeat = Math.max(1, d.getInt(MAX_HEAT));

        if (!d.getBoolean(ASSEMBLED)) {
            tooltip.add(Component.translatable("jade.oritech.reactor.not_assembled").withStyle(ChatFormatting.GRAY));
            if (d.getBoolean(COOLDOWN))
                tooltip.add(Component.translatable("jade.oritech.reactor.cooling_down").withStyle(ChatFormatting.GOLD));
            return;
        }

        // ---- structure ----
        tooltip.add(Component.translatable("jade.oritech.reactor.size",
          value(String.valueOf(d.getInt(SIZE_X))), value(String.valueOf(d.getInt(SIZE_Z))),
          value(String.valueOf(d.getInt(HEIGHT)))));

        // ---- rods and fuel ----
        tooltip.add(Component.translatable("jade.oritech.reactor.rods",
          value(String.valueOf(d.getInt(ACTIVE_RODS))), value(String.valueOf(d.getInt(TOTAL_RODS)))));

        var fuel = d.getInt(FUEL);
        var fuelCapacity = d.getInt(FUEL_CAPACITY);
        if (fuelCapacity > 0) {
            tooltip.add(Component.translatable("jade.oritech.reactor.fuel",
              value(String.valueOf(fuel)), value(String.valueOf(fuelCapacity)),
              value(percent(fuel, fuelCapacity))));
        } else {
            tooltip.add(Component.translatable("jade.oritech.reactor.no_fuel").withStyle(ChatFormatting.GRAY));
        }

        // ---- energy ----
        tooltip.add(Component.translatable("jade.oritech.reactor.energy",
          value(String.valueOf(d.getInt(ENERGY_RATE)))));
        long stored = d.getLong(ENERGY_STORED);
        long capacity = d.getLong(ENERGY_CAPACITY);
        if (capacity > 0) {
            tooltip.add(Component.translatable("jade.oritech.reactor.energy_buffer",
              value(String.valueOf(stored)), value(String.valueOf(capacity)),
              value(percent(stored, capacity))));
        }

        // ---- heat balance ----
        var produced = d.getInt(HEAT_PRODUCED);
        var removed = d.getInt(HEAT_REMOVED);
        var net = produced - removed;
        tooltip.add(Component.translatable("jade.oritech.reactor.heat_balance",
          value(String.valueOf(produced)), value(String.valueOf(removed)),
          Component.literal((net > 0 ? "+" : "") + net)
            .withStyle(net > 0 ? ChatFormatting.RED : ChatFormatting.GREEN)));

        // ---- hottest component ----
        var hottest = d.getInt(HOTTEST);
        var hottestLayer = d.getInt(HOTTEST_LAYER);
        var hottestChange = d.getInt(HOTTEST_CHANGE);
        tooltip.add(Component.translatable("jade.oritech.reactor.hottest",
          value(String.valueOf(hottest)), value(String.valueOf(hottestLayer)),
          value(percent(hottest, maxHeat))));

        var changeText = (hottestChange > 0 ? "+" : "") + hottestChange;
        tooltip.add(Component.translatable("jade.oritech.reactor.hottest_change",
          Component.literal(changeText).withStyle(hottestChange > 0 ? ChatFormatting.RED
            : hottestChange < 0 ? ChatFormatting.GREEN : ChatFormatting.GRAY)));

        // heat trend over the last 5 seconds, reported as a per-tick rate and a 5s delta
        var trend = d.getInt(TREND) / 100.0;
        var fiveSecondDelta = Math.round(trend * HEAT_TREND_WINDOW_TICKS);
        var trendText = (fiveSecondDelta > 0 ? "+" : "") + fiveSecondDelta;
        tooltip.add(Component.translatable("jade.oritech.reactor.trend",
          Component.literal(String.format(java.util.Locale.ROOT, "%+.2f", trend))
            .withStyle(trend > 0.01 ? ChatFormatting.RED : trend < -0.01 ? ChatFormatting.GREEN : ChatFormatting.GRAY),
          Component.literal(trendText).withStyle(trend > 0.01 ? ChatFormatting.RED
            : trend < -0.01 ? ChatFormatting.GREEN : ChatFormatting.GRAY)));

        // ---- verdict ----
        tooltip.add(verdict(d));

        // ---- meltdown countdown ----
        var unstableTicks = d.getInt(UNSTABLE_TICKS);
        if (unstableTicks > 0 && hottestLayer > maxHeat) {
            var maxUnstable = Math.max(1, d.getInt(MAX_UNSTABLE_TICKS));
            var remaining = Math.max(0, maxUnstable - unstableTicks);
            tooltip.add(Component.translatable("jade.oritech.reactor.meltdown_in",
              Component.literal(String.format(java.util.Locale.ROOT, "%.1fs", remaining / 20.0))
                .withStyle(ChatFormatting.DARK_RED)));
            tooltip.add(helper.progress(Math.min(1f, unstableTicks / (float) maxUnstable)));
        }

        // ---- thresholds / state ----
        tooltip.add(Component.translatable("jade.oritech.reactor.warning_threshold",
          value(String.valueOf(d.getInt(WARNING_THRESHOLD))),
          value(percent(d.getInt(WARNING_THRESHOLD), maxHeat))));

        if (d.getBoolean(REDSTONE_DISABLED))
            tooltip.add(Component.translatable("jade.oritech.reactor.redstone_disabled").withStyle(ChatFormatting.GOLD));

        if (d.getBoolean(COOLDOWN))
            tooltip.add(Component.translatable("jade.oritech.reactor.cooling_down").withStyle(ChatFormatting.RED));

        // a heat bar makes the margin to the warning threshold readable at a glance
        tooltip.add(helper.progress(Math.min(1f, hottest / (float) maxHeat)));
    }

    /**
     * Turns the current heat, the trend and the meltdown margin into a single readable verdict.
     * The thresholds mirror the ones the reactor GUI uses, extended with the predictive cases.
     */
    private static Component verdict(CompoundTag d) {
        var hottest = d.getInt(HOTTEST);
        var hottestLayer = d.getInt(HOTTEST_LAYER);
        var warning = d.getInt(WARNING_THRESHOLD);
        var maxHeat = Math.max(1, d.getInt(MAX_HEAT));
        var trend = d.getInt(TREND) / 100.0;
        var ticksToMeltdown = d.getInt(TICKS_TO_MELTDOWN);

        if (hottestLayer >= maxHeat) {
            return Component.translatable("jade.oritech.reactor.status.melting",
              Component.translatable("jade.oritech.reactor.status.melting.value").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
        }
        if (ticksToMeltdown >= 0 && ticksToMeltdown < 20 * 10) {
            return Component.translatable("jade.oritech.reactor.status.critical",
              Component.literal((ticksToMeltdown / 20) + "s").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
        }
        if (ticksToMeltdown >= 0) {
            return Component.translatable("jade.oritech.reactor.status.heating",
              Component.literal((ticksToMeltdown / 20) + "s").withStyle(ChatFormatting.RED));
        }
        if (hottest > warning) {
            return Component.translatable("jade.oritech.reactor.status.hot",
              Component.translatable("jade.oritech.reactor.status.hot.value").withStyle(ChatFormatting.GOLD));
        }
        if (hottest <= 0 && trend <= 0.01) {
            return Component.translatable("jade.oritech.reactor.status.idle").withStyle(ChatFormatting.GRAY);
        }
        return Component.translatable("jade.oritech.reactor.status.stable",
          Component.translatable("jade.oritech.reactor.status.stable.value").withStyle(ChatFormatting.GREEN));
    }

    private static Component value(String text) {
        return Component.literal(text).withStyle(ChatFormatting.YELLOW);
    }

    private static String percent(long value, long total) {
        if (total <= 0) return "0%";
        return Math.round(value * 100.0 / total) + "%";
    }

    @Override
    public ResourceLocation getUid() {
        return ID;
    }
}
