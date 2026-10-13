package io.baleia.trino.catalogstore.guard;

import io.trino.spi.QueryId;
import io.trino.spi.connector.CatalogSchemaName;
import io.trino.spi.connector.CatalogSchemaTableName;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.security.AccessDeniedException;
import io.trino.spi.security.Identity;
import io.trino.spi.security.SystemAccessControl;
import io.trino.spi.security.SystemSecurityContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.Principal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.TreeSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class BaleiaCatalogGuardTest
{
    private static final String ADMIN = "baleia_admin";
    private static final String USER = "u_123e4567-e89b-12d3-a456-426614174000";

    private static final BaleiaCatalogGuard guard = new BaleiaCatalogGuard(Set.of(ADMIN));

    // Checks the guard does not decide: it only allows them (see the guard's comment).
    private static final Set<String> PASS_THROUGH_CHECKS = Set.of(
            "checkCanExecuteQuery", "checkCanViewQueryOwnedBy", "checkCanKillQueryOwnedBy",
            "checkCanReadSystemInformation", "checkCanShowSchemas", "checkCanShowCreateSchema",
            "checkCanShowCreateTable", "checkCanShowTables", "checkCanShowColumns",
            "checkCanSelectFromColumns", "checkCanShowRoles", "checkCanShowCurrentRoles",
            "checkCanShowRoleGrants", "checkCanShowFunctions", "checkCanShowCreateFunction",
            "checkCanShowBranches");

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
        // covered by deniesCreateForNonAdmin / deniesImpersonatingAdmin. The read
        // checks the guard only allows are covered by allowsEverythingItDoesNotDecide.
        Set<String> notWriteChecks = new java.util.HashSet<>(PASS_THROUGH_CHECKS);
        notWriteChecks.addAll(Set.of("checkCanImpersonateUser", "checkCanSetUser"));
        Method[] overridden = Arrays.stream(BaleiaCatalogGuard.class.getDeclaredMethods())
                .filter(m -> m.getName().startsWith("checkCan"))
                .filter(m -> m.getDeclaringClass() == BaleiaCatalogGuard.class)
                .filter(m -> !notWriteChecks.contains(m.getName()))
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
        assertEquals(Set.of("tpch"), guard.filterCatalogs(ro, Set.of("tpch")));
        assertEquals(Set.of("sf1"), guard.filterSchemas(ro, "tpch", Set.of("sf1")));
        assertEquals(Set.of(new SchemaTableName("sf1", "lineitem")),
                guard.filterTables(ro, "tpch", Set.of(new SchemaTableName("sf1", "lineitem"))));
        assertEquals(Set.of("l_orderkey"), guard.filterColumns(ro, TABLE, Set.of("l_orderkey")));
        assertTrue(guard.canAccessCatalog(ro, "tpch"));
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

    /**
     * Trino denies every SystemAccessControl check an implementation does not override
     * (the SPI default), so a guard that skips one denies that operation cluster-wide.
     * The same goes for the boolean can* methods (default false) and the filter* methods
     * (default: empty), which would hide every catalog and table. Every one of them must be
     * overridden here.
     */
    @Test
    public void everySpiCheckIsOverridden()
    {
        TreeSet<String> missing = new TreeSet<>();
        for (Method spi : SystemAccessControl.class.getMethods()) {
            boolean restrictiveDefault = spi.getName().startsWith("checkCan")
                    || spi.getName().startsWith("canAccess")
                    || spi.getName().startsWith("canExecute")
                    || spi.getName().startsWith("canCreate")
                    || spi.getName().startsWith("filter");
            if (!restrictiveDefault || !spi.isDefault()) {
                continue;
            }
            boolean overridden = Arrays.stream(BaleiaCatalogGuard.class.getDeclaredMethods())
                    .anyMatch(m -> m.getName().equals(spi.getName())
                            && Arrays.equals(m.getParameterTypes(), spi.getParameterTypes()));
            if (!overridden) {
                missing.add(spi.getName() + Arrays.toString(spi.getParameterTypes()));
            }
        }
        assertTrue(missing.isEmpty(), "the guard does not override these SPI checks (Trino would deny them): " + missing);
    }

    @Test
    public void allowsEverythingItDoesNotDecide()
    {
        SystemSecurityContext user = context(USER, Map.of());
        SystemSecurityContext readonly = context(USER, Map.of("baleia.mode", "readonly"));
        for (SystemSecurityContext ctx : new SystemSecurityContext[] {user, readonly}) {
            assertDoesNotThrow(() -> guard.checkCanExecuteQuery(ctx.getIdentity(), ctx.getQueryId()));
            assertDoesNotThrow(() -> guard.checkCanViewQueryOwnedBy(ctx.getIdentity(), ctx.getIdentity()));
            assertDoesNotThrow(() -> guard.checkCanReadSystemInformation(ctx.getIdentity()));
            assertDoesNotThrow(() -> guard.checkCanShowSchemas(ctx, "tpch"));
            assertDoesNotThrow(() -> guard.checkCanShowTables(ctx, new CatalogSchemaName("tpch", "sf1")));
            assertDoesNotThrow(() -> guard.checkCanShowColumns(ctx, TABLE));
            assertDoesNotThrow(() -> guard.checkCanSelectFromColumns(ctx, TABLE, Set.of("a")));
            assertDoesNotThrow(() -> guard.checkCanSelectFromColumns(ctx, TABLE, Optional.empty(), Set.of("a")));
            assertTrue(guard.canAccessCatalog(ctx, "tpch"));
            assertEquals(Set.of("tpch", "sf1"), guard.filterCatalogs(ctx, Set.of("tpch", "sf1")));
        }
    }

    private static Principal principal(String name)
    {
        return () -> name;
    }

    @Test
    public void setUserWithoutAuthenticationIsDeniedByDefault()
    {
        assertThrows(AccessDeniedException.class, () -> guard.checkCanSetUser(Optional.empty(), ADMIN));
        // Non-admin target users are not the guard's business.
        assertDoesNotThrow(() -> guard.checkCanSetUser(Optional.empty(), USER));
    }

    @Test
    public void setUserWithoutAuthenticationAllowedWhenConfigured()
    {
        BaleiaCatalogGuard open = new BaleiaCatalogGuard(Set.of(ADMIN), true);
        assertDoesNotThrow(() -> open.checkCanSetUser(Optional.empty(), ADMIN));
        // An authenticated non-admin principal is still denied, flag or not.
        assertThrows(AccessDeniedException.class, () -> open.checkCanSetUser(Optional.of(principal(USER)), ADMIN));
        assertDoesNotThrow(() -> open.checkCanSetUser(Optional.of(principal(ADMIN)), ADMIN));
        // Impersonation (SET SESSION AUTHORIZATION) is unaffected by the flag.
        assertThrows(AccessDeniedException.class, () -> open.checkCanImpersonateUser(context(USER, Map.of()).getIdentity(), ADMIN));
    }

    @Test
    public void factoryReadsTheUnauthenticatedAdminFlag()
    {
        BaleiaCatalogGuardFactory factory = new BaleiaCatalogGuardFactory();
        SystemAccessControl strict = factory.create(Map.of("baleia.admin-users", ADMIN), null);
        assertThrows(AccessDeniedException.class, () -> strict.checkCanSetUser(Optional.empty(), ADMIN));
        SystemAccessControl open = factory.create(Map.of(
                "baleia.admin-users", ADMIN,
                "baleia.allow-unauthenticated-admin", "true"), null);
        assertDoesNotThrow(() -> open.checkCanSetUser(Optional.empty(), ADMIN));
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