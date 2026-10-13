package io.baleia.trino.catalogstore.guard;

import com.google.common.collect.ImmutableSet;
import io.trino.spi.QueryId;
import io.trino.spi.connector.CatalogSchemaName;
import io.trino.spi.connector.CatalogSchemaRoutineName;
import io.trino.spi.connector.CatalogSchemaTableName;
import io.trino.spi.connector.EntityKindAndName;
import io.trino.spi.connector.EntityPrivilege;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.function.SchemaFunctionName;
import io.trino.spi.security.AccessDeniedException;
import io.trino.spi.security.Identity;
import io.trino.spi.security.Privilege;
import io.trino.spi.security.SystemAccessControl;
import io.trino.spi.security.SystemSecurityContext;
import io.trino.spi.security.TrinoPrincipal;

import java.security.Principal;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Baleia's catalog guard.
 *
 * <p>Trino loads it alongside the operator's own access control via
 * {@code access-control.config-files} (a comma-separated list), so it <em>stacks</em>
 * with file-based or Polaris guards instead of replacing them.</p>
 *
 * <p>It enforces two rules that the SQL editor cannot prove on its own:</p>
 * <ol>
 *   <li><b>Catalog management is admin-only.</b> {@code CREATE/DROP CATALOG} is
 *       accepted only for {@code baleia.admin-users} identities, and admin users
 *       cannot be impersonated by non-admins (which is what {@code SET SESSION
 *       AUTHORIZATION} would otherwise allow).</li>
 *   <li><b>Read-only mode.</b> A session whose identity carries the extra
 *       credential {@code baleia.mode=readonly} is denied every write/DDL check.
 *       See the SPI inventory in {@code docs/spi-482.md}.</li>
 * </ol>
 */
