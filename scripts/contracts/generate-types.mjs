#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const targets = [
  ['docs/architecture/openapi/public-v1.yaml', 'frontend/src/api/generated/public.ts'],
  ['docs/architecture/openapi/admin-v1.yaml', 'frontend/src/api/generated/admin.ts']
];

const propertyName = (name) => /^[A-Za-z_$][A-Za-z0-9_$]*$/.test(name) ? name : JSON.stringify(name);
const indent = (value, spaces) => value.split('\n').map((line) => `${' '.repeat(spaces)}${line}`).join('\n');
const refName = (ref) => decodeURIComponent(ref.split('/').at(-1).replaceAll('~1', '/').replaceAll('~0', '~'));

const schemaType = (schema, context) => {
  if (!schema || typeof schema !== 'object') throw new Error(`${context}: missing schema`);
  if (schema.$ref) return refName(schema.$ref);
  if (Object.hasOwn(schema, 'const')) return JSON.stringify(schema.const);
  if (schema.enum) return schema.enum.map((value) => JSON.stringify(value)).join(' | ');
  if (schema.oneOf) return schema.oneOf.map((item, index) => schemaType(item, `${context}.oneOf[${index}]`)).join(' | ');
  if (schema.anyOf && schema.type !== 'object') return schema.anyOf.map((item, index) => schemaType(item, `${context}.anyOf[${index}]`)).join(' | ');
  if (schema.allOf) return schema.allOf.map((item, index) => schemaType(item, `${context}.allOf[${index}]`)).join(' & ');
  if (Array.isArray(schema.type)) {
    return schema.type
      .map((type) => schemaType({ ...schema, type }, `${context}.${type}`))
      .join(' | ');
  }
  if (schema.type === 'null') return 'null';
  if (schema.type === 'string') return 'string';
  if (schema.type === 'number' || schema.type === 'integer') return 'number';
  if (schema.type === 'boolean') return 'boolean';
  if (schema.type === 'array') return `Array<${schemaType(schema.items, `${context}.items`)}>`;
  if (schema.type === 'object') {
    const required = new Set(schema.required ?? []);
    const fields = Object.entries(schema.properties ?? {}).map(([name, child]) =>
      `${propertyName(name)}${required.has(name) ? '' : '?'}: ${schemaType(child, `${context}.${name}`)};`
    );
    if (fields.length === 0) throw new Error(`${context}: refusing to generate an arbitrary object`);
    const base = `{\n${indent(fields.join('\n'), 2)}\n}`;
    if (!schema.anyOf) return base;
    const requiredAlternatives = schema.anyOf.map((alternative, index) => {
      const names = alternative.required ?? [];
      if (names.length === 0) throw new Error(`${context}.anyOf[${index}]: generator requires at least one required property`);
      const requiredFields = names.map((name) => {
        const child = schema.properties?.[name];
        if (!child) throw new Error(`${context}.anyOf[${index}]: required property ${name} is not declared on the base object`);
        return `${propertyName(name)}: ${schemaType(child, `${context}.${name}`)};`;
      });
      return `{ ${requiredFields.join(' ')} }`;
    });
    return `${base} & (${requiredAlternatives.join(' | ')})`;
  }
  throw new Error(`${context}: unsupported schema shape ${JSON.stringify(schema)}`);
};

const resolveLocal = (document, ref, context) => {
  if (!ref?.startsWith('#/')) throw new Error(`${context}: only local refs are supported`);
  const result = ref.slice(2).split('/').reduce((cursor, part) => cursor?.[part.replaceAll('~1', '/').replaceAll('~0', '~')], document);
  if (!result) throw new Error(`${context}: unresolved ref ${ref}`);
  return result;
};

const dereference = (document, value, context) => value?.$ref ? resolveLocal(document, value.$ref, context) : value;

const schemaDeclaration = (name, schema) => {
  const description = schema.description ? `/** ${schema.description.replaceAll('*/', '* /')} */\n` : '';
  if (schema.type === 'object' && !schema.oneOf && !schema.allOf && !schema.anyOf) {
    const required = new Set(schema.required ?? []);
    const fields = Object.entries(schema.properties ?? {}).map(([property, child]) =>
      `  ${propertyName(property)}${required.has(property) ? '' : '?'}: ${schemaType(child, `${name}.${property}`)};`
    );
    if (fields.length === 0) throw new Error(`${name}: refusing to generate an arbitrary object`);
    return `${description}export interface ${name} {\n${fields.join('\n')}\n}`;
  }
  return `${description}export type ${name} = ${schemaType(schema, name)};`;
};

