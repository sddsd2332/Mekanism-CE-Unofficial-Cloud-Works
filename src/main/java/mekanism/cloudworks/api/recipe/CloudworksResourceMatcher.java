package mekanism.cloudworks.api.recipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import mekanism.api.qio.resource.QIOResourceCodecs;
import net.minecraftforge.oredict.OreDictionary;

/** Data-only resource predicate. It never invokes addon code during Runtime ticks. */
public final class CloudworksResourceMatcher {
    private final List<QIOResourceDescriptor> alternatives;
    private final ResourceLocation codec;
    private final ItemRule itemRule;
    private final List<ItemRule> itemRules;
    private final boolean exclusion;
    private final boolean itemList;
    private final QIOResourceDescriptor representative;

    private CloudworksResourceMatcher(List<QIOResourceDescriptor> alternatives, ResourceLocation codec,
            ItemRule itemRule, List<ItemRule> itemRules, boolean exclusion) {
        this(alternatives, codec, itemRule, itemRules, exclusion, null);
    }

    private CloudworksResourceMatcher(List<QIOResourceDescriptor> alternatives, ResourceLocation codec,
            ItemRule itemRule, List<ItemRule> itemRules, boolean exclusion, QIOResourceDescriptor representative) {
        this.representative = representative;
        this.alternatives = Collections.unmodifiableList(new ArrayList<>(alternatives));
        this.codec = codec;
        this.itemRule = itemRule;
        this.itemRules = Collections.unmodifiableList(new ArrayList<>(itemRules));
        this.exclusion = exclusion;
        this.itemList = itemRule == null && alternatives.isEmpty()
                && (exclusion || !itemRules.isEmpty());
    }

    public static CloudworksResourceMatcher exact(QIOResourceDescriptor descriptor) {
        return alternatives(Collections.singletonList(descriptor));
    }

    public static CloudworksResourceMatcher alternatives(List<QIOResourceDescriptor> descriptors) {
        Objects.requireNonNull(descriptors);
        if (descriptors.isEmpty()) throw new IllegalArgumentException("Empty resource matcher");
        ResourceLocation codec = Objects.requireNonNull(descriptors.get(0)).getCodecId();
        List<QIOResourceDescriptor> copy = new ArrayList<>(descriptors.size());
        for (QIOResourceDescriptor descriptor : descriptors) {
            if (descriptor == null || !descriptor.isResolved() || !codec.equals(descriptor.getCodecId()))
                throw new IllegalArgumentException("Invalid resource alternatives");
            copy.add(descriptor);
        }
        return new CloudworksResourceMatcher(copy, codec, null, Collections.emptyList(), false);
    }

    public static CloudworksResourceMatcher codec(ResourceLocation codec) {
        return new CloudworksResourceMatcher(Collections.emptyList(), Objects.requireNonNull(codec),
                null, Collections.emptyList(), false);
    }

    /** Data-only item matcher retaining wildcard damage and optional NBT semantics. */
    public static CloudworksResourceMatcher item(QIOResourceDescriptor descriptor) {
        Objects.requireNonNull(descriptor);
        if (!descriptor.isResolved() || !QIOResourceCodecs.ITEM_STACK_ID
                .equals(descriptor.getCodecId()))
            throw new IllegalArgumentException("Expected an item descriptor");
        NBTTagCompound payload = descriptor.getPayload();
        return new CloudworksResourceMatcher(Collections.emptyList(),
                QIOResourceCodecs.ITEM_STACK_ID,
                ItemRule.read(payload), Collections.emptyList(), false, descriptor);
    }

    public static CloudworksResourceMatcher itemAllowlist(List<QIOResourceDescriptor> descriptors) {
        if (descriptors == null || descriptors.isEmpty())
            throw new IllegalArgumentException("Empty item allowlist");
        List<ItemRule> rules = new ArrayList<>();
        if (descriptors != null) for (QIOResourceDescriptor descriptor : descriptors)
            rules.add(item(descriptor).itemRule);
        return new CloudworksResourceMatcher(Collections.emptyList(),
                QIOResourceCodecs.ITEM_STACK_ID,
                null, rules, false);
    }

    public static CloudworksResourceMatcher itemExclusionList(List<QIOResourceDescriptor> descriptors) {
        List<ItemRule> rules = new ArrayList<>();
        if (descriptors != null) for (QIOResourceDescriptor descriptor : descriptors)
            rules.add(item(descriptor).itemRule);
        return new CloudworksResourceMatcher(Collections.emptyList(),
                QIOResourceCodecs.ITEM_STACK_ID,
                null, rules, true);
    }

    public boolean matches(QIOResourceDescriptor candidate) {
        if (candidate == null || !codec.equals(candidate.getCodecId())) return false;
        if (itemRule != null) return itemRule.matches(candidate.getPayload());
        if (!itemRules.isEmpty()) {
            boolean matched = false;
            for (ItemRule rule : itemRules) if (rule.matches(candidate.getPayload())) {
                matched = true;
                break;
            }
            return exclusion ? !matched : matched;
        }
        if (alternatives.isEmpty()) return true;
        for (QIOResourceDescriptor alternative : alternatives)
            if (alternative.equals(candidate)) return true;
        return false;
    }

    public ResourceLocation codec() { return codec; }
    public List<QIOResourceDescriptor> alternatives() { return alternatives; }

    /** One concrete data-only resource, or null for wildcard/codec/list predicates. */
    public QIOResourceDescriptor singleResource() {
        if (alternatives.size() == 1) return alternatives.get(0);
        return representative != null && itemRule != null && itemRule.damage != OreDictionary.WILDCARD_VALUE
                ? representative : null;
    }

    private static final class ItemRule {
        private final String id;
        private final int damage;
        private final boolean hasTag;
        private final NBTTagCompound tag;

        private ItemRule(String id, int damage, boolean hasTag, NBTTagCompound tag) {
            this.id = id;
            this.damage = damage;
            this.hasTag = hasTag;
            this.tag = tag == null ? null : tag.copy();
        }

        static ItemRule read(NBTTagCompound payload) {
            boolean hasTag = payload.hasKey("tag", 10);
            return new ItemRule(payload.getString("id"), payload.getShort("Damage"), hasTag,
                    hasTag ? payload.getCompoundTag("tag") : null);
        }

        boolean matches(NBTTagCompound payload) {
            int actualDamage = payload.getShort("Damage");
            return id.equals(payload.getString("id"))
                    && (damage == OreDictionary.WILDCARD_VALUE
                    || actualDamage == OreDictionary.WILDCARD_VALUE
                    || damage == actualDamage)
                    && hasTag == payload.hasKey("tag", 10)
                    && (!hasTag || tag.equals(payload.getCompoundTag("tag")));
        }
    }
}
