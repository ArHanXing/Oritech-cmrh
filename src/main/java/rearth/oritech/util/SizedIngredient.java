package rearth.oritech.util;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

// this is very much inspired by TR: https://github.com/TechReborn/TechReborn/blob/1.21/RebornCore/src/main/java/reborncore/common/crafting/SizedIngredient.java#L50
public record SizedIngredient(int count, Ingredient ingredient) implements Predicate<ItemStack> {
    
    /**
     * Nested form, used by the augment research costs: {@code {"count": 2, "ingredient": {...}}}.
     */
    public static MapCodec<SizedIngredient> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
      Codec.INT.optionalFieldOf("count", 1).forGetter(SizedIngredient::count),
      Ingredient.CODEC_NONEMPTY.fieldOf("ingredient").forGetter(SizedIngredient::ingredient)
    ).apply(instance, SizedIngredient::new));
    
    /**
     * Flat form used by machine recipes: {@code {"item": "...", "count": 2}}. The count is optional
     * and is only written when it is above 1, so pre-existing recipes keep their exact shape.
     * <p>
     * {@link Ingredient#CODEC_NONEMPTY} ignores unknown keys, which is what silently dropped a
     * {@code count} before; here the same map is read for the count first and then handed to the
     * ingredient codec.
     */
    public static Codec<SizedIngredient> FLAT_CODEC = new Codec<>() {
        @Override
        public <T> DataResult<Pair<SizedIngredient, T>> decode(DynamicOps<T> ops, T input) {
            return ops.getMap(input).flatMap(map -> {
                var count = 1;
                var rawCount = map.get("count");
                if (rawCount != null) {
                    var parsedCount = ops.getNumberValue(rawCount).result();
                    if (parsedCount.isEmpty()) return DataResult.error(() -> "Invalid ingredient count");
                    count = Math.max(1, parsedCount.get().intValue());
                }
                
                final var resolvedCount = count;
                return Ingredient.CODEC_NONEMPTY.parse(ops, input)
                         .map(ingredient -> Pair.of(new SizedIngredient(resolvedCount, ingredient), ops.empty()));
            });
        }
        
        @Override
        public <T> DataResult<T> encode(SizedIngredient input, DynamicOps<T> ops, T prefix) {
            var encodedIngredient = Ingredient.CODEC_NONEMPTY.encodeStart(ops, input.ingredient());
            if (encodedIngredient.error().isPresent()) return encodedIngredient;
            
            var ingredientMap = ops.getMap(encodedIngredient.result().get());
            if (ingredientMap.error().isPresent())
                return DataResult.error(() -> "Ingredient did not encode to a map");
            
            var builder = ops.mapBuilder();
            ingredientMap.result().get().entries().forEach(entry -> builder.add(entry.getFirst(), entry.getSecond()));
            if (input.count() > 1) builder.add(ops.createString("count"), ops.createInt(input.count()));
            return builder.build(prefix);
        }
    };
    
    public static StreamCodec<RegistryFriendlyByteBuf, SizedIngredient> PACKET_CODEC = StreamCodec.composite(
      ByteBufCodecs.INT, SizedIngredient::count,
      Ingredient.CONTENTS_STREAM_CODEC, SizedIngredient::ingredient,
      SizedIngredient::new
    );
    
    /**
     * Takes {@code count} matching items out of the given stacks, possibly spread over several slots.
     * With {@code simulate} the stacks are left untouched and the result only reports whether the
     * full amount is available.
     */
    public boolean consume(List<ItemStack> stacks, boolean simulate) {
        var remaining = count;
        
        for (var stack : stacks) {
            if (stack.isEmpty() || !ingredient.test(stack)) continue;
            
            var taken = Math.min(remaining, stack.getCount());
            if (!simulate) stack.shrink(taken);
            remaining -= taken;
            if (remaining <= 0) return true;
        }
        
        return false;
    }
    
    @Override
    public boolean test(ItemStack itemStack) {
        if (itemStack.isEmpty()) {
            // Never match empty stacks.
            return false;
        }
        
        if (itemStack.getCount() < count) {
            return false;
        }
        
        return ingredient.test(itemStack);
    }
}
