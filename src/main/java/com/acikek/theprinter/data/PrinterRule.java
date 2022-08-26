package com.acikek.theprinter.data;

import com.acikek.theprinter.ThePrinter;
import com.google.gson.JsonObject;
import com.udojava.evalex.Expression;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ToolItem;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.recipe.Ingredient;
import net.minecraft.util.Identifier;
import net.minecraft.util.JsonHelper;
import net.minecraft.util.Rarity;

import java.math.BigDecimal;
import java.util.*;

public class PrinterRule {

	public enum Type {

		OVERRIDE(true),
		MODIFIER(false),
		SIZE(true),
		ENABLED(true);

		public final boolean exclusive;

		Type(boolean exclusive) {
			this.exclusive = exclusive;
		}
	}

	public static Map<Identifier, PrinterRule> RULES = new HashMap<>();

	public static final int BASE_ITEM_COST = 55;
	public static final int BASE_BLOCK_COST = 91;

	public Ingredient input;
	public Optional<Integer> override;
	public Optional<String> modifier;
	public Optional<Integer> size;
	public Optional<Boolean> enabled;
	public Expression expression;
	public List<Type> types = new ArrayList<>();

	public PrinterRule(Ingredient input, Optional<Integer> override, Optional<String> modifier, Optional<Integer> size, Optional<Boolean> enabled) {
		this.input = input;
		this.override = override;
		this.modifier = modifier;
		this.size = size;
		this.enabled = enabled;
		modifier.ifPresent(s -> expression = new Expression(s));
		if (override.isPresent() || modifier.isPresent()) {
			types.add(override.isPresent() ? Type.OVERRIDE : Type.MODIFIER);
		}
		if (size.isPresent()) {
			types.add(Type.SIZE);
		}
		if (enabled.isPresent()) {
			types.add(Type.ENABLED);
		}
	}

	public boolean validate() {
		if (types.isEmpty()) {
			throw new IllegalStateException("rule must contain properties other than 'input'");
		}
		if (override.isPresent() && modifier.isPresent()) {
			throw new IllegalStateException("'modifier' rule is mutually exclusive with 'override'");
		}
		return true;
	}

	public static List<Map.Entry<Identifier, PrinterRule>> getMatchingRules(ItemStack stack) {
		if (RULES.isEmpty()) {
			return Collections.emptyList();
		}
		return RULES.entrySet().stream()
				.filter(pair -> pair.getValue().input.test(stack))
				.toList();
	}

	public static List<Map.Entry<Identifier, PrinterRule>> filterByType(List<Map.Entry<Identifier, PrinterRule>> rules, Type type, Object sourceObject) {
		var result = rules.stream()
				.filter(rule -> rule.getValue().types.contains(type))
				.toList();
		if (type.exclusive && result.size() > 1) {
			List<String> ids = result.stream()
					.map(Map.Entry::getKey)
					.map(Identifier::toString)
					.toList();
			String joined = String.join(", ", ids);
			ThePrinter.LOGGER.warn("Multiple " + type + " rules for object '" + sourceObject + "': " + joined);
		}
		return result;
	}

	public static int getRarityXPMultiplier(Rarity rarity) {
		return switch (rarity) {
			case COMMON -> 1;
			case UNCOMMON -> 2;
			case RARE -> 4;
			case EPIC -> 6;
		};
	}

	public static int getNbtXPCost(ItemStack stack) {
		if (!stack.hasNbt()) {
			return 0;
		}
		NbtCompound nbt = stack.getOrCreateNbt();
		return nbt.getKeys().size() * 20;
	}

	public static int getBaseXP(ItemStack stack) {
		int baseCost = stack.getItem() instanceof BlockItem ? BASE_BLOCK_COST : BASE_ITEM_COST;
		int materialMultiplier = stack.getItem() instanceof ToolItem ? 3 : 1;
		int rarityMultiplier = getRarityXPMultiplier(stack.getRarity());
		int nbtCost = getNbtXPCost(stack);
		return (baseCost * materialMultiplier * rarityMultiplier) + nbtCost;
	}

