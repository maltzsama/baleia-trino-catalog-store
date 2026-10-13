package io.baleia.trino.catalogstore.guard;

import io.trino.spi.QueryId;
import io.trino.spi.connector.CatalogSchemaName;
import io.trino.spi.connector.CatalogSchemaTableName;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.security.AccessDeniedException;
import io.trino.spi.security.Identity;
import io.trino.spi.security.SystemSecurityContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class BaleiaCatalogGuardTest
{
    private static final String ADMIN = "baleia_admin";
    private static final String USER = "u_123e4567-e89b-12d3-a456-426614174000";

    private static final BaleiaCatalogGuard guard = new BaleiaCatalogGuard(Set.of(ADMIN));

    private static SystemSecurityContext context(String user, Map<String, String> extraCredentials)
    {
        Identity identity = Identity.forUser(user)
                .withExtraCredentials(extraCredentials)
                .build();
        return new SystemSecurityContext(identity, new QueryId("20240101_000000_00000_abcde"), Instant.now());
    }

    private static final CatalogSchemaTableName TABLE =
            new CatalogSchemaTableName("tpch", new SchemaTableName("sf1", "lineitem"));

    @Test
    public void deniesCreateForNonAdmin()
    {
        assertThrows(AccessDeniedException.class, () -> guard.checkCanCreateCatalog(context(USER, Map.of()), "evil"));
    }

    @Test
    public void deniesDropForNonAdmin()
    {
        assertThrows(AccessDeniedException.class, () -> guard.checkCanDropCatalog(context(USER, Map.of()), "tpch"));
    }

    @Test
    public void allowsCreateForAdmin()
    {
        assertDoesNotThrow(() -> guard.checkCanCreateCatalog(context(ADMIN, Map.of()), "tpch"));
        assertDoesNotThrow(() -> guard.checkCanDropCatalog(context(ADMIN, Map.of()), "tpch"));
    }

    @Test
    public void deniesImpersonatingAdmin()
    {
        assertThrows(AccessDeniedException.class,
                () -> guard.checkCanImpersonateUser(Identity.forUser(USER).build(), ADMIN));
        assertDoesNotThrow(() -> guard.checkCanImpersonateUser(Identity.forUser(ADMIN).build(), USER));
        assertDoesNotThrow(() -> guard.checkCanImpersonateUser(Identity.forUser(USER).build(), "someone_else"));
    }

    @Test
    public void readonlyDeniesEveryWriteCheck()
    {
        SystemSecurityContext ro = context(USER, Map.of(BaleiaCatalogGuard.READ_ONLY_CREDENTIAL, "readonly"));

        // The admin/impersonation rules are not read-only write checks; they are
        // covered by deniesCreateForNonAdmin / deniesImpersonatingAdmin.
        Set<String> adminOnly = Set.of("checkCanImpersonateUser", "checkCanSetUser");
        Method[] overridden = Arrays.stream(BaleiaCatalogGuard.class.getDeclaredMethods())
                .filter(m -> m.getName().startsWith("checkCan"))
                .filter(m -> m.getDeclaringClass() == BaleiaCatalogGuard.class)
                .filter(m -> !adminOnly.contains(m.getName()))
                .toArray(Method[]::new);

        for (Method method : overridden) {
            assertThrows(AccessDeniedException.class,
                    invocation(method, guard, ro),
                    "method " + method.getName() + " must deny in read-only mode");
        }
    }

    @Test
    public void readonlyAllowsReads()
    {
        SystemSecurityContext ro = context(USER, Map.of(BaleiaCatalogGuard.READ_ONLY_CREDENTIAL, "readonly"));
        // Read-only sessions still see the catalog namespace (SPI pass-through
        // filters); the guard only removes write abilities.
        assertDoesNotThrow(() -> guard.filterCatalogs(ro, Set.of("tpch")));
        assertDoesNotThrow(() -> guard.filterSchemas(ro, "tpch", Set.of("sf1")));
        assertDoesNotThrow(() -> guard.filterTables(ro, "tpch", Set.of(new SchemaTableName("sf1", "lineitem"))));
        assertDoesNotThrow(() -> guard.filterColumns(ro, TABLE, Set.of("l_orderkey")));
    }

    @Test
    public void noCredentialUnchanged()
    {
        SystemSecurityContext normal = context(USER, Map.of());
        assertDoesNotThrow(() -> guard.checkCanCreateTable(normal, TABLE, Map.of()));
        assertDoesNotThrow(() -> guard.checkCanSetSystemSessionProperty(
                normal.getIdentity(), normal.getQueryId(), "query_max_execution_time"));
    }

    @Test
    public void readonlyAllowsBaleiaSessionProperties()
    {
        SystemSecurityContext ro = context(USER, Map.of(BaleiaCatalogGuard.READ_ONLY_CREDENTIAL, "readonly"));
        assertThrows(AccessDeniedException.class,
                () -> guard.checkCanSetSystemSessionProperty(ro.getIdentity(), ro.getQueryId(), "cte_max_distance"));
        assertDoesNotThrow(() -> guard.checkCanSetSystemSessionProperty(
                ro.getIdentity(), ro.getQueryId(), "query_max_execution_time"));
        assertDoesNotThrow(() -> guard.checkCanSetSystemSessionProperty(
                ro.getIdentity(), ro.getQueryId(), "query_max_run_time"));
    }

    @Test
    public void factoryRequiresAdminUsers()
    {
        assertThrows(IllegalArgumentException.class,
                () -> new BaleiaCatalogGuardFactory().create(Map.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> new BaleiaCatalogGuardFactory().create(Map.of("baleia.admin-users", "   "), null));
        assertDoesNotThrow(() -> new BaleiaCatalogGuardFactory()
                .create(Map.of("baleia.admin-users", ADMIN), null));
    }

    private static Executable invocation(Method method, Object target, SystemSecurityContext ro)
    {
        return () -> {
            Class<?>[] params = method.getParameterTypes();
            Object[] args = new Object[params.length];
            args[0] = SystemSecurityContext.class.isAssignableFrom(params[0]) ? ro : ro.getIdentity();
            for (int i = 1; i < params.length; i++) {
                args[i] = params[i] == boolean.class ? Boolean.FALSE : null;
            }
            try {
                method.invoke(target, args);
            }
            catch (InvocationTargetException e) {
                // The declared checks throw AccessDeniedException directly.
                if (e.getCause() instanceof RuntimeException) {
                    throw (RuntimeException) e.getCause();
                }
                throw new RuntimeException(e);
            }
            catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        };
    }
}