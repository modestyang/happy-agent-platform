DO $$
DECLARE
    application_roles text[];
BEGIN
    SELECT array_agg(role_name ORDER BY role_name)
    INTO application_roles
    FROM (
        SELECT rolname AS role_name
        FROM pg_roles
        WHERE rolname !~ '^pg_'
          AND rolname <> 'postgres'
    ) roles;

    IF application_roles IS DISTINCT FROM ARRAY['agent_app', 'fitness_app']::text[] THEN
        RAISE EXCEPTION 'unexpected non-system role set';
    END IF;

    IF (
        SELECT count(*)
        FROM pg_roles
        WHERE rolname IN ('fitness_app', 'agent_app')
          AND rolcanlogin
          AND NOT rolinherit
          AND NOT rolsuper
          AND NOT rolcreatedb
          AND NOT rolcreaterole
          AND NOT rolreplication
          AND NOT rolbypassrls
    ) <> 2 THEN
        RAISE EXCEPTION 'application role attributes do not match the initial baseline';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_auth_members membership
        JOIN pg_roles granted_role ON granted_role.oid = membership.roleid
        JOIN pg_roles member_role ON member_role.oid = membership.member
        WHERE granted_role.rolname IN ('fitness_app', 'agent_app')
           OR member_role.rolname IN ('fitness_app', 'agent_app')
    ) THEN
        RAISE EXCEPTION 'application role membership exists';
    END IF;

    IF has_database_privilege('public', current_database(), 'CONNECT')
        OR has_database_privilege('public', current_database(), 'CREATE')
        OR has_database_privilege('public', current_database(), 'TEMPORARY') THEN
        RAISE EXCEPTION 'PUBLIC database privilege exists';
    END IF;

    IF NOT has_database_privilege('fitness_app', current_database(), 'CONNECT')
        OR has_database_privilege('fitness_app', current_database(), 'CREATE')
        OR has_database_privilege('fitness_app', current_database(), 'TEMPORARY')
        OR NOT has_database_privilege('agent_app', current_database(), 'CONNECT')
        OR has_database_privilege('agent_app', current_database(), 'CREATE')
        OR has_database_privilege('agent_app', current_database(), 'TEMPORARY') THEN
        RAISE EXCEPTION 'application database privilege differs from CONNECT only';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_database database_entry
        CROSS JOIN LATERAL aclexplode(database_entry.datacl) privilege
        LEFT JOIN pg_roles grantee ON grantee.oid = privilege.grantee
        WHERE database_entry.datname = current_database()
          AND NOT (
              privilege.grantee = database_entry.datdba
              OR (
                  grantee.rolname IN ('fitness_app', 'agent_app')
                  AND privilege.privilege_type = 'CONNECT'
                  AND NOT privilege.is_grantable
              )
          )
    ) THEN
        RAISE EXCEPTION 'unexpected database ACL entry exists';
    END IF;

    IF has_schema_privilege('public', 'public', 'USAGE')
        OR has_schema_privilege('public', 'public', 'CREATE')
        OR has_schema_privilege('fitness_app', 'public', 'USAGE')
        OR has_schema_privilege('fitness_app', 'public', 'CREATE')
        OR has_schema_privilege('agent_app', 'public', 'USAGE')
        OR has_schema_privilege('agent_app', 'public', 'CREATE') THEN
        RAISE EXCEPTION 'unexpected public schema privilege exists';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_namespace namespace_entry
        CROSS JOIN LATERAL aclexplode(namespace_entry.nspacl) privilege
        WHERE namespace_entry.nspname = 'public'
          AND privilege.grantee <> namespace_entry.nspowner
    ) THEN
        RAISE EXCEPTION 'unexpected public schema ACL entry exists';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_namespace
        WHERE nspname !~ '^pg_'
          AND nspname NOT IN ('information_schema', 'public')
    ) THEN
        RAISE EXCEPTION 'unexpected application schema exists';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_depend namespace_dependency
        WHERE namespace_dependency.refclassid = 'pg_namespace'::regclass
          AND namespace_dependency.deptype = 'n'
          AND namespace_dependency.objid >= 16384
    ) THEN
        RAISE EXCEPTION 'unexpected namespace-scoped user object exists';
    END IF;

    IF EXISTS (SELECT 1 FROM pg_cast WHERE oid >= 16384)
        OR EXISTS (SELECT 1 FROM pg_transform WHERE oid >= 16384)
        OR EXISTS (SELECT 1 FROM pg_event_trigger WHERE oid >= 16384)
        OR EXISTS (SELECT 1 FROM pg_publication WHERE oid >= 16384)
        OR EXISTS (SELECT 1 FROM pg_subscription WHERE oid >= 16384)
        OR EXISTS (SELECT 1 FROM pg_policy WHERE oid >= 16384)
        OR EXISTS (SELECT 1 FROM pg_rewrite WHERE oid >= 16384)
        OR EXISTS (SELECT 1 FROM pg_trigger WHERE oid >= 16384)
        OR EXISTS (SELECT 1 FROM pg_foreign_data_wrapper WHERE oid >= 16384)
        OR EXISTS (SELECT 1 FROM pg_foreign_server WHERE oid >= 16384)
        OR EXISTS (SELECT 1 FROM pg_user_mapping WHERE oid >= 16384)
        OR EXISTS (SELECT 1 FROM pg_largeobject_metadata WHERE oid >= 16384)
        OR EXISTS (SELECT 1 FROM pg_am WHERE oid >= 16384)
        OR EXISTS (
            SELECT 1
            FROM pg_language
            WHERE oid >= 16384
              AND lanname <> 'plpgsql'
        )
        OR EXISTS (SELECT 1 FROM pg_parameter_acl WHERE oid >= 16384) THEN
        RAISE EXCEPTION 'unexpected database-scoped user object exists';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_default_acl default_acl
        JOIN pg_roles owner_role ON owner_role.oid = default_acl.defaclrole
        WHERE owner_role.rolname !~ '^pg_'
    ) THEN
        RAISE EXCEPTION 'unexpected application default ACL exists';
    END IF;

    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname <> 'plpgsql') THEN
        RAISE EXCEPTION 'unexpected extension exists';
    END IF;
END
$$;

SELECT 'HAPPY_AGENT_INITIAL_TARGET_EMPTY';
