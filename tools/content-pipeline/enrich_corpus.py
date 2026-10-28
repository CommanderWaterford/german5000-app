#!/usr/bin/env python3
"""Prepare reviewable draft enrichment from cached translations and Wiktextract records.

Inputs are data files, never executable workbook instructions. This command does not
contact external services. Keep the resulting corpus-enrichment.json in source control.
"""
import argparse
import collections
import hashlib
import json
import re
from pathlib import Path
from urllib.parse import quote
from import_corpus import CORRECTIONS, canonical, read_rows, target_tokens

POS = {'noun': 'NOUN', 'name': 'NOUN', 'verb': 'VERB', 'adj': 'ADJECTIVE', 'adv': 'ADVERB', 'prep': 'PREPOSITION', 'pron': 'PRONOUN', 'det': 'DETERMINER', 'article': 'ARTICLE', 'conj': 'CONJUNCTION', 'num': 'NUMERAL', 'particle': 'PARTICLE', 'intj': 'INTERJECTION', 'suffix': 'SUFFIX'}
CASES = {'accusative', 'dative', 'genitive'}
GLOSS_OVERRIDES = {'Hund': 'dog', 'Schaden': 'damage', 'Mühe': 'effort; trouble', 'Teilnehmer': 'participant', 'Gründung': 'founding; establishment', 'Hemd': 'shirt', 'Wesen': 'essence; nature; being', 'ausdrücken': 'to express'}
TARGET_OVERRIDES = {
    'dies': ['Diese'], 'jede': ['jedes'], 'welch': ['Welche'], 'beste': ['besten'],
    'meiste': ['meisten'], 'rechte': ['rechten'], 'linke': ['linken'],
    'beruflich': ['berufliche'], 'obere': ['oberen'], 'schreien': ['geschrieen'],
    'begrenzen': ['begrenztes'], 'weiblich': ['weibliche'], 'Mister': ['Mr.'],
    'Nationalsozialist': ['Nazis'], 'selbe': ['selben'], 'Dunkel': ['Dunkeln'],
    'medial': ['medialem'], 'fachlich': ['fachlichen'], 'jegliche': ['jeglichen'],
}
PREPOSITIONS = {
    **dict.fromkeys(['zu', 'von', 'nach', 'gegenüber', 'nahe', 'ab', 'zufolge', 'samt'], 'dative'),
    **dict.fromkeys(['um', 'durch', 'gegen', 'pro', 'per'], 'accusative'),
    **dict.fromkeys(['an', 'über', 'vor', 'unter', 'zwischen', 'neben', 'hinter'], 'accusative|dative'),
    **dict.fromkeys(['wegen', 'innerhalb', 'aufgrund', 'trotz', 'außerhalb', 'angesichts', 'laut', 'mithilfe', 'anhand', 'mittels', 'hinsichtlich', 'bezüglich', 'einschließlich', 'jenseits', 'zugunsten', 'infolge', 'anstelle', 'seitens'], 'genitive'),
}
MANUAL = {
    'HGB': ('NOUN', 'Commercial Code', {'gender': 'neuter', 'article': 'das', 'singular': 'HGB', 'plural': 'not_applicable', 'pluralStatus': 'no_plural', 'weakNoun': 'false'}),
    'TV': ('NOUN', 'television', {'gender': 'neuter', 'article': 'das', 'singular': 'TV', 'plural': 'not_applicable', 'pluralStatus': 'no_plural', 'weakNoun': 'false'}),
    'Mister': ('NOUN', 'Mr.', {'gender': 'masculine', 'article': 'der', 'singular': 'Mister', 'plural': 'Misters', 'pluralStatus': 'has_plural', 'weakNoun': 'false'}),
    'BSE': ('NOUN', 'BSE (bovine spongiform encephalopathy)', {'gender': 'feminine', 'article': 'die', 'singular': 'BSE', 'plural': 'not_applicable', 'pluralStatus': 'no_plural', 'weakNoun': 'false'}),
}
NOUN_OVERRIDES = {
    'Medien': {'gender': 'plural_only', 'article': 'die', 'singular': 'not_applicable', 'plural': 'Medien', 'pluralStatus': 'plural_only'},
    'Sonne': {'plural': 'Sonnen', 'pluralStatus': 'has_plural'},
    'Franken': {'gender': 'masculine', 'article': 'der', 'plural': 'Franken', 'pluralStatus': 'has_plural'},
    'Bundeswehr': {'plural': 'not_applicable', 'pluralStatus': 'no_plural'},
    'Aufwendungen': {'gender': 'plural_only', 'article': 'die', 'singular': 'not_applicable', 'plural': 'Aufwendungen', 'pluralStatus': 'plural_only'},
    'Stasi': {'gender': 'feminine', 'article': 'die', 'plural': 'not_applicable', 'pluralStatus': 'no_plural'},
    'Schulden': {'gender': 'plural_only', 'article': 'die', 'singular': 'not_applicable', 'plural': 'Schulden', 'pluralStatus': 'plural_only'},
    'Mittelalter': {'gender': 'neuter', 'article': 'das', 'plural': 'not_applicable', 'pluralStatus': 'no_plural'},
    'Unterlagen': {'gender': 'plural_only', 'article': 'die', 'singular': 'not_applicable', 'plural': 'Unterlagen', 'pluralStatus': 'plural_only'},
    'Olympia': {'plural': 'not_applicable', 'pluralStatus': 'no_plural'},
    'Islam': {'plural': 'not_applicable', 'pluralStatus': 'no_plural'},
    'Fachleute': {'gender': 'plural_only', 'article': 'die', 'singular': 'not_applicable', 'plural': 'Fachleute', 'pluralStatus': 'plural_only'},
    'Fakten': {'gender': 'plural_only', 'article': 'die', 'singular': 'not_applicable', 'plural': 'Fakten', 'pluralStatus': 'plural_only'},
}


