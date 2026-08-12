ALTER SCHEMA fitness OWNER TO fitness_app;
ALTER SCHEMA agent OWNER TO agent_app;

REVOKE ALL ON DATABASE happy_agent FROM PUBLIC;
GRANT CONNECT ON DATABASE happy_agent TO fitness_app;
GRANT CONNECT ON DATABASE happy_agent TO agent_app;

REVOKE ALL ON SCHEMA fitness FROM PUBLIC;
REVOKE ALL ON SCHEMA fitness FROM agent_app;
REVOKE ALL ON SCHEMA agent FROM PUBLIC;
REVOKE ALL ON SCHEMA agent FROM fitness_app;
GRANT USAGE, CREATE ON SCHEMA fitness TO fitness_app;
GRANT USAGE, CREATE ON SCHEMA agent TO agent_app;

DO $$
DECLARE
    relation_record record;
    function_record record;
BEGIN
    FOR relation_record IN
        SELECT n.nspname, c.relname, c.relkind
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname IN ('fitness', 'agent')
          AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
    LOOP
        EXECUTE format(
            'ALTER %s %I.%I OWNER TO %I',
            CASE relation_record.relkind WHEN 'S' THEN 'SEQUENCE' ELSE 'TABLE' END,
            relation_record.nspname,
            relation_record.relname,
            relation_record.nspname || '_app');
    END LOOP;

    FOR function_record IN
        SELECT p.oid::regprocedure AS identity, n.nspname
        FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname IN ('fitness', 'agent')
    LOOP
        EXECUTE format(
            'ALTER FUNCTION %s OWNER TO %I', function_record.identity, function_record.nspname || '_app');
    END LOOP;
END
$$;

REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA fitness FROM PUBLIC, agent_app;
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA fitness FROM PUBLIC, agent_app;
REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA fitness FROM PUBLIC, agent_app;
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA agent FROM PUBLIC, fitness_app;
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA agent FROM PUBLIC, fitness_app;
REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA agent FROM PUBLIC, fitness_app;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA fitness TO fitness_app;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA fitness TO fitness_app;
GRANT ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA fitness TO fitness_app;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA agent TO agent_app;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA agent TO agent_app;
GRANT ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA agent TO agent_app;

ALTER ROLE fitness_app IN DATABASE happy_agent SET search_path TO fitness, public;
ALTER ROLE agent_app IN DATABASE happy_agent SET search_path TO agent, public;

ALTER DEFAULT PRIVILEGES FOR ROLE fitness_app IN SCHEMA fitness REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE fitness_app IN SCHEMA fitness REVOKE ALL ON TABLES FROM agent_app;
ALTER DEFAULT PRIVILEGES FOR ROLE fitness_app IN SCHEMA fitness REVOKE ALL ON SEQUENCES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE fitness_app IN SCHEMA fitness REVOKE ALL ON SEQUENCES FROM agent_app;
ALTER DEFAULT PRIVILEGES FOR ROLE fitness_app IN SCHEMA fitness REVOKE ALL ON FUNCTIONS FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE fitness_app IN SCHEMA fitness REVOKE ALL ON FUNCTIONS FROM agent_app;
ALTER DEFAULT PRIVILEGES FOR ROLE fitness_app IN SCHEMA fitness GRANT ALL ON TABLES TO fitness_app;
ALTER DEFAULT PRIVILEGES FOR ROLE fitness_app IN SCHEMA fitness GRANT ALL ON SEQUENCES TO fitness_app;
ALTER DEFAULT PRIVILEGES FOR ROLE fitness_app IN SCHEMA fitness GRANT ALL ON FUNCTIONS TO fitness_app;

ALTER DEFAULT PRIVILEGES FOR ROLE agent_app IN SCHEMA agent REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE agent_app IN SCHEMA agent REVOKE ALL ON TABLES FROM fitness_app;
ALTER DEFAULT PRIVILEGES FOR ROLE agent_app IN SCHEMA agent REVOKE ALL ON SEQUENCES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE agent_app IN SCHEMA agent REVOKE ALL ON SEQUENCES FROM fitness_app;
ALTER DEFAULT PRIVILEGES FOR ROLE agent_app IN SCHEMA agent REVOKE ALL ON FUNCTIONS FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE agent_app IN SCHEMA agent REVOKE ALL ON FUNCTIONS FROM fitness_app;
ALTER DEFAULT PRIVILEGES FOR ROLE agent_app IN SCHEMA agent GRANT ALL ON TABLES TO agent_app;
ALTER DEFAULT PRIVILEGES FOR ROLE agent_app IN SCHEMA agent GRANT ALL ON SEQUENCES TO agent_app;
ALTER DEFAULT PRIVILEGES FOR ROLE agent_app IN SCHEMA agent GRANT ALL ON FUNCTIONS TO agent_app;
