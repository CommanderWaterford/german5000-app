#!/usr/bin/env python3
"""Import the ranked German workbook using only Python's standard library.

Workbook cells are data, never executable instructions. No translations, POS tags,
grammar or inflected target forms are inferred from the source.
"""
import argparse
import collections
import copy
import hashlib
import json
import re
import unicodedata
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

NS = {'s': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
ABBREVIATIONS = {'st.', 'mr.', 'dr.', 'ca.', 'etc.', 'bzw.', 'z.b.', 'u.a.', 'h.'}
CORRECTIONS = {'Hund der dog': 'Hund', 'Schaden der': 'Schaden', 'Millie': 'Mühe', 'Tellnehmer': 'Teilnehmer', 'Grttndung': 'Gründung', 'Hbemd': 'Hemd', 'Wiesen': 'Wesen', 'ausdrucken': 'ausdrücken'}
MISSING_EXAMPLES = {'Aktiengesellschaft, AG': ('Die Aktiengesellschaft ist an der Börse notiert.', 'The public limited company is listed on the stock exchange.')}


def canonical(word, rank=None):
    if word == 'Welle' and rank == 1807:
        return 'Weile'
    return CORRECTIONS.get(word, re.sub(r'\s*\(.*\)', '', word).split(',')[0].strip())


def sentences(text):
    """Split examples without cutting ordinal dates, initials or abbreviations."""
    result = []
    for line in text.splitlines():
        start = 0
        for boundary in re.finditer(r'[.!?](?:[»”\"])?(?=\s|$)', line):
            end = boundary.end()
            token = line[:end].split()[-1].lower().strip('»”"')
            if token in ABBREVIATIONS or re.fullmatch(r'\d+\.', token) or re.fullmatch(r'[a-zäöü]\.', token):
                continue
            result.append(line[start:end].strip())
            start = end
        if line[start:].strip():
            result.append(line[start:].strip())
    return [s for s in result if s]


def variants(word):
    return [v.strip() for v in word.split(',') if v.strip() and '(' not in v and ')' not in v]


def target_tokens(word, sentence):
    for variant in variants(word):
        match = re.search(r'(?<!\w)' + re.escape(variant) + r'(?!\w)', sentence, re.IGNORECASE)
        if match:
            return [match.group()]
    return []


def read_rows(path):
    with zipfile.ZipFile(path) as z:
        strings = [''.join(e.itertext()) for e in ET.fromstring(z.read('xl/sharedStrings.xml'))]
        rows = ET.fromstring(z.read('xl/worksheets/sheet1.xml')).findall('.//s:row', NS)
        values = []
        for row in rows:
            cells = {}
            for c in row:
                value = c.find('s:v', NS)
                cells[re.sub(r'\d', '', c.attrib['r'])] = (
                    strings[int(value.text)] if c.attrib.get('t') == 's'
                    else ''.join(c.find('s:is', NS).itertext()) if c.attrib.get('t') == 'inlineStr'
                    else value.text if value is not None else ''
                )
            values.append(cells)
    if [values[0].get(c) for c in 'ABC'] != ['Ranking', 'Word', 'Examples']:
        raise ValueError('Expected Ranking, Word, Examples columns')
    result = []
    for cells in values[1:]:
        rank = int(float(cells['A']))
        word = unicodedata.normalize('NFC', cells['B'].strip())
        example_list = sentences(unicodedata.normalize('NFC', cells.get('C', '')))
        example = next((s for s in example_list if target_tokens(word, s)), example_list[0] if example_list else '')
        result.append((rank, word, example))
    if [r[0] for r in result] != list(range(1, len(result) + 1)):
        raise ValueError('Workbook ranks must be contiguous and unique')
    return result


def import_corpus(workbook, source):
    # This checked-in baseline keeps existing identities and makes reruns deterministic.
    pilot = json.loads((source / 'pilot-entries.json').read_text())['entries']
    rows = read_rows(workbook)
    enrichment_file = json.loads((source / 'corpus-enrichment.json').read_text())
    if enrichment_file['workbookSha256'] != hashlib.sha256(workbook.read_bytes()).hexdigest():
        raise ValueError('Enrichment was prepared for a different workbook; regenerate it first')
    enrichment = enrichment_file['entries']
    used = set()
    entries = []
    occurrences = collections.Counter()
    for rank, word, sentence in rows:
        occurrences[word] += 1
        candidates = [e for e in pilot if e['entryId'] not in used and (e['lemma'] == word or e['lemma'] in variants(word))]
        if candidates:
            entry = copy.deepcopy(candidates[0])
            used.add(entry['entryId'])
            # Keep the bilingual, annotated example with its correct translation.
            entry['examples'] = entry['examples'][:1]
            if word != entry['lemma']:
                entry['aliases'] = list(dict.fromkeys(entry.get('aliases', []) + [word]))
        else:
            identity = hashlib.sha256(f'{word}\0{occurrences[word]}'.encode()).hexdigest()[:16]
            draft = enrichment[str(rank)]
            if draft['sourceWord'] != word:
                raise ValueError(f'Enrichment does not match source rank {rank}')
            lemma = canonical(word, rank)
            entry = {
                'entryId': f'de.corpus.{identity}', 'lemma': lemma, 'pos': draft['pos'],
                'grammar': draft['grammar'], 'senses': [{'glossEn': draft['glossEn']}],
                'examples': [], 'aliases': list(dict.fromkeys([word, *variants(word)])),
                'editorialStatus': 'draft', 'rightsIds': ['german-workbook-pending-review', 'wiktionary-grammar-pending-review', 'machine-translation-pending-review'],
                'assessmentEligible': False, 'corpusDraft': True,
                'notes': f'Imported from german_words.xlsx, rank {rank}. Draft translation and dictionary-derived grammar require linguistic review.',
            }
            if word in CORRECTIONS or (word == 'Welle' and lemma == 'Weile'):
                entry['notes'] += f' Source spelling corrected from “{word}”.'
            if draft.get('sourceUrl'):
                entry['notes'] += f' Grammar source: {draft["sourceUrl"]} (Wiktionary via Kaikki, CC BY-SA).'
            if sentence:
                entry['examples'] = [{'textDe': draft['textDe'], 'textEn': draft['textEn'], 'target': draft['target']}]
                if draft['textDe'] != sentence:
                    entry['notes'] += ' A missing space in the source example was corrected.'
            else:
                sentence, english = MISSING_EXAMPLES[word]
                entry['examples'] = [{'textDe': sentence, 'textEn': english, 'target': ['Aktiengesellschaft']}]
                entry['notes'] += ' The workbook supplies no example; this sentence was added as an AI draft.'
        entries.append(entry)
    # Retain pilot-only identities so existing saved progress continues to resolve.
    for original in pilot:
        if original['entryId'] not in used:
            entry = copy.deepcopy(original)
            entry['examples'] = entry['examples'][:1]
            entries.append(entry)
    # Preserve the independently curated Leipzig expansion on workbook reimports.
    expansion_path = source / 'leipzig-expansion.json'
    if expansion_path.exists():
        additions = json.loads(expansion_path.read_text())['entries']
        known_ids = {entry['entryId'] for entry in entries}
        if any(entry['entryId'] in known_ids for entry in additions):
            raise ValueError('Leipzig expansion overlaps workbook entry identities')
        entries.extend(additions)
    (source / 'entries.json').write_text(json.dumps({'entries': entries}, ensure_ascii=False, indent=2) + '\n')
    (source / 'curriculum.txt').write_text('\n'.join(e['entryId'] for e in entries) + '\n')
    provenance = {
        'source': workbook.name, 'sha256': hashlib.sha256(workbook.read_bytes()).hexdigest(),
        'sourceEntryCount': len(rows), 'entryCount': len(entries),
        'retainedPilotOnlyCount': sum(e['entryId'] not in used for e in pilot),
        'expansionEntryCount': len(entries) - len(rows),
        'examplePolicy': 'One complete source sentence, preferring a literal target match. Existing bilingual examples retained for enriched pilot entries. One absent source example replaced by an explicitly marked AI draft.',
        'rows': [{'rank': rank, 'word': word, 'example': sentence, 'entryId': entry['entryId']} for (rank, word, sentence), entry in zip(rows, entries)],
    }
    (source / 'corpus-import.json').write_text(json.dumps(provenance, ensure_ascii=False, indent=2) + '\n')
    print(f'Imported {len(rows)} ranked rows; {len(entries)} app entries; {sum(bool(e["examples"]) for e in entries)} single examples.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('workbook', type=Path)
    parser.add_argument('--source', type=Path, default=Path(__file__).resolve().parents[2] / 'content/german5000/source')
    args = parser.parse_args()
    import_corpus(args.workbook, args.source)