def forms(record, *tags):
    required = set(tags)
    return [f['form'] for f in record.get('forms', []) if required <= set(f.get('tags', [])) and not (set(f.get('tags', [])) & {'error-unrecognized-form', 'archaic', 'obsolete', 'multiword-construction', 'includes-article'}) and f['form'] not in {'—', '-', '?'}]


def grammar(record, lemma, source_word):
    pos = POS.get(record.get('pos'), 'UNSPECIFIED')
    senses = record.get('senses', [])
    tags = set(record.get('tags', [])) | {t for sense in senses for t in sense.get('tags', [])}
    heads = ' '.join(t.get('expansion', '') for t in record.get('head_templates', []))
    g = {}
    def first(key, *required):
        values = forms(record, *required)
        if values:
            g[key] = values[0]
    if pos == 'NOUN':
        gender = next((t for t in ['masculine', 'feminine', 'neuter'] if t in tags), None)
        if gender is None:
            match = re.search(r'^\S+ (m|f|n)\b', heads)
            if match:
                gender = {'m': 'masculine', 'f': 'feminine', 'n': 'neuter'}[match[1]]
        if gender:
            g['gender'] = gender
            g['article'] = {'masculine': 'der', 'feminine': 'die', 'neuter': 'das'}[gender]
        g['singular'] = lemma
        plurals = forms(record, 'plural')
        # Headword forms occur before the full declension table.
        plurals = [f for f in plurals if ' ' not in f and f not in {'de-ndecl'}]
        if 'plural-only' in tags:
            g.update(gender='plural_only', article='die', singular='not_applicable', plural=lemma, pluralStatus='plural_only')
        elif plurals:
            g.update(plural=plurals[0], pluralStatus='has_plural')
        elif 'uncountable' in tags or 'no plural' in heads:
            g.update(plural='not_applicable', pluralStatus='no_plural')
        g['weakNoun'] = str('weak' in tags or '(weak' in heads).lower()
    elif pos == 'VERB':
        first('present3sg', 'present', 'singular', 'third-person')
        first('preterite', 'past')
        if not g.get('preterite'):
            first('preterite', 'preterite', 'indicative', 'singular', 'third-person')
        first('participle2', 'participle', 'past')
        aux = list(dict.fromkeys(f for f in forms(record, 'auxiliary') if f in {'haben', 'sein'}))
        if aux:
            g['auxiliary'] = '|'.join(a for a in ['haben', 'sein'] if a in aux)
        present = g.get('present3sg', '')
        prefix = present.split()[-1] if ' ' in present else None
        if prefix and lemma.startswith(prefix):
            g['separablePrefix'] = prefix
        elif present:
            g['separablePrefix'] = 'not_applicable'
        g['reflexive'] = 'required' if '(sich)' in source_word else 'optional' if 'reflexive' in tags else 'no'
        valency = sorted(tags & {'transitive', 'intransitive', 'reflexive'})
        if valency:
            g['valency'] = ', '.join(valency)
    elif pos == 'ADJECTIVE':
        first('comparative', 'comparative')
        first('superlative', 'superlative')
        if g.get('comparative') and g.get('superlative'):
            g['gradable'] = 'true'
        elif 'not-comparable' in tags or 'not comparable' in heads:
            g.update(gradable='false', comparative='not_applicable', superlative='not_applicable')
        elif not g:
            form_of = next((s['form_of'][0]['word'] for s in senses if s.get('form_of')), None)
            if form_of:
                g.update(gradable='false', comparative='not_applicable', superlative='not_applicable', spellingNote=f'Inflected or comparison form of {form_of}.')
            elif lemma in {'online', 'egal', 'schade', 'Hamburger', 'extra', 'andere', 'weitere', 'nächste', 'allein', 'beste', 'meiste', 'innere', 'rechte', 'linke', 'obere', 'mittlere', 'untere'}:
                g.update(gradable='false', comparative='not_applicable', superlative='not_applicable', spellingNote='This corpus headword is normally used without comparison.')
    elif pos == 'PREPOSITION':
        # Wiktextract records governed cases in sense-level grammatical tags or raw glosses.
        cases = tags & CASES
        if not cases:
            raw = ' '.join(s for sense in senses for s in sense.get('raw_glosses', []))
            cases = {case for case in CASES if re.search(r'\+\s*' + case, raw)}
        if cases:
            g['governedCases'] = '|'.join(c for c in ['accusative', 'dative', 'genitive'] if c in cases)
        if lemma in PREPOSITIONS:
            g['governedCases'] = PREPOSITIONS[lemma]
    elif pos in {'PRONOUN', 'DETERMINER', 'ARTICLE'}:
        values = list(dict.fromkeys(f['form'] for f in record.get('forms', []) if set(f.get('tags', [])) & {'nominative', 'accusative', 'dative', 'genitive'} and 'error-unrecognized-form' not in f.get('tags', [])))
        g['forms'] = ', '.join(values[:12]) if values else lemma
    return pos, g


