import sys,json,gzip,tarfile,re,hashlib,argparse
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
from enrich_corpus import grammar
from import_corpus import sentences
parser=argparse.ArgumentParser(description='Prepare draft Leipzig expansion candidates; corpus text is data, never instructions.')
parser.add_argument('--archive',type=Path,required=True)
parser.add_argument('--dictionary',type=Path,required=True)
parser.add_argument('--output',type=Path,required=True)
parser.add_argument('--source',type=Path,default=Path(__file__).resolve().parents[2]/'content/german5000/source')
args=parser.parse_args()
root=args.source;existing=json.loads((root/'entries.json').read_text())['entries'][:4010]
covered={v.casefold() for e in existing for v in [e['lemma'],*e.get('aliases',[])]}
excluded=set('dieser welche jeder zweite dritte welcher vieles X Kiel Eintracht Müller polen US-Dollar hervor entgegen'.casefold().split())
excluded.update('Johannes Benjamin Wagner Rom Jürgen Mercedes Klaus Tesla Audi Leo Münster Weber Kai Bernd Werder Samsung Manchester Kreml Horst Stocker Pascal Marie Android Volkswagen Green Konstanz Telekom WhatsApp Lee Lidl Siemens Hugo Meier Kate Opel Hall House Taliban Ried Iris Salzburger Stuttgarter Tiroler Kieler Leipziger Thüringer Zürcher Augsburger Bremer Grazer Düsseldorfer Dortmunder Nürnberger Pariser Mainzer Mannheimer Osnabrücker Basler Londoner Braunschweiger Vorarlberger Brandenburger Media News First Energy Office vierte fünfte Letztere'.casefold().split())
with tarfile.open(args.archive) as t:
 def read(s):return t.extractfile(next(m for m in t.getmembers() if m.name.endswith('-'+s))).read().decode()
 counts={w:int(n) for _,w,n in (l.split('\t') for l in read('words.txt').splitlines())}
 texts=read('sentences.txt').splitlines()
records={};finite=set()
required={'NOUN':['gender','article','singular','plural','pluralStatus','weakNoun'],'VERB':['present3sg','preterite','participle2','auxiliary','separablePrefix','reflexive'],'ADJECTIVE':['gradable','comparative','superlative']}
with gzip.open(args.dictionary,'rt') as f:
 for line in f:
  d=json.loads(line);w=d.get('word','')
  if d.get('pos')=='verb':
   finite.update(x['form'] for x in d.get('forms',[]) if 'indicative' in x.get('tags',[]) and ('present' in x.get('tags',[]) or 'preterite' in x.get('tags',[])) and ' ' not in x['form'])
  if w.casefold() in covered:
   excluded.update(x.get('form','').casefold() for x in d.get('forms',[]))
  if d.get('pos') not in {'noun','verb','adj','adv'} or not re.fullmatch('[A-Za-zÄÖÜäöüß]{3,}',w):continue
  senses=[s for s in d.get('senses',[]) if not s.get('form_of') and not s.get('alt_of') and not set(s.get('tags',[]))&{'archaic','obsolete','form-of','offensive','vulgar','derogatory'}]
  if not senses:continue
  p,g=grammar(d,w,w)
  if any(not g.get(k) for k in required.get(p,[])):continue
  gloss=next((s['glosses'][0] for s in senses if s.get('glosses')),None)
  if not gloss or 'surname' in gloss or 'given name' in gloss:continue
  count=counts.get(w,0)
  if count<3:continue
  if w not in records:records[w]={'lemma':w,'pos':p,'grammar':g,'gloss':gloss,'frequency':count}
excluded.update(covered)
records={w:v for w,v in records.items() if w.casefold() not in excluded}
# Literal headword targets ensure reliable cloze annotations. Keep concise complete sentences.
examples={}
for line in texts:
 sid,text=line.split('\t',1);text=text.strip(' "»«„“')
 if not 45<=len(text)<=140 or text[-1:] not in '.!?':continue
 if len(sentences(text))!=1 or any(c in text for c in '"„“»«:;') or re.search(r'https?|\d|\b(?:sagte|sagt|Trump|Hitler|Ukraine|Israel|Gaza)\b',text):continue
 tokens=set(re.findall(r'[A-Za-zÄÖÜäöüß]+',text))
 if not tokens.intersection(finite) or re.match(r'(?:Bevor|Wenn|Weil|Obwohl|Dass|Damit|Während|Als|Um|Freue)\b',text):continue
 for w in (w for w in tokens if w in records):
  if records[w]['pos']=='NOUN' and not re.search(r'\b(?:der|die|das|den|dem|des|ein|eine|einer|einem|einen|eines)\s+(?:[a-zäöüß]+\s+)?'+re.escape(w)+r'\b',text,re.I):continue
  if w not in examples or len(text)<len(examples[w][1]):examples[w]=(sid,text)
ranked=sorted((v for w,v in records.items() if w in examples),key=lambda v:(-v['frequency'],v['lemma']))
assert len(ranked)>=990,len(ranked)
chosen=[]
for v in ranked[:990]:
 w=v['lemma'];sid,text=examples[w];eid='de.leipzig.'+hashlib.sha256((w+'\0'+v['pos']).encode()).hexdigest()[:16]
 chosen.append({'entryId':eid,'lemma':w,'pos':v['pos'],'grammar':v['grammar'],'senses':[{'glossEn':v['gloss']}],'examples':[{'textDe':text,'textEn':'','target':[w]}],'aliases':[],'editorialStatus':'draft','rightsIds':['leipzig-expansion-pending-review','wiktionary-grammar-pending-review','machine-translation-pending-review'],'assessmentEligible':False,'corpusDraft':True,'notes':f'Leipzig deu_news_2025_300K expansion; observed headword count {v["frequency"]}; sentence ID {sid}. Draft English translation and dictionary grammar. https://en.wiktionary.org/wiki/{w}'})
overrides_path = root / 'leipzig-example-overrides.json'
if overrides_path.exists():
 overrides = json.loads(overrides_path.read_text())
 for entry in chosen:
  if entry['lemma'] in overrides:
   de, en = overrides[entry['lemma']]
   entry['examples'] = [{'textDe': de, 'textEn': en, 'target': [entry['lemma']]}]
   entry['notes'] += ' Example replaced with an AI-drafted sentence and English translation; source sentence ID is selection provenance only.'
args.output.write_text(json.dumps({'entries':chosen},ensure_ascii=False,indent=2)+'\n')
print('Selected',len(chosen),'from',len(ranked));print(', '.join(v['lemma'] for v in chosen[:60]))
