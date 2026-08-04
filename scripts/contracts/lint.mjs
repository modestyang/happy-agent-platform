#!/usr/bin/env node

import { readFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const fixtureDir = resolve(root, 'scripts/contracts/fixtures');
const failures = [];

const loadJson = async (path, label) => {
  try {
    return JSON.parse(await readFile(path, 'utf8'));
  } catch (error) {
    failures.push(`${label}: ${error.code === 'ENOENT' ? 'file does not exist' : error.message}`);
    return undefined;
  }
};

const fixtures = await Promise.all([
  loadJson(resolve(fixtureDir, 'public-coverage.json'), 'public fixture'),
  loadJson(resolve(fixtureDir, 'admin-coverage.json'), 'admin fixture')
]);
const requirements = await loadJson(resolve(fixtureDir, 'schema-requirements.json'), 'schema requirements');
const semanticInvariants = await loadJson(resolve(fixtureDir, 'semantic-invariants.json'), 'semantic invariants');

const resolveRef = (document, ref, context) => {
  if (typeof ref !== 'string' || !ref.startsWith('#/')) {
    failures.push(`${context}: only local component refs are allowed`);
    return undefined;
  }
  const value = ref.slice(2).split('/').reduce((cursor, part) => cursor?.[part.replaceAll('~1', '/').replaceAll('~0', '~')], document);
  if (!value) failures.push(`${context}: unresolved ref ${ref}`);
  return value;
};

const responseSchemaName = (response, contentType = 'application/json') => {
  const ref = response?.content?.[contentType]?.schema?.$ref;
  return typeof ref === 'string' ? ref.split('/').at(-1) : undefined;
};

const parametersFor = (document, pathItem, operation) => [...(pathItem.parameters ?? []), ...(operation.parameters ?? [])]
  .map((parameter) => parameter.$ref ? resolveRef(document, parameter.$ref, 'parameter') : parameter)
  .filter(Boolean);

const assertProblemResponse = (document, response, context) => {
  const resolved = response?.$ref ? resolveRef(document, response.$ref, context) : response;
  const schema = resolved?.content?.['application/problem+json']?.schema;
  if (schema?.$ref !== '#/components/schemas/Problem') failures.push(`${context}: must use application/problem+json with Problem`);
};

const inspectSchema = (document, schema, context, seen = new Set()) => {
  if (!schema || typeof schema !== 'object') {
    failures.push(`${context}: schema must be a non-empty object`);
    return;
  }
  if (Object.keys(schema).length === 0) failures.push(`${context}: empty schemas are forbidden`);
  if (schema.$ref) {
    if (!seen.has(schema.$ref)) {
      const next = new Set(seen).add(schema.$ref);
      const target = resolveRef(document, schema.$ref, context);
      if (target) inspectSchema(document, target, schema.$ref, next);
    }
    return;
  }
  if (schema.type === 'object') {
    if (schema.additionalProperties !== false) failures.push(`${context}: object schemas must set additionalProperties=false`);
    if (!schema.properties || Object.keys(schema.properties).length === 0) failures.push(`${context}: arbitrary/empty objects are forbidden`);
    for (const [name, child] of Object.entries(schema.properties ?? {})) inspectSchema(document, child, `${context}.${name}`, seen);
  }
  if (schema.type === 'array') {
    if (!schema.items) failures.push(`${context}: arrays must declare items`);
    else inspectSchema(document, schema.items, `${context}[]`, seen);
  }
  for (const keyword of ['allOf', 'oneOf', 'anyOf']) {
    for (const [index, child] of (schema[keyword] ?? []).entries()) inspectSchema(document, child, `${context}.${keyword}[${index}]`, seen);
  }
  if (schema.additionalProperties && schema.additionalProperties !== false) failures.push(`${context}: free-form maps are forbidden`);
};

for (const fixture of fixtures.filter(Boolean)) {
  const documentPath = resolve(root, fixture.document);
  const document = await loadJson(documentPath, `${fixture.service} OpenAPI`);
  if (!document) continue;
  if (document.openapi !== '3.1.0') failures.push(`${fixture.service}: OpenAPI version must be 3.1.0`);
  if (document.jsonSchemaDialect !== 'https://json-schema.org/draft/2020-12/schema') failures.push(`${fixture.service}: JSON Schema dialect must be explicit`);
  if (!document.components?.securitySchemes?.bearerAuth) failures.push(`${fixture.service}: bearerAuth security scheme is required`);
  const declaredProblemCodes = new Set(document.components?.schemas?.ProblemCode?.enum ?? []);

  const operationIds = new Set();
  for (const [path, pathItem] of Object.entries(document.paths ?? {})) {
    for (const method of ['get', 'post', 'put', 'patch', 'delete']) {
      const operation = pathItem[method];
      if (!operation) continue;
      const context = `${method.toUpperCase()} ${path}`;
      if (!operation.operationId) failures.push(`${context}: operationId is required`);
      else if (operationIds.has(operation.operationId)) failures.push(`${context}: duplicate operationId ${operation.operationId}`);
      else operationIds.add(operation.operationId);
      if (!operation.summary || !operation.description) failures.push(`${context}: summary and description are required`);
      if (!Array.isArray(operation.security) && !Array.isArray(document.security)) failures.push(`${context}: security is required`);
      if (!Array.isArray(operation['x-error-codes']) || operation['x-error-codes'].length === 0) failures.push(`${context}: x-error-codes must close error semantics`);
      for (const code of operation['x-error-codes'] ?? []) if (!declaredProblemCodes.has(code)) failures.push(`${context}: x-error-codes contains undeclared ${code}`);
      for (const [status, response] of Object.entries(operation.responses ?? {})) {
        if (status === 'default' || /^[45]/.test(status)) assertProblemResponse(document, response, `${context} ${status}`);
      }
      if (!operation.responses?.default) failures.push(`${context}: default Problem response is required`);
      for (const status of ['401', '403']) if (!operation.responses?.[status]) failures.push(`${context}: authenticated operations must document ${status}`);
      if (path.includes('{') && !operation.responses?.['404']) failures.push(`${context}: resource paths must document 404`);

      if (['post', 'put', 'patch', 'delete'].includes(method)) {
        const parameters = parametersFor(document, pathItem, operation);
        if (!parameters.some((parameter) => parameter.in === 'header' && parameter.name === 'Idempotency-Key' && parameter.required === true)) {
          failures.push(`${context}: required Idempotency-Key header is missing`);
        }
        if (!operation.requestBody) failures.push(`${context}: write operations require an explicit request body`);
        for (const status of ['400', '409', '422']) if (!operation.responses?.[status]) failures.push(`${context}: write operations must document ${status}`);
        const requestBody = operation.requestBody?.$ref ? resolveRef(document, operation.requestBody.$ref, `${context} request body`) : operation.requestBody;
        if (!requestBody?.content?.['application/json']?.schema) failures.push(`${context}: write request body must define an application/json schema`);
      }
      if (operation['x-optimistic-concurrency'] === true) {
        const parameters = parametersFor(document, pathItem, operation);
        if (!parameters.some((parameter) => parameter.in === 'header' && parameter.name === 'If-Match' && parameter.required === true)) failures.push(`${context}: required If-Match header is missing`);
        for (const status of ['412', '428']) if (!operation.responses?.[status]) failures.push(`${context}: optimistic update must document ${status}`);
      }
      if (operation['x-asynchronous'] === true) {
        const response = operation.responses?.['202'];
        const resolved = response?.$ref ? resolveRef(document, response.$ref, `${context} 202`) : response;
        if (!resolved) failures.push(`${context}: asynchronous creation must return 202`);
        if (!resolved?.headers?.Location) failures.push(`${context}: 202 response must include Location`);
        for (const status of ['429', '503']) if (!operation.responses?.[status]) failures.push(`${context}: asynchronous creation must document ${status}`);
      }
    }
  }

  for (const expected of fixture.operations) {
    const pathItem = document.paths?.[expected.path];
    const operation = pathItem?.[expected.method];
    const context = `${expected.method.toUpperCase()} ${expected.path}`;
    if (!operation) {
      failures.push(`${fixture.service}: missing ${context}`);
      continue;
    }
    if (operation.operationId !== expected.operationId) failures.push(`${context}: expected operationId ${expected.operationId}`);
    const success = operation.responses?.[expected.success];
    const resolvedSuccess = success?.$ref ? resolveRef(document, success.$ref, `${context} ${expected.success}`) : success;
    const mediaType = expected.sse ? 'text/event-stream' : 'application/json';
    if (responseSchemaName(resolvedSuccess, mediaType) !== expected.schema) failures.push(`${context}: ${expected.success} must return ${expected.schema}`);
    if (Boolean(operation['x-optimistic-concurrency']) !== Boolean(expected.optimistic)) failures.push(`${context}: optimistic concurrency marker mismatch`);
    if (Boolean(operation['x-asynchronous']) !== Boolean(expected.async)) failures.push(`${context}: asynchronous marker mismatch`);
    if (expected.sse) {
      const parameters = parametersFor(document, pathItem, operation);
      if (!parameters.some((parameter) => parameter.in === 'header' && parameter.name === 'Last-Event-ID')) failures.push(`${context}: SSE reconnect cursor Last-Event-ID is missing`);
    }
    for (const parameterName of expected.queryParameters ?? []) {
      const parameters = parametersFor(document, pathItem, operation);
      if (!parameters.some((parameter) => parameter.in === 'query' && parameter.name === parameterName)) failures.push(`${context}: required query parameter ${parameterName} is missing`);
    }
  }

  const expectedOperationIds = new Set(fixture.operations.map((operation) => operation.operationId));
  for (const operationId of operationIds) if (!expectedOperationIds.has(operationId)) failures.push(`${fixture.service}: operation ${operationId} exists in OpenAPI but is absent from the coverage fixture`);

  const schemas = document.components?.schemas ?? {};
  for (const name of requirements?.[fixture.service] ?? []) if (!schemas[name]) failures.push(`${fixture.service}: required schema ${name} is missing`);
  for (const [name, schema] of Object.entries(schemas)) inspectSchema(document, schema, `#/components/schemas/${name}`);
  const codes = schemas.ProblemCode?.enum ?? [];
  for (const code of requirements?.problemCodes ?? []) if (!codes.includes(code)) failures.push(`${fixture.service}: ProblemCode is missing ${code}`);
  if (fixture.service === 'admin') for (const schema of requirements?.componentCatalogs ?? []) if (!schemas[schema]) failures.push(`admin: typed component schema ${schema} is missing`);

  const invariants = semanticInvariants?.[fixture.service] ?? {};
  for (const [schemaName, choices] of Object.entries(invariants.anyOfRequiredChoices ?? {})) {
    const actual = (schemas[schemaName]?.anyOf ?? []).map((choice) => choice.required?.[0]).filter(Boolean);
    for (const choice of choices) if (!actual.includes(choice)) failures.push(`${fixture.service}: ${schemaName} must require ${choice} in an anyOf branch`);
  }
  for (const [schemaName, invariant] of Object.entries(invariants.discriminatedUnions ?? {})) {
    const branches = schemas[schemaName]?.oneOf ?? [];
    const actual = branches.map((branch, index) => {
      const resolved = branch.$ref ? resolveRef(document, branch.$ref, `${schemaName}.oneOf[${index}]`) : branch;
      return resolved?.properties?.[invariant.property]?.const;
    }).filter(Boolean);
    for (const value of invariant.values) if (!actual.includes(value)) failures.push(`${fixture.service}: ${schemaName} is missing discriminated ${invariant.property}=${value}`);
  }
  for (const [schemaName, properties] of Object.entries(invariants.requiredProperties ?? {})) {
    const schema = schemas[schemaName];
    for (const property of properties) {
      if (!schema?.properties?.[property]) failures.push(`${fixture.service}: ${schemaName} property ${property} is missing`);
      if (schemaName !== 'AgentDraftPatchRequest' && schemaName !== 'SkillPatchRequest' && !(schema?.required ?? []).includes(property)) failures.push(`${fixture.service}: ${schemaName} must require ${property}`);
    }
  }
  for (const [path, minimum] of Object.entries(invariants.arrayMinimums ?? {})) {
    const [schemaName, property] = path.split('.');
    if (schemas[schemaName]?.properties?.[property]?.minItems !== minimum) failures.push(`${fixture.service}: ${path} must set minItems=${minimum}`);
  }
}

if (failures.length > 0) {
  console.error(`Contract lint failed with ${failures.length} error(s):`);
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log(`Contract lint passed: ${fixtures.reduce((count, fixture) => count + (fixture?.operations.length ?? 0), 0)} fixture operations validated.`);
