SELECT format('CREATE ROLE fitness_app LOGIN PASSWORD %L NOINHERIT', :'fitness_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fitness_app')
\gexec

SELECT format('CREATE ROLE agent_app LOGIN PASSWORD %L NOINHERIT', :'agent_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agent_app')
\gexec

SELECT format('ALTER ROLE fitness_app PASSWORD %L', :'fitness_password')
\gexec

SELECT format('ALTER ROLE agent_app PASSWORD %L', :'agent_password')
\gexec

ALTER ROLE fitness_app NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOINHERIT;
ALTER ROLE agent_app NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOINHERIT;

REVOKE ALL ON DATABASE happy_agent FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM PUBLIC, fitness_app, agent_app;

CREATE SCHEMA IF NOT EXISTS fitness AUTHORIZATION fitness_app;
CREATE SCHEMA IF NOT EXISTS agent AUTHORIZATION agent_app;
ALTER SCHEMA fitness OWNER TO fitness_app;
ALTER SCHEMA agent OWNER TO agent_app;

REVOKE ALL ON SCHEMA fitness FROM PUBLIC, agent_app;
REVOKE ALL ON SCHEMA agent FROM PUBLIC, fitness_app;
GRANT CONNECT ON DATABASE happy_agent TO fitness_app, agent_app;
GRANT USAGE, CREATE ON SCHEMA fitness TO fitness_app;
GRANT USAGE, CREATE ON SCHEMA agent TO agent_app;

ALTER ROLE fitness_app IN DATABASE happy_agent SET search_path = fitness;
ALTER ROLE agent_app IN DATABASE happy_agent SET search_path = agent;

ALTER DEFAULT PRIVILEGES FOR ROLE fitness_app IN SCHEMA fitness REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE agent_app IN SCHEMA agent REVOKE ALL ON TABLES FROM PUBLIC;
