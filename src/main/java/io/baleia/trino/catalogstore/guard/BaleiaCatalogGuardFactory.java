package io.baleia.trino.catalogstore.guard;

import io.trino.spi.security.SystemAccessControl;
import io.trino.spi.security.SystemAccessControlFactory;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Factory for {@link BaleiaCatalogGuard}. Selected in the operator's own access
 * control file:
 *
 * <pre>{@code
 * access-control.name=baleia-catalog-guard
 * baleia.admin-users=baleia_admin
 * baleia.service-users=baleia
 * baleia.allow-unauthenticated-admin=false
 * }</pre>
 *
 * The {@code baleia.admin-users} comma-separated list is required: without it
 * the guard would deny every catalog operation to everyone.
 *
 * <p>{@code baleia.service-users} (optional) lists Baleia's own service identities. They are
 * trusted for catalog access and query visibility, like admins, but cannot manage catalogs.</p>
 *
 * <p>{@code baleia.allow-unauthenticated-admin} (default {@code false}) lets a connection
 * with no authenticated principal claim a trusted user. Set it only when the coordinator has no
 * authentication and is reachable solely from trusted callers (Baleia's API and worker);
 * with authentication enabled a non-trusted principal is still denied.</p>
 */
public class BaleiaCatalogGuardFactory
        implements SystemAccessControlFactory
{
    public static final String NAME = "baleia-catalog-guard";
    public static final String ADMIN_USERS_PROPERTY = "baleia.admin-users";
    public static final String SERVICE_USERS_PROPERTY = "baleia.service-users";
    public static final String ALLOW_UNAUTHENTICATED_ADMIN_PROPERTY = "baleia.allow-unauthenticated-admin";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public SystemAccessControl create(Map<String, String> config, SystemAccessControlContext context)
    {
        Set<String> admins = splitUsers(config.get(ADMIN_USERS_PROPERTY));
        if (admins.isEmpty()) {
            throw new IllegalArgumentException(ADMIN_USERS_PROPERTY + " must list at least one user");
        }
        Set<String> services = splitUsers(config.get(SERVICE_USERS_PROPERTY));
        boolean allowUnauthenticatedAdmin = Boolean.parseBoolean(
                config.getOrDefault(ALLOW_UNAUTHENTICATED_ADMIN_PROPERTY, "false").trim());
        return new BaleiaCatalogGuard(admins, services, allowUnauthenticatedAdmin);
    }

    private static Set<String> splitUsers(String raw)
    {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(user -> !user.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}