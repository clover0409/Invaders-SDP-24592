package shop;

import currency.ShopCatalog.Category;

/** Non-stat shop unlocks. Ownership also equips the cosmetic. */
public enum ShipCosmetic {
    USED_EXHAUST("cosmetic.used-exhaust", "二手尾焰，九成新", "Used Exhaust", 30),
    NOSE_DECAL("cosmetic.nose-decal", "机头贴纸，理论上减阻", "Nose Decal", 20);

    private final String id;
    private final String label;
    private final String fallback;
    private final int price;

    ShipCosmetic(String id, String label, String fallback, int price) {
        this.id = id;
        this.label = label;
        this.fallback = fallback;
        this.price = price;
    }

    public String getId() { return id; }
    public String getLabel() { return label; }
    public String getFallback() { return fallback; }

    ShopProduct toProduct() {
        return new ShopProduct(id, label, Category.UNLOCKABLE, price, "Visual only");
    }

    public static ShipCosmetic fromProductId(String id) {
        for (ShipCosmetic cosmetic : values()) {
            if (cosmetic.id.equals(id)) return cosmetic;
        }
        return null;
    }
}