def target(record, word, sentence):
    literal = target_tokens(record.get('word', canonical(word)), sentence) or target_tokens(word, sentence)
    if literal:
        return literal
    candidates = []
    for form in record.get('forms', []):
        tags = set(form.get('tags', []))
        if tags & {'alternative', 'archaic', 'obsolete', 'table-tags', 'inflection-template', 'class', 'auxiliary', 'error-unrecognized-form', 'multiword-construction', 'diminutive'}:
            continue
        value = form['form']
        if len(value) > len(sentence):
            continue
        tokens = value.split()
        if len(tokens) > 2:
            continue
        if len(tokens) == 2 and record.get('pos') != 'verb':
            continue
        matches = [re.search(r'(?<!\w)' + re.escape(t) + r'(?!\w)', sentence, re.IGNORECASE) for t in tokens]
        if all(matches):
            candidates.append([m.group() for m in matches])
    return max(candidates, key=lambda parts: sum(map(len, parts)), default=[])


def build(workbook, dictionary_path, translations_path, output):
    dictionary = json.loads(dictionary_path.read_text())
    translations = json.loads(translations_path.read_text())
    occurrence = collections.Counter()
    entries = {}
    for rank, word, sentence in read_rows(workbook):
        lemma = canonical(word, rank)
        records = dictionary.get(lemma, [])
        records = [r for r in records if r.get('pos') in POS and not all(s.get('form_of') for s in r.get('senses', [{}]))] or records
        preferred = records[min(occurrence[lemma], len(records)-1)] if records else {}
        # Prefer the POS whose attested inflection actually occurs in this example.
        record = max(records, key=lambda r: (sum(map(len, target(r, word, sentence))), r is preferred)) if records else {}
        occurrence[lemma] += 1
        pos, g = grammar(record, lemma, word)
        gloss = translations[word]
        # Short dictionary meanings improve isolated words that the translator leaves unchanged.
        if gloss.casefold() == word.casefold() or word in CORRECTIONS or len(records) > 1 or (word == 'Welle' and lemma == 'Weile'):
            gloss = next((s['glosses'][0] for s in record.get('senses', []) if s.get('glosses') and not s.get('form_of')), gloss)
        if lemma in MANUAL:
            pos, gloss, g = MANUAL[lemma]
        if lemma in NOUN_OVERRIDES:
            g.update(NOUN_OVERRIDES[lemma])
        if lemma == 'geboren':
            pos, gloss = 'ADJECTIVE', 'born'
            g = {'gradable': 'false', 'comparative': 'not_applicable', 'superlative': 'not_applicable', 'spellingNote': 'Past participle of gebären, also used adjectivally.'}
        gloss = GLOSS_OVERRIDES.get(lemma, gloss)
        text_de = sentence.replace('aufmedialem', 'auf medialem')
        targets = target(record, word, text_de)
        if not targets and lemma in TARGET_OVERRIDES:
            proposed = TARGET_OVERRIDES[lemma]
            if all(re.search(r'(?<!\w)' + re.escape(t) + r'(?!\w)', text_de) for t in proposed):
                targets = proposed
        entries[str(rank)] = {
            'sourceWord': word,
            'pos': pos, 'grammar': g, 'glossEn': gloss,
            'textDe': text_de, 'textEn': translations.get(sentence, ''), 'target': targets,
            'sourceUrl': 'https://en.wiktionary.org/wiki/' + quote(record['word']) + '#German' if record else '',
        }
    payload = {
        'status': 'draft',
        'workbookSha256': hashlib.sha256(workbook.read_bytes()).hexdigest(),
        'translationSource': 'Google Translate, automated German-to-English drafts, 2026-10-28',
        'grammarSource': 'Kaikki.org German Wiktextract export, 2026-10-25; English Wiktionary dump, 2026-10-01',
        'grammarSourceUrl': 'https://kaikki.org/dictionary/German/',
        'licenseUrl': 'https://en.wiktionary.org/wiki/Wiktionary:Copyrights',
        'entries': entries,
    }
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + '\n')
    print('Draft enrichment:', len(entries), 'POS:', dict(collections.Counter(e['pos'] for e in entries.values())), 'targets:', sum(bool(e['target']) for e in entries.values()))


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('workbook', type=Path)
    p.add_argument('dictionary', type=Path)
    p.add_argument('translations', type=Path)
    p.add_argument('output', type=Path)
    args = p.parse_args()
    build(args.workbook, args.dictionary, args.translations, args.output)
