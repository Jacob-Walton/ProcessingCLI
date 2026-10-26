// Builds src/main/resources/org/konpeki/reference.json from the Processing website's reference,
// used for hover docs in the language server.
//
//   git clone --depth 1 --filter=blob:none --sparse https://github.com/processing/processing-website.git
//   git -C processing-website sparse-checkout set content/references/translations/en/processing
//   node scripts/reference.mjs processing-website/content/references/translations/en/processing
//
// The reference is CC BY-NC-SA 4.0, see src/main/resources/org/konpeki/reference-LICENSE.txt

import { readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const [source] = process.argv.slice(2);
const output = new URL('../src/main/resources/org/konpeki/reference.json', import.meta.url);

const markdown = (html = '') => html
	.replace(/<br\s*\/?>/gi, '\n')
	.replace(/<\/?(b|strong)>/gi, '**')
	.replace(/<\/?(i|em)>/gi, '*')
	.replace(/<\/?(code|tt|kbd)>/gi, '`')
	.replace(/<a [^>]*>(.*?)<\/a>/gi, '$1')
	.replace(/<[^>]+>/g, '')
	.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&')
	.replace(/[ \t]*\n[ \t]*/g, '\n')
	.replace(/([^\n])\n(?!\n)/g, '$1 ')
	.replace(/\n{3,}/g, '\n\n')
	.trim();

const reference = {};
for (const file of readdirSync(source).filter((f) => f.endsWith('.json')).sort()) {
	const entry = JSON.parse(readFileSync(join(source, file), 'utf8'));
	reference[file.slice(0, -'.json'.length)] = {
		name: entry.name,
		description: markdown(entry.description || entry.brief),
		syntax: entry.syntax ?? entry.constructors ?? [],
		parameters: (entry.parameters ?? []).map((p) => ({ name: p.name, description: markdown(p.description) })),
		returns: entry.returns ?? '',
	};
}

writeFileSync(output, JSON.stringify(reference));
console.log(`${Object.keys(reference).length} entries`);
