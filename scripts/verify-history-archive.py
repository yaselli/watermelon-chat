"""Exercise additive archive-evidence migration and production scope guards in SQLite."""
import json
import re
import sqlite3
from pathlib import Path
root = Path(__file__).resolve().parents[1]
source = root / 'app/src/main/java/me/rerere/rikkahub'
schema = json.loads((root / 'app/schemas/me.rerere.rikkahub.data.db.AppDatabase/43.json').read_text())['database']
db = sqlite3.connect(':memory:')
db.execute('PRAGMA foreign_keys=ON')
for entity in schema['entities']:
    if 'ftsOptions' in entity:
        continue
    db.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
    for index in entity.get('indices', []):
        db.execute(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))
db.execute("INSERT INTO conversationentity(id,assistant_id,title,nodes,create_at,update_at) VALUES('chat','a','','[]',1,2)")
db.execute("INSERT INTO memoryentity(id,assistant_id,content,source_kind) VALUES(8,'a','memory','automatic')")
db.execute("INSERT INTO memory_evidence VALUES(8,'a','chat','node','message','hash','user','date','quote')")
db.execute("INSERT INTO message_node VALUES('node','chat',0,'[]',0)")
before = db.execute('SELECT * FROM memory_evidence').fetchone()
migration = (source / 'data/db/migrations/Migration_43_44.kt').read_text()
for sql in re.findall(r'db\.execSQL\("([^"\n]+)"\)', migration):
    db.execute(sql)
assert db.execute('SELECT * FROM memory_evidence').fetchone() == before + (None,)
assert db.execute('PRAGMA foreign_key_check').fetchall() == []
# Execute the actual DAO queries, not a hand-written approximation.
dao = (source / 'data/memory/MemoryOrganizationStorage.kt').read_text()
queries = re.findall(r'@Query\((.*?)\)\s+suspend fun (\w+)', dao, re.S)
queries = {name: ''.join(re.findall(r'"([^"\n]*)"', query)) for query, name in queries}
args = dict(owner='a', conversationId='chat', nodeId='node', messageId='message', hash='hash', source='retained original')
db.execute(queries['retainSource'], dict(args, hash='wrong'))
assert db.execute('SELECT archivedSource FROM memory_evidence').fetchone() == (None,)
db.commit()
# A failure during deletion rolls evidence preservation back with the deletion.
db.execute('BEGIN')
db.execute(queries['retainSource'], args)
db.execute("DELETE FROM message_node WHERE id='node'")
db.rollback()
assert db.execute('SELECT COUNT(*) FROM message_node').fetchone() == (1,)
assert db.execute('SELECT archivedSource FROM memory_evidence').fetchone() == (None,)
with db:
    db.execute(queries['retainSource'], args)
    db.execute("DELETE FROM message_node WHERE id='node'")
assert db.execute(queries['archivedEvidence'], dict(scope='a', nodeIds='node')).fetchone()[-1] == 'retained original'
assert db.execute(queries['archivedEvidence'], dict(scope='b', nodeIds='node')).fetchone() is None
# Moving a conversation to another assistant cannot leak a retained original across roles.
db.execute("UPDATE conversationentity SET assistant_id='b' WHERE id='chat'")
assert db.execute(queries['archivedEvidence'], dict(scope='a', nodeIds='node')).fetchone() is None
db.execute('DELETE FROM memoryentity WHERE id=8')
assert db.execute('SELECT COUNT(*) FROM memory_evidence').fetchone() == (0,)
# When available, compare the new column metadata to the compiler-exported Room schema.
compiled = root / 'app/schemas/me.rerere.rikkahub.data.db.AppDatabase/44.json'
if compiled.exists():
    table = next(e for e in json.loads(compiled.read_text())['database']['entities'] if e['tableName'] == 'memory_evidence')
    column = next(f for f in table['fields'] if f['columnName'] == 'archivedSource')
    assert column['affinity'] == 'TEXT' and column.get('notNull', False) is False and column['defaultValue'] == 'NULL'
print('PASS: v43->44 preserves existing evidence; exact DAO hash/scope guards, atomic rollback and memory deletion cascade')
