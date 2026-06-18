package site.kael.telegram.tdlight;

import it.tdlight.client.SimpleTelegramClientFactory;

import java.util.List;
import java.util.Locale;

public final class TdLightRuntime {
    private static final List<String> INCLUDED_NATIVE_CLASSIFIERS = List.of(
            "linux_amd64_gnu_ssl3",
            "linux_arm64_gnu_ssl3",
            "linux_amd64_gnu_ssl1",
            "linux_arm64_gnu_ssl1",
            "windows_amd64",
            "macos_arm64"
    );

    private TdLightRuntime() {
    }

    public static SimpleTelegramClientFactory createClientFactory() {
        return new SimpleTelegramClientFactory();
    }

    public static List<String> includedNativeClassifiers() {
        return INCLUDED_NATIVE_CLASSIFIERS;
    }

    public static String currentPlatformKey() {
        return normalizeOs(System.getProperty("os.name")) + "_" + normalizeArch(System.getProperty("os.arch"));
    }

    public static boolean isCurrentPlatformIncluded() {
        var platform = currentPlatformKey();
        return INCLUDED_NATIVE_CLASSIFIERS.stream().anyMatch(classifier -> classifier.startsWith(platform));
    }

    private static String normalizeOs(String osName) {
        var value = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (value.contains("win")) {
            return "windows";
        }
        if (value.contains("mac") || value.contains("darwin")) {
            return "macos";
        }
        if (value.contains("linux")) {
            return "linux";
        }
        return value.replaceAll("[^a-z0-9]+", "");
    }

    private static String normalizeArch(String archName) {
        var value = archName == null ? "" : archName.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "x86_64", "amd64" -> "amd64";
            case "aarch64", "arm64" -> "arm64";
            default -> value.replaceAll("[^a-z0-9]+", "");
        };
    }
}
