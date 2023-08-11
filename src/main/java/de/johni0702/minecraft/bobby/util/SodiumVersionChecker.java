package de.johni0702.minecraft.bobby.util;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;

public class SodiumVersionChecker {
    public static class SodiumVersions {
        public static final int None = 0;
        public static final int Sodium0_4 = 1;
        public static final int Sodium0_5 = 2;
    }

    public static final int sodiumVersion;

    static{
        int sodiumVersionTemp = -1;
        var sodiumModContainer = FabricLoader.getInstance().getModContainer("sodium");
        if (sodiumModContainer.isEmpty()){
            sodiumVersionTemp = SodiumVersions.None;
        }

        Version sodiumSemver = null;
        Version ver0_4_0 = null;
        Version ver0_5_0 = null;

        if (sodiumVersionTemp < 0) {
            sodiumSemver = sodiumModContainer.get().getMetadata().getVersion();
            try {
                ver0_4_0 = Version.parse("0.4.0");
                ver0_5_0 = Version.parse("0.5.0");
            } catch (Exception e) {
                sodiumVersionTemp = SodiumVersions.None;
            }
        }

        if (sodiumVersionTemp < 0) {
            if (sodiumSemver.compareTo(ver0_4_0) < 0) {
                sodiumVersionTemp = SodiumVersions.None;
            } else if (sodiumSemver.compareTo(ver0_5_0) < 0) {
                sodiumVersionTemp = SodiumVersions.Sodium0_4;
            } else {
                sodiumVersionTemp = SodiumVersions.Sodium0_5;
            }
        }

        sodiumVersion = sodiumVersionTemp;
    }
}