const parameterGroups = (document, pathItem, operation, context) => {
  const groups = new Map();
  for (const candidate of [...(pathItem.parameters ?? []), ...(operation.parameters ?? [])]) {
    const parameter = dereference(document, candidate, `${context} parameter`);
    const group = groups.get(parameter.in) ?? [];
    group.push(`    ${propertyName(parameter.name)}${parameter.required ? '' : '?'}: ${schemaType(parameter.schema, `${context} ${parameter.name}`)};`);
    groups.set(parameter.in, group);
  }
  if (groups.size === 0) return '  parameters: Record<never, never>;';
  const rendered = [...groups.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([location, fields]) =>
    `    ${propertyName(location)}: {\n${fields.join('\n')}\n    };`
  );
  return `  parameters: {\n${rendered.join('\n')}\n  };`;
};

const responseSchema = (document, response, context) => {
  const resolved = dereference(document, response, context);
  const content = resolved.content ?? {};
  const media = content['application/json'] ?? content['application/problem+json'] ?? content['text/event-stream'];
  return media?.schema ? schemaType(media.schema, `${context} body`) : 'void';
};

const responseHeaders = (document, response, context) => {
  const resolved = dereference(document, response, context);
  const fields = Object.entries(resolved.headers ?? {}).map(([name, candidate]) => {
    const header = dereference(document, candidate, `${context} header ${name}`);
    return `      ${propertyName(name)}: ${schemaType(header.schema, `${context} header ${name}`)};`;
  });
  return fields.length === 0 ? undefined : `{\n${fields.join('\n')}\n    }`;
};

const operationDeclaration = (document, path, method, pathItem, operation) => {
  const context = `${method.toUpperCase()} ${path}`;
  const lines = [
    `${operation.operationId}: {`,
    `  method: ${JSON.stringify(method.toUpperCase())};`,
    `  path: ${JSON.stringify(path)};`,
    parameterGroups(document, pathItem, operation, context)
  ];
  if (operation.requestBody) {
    const body = dereference(document, operation.requestBody, `${context} requestBody`);
    const media = body.content?.['application/json'];
    if (!media?.schema) throw new Error(`${context}: application/json request schema is required`);
    lines.push(`  requestBody${body.required ? '' : '?'}: ${schemaType(media.schema, `${context} requestBody`)};`);
  }
  const responseLines = Object.entries(operation.responses ?? {}).map(([status, response]) =>
    `    ${propertyName(status)}: ${responseSchema(document, response, `${context} ${status}`)};`
  );
  lines.push(`  responses: {\n${responseLines.join('\n')}\n  };`);
  const headers = Object.entries(operation.responses ?? {}).map(([status, response]) => {
    const rendered = responseHeaders(document, response, `${context} ${status}`);
    return rendered ? `    ${propertyName(status)}: ${rendered};` : undefined;
  }).filter(Boolean);
  if (headers.length > 0) lines.push(`  responseHeaders: {\n${headers.join('\n')}\n  };`);
  lines.push('}');
  return lines.join('\n');
};

const generate = (document, sourcePath, digest) => {
  const schemaEntries = Object.entries(document.components?.schemas ?? {}).sort(([a], [b]) => a.localeCompare(b));
  const operationEntries = [];
  const pathEntries = [];
  for (const [path, pathItem] of Object.entries(document.paths ?? {}).sort(([a], [b]) => a.localeCompare(b))) {
    const methods = [];
    for (const method of ['get', 'post', 'put', 'patch', 'delete']) {
      const operation = pathItem[method];
      if (!operation) continue;
      operationEntries.push([operation.operationId, operationDeclaration(document, path, method, pathItem, operation)]);
      methods.push(`    ${method}: Operations[${JSON.stringify(operation.operationId)}];`);
    }
    pathEntries.push(`  ${JSON.stringify(path)}: {\n${methods.join('\n')}\n  };`);
  }
  operationEntries.sort(([a], [b]) => a.localeCompare(b));
  return `/**
 * Generated by scripts/contracts/generate-types.mjs from ${sourcePath}.
 * Source SHA-256: ${digest}
 * Do not edit by hand.
 */

${schemaEntries.map(([name, schema]) => schemaDeclaration(name, schema)).join('\n\n')}

export interface Operations {
${operationEntries.map(([, declaration]) => indent(declaration, 2)).join('\n\n')}
}

export interface Paths {
${pathEntries.join('\n')}
}
`;
};

for (const [sourcePath, targetPath] of targets) {
  const source = await readFile(resolve(root, sourcePath), 'utf8');
  const document = JSON.parse(source);
  const digest = createHash('sha256').update(source).digest('hex');
  const output = generate(document, sourcePath, digest);
  const absoluteTarget = resolve(root, targetPath);
  await mkdir(dirname(absoluteTarget), { recursive: true });
  await writeFile(absoluteTarget, output, 'utf8');
  console.log(`generated ${targetPath} (${Object.keys(document.components.schemas).length} schemas)`);
}
