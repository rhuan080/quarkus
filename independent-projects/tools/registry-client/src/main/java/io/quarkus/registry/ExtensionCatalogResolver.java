package io.quarkus.registry;

import io.quarkus.bootstrap.resolver.maven.BootstrapMavenException;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.devtools.messagewriter.MessageWriter;
import io.quarkus.maven.ArtifactCoords;
import io.quarkus.maven.ArtifactKey;
import io.quarkus.registry.catalog.ExtensionCatalog;
import io.quarkus.registry.catalog.Platform;
import io.quarkus.registry.catalog.PlatformCatalog;
import io.quarkus.registry.catalog.json.JsonCatalogMerger;
import io.quarkus.registry.catalog.json.JsonPlatformCatalog;
import io.quarkus.registry.client.RegistryClientFactory;
import io.quarkus.registry.client.maven.MavenRegistryClientFactory;
import io.quarkus.registry.config.RegistriesConfig;
import io.quarkus.registry.config.RegistriesConfigLocator;
import io.quarkus.registry.config.RegistryConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public class ExtensionCatalogResolver {

    public static Builder builder() {
        return new ExtensionCatalogResolver().new Builder();
    }

    public class Builder {

        private MavenArtifactResolver artifactResolver;
        private RegistriesConfig config;
        private boolean built;

        private Builder() {
        }

        public Builder artifactResolver(MavenArtifactResolver resolver) {
            assertNotBuilt();
            artifactResolver = resolver;
            return this;
        }

        public Builder messageWriter(MessageWriter messageWriter) {
            assertNotBuilt();
            log = messageWriter;
            return this;
        }

        public Builder config(RegistriesConfig registriesConfig) {
            assertNotBuilt();
            config = registriesConfig;
            return this;
        }

        public ExtensionCatalogResolver build() {
            assertNotBuilt();
            built = true;
            completeConfig();
            buildRegistryClients();
            return ExtensionCatalogResolver.this;
        }

        private void completeConfig() {
            if (config == null) {
                config = RegistriesConfigLocator.resolveConfig();
            }
            if (log == null) {
                log = config.isDebug() ? MessageWriter.debug() : MessageWriter.info();
            }
            if (artifactResolver == null) {
                try {
                    artifactResolver = MavenArtifactResolver.builder()
                            .setWorkspaceDiscovery(false)
                            .setArtifactTransferLogging(config.isDebug())
                            .build();
                } catch (BootstrapMavenException e) {
                    throw new IllegalStateException("Failed to intialize the default Maven artifact resolver", e);
                }
            }
        }

        private void buildRegistryClients() {
            registries = new ArrayList<>(config.getRegistries().size());
            final RegistryClientFactory defaultClientFactory = new MavenRegistryClientFactory(artifactResolver,
                    log);
            for (RegistryConfig config : config.getRegistries()) {
                if (config.isDisabled()) {
                    continue;
                }
                try {
                    registries.add(new RegistryExtensionResolver(defaultClientFactory.buildRegistryClient(config), log));
                } catch (RegistryResolutionException e) {
                    log.warn(e.getMessage());
                    continue;
                }
            }
        }

        private void assertNotBuilt() {
            if (built) {
                throw new IllegalStateException("The builder has already built an instance");
            }
        }
    }

    private MessageWriter log;
    private List<RegistryExtensionResolver> registries;

    public boolean hasRegistries() {
        return !registries.isEmpty();
    }

    public PlatformCatalog resolvePlatformCatalog() throws RegistryResolutionException {
        return resolvePlatformCatalog(null);
    }

    public PlatformCatalog resolvePlatformCatalog(String quarkusVersion) throws RegistryResolutionException {
        final Set<ArtifactKey> collectedPlatformKeys = new HashSet<>();

        List<PlatformCatalog> catalogs = new ArrayList<>(registries.size());
        for (RegistryExtensionResolver qer : registries) {
            final PlatformCatalog catalog = qer.resolvePlatformCatalog(quarkusVersion);
            if (catalog != null) {
                catalogs.add(catalog);
            }
        }

        if (catalogs.isEmpty()) {
            return null;
        }
        if (catalogs.size() == 1) {
            return catalogs.get(0);
        }

        final JsonPlatformCatalog result = new JsonPlatformCatalog();

        result.setDefaultPlatform(catalogs.get(0).getDefaultPlatform());

        final List<Platform> collectedPlatforms = new ArrayList<>();
        result.setPlatforms(collectedPlatforms);

        collectedPlatformKeys.clear();
        for (PlatformCatalog c : catalogs) {
            collectPlatforms(c, collectedPlatforms, collectedPlatformKeys);
        }

        return result;
    }

    private void collectPlatforms(PlatformCatalog catalog, List<Platform> collectedPlatforms,
            Set<ArtifactKey> collectedPlatformKeys) {
        for (Platform p : catalog.getPlatforms()) {
            if (collectedPlatformKeys.add(p.getBom().getKey())) {
                collectedPlatforms.add(p);
            }
        }
    }

    public ExtensionCatalog resolveExtensionCatalog() throws RegistryResolutionException {
        return resolveExtensionCatalog((String) null);
    }

    public ExtensionCatalog resolveExtensionCatalog(String quarkusCoreVersion) throws RegistryResolutionException {

        final int registriesTotal = registries.size();
        if (registriesTotal == 0) {
            throw new RegistryResolutionException("No registries configured");
        }
        final Map<String, List<RegistryExtensionResolver>> registriesByQuarkusCore = new LinkedHashMap<>(registriesTotal);
        List<ExtensionCatalog> extensionCatalogs = null;

        if (quarkusCoreVersion == null) {
            // if there is only one registry and all the platforms it recommends are based
            // on the same quarkus version then we don't need to execute extra requests to align the platforms
            // on the common quarkus version
            if (registriesTotal == 1) {
                final PlatformCatalog platformsCatalog = registries.get(0).resolvePlatformCatalog();
                final List<Platform> platforms = platformsCatalog == null ? Collections.emptyList()
                        : platformsCatalog.getPlatforms();
                if (platforms.isEmpty()) {
                    // TODO this should be allowed
                    throw new RegistryResolutionException(
                            "Registry " + registries.get(0).getId() + " does not provide any platform");
                }
                String commonQuarkusVersion = quarkusCoreVersion = platforms.get(0).getQuarkusCoreVersion();
                Platform defaultPlatform = null;
                int i = 1;
                while (i < platforms.size()) {
                    final Platform p = platforms.get(i++);
                    if (defaultPlatform == null && p.getBom().equals(platformsCatalog.getDefaultPlatform())) {
                        defaultPlatform = p;
                        quarkusCoreVersion = p.getQuarkusCoreVersion();
                    }
                    if (commonQuarkusVersion != null && !commonQuarkusVersion.equals(p.getQuarkusCoreVersion())) {
                        commonQuarkusVersion = null;
                    }
                }
                if (commonQuarkusVersion != null) {
                    // all platforms are aligned on the same quarkus version
                    final RegistryExtensionResolver mainRegistry = registries.get(0);
                    registriesByQuarkusCore.put(commonQuarkusVersion, Arrays.asList(mainRegistry));
                    extensionCatalogs = new ArrayList<>();
                    i = 0;
                    while (i < platforms.size()) {
                        extensionCatalogs.add(mainRegistry.resolvePlatformExtensions(platforms.get(i++).getBom()));
                    }
                }
            } else {
                quarkusCoreVersion = registries.get(0).resolveDefaultPlatform().getQuarkusCoreVersion();
            }
        }

        if (extensionCatalogs == null) {
            // collect all the platform extensions from all the registries compatible with the given quarkus core version
            extensionCatalogs = new ArrayList<>();
            final Set<String> upstreamQuarkusVersions = new HashSet<>(1);

            collectPlatforms(quarkusCoreVersion, registriesByQuarkusCore, upstreamQuarkusVersions, extensionCatalogs);

            if (!upstreamQuarkusVersions.isEmpty()) {
                Set<String> upstreamToProcess = upstreamQuarkusVersions;
                Set<String> collectedUpstream = new HashSet<>(0);
                do {
                    collectedUpstream.clear();
                    final Iterator<String> upstreamIterator = upstreamToProcess.iterator();
                    while (upstreamIterator.hasNext()) {
                        collectPlatforms(upstreamIterator.next(),
                                registriesByQuarkusCore, collectedUpstream, extensionCatalogs);
                    }
                    final Set<String> tmp = upstreamToProcess;
                    upstreamToProcess = collectedUpstream;
                    collectedUpstream = tmp;
                } while (!upstreamToProcess.isEmpty());
            }
        }
        return appendNonPlatformExtensions(registriesByQuarkusCore, extensionCatalogs);
    }

    public ExtensionCatalog resolveExtensionCatalog(List<ArtifactCoords> platforms)
            throws RegistryResolutionException {
        if (platforms.isEmpty()) {
            return resolveExtensionCatalog();
        }
        final List<ExtensionCatalog> catalogs = new ArrayList<>(platforms.size() + registries.size());
        Map<String, List<RegistryExtensionResolver>> registriesByQuarkusCore = new HashMap<>(2);
        String quarkusVersion = null;
        for (ArtifactCoords bom : platforms) {
            final List<RegistryExtensionResolver> registries;
            try {
                registries = filterRegistries(r -> r.checkPlatform(bom));
            } catch (ExclusiveProviderConflictException e) {
                final StringBuilder buf = new StringBuilder();
                buf.append(
                        "The following registries were configured as exclusive providers of the ");
                buf.append(bom);
                buf.append("platform: ").append(e.conflictingRegistries.get(0).getId());
                for (int i = 1; i < e.conflictingRegistries.size(); ++i) {
                    buf.append(", ").append(e.conflictingRegistries.get(i).getId());
                }
                throw new RuntimeException(buf.toString());
            }

            final ExtensionCatalog catalog = resolvePlatformExtensions(bom, registries);
            if (catalog != null) {
                catalogs.add(catalog);
                if (quarkusVersion == null) {
                    quarkusVersion = catalog.getQuarkusCoreVersion();
                    registriesByQuarkusCore.put(quarkusVersion, getRegistriesForQuarkusVersion(quarkusVersion));
                }
                final String upstreamQuarkusVersion = catalog.getUpstreamQuarkusCoreVersion();
                if (upstreamQuarkusVersion != null && !registriesByQuarkusCore.containsKey(upstreamQuarkusVersion)) {
                    registriesByQuarkusCore.put(upstreamQuarkusVersion,
                            getRegistriesForQuarkusVersion(upstreamQuarkusVersion));
                }
            }
        }
        return appendNonPlatformExtensions(registriesByQuarkusCore, catalogs);
    }

    private ExtensionCatalog resolvePlatformExtensions(ArtifactCoords bom, List<RegistryExtensionResolver> registries) {
        if (registries.isEmpty()) {
            log.debug("None of the configured registries recognizes platform %s", bom);
            return null;
        }
        for (RegistryExtensionResolver registry : registries) {
            try {
                return registry.resolvePlatformExtensions(bom);
            } catch (RegistryResolutionException e) {
            }
        }
        final StringBuilder buf = new StringBuilder();
        buf.append("Failed to resolve platform ").append(bom).append(" using the following registries: ");
        buf.append(registries.get(0).getId());
        for (int i = 1; i < registries.size(); ++i) {
            buf.append(", ").append(registries.get(i++));
        }
        log.warn(buf.toString());
        return null;
    }

    private ExtensionCatalog appendNonPlatformExtensions(
            final Map<String, List<RegistryExtensionResolver>> registriesByQuarkusCore,
            List<ExtensionCatalog> extensionCatalogs) throws RegistryResolutionException {
        for (Map.Entry<String, List<RegistryExtensionResolver>> quarkusVersionRegistries : registriesByQuarkusCore.entrySet()) {
            for (RegistryExtensionResolver registry : quarkusVersionRegistries.getValue()) {
                final ExtensionCatalog nonPlatformCatalog = registry
                        .resolveNonPlatformExtensions(quarkusVersionRegistries.getKey());
                if (nonPlatformCatalog != null) {
                    extensionCatalogs.add(nonPlatformCatalog);
                }
            }
        }
        return JsonCatalogMerger.merge(extensionCatalogs);
    }

    private void collectPlatforms(String quarkusCoreVersion,
            Map<String, List<RegistryExtensionResolver>> registriesByQuarkusCore,
            Set<String> upstreamQuarkusVersions, List<ExtensionCatalog> extensionCatalogs) throws RegistryResolutionException {
        List<RegistryExtensionResolver> quarkusVersionRegistries = registriesByQuarkusCore.get(quarkusCoreVersion);
        if (quarkusVersionRegistries != null) {
            return;
        }

        quarkusVersionRegistries = getRegistriesForQuarkusVersion(quarkusCoreVersion);
        registriesByQuarkusCore.put(quarkusCoreVersion, quarkusVersionRegistries);

        for (RegistryExtensionResolver registry : quarkusVersionRegistries) {
            final PlatformCatalog platformCatalog = registry.resolvePlatformCatalog(quarkusCoreVersion);
            if (platformCatalog == null) {
                continue;
            }
            final List<Platform> platforms = platformCatalog.getPlatforms();
            if (platforms.isEmpty()) {
                continue;
            }
            for (Platform p : platforms) {
                final String upstreamQuarkusCoreVersion = p.getUpstreamQuarkusCoreVersion();
                if (upstreamQuarkusCoreVersion != null
                        && !registriesByQuarkusCore.containsKey(upstreamQuarkusCoreVersion)) {
                    upstreamQuarkusVersions.add(upstreamQuarkusCoreVersion);
                }
                final ExtensionCatalog catalog = registry.resolvePlatformExtensions(p.getBom());
                if (catalog != null) {
                    extensionCatalogs.add(catalog);
                }
            }
        }
    }

    private List<RegistryExtensionResolver> getRegistriesForQuarkusVersion(String quarkusCoreVersion) {
        try {
            return filterRegistries(r -> r.checkQuarkusVersion(quarkusCoreVersion));
        } catch (ExclusiveProviderConflictException e) {
            final StringBuilder buf = new StringBuilder();
            buf.append(
                    "The following registries were configured as exclusive providers of extensions based on Quarkus version ");
            buf.append(quarkusCoreVersion);
            buf.append(": ").append(e.conflictingRegistries.get(0).getId());
            for (int i = 1; i < e.conflictingRegistries.size(); ++i) {
                buf.append(", ").append(e.conflictingRegistries.get(i).getId());
            }
            throw new RuntimeException(buf.toString());
        }
    }

    private List<RegistryExtensionResolver> filterRegistries(Function<RegistryExtensionResolver, Integer> recognizer)
            throws ExclusiveProviderConflictException {
        RegistryExtensionResolver exclusiveProvider = null;
        List<RegistryExtensionResolver> filtered = null;
        List<RegistryExtensionResolver> conflicts = null;
        for (int i = 0; i < registries.size(); ++i) {
            final RegistryExtensionResolver registry = registries.get(i);
            final int versionCheck = recognizer.apply(registry);

            if (versionCheck == RegistryExtensionResolver.VERSION_NOT_RECOGNIZED) {
                if (exclusiveProvider == null && filtered == null) {
                    filtered = new ArrayList<>(registries.size() - 1);
                    for (int j = 0; j < i; ++j) {
                        filtered.add(registries.get(j));
                    }
                }
                continue;
            }

            if (versionCheck == RegistryExtensionResolver.VERSION_EXCLUSIVE_PROVIDER) {
                if (exclusiveProvider == null) {
                    exclusiveProvider = registry;
                } else {
                    if (conflicts == null) {
                        conflicts = new ArrayList<>();
                        conflicts.add(exclusiveProvider);
                    }
                    conflicts.add(registry);
                }
            }

            if (filtered != null) {
                filtered.add(registry);
            }
        }

        if (conflicts != null) {
            throw new ExclusiveProviderConflictException(conflicts);
        }

        return exclusiveProvider == null ? filtered == null ? registries : filtered : Arrays.asList(exclusiveProvider);
    }
}