	public static int getRequiredXP(List<Map.Entry<Identifier, PrinterRule>> rules, ItemStack stack) {
		var overrides = PrinterRule.filterByType(rules, PrinterRule.Type.OVERRIDE, stack);
		if (!overrides.isEmpty()) {
			return overrides.get(0).getValue().override.orElse(0);
		}
		int baseXP = getBaseXP(stack);
		var modifiers = PrinterRule.filterByType(rules, PrinterRule.Type.MODIFIER, null);
		if (!modifiers.isEmpty()) {
			double result = baseXP;
			List<Expression> expressions = modifiers.stream()
					.map(rule -> rule.getValue().expression)
					.filter(Objects::nonNull)
					.toList();
			for (Expression expression : expressions) {
				result = expression
						.with("value", BigDecimal.valueOf(result))
						.with("size", BigDecimal.valueOf(stack.getCount()))
						.eval().doubleValue();
			}
			return (int) result;
		}
		return baseXP;
	}

	public static List<Map.Entry<Identifier, PrinterRule>> readNbt(NbtCompound nbt) {
		if (!nbt.contains("Rules")) {
			return null;
		}
		NbtList ids = nbt.getList("Rules", NbtList.STRING_TYPE);
		List<Map.Entry<Identifier, PrinterRule>> list = new ArrayList<>();
		for (NbtElement element : ids) {
			Identifier id = Identifier.tryParse(element.asString());
			if (!RULES.containsKey(id)) {
				ThePrinter.LOGGER.error("Unknown rule '" + id + "'");
				continue;
			}
			list.add(new AbstractMap.SimpleImmutableEntry<>(id, RULES.get(id)));
		}
		return list;
	}

	public static void writeNbt(NbtCompound nbt, List<Map.Entry<Identifier, PrinterRule>> rules) {
		List<NbtString> ids = rules.stream()
				.map(Map.Entry::getKey)
				.map(Identifier::toString)
				.map(NbtString::of)
				.toList();
		NbtList list = new NbtList();
		list.addAll(ids);
		nbt.put("Rules", list);
	}

	public static PrinterRule fromJson(JsonObject obj) {
		Ingredient input = Ingredient.fromJson(obj.get("input"));
		int override = JsonHelper.getInt(obj, "override", -1);
		String modifier = JsonHelper.getString(obj, "modifier", null);
		int size = JsonHelper.getInt(obj, "size", -1);
		Boolean enabled = JsonHelper.hasBoolean(obj, "enabled") ? JsonHelper.getBoolean(obj, "enabled") : null;
		return new PrinterRule(
				input,
				override == -1 ? Optional.empty() : Optional.of(override),
				Optional.ofNullable(modifier),
				size == -1 ? Optional.empty() : Optional.of(size),
				Optional.ofNullable(enabled)
		);
	}

	public static PrinterRule read(PacketByteBuf buf) {
		Ingredient input = Ingredient.fromPacket(buf);
		Optional<Integer> override = buf.readOptional(PacketByteBuf::readInt);
		Optional<String> modifier = buf.readOptional(PacketByteBuf::readString);
		Optional<Integer> size = buf.readOptional(PacketByteBuf::readInt);
		Optional<Boolean> enabled = buf.readOptional(PacketByteBuf::readBoolean);
		return new PrinterRule(input, override, modifier, size, enabled);
	}

	public void write(PacketByteBuf buf) {
		input.write(buf);
		buf.writeOptional(override, PacketByteBuf::writeInt);
		buf.writeOptional(modifier, PacketByteBuf::writeString);
		buf.writeOptional(size, PacketByteBuf::writeInt);
		buf.writeOptional(enabled, PacketByteBuf::writeBoolean);
	}
}
