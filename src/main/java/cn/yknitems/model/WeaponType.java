package cn.yknitems.model;

public enum WeaponType {
    SWORD,
    GREATSWORD,
    DAGGER,
    BOW,
    SPEAR,
    STAFF;

    public static WeaponType fromKey(String value) {
        return valueOf(value.toUpperCase(java.util.Locale.ROOT));
    }
}
