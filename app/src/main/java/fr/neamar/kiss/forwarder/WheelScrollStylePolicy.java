package fr.neamar.kiss.forwarder;

/**
 * The 3D Wheel scroll style is applied to the existing 12 shells, never as an
 * additional per-child Animator. A single geometry pass avoids fighting the
 * scrolling gate or competing with launch-morph animations.
 */
final class WheelScrollStylePolicy {
    static final class Profile {
        final float tilt, depth, fade, lift, sideways, twist, yaw;
        Profile(float tilt, float depth, float fade, float lift,
                float sideways, float twist, float yaw) {
            this.tilt = tilt;
            this.depth = depth;
            this.fade = fade;
            this.lift = lift;
            this.sideways = sideways;
            this.twist = twist;
            this.yaw = yaw;
        }
    }
    private WheelScrollStylePolicy() {}

    static Profile forStyle(String style) {
        if (style == null) style = "classic";
        switch (style) {
            case "none": return new Profile(68f, .17f, .64f, 15f, 0f, 0f, 0f);
            case "bounce": return new Profile(58f, .13f, .54f, 40f, 0f, 0f, 0f);
            case "depth": return new Profile(86f, .28f, .70f, 14f, 0f, 0f, 0f);
            case "wave": return new Profile(65f, .18f, .66f, 24f, 22f, 0f, 0f);
            case "slide": return new Profile(40f, .09f, .46f, 16f, 32f, 0f, 0f);
            case "stack": return new Profile(47f, .15f, .64f, 22f, 0f, 0f, 0f);
            case "zoom": return new Profile(65f, .30f, .67f, 15f, 0f, 0f, 0f);
            case "tilt": return new Profile(68f, .20f, .66f, 16f, 17f, 0f, 22f);
            case "cascade": return new Profile(60f, .16f, .59f, 32f, 17f, 0f, 0f);
            case "flip": return new Profile(102f, .20f, .70f, 13f, 0f, 0f, 0f);
            case "helix": return new Profile(76f, .23f, .69f, 21f, 34f, 0f, 27f);
            case "fan": return new Profile(69f, .20f, .64f, 17f, 17f, 11f, 0f);
            case "parallax": return new Profile(68f, .13f, .55f, 19f, 44f, 0f, 0f);
            case "swing": return new Profile(66f, .18f, .62f, 18f, 19f, 13f, 0f);
            case "accordion": return new Profile(80f, .27f, .72f, 37f, 0f, 0f, 0f);
            case "spiral": return new Profile(75f, .22f, .71f, 27f, 30f, 18f, 11f);
            case "cube": return new Profile(89f, .25f, .70f, 15f, 17f, 0f, 26f);
            case "focus": return new Profile(56f, .13f, .56f, 15f, 0f, 0f, 0f);
            case "classic":
            default: return new Profile(72f, .20f, .78f, 22f, 0f, 0f, 0f);
        }
    }
}
