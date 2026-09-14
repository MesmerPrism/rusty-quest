package io.github.mesmerprism.rustyquest.media;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exact providers packaged by one Android product. */
public final class MediaProductBinding {
    private final String productId;
    private final Map<String, MediaOwnerProvider> providers;

    private MediaProductBinding(String productId, Map<String, MediaOwnerProvider> providers) {
        this.productId = productId;
        this.providers = Collections.unmodifiableMap(new LinkedHashMap<>(providers));
    }
    public String productId() { return productId; }
    MediaOwnerProvider provider(MediaOwnerAction action) { return providers.get(action.bindingKey()); }
    public int providerCount() { return providers.size(); }

    static String key(String ownerKind, String ownerId, String providerKind, String resourceId) {
        return ownerKind + "\u0000" + ownerId + "\u0000" + providerKind + "\u0000" + resourceId;
    }

    public static final class Builder {
        private final String productId;
        private final Map<String, MediaOwnerProvider> providers = new LinkedHashMap<>();
        public Builder(String productId) {
            if (productId == null || productId.isEmpty()) throw new IllegalArgumentException("productId");
            this.productId = productId;
        }
        public Builder bind(String ownerKind, String ownerId, String providerKind,
                String resourceId, MediaOwnerProvider provider) {
            if (provider == null) throw new NullPointerException("provider");
            String key = key(ownerKind, ownerId, providerKind, resourceId);
            if (providers.put(key, provider) != null) {
                throw new IllegalArgumentException("duplicate media provider binding");
            }
            return this;
        }
        public MediaProductBinding build() {
            if (providers.isEmpty()) throw new IllegalStateException("product has no media providers");
            return new MediaProductBinding(productId, providers);
        }
    }
}
