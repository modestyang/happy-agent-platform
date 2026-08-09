# Local development Postgres image: the stock Alpine image plus pgvector built
# from source (Alpine 3.24 only packages pgvector for PostgreSQL 18).
FROM postgres:16.14-alpine3.24 AS pgvector-build
RUN apk add --no-cache git build-base postgresql16-dev \
    && git clone --depth 1 --branch v0.8.1 https://github.com/pgvector/pgvector.git /tmp/pgvector \
    && make -C /tmp/pgvector with_llvm=no \
    && make -C /tmp/pgvector with_llvm=no install

FROM postgres:16.14-alpine3.24
COPY --from=pgvector-build /usr/local/lib/postgresql/vector.so /usr/local/lib/postgresql/vector.so
COPY --from=pgvector-build /usr/local/share/postgresql/extension/vector* /usr/local/share/postgresql/extension/
