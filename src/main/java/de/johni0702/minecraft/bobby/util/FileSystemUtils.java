package de.johni0702.minecraft.bobby.util;

import com.google.common.net.PercentEscaper;

import java.nio.file.Path;

public class FileSystemUtils {
    private static final PercentEscaper FALLBACK_NAME_ENCODER = new PercentEscaper(".-_ ", false);

    /**
     * Resolves the child string against the parent path, ensuring that the result is a (direct or indirect) child of
     * the parent (i.e. guards against directory traversal).
     */
    public static Path resolveChild(Path parent, String child) {
        Path childPath = parent.resolve(child);
        if (!childPath.normalize().startsWith(parent.normalize())) {
            return parent.resolve(FALLBACK_NAME_ENCODER.escape(child));
        }
        return childPath;
    }
}