public class BaleiaCatalogGuard
        implements SystemAccessControl
{
    public static final String READ_ONLY_CREDENTIAL = "baleia.mode";
    public static final String READ_ONLY_VALUE = "readonly";

    // Session properties Baleia itself sets; allowed even in read-only sessions.
    private static final Set<String> BALEIA_SESSION_PROPERTIES =
            ImmutableSet.of("query_max_execution_time", "query_max_run_time");

    private final Set<String> adminUsers;
    private final boolean allowUnauthenticatedAdmin;

    public BaleiaCatalogGuard(Set<String> adminUsers)
    {
        this(adminUsers, false);
    }

    /**
     * @param allowUnauthenticatedAdmin when true, a connection with no authenticated
     *        principal (a coordinator without authentication, reachable only on a private
     *        network) may claim an admin user. A connection that does have an authenticated
     *        principal must still be an admin to land in an admin user.
     */
    public BaleiaCatalogGuard(Set<String> adminUsers, boolean allowUnauthenticatedAdmin)
    {
        this.adminUsers = ImmutableSet.copyOf(adminUsers);
        this.allowUnauthenticatedAdmin = allowUnauthenticatedAdmin;
    }

    // ── catalog management (admin-only) ────────────────────────────────────────

    @Override
    public void checkCanCreateCatalog(SystemSecurityContext context, String catalogName)
    {
        requireAdmin(context, "create catalog");
    }

    @Override
    public void checkCanDropCatalog(SystemSecurityContext context, String catalogName)
    {
        requireAdmin(context, "drop catalog");
    }

    // SET SESSION AUTHORIZATION and impersonation must not reach admin targets.
    @Override
    public void checkCanImpersonateUser(Identity identity, String userName)
    {
        if (adminUsers.contains(userName) && !adminUsers.contains(identity.getUser())) {
            AccessDeniedException.denyImpersonateUser(identity.getUser(), userName);
        }
    }

    @Override
    public void checkCanSetUser(Optional<Principal> principal, String userName)
    {
        if (!adminUsers.contains(userName)) {
            return;
        }
        if (principal.isPresent()) {
            // An authenticated principal may land in an admin user only if it is one.
            if (!adminUsers.contains(principal.get().getName())) {
                throw new AccessDeniedException("only Baleia admin users may connect as an admin user");
            }
            return;
        }
        // No authentication on this coordinator: identity is whatever the client sent.
        if (!allowUnauthenticatedAdmin) {
            throw new AccessDeniedException("only Baleia admin users may connect as an admin user");
        }
    }

    // ── read-only mode: every write check is denied ────────────────────────────

    @Override
    public void checkCanInsertIntoTable(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        requireWritable(context, "insert into table");
    }

    @Override
    public void checkCanInsertIntoTable(SystemSecurityContext context, CatalogSchemaTableName table, Optional<String> column)
    {
        requireWritable(context, "insert into table");
    }

    @Override
    public void checkCanDeleteFromTable(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        requireWritable(context, "delete from table");
    }

    @Override
    public void checkCanDeleteFromTable(SystemSecurityContext context, CatalogSchemaTableName table, Optional<String> column)
    {
        requireWritable(context, "delete from table");
    }

    @Override
    public void checkCanTruncateTable(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        requireWritable(context, "truncate table");
    }

    @Override
    public void checkCanUpdateTableColumns(SystemSecurityContext context, CatalogSchemaTableName table, Set<String> columns)
    {
        requireWritable(context, "update table");
    }

    @Override
    public void checkCanUpdateTableColumns(SystemSecurityContext context, CatalogSchemaTableName table, Optional<String> column, Set<String> columns)
    {
        requireWritable(context, "update table");
    }

    @Override
    public void checkCanCreateTable(SystemSecurityContext context, CatalogSchemaTableName table, Map<String, Object> properties)
    {
        requireWritable(context, "create table");
    }

    @Override
    public void checkCanDropTable(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        requireWritable(context, "drop table");
    }

    @Override
    public void checkCanRenameTable(SystemSecurityContext context, CatalogSchemaTableName from, CatalogSchemaTableName to)
    {
        requireWritable(context, "rename table");
    }

    @Override
    public void checkCanSetTableProperties(SystemSecurityContext context, CatalogSchemaTableName table, Map<String, Optional<Object>> properties)
    {
        requireWritable(context, "set table properties");
    }

    @Override
    public void checkCanSetTableComment(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        requireWritable(context, "set table comment");
    }

    @Override
    public void checkCanSetColumnComment(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        requireWritable(context, "set column comment");
    }

    @Override
    public void checkCanAddColumn(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        requireWritable(context, "add column");
    }

    @Override
    public void checkCanAlterColumn(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        requireWritable(context, "alter column");
    }

    @Override
    public void checkCanDropColumn(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        requireWritable(context, "drop column");
    }

    @Override
    public void checkCanRenameColumn(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        requireWritable(context, "rename column");
    }

    @Override
    public void checkCanSetTableAuthorization(SystemSecurityContext context, CatalogSchemaTableName table, TrinoPrincipal principal)
    {
        requireWritable(context, "set table authorization");
    }

    @Override
    public void checkCanCreateSchema(SystemSecurityContext context, CatalogSchemaName schema, Map<String, Object> properties)
    {
        requireWritable(context, "create schema");
    }

    @Override
    public void checkCanDropSchema(SystemSecurityContext context, CatalogSchemaName schema)
    {
        requireWritable(context, "drop schema");
    }

    @Override
    public void checkCanRenameSchema(SystemSecurityContext context, CatalogSchemaName from, String to)
    {
        requireWritable(context, "rename schema");
    }

    @Override
    public void checkCanSetSchemaAuthorization(SystemSecurityContext context, CatalogSchemaName schema, TrinoPrincipal principal)
    {
        requireWritable(context, "set schema authorization");
    }

    @Override
    public void checkCanCreateView(SystemSecurityContext context, CatalogSchemaTableName view)
    {
        requireWritable(context, "create view");
    }

    @Override
    public void checkCanCreateViewWithSelectFromColumns(SystemSecurityContext context, CatalogSchemaTableName view, Optional<String> column, Set<String> columns)
    {
        requireWritable(context, "create view");
    }

    @Override
    public void checkCanCreateViewWithSelectFromColumns(SystemSecurityContext context, CatalogSchemaTableName view, Set<String> columns)
    {
        requireWritable(context, "create view");
    }

    @Override
    public void checkCanDropView(SystemSecurityContext context, CatalogSchemaTableName view)
    {
        requireWritable(context, "drop view");
    }

    @Override
    public void checkCanRenameView(SystemSecurityContext context, CatalogSchemaTableName from, CatalogSchemaTableName to)
    {
        requireWritable(context, "rename view");
    }

    @Override
    public void checkCanSetViewAuthorization(SystemSecurityContext context, CatalogSchemaTableName view, TrinoPrincipal principal)
    {
        requireWritable(context, "set view authorization");
    }

    @Override
    public void checkCanSetViewComment(SystemSecurityContext context, CatalogSchemaTableName view)
    {
        requireWritable(context, "set view comment");
    }

    @Override
    public void checkCanRefreshView(SystemSecurityContext context, CatalogSchemaTableName view)
    {
        requireWritable(context, "refresh view");
    }

    @Override
    public void checkCanCreateMaterializedView(SystemSecurityContext context, CatalogSchemaTableName view, Map<String, Object> properties)
    {
        requireWritable(context, "create materialized view");
    }

    @Override
    public void checkCanRefreshMaterializedView(SystemSecurityContext context, CatalogSchemaTableName view)
    {
        requireWritable(context, "refresh materialized view");
    }

    @Override
    public void checkCanDropMaterializedView(SystemSecurityContext context, CatalogSchemaTableName view)
    {
        requireWritable(context, "drop materialized view");
    }

    @Override
    public void checkCanRenameMaterializedView(SystemSecurityContext context, CatalogSchemaTableName from, CatalogSchemaTableName to)
    {
        requireWritable(context, "rename materialized view");
    }

    @Override
    public void checkCanSetMaterializedViewProperties(SystemSecurityContext context, CatalogSchemaTableName view, Map<String, Optional<Object>> properties)
    {
        requireWritable(context, "set materialized view properties");
    }

    @Override
    public void checkCanSetMaterializedViewAuthorization(SystemSecurityContext context, CatalogSchemaTableName view, TrinoPrincipal principal)
    {
        requireWritable(context, "set materialized view authorization");
    }

    @Override
    public void checkCanExecuteProcedure(SystemSecurityContext context, CatalogSchemaRoutineName procedure)
    {
        requireWritable(context, "execute procedure");
    }

    @Override
    public void checkCanExecuteTableProcedure(SystemSecurityContext context, CatalogSchemaTableName table, String procedure)
    {
        requireWritable(context, "execute table procedure");
    }

    @Override
    public void checkCanCreateFunction(SystemSecurityContext context, CatalogSchemaRoutineName function)
    {
        requireWritable(context, "create function");
    }

    @Override
    public void checkCanDropFunction(SystemSecurityContext context, CatalogSchemaRoutineName function)
    {
        requireWritable(context, "drop function");
    }

    @Override
    public void checkCanCreateBranch(SystemSecurityContext context, CatalogSchemaTableName table, String branch)
    {
        requireWritable(context, "create branch");
    }

    @Override
    public void checkCanDropBranch(SystemSecurityContext context, CatalogSchemaTableName table, String branch)
    {
        requireWritable(context, "drop branch");
    }

    @Override
    public void checkCanFastForwardBranch(SystemSecurityContext context, CatalogSchemaTableName table, String current, String target)
    {
        requireWritable(context, "fast-forward branch");
    }

    @Override
    public void checkCanSetCatalogSessionProperty(SystemSecurityContext context, String catalogName, String propertyName)
    {
        requireWritable(context, "set catalog session property");
    }

    @Override
    public void checkCanSetSystemSessionProperty(Identity identity, QueryId queryId, String propertyName)
    {
        if (isReadOnly(identity) && !BALEIA_SESSION_PROPERTIES.contains(propertyName)) {
            throw new AccessDeniedException("cannot set session property '" + propertyName + "' in read-only mode");
        }
    }

    @Override
    public void checkCanGrantSchemaPrivilege(SystemSecurityContext context, Privilege privilege, CatalogSchemaName schema, TrinoPrincipal grantee, boolean grantOption)
    {
        requireWritable(context, "grant schema privilege");
    }

    @Override
    public void checkCanDenySchemaPrivilege(SystemSecurityContext context, Privilege privilege, CatalogSchemaName schema, TrinoPrincipal grantee)
    {
        requireWritable(context, "deny schema privilege");
    }

    @Override
    public void checkCanRevokeSchemaPrivilege(SystemSecurityContext context, Privilege privilege, CatalogSchemaName schema, TrinoPrincipal grantee, boolean grantOption)
    {
        requireWritable(context, "revoke schema privilege");
    }

    @Override
    public void checkCanGrantTablePrivilege(SystemSecurityContext context, Privilege privilege, CatalogSchemaTableName table, TrinoPrincipal grantee, boolean grantOption)
    {
        requireWritable(context, "grant table privilege");
    }

    @Override
    public void checkCanDenyTablePrivilege(SystemSecurityContext context, Privilege privilege, CatalogSchemaTableName table, TrinoPrincipal grantee)
    {
        requireWritable(context, "deny table privilege");
    }

    @Override
    public void checkCanRevokeTablePrivilege(SystemSecurityContext context, Privilege privilege, CatalogSchemaTableName table, TrinoPrincipal grantee, boolean grantOption)
    {
        requireWritable(context, "revoke table privilege");
    }

    @Override
    public void checkCanGrantTableBranchPrivilege(SystemSecurityContext context, Privilege privilege, CatalogSchemaTableName table, String branch, TrinoPrincipal grantee, boolean grantOption)
    {
        requireWritable(context, "grant table branch privilege");
    }

    @Override
    public void checkCanDenyTableBranchPrivilege(SystemSecurityContext context, Privilege privilege, CatalogSchemaTableName table, String branch, TrinoPrincipal grantee)
    {
        requireWritable(context, "deny table branch privilege");
    }

    @Override
    public void checkCanRevokeTableBranchPrivilege(SystemSecurityContext context, Privilege privilege, CatalogSchemaTableName table, String branch, TrinoPrincipal grantee, boolean grantOption)
    {
        requireWritable(context, "revoke table branch privilege");
    }

    @Override
    public void checkCanGrantEntityPrivilege(SystemSecurityContext context, EntityPrivilege privilege, EntityKindAndName entity, TrinoPrincipal grantee, boolean grantOption)
    {
        requireWritable(context, "grant entity privilege");
    }

    @Override
    public void checkCanDenyEntityPrivilege(SystemSecurityContext context, EntityPrivilege privilege, EntityKindAndName entity, TrinoPrincipal grantee)
    {
        requireWritable(context, "deny entity privilege");
    }

    @Override
    public void checkCanRevokeEntityPrivilege(SystemSecurityContext context, EntityPrivilege privilege, EntityKindAndName entity, TrinoPrincipal grantee, boolean grantOption)
    {
        requireWritable(context, "revoke entity privilege");
    }

    @Override
    public void checkCanSetEntityAuthorization(SystemSecurityContext context, EntityKindAndName entity, TrinoPrincipal principal)
    {
        requireWritable(context, "set entity authorization");
    }

    @Override
    public void checkCanCreateRole(SystemSecurityContext context, String role, Optional<TrinoPrincipal> grantor)
    {
        requireWritable(context, "create role");
    }

    @Override
    public void checkCanDropRole(SystemSecurityContext context, String role)
    {
        requireWritable(context, "drop role");
    }

    @Override
    public void checkCanGrantRoles(SystemSecurityContext context, Set<String> roles, Set<TrinoPrincipal> grantees, boolean adminOption, Optional<TrinoPrincipal> grantor)
    {
        requireWritable(context, "grant roles");
    }

    @Override
    public void checkCanRevokeRoles(SystemSecurityContext context, Set<String> roles, Set<TrinoPrincipal> grantees, boolean adminOption, Optional<TrinoPrincipal> grantor)
    {
        requireWritable(context, "revoke roles");
    }

    @Override
    public void checkCanWriteSystemInformation(Identity identity)
    {
        if (isReadOnly(identity)) {
            throw new AccessDeniedException("cannot write system information in read-only mode");
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    // ── checks the guard does not decide: always allowed ───────────────────────
    //
    // Trino denies, by default, every SystemAccessControl check an implementation
    // does not override. The guard decides catalog management, impersonation and
    // read-only sessions only; everything below is allowed here and left to the other
    // access-control files in access-control.config-files. Without these overrides an
    // active guard denies the whole cluster (even SELECT 1). BaleiaCatalogGuardTest
    // fails if the SPI gains a check the guard does not override.

    @Override
    public void checkCanExecuteQuery(Identity identity, QueryId queryId)
    {
    }

    @Override
    public void checkCanViewQueryOwnedBy(Identity identity, Identity queryOwner)
    {
    }

    @Override
    public void checkCanKillQueryOwnedBy(Identity identity, Identity queryOwner)
    {
    }

    @Override
    public void checkCanReadSystemInformation(Identity identity)
    {
    }

    @Override
    public void checkCanShowSchemas(SystemSecurityContext context, String catalogName)
    {
    }

    @Override
    public void checkCanShowCreateSchema(SystemSecurityContext context, CatalogSchemaName schema)
    {
    }

    @Override
    public void checkCanShowCreateTable(SystemSecurityContext context, CatalogSchemaTableName table)
    {
    }

    @Override
    public void checkCanShowTables(SystemSecurityContext context, CatalogSchemaName schema)
    {
    }

    @Override
    public void checkCanShowColumns(SystemSecurityContext context, CatalogSchemaTableName table)
    {
    }

    @Override
    public void checkCanSelectFromColumns(SystemSecurityContext context, CatalogSchemaTableName table, Optional<String> column, Set<String> columns)
    {
    }

    @Override
    public void checkCanSelectFromColumns(SystemSecurityContext context, CatalogSchemaTableName table, Set<String> columns)
    {
    }

    @Override
    public void checkCanShowRoles(SystemSecurityContext context)
    {
    }

    @Override
    public void checkCanShowCurrentRoles(SystemSecurityContext context)
    {
    }

    @Override
    public void checkCanShowRoleGrants(SystemSecurityContext context)
    {
    }

    @Override
    public void checkCanShowFunctions(SystemSecurityContext context, CatalogSchemaName schema)
    {
    }

    @Override
    public void checkCanShowCreateFunction(SystemSecurityContext context, CatalogSchemaRoutineName routine)
    {
    }

    @Override
    public void checkCanShowBranches(SystemSecurityContext context, CatalogSchemaTableName table)
    {
    }

    // Visibility and function checks. Their SPI defaults are restrictive too
    // (canAccessCatalog and friends return false, the filter* methods return an empty
    // set), which hides every catalog, schema and table from SHOW and the metadata
    // tables. The guard does not hide anything.

    @Override
    public boolean canAccessCatalog(SystemSecurityContext context, String catalogName)
    {
        return true;
    }

    @Override
    public boolean canExecuteFunction(SystemSecurityContext context, CatalogSchemaRoutineName functionName)
    {
        return true;
    }

    @Override
    public boolean canCreateViewWithExecuteFunction(SystemSecurityContext context, CatalogSchemaRoutineName functionName)
    {
        return true;
    }

    @Override
    public Set<String> filterCatalogs(SystemSecurityContext context, Set<String> catalogs)
    {
        return catalogs;
    }

    @Override
    public Set<String> filterSchemas(SystemSecurityContext context, String catalogName, Set<String> schemaNames)
    {
        return schemaNames;
    }

    @Override
    public Set<SchemaTableName> filterTables(SystemSecurityContext context, String catalogName, Set<SchemaTableName> tableNames)
    {
        return tableNames;
    }

    @Override
    public Set<String> filterColumns(SystemSecurityContext context, CatalogSchemaTableName table, Set<String> columns)
    {
        return columns;
    }

    @Override
    public Map<SchemaTableName, Set<String>> filterColumns(SystemSecurityContext context, String catalogName, Map<SchemaTableName, Set<String>> tableColumns)
    {
        return tableColumns;
    }

    @Override
    public Set<SchemaFunctionName> filterFunctions(SystemSecurityContext context, String catalogName, Set<SchemaFunctionName> functionNames)
    {
        return functionNames;
    }

    @Override
    public Collection<Identity> filterViewQueryOwnedBy(Identity identity, Collection<Identity> queryOwners)
    {
        return queryOwners;
    }

    private void requireAdmin(SystemSecurityContext context, String operation)
    {
        if (!adminUsers.contains(context.getIdentity().getUser())) {
            throw new AccessDeniedException("only Baleia admin users may " + operation);
        }
    }

    private void requireWritable(SystemSecurityContext context, String operation)
    {
        if (isReadOnly(context.getIdentity())) {
            throw new AccessDeniedException("cannot " + operation + ": this session is read-only");
        }
    }

    private static boolean isReadOnly(Identity identity)
    {
        return READ_ONLY_VALUE.equalsIgnoreCase(identity.getExtraCredentials().get(READ_ONLY_CREDENTIAL));
    }
}