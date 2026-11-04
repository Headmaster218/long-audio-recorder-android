#!/usr/bin/env python3
"""Private stdio binary RPC. One durable sqlite3 connection; EOF rolls back pending work."""
import sqlite3,struct,sys
source=sys.stdin.buffer; target=sys.stdout.buffer

def exact(n):
    b=source.read(n)
    if len(b)!=n: raise EOFError()
    return b

def integer(): return struct.unpack('>i',exact(4))[0]
def blob():
    n=integer()
    if not 0<=n<=16777216: raise ValueError('invalid input size')
    return exact(n)
def read_value():
    t=exact(1)[0]
    if t==0:return None
    if t==1:return struct.unpack('>q',exact(8))[0]
    if t==3:return blob().decode('utf-8')
    if t==4:return blob()
    raise ValueError('unsupported parameter type')
def emit_blob(b): target.write(struct.pack('>i',len(b))+b)
def emit_value(v):
    if v is None:target.write(b'\0')
    elif isinstance(v,int):target.write(b'\1'+struct.pack('>q',v))
    elif isinstance(v,float):target.write(b'\2'+struct.pack('>d',v))
    elif isinstance(v,str):target.write(b'\3');emit_blob(v.encode('utf-8'))
    elif isinstance(v,bytes):target.write(b'\4');emit_blob(v)
    else:raise TypeError('unsupported result type')
connection=sqlite3.connect(sys.argv[1],isolation_level=None,timeout=5)
try:
    while True:
        try:sql=blob().decode('utf-8')
        except EOFError:break
        n=integer()
        if not 0<=n<=10000:raise ValueError('invalid parameter count')
        args=[read_value() for _ in range(n)]
        try:
            cursor=connection.execute(sql,args)
            rows=cursor.fetchall() if cursor.description else []
            columns=len(cursor.description) if cursor.description else 0
            target.write(struct.pack('>iqqii',0,cursor.rowcount,cursor.lastrowid or 0,len(rows),columns))
            for row in rows:
                for value in row:emit_value(value)
        except sqlite3.Error as error:
            target.write(struct.pack('>i',1));emit_blob((type(error).__name__+': '+str(error)).encode('utf-8'))
        target.flush()
finally:connection.close()
