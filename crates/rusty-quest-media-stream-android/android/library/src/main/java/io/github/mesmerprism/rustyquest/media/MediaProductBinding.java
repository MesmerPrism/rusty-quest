package io.github.mesmerprism.rustyquest.media;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exact providers packaged by one Android product. */
public final class MediaProductBinding {
    private final String productId;
    private final Map<String, MediaOwnerProvider> providers;
    private final Map<String,String[]> preparationBindings;

    private MediaProductBinding(String productId, Map<String, MediaOwnerProvider> providers, Map<String,String[]> rows) {
        this.productId = productId;
        this.providers = Collections.unmodifiableMap(new LinkedHashMap<>(providers));
        Map<String,String[]> owned=new LinkedHashMap<>();
        for(Map.Entry<String,String[]> row:rows.entrySet())owned.put(row.getKey(),row.getValue().clone());
        this.preparationBindings=Collections.unmodifiableMap(owned);
    }
    public String productId() { return productId; }
    MediaOwnerProvider provider(MediaOwnerAction action) { return providers.get(action.bindingKey()); }
    public int providerCount() { return providers.size(); }
    /** Data from this SAME builder's actual providers; it grants no media effect. */
    public org.json.JSONObject preparationDescriptor() throws Exception {
        java.util.ArrayList<String> keys=new java.util.ArrayList<>(preparationBindings.keySet());java.util.Collections.sort(keys);
        org.json.JSONArray rows=new org.json.JSONArray();
        for(String key:keys){
            String[] row=preparationBindings.get(key);
            for(String value:row)if(value==null || value.isEmpty() || value.indexOf(0)>=0)throw new IllegalStateException("preparation provider identity");
            if(providers.get(key)==null)throw new IllegalStateException("preparation actual provider absent");
            rows.put(new org.json.JSONObject().put("owner_kind",row[0]).put("owner_id",row[1]).put("provider_kind",row[2]).put("resource_id",row[3]));
        }
        return new org.json.JSONObject().put("$schema","rusty.quest.android.media.preparation_target.v1")
            .put("product_id",productId).put("providers",rows);
    }


    static String key(String ownerKind, String ownerId, String providerKind, String resourceId) {
        return ownerKind + "\u0000" + ownerId + "\u0000" + providerKind + "\u0000" + resourceId;
    }

    public static final class Builder {
        private final String productId;
        private final Map<String, MediaOwnerProvider> providers = new LinkedHashMap<>();
        private final Map<String,String[]> preparationBindings=new LinkedHashMap<>();
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
            preparationBindings.put(key,new String[]{ownerKind,ownerId,providerKind,resourceId});
            return this;
        }
        public MediaProductBinding build() {
            if (providers.isEmpty()) throw new IllegalStateException("product has no media providers");
            return new MediaProductBinding(productId, providers, preparationBindings);
        }
    }
}
