package xyz.kyngs.librelogin.common.config;

import org.spongepowered.configurate.CommentedConfigurationNode;
import xyz.kyngs.librelogin.api.BiHolder;
import xyz.kyngs.librelogin.api.Logger;
import xyz.kyngs.librelogin.common.config.key.ConfigurationKey;
import xyz.kyngs.librelogin.common.util.GeneralUtil;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Aplica sobreescrituras de configuración a partir de variables de entorno.
 * Convención:
 *  - Prefijo por defecto: LIBRELOGIN_
 *  - Soporta la convención clásica: LIBRELOGIN_MAIL_HOST -> mail.host
 *  - También soporta doble guion bajo para delimitar niveles si se desea: LIBRELOGIN__mail__host
 *  - Para claves con guiones (ej: allowed-commands-while-unauthorized) intentará emparejar
 *    un env como LIBRELOGIN_ALLOWED_COMMANDS_WHILE_UNAUTHORIZED.
 *  - Listas: valores separados por coma -> lista de strings
 */
public class EnvConfigurationOverrider {

    public static final String DEFAULT_PREFIX = "LIBRELOGIN_";

    private final String prefix;
    private final Logger logger;

    public EnvConfigurationOverrider(Logger logger) {
        this(DEFAULT_PREFIX, logger);
    }

    public EnvConfigurationOverrider(String prefix, Logger logger) {
        this.prefix = prefix == null ? DEFAULT_PREFIX : prefix;
        this.logger = logger;
    }

    public void loadFromEnvironment(Collection<BiHolder<Class<?>, String>> defaultKeys, ConfigurateHelper helper) {
        try {
            var allKeys = defaultKeys.stream()
                    .map(data -> new BiHolder<>(GeneralUtil.extractKeys(data.key()), data.value()))
                    .flatMap(k -> k.key().stream())
                    .toList();
            new EnvConfigurationOverrider(logger).applyOverrides(helper, allKeys);
        } catch (Exception e) {
            // No debe impedir la carga de configuración; solo logueamos el error
            logger.warn("Failed to apply environment overrides: " + e.getMessage());
        }
    }

    public void applyOverrides(ConfigurateHelper helper, Collection<ConfigurationKey<?>> knownKeys) {
        var env = System.getenv();

        // Prepare set of known keys (lower-case) for matching
        Set<String> known = (knownKeys == null) ? Collections.emptySet() : knownKeys.stream()
                .map(k -> k.key().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        for (Map.Entry<String, String> entry : env.entrySet()) {
            var name = entry.getKey();
            if (!name.startsWith(prefix)) continue;

            var raw = entry.getValue();
            if (raw == null) continue;

            var remainder = name.substring(prefix.length());
            // remove leading underscores
            while (remainder.startsWith("_")) remainder = remainder.substring(1);

            // split into words: if double underscore present, treat as explicit separators between levels
            String[] words = Arrays.stream(remainder.split("_"))
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .toArray(String[]::new);

            try {
                String[] segments = resolveSegmentsFromWords(words, known);

                Object parsed = parseValue(raw, segments, knownKeys);
                CommentedConfigurationNode node = helper.configuration().node((Object[]) segments);
                node.set(parsed);

                if (isSecretKey(segments)) {
                    logger.info("Applied env override for " + String.join(".", segments) + " = ****");
                } else {
                    logger.info("Applied env override for " + String.join(".", segments) + " = " + raw);
                }
            } catch (Exception e) {
                logger.warn("Failed to apply env override " + name + " -> " + Arrays.toString(words) + ": " + e.getMessage());
            }
        }
    }

    private String[] resolveSegmentsFromWords(String[] words, Set<String> knownKeysLower) {
        if (words.length == 0) return new String[0];

        // Generate all contiguous partitions of words into groups; each group will be joined with '-' to represent hyphenated keys
        List<List<String>> partitions = new ArrayList<>();
        generatePartitions(words, 0, new ArrayList<>(), partitions);

        // Prefer partitions that match known keys. We will check partitions in order of increasing number of groups
        partitions.sort(Comparator.comparingInt(List::size));

        for (List<String> partition : partitions) {
            String candidate = String.join(".", partition);
            if (knownKeysLower.contains(candidate)) {
                // matched exactly
                return partition.toArray(new String[0]);
            }
        }

        // No match with known keys; fallback to treating each word as a level (classic behavior)
        return Arrays.stream(words)
                .map(s -> s.replace('-', '_')) // normalize
                .toArray(String[]::new);
    }

    private void generatePartitions(String[] words, int idx, List<String> current, List<List<String>> out) {
        if (idx == words.length) {
            out.add(new ArrayList<>(current));
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (int end = idx; end < words.length; end++) {
            if (sb.length() > 0) sb.append('-');
            sb.append(words[end]);
            current.add(sb.toString());
            generatePartitions(words, end + 1, current, out);
            current.remove(current.size() - 1);
        }
    }

    private boolean isSecretKey(String[] segments) {
        var joined = String.join(".", segments).toLowerCase(Locale.ROOT);
        return joined.contains("password") || joined.contains("secret") || joined.contains("token") || joined.contains("key");
    }

    private Object parseValue(String raw, String[] segments, Collection<ConfigurationKey<?>> knownKeys) {
        var maybeKey = findKeyForPath(knownKeys, segments);
        return parseValue(raw, maybeKey.orElse(null));
    }

    private Optional<ConfigurationKey<?>> findKeyForPath(Collection<ConfigurationKey<?>> keys, String[] segments) {
        if (keys == null) return Optional.empty();
        var path = String.join(".", segments);
        for (ConfigurationKey<?> k : keys) {
            if (k.key().equalsIgnoreCase(path)) return Optional.of(k);
        }
        return Optional.empty();
    }

    private Object parseValue(String raw, ConfigurationKey<?> key) {
        if (key == null) return guessParse(raw);

        // We don't have type information in ConfigurationKey, so fallback to heuristics
        return guessParse(raw);
    }

    private Object guessParse(String raw) {
        var trimmed = raw.trim();
        if (trimmed.equalsIgnoreCase("true") || trimmed.equalsIgnoreCase("false") || trimmed.equals("1") || trimmed.equals("0")
                || trimmed.equalsIgnoreCase("yes") || trimmed.equalsIgnoreCase("no")) {
            return parseBoolean(trimmed);
        }

        if (trimmed.contains(",")) {
            return Arrays.stream(trimmed.split(","))
                    .map(String::trim)
                    .collect(Collectors.toList());
        }

        // integer?
        try {
            if (trimmed.matches("-?\\d+")) return Integer.parseInt(trimmed);
            if (trimmed.matches("-?\\d+L")) return Long.parseLong(trimmed.substring(0, trimmed.length() - 1));
            if (trimmed.matches("-?\\d+\\.\\d+")) return Double.parseDouble(trimmed);
        } catch (NumberFormatException ignored) {
        }

        return raw;
    }

    private Boolean parseBoolean(String s) {
        s = s.trim().toLowerCase(Locale.ROOT);
        if (s.equals("1") || s.equals("true") || s.equals("yes") || s.equals("y")) return true;
        if (s.equals("0") || s.equals("false") || s.equals("no") || s.equals("n")) return false;
        throw new IllegalArgumentException("Cannot parse boolean from " + s);
    }
}
