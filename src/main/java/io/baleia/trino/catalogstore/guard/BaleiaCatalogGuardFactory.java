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
 * baleia.allow-unauthenticated-admin=false
 * }</pre>
 *
 * The {@code baleia.admin-users} comma-separated list is required: without it
 * the guard would deny every catalog operation to everyone.
 *
 * <p>{@code baleia.allow-unauthenticated-admin} (default {@code false}) lets a connection
 * with no authenticated principal claim an admin user. Set it only when the coordinator has no
 * authentication and is reachable solely from trusted callers (Baleia's API and worker);
 * with authentication enabled a non-admin principal is still denied.</p>
 */
public class BaleiaCatalogGuardFactory
        implements SystemAccessControlFactory
{
    public static final String NAME = "baleia-catalog-guard";
    public static final String ADMIN_USERS_PROPERTY = "baleia.admin-users";
    public static final String ALLOW_UNAUTHENTICATED_ADMIN_PROPERTY = "baleia.allow-unauthenticated-admin";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public SystemAccessControl create(Map<String, String> config, SystemAccessControlContext context)
    {
        String raw = config.get(ADMIN_USERS_PROPERTY);
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(ADMIN_USERS_PROPERTY + " is required");
        }
        Set<String> admins = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(user -> !user.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        if (admins.isEmpty()) {
            throw new IllegalArgumentException(ADMIN_USERS_PROPERTY + " must list at least one user");
        }
        boolean allowUnauthenticatedAdmin = Boolean.parseBoolean(
                config.getOrDefault(ALLOW_UNAUTHENTICATED_ADMIN_PROPERTY, "false").trim());
        return new BaleiaCatalogGuard(admins, allowUnauthenticatedAdmin);
    }
}